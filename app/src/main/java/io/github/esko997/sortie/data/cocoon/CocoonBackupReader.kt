package io.github.esko997.sortie.data.cocoon

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A library item reference as Cocoon's backup writes it: `<platform>/<file>` or an Android package. [file] is the last path
 * segment (the whole name for `shortcut` entries, which are titles, not files); [packageName] is set for `android_app` only.
 */
data class CocoonRef(val platform: String, val file: String?, val packageName: String?)

/** A user-made Cocoon folder (is_smart_folder == false) and its game/app members in Cocoon's order. */
data class CocoonFolder(val id: Int, val name: String, val members: List<CocoonRef>)

/** One real play session from Cocoon's play history. */
data class CocoonSession(val ref: CocoonRef, val startedAt: Long, val endedAt: Long, val emulatorPackage: String?)

/** What the importer takes from one Cocoon unified backup. */
data class CocoonBackup(
    val dir: File,
    val createdAt: Long,
    val appVersion: String,
    val folders: List<CocoonFolder>,
    /** play_history recordType == "session" with endTime > startTime. */
    val sessions: List<CocoonSession>,
    /** metadata fields.playerId when non-null (raw, same grammar as `<cocoon:defaultplayerid>`). */
    val playerOverrides: Map<CocoonRef, String>,
    /** layout game_flags is_hidden. */
    val hidden: Set<CocoonRef>,
    /** platform → set of "<vol>:<dir>" from identity.normalized_document_id (dir = path without file name). */
    val roots: Map<String, Set<String>>,
    /** metadata fields.favorite; the only source of favourite apps (`android_app` has no gamelist). */
    val favourites: Set<CocoonRef> = emptySet()
)

/**
 * Reads a Cocoon unified backup (`manifest.json` + `categories/…`, made by the user in Cocoon → Settings). Read-only: files are
 * opened for reading and nothing in the backup directory is written. The JSONL categories are streamed line by line (the Thor's
 * metadata file is 22 MB); a malformed line is skipped rather than failing the backup.
 */
object CocoonBackupReader {
    private const val FORMAT = "cocoon_unified_backup"
    private const val ANDROID_APP = "android_app"
    private const val SHORTCUT = "shortcut"
    private const val MANIFEST = "manifest.json"
    private const val LAYOUT = "categories/layout/grid_v1.json"
    private const val PLAY_HISTORY = "categories/play_history/play_history_v1.jsonl"
    private const val METADATA = "categories/metadata/metadata_v1.jsonl"

    /** A manifest is a few KB; anything far larger is not one. */
    private const val MANIFEST_LIMIT = 4L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Unified backups (manifest.json with format "cocoon_unified_backup") at [dir] itself, among its direct subdirectories and
     * under its `backups/`, newest first. [dir] may be a Cocoon folder, a folder of backups or one backup, wherever it is kept.
     */
    fun find(dir: File): List<File> = findDated(dir).map { it.first }

