package io.github.esko997.sortie.data.index

/**
 * A title without the file-name noise ROM sets carry: "(USA)", "(Disc 1)", "[!]", "v1.1.1", "Rev A", "Rev 1". Words that merely
 * start with "Rev" (Revenge, Revelations) are kept: a revision marker needs a digit, or a lone letter after a space.
 */
fun cleanTitle(title: String): String = title.replace(TITLE_NOISE, " ").replace(SPACES, " ").trim().ifEmpty { title }

private val TITLE_NOISE = Regex("""\s*(\([^)]*\)|\[[^]]*]|\bv\d+(\.\d+)*[a-z]?\b|\bRev\.?\s?\d+[A-Z]?\b|\bRev\s[A-Z]\b)""", RegexOption.IGNORE_CASE)
private val SPACES = Regex("""\s{2,}""")
