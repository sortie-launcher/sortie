package io.github.esko997.sortie

import android.app.Application
import android.os.Environment
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import io.github.esko997.sortie.launch.LaunchManager
import java.io.File

/** Hand-rolled service locator; small enough that a DI framework would be more code than it saves. */
class SortieApp : Application() {
    lateinit var graph: Graph
        private set

    class Graph(app: SortieApp) {
        val sortieDir: File = locateSortieDir(app)
        val manifests = ManifestStore(sortieDir)
        val db = SortieDb.open(app)
        val user = UserStore(File(sortieDir, "user.json"))
        val playLog = PlayLog(db.games(), File(sortieDir, "playlog.jsonl"), manifests.snapshot.value.device.device)
        val indexer = Indexer(db.games(), manifests)
        val launcher = LaunchManager(app, manifests, user, playLog)
    }

    override fun onCreate() {
        super.onCreate()
        // Many emulators only accept file:// URIs (PPSSPP, Mupen64Plus FZ, ...). Android forbids exposing them unless the
        // app opts out of the StrictMode check, which is what every launcher frontend does.
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        graph = Graph(this)
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
                add(File(Environment.getExternalStorageDirectory(), "sortie"))
                File("/storage").listFiles()?.forEach { add(File(it, "sortie")) }
            }
            return candidates.firstOrNull { File(it, "device.json").isFile } ?: candidates.first()
        }
    }
}
