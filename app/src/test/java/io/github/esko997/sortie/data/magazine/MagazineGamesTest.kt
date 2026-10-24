package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.db.MatchRow
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MagazineGamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val systems = listOf(
        SystemDef(id = "ps2", generation = "128-bit", roots = emptyList()),
        SystemDef(id = "psp", generation = "handheld", roots = emptyList()),
        SystemDef(id = "psx", generation = "32-bit", roots = emptyList()),
        SystemDef(id = "snes", generation = "16-bit", roots = emptyList()),
        SystemDef(id = "toaplan-cave-stg", generation = "arcade", roots = emptyList()),
        SystemDef(id = "fbneo", generation = "arcade", roots = emptyList()),
        SystemDef(id = "mame", generation = "arcade", roots = emptyList())
    )
    private val families = SystemFamilies(systems)

    /** A real non-empty file, so the row is available unless the test says otherwise. */
    private fun row(systemId: String, fileName: String, title: String = "", hack: Boolean = false, placeholder: Boolean = false): MatchRow {
        val f = File(tmp.root, "$systemId/$fileName")
        f.parentFile!!.mkdirs()
        if (!placeholder) f.writeBytes(byteArrayOf(1, 2, 3))
        return MatchRow(
            id = "$systemId/$fileName",
            systemId = systemId,
            fileName = fileName,
            path = f.path,
            title = title.ifEmpty { GameNames.displayTitle(GameNames.stem(fileName)) },
            size = if (placeholder) 0 else 3,
            placeholder = placeholder,
            arriving = false,
            hack = hack
        )
    }

    private fun ids(ref: MagazineGameReference, rows: List<MatchRow>): List<String> =
        matchReferences(listOf(ref), rows, families).getValue(ref.id).map { it.id }

    private val dw5xl = MagazineGameReference("ps2-dynasty-warriors-5-xtreme-legends", "Dynasty Warriors 5: Xtreme Legends", "ps2")

    @Test
    fun sameEditionMatchesAcrossContainersAndRegionsButNotOtherEditions() {
        val rows = listOf(
            row("ps2", "Dynasty Warriors 5 - Xtreme Legends (USA).chd"),
            row("ps2", "Dynasty Warriors 5 - Xtreme Legends (Europe) (En,Fr,De).iso"),
            row("ps2", "Dynasty Warriors 5 (USA).iso"),
            row("ps2", "Dynasty Warriors 5 - Empires (USA).iso"),
            // Not a PSP game: another platform never matches.
            row("psp", "Dynasty Warriors 5 - Xtreme Legends (USA).iso")
        )
        assertEquals(
            listOf("ps2/Dynasty Warriors 5 - Xtreme Legends (USA).chd", "ps2/Dynasty Warriors 5 - Xtreme Legends (Europe) (En,Fr,De).iso").sorted(),
            ids(dw5xl, rows).sorted()
        )
        val dw5 = MagazineGameReference("ps2-dynasty-warriors-5", "Dynasty Warriors 5", "ps2")
        assertEquals(listOf("ps2/Dynasty Warriors 5 (USA).iso"), ids(dw5, rows))
    }

    @Test
    fun translationAndOriginalNeverCollapse() {
        val rows = listOf(
            row("psx", "Baroque - Yuganda Mousou (Japan) (En) (v1.02 Translation).chd"),
            row("psx", "Baroque - Yuganda Mousou (Japan).chd"),
            row("psx", "Baroque - Yuganda Mousou (Japan) [T-Fr by Someone].chd")
        )
        val original = MagazineGameReference("psx-baroque-yuganda-mousou", "Baroque: Yuganda Mousou", "psx")
        val translated = MagazineGameReference(
            "psx-baroque-yuganda-mousou-english-translation",
            "Baroque: Yuganda Mousou — English fan translation",
            "psx",
            edition = "Fan translation",
            matchNames = listOf("Baroque - Yuganda Mousou (v1.02 Translation)")
        )
        assertEquals(listOf("psx/Baroque - Yuganda Mousou (Japan).chd"), ids(original, rows))
        // the English translation, in another container or region, but never the French one; edition text is not a rule
        assertEquals(listOf("psx/Baroque - Yuganda Mousou (Japan) (En) (v1.02 Translation).chd"), ids(translated, rows))
        val english = MagazineGameReference("psx-baroque-en", "Baroque - Yuganda Mousou [T-En by Someone]", "psx")
        assertEquals(emptyList<String>(), ids(english, rows))
        assertNotEquals(GameNames.key("Baroque [T-En by Someone]"), GameNames.key("Baroque [T-Fr by Someone]"))
        assertNotEquals(GameNames.key("Baroque [T+Eng]"), GameNames.key("Baroque [T+Fr]"))
        assertNotEquals(GameNames.key("Baroque [T+Fr]"), GameNames.key("Baroque"))
    }

    @Test
    fun differentHacksOfOneGameStayApart() {
        val foo = MagazineGameReference("snes-smw-foo", "Super Mario World (Hack Foo)", "snes")
        val rows = listOf(
            row("snes", "Super Mario World (USA) (Hack Foo).sfc", hack = true),
            row("snes", "Super Mario World (USA) (Hack Bar).sfc", hack = true),
            row("snes", "Super Mario World (USA) [h1C].sfc", hack = true),
            row("snes", "Super Mario World (USA).sfc")
        )
        assertEquals(listOf("snes/Super Mario World (USA) (Hack Foo).sfc"), ids(foo, rows))
        assertEquals(listOf("snes/Super Mario World (USA).sfc"), ids(MagazineGameReference("snes-smw", "Super Mario World", "snes"), rows))
    }

    @Test
    fun genericScrapedTitleNeverMakesAHackOrOtherEditionLookLikeTheOriginal() {
        val smw = MagazineGameReference("snes-super-mario-world", "Super Mario World", "snes")
        val rows = listOf(
            row("snes", "Kaizo Mario World (Hack).sfc", title = "Super Mario World", hack = true),
            row("snes", "SMW (USA) (Special Edition).sfc", title = "Super Mario World"),
            // A flagged hack with an opaque name.
            row("snes", "SNSP-12345.sfc", title = "Super Mario World", hack = true)
        )
        assertEquals(emptyList<String>(), ids(smw, rows))

        // an opaque file name (a product serial) is identified by its scraped title
        val serial = row("ps2", "SLUS-21299.iso", title = "Dynasty Warriors 5: Xtreme Legends")
        assertEquals(listOf(serial.id), ids(dw5xl, listOf(serial)))
        // a descriptive file name is the identity: a wrong scrape cannot pull it into another edition
        assertEquals(emptyList<String>(), ids(dw5xl, listOf(row("ps2", "Dynasty Warriors 5 (USA).iso", title = "Dynasty Warriors 5: Xtreme Legends"))))
    }

    @Test
    fun arcadeReferencesCoverEveryArcadeSystemByRomsetOrScrapedTitle() {
        val garegga = MagazineGameReference("arcade-battle-garegga", "Battle Garegga", "arcade", matchNames = listOf("bgaregga"))
        val rows = listOf(
            row("toaplan-cave-stg", "bgaregga.7z", title = "bgaregga"),
            row("mame", "bgaregga.zip", title = "Battle Garegga (Europe / USA / Japan / Asia) (Sat Feb 3 1996)"),
            row("fbneo", "bgareggat2.zip", title = "Battle Garegga - Type 2 (Europe / USA / Japan / Asia) (Sat Mar 2 1996)"),
            row("fbneo", "bgaregganv.zip", title = "Battle Garegga (Hack)"),
            row("ps2", "bgaregga.iso", title = "Battle Garegga")
        )
        assertEquals(listOf("mame/bgaregga.zip", "toaplan-cave-stg/bgaregga.7z"), ids(garegga, rows).sorted())
        // a specific board id stays exact
        assertEquals(listOf("fbneo/bgareggat2.zip"), ids(MagazineGameReference("x", "Battle Garegga - Type 2", "FBNEO"), rows))
    }

    @Test
    fun informationalReferencesWithoutPlatformNeverMatch() {
        val info = MagazineGameReference("devil-may-cry-5", "Devil May Cry 5")
        assertEquals(emptyList<String>(), ids(info, listOf(row("ps2", "Devil May Cry 5.iso"))))
    }

    @Test
    fun severalLocalCopiesAreChoicesWithUnavailableOnesLast() {
        val gone = row("ps2", "Dynasty Warriors 5 - Xtreme Legends (Japan).iso").also { File(it.path).delete() }
        val shed = row("ps2", "Dynasty Warriors 5 - Xtreme Legends (USA).chd", placeholder = true)
        val empty = row("ps2", "Dynasty Warriors 5 - Xtreme Legends (Korea).iso").also { File(it.path).writeBytes(ByteArray(0)) }.copy(size = 7)
        val arriving = row("ps2", "Dynasty Warriors 5 - Xtreme Legends (Asia).iso")
        File(File(arriving.path).parentFile, ".syncthing.${arriving.fileName}.tmp").writeText("x")
        val flagged = row("ps2", "Dynasty Warriors 5 - Xtreme Legends (Brazil).iso").copy(arriving = true)
        val good = row("ps2", "Dynasty Warriors 5 - Xtreme Legends (Europe).iso")
        val matches = localMatches(listOf(dw5xl), listOf(gone, shed, empty, arriving, flagged, good), families).getValue(dw5xl.id)

        assertEquals(6, matches.size)
        assertEquals(good.id, matches.first().gameId)
        assertEquals(listOf(good.id), matches.filter { it.available }.map { it.gameId })
        val presence = matches.associate { it.gameId to it.presence }
        assertEquals(GamePresence.MISSING, presence[gone.id])
        assertEquals(GamePresence.PLACEHOLDER, presence[shed.id])
        assertEquals(GamePresence.PLACEHOLDER, presence[empty.id])
        assertEquals(GamePresence.ARRIVING, presence[arriving.id])
        assertEquals(GamePresence.ARRIVING, presence[flagged.id])
    }

    @Test
    fun namesForgivePunctuationArticlesAndTagsOnly() {
        val zelda = GameNames.stem("Legend of Zelda, The - Spirit Tracks (USA) (Rev 1).nds")
        assertEquals(GameNames.key("The Legend of Zelda: Spirit Tracks"), GameNames.key(zelda))
        assertEquals(GameNames.key("Ratchet & Clank"), GameNames.key("Ratchet and Clank (USA) (Disc 1) (Greatest Hits)"))
        assertEquals(GameNames.key("Pokemon Mystery Dungeon"), GameNames.key("Pokémon Mystery Dungeon"))
        assertEquals("game", GameNames.key(GameNames.stem("Game (USA).nkit.iso")))
        assertNotEquals(GameNames.key("Sakura Wars (English Voice Over)"), GameNames.key("Sakura Wars (USA)"))
        assertNotEquals(GameNames.key("Game (Beta)"), GameNames.key("Game"))
        assertNotEquals(GameNames.key("Game (Undub)"), GameNames.key("Game (USA)"))
        assertNotEquals(GameNames.key("Strikers 1945 II"), GameNames.key("Strikers 1945"))
    }

    @Test
    fun gameDetailFindsOnlyArticlesAboutThatEdition() {
        val translated = MagazineGameReference(
            "psx-baroque-tl",
            "Baroque (Translation)",
            "psx",
            matchNames = listOf("Baroque - Yuganda Mousou (v1.02 Translation)")
        )
        val original = MagazineGameReference("psx-baroque", "Baroque - Yuganda Mousou", "psx")
        val issue = MagazineIssue(
            id = "field-notes-002",
            title = "T",
            published = "2026-10-24",
            articles = listOf(
                MagazineArticle(id = "tl", title = "Translated", games = listOf(translated), body = "articles/tl.md"),
                MagazineArticle(id = "jp", title = "Original", games = listOf(original), body = "articles/jp.md"),
                MagazineArticle(id = "both", title = "Both", games = listOf(original, translated), body = "articles/both.md")
            )
        )
        val shelf = MagazineShelf(tmp.root, listOf(ShelfIssue(tmp.root, issue, null, emptyMap())), emptyList())
        val features = featuresOf("psx", "Baroque - Yuganda Mousou (Japan) (En) (v1.02 Translation).chd", "Baroque", hack = false, shelf, families)
        assertEquals(listOf("tl" to translated.id, "both" to translated.id), features.map { it.article.id to it.ref.id })
        assertTrue(featuresOf("ps2", "Baroque (USA).iso", "Baroque", hack = false, shelf, families).isEmpty())
    }
}
