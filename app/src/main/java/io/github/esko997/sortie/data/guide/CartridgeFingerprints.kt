package io.github.esko997.sortie.data.guide

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Only unmatched small cartridge payloads are read. Discs, interleaved dumps and unknown containers never enter this path. */
internal object CartridgeFingerprints {
    const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 32
    private val extensions = mapOf(
        "nes" to setOf("nes"),
        "snes" to setOf("sfc"),
        "genesis" to setOf("md", "gen", "bin"),
        "gb" to setOf("gb"),
        "gbc" to setOf("gbc"),
        "gba" to setOf("gba"),
        "pcengine" to setOf("pce"),
        "supergrafx" to setOf("sgx", "pce"),
        "ngp" to setOf("ngp", "ngc"),
        "ngpc" to setOf("ngc"),
        "mastersystem" to setOf("sms"),
        "gamegear" to setOf("gg")
    )

    fun read(system: String, file: File): List<PayloadFingerprint> {
        val allowed = extensions[system] ?: return emptyList()
        val size = file.length()
        if (size !in 1..MAX_BYTES) return emptyList()
        val mtime = file.lastModified()
        val result = try {
            when (file.extension.lowercase()) {
                "zip" -> zip(file, system, allowed)
                in allowed -> file.inputStream().buffered().use { payload(it, size, system) }
                else -> emptyList()
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        // A transfer in progress must not publish the identity of a partial/previous payload.
        return if (file.length() == size && file.lastModified() == mtime) result else emptyList()
    }

    private fun zip(file: File, system: String, allowed: Set<String>): List<PayloadFingerprint> = ZipFile(file).use { zip ->
        if (zip.size() > MAX_ZIP_ENTRIES) return emptyList()
        // Multi-file sets, including arcade chips and ambiguous cartridge archives, have no single payload identity.
        val entries = zip.entries()
        var found: java.util.zip.ZipEntry? = null
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            if (found != null) return emptyList()
            found = entry
        }
        val member = found ?: return emptyList()
        if (member.name.substringAfterLast('.', "").lowercase() !in allowed || member.size !in 1..MAX_BYTES || member.crc < 0) return emptyList()
        if (system == "nes") {
            zip.getInputStream(member).buffered().use { payload(it, member.size, system) }
        } else {
            // ZIP stores the CRC and uncompressed size of this exact member; never compare the archive's own checksum.
            listOf(PayloadFingerprint(hex(member.crc), "raw", member.size))
        }
    }

    private fun payload(input: InputStream, size: Long, system: String): List<PayloadFingerprint> {
        var length = size
        var representation = "raw"
        if (system == "nes") {
            val header = ByteArray(16)
            if (!readFully(input, header)) return emptyList()
            if (header[0] != 0x4e.toByte() || header[1] != 0x45.toByte() || header[2] != 0x53.toByte() || header[3] != 0x1a.toByte()) return emptyList()
            // NES 2.0 changes size encoding; do not pretend unsupported containers have iNES1 semantics.
            if ((header[7].toInt() and 0x0c) != 0 || (12..15).any { header[it] != 0.toByte() }) return emptyList()
            val trainer = if ((header[6].toInt() and 4) != 0) 512 else 0
            length = (header[4].toInt() and 0xff) * 16384L + (header[5].toInt() and 0xff) * 8192L
            if (length <= 0 || size != 16L + trainer + length) return emptyList()
            if (trainer > 0 && !readFully(input, ByteArray(trainer))) return emptyList()
            representation = "nes-prg-chr"
        }
        val crc = CRC32()
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) return emptyList()
            crc.update(buffer, 0, count)
            remaining -= count
        }
        if (input.read() != -1) return emptyList()
        return listOf(PayloadFingerprint(hex(crc.value), representation, length))
    }

    private fun readFully(input: InputStream, bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) return false
            offset += count
        }
        return true
    }

    private fun hex(value: Long): String = value.toString(16).padStart(8, '0')
}
