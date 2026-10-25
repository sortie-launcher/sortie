package io.github.esko997.sortie.data.index

import io.github.esko997.sortie.data.manifest.GuideEntry
import io.github.esko997.sortie.data.manifest.LibraryRules
import java.util.Locale

/**
 * Decides [io.github.esko997.sortie.data.db.GameEntity.hack]: the file is a hack, fan translation or other patched build, not
 * the retail release. Positive evidence only, and only explicit metadata:
 * - the card's configurable `hackRegex` against the file name, and again with version numbers removed from its bracketed
 *   qualifiers, so a rule naming "(true analogs)" also covers "(True Analogs v1.03)";
 * - a qualifier that names the word hack, as in "(v1.21 Hack)" or "[Hack by X]" (not ".hack" or "hacker");
 * - a guide.json flag that says so ([FLAGS]).
 * Descriptions and guide hooks are never read: "hacking" or "analog controls" in retail prose says nothing about the file, and an
 * unrecognised qualifier is not evidence of anything.
 */
object HackMarks {
    /** guide.json flags that mark a patched build, compared lower-case. */
    private val FLAGS = setOf("hack", "fan translation", "translation")

    private val GROUP = Regex("""([(\[])([^()\[\]]*)([)\]])""")
    private val HACK_WORD = Regex("""(?<![\w.])hack(?!\w)""", RegexOption.IGNORE_CASE)
    private val VERSION = Regex("""\bv\d+(?:\.\d+)*[a-z]?\b|\b\d+(?:\.\d+)+\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    /** Bump whenever these rules change: the new [stamp] makes the next index pass reclassify cached rows. */
    private const val RULES_VERSION = 1

    /** Changes when the built-in rules or the configured `hackRegex` change; part of the indexer's per-system metadata stamp. */
    fun stamp(rules: LibraryRules): String = "hack$RULES_VERSION:${rules.hackRegex.orEmpty()}"

    /** The configured `hackRegex`, or null when there is none or it does not compile (the built-in rules still apply). */
    fun compile(rules: LibraryRules): Regex? = rules.hackRegex?.let { runCatching { Regex(it) }.getOrNull() }

    fun isHack(fileName: String, configured: Regex?, guide: GuideEntry?): Boolean {
        if (guide != null && guide.flags.any { it.trim().lowercase(Locale.ROOT) in FLAGS }) return true
        if (configured?.containsMatchIn(fileName) == true) return true
        var versionedQualifier = false
        for (group in GROUP.findAll(fileName)) {
            val qualifier = group.groupValues[2]
            if (HACK_WORD.containsMatchIn(qualifier)) return true
            if (configured != null && !versionedQualifier && VERSION.containsMatchIn(qualifier)) versionedQualifier = true
        }
        return configured != null && versionedQualifier && configured.containsMatchIn(withoutVersions(fileName))
    }

    /** "Armored Core 2 (USA) (True Analogs v1.0).iso" -> "Armored Core 2 (USA) (True Analogs).iso"; text outside brackets is kept. */
    private fun withoutVersions(fileName: String): String = GROUP.replace(fileName) { m ->
        val inner = m.groupValues[2]
        if (!VERSION.containsMatchIn(inner)) m.value else m.groupValues[1] + inner.replace(VERSION, " ").replace(SPACES, " ").trim() + m.groupValues[3]
    }
}
