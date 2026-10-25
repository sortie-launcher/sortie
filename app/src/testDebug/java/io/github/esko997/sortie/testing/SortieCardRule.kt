package io.github.esko997.sortie.testing

import androidx.test.core.app.ApplicationProvider
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.magazine.CATALOG
import io.github.esko997.sortie.data.magazine.parseCatalog
import io.github.esko997.sortie.ui.Selection
import io.github.esko997.sortie.ui.uiNow
import java.io.File
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.robolectric.shadows.ShadowEnvironment

/**
 * Points a fresh Robolectric [SortieApp] at [FixtureCard] before anything touches its graph, so the activity under test runs the
 * real stores, Room database (Robolectric's native SQLite), indexer and view model over the fixture. Order it before the compose
 * rule (`@get:Rule(order = 0)`): the graph binds to the card directory once, on first use.
 *
 * The app-private side is a returning user's: a Hangar save (`fixtures/app/mecha-state.json`, motion off so sprites hold
 * still). Process-wide UI state ([Selection]) starts empty, as in a new process; Robolectric reuses one JVM for many tests.
 *
 * Also fixes what would otherwise differ between runs or machines: the displayed wall clock ([NOW], UTC) and the all-files
 * grant. Builds made with -PsortieMagazinesDir bundle the real Field Notes issues in the APK's assets; [hideBundledMagazines]
 * keeps them off the shelf so every build sees only the fixture's card issues. Image loads are counted ([ImageLoads]).
 */
class SortieCardRule : ExternalResource() {
    private val tmp = TemporaryFolder()
    private var zone: TimeZone? = null
    private var clock: (() -> Long)? = null

    lateinit var sortieDir: File
        private set

    @OptIn(ExperimentalCoroutinesApi::class) // resetReplayCache
    override fun before() {
        // a real activity Context, as on a device: Sortie asks its activities for their display (Activity.getDisplay)
        System.setProperty("robolectric.createActivityContexts", "true")
        tmp.create()
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        clock = uiNow
        uiNow = { NOW }
        val app = ApplicationProvider.getApplicationContext<SortieApp>()
        sortieDir = FixtureCard.writeTo(tmp.newFolder("card"))
        SortieApp.pinSortieDir(app, sortieDir)
        hideBundledMagazines(app)
        val mecha = checkNotNull(javaClass.classLoader!!.getResource("fixtures/app/mecha-state.json")).readText()
        File(app.filesDir, "mecha").apply { mkdirs() }.resolve("state.json").writeText(mecha)
        Selection.set(null)
        Selection.heroPalette.value = null
        Selection.heroes.value = emptyList()
        Selection.openRoute.resetReplayCache()
        ImageLoads.install(app)
        // the all-files grant: Environment.isExternalStorageManager() needs a primary volume, and Robolectric's AppOpsManager
        // allows every op it was not told otherwise
        ShadowEnvironment.addExternalDir(tmp.newFolder("storage").path)
    }

    override fun after() {
        clock?.let { uiNow = it }
        zone?.let(TimeZone::setDefault)
        tmp.delete()
    }

    /**
     * BundledMagazines extracts the APK's catalog once per digest into files/magazines-bundled/<digest>/ and treats a folder
     * with its completion marker as done. An empty, completed snapshot is therefore an empty bundled shelf.
     */
    private fun hideBundledMagazines(app: SortieApp) {
        val catalog = try {
            app.assets.open("magazines/$CATALOG").use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            return // a build without magazines
        }
        val digest = checkNotNull(parseCatalog(catalog)) { "the bundled magazine catalog is malformed" }.digest
        File(app.filesDir, "magazines-bundled/$digest").apply { mkdirs() }.resolve(".complete").writeText("")
    }

    companion object {
        /** 2026-10-25T09:41:00Z: what the UI shows as "now". */
        const val NOW = 1_792_921_260_000L
    }
}
