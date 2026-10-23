package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FolderDef
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Everything a rule may look at for one folder candidate: a game (with its index row), an installed app or a launcher shortcut.
 * Play data and favourites come from outside the games table and apply to every kind; game-only fields are null elsewhere.
 * [pkg] is the app's package or the shortcut's publishing package; [source] is set for shortcuts only (the publishing package).
 */
data class Subject(
    val id: String,
    val kind: Kind,
    val label: String,
    val pkg: String? = null,
    val source: String? = null,
    val game: GameEntity? = null,
    val playMinutes: Int = 0,
    val lastPlayed: Long? = null,
    val favourite: Boolean = false,
    val now: Long = System.currentTimeMillis()
) {
    constructor(game: GameEntity, playMinutes: Int, lastPlayed: Long?, favourite: Boolean, now: Long = System.currentTimeMillis()) :
        this(
            game.id,
            Kind.GAME,
            game.title,
            game = game,
            playMinutes = playMinutes,
            lastPlayed = lastPlayed,
            favourite = favourite,
            now = now
        )

    val sortLabel: String get() = game?.sortTitle ?: label.lowercase()

    enum class Kind(val key: String) { GAME("game"), APP("app"), SHORTCUT("shortcut") }
}

/**
 * Evaluates `folders.json` rules (docs/formats/folders.md). A rule is a JSON object: combinators `all`/`any`/`not`,
 * field predicates `{field: {op: value}}`, or booleans `favourite`/`hack`. Unknown fields or operators evaluate false and are
 * reported through [problems], never thrown, so a typo in a manifest cannot crash the launcher. A field the subject does not
 * have (a game-only field on an app or shortcut, `package` on a game) is a non-match, not a problem.
 */
class RuleEngine(rule: JsonElement?) {
    private val issues = mutableListOf<String>() // must be initialised before compile() runs
    private val compiled: Node = compile(rule)
    val problems: List<String> get() = issues

    fun matches(s: Subject): Boolean = compiled(s)

    private fun compile(e: JsonElement?): Node {
        if (e == null || e is JsonNull) return { true }
        val obj = e as? JsonObject ?: return bad("rule must be an object")
        val nodes = obj.entries.map { (k, v) -> compileEntry(k, v) }
        return { s -> nodes.all { it(s) } } // several keys in one object are implicitly ANDed
    }

    private fun compileEntry(key: String, v: JsonElement): Node = when (key) {
        "all" -> {
            val ns = v.arr().map(::compile);
            { s -> ns.all { it(s) } }
        }
        "any" -> {
            val ns = v.arr().map(::compile);
            { s -> ns.any { it(s) } }
        }
        "not" -> {
            val n = compile(v);
            { s -> !n(s) }
        }
        "favourite" -> {
            val want = v.bool();
            { s -> s.favourite == want }
        }
        "hack" -> {
            val want = v.bool();
            { s -> s.game?.let { it.hack == want } ?: false }
        }
        "handWritten" -> {
            val want = v.bool();
            { s -> s.game?.let { it.handWritten == want } ?: false }
        }
        else -> compileField(key, v)
    }

    private fun compileField(field: String, v: JsonElement): Node {
        val ops = v as? JsonObject ?: return bad("$field: expected {op: value}")
        val tests = ops.entries.map { (op, value) -> compileOp(field, op, value) }
        return { s -> tests.all { it(s) } }
    }

