package io.github.esko997.sortie.data.index

import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.IndexMetaEntity
import io.github.esko997.sortie.data.db.NameRow
import io.github.esko997.sortie.data.db.Signature
import io.github.esko997.sortie.data.guide.CuratedCatalog
import io.github.esko997.sortie.data.guide.CuratedCatalogFile
import io.github.esko997.sortie.data.guide.CuratedEntry
import io.github.esko997.sortie.data.guide.CuratedGuide
import io.github.esko997.sortie.data.manifest.GuideEntry
import io.github.esko997.sortie.data.manifest.GuideFile
import io.github.esko997.sortie.data.manifest.ManifestStore
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogRefreshTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Models only the indexer's table operations; any access to session history fails the test. */
    private class IndexTables {
        val games = LinkedHashMap<String, GameEntity>()
        val metadata = HashMap<String, IndexMetaEntity>()
        var failNextWrite = false

        @Suppress("UNCHECKED_CAST")
        val dao = Proxy.newProxyInstance(GameDao::class.java.classLoader, arrayOf(GameDao::class.java)) { _, method, args ->
            val a = args.orEmpty()
            when (method.name) {
                "meta" -> metadata[a[0] as String]
                "putMeta" -> {
                    val meta = a[0] as IndexMetaEntity
                    metadata[meta.systemId] = meta
                    Unit
                }
                "signatures" -> games.values.filter { it.systemId == a[0] }.map {
                    Signature(it.id, it.path, it.folder, it.mtime, it.size, it.addedAt, it.arriving)
                }
                "upsertAll" -> {
                    if (failNextWrite) {
                        failNextWrite = false
                        error("Temporary index write failure")
                    }
                    (a[0] as List<GameEntity>).forEach { games[it.id] = it }
                    Unit
                }
                "namesOf" -> games.values.filter { it.systemId == a[0] }.map { NameRow(it.id, it.fileName, it.placeholder) }
                "clearVariants" -> {
                    games.replaceAll { _, game -> if (game.systemId == a[0]) game.copy(variantOf = null) else game }
                    Unit
                }
                "setVariantOf" -> {
                    (a[0] as List<String>).forEach { id -> games[id] = games.getValue(id).copy(variantOf = a[1] as String) }
                    Unit
                }
                "setArriving" -> {
                    (a[0] as List<String>).forEach { id -> games[id] = games.getValue(id).copy(arriving = a[1] as Boolean) }
                    Unit
                }
                "deleteByIds" -> (a[0] as List<String>).count { games.remove(it) != null }
                "deleteSystemsNotIn" -> {
                    val ids = a[0] as List<String>
                    val removed = games.values.filter { it.systemId !in ids }.map { it.id }
                    removed.forEach(games::remove)
                    removed.size
                }
                else -> error("Unexpected database operation ${method.name}; catalog updates must not touch sessions")
            }
        } as GameDao
    }

    private fun guide(revision: String, hook: String?): CuratedGuide {
        val entries = if (hook == null) {
            emptyList()
        } else {
            listOf("Shared.md", "Local.md", "Generated.md").mapIndexed { index, alias ->
                CuratedEntry(
                    id = "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}", system = "genesis", aliases = listOf(alias),
                    hook = hook, controls = "Shared controls", flags = listOf("shared flag")
                )
            }
        }
        val bytes = Json.encodeToString(CuratedCatalogFile.serializer(), CuratedCatalogFile(1, revision, entries)).toByteArray()
        return CuratedGuide { CuratedCatalog.parse(bytes) }
    }

    @Test
    fun `normal startup refresh replaces and retires cached shared prose without migrating local ids or overrides`() = runBlocking {
        val root = tmp.newFolder("card")
        val config = File(root, "sortie").apply { mkdirs() }
        val roms = File(root, "roms").apply { mkdirs() }
        for (name in listOf("Shared.md", "Local.md", "Generated.md")) File(roms, name).writeBytes(byteArrayOf(1, 2, 3))
        File(config, "device.json").writeText("""{"root":"unused"}""")
        File(config, "systems.json").writeText("""{"systems":[{"id":"genesis","roots":["roms"],"extensions":[".md"]}]}""")
        File(config, "launchers.json").writeText("""{"profiles":[]}""")
        File(config, "folders.json").writeText("""{"folders":[]}""")
        val local = GuideEntry(fit = 5, auto = false)
        val generated = GuideEntry(fit = 4, controls = "Local mapping", flags = listOf("local flag"), tags = listOf("personal"))
        val localFile = File(config, "guide.json").apply {
            writeText(Json.encodeToString(GuideFile.serializer(), GuideFile(games = mapOf("genesis/Local.md" to local, "genesis/Generated.md" to generated))))
        }
        val localBytes = localFile.readText()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val manifests = ManifestStore(config, scope)
            val tables = IndexTables()
            val original = Indexer(tables.dao, manifests, guide("one", "Original note"))
            assertTrue(original.needsMetadataRefresh())
            assertEquals(3, original.run().updated)
            assertFalse(original.needsMetadataRefresh())
            val ids = tables.games.keys.toSet()
            val added = tables.games.mapValues { it.value.addedAt }
            assertEquals(0, original.run().updated)

            // Existing v4 databases have no shared-ID column; a legacy catalog stamp alone triggers the clean cutover.
            tables.metadata["genesis"] = tables.metadata.getValue("genesis").copy(guideStamp = "guide|curated:old-catalog|hack1")
            val upgrade = Indexer(tables.dao, manifests, guide("two", "Revised note"))
            assertTrue(upgrade.needsMetadataRefresh())
            val detached = File(root, "detached-roms")
            assertTrue(roms.renameTo(detached))
            val unavailable = upgrade.run()
            assertTrue(unavailable.problems.isNotEmpty())
            assertEquals(0, unavailable.updated)
            assertTrue(upgrade.needsMetadataRefresh())
            assertEquals("Original note", tables.games.getValue("genesis/Shared.md").hook)
            assertEquals(ids, tables.games.keys)
            assertTrue(detached.renameTo(roms))

            tables.failNextWrite = true
            val failed = upgrade.run()
            assertTrue(failed.problems.isNotEmpty())
            assertTrue(upgrade.needsMetadataRefresh())
            assertEquals("Original note", tables.games.getValue("genesis/Shared.md").hook)

            // A later ordinary foreground refresh must still be eligible after either incomplete attempt.
            assertEquals(3, upgrade.run().updated)
            assertEquals("Revised note", tables.games.getValue("genesis/Shared.md").hook)
            assertEquals("Revised note", tables.games.getValue("genesis/Generated.md").hook)
            assertNull(tables.games.getValue("genesis/Local.md").hook)
            assertEquals(5, tables.games.getValue("genesis/Local.md").fit)
            assertFalse(upgrade.needsMetadataRefresh())
            assertEquals(0, upgrade.run().updated)

            val retired = Indexer(tables.dao, manifests, guide("three", null))
            assertTrue(retired.needsMetadataRefresh())
            assertEquals(3, retired.run().updated)
            val shared = tables.games.getValue("genesis/Shared.md")
            assertNull(shared.hook)
            assertNull(shared.controls)
            assertEquals("", shared.flags)
            assertFalse(shared.handWritten)
            val kept = tables.games.getValue("genesis/Generated.md")
            assertNull(kept.hook)
            assertEquals(4, kept.fit)
            assertEquals("Local mapping", kept.controls)
            assertEquals("local flag", kept.flags)
            assertEquals("personal", kept.tags)
            assertEquals(ids, tables.games.keys)
            assertEquals(added, tables.games.mapValues { it.value.addedAt })
            assertEquals(localBytes, localFile.readText())
            assertFalse(retired.needsMetadataRefresh())
        } finally {
            scope.cancel()
        }
    }
}
