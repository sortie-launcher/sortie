package io.github.esko997.sortie

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.StrictMode
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import io.github.esko997.sortie.data.apps.AppsRepository
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutsRepository
import io.github.esko997.sortie.data.backup.BackupManager
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.folders.FolderRepository
import io.github.esko997.sortie.data.index.DeviceState
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.magazine.BundledMagazines
import io.github.esko997.sortie.data.magazine.MagazineGames
import io.github.esko997.sortie.data.magazine.MagazineRepository
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.setup.LibrarySetupStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import io.github.esko997.sortie.launch.LaunchManager
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.PlaySession
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.TaskSwitcher
import io.github.esko997.sortie.stats.DeviceStatsMonitor
import io.github.esko997.sortie.ui.SwitcherHost
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.map

/** Hand-rolled service locator; small enough that a DI framework would be more code than it saves. */
class SortieApp :
    Application(),
    SingletonImageLoader.Factory {
    val graph: Graph by lazy { Graph(this) }

    class Graph(val app: SortieApp) {
        val sortieDir: File = locateSortieDir(app)

        /** The remembered library folder was missing when the stores below bound to it (see [needsRestart]). */
        private val boundWhileMissing = pinnedSortieDir(app) != null && !sortieDir.isDirectory
        val manifests = ManifestStore(sortieDir)
        val db = SortieDb.open(app)

        // never recreates a remembered folder that is missing: preferences then wait for it instead of starting an empty one
        val user = UserStore(File(sortieDir, "user.json"), mayCreateDirectory = { pinnedSortieDir(app) == null })
        val playLog = PlayLog(db.games(), File(sortieDir, "playlog.jsonl"), manifests.snapshot.value.device.device)
        val mecha = MechaStore(app) { from -> db.games().completedSessionsFrom(from).map { rows -> rows.map { it.toPlaySession() } } }
        val indexer = Indexer(db.games(), manifests)
        val magazines = MagazineRepository(sortieDir, BundledMagazines.forApp(app))
        val magazineGames = MagazineGames(db.games()) { manifests.snapshot.value.systems }

        /** Editor of the card's library manifests, shared by Library setup, imports and the launcher's remembered Open with… choice. */
        val librarySetup = LibrarySetupStore(sortieDir, manifests)
        val launcher = LaunchManager(app, manifests, user, playLog, librarySetup)
        val deviceState = DeviceState(manifests)
        val screenMover = ScreenMover(app)
        val switcher = TaskSwitcher(app, screenMover) { manifests.snapshot.value.device.screens.presentationIndex }
        val deviceStats = DeviceStatsMonitor(app, screenMover, { user.data.value.deviceStatsInterval }) { user.data.value.perfInterval }
        val apps = AppsRepository(app)
        val shortcuts = ShortcutsRepository(app)
        val folders = FolderRepository(db, manifests, user.data, apps.apps, shortcuts.shortcuts)
        val backup = BackupManager(
            app,
            db,
            user,
            playLog,
            sortieDir,
            deviceName = { manifests.snapshot.value.device.device },
            availableLaunchables = { apps.apps.value.mapTo(HashSet()) { it.id } + shortcuts.shortcuts.value.map { it.id } }
        )

        init {
            SwitcherHost(this).install() // overlay switcher when the draw-over-apps grant exists; activity route otherwise
        }

        /**
         * True when storage that was unavailable as this process started is back: the remembered folder is mounted or restored,
         * or a user.json that could not be read then now reads. Every store bound to what it found at startup, so only a
         * [restart] loads it all. Touches the disk.
         */
        fun needsRestart(): Boolean = (boundWhileMissing && sortieDir.isDirectory) || user.reloadable()
    }

    override fun onCreate() {
        super.onCreate()
        // Some launch targets only accept file:// URIs. Android forbids exposing them unless the app opts out of the
        // StrictMode check, which is what launcher frontends do. Keep everything else in the default policy.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }

    /**
     * Coil's process-wide loader. Covers are local files, so a disk cache would only copy the SD card into app storage: off.
     * The memory cache is a fixed 96 MB rather than a share of the heap: decodes land in hardware bitmaps that live in graphics
     * memory, so a percentage of the Dalvik class heap measures the wrong pool and varies by device. 96 MB holds roughly 170
     * INEXACT-decoded grid covers (~550 KB each at ~300x450 ARGB), about eight screens of back-scroll, and is under 1% of the
     * Thor's 12 GB. Crossfade is off so a loaded image costs one frame, not an animation.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(96L * 1024 * 1024).build() }
        .diskCache(null)
        .crossfade(false)
        .build()

    companion object {
        /**
         * The card directory the running process is bound to may be wrong on the very first run: before the all-files grant an
         * SD card's `device.json` is unreadable, so [locateSortieDir] falls back to primary storage. Likewise a remembered folder
         * may be missing at startup (card not mounted yet, folder deleted) and come back later. The stores bind to one directory
         * and read it once for the life of the process, so once the grant exists and discovery disagrees, or that storage is back
         * ([Graph.needsRestart]), the process is restarted through [RestartActivity], which runs in its own process, kills this
         * one and starts MainActivity again (no alarms, works whether or not Sortie is the home app).
         */
        fun relocateIfNeeded(activity: Activity, graph: Graph): Boolean {
            if (locateSortieDir(activity.application) == graph.sortieDir && !graph.needsRestart()) return false
            Log.i("Sortie", "card directory changed or came back; restarting")
            restart(activity)
            return true
        }

        /** Hands this process's pid to [RestartActivity] and closes every activity; the new process starts MainActivity. */
        fun restart(activity: Activity) {
            activity.startActivity(
                Intent(activity, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(RestartActivity.EXTRA_PID, Process.myPid())
            )
            activity.finishAffinity()
        }

        private const val LOCATION_PREFS = "sortie_location"
        private const val PINNED_DIR = "sortieDir"

        /**
         * Remembers [directory] (the card's `sortie` folder that on-device library setup just saved to, or the current one) in
         * app-private preferences so [locateSortieDir] keeps using it. Synchronous disk write: call off the main thread.
         * Throws [IOException] when the preference could not be written.
         */
        fun pinSortieDir(app: Application, directory: File) {
            val ok = app.getSharedPreferences(LOCATION_PREFS, Context.MODE_PRIVATE).edit()
                .putString(PINNED_DIR, directory.absoluteFile.normalize().path).commit()
            if (!ok) throw IOException("Could not remember the library location ${directory.path}")
        }

        /** The directory [pinSortieDir] remembered, whether or not it is currently mounted; null before any setup save. */
        fun pinnedSortieDir(app: Application): File? =
            app.getSharedPreferences(LOCATION_PREFS, Context.MODE_PRIVATE).getString(PINNED_DIR, null)?.takeIf { it.isNotEmpty() }?.let(::File)

        /**
         * A pinned directory always wins, even while its card is missing: falling through to another volume would show an
         * empty or different library as if personal data were lost. Unpinned (desktop-prepared cards), look for
         * `sortie/device.json` on every mounted volume, since the card root is not known until device.json is read.
         */
        fun locateSortieDir(app: Application): File {
            pinnedSortieDir(app)?.let { return it }
            val candidates = buildList {
                app.getExternalFilesDirs(null).filterNotNull().forEach { d ->
                    // .../Android/data/<pkg>/files -> volume root
                    var p: File? = d
                    repeat(4) { p = p?.parentFile }
                    p?.let { add(File(it, "sortie")) }
                }
                File("/storage").listFiles()?.filter { it.name !in setOf("emulated", "self") }?.forEach { add(File(it, "sortie")) }
            }
            return candidates.firstOrNull { File(it, "device.json").isFile } ?: candidates.first()
        }
    }
}

/** The companion's view of a recorded session: games only earn credit, never apps or shortcuts ([LibraryItem.isGameId]). */
private fun SessionEntity.toPlaySession() = PlaySession(gameId, startedAt, endedAt, abandoned, isGame = LibraryItem.isGameId(gameId))
