package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.LibraryRules
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.setup.LibraryConfiguration
import io.github.esko997.sortie.data.setup.LibraryPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CocoonPlannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var card: File
    private lateinit var cocoon: File

    private fun files(path: String, vararg names: String): File = File(card, path).apply {
        mkdirs()
        names.forEach { File(this, it).writeText("x") }
    }

    /** A Cocoon artwork reference on internal storage; the planner's injected `exists` treats every such file as present. */
    private fun art(platform: String, kind: String, name: String) =
        "content://com.android.externalstorage.documents/tree/primary%3ACocoon/document/primary%3ACocoon%2Fdownloaded_media%2F$platform%2F$kind%2F" +
            name.replace(" ", "%20").replace("'", "%27")

    private fun game(file: String, favourite: Boolean = false, hidden: Boolean = false, playCount: Int = 0, boxArt: String? = null, logo: String? = null) =
        CocoonGame(file, null, favourite, hidden, playCount, null, 0, boxArt, logo, null)

    private fun platform(id: String, player: String?, vararg games: CocoonGame) =
        CocoonPlatform(id, player, games.toList(), File(cocoon, "gamelists/$id/gamelist.xml"))

    private val config = LibraryConfiguration(
        systems = listOf(
            SystemDef(id = "pcengine", label = "PC Engine", roots = listOf("roms/pcengine"), profiles = listOf("ra-mednafen_pce")),
            SystemDef(id = "genesis", label = "Genesis", roots = listOf("roms/genesis"), media = "media/genesis", profiles = listOf("ra-genesis_plus_gx"))
        ),
        profiles = listOf(LibraryPresets.retroArch("mednafen_pce", "Beetle PCE"), LibraryPresets.retroArch("genesis_plus_gx", "Genesis Plus GX")),
        fingerprint = "f",
        errors = emptyList(),
        exists = true,
        biosDir = null,
        saves = emptyList()
    )

    @Before
    fun card() {
        card = tmp.newFolder("card")
        cocoon = File(card, "Cocoon")
        files("Cocoon/downloaded_media/gba", "Metroid Fusion (USA).gba")
        files("roms/pcengine", "Bonk's Adventure (USA).pce", "Twin.pce", "Dup.pce")
        files("roms/tg16", "Star Patrol (USA).pce", "twin.pce", "Dup.pce")
        files("roms/genesis", "Sonic (USA).md", "Gone.pce")
        files("roms/gba", "Metroid Fusion (USA).gba", "Existing (USA).gba")
        files("roms/cps2", "skyfleet.7z", "brickout.zip")
        files("scraped-esde/downloaded_media/gba/covers", "Existing (USA).png")
        files("scraped-esde/gamelists/gba", "gamelist.xml")
    }

    private val library: CocoonLibrary
        get() = CocoonLibrary(
            cocoon,
            listOf(
                platform(
                    "tg16",
                    "tg16.ra.mednafen_pce",
                    game(
                        "Bonk's Adventure (USA).pce",
                        favourite = true,
                        playCount = 2,
                        boxArt = art("tg16", "icon", "Bonk's Adventure (USA)__cocoon_edit_1_ab.jpg")
                    ),
                    game("star patrol (usa).pce", hidden = true),
                    game("Twin.pce"),
                    game("Dup.pce"),
                    game("Gone.pce")
                ),
                platform("genesis", "genesis.ra.genesis_plus_gx", game("Sonic (USA).md", boxArt = art("genesis", "icon", "Sonic (USA)__cocoon_edit_2_cd.png"))),
                platform(
                    "gba",
                    "gba.ra.mgba",
                    game(
                        "Metroid Fusion (USA).gba",
                        boxArt = art("gba", "icon", "Metroid Fusion (USA, Australia)__cocoon_edit_3_aaaa04.jpg"),
                        logo = "content://com.android.externalstorage.documents/tree/9999-0000%3Ascraped-esde/document/" +
                            "9999-0000%3Ascraped-esde%2Fgba%2Flogo.png"
                    ),
                    game("Existing (USA).gba", boxArt = art("gba", "icon", "Existing__cocoon_edit_3_ef.jpg"))
                ),
                platform("cps2", "cps2.ra.fbneo", game("skyfleet.7z"), game("brickout.zip")),
                platform("switch", null, game("example_switch_game.nsp")),
                platform(
                    "shortcut",
                    null,
                    CocoonGame("pinned_com_droiddeck_launcher_game_100001", "Example Quest", false, false, 0, null, 0, null, null, null)
                ),
                platform("pod", null, game("silk"))
            ),
            emptyList()
        )

    private val roots: Map<String, List<File>>
        get() = mapOf(
            "tg16" to listOf(File(card, "roms/pcengine"), File(card, "roms/tg16")),
            "genesis" to listOf(File(card, "roms/genesis")),
            "gba" to listOf(File(card, "roms/gba"), File(cocoon, "downloaded_media/gba")),
            "cps2" to listOf(File(card, "roms/cps2"))
        )

    private fun plan(backup: CocoonBackup? = null, library: CocoonLibrary = this.library, installed: Set<String> = setOf("com.retroarch")): CocoonImportPlan {
        val planner = CocoonPlanner(
            cardRoot = card,
            sortieDir = File(card, "sortie"),
            config = config,
            installed = { it in installed },
            existingMedia = { system, kind, stem -> system == "genesis" && kind == "covers" && stem == "Sonic (USA)" },
            exists = { it.path.startsWith("/storage/emulated/0/Cocoon/") || it.isFile },
            length = { if (it.path.startsWith("/storage/")) 1234L else it.length() }
        )
        val listings = RootProbe(listOf(card), exclude = listOf(cocoon)).listings()
        return planner.plan(library, backup, roots, listings)
    }

    @Test
    fun `matches within the alias group, case-insensitively, preferring exact case`() {
        val p = plan()
        val ids = setOf(
            // pcengine: existing system owns roms/pcengine; tg16: only a case-insensitive hit; Twin: exact case beats tg16's twin.pce
            "pcengine/Bonk's Adventure (USA).pce",
            "tg16/Star Patrol (USA).pce",
            "pcengine/Twin.pce",
            "genesis/Sonic (USA).md",
            "gba/Metroid Fusion (USA).gba",
            "gba/Existing (USA).gba",
            "cps2/skyfleet.7z",
            "cps2/brickout.zip"
        )
        assertEquals(8, p.matched)
        assertEquals(listOf("pcengine/Bonk's Adventure (USA).pce"), p.favourites)
        assertEquals(listOf("tg16/Star Patrol (USA).pce"), p.hidden)
        assertTrue(p.artwork.map { it.gameId }.all { it in ids })
        // Gone.pce exists only in roms/genesis, outside tg16's group: missing, not guessed
        assertEquals(listOf("tg16/Gone.pce"), p.unmatched)
        assertEquals(listOf("tg16/Dup.pce (pcengine, tg16)"), p.ambiguous)
        assertEquals(1, p.playStats)
    }

    @Test
    fun `existing systems are reused and only unowned roots create systems`() {
        val p = plan()
        assertEquals(listOf("tg16", "gba", "cps2"), p.newSystems.map { it.system.id })
        val tg16 = p.newSystems[0]
        // the pcengine preset id is taken by the user's system, so tg16 keeps its own id
        assertEquals(listOf("roms/tg16"), tg16.system.roots)
        assertEquals("PC Engine / TurboGrafx-16 (tg16)", tg16.system.label)
        assertTrue(tg16.fromPreset)
        assertFalse("configured profiles are never rewritten", tg16.profiles.any { it.id == "ra-mednafen_pce" })
        assertTrue(p.notes.any { it.startsWith("tg16: your PC Engine system does not include") })

        // only new systems get an emulator, and it is the one they list first; the user's pcengine and genesis are untouched
        val choices = p.systemProfiles.associate { it.systemId to it.profileId }
        assertEquals(mapOf("tg16" to "ra-mednafen_pce", "gba" to "ra-mgba", "cps2" to "ra-fbneo"), choices)
        p.newSystems.forEach { assertEquals(choices[it.system.id], it.system.profiles.first()) }
        assertTrue(p.systemProfiles.all { it.installed && it.requestedProfileId == null })
        assertEquals(listOf("pod: Cocoon's own screens, not games", "switch: no ROM folder found (1 game)"), p.skippedPlatforms)
        assertEquals(listOf("Example Quest"), p.droidDeck)
    }

    @Test
    fun `a preset system takes the ES-DE media and gamelist when present`() {
        val gba = plan().newSystems.single { it.system.id == "gba" }
        assertTrue(gba.fromPreset)
        assertEquals("Game Boy Advance", gba.system.label)
        assertEquals(listOf("roms/gba"), gba.system.roots) // the root inside the Cocoon dir is dropped
        assertEquals("scraped-esde/downloaded_media/gba", gba.system.media)
        assertEquals("scraped-esde/gamelists/gba/gamelist.xml", gba.system.gamelist)
        assertEquals("ra-mgba", gba.system.profiles.first())
        assertTrue(gba.profiles.any { it.id == "ra-mgba" } && gba.profiles.any { it.id == "open-with" })
    }

    @Test
    fun `a platform without a preset becomes a custom system`() {
        val cps2 = plan().newSystems.single { it.system.id == "cps2" }
        assertFalse(cps2.fromPreset)
        assertEquals("CPS-2", cps2.system.label)
        assertEquals("other", cps2.system.generation)
        assertEquals(setOf(".7z", ".zip"), cps2.system.extensions.toSet())
        assertEquals(listOf("ra-fbneo"), cps2.system.profiles)
        assertEquals(listOf("ra-fbneo", "open-with"), cps2.profiles.map { it.id })
        assertEquals("sortie/media/cps2", cps2.system.media)
        assertNull(cps2.system.gamelist)
    }

    @Test
    fun `artwork is copied under the rom stem and skipped where Sortie has art`() {
        val p = plan()
        assertEquals(
            listOf(
                File(card, "sortie/media/pcengine/covers/Bonk's Adventure (USA).jpg"),
                File(card, "scraped-esde/downloaded_media/gba/covers/Metroid Fusion (USA).jpg")
            ),
            p.artwork.map { it.target }
        )
        assertEquals(
            File("/storage/emulated/0/Cocoon/downloaded_media/gba/icon/Metroid Fusion (USA, Australia)__cocoon_edit_3_aaaa04.jpg"),
            p.artwork[1].source
        )
        assertEquals(listOf("covers", "covers"), p.artwork.map { it.kind })
        assertEquals(2468L, p.artworkBytes)
        // the logo on an unmounted card is unavailable; Sonic's cover exists; Existing (USA) already has covers/Existing (USA).png
        assertEquals(1, p.missingVolume)
        assertEquals(mapOf("pcengine" to "sortie/media/pcengine"), p.mediaDirs)
    }

    @Test
    fun `without a backup there are no sessions, folders or overrides`() {
        val p = plan()
        assertNull(p.backup)
        assertTrue(p.sessions.isEmpty())
        assertTrue(p.folders.isEmpty())
        assertTrue(p.profileOverrides.isEmpty())
    }

    @Test
    fun `a backup adds folders, real sessions, overrides and app flags`() {
        val patrol = CocoonRef("tg16", "Star Patrol (USA).pce", null)
        val app = CocoonRef("android_app", null, "com.example.shmup")
        val backup = CocoonBackup(
            dir = tmp.newFolder("backup"),
            createdAt = 0L,
            appVersion = "3.07",
            folders = listOf(
                CocoonFolder(7, "Weekend Picks", listOf(patrol, CocoonRef("cps2", "skyfleet.7z", null), CocoonRef("shortcut", "Example Quest", null), app))
            ),
            sessions = listOf(
                CocoonSession(patrol, 100L, 200L, "com.retroarch"),
                CocoonSession(patrol, 100L, 250L, "com.retroarch"),
                CocoonSession(app, 300L, 400L, null)
            ),
            playerOverrides = mapOf(
                CocoonRef("gba", "Metroid Fusion (USA).gba", null) to "gba.ra64.mgba",
                CocoonRef("cps2", "brickout.zip", null) to "cps2.org.example.unknown"
            ),
            hidden = setOf(CocoonRef("android_app", null, "com.example.reader")),
            roots = emptyMap(),
            favourites = setOf(app)
        )
        val p = plan(backup)
        assertEquals(
            listOf(
                FolderPlan(
                    FolderDef(
                        id = "cocoon-7",
                        label = "Weekend Picks",
                        include = listOf("tg16/Star Patrol (USA).pce", "cps2/skyfleet.7z", "app:com.example.shmup")
                    ),
                    1
                )
            ),
            p.folders
        )
        assertEquals(
            listOf(SessionPlan("tg16/Star Patrol (USA).pce", "ra-mednafen_pce", 100L, 200L), SessionPlan("app:com.example.shmup", null, 300L, 400L)),
            p.sessions
        )
        // Cocoon's AArch64 RetroArch isn't installed: the same core in the installed com.retroarch build is used
        assertEquals(mapOf("gba/Metroid Fusion (USA).gba" to "ra-mgba"), p.profileOverrides)
        assertEquals(emptyList<String>(), p.additionalProfiles.map { it.id }) // ra-mgba comes with the new gba system
        assertTrue(p.notes.any { it.startsWith("1 per-game emulator choice uses an app") })
        assertTrue(p.notes.any { it.startsWith("1 per-game emulator choice names a RetroArch build that isn't installed") })
        assertEquals(listOf("pcengine/Bonk's Adventure (USA).pce", "app:com.example.shmup"), p.favourites)
        assertEquals(listOf("tg16/Star Patrol (USA).pce", "app:com.example.reader"), p.hidden)
    }

    /**
     * A Cocoon folder the user named "Favourites" is just a user folder: it keeps its `cocoon-<id>` identity (never the
     * reserved built-in id) and its members do not become native favourites.
     */
    @Test
    fun `a backup folder named Favourites stays a cocoon folder and adds no favourite flags`() {
        val patrol = CocoonRef("tg16", "Star Patrol (USA).pce", null)
        val backup = CocoonBackup(
            dir = tmp.newFolder("backup"),
            createdAt = 0L,
            appVersion = "3.07",
            folders = listOf(CocoonFolder(3, "Favourites", listOf(patrol, CocoonRef("cps2", "skyfleet.7z", null)))),
            sessions = emptyList(),
            playerOverrides = emptyMap(),
            hidden = emptySet(),
            roots = emptyMap(),
            favourites = emptySet()
        )
        val p = plan(backup)
        assertEquals(
            listOf(FolderPlan(FolderDef(id = "cocoon-3", label = "Favourites", include = listOf("tg16/Star Patrol (USA).pce", "cps2/skyfleet.7z")), 0)),
            p.folders
        )
        // only the gamelist's own favourite flag; folder members are not favourited
        assertEquals(listOf("pcengine/Bonk's Adventure (USA).pce"), p.favourites)
        assertEquals(0, p.legacyFavourites)
    }

    /**
     * Seen on the DMG: ZX Spectrum ports share arcade names (`strider.zip`) and Game Gear shares titles with Genesis, so the
     * probe offers those folders as roots. A hit in the platform's own system must win and a folder of a system outside the
     * alias group must never make a match ambiguous — or, worse, be the only match.
     */
    @Test
    fun `names shared with systems outside the alias group neither match nor make a match ambiguous`() {
        files("roms/cps1", "strider.zip", "mercs.zip")
        files("roms/zxspectrum", "strider.zip", "mercs.zip", "forgottn.zip")
        files("roms/gamegear", "Caesars Palace (USA).zip")
        files("roms/genesis", "Caesars Palace (USA).zip")
        val config = config.copy(
            systems = config.systems + SystemDef(id = "zxspectrum", label = "ZX Spectrum", roots = listOf("roms/zxspectrum"), profiles = listOf("ra-fuse")),
            profiles = config.profiles + LibraryPresets.retroArch("fuse", "Fuse")
        )
        val planner = CocoonPlanner(card, File(card, "sortie"), config, installed = { true }, existingMedia = { _, _, _ -> false })
        val library = CocoonLibrary(
            cocoon,
            listOf(
                platform("cps1", "cps1.ra.fbneo", game("strider.zip", favourite = true), game("mercs.zip"), game("forgottn.zip")),
                platform("gamegear", "gamegear.ra.genesis_plus_gx", game("Caesars Palace (USA).zip", favourite = true))
            ),
            emptyList()
        )
        // the probe's view: both folders qualified for both platforms
        val roots = mapOf(
            "cps1" to listOf(File(card, "roms/cps1"), File(card, "roms/zxspectrum")),
            "gamegear" to listOf(File(card, "roms/gamegear"), File(card, "roms/genesis"))
        )
        val p = planner.plan(library, null, roots, RootProbe(listOf(card), exclude = listOf(cocoon)).listings())
        assertEquals(emptyList<String>(), p.ambiguous)
        assertEquals(listOf("cps1/strider.zip", "gamegear/Caesars Palace (USA).zip"), p.favourites)
        // forgottn.zip exists only under ZX Spectrum, outside cps1's group: missing, not filed under zxspectrum
        assertEquals(listOf("cps1/forgottn.zip"), p.unmatched)
        assertEquals(listOf("cps1", "gamegear"), p.newSystems.map { it.system.id })
        assertEquals(listOf("roms/cps1"), p.newSystems[0].system.roots)
        assertEquals(listOf("roms/gamegear"), p.newSystems[1].system.roots)
    }

    /**
     * Fresh install, and Cocoon never scanned ZX Spectrum: nothing outranks CPS1's three romset-name hits in `roms/zxspectrum`,
     * so the claim itself must refuse a folder that is neither named for the platform nor mostly its games. The real
     * `roms/cps1` (named, and all hits) is still claimed.
     */
    @Test
    fun `a fresh install never claims a folder that only shares a few names with the platform`() {
        files("roms/cps1", "strider.zip", "mercs.zip")
        files("roms/zxspectrum", "strider.zip", "mercs.zip", "jetset.tzx", "manic.tzx", "sabre.tzx", "elite.tzx")
        val empty = config.copy(systems = emptyList(), profiles = emptyList())
        val planner = CocoonPlanner(card, File(card, "sortie"), empty, installed = { true }, existingMedia = { _, _, _ -> false })
        val library = CocoonLibrary(cocoon, listOf(platform("cps1", "cps1.ra.fbneo", game("strider.zip"), game("mercs.zip"))), emptyList())
        val roots = mapOf("cps1" to listOf(File(card, "roms/cps1"), File(card, "roms/zxspectrum")))
        val p = planner.plan(library, null, roots, RootProbe(listOf(card), exclude = listOf(cocoon)).listings())
        assertEquals(listOf("roms/cps1"), p.newSystems.single().system.roots)
        assertEquals(2, p.matched)
        assertEquals(emptyList<String>(), p.ambiguous)
    }

    /**
     * Fresh install: every file in `roms/neogeo` is an FBNeo set name and the Game Gear folder's only file shares its name
     * with a Genesis game, so the half-the-files rule alone would hand both folders to the wrong platform. A folder named for
     * another system is never adopted (and the preview says so); a folder with no system name still is when it is mostly
     * the platform's games.
     */
    @Test
    fun `a fresh install never adopts a folder named for another system`() {
        files("roms/fbneo", "alpha.zip")
        files("roms/neogeo", "beta.zip", "gamma.zip")
        files("roms/picks", "zeta.zip")
        files("roms/genesis", "Shared Title (USA).zip")
        files("roms/gamegear", "Shared Title (USA).zip")
        val empty = config.copy(systems = emptyList(), profiles = emptyList())
        val planner = CocoonPlanner(card, File(card, "sortie"), empty, installed = { true }, existingMedia = { _, _, _ -> false })
        val library = CocoonLibrary(
            cocoon,
            listOf(
                platform("fbneo", "fbneo.ra.fbneo", game("alpha.zip"), game("beta.zip"), game("gamma.zip"), game("zeta.zip")),
                platform("genesis", "genesis.ra.genesis_plus_gx", game("Shared Title (USA).zip", favourite = true))
            ),
            emptyList()
        )
        // the probe's view: every folder qualified for the platform listed
        val roots = mapOf(
            "fbneo" to listOf(File(card, "roms/fbneo"), File(card, "roms/neogeo"), File(card, "roms/picks")),
            "genesis" to listOf(File(card, "roms/genesis"), File(card, "roms/gamegear"))
        )
        val p = planner.plan(library, null, roots, RootProbe(listOf(card), exclude = listOf(cocoon)).listings())
        assertEquals(
            mapOf("arcade" to listOf("roms/fbneo", "roms/picks"), "genesis" to listOf("roms/genesis")),
            p.newSystems.associate { it.system.id to it.system.roots }
        )
        assertEquals(listOf("fbneo/beta.zip", "fbneo/gamma.zip"), p.unmatched)
        assertEquals(listOf("genesis/Shared Title (USA).zip"), p.favourites)
        assertEquals(emptyList<String>(), p.ambiguous)
        listOf("roms/neogeo", "roms/gamegear").forEach { dir ->
            assertTrue(dir, p.notes.any { it.startsWith(File(card, dir).path + " holds games of ") })
        }
    }

    private fun withPlayer(platform: String, player: String) =
        library.copy(platforms = library.platforms.map { if (it.id == platform) it.copy(defaultPlayer = player) else it })

    /**
     * On the Thor the import pinned 34 existing systems in user.json's systemProfiles, three to another emulator. An existing
     * system's emulator stays the user's whether Cocoon used the same one (pcengine) or another (genesis): no choice is
     * planned for either, only the different one is mentioned.
     */
    @Test
    fun `existing systems keep their emulators whether Cocoon used the same one or another`() {
        val p = plan(library = withPlayer("genesis", "genesis.ra.picodrive"))
        val newIds = p.newSystems.map { it.system.id }
        assertFalse("pcengine" in newIds || "genesis" in newIds)
        assertEquals(newIds.toSet(), p.systemProfiles.map { it.systemId }.toSet())
        assertTrue(p.notes.any { it.startsWith("Genesis: Cocoon launched it with RetroArch · PicoDrive; your emulator setup for it is kept") })
        assertFalse(p.notes.any { it.startsWith("PC Engine: Cocoon launched it") })
    }

    /**
     * A new preset system must list what Cocoon's games there are (an N64 library of .7z files indexed nothing), yet a
     * disc track type must not join a folder of cue sheets, where it would list each track next to its sheet; a folder of
     * bare tracks gets the type. Checked against the index's own scan of the planned systems.
     */
    @Test
    fun `a new preset system lists its Cocoon games' file types, except disc tracks beside cue sheets`() {
        files("roms/n64", "Mario Kart 64 (USA).7z", "F-Zero X (USA).z64")
        files("roms/psx", "Ape Escape (USA).cue", "Ape Escape (USA).bin", "Vagrant Story (USA).bin")
        files("roms/saturn", "Panzer Dragoon (USA).bin")
        val library = CocoonLibrary(
            cocoon,
            listOf(
                platform("n64", "n64.ra.mupen64plus_next", game("Mario Kart 64 (USA).7z", favourite = true), game("F-Zero X (USA).z64")),
                platform("psx", "psx.ra.swanstation", game("Ape Escape (USA).cue"), game("Vagrant Story (USA).bin", favourite = true)),
                platform("saturn", "saturn.ra.yabasanshiro", game("Panzer Dragoon (USA).bin", favourite = true))
            ),
            emptyList()
        )
        val roots = listOf("n64", "psx", "saturn").associateWith { listOf(File(card, "roms/$it")) }
        val planner = CocoonPlanner(card, File(card, "sortie"), config, installed = { true }, existingMedia = { _, _, _ -> false })
        val p = planner.plan(library, null, roots, RootProbe(listOf(card), exclude = listOf(cocoon)).listings())
        assertEquals(listOf("n64", "psx", "saturn"), p.newSystems.map { it.system.id })
        assertTrue(p.newSystems.all { it.fromPreset })

        val indexed = p.newSystems.flatMap { Indexer.scan(card, it.system, LibraryRules()).found.keys }.toSet()
        assertEquals(setOf("n64/Mario Kart 64 (USA).7z", "n64/F-Zero X (USA).z64", "psx/Ape Escape (USA).cue", "saturn/Panzer Dragoon (USA).bin"), indexed)
        // what the preview counts and flags is what the index lists; the Cocoon .bin beside cue sheets is reported instead
        assertEquals(4, p.matched)
        assertEquals(listOf("n64/Mario Kart 64 (USA).7z", "saturn/Panzer Dragoon (USA).bin"), p.favourites)
        assertTrue(p.notes.any { it.startsWith("PlayStation: 1 matched game skipped (.bin)") })
    }

    /** Cocoon's per-game `gbc.ra.sameboy` was dropped for want of a profile: per-game cores now resolve like platform ones. */
    @Test
    fun `per-game RetroArch cores no preset has are imported with their profiles`() {
        val backup = CocoonBackup(
            dir = tmp.newFolder("backup"),
            createdAt = 0L,
            appVersion = "3.07",
            folders = emptyList(),
            sessions = emptyList(),
            playerOverrides = mapOf(
                CocoonRef("tg16", "Bonk's Adventure (USA).pce", null) to "tg16.ra.mednafen_supergrafx",
                CocoonRef("cps2", "skyfleet.7z", null) to "cps2.ra.fbalpha2012",
                CocoonRef("cps2", "brickout.zip", null) to "cps2.ra.fbalpha2012",
                CocoonRef("gba", "Existing (USA).gba", null) to "gba.ra.mgba",
                CocoonRef("genesis", "Sonic (USA).md", null) to "genesis.ra.genesis_plus_gx"
            ),
            hidden = emptySet(),
            roots = emptyMap(),
            favourites = emptySet()
        )
        val p = plan(backup)
        assertEquals(
            mapOf(
                "pcengine/Bonk's Adventure (USA).pce" to "ra-mednafen_supergrafx",
                "cps2/skyfleet.7z" to "ra-fbalpha2012",
                "cps2/brickout.zip" to "ra-fbalpha2012",
                "gba/Existing (USA).gba" to "ra-mgba",
                "genesis/Sonic (USA).md" to "ra-genesis_plus_gx"
            ),
            p.profileOverrides
        )
        // each definition once, and only those neither configured nor added with the game's own new system
        assertEquals(listOf("ra-mednafen_supergrafx", "ra-fbalpha2012"), p.additionalProfiles.map { it.id })
        p.additionalProfiles.forEach { profile ->
            assertEquals("com.retroarch", profile.`package`)
            assertTrue(profile.extras.getValue("LIBRETRO").endsWith("/${profile.id.removePrefix("ra-")}_libretro_android.so"))
        }
        // after the import every override names a profile Sortie has
        val available = config.profiles.map { it.id } + p.additionalProfiles.map { it.id }
        p.profileOverrides.forEach { (gameId, profileId) ->
            val own = p.newSystems.firstOrNull { it.system.id == gameId.substringBefore('/') }?.profiles.orEmpty().map { it.id }
            assertTrue("$gameId → $profileId", profileId in available || profileId in own)
        }
        assertFalse(p.notes.any { it.contains("per-game emulator choice") })
    }

    /**
     * GBA got `ra64-mgba` on the Thor although only com.retroarch was installed. The same core in an installed build replaces
     * a missing one and says so; the exact build Cocoon named is kept when it is installed, and when nothing is.
     */
    @Test
    fun `a missing RetroArch build is replaced by the same core in an installed one only when it is missing`() {
        val library = withPlayer("gba", "gba.ra64.mgba")
        fun gba(installed: Set<String>): Pair<CocoonImportPlan, SystemProfileChoice> =
            plan(library = library, installed = installed).let { p -> p to p.systemProfiles.single { it.systemId == "gba" } }
        fun CocoonImportPlan.first() = newSystems.single { it.system.id == "gba" }.system.profiles.first()

        val (replaced, replacedChoice) = gba(setOf("com.retroarch"))
        assertEquals(SystemProfileChoice("gba", "ra-mgba", installed = true, requestedProfileId = "ra64-mgba"), replacedChoice)
        assertEquals("ra-mgba", replaced.first())
        val note = "Game Boy Advance: Cocoon used RetroArch (AArch64) · mGBA (com.retroarch.aarch64), which isn't installed"
        assertTrue(replaced.notes.any { it.startsWith(note) })

        val (exact, exactChoice) = gba(setOf("com.retroarch", "com.retroarch.aarch64"))
        assertEquals(SystemProfileChoice("gba", "ra64-mgba", installed = true), exactChoice)
        assertEquals("ra64-mgba", exact.first())

        val (none, noneChoice) = gba(emptySet())
        assertEquals(SystemProfileChoice("gba", "ra64-mgba", installed = false), noneChoice)
        assertEquals("ra64-mgba", none.first())
    }
}