    /** [find] with each backup's manifest `created_at` (the folder's mtime when absent), newest first. */
    fun findDated(dir: File): List<Pair<File, Long>> {
        fun children(d: File) = d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        val dirs = listOf(dir) + children(dir) + children(File(dir, "backups"))
        return dirs.mapNotNull { candidate ->
            val manifest = try {
                readManifest(candidate)
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            candidate to (manifest.long("created_at") ?: candidate.lastModified())
        }.sortedByDescending { it.second }
    }

    /** Streams the JSONL files; never loads metadata as one string. */
    @Throws(IOException::class)
    fun read(dir: File): CocoonBackup {
        val manifest = try {
            readManifest(dir)
        } catch (e: IllegalArgumentException) {
            throw IOException("${dir.path}/$MANIFEST is not valid JSON: ${e.message}", e)
        } ?: throw IOException("${dir.path} is not a Cocoon backup (no $MANIFEST with format \"$FORMAT\").")

        val folders = ArrayList<CocoonFolder>()
        val hidden = LinkedHashSet<CocoonRef>()
        File(dir, LAYOUT).takeIf { it.isFile }?.let { file ->
            val layout = try {
                json.parseToJsonElement(file.readText()) as? JsonObject
            } catch (e: IllegalArgumentException) {
                throw IOException("${file.path} is not valid JSON: ${e.message}", e)
            }
            if (layout != null) {
                folders += userFolders(layout)
                layout.array("game_flags").forEach { flag ->
                    if (flag.bool("is_hidden") == true) flag.obj("ref")?.let(::layoutRef)?.let { hidden += it }
                }
            }
        }

        val sessions = ArrayList<CocoonSession>()
        eachRecord(File(dir, PLAY_HISTORY)) { record ->
            if (record.str("recordType") != "session") return@eachRecord
            val ref = record.obj("identity")?.let(::identityRef) ?: return@eachRecord
            val session = record.obj("session") ?: return@eachRecord
            val start = session.long("startTime") ?: return@eachRecord
            val end = session.long("endTime") ?: return@eachRecord
            if (end > start) sessions += CocoonSession(ref, start, end, session.str("emulatorPackage"))
        }

        val overrides = LinkedHashMap<CocoonRef, String>()
        val favourites = LinkedHashSet<CocoonRef>()
        val roots = LinkedHashMap<String, MutableSet<String>>()
        eachRecord(File(dir, METADATA)) { record ->
            if (record.str("recordType") != "game") return@eachRecord
            val identity = record.obj("identity") ?: return@eachRecord
            val ref = identityRef(identity) ?: return@eachRecord
            val fields = record.obj("fields")
            fields?.str("playerId")?.trim()?.takeIf { it.isNotEmpty() }?.let { overrides[ref] = it }
            if (fields?.bool("favorite") == true) favourites += ref
            identity.str("normalized_document_id")?.let { id ->
                val volume = id.substringBefore(':', "")
                if (volume.isNotEmpty()) {
                    val dirPart = id.substringAfter(':').substringBeforeLast('/', "")
                    roots.getOrPut(ref.platform) { LinkedHashSet() } += "$volume:$dirPart"
                }
            }
        }

        return CocoonBackup(
            dir = dir,
            createdAt = manifest.long("created_at") ?: dir.lastModified(),
            appVersion = manifest.str("source_app_version") ?: "",
            folders = folders,
            sessions = sessions,
            playerOverrides = overrides,
            hidden = hidden,
            roots = roots,
            favourites = favourites
        )
    }

    /** The parsed manifest when [dir] holds a unified backup's; null otherwise. Throws IllegalArgumentException on broken JSON. */
    private fun readManifest(dir: File): JsonObject? {
        val file = File(dir, MANIFEST)
        if (!file.isFile || file.length() > MANIFEST_LIMIT) return null
        val manifest = json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
        return manifest.takeIf { it.str("format") == FORMAT }
    }

    /** User folders (smart BY_PLATFORM folders are Cocoon's systems view) with their GAME members from `membership`. */
    private fun userFolders(layout: JsonObject): List<CocoonFolder> {
        val members = HashMap<String, MutableList<Pair<Long, CocoonRef>>>()
        layout.array("membership").forEach { m ->
            val screen = m.str("screen") ?: return@forEach
            if (!screen.startsWith("folder_") || m.str("item_type") != "GAME") return@forEach
            val ref = m.obj("ref")?.let(::layoutRef) ?: return@forEach
            members.getOrPut(screen) { ArrayList() } += (m.long("sort_order") ?: Long.MAX_VALUE) to ref
        }
        return layout.array("folders").mapNotNull { f ->
            if (f.bool("is_smart_folder") != false) return@mapNotNull null
            val id = f.long("id")?.toInt() ?: return@mapNotNull null
            val name = f.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Cocoon folder $id"
            CocoonFolder(id, name, members["folder_$id"].orEmpty().sortedBy { it.first }.map { it.second }.distinct())
        }
    }

    /** `ref` of the layout: `path` = `<platform>/<legacy path>`, `package` for apps. */
    private fun layoutRef(ref: JsonObject): CocoonRef? {
        val path = ref.str("path")
        val pkg = ref.str("package")
        if (path == null) return pkg?.let { CocoonRef(ANDROID_APP, null, it) }
        return ref(path.substringBefore('/'), path.substringAfter('/', ""), pkg)
    }

    /** `identity` of play history and metadata records. */
    private fun identityRef(identity: JsonObject): CocoonRef? {
        val legacy = identity.str("legacy_game_path")
        val platform = identity.str("platform_id") ?: legacy?.substringBefore('/') ?: return null
        val name = identity.str("file_name") ?: legacy?.substringAfter('/', "") ?: return null
        return ref(platform, name, identity.str("android_package_name"))
    }

    private fun ref(platform: String, path: String, pkg: String?): CocoonRef? {
        if (platform.isEmpty()) return null
        if (platform == ANDROID_APP) return (pkg ?: path.takeIf { it.isNotEmpty() })?.let { CocoonRef(platform, null, it) }
        val file = if (platform == SHORTCUT) path else path.substringAfterLast('/')
        return file.takeIf { it.isNotEmpty() }?.let { CocoonRef(platform, it, null) }
    }

    private fun eachRecord(file: File, block: (JsonObject) -> Unit) {
        if (!file.isFile) return
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val record = try {
                    json.parseToJsonElement(line) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null // a torn or foreign line; the rest of the file still counts
                }
                record?.let(block)
            }
        }
    }

    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.array(key: String): List<JsonObject> = ((this as? JsonObject)?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonElement.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonElement.bool(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
