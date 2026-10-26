package io.github.esko997.sortie.data.guide

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the shipped catalog through the same parser/resolver as a user's library, without a private guide.json. */
class BundledGuideCatalogTest {
    private val bytes = File("src/main/assets/${CuratedGuide.ASSET}").readBytes()
    private val catalog = CuratedCatalog.parse(bytes)

    @Test
    fun `public catalog contains no personal scores or paths`() {
        val raw = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        for (value in raw.getValue("entries").jsonArray) {
            val entry = value.jsonObject
            assertTrue("no private editorial fields", entry.keys.intersect(setOf("fit", "tags", "auto", "path", "minutes", "device")).isEmpty())
        }
        val privatePath = Regex("""/(?:var/)?home/|/storage/|/sdcard/""")
        val privateWording = Regex("""\byour (?:library|lane|list|genres|tastes?)\b|pending cleanup|supplied archive""", RegexOption.IGNORE_CASE)
        for (entry in catalog.entries) {
            // Public citation URLs may legitimately contain /home/; inspect editorial text and aliases, not URI paths.
            val editorial = listOf(entry.title, entry.hook, entry.controls) + entry.aliases + entry.flags
            assertFalse(entry.id, editorial.any(privatePath::containsMatchIn))
            assertFalse(entry.id, privateWording.containsMatchIn(entry.hook + " " + entry.controls))
        }
    }

    @Test
    fun `real exact aliases resolve their own edition and never an unknown platform`() {
        val aliases = catalog.entries.flatMap { entry ->
            entry.aliases.map { (CuratedCatalog.platform(entry.system) to it) to entry.id }
        }.groupBy({ it.first }, { it.second })
        for ((key, ids) in aliases) {
            val (system, alias) = key
            val matched = catalog.match(system, alias)
            if (ids.distinct().size > 1) {
                assertNull("ambiguous alias $key", matched)
            } else if (matched != null) {
                // An extensionless alias collision may make a full filename ambiguous too; it must never choose another entry.
                assertEquals(key.toString(), ids.distinct().single(), matched.id)
            }
            assertNull(catalog.match("unknown-user-system", alias))
        }
    }

    @Test
    fun `verified release fingerprints recognize renamed games without a local guide`() {
        val identified = catalog.entries.flatMap { entry -> entry.fingerprints.map { (CuratedCatalog.platform(entry.system) to it.key()!!) to entry.id } }
            .groupBy({ it.first }, { it.second })
        for ((key, ids) in identified) {
            val (system, fingerprint) = key
            val matched = catalog.match(system, "user-renamed-rom", listOf(fingerprint))
            assertEquals(ids.distinct().singleOrNull(), matched?.id)
            assertNull(catalog.match("unknown-user-system", "user-renamed-rom", listOf(fingerprint)))
        }
    }
}
