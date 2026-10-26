package io.github.esko997.sortie.data.guide

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CartridgeFingerprintsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(16 * 1024) { (it % 251).toByte() }
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')
    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }
    private fun zip(name: String, vararg members: Pair<String, ByteArray>): File = tmp.newFile(name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((path, bytes) in members) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun catalog(system: String, representation: String = "raw"): CuratedCatalog {
        val entry = CuratedEntry(
            id = "00000000-0000-4000-8000-000000000001",
            system = system,
            hook = "Verified payload's note.",
            aliases = listOf("Original (USA).md"),
            fingerprints = listOf(CatalogFingerprint("crc32", crc(payload), representation, payload.size.toLong(), "payload-verified", "Regression payload"))
        )
        return CuratedCatalog.parse(Json.encodeToString(CuratedCatalogFile.serializer(), CuratedCatalogFile(1, "test", listOf(entry))).toByteArray())
    }

    @Test
    fun `renamed raw cartridge and renamed single-member ZIP attach same stable identity`() {
        val c = catalog("genesis")
        val raw = file("My name.md", payload)
        val archived = zip("Another name.zip", "nested/whatever.bin" to payload)
        assertEquals(c.entries.single().id, c.matchFile("genesis", raw)?.id)
        assertEquals(c.entries.single().id, c.matchFile("megadrive", archived)?.id)
        assertNull(c.matchFile("snes", raw))
        assertNull(c.matchFile("custom", raw))
        assertEquals(PayloadFingerprint(crc(payload), "raw", payload.size.toLong()), CartridgeFingerprints.read("genesis", archived).single())
    }

    @Test
    fun `a changed payload cannot inherit the retail note merely from a similar patched name`() {
        val changed = payload.copyOf().apply { this[25] = (this[25] + 1).toByte() }
        assertNull(catalog("genesis").matchFile("genesis", file("Original (USA) (Hack).md", changed)))
    }

    @Test
    fun `multimember archives and unsupported dump representations are not fingerprinted`() {
        assertTrue(CartridgeFingerprints.read("genesis", zip("set.zip", "one.md" to payload, "two.md" to payload)).isEmpty())
        assertTrue(CartridgeFingerprints.read("genesis", file("interleaved.smd", payload)).isEmpty())
        assertTrue(CartridgeFingerprints.read("snes", file("copier.smc", payload)).isEmpty())
        assertTrue(CartridgeFingerprints.read("ps2", file("disc.iso", payload)).isEmpty())
        assertTrue(CartridgeFingerprints.read("genesis", file("unknown.7z", payload)).isEmpty())
    }

    @Test
    fun `oversized and empty raw payloads are skipped without scanning bytes`() {
        val large = tmp.newFile("large.gba")
        RandomAccessFile(large, "rw").use { it.setLength(CartridgeFingerprints.MAX_BYTES + 1) }
        assertTrue(CartridgeFingerprints.read("gba", large).isEmpty())
        assertTrue(CartridgeFingerprints.read("gba", tmp.newFile("empty.gba")).isEmpty())
    }

    @Test
    fun `NES checksum omits header and trainer but validates declared PRG CHR layout`() {
        val header = ByteArray(16).apply {
            this[0] = 0x4e
            this[1] = 0x45
            this[2] = 0x53
            this[3] = 0x1a
            this[4] = 1
        }
        val c = catalog("nes", "nes-prg-chr")
        val expected = PayloadFingerprint(crc(payload), "nes-prg-chr", payload.size.toLong())
        assertEquals(expected, CartridgeFingerprints.read("nes", file("renamed.nes", header + payload)).single())
        val trainerHeader = header.copyOf().apply { this[6] = 4 }
        val trained = file("trainer.nes", trainerHeader + ByteArray(512) { 7 } + payload)
        assertEquals(c.entries.single().id, c.matchFile("nes", trained)?.id)
        assertEquals(expected, CartridgeFingerprints.read("nes", zip("nes.zip", "renamed.nes" to (header + payload))).single())
        assertTrue(CartridgeFingerprints.read("nes", file("truncated.nes", header + payload.copyOf(100))).isEmpty())
        assertTrue(CartridgeFingerprints.read("nes", file("trailing.nes", header + payload + byteArrayOf(0))).isEmpty())
        assertTrue(CartridgeFingerprints.read("nes", file("headerless.nes", payload)).isEmpty())
        val nes2 = header.copyOf().apply { this[7] = 8 }
        assertTrue(CartridgeFingerprints.read("nes", file("nes2.nes", nes2 + payload)).isEmpty())
    }
}
