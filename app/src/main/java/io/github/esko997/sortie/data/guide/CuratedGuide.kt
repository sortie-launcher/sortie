package io.github.esko997.sortie.data.guide

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import io.github.esko997.sortie.data.manifest.GuideEntry
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

@Serializable
data class CuratedCatalogFile(val schemaVersion: Int = 0, val revision: String = "", val entries: List<CuratedEntry> = emptyList())

/** A platform-specific edition. The immutable UUID is independent of both prose and the user's local game id. */
@Serializable
data class CuratedEntry(
    val id: String,
    val system: String,
    val title: String = "",
    val aliases: List<String> = emptyList(),
    val hook: String,
    val controls: String = "",
    val flags: List<String> = emptyList(),
    val parentId: String? = null,
    val fingerprints: List<CatalogFingerprint> = emptyList()
)

/** An expected release checksum is evidence about a release, not a claim that the user's bytes were verified. */
@Serializable
data class CatalogFingerprint(
    val algorithm: String,
    val value: String,
    val representation: String,
    val size: Long,
    val verification: String,
    val source: String
) {
    internal fun key(): PayloadFingerprint? = if (
        algorithm == "crc32" && value.matches(CRC32) && size > 0 &&
        representation in REPRESENTATIONS && verification in VERIFICATIONS && source.isNotBlank()
    ) {
        PayloadFingerprint(value.lowercase(), representation, size)
    } else {
        null
    }

    companion object {
        private val CRC32 = Regex("[0-9a-fA-F]{8}")
        private val REPRESENTATIONS = setOf("raw", "nes-prg-chr")
        private val VERIFICATIONS = setOf("dat-release", "payload-verified")
    }
}

/** A measured payload, never an archive's CRC or an inferred scraped title. */
internal data class PayloadFingerprint(val crc32: String, val representation: String, val size: Long)

/** Immutable indexes, loaded once. No title cleanup, patch stripping, parent inheritance or fuzzy matching. */
class CuratedCatalog private constructor(val revision: String, private val digest: String, val entries: List<CuratedEntry>) {
    val stamp: String = "shared-blurbs:1:$revision:$digest"

    private data class AliasKey(val system: String, val alias: String)
    private data class FingerprintKey(val system: String, val payload: PayloadFingerprint)

    // A key needs only one entry index, or AMBIGUOUS: retaining a hash set for every alias wastes most of its capacity.
    private val byAlias = HashMap<AliasKey, Int>()
    private val byFingerprint = HashMap<FingerprintKey, Int>()
    private val fingerprintSystems = HashSet<String>()

    init {
        for ((index, entry) in entries.withIndex()) {
            val system = platform(entry.system)
            for (alias in entry.aliases) register(byAlias, AliasKey(system, alias.lowercase(Locale.ROOT)), index)
            for (fingerprint in entry.fingerprints) {
                val key = fingerprint.key() ?: continue
                register(byFingerprint, FingerprintKey(system, key), index)
                fingerprintSystems.add(system)
            }
        }
    }

    private fun <K> register(index: MutableMap<K, Int>, key: K, entry: Int) {
        val previous = index.putIfAbsent(key, entry)
        if (previous != null && previous != entry) index[key] = AMBIGUOUS
    }

    private fun aliases(system: String, fileName: String): Int? {
        val folded = fileName.lowercase(Locale.ROOT)
        val full = byAlias[AliasKey(system, folded)]
        // A curated extensionless alias may cross known containers, but never turns a patch/sidecar into its retail game.
        if (folded.substringAfterLast('.', "") !in ROM_EXTENSIONS) return full
        val stem = byAlias[AliasKey(system, folded.substringBeforeLast('.'))]
        if (full == null) return stem
        if (stem == null) return full
        return if (full == stem) full else AMBIGUOUS
    }

    internal fun match(systemId: String, fileName: String, fingerprints: List<PayloadFingerprint> = emptyList()): CuratedEntry? {
        val system = platform(systemId)
        var identified: Int? = null
        for (fingerprint in fingerprints) {
            val candidate = byFingerprint[FingerprintKey(system, fingerprint)] ?: continue
            // Conflicting verified identities never fall back to a more convenient filename.
            if (candidate == AMBIGUOUS || (identified != null && identified != candidate)) return null
            identified = candidate
        }
        return entries.getOrNull(identified ?: aliases(system, fileName) ?: AMBIGUOUS)
    }

    /** Called only for rows whose size/mtime/path or metadata changed, on the indexer's IO dispatcher. */
    internal fun matchFile(systemId: String, file: File): CuratedEntry? {
        val system = platform(systemId)
        val named = aliases(system, file.name)
        if (named != null && named != AMBIGUOUS) return entries[named]
        if (system !in fingerprintSystems) return null
        val fingerprints = CartridgeFingerprints.read(system, file)
        return match(system, file.name, fingerprints)
    }

