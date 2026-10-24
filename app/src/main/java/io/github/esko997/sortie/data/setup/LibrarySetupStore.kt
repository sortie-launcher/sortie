package io.github.esko997.sortie.data.setup

import io.github.esko997.sortie.data.manifest.DeviceManifest
import io.github.esko997.sortie.data.manifest.FoldersManifest
import io.github.esko997.sortie.data.manifest.GuideFile
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.LaunchersManifest
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.manifest.SystemsManifest
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The editable part of the card configuration as last read. [fingerprint] identifies the exact bytes of every manifest; pass
 * the configuration back as `expected` so a save is refused when anything changed in between. [errors] lists files that
 * could not be read (saving is refused while one of device/systems/launchers.json is among them) and interrupted changes.
 * [exists] is false when there is no device.json, systems.json or launchers.json yet (first setup).
 */
data class LibraryConfiguration(
    val systems: List<SystemDef>,
    val profiles: List<LaunchProfile>,
    val fingerprint: String,
    val errors: List<String>,
    val exists: Boolean,
    val biosDir: String?,
    val saves: List<String>
)

/** The card configuration changed since it was loaded (desktop edit, another screen): reload, keep the draft, try again. */
class LibraryConflictException(message: String) : IOException(message)

/**
 * Reads and edits the library part of `<card>/sortie` (systems.json, launchers.json and the BIOS/save paths of device.json)
 * for on-device setup. Writes are JSON-level merges: unknown keys, unedited systems/profiles, library rules, folders.json,
 * guide.json and user data are kept as they are; only the edited entries change. A first save creates the complete manifest
 * set (missing files only). Every save is checked against the caller's [LibraryConfiguration.fingerprint] and committed with
 * [ManifestCommit], so a crash, failure or cancellation leaves the previous set or the complete new one, never a mix, and
 * the [ManifestStore] never publishes a half-written set. Successful saves reload the [ManifestStore] (which also starts its
 * watcher on a freshly created directory). Removing a system removes its library entry only; no ROM, save or personal record
 * is touched.
 */