    private fun compileOp(field: String, op: String, value: JsonElement): Node {
        val text: ((Subject) -> String?)? = when (field) {
            "name", "title" -> { s -> s.label }
            "kind" -> { s -> s.kind.key }
            "package" -> { s -> s.pkg }
            "source" -> { s -> s.source }
            "path" -> { s -> s.game?.path }
            "file" -> { s -> s.game?.fileName }
            "system" -> { s -> s.game?.systemId }
            "genre" -> { s -> s.game?.genre }
            "developer" -> { s -> s.game?.developer }
            "publisher" -> { s -> s.game?.publisher }
            "desc" -> { s -> s.game?.desc }
            else -> null
        }
        val num: ((Subject) -> Double?)? = when (field) {
            "players" -> { s -> s.game?.playersMax?.toDouble() }
            "year" -> { s -> s.game?.year?.toDouble() }
            "fit" -> { s -> s.game?.fit?.toDouble() }
            "rating" -> { s -> s.game?.rating?.toDouble() }
            "playMinutes" -> { s -> s.playMinutes.toDouble() }
            "daysSincePlayed" -> { s -> s.lastPlayed?.let { TimeUnit.MILLISECONDS.toDays(s.now - it).toDouble() } }
            "daysSinceAdded" -> { s -> s.game?.let { TimeUnit.MILLISECONDS.toDays(s.now - it.addedAt).toDouble() } }
            else -> null
        }
        val list: ((Subject) -> List<String>?)? = when (field) {
            "tags" -> { s -> s.game?.tags?.split(',')?.filter { it.isNotBlank() } }
            "flags" -> { s -> s.game?.flags?.split(',')?.filter { it.isNotBlank() } }
            else -> null
        }
        return when {
            text != null -> when (op) {
                "regex" -> {
                    val rx = runCatching { Regex(value.str()) }.getOrElse { return bad("$field.regex: ${it.message}") };
                    { s -> text(s)?.let(rx::containsMatchIn) ?: false }
                }
                "eq" -> {
                    val want = value.str().lowercase();
                    { s -> text(s)?.lowercase() == want }
                }
                "in" -> {
                    val set = value.arr().map { it.str().lowercase() }.toSet();
                    { s -> text(s)?.lowercase() in set }
                }
                "contains" -> {
                    val want = value.str().lowercase();
                    { s -> text(s)?.lowercase()?.contains(want) ?: false }
                }
                else -> bad("$field: unknown operator $op")
            }
            num != null -> {
                val want = value.num() ?: return bad("$field.$op: expected a number")
                val test: ((Double) -> Boolean)? = when (op) {
                    "gte" -> { v -> v >= want }
                    "lte" -> { v -> v <= want }
                    "gt" -> { v -> v > want }
                    "lt" -> { v -> v < want }
                    "eq" -> { v -> v == want }
                    else -> null
                }
                if (test == null) bad("$field: unknown operator $op") else { s -> num(s)?.let(test) ?: false }
            }
            list != null -> when (op) {
                "has" -> {
                    val want = value.str().lowercase();
                    { s -> list(s)?.contains(want) ?: false }
                }
                "hasAny" -> {
                    val want = value.arr().map { it.str().lowercase() }.toSet();
                    { s -> list(s)?.any { it in want } ?: false }
                }
                "hasAll" -> {
                    val want = value.arr().map { it.str().lowercase() };
                    { s -> list(s)?.containsAll(want) ?: false }
                }
                else -> bad("$field: unknown operator $op")
            }
            else -> bad("unknown field $field")
        }
    }

    private fun bad(msg: String): Node {
        issues += msg
        return { false }
    }

    private fun JsonElement.arr(): List<JsonElement> = (this as? JsonArray)?.jsonArray ?: run {
        issues += "expected an array"
        emptyList()
    }
    private fun JsonElement.str(): String = (this as? JsonPrimitive)?.contentOrNull ?: run {
        issues += "expected a string"
        ""
    }
    private fun JsonElement.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: run {
        issues += "expected true/false"
        true
    }

    /** One folder's members in folder order, plus the rule problems found while compiling it. */
    data class Evaluation(val subjects: List<Subject>, val problems: List<String>)

    companion object {
        /**
         * Evaluates [def] over [subjects] (games, apps and shortcuts). The rule only sees apps and shortcuts when the folder opts in
         * with `apps: true`; `include` and `exclude` list ids of any kind and always win.
         */
        fun evaluate(def: FolderDef, subjects: List<Subject>): Evaluation {
            val engine = RuleEngine(def.rule)
            val include = def.include.toSet()
            val exclude = def.exclude.toSet()
            val matched = subjects.filter { s ->
                s.id !in exclude && (s.id in include || (def.rule != null && (def.apps || s.kind == Subject.Kind.GAME) && engine.matches(s)))
            }
            val sorted = matched.sortedWith(comparator(def.sort)).let { if (def.limit > 0) it.take(def.limit) else it }
            return Evaluation(sorted, engine.problems)
        }

        /** Parses `sort` strings such as `fit:desc,title:asc` into a comparator over subjects. */
        fun comparator(sort: String): Comparator<Subject> {
            val keys = sort.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("title:asc") }
            var cmp: Comparator<Subject>? = null
            for (k in keys) {
                val (field, dir) = k.split(':').let { it[0] to (it.getOrNull(1) ?: "asc") }
                val c: Comparator<Subject> = when (field) {
                    "fit" -> compareBy(nullsFirst()) { it.game?.fit }
                    "title" -> compareBy { it.sortLabel }
                    "year" -> compareBy(nullsFirst()) { it.game?.year }
                    "lastPlayed" -> compareBy(nullsFirst()) { it.lastPlayed }
                    "playMinutes" -> compareBy { it.playMinutes }
                    "added" -> compareBy(nullsFirst()) { it.game?.addedAt }
                    "system" -> compareBy(nullsFirst()) { it.game?.systemId }
                    else -> compareBy { it.sortLabel }
                }
                val d = if (dir == "desc") c.reversed() else c
                cmp = cmp?.then(d) ?: d
            }
            return cmp!!
        }
    }
}

private typealias Node = (Subject) -> Boolean
