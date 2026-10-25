package io.github.esko997.sortie.testing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * The deterministic sortie card the UI tests run against: the manifests and magazine text under
 * `src/testDebug/resources/fixtures/card/`, plus ROM stubs and covers/figures generated here as small PNG test patterns.
 * Every file gets a fixed modification time, so the index (and anything sorted by it) is the same on every run.
 */
object FixtureCard {
    /**
     * The card's library: three systems with a handful of invented games. At most one game per system has a cover, so the
     * cover Home's system tile borrows (the best-rated game with one; ties are arbitrary) is always the same.
     */
    val games = listOf(
        Game("genesis", "Ace Sortie (World).md", 0xE11D48),
        Game("genesis", "Bolt Runner (World).md", null),
        Game("genesis", "Cinder Vale (Europe).md", null),
        Game("snes", "Night Lancer (USA).sfc", 0x6366F1),
        Game("snes", "Quiet Harbor (USA).sfc", null),
        Game("psx", "Tin Soldier Garage (USA).chd", 0x0EA5E9)
    )

    data class Game(val system: String, val file: String, val coverRgb: Int?) {
        val id: String get() = "$system/$file"
    }

    private const val RESOURCES = "fixtures/card/"
    private val TEXT_FILES = listOf(
        "sortie/device.json",
        "sortie/systems.json",
        "sortie/launchers.json",
        "sortie/folders.json",
        "sortie/user.json",
        "sortie/magazines/field-notes-007/issue.json",
        "sortie/magazines/field-notes-007/articles/bolt-and-wrench.md",
        "sortie/magazines/fixture-special/issue.json",
        "sortie/magazines/fixture-special/articles/long-read.md",
        "sortie/magazines/fixture-special/articles/short-note.md"
    )

    /** 2026-09-01T00:00Z: every fixture file's modification time. */
    const val FILE_TIME = 1_788_220_800_000L

    /** Writes the card into [root] (the card root; manifests go to `root/sortie/`) and returns its `sortie` folder. */
    fun writeTo(root: File): File {
        for (path in TEXT_FILES) {
            val text = checkNotNull(FixtureCard::class.java.classLoader!!.getResource(RESOURCES + path)) { "missing fixture $path" }.readText()
            write(root, path, text.replace("@CARD_ROOT@", root.absolutePath).toByteArray())
        }
        for (g in games) {
            write(root, "roms/${g.system}/${g.file}", ByteArray(16) { it.toByte() })
            g.coverRgb?.let { write(root, "media/${g.system}/covers/${g.file.substringBeforeLast('.')}.png", pattern(240, 320, it)) }
        }
        val issue = "sortie/magazines/fixture-special/media"
        write(root, "$issue/cover.png", pattern(300, 400, 0xB45309))
        write(root, "$issue/lead.png", pattern(960, 400, 0x0F766E))
        write(root, "$issue/portrait.png", pattern(240, 360, 0x7C3AED))
        write(root, "$issue/wide.png", pattern(1200, 400, 0xBE123C))
        write(root, "sortie/magazines/field-notes-007/media/cover.png", pattern(300, 400, 0x1D4ED8))
        root.walkTopDown().forEach { it.setLastModified(FILE_TIME) }
        return File(root, "sortie")
    }

    private fun write(root: File, path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    /**
     * A [width]×[height] PNG test pattern: [rgb] darkening downwards, diagonal light stripes and a centred dark block. Encoded
     * here (8-bit RGB, no filtering) so the bytes depend on nothing but the arguments.
     */
    private fun pattern(width: Int, height: Int, rgb: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0) // filter: none
            val shade = 1f - 0.5f * y / height
            for (x in 0 until width) {
                val block = x in width / 4 until width * 3 / 4 && y in height * 2 / 5 until height * 3 / 5
                val lift = if (((x + y) / 24) % 2 == 0) 1.15f else 1f
                for (shift in intArrayOf(16, 8, 0)) {
                    val v = (rgb shr shift) and 0xFF
                    raw.write(if (block) 0x18 else (v * shade * lift).toInt().coerceIn(0, 255))
                }
            }
        }
        val png = ByteArrayOutputStream()
        val out = DataOutputStream(png)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size)
            val body = type.toByteArray() + data
            out.write(body)
            out.writeInt(CRC32().apply { update(body) }.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 2, 0, 0, 0)) // 8-bit RGB, deflate, no filter, no interlace
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", ByteArrayOutputStream().also { z -> DeflaterOutputStream(z).use { it.write(raw.toByteArray()) } }.toByteArray())
        chunk("IEND", ByteArray(0))
        return png.toByteArray()
    }
}