class LibrarySetupStore internal constructor(
    private val directory: File,
    private val reload: suspend () -> Unit,
    private val io: CommitIo,
    private val deviceName: String
) {
    constructor(directory: File, manifests: ManifestStore) : this(directory, { manifests.reloadNow() }, CommitIo(), defaultDeviceName())

    /** Reads like ManifestStore does (unknown keys allowed) so "readable here" means "loads in the app". */
    private val decoder = Json { ignoreUnknownKeys = true }

    /** Encodes only non-default, non-null fields; absent keys decode to the same defaults. */
    private val encoder = Json {
        encodeDefaults = false
        explicitNulls = false
    }
    private val writer = Json { prettyPrint = true }

    private class Parsed<T>(val obj: JsonObject?, val value: T?)

    private inner class State(
        val raw: Map<String, ByteArray?>,
        val device: Parsed<DeviceManifest>,
        val systems: Parsed<SystemsManifest>,
        val launchers: Parsed<LaunchersManifest>,
        val errors: List<String>,
        val blocking: List<String>,
        val fingerprint: String
    ) {
        val exists: Boolean get() = raw[DEVICE] != null || raw[SYSTEMS] != null || raw[LAUNCHERS] != null

        fun toConfiguration() = LibraryConfiguration(
            systems = systems.value?.systems.orEmpty().sortedBy { it.order },
            profiles = launchers.value?.profiles.orEmpty(),
            fingerprint = fingerprint,
            errors = errors,
            exists = exists,
            biosDir = device.value?.biosDir,
            saves = device.value?.saves.orEmpty()
        )
    }

    /** Recovers any interrupted commit, then reads every manifest. Caller holds the [ManifestCommit] lock. Never throws. */
    private fun readState(): State {
        val recovery = ManifestCommit.recover(directory, io)
        val errors = mutableListOf<String>()
        val blocking = mutableListOf<String>()
        recovery.error?.let {
            errors += it
            blocking += it
        }
        val raw = LinkedHashMap<String, ByteArray?>()
        val unreadable = HashSet<String>()
        for (name in ALL) {
            val f = recovery.pending[name] ?: File(directory, name)
            raw[name] = if (!f.exists()) {
                null
            } else {
                try {
                    f.readBytes()
                } catch (e: IOException) {
                    val msg = "$name could not be read (${e.message}). Check that the storage is available and Sortie has All files access."
                    errors += msg
                    blocking += msg
                    unreadable += name
                    ByteArray(0)
                }
            }
        }

        fun <T> parse(name: String, serializer: KSerializer<T>, blocks: Boolean): Parsed<T> {
            val bytes = raw[name]
            // an inconsistent interrupted change may have left a mix of old and new files: show none of them (writes are blocked)
            if (bytes == null || name in unreadable || !recovery.consistent) return Parsed(null, null)
            return try {
                val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                Parsed(obj, decoder.decodeFromJsonElement(serializer, obj))
            } catch (e: Exception) {
                val msg = "$name is not valid (${e.message?.lineSequence()?.firstOrNull()}). Sortie will not overwrite it: fix it or move it " +
                    "aside on a computer, then reload."
                if (msg !in errors) errors += msg
                if (blocks) blocking += msg
                Parsed(null, null)
            }
        }
        val device = parse(DEVICE, DeviceManifest.serializer(), blocks = true)
        val systems = parse(SYSTEMS, SystemsManifest.serializer(), blocks = true)
        val launchers = parse(LAUNCHERS, LaunchersManifest.serializer(), blocks = true)
        // only created when missing, never rewritten, so a broken one is reported but does not block library edits
        parse(FOLDERS, FoldersManifest.serializer(), blocks = false)
        parse(GUIDE, GuideFile.serializer(), blocks = false)
        val fingerprint = ManifestCommit.sha256(
            raw.entries.joinToString("\n") { (name, bytes) -> "$name=${bytes?.let { ManifestCommit.sha256(it) } ?: "absent"}" }
                .toByteArray(Charsets.UTF_8)
        )
        return State(raw, device, systems, launchers, errors, blocking, fingerprint)
    }

    suspend fun load(): LibraryConfiguration = withContext(Dispatchers.IO) { ManifestCommit.withLock(directory) { readState() }.toConfiguration() }

    /**
     * Adds [system] or replaces the system with its id (ids are never changed here), and adds or replaces each of [profiles]
     * by id in launchers.json. Every id in [SystemDef.profiles] must be in [profiles] or already configured. A new system
     * with order ≤ 0 goes after the others. Extensions are normalised to lower-case `.ext`.
     */
    suspend fun saveSystem(expected: LibraryConfiguration, system: SystemDef, profiles: List<LaunchProfile>): LibraryConfiguration =
        mutate(expected) { state, work ->
            val existingSystems = state.systems.value?.systems.orEmpty()
            val existingProfiles = state.launchers.value?.profiles.orEmpty().associateBy { it.id }
            val isNew = existingSystems.none { it.id == system.id }
            val passedIds = HashSet<String>()
            for (p in profiles) {
                require(passedIds.add(p.id)) { "Emulator profile ${p.id} is listed twice." }
                validateProfile(p, isNew = p.id !in existingProfiles)
            }
            var next = normaliseSystem(system, existingSystems.firstOrNull { it.id == system.id })
            next.profiles.forEach { id ->
                require(id in passedIds || id in existingProfiles) { "${next.label}: emulator profile $id is not configured." }
            }
            if (isNew && next.order <= 0 && existingSystems.isNotEmpty()) next = next.copy(order = existingSystems.maxOf { it.order } + 1)

            val changedProfiles = profiles.filter { existingProfiles[it.id] != it }
            if (changedProfiles.isNotEmpty()) {
                var profilesArray = elements(work.launchers, "profiles")
                for (p in changedProfiles) profilesArray = upsert(profilesArray, p.id, encode(LaunchProfile.serializer(), p), LaunchProfile.serializer())
                work.launchers = JsonObject(work.launchers + ("profiles" to JsonArray(profilesArray)))
            }
            if (existingSystems.firstOrNull { it.id == next.id } != next) {
                val systemsArray = upsert(elements(work.systems, "systems"), next.id, encode(SystemDef.serializer(), next), SystemDef.serializer())
                work.systems = JsonObject(work.systems + ("systems" to JsonArray(systemsArray)))
            }
        }

    /** Removes the library entry of [systemId] only: its profiles, ROM folders, saves and personal records stay. */
    suspend fun removeSystem(expected: LibraryConfiguration, systemId: String): LibraryConfiguration = mutate(expected) { state, work ->
        require(state.systems.value?.systems.orEmpty().any { it.id == systemId }) { "System $systemId is not in the library." }
        val remaining = elements(work.systems, "systems").filterNot { idOf(it) == systemId }
        work.systems = JsonObject(work.systems + ("systems" to JsonArray(remaining)))
    }

    /** Sets device.json's BIOS folder (null or blank removes it) and save folders, absolute or relative to the card root. */
    suspend fun saveSupportPaths(expected: LibraryConfiguration, biosDir: String?, saves: List<String>): LibraryConfiguration = mutate(expected) { _, work ->
        val bios = biosDir?.takeIf { it.isNotBlank() }?.let { normalisePath(it, "BIOS folder") }
        val saveDirs = saves.map { normalisePath(it, "Save folder") }.distinct()
        val fields = LinkedHashMap(work.device)
        if (bios == null) fields.remove("biosDir") else fields["biosDir"] = JsonPrimitive(bios)
        if (saveDirs.isEmpty()) fields.remove("saves") else fields["saves"] = JsonArray(saveDirs.map { JsonPrimitive(it) })
        work.device = JsonObject(fields)
    }

    private class Work(var device: JsonObject, var systems: JsonObject, var launchers: JsonObject)

    private enum class Outcome { UNCHANGED, COMMITTED }

    /**
     * Checks [expected] against the card under the commit lock, applies [edit] to the parsed (or scaffolded) objects and commits
     * every changed or missing manifest as one unit. Cancellation before the commit starts changes nothing; once it started it
     * finishes (and reloads) before the cancellation is delivered. A decided save returns the recovered configuration, with
     * any remaining recovery errors, rather than inviting the caller to repeat an already committed addition.
     */
    private suspend fun mutate(expected: LibraryConfiguration, edit: (State, Work) -> Unit): LibraryConfiguration {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO + NonCancellable) {
            val outcome = try {
                ManifestCommit.withLock(directory) { commitLocked(expected, edit) }
            } catch (_: ManifestCommit.PartiallyApplied) {
                // The journal is durable: reload rolls it forward, or reads the complete staged set with an error.
                Outcome.COMMITTED
            }
            if (outcome == Outcome.COMMITTED) reload()
        }
        val saved = load()
        return if (saved.exists) {
            saved
        } else {
            saved.copy(errors = saved.errors + "The change was saved, but its storage is no longer available. Reconnect it and reload the library.")
        }
    }

    private fun commitLocked(expected: LibraryConfiguration, edit: (State, Work) -> Unit): Outcome {
        val state = readState()
        if (state.fingerprint != expected.fingerprint) {
            throw LibraryConflictException("The library settings on the card changed since this screen loaded them. Reload, then make the change again.")
        }
        if (state.blocking.isNotEmpty()) throw IOException(state.blocking.joinToString("\n"))
        val parent = directory.absoluteFile.parentFile
        val work = Work(
            device = state.device.obj ?: JsonObject(mapOf("device" to JsonPrimitive(deviceName), "root" to JsonPrimitive(parent?.path ?: "/"))),
            systems = state.systems.obj ?: JsonObject(
                mapOf(
                    "systems" to JsonArray(emptyList()),
                    "library" to JsonObject(mapOf("skipFiles" to JsonArray(LibraryPresets.defaultSkipFiles.map { JsonPrimitive(it) })))
                )
            ),
            launchers = state.launchers.obj ?: JsonObject(mapOf("profiles" to JsonArray(emptyList())))
        )
        edit(state, work)
        val next = LinkedHashMap<String, JsonObject>()
        if (state.device.obj != work.device) next[DEVICE] = work.device
        if (state.systems.obj != work.systems) next[SYSTEMS] = work.systems
        if (state.launchers.obj != work.launchers) next[LAUNCHERS] = work.launchers
        if (next.isEmpty() && state.exists) {
            // a no-op edit of an existing configuration changes no file, not even missing folders/guide (not ours to add)
            return Outcome.UNCHANGED
        }
        // a configuration being created or edited is completed so the app loads it without "missing" errors
        if (state.raw[DEVICE] == null) next[DEVICE] = work.device
        if (state.raw[SYSTEMS] == null) next[SYSTEMS] = work.systems
        if (state.raw[LAUNCHERS] == null) next[LAUNCHERS] = work.launchers
        if (state.raw[FOLDERS] == null) next[FOLDERS] = JsonObject(mapOf("folders" to JsonArray(emptyList())))
        if (state.raw[GUIDE] == null) next[GUIDE] = JsonObject(mapOf("built" to JsonPrimitive(""), "games" to JsonObject(emptyMap())))
        // the result must load exactly as ManifestStore will load it
        next[DEVICE]?.let { decoder.decodeFromJsonElement(DeviceManifest.serializer(), it) }
        next[SYSTEMS]?.let { decoder.decodeFromJsonElement(SystemsManifest.serializer(), it) }
        next[LAUNCHERS]?.let { decoder.decodeFromJsonElement(LaunchersManifest.serializer(), it) }
        if (!directory.isDirectory) {
            if (parent == null || !parent.isDirectory) {
                throw IOException("Storage ${parent?.path ?: directory.path} is not available. Insert the card or grant All files access, then try again.")
            }
            if (!directory.mkdir() && !directory.isDirectory) throw IOException("Could not create ${directory.path}. Check that Sortie has All files access.")
        }
        val bytes = next.mapValues { (_, obj) -> (writer.encodeToString(JsonElement.serializer(), obj) + "\n").toByteArray(Charsets.UTF_8) }
        try {
            ManifestCommit.commit(directory, bytes, io)
        } catch (e: ManifestCommit.PartiallyApplied) {
            throw e
        } catch (e: IOException) {
            throw IOException(
                "Could not save library settings in ${directory.path} (${e.message}). Nothing was changed. Check that the storage is " +
                    "inserted and writable and that Sortie has All files access.",
                e
            )
        }
        return Outcome.COMMITTED
    }

    private fun elements(obj: JsonObject, key: String): List<JsonElement> = (obj[key] as? JsonArray).orEmpty()

    private fun idOf(e: JsonElement): String? = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject = encoder.encodeToJsonElement(serializer, value).jsonObject

    /**
     * Replaces the first element with [id] by [encoded] merged over it (keys unknown to [serializer] are kept, known keys now at
     * their default are dropped since absent means default), or appends [encoded].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> upsert(array: List<JsonElement>, id: String, encoded: JsonObject, serializer: KSerializer<T>): List<JsonElement> {
        val index = array.indexOfFirst { idOf(it) == id }
        if (index < 0) return array + encoded
        val known = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }
        val merged = LinkedHashMap((array[index] as JsonObject))
        for (key in known) {
            val v = encoded[key]
            if (v == null || v is JsonNull) merged.remove(key) else merged[key] = v
        }
        return array.toMutableList().also { it[index] = JsonObject(merged) }
    }

    private fun normalisePath(path: String, what: String): String {
        val p = path.trim()
        require(p.isNotEmpty()) { "$what is empty." }
        require('\u0000' !in p && '\n' !in p) { "$what contains invalid characters." }
        return if (p.length > 1) p.trimEnd('/').ifEmpty { "/" } else p
    }

    /** Validates and tidies [system]; fields kept unchanged from [existing] (a desktop-authored entry) are not re-validated. */
    private fun normaliseSystem(system: SystemDef, existing: SystemDef?): SystemDef {
        if (existing == null) require(NEW_ID.matches(system.id)) { "System id \"${system.id}\" must use lower-case letters, digits, '.', '_' or '-'." }
        val label = system.label.trim()
        require(label.isNotEmpty()) { "Give the system a name." }
        val roots = system.roots.map { normalisePath(it, "$label folder") }.distinct()
        require(roots.isNotEmpty()) { "$label: choose at least one game folder." }
        val extensions = system.extensions.map { raw ->
            val e = raw.trim().lowercase(Locale.ROOT).removePrefix(".")
            require(e.isNotEmpty() && EXTENSION.matches(e)) { "$label: \"$raw\" is not a file extension (for example .iso)." }
            ".$e"
        }.distinct()
        val profiles = system.profiles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(profiles.isNotEmpty()) { "$label: choose at least one emulator." }
        if (system.colour != existing?.colour) require(COLOUR.matches(system.colour)) { "$label: colour must look like #3b82f6." }
        if (system.generation != existing?.generation) require(system.generation.isNotBlank()) { "$label: generation is empty." }
        return system.copy(
            label = label,
            folder = system.folder.trim().ifEmpty { system.id },
            roots = roots,
            extensions = extensions,
            profiles = profiles,
            bios = system.bios.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        )
    }

    private fun validateProfile(p: LaunchProfile, isNew: Boolean) {
        if (isNew) require(NEW_ID.matches(p.id)) { "Emulator profile id \"${p.id}\" must use lower-case letters, digits, '.', '_' or '-'." }
        require(p.label.isNotBlank()) { "Emulator profile ${p.id} has no name." }
        require(p.generic || !p.`package`.isNullOrBlank()) { "${p.label}: choose the app to launch." }
        require(p.display in DISPLAYS) { "${p.label}: screen must be one of ${DISPLAYS.joinToString()}." }
    }

    companion object {
        const val DEVICE = "device.json"
        const val SYSTEMS = "systems.json"
        const val LAUNCHERS = "launchers.json"
        const val FOLDERS = "folders.json"
        const val GUIDE = "guide.json"
        private val ALL = listOf(DEVICE, SYSTEMS, LAUNCHERS, FOLDERS, GUIDE)
        private val NEW_ID = Regex("[a-z0-9][a-z0-9._-]*")
        private val EXTENSION = Regex("[a-z0-9_+-]+")
        private val COLOUR = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
        private val DISPLAYS = setOf("same", "top", "bottom")

        /** A stable, readable device name for playlog lines (`ayn-thor`); "android" when the model is unknown. */
        internal fun defaultDeviceName(): String = runCatching { android.os.Build.MODEL }.getOrNull()
            ?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() } ?: "android"
    }
}
