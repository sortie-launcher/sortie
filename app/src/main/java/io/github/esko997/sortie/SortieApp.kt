package io.github.esko997.sortie

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import io.github.esko997.sortie.data.apps.AppsRepository
import io.github.esko997.sortie.data.apps.ShortcutsRepository
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.folders.FolderRepository
import io.github.esko997.sortie.data.index.DeviceState
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import io.github.esko997.sortie.launch.LaunchManager
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.TaskSwitcher
import io.github.esko997.sortie.ui.SwitcherHost
import java.io.File

/** Hand-rolled service locator; small enough that a DI framework would be more code than it saves. */
class SortieApp :
    Application(),
    SingletonImageLoader.Factory {
    val graph: Graph by lazy { Graph(this) }

    class Graph(val app: SortieApp) {
        val sortieDir: File = locateSortieDir(app)
        val manifests = ManifestStore(sortieDir)
        val db = SortieDb.open(app)
        val user = UserStore(File(sortieDir, "user.json"))
        val playLog = PlayLog(db.games(), File(sortieDir, "playlog.jsonl"), manifests.snapshot.value.device.device)
        val indexer = Indexer(db.games(), manifests)
        val launcher = LaunchManager(app, manifests, user, playLog)
        val deviceState = DeviceState(manifests)
        val screenMover = ScreenMover(app)
        val switcher = TaskSwitcher(app, screenMover) { manifests.snapshot.value.device.screens.presentationIndex }
        val apps = AppsRepository(app)
        val shortcuts = ShortcutsRepository(app)
        val folders = FolderRepository(db, manifests, user.data, apps.apps, shortcuts.shortcuts)

        init {
            SwitcherHost(this).install() // overlay switcher when the draw-over-apps grant exists; activity route otherwise
        }
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
        /** The card root is not known until device.json is read, so look for `sortie/device.json` on every mounted volume. */
        /**
         * The card directory the running process is bound to may be wrong on the very first run: before the all-files grant an
         * SD card's `device.json` is unreadable, so [locateSortieDir] falls back to primary storage. The stores bind to one
         * directory for the life of the process, so once the grant exists and discovery disagrees, the process restarts
         * (MainActivity relaunches via an alarm; as the home app it comes back anyway).
         */
        fun relocateIfNeeded(app: Application, graph: Graph): Boolean {
            if (locateSortieDir(app) == graph.sortieDir) return false
            Log.i("Sortie", "card directory changed after the storage grant; restarting")
            val restart = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 400, restart)
            Runtime.getRuntime().exit(0)
            return true
        }

        fun locateSortieDir(app: Application): File {
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