    companion object {
        private const val TAG = "CuratedGuide"
        private const val AMBIGUOUS = -1
        private val json = Json { ignoreUnknownKeys = true }
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ARCADE = setOf("arcade", "mame", "fbneo", "neogeo", "cps1", "cps2", "cps3", "toaplan-cave-stg", "stv", "model2", "naomi", "atomiswave")
        private val ROM_EXTENSIONS = setOf(
            "32x", "3ds", "7z", "a26", "a78", "apk", "bin", "cci", "cdi", "chd", "ciso", "col", "cso", "cue", "fds", "gb", "gba", "gbc", "gcm", "gdi",
            "gen", "gg", "iso", "lnx", "m3u", "md", "n64", "nds", "nes", "ngc", "ngp", "pbp", "pce", "rom", "rvz", "sfc", "sgx", "smc", "sms",
            "v64", "vpk", "wbfs", "ws", "wsc", "xiso", "z64", "zip"
        )

        /** Unknown IDs remain exact: a user label or generation must never opt a custom system into another platform. */
        internal fun platform(id: String): String = when (id) {
            "tg16" -> "pcengine"
            "megadrive" -> "genesis"
            in ARCADE -> "arcade"
            else -> id
        }

        val EMPTY = CuratedCatalog("", "", emptyList())

        private fun safeAlias(alias: String): Boolean = alias.isNotBlank() && '\\' !in alias && '\u0000' !in alias &&
            !(alias.length >= 2 && alias[0].isLetter() && alias[1] == ':') &&
            alias.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        fun parse(bytes: ByteArray): CuratedCatalog = bytes.inputStream().use(::parse)

        /** Decode directly from UTF-8; ignored public provenance never needs a whole-file byte array or UTF-16 string. */
        @OptIn(ExperimentalSerializationApi::class)
        fun parse(input: InputStream): CuratedCatalog {
            val digest = MessageDigest.getInstance("SHA-256")
            val file = json.decodeFromStream(CuratedCatalogFile.serializer(), DigestInputStream(input, digest))
            require(file.schemaVersion == 1) { "unsupported shared blurb schema ${file.schemaVersion}" }
            require(file.revision.isNotBlank()) { "missing shared blurb revision" }
            val ids = HashSet<String>()
            for (entry in file.entries) {
                require(UUID.matches(entry.id) && ids.add(entry.id)) { "invalid or duplicate shared blurb UUID ${entry.id}" }
                require(entry.system.isNotBlank() && entry.hook.isNotBlank()) { "missing shared blurb platform or hook" }
                require(entry.aliases.all(::safeAlias)) { "aliases must be exact filenames, stems or safe system-relative paths" }
                require(entry.fingerprints.all { it.key() != null }) { "unsupported or unverified fingerprint for ${entry.id}" }
            }
            require(file.entries.all { it.parentId == null || (it.parentId != it.id && it.parentId in ids) }) { "invalid shared blurb parent" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return CuratedCatalog(file.revision, hash, file.entries)
        }

        fun load(input: InputStream): CuratedCatalog = try {
            parse(input)
        } catch (e: Exception) {
            Log.w(TAG, "bundled shared blurbs unreadable; continuing without them", e)
            EMPTY
        }
    }
}

/** Read once on first use, off the main thread. The immutable APK catalog is shared across application graphs. */
class CuratedGuide(load: () -> CuratedCatalog) {
    val catalog: CuratedCatalog by lazy(load)

    companion object {
        const val ASSET = "game-blurbs.json"
        private var appCatalog: CuratedCatalog? = null

        fun forApp(context: Context): CuratedGuide {
            val assets = context.assets
            return CuratedGuide { bundled(assets) }
        }

        @Synchronized
        private fun bundled(assets: AssetManager): CuratedCatalog {
            appCatalog?.let { return it }
            val loaded = try {
                assets.open(ASSET).use { CuratedCatalog.load(it) }
            } catch (e: java.io.IOException) {
                CuratedCatalog.EMPTY
            }
            appCatalog = loaded
            return loaded
        }
    }
}

/** Local authored entries are complete (even blank fields). Generated local entries keep every nonempty field and personal fit/tag. */
object GuidePrecedence {
    fun effective(external: GuideEntry?, bundled: CuratedEntry?): GuideEntry? = when {
        bundled == null -> external
        external == null -> GuideEntry(fit = 0, hook = bundled.hook, controls = bundled.controls, flags = bundled.flags, tags = emptyList(), auto = false)
        !external.auto -> external
        else -> external.copy(
            hook = external.hook.ifBlank { bundled.hook },
            controls = external.controls.ifBlank { bundled.controls },
            flags = external.flags.ifEmpty { bundled.flags },
            auto = external.hook.isNotBlank()
        )
    }
}
