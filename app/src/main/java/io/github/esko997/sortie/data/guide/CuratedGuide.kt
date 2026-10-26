package io.github.esko997.sortie.data.guide

import android.content.Context
import android.util.Log
import io.github.esko997.sortie.data.magazine.GameNames
import io.github.esko997.sortie.data.magazine.SystemFamilies
import io.github.esko997.sortie.data.magazine.localKeys
import io.github.esko997.sortie.data.manifest.GuideEntry
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The curated guide bundled with the app (assets/guide/catalog.json, built by tools/guide/import-blurbs.py from authored blurbs):
 * short hooks, control notes and cautions for a set of games, keyed by portable identities rather than file names. A work is
 * one game across its versions; each variant is one platform/edition of it and answers to explicit match names.
 *
 * Matching reuses the magazine rules (GameNames.key, localKeys, SystemFamilies) instead of a second convention: same platform
 * (exact system id; "arcade" covers the arcade-generation systems), equal normalised names, region/language/disc/revision tags
 * ignored and edition, hack and translation qualifiers kept. Nothing is fuzzy, there are no series or prefix matches, and a
 * file that two variants could claim attaches to neither unless exactly one of them matches its file name.
 *
 * The card's guide.json stays in charge ([GuidePrecedence]); the catalogue only fills what it leaves empty. It carries no fit
 * scores or taste tags, so a game it describes has fit 0 and no tags unless guide.json gives them.
 */

@Serializable
data class CuratedCatalogFile(val format: Int = 0, val source: CuratedSource = CuratedSource(), val works: List<CuratedWork> = emptyList())

/** Accounting for the authored source the catalogue was built from (see tools/guide/import-report.json). */
@Serializable
data class CuratedSource(
    val sha256: String = "",
    val records: Int = 0,
    val published: Int = 0,
    val edited: Int = 0,
    val merged: Int = 0,
    val excluded: Int = 0
)

/** One game across its versions. [id] is stable: later title or alias changes never rename it. */
@Serializable
data class CuratedWork(val id: String, val title: String = "", val variants: List<CuratedVariant> = emptyList())

/** One platform/edition of a work. [edition] is a reader-facing description, not a matching rule; [matchNames] are. */
@Serializable
data class CuratedVariant(
    val id: String,
    val platform: String,
    val title: String = "",
    val edition: String = "",
    val matchNames: List<String> = emptyList(),
    val hook: String = "",
    val controls: String = "",
    val flags: List<String> = emptyList()
)

/** A loaded catalogue. [stamp] changes with its bytes, so an app update with new prose re-indexes cached games. */
class CuratedCatalog private constructor(val digest: String, val works: List<CuratedWork>) {
    data class Match(val work: CuratedWork, val variant: CuratedVariant)

    val stamp: String = if (digest.isEmpty()) "curated:none" else "curated:$digest"

    private val byKey: Map<String, List<Match>> = HashMap<String, MutableList<Match>>().also { index ->
        for (w in works) {
            for (v in w.variants) {
                for (k in v.matchNames.map(GameNames::key).filter { it.isNotEmpty() }.distinct()) index.getOrPut(k) { ArrayList() } += Match(w, v)
            }
        }
    }

    val variantCount: Int get() = works.sumOf { it.variants.size }

    /**
     * The variant describing a local file, or null. [title] is the indexed title (gamelist name or cleaned file name) and
     * [hack] the file's hack classification so far; both are used exactly as magazine references use them (see localKeys).
     */
    internal fun match(systemId: String, fileName: String, title: String, hack: Boolean, families: SystemFamilies): Match? {
        fun candidates(keys: Collection<String>) =
            keys.flatMap { byKey[it].orEmpty() }.filter { families.covers(it.variant.platform, systemId) }.distinctBy { it.variant.id }
        val found = candidates(localKeys(fileName, title, hack, families.isArcade(systemId)))
        if (found.size <= 1) return found.firstOrNull()
        // a name the file itself carries outranks a scraped title; anything still ambiguous attaches nothing
        return candidates(listOf(GameNames.key(GameNames.stem(fileName)))).singleOrNull()
    }

    companion object {
        const val FORMAT = 1
        private const val TAG = "CuratedGuide"
        private val json = Json { ignoreUnknownKeys = true }

        val EMPTY = CuratedCatalog("", emptyList())

        /**
         * Parses catalogue bytes. Throws on malformed JSON or an unknown format. Variants without a platform, a match name or a
         * hook are dropped: they could never be shown.
         */
        fun parse(bytes: ByteArray): CuratedCatalog {
            val file = json.decodeFromString(CuratedCatalogFile.serializer(), bytes.decodeToString())
            require(file.format == FORMAT) { "unsupported curated guide format ${file.format}" }
            val works = file.works.mapNotNull { w ->
                val usable = w.variants.filter { v -> v.platform.isNotBlank() && v.hook.isNotBlank() && v.matchNames.any { it.isNotBlank() } }
                if (w.id.isBlank() || usable.isEmpty()) null else w.copy(variants = usable)
            }
            return CuratedCatalog(sha256(bytes), works)
        }

        /** [parse], or [EMPTY] when there are no bytes or they cannot be read: the curated layer is optional. */
        fun load(bytes: ByteArray?): CuratedCatalog {
            if (bytes == null) return EMPTY
            return try {
                parse(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "bundled curated guide unreadable; continuing without it", e)
                EMPTY
            }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** The catalogue shipped in the APK, read once on first use (by the indexer, off the main thread). */
class CuratedGuide(private val read: () -> ByteArray?) {
    val catalog: CuratedCatalog by lazy { CuratedCatalog.load(read()) }

    companion object {
        const val ASSET = "guide/catalog.json"

        fun forApp(context: Context): CuratedGuide = CuratedGuide {
            try {
                context.assets.open(ASSET).use { it.readBytes() }
            } catch (e: java.io.IOException) {
                null
            }
        }
    }
}

/**
 * How the card's guide.json and the bundled catalogue combine for one game. guide.json always wins:
 * - an individually authored entry (`auto: false`) is the curator's complete statement and is used as it is, blanks included;
 * - a generated entry (`auto: true`, typically fit and tags with no hook) keeps its fit, tags and any hook, controls or flags it
 *   has, and the catalogue fills only the hook, controls and flags it leaves empty; once a catalogue hook is used the game
 *   counts as hand-written;
 * - without a guide.json entry the catalogue entry is used with fit 0 and no tags.
 */
object GuidePrecedence {
    fun effective(external: GuideEntry?, bundled: CuratedVariant?): GuideEntry? = when {
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
