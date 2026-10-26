package io.github.esko997.sortie.data.guide

import io.github.esko997.sortie.data.magazine.GameNames
import io.github.esko997.sortie.data.magazine.SystemFamilies
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated guide that ships in every APK (src/main/assets/guide/catalog.json, built by tools/guide/import-blurbs.py): it
 * reads with the app's own parser, accounts for every authored source record, carries nothing private, and its identities
 * cannot collide. Unit tests run in the app module directory.
 */
class BundledGuideCatalogTest {
    private val assetFile = File("src/main/assets/${CuratedGuide.ASSET}")
    private val bytes = assetFile.readBytes()
    private val catalog = CuratedCatalog.parse(bytes)
    private val raw = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    private val report = Json.parseToJsonElement(File("../tools/guide/import-report.json").readText()).jsonObject

    private val families = SystemFamilies(
        listOf(
            SystemDef(id = "ps2", generation = "128-bit", roots = emptyList()),
            SystemDef(id = "dreamcast", generation = "128-bit", roots = emptyList()),
            SystemDef(id = "cps2", generation = "arcade", roots = emptyList()),
            SystemDef(id = "neogeo", generation = "arcade", roots = emptyList()),
            SystemDef(id = "neogeocd", generation = "arcade", roots = emptyList())
        )
    )

    @Test
    fun everyAuthoredRecordIsAccountedForAndEveryPublishedOneIsLoaded() {
        val source = Json { ignoreUnknownKeys = true }.decodeFromString(CuratedCatalogFile.serializer(), bytes.decodeToString()).source
        assertTrue("source hash", Regex("[0-9a-f]{64}").matches(source.sha256))
        assertEquals("records = published + edited + merged + excluded", source.records, source.published + source.edited + source.merged + source.excluded)
        // one variant per published or edited record; merged records share their primary's variant
        assertEquals(source.published + source.edited, catalog.variantCount)
        assertTrue("a real catalogue", catalog.variantCount > 1000)

        val reportSource = report.getValue("source").jsonObject
        assertEquals(source.sha256, reportSource.getValue("sha256").jsonPrimitive.content)
        assertEquals(source.records, reportSource.getValue("records").jsonPrimitive.int)
        val records = report.getValue("records").jsonArray.map { it.jsonObject }
        assertEquals(source.records, records.size)
        assertEquals(source.excluded, records.count { it.getValue("disposition").jsonPrimitive.content == "excluded" })
        assertEquals(source.merged, records.count { it.getValue("disposition").jsonPrimitive.content == "merged" })
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("report describes this catalogue", sha, report.getValue("catalog").jsonObject.getValue("sha256").jsonPrimitive.content)
        val variantIds = catalog.works.flatMap { w -> w.variants.map { it.id } }.toSet()
        assertEquals("one entry per source line", records.size, records.map { it.getValue("line").jsonPrimitive.int }.toSet().size)
        for (r in records) {
            // a digest of one short record would let anyone confirm guesses about its private fit and file name
            assertEquals(
                "report keys for line ${r["line"]}",
                emptySet<String>(),
                r.keys - setOf("line", "disposition", "variant", "mergedInto", "edited", "reasons")
            )
        }
        for (r in records.filter { it.getValue("disposition").jsonPrimitive.content != "excluded" }) {
            assertTrue("report variant ${r["variant"]}", r.getValue("variant").jsonPrimitive.content in variantIds)
        }
        val curation = Json.parseToJsonElement(File("../tools/guide/curation.json").readText()).jsonObject
        assertEquals(
            "curation is written for this source snapshot",
            source.sha256,
            curation.getValue("source").jsonObject.getValue("sha256").jsonPrimitive.content
        )
        for (d in curation.getValue("decisions").jsonArray.map { it.jsonObject }) {
            assertFalse("curation decision for line ${d["line"]} carries a record digest", "record" in d || "sha256" in d)
        }
    }

