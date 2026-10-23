package io.github.esko997.sortie

import android.app.Application
import android.os.StrictMode
import io.github.esko997.sortie.data.apps.AppsRepository
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.folders.FolderRepository
import io.github.esko997.sortie.data.index.DeviceState
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import io.github.esko997.sortie.launch.LaunchManager
import io.github.esko997.sortie.shell.ScreenMover
import java.io.File

/** Hand-rolled service locator; small enough that a DI framework would be more code than it saves. */
class SortieApp : Application() {
    val graph: Graph by lazy { Graph(this) }

    class Graph(app: SortieApp) {
        val sortieDir: File = locateSortieDir(app)
        val manifests = ManifestStore(sortieDir)
        val db = SortieDb.open(app)
        val user = UserStore(File(sortieDir, "user.json"))
        val playLog = PlayLog(db.games(), File(sortieDir, "playlog.jsonl"), manifests.snapshot.value.device.device)
        val indexer = Indexer(db.games(), manifests)
        val launcher = LaunchManager(app, manifests, user, playLog)
        val folders = FolderRepository(db, manifests, user.data)
        val deviceState = DeviceState(manifests)
        val screenMover = ScreenMover(app)
        val apps = AppsRepository(app)
    }

    override fun onCreate() {
        super.onCreate()
        // Some launch targets only accept file:// URIs. Android forbids exposing them unless the app opts out of the
        // StrictMode check, which is what launcher frontends do. Keep everything else in the default policy.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }

    companion object {
        /** The card root is not known until device.json is read, so look for `sortie/device.json` on every mounted volume. */
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