    @Test
    fun publishesNoFileNamesScoresTagsOrPrivateWording() {
        for (w in raw.getValue("works").jsonArray) {
            for (v in w.jsonObject.getValue("variants").jsonArray.map { it.jsonObject }) {
                assertFalse("${v["id"]}: no fit score", "fit" in v)
                assertFalse("${v["id"]}: no taste tags", "tags" in v)
                assertFalse("${v["id"]}: no auto marker", "auto" in v)
                assertFalse("${v["id"]}: no file name", "file" in v || "sys" in v)
            }
        }
        val text = bytes.decodeToString()
        val forbidden = listOf(
            Regex("""/(?:var/)?home/|/storage/|/sdcard/"""),
            Regex("""\byour (?:library|lane|list|genres|tastes?)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:in|not in) the library\b""", RegexOption.IGNORE_CASE),
            Regex("""vita3k|NoNpDrm|PC[A-Z]{2}\d{5}""", RegexOption.IGNORE_CASE),
            Regex("""pending cleanup|supplied archive""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:cracked|raw|unverified) dump\b|\bfile may be\b""", RegexOption.IGNORE_CASE)
        )
        for (rx in forbidden) assertFalse("catalogue contains ${rx.pattern}: ${rx.find(text)?.value}", rx.containsMatchIn(text))
        val extension =
            Regex("""\.(?:zip|7z|iso|chd|cue|bin|cso|pbp|gba|nds|3ds|cci|z64|sfc|smc|md|32x|cdi|gdi|m3u|ciso|rvz|wbfs|ngp|ngc)$""", RegexOption.IGNORE_CASE)
        for (v in catalog.works.flatMap { it.variants }) {
            for (name in v.matchNames) assertFalse("${v.id}: match name $name is a file name", extension.containsMatchIn(name))
            for (flag in v.flags) assertFalse("${v.id}: library-relative flag $flag", flag.equals("duplicate", true) || flag.equals("check", true))
        }
    }

    @Test
    fun identitiesAreUniqueAndNoTwoVariantsAnswerToTheSameName() {
        val idRx = Regex("[a-z0-9][a-z0-9-]{0,119}")
        val workIds = catalog.works.map { it.id }
        assertEquals(workIds.size, workIds.toSet().size)
        val variants = catalog.works.flatMap { it.variants }
        assertEquals(variants.size, variants.map { it.id }.toSet().size)
        (workIds + variants.map { it.id }).forEach { assertTrue("id $it", idRx.matches(it)) }
        // "arcade" covers every arcade system, so it overlaps any of them as well as itself
        val arcadeFamily = setOf("arcade", "neogeo", "cps1", "cps2", "cps3", "mame", "fbneo", "naomi", "atomiswave", "stv", "model2", "neogeocd")
        fun overlap(a: String, b: String) = a == b || (a == "arcade" && b in arcadeFamily) || (b == "arcade" && a in arcadeFamily)
        val byKey = variants.flatMap { v -> v.matchNames.map(GameNames::key).distinct().map { it to v } }.groupBy({ it.first }, { it.second })
        for ((key, claimants) in byKey) {
            for (i in claimants.indices) {
                for (j in i + 1 until claimants.size) {
                    val (a, b) = claimants[i] to claimants[j]
                    assertFalse("$key answers to both ${a.id} and ${b.id}", a.id != b.id && overlap(a.platform.lowercase(), b.platform.lowercase()))
                }
            }
        }
    }

    @Test
    fun realEntriesAttachToTheGamesTheyDescribeAndNothingElse() {
        fun id(system: String, file: String, title: String = file.substringBeforeLast('.'), hack: Boolean = false) =
            catalog.match(system, file, title, hack, families)?.variant?.id
        assertEquals("ps2-armored-core-2", id("ps2", "Armored Core 2 (USA).iso"))
        assertEquals("ps2-armored-core-2", id("ps2", "Armored Core 2 (Europe) (En,Fr,De).chd"))
        assertEquals("ps2-armored-core-2-true-analogs-v1-0", id("ps2", "Armored Core 2 (USA) (True Analogs v1.0).iso", hack = true))
        assertEquals(null, id("ps2", "Armored Core 2 - Another Age (Japan) (Hack).iso", hack = true))
        assertEquals("arcade-sfa3", id("cps2", "sfa3.zip", "Street Fighter Alpha 3"))
        assertEquals("dreamcast-twinkle-star-sprites", id("dreamcast", "Twinkle Star Sprites (Japan).chd"))
        assertEquals("neogeocd-twinkle-star-sprites", id("neogeocd", "Twinkle Star Sprites (Japan) (En,Ja).chd"))
        // disc companions share the game's prose rather than a "second disc" note
        val disc2 = catalog.match("psx", "Valkyrie Profile (USA) (Disc 2).chd", "Valkyrie Profile", false, families)?.variant
        assertTrue(disc2?.hook.orEmpty().startsWith("Recruit dying warriors"))
        // the catalogue never publishes fit, so the indexer gives catalogue-only games none
        assertEquals(0, GuidePrecedence.effective(null, disc2)!!.fit)
    }
}
