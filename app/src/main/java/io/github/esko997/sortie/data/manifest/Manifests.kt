package io.github.esko997.sortie.data.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Mirrors docs/formats. Unknown keys are ignored so the files can evolve ahead of the app. */
@Serializable
data class DeviceManifest(
    val device: String = "unknown",
    val root: String,
    val screens: Screens = Screens(),
    val shizuku: Boolean = false,
    /** Where RetroArch-style saves live (scanned two levels deep for `<stem>.*`) and where BIOS files are expected. */
    val saves: List<String> = emptyList(),
    val biosDir: String? = null
) {
    /** `presentationIndex` picks which secondary display is "bottom" when several exist. */
    @Serializable
    data class Screens(val count: Int = 1, val navigation: String = "bottom", val presentationIndex: Int = 0)
}

@Serializable
data class SystemsManifest(val systems: List<SystemDef> = emptyList(), val library: LibraryRules = LibraryRules())

/** Library-wide rules that used to be hard-coded: BIOS archives to hide, how to recognise hacks. Desktop-authored. */
@Serializable
data class LibraryRules(val skipFiles: List<String> = emptyList(), val hackRegex: String? = null)

@Serializable
data class SystemDef(
    val id: String,
    val folder: String = id,
    val label: String = id,
    val generation: String = "other",
    val roots: List<String>,
    val extensions: List<String> = emptyList(),
    val gamelist: String? = null,
    val media: String? = null,
    val profiles: List<String> = emptyList(),
    val colour: String = "#64748b",
    val order: Int = 0,
    /** BIOS files (relative to device.biosDir) the system's cores need; missing ones are shown as a badge. */
    val bios: List<String> = emptyList()
)

@Serializable
data class LaunchersManifest(val profiles: List<LaunchProfile> = emptyList())

@Serializable
data class LaunchProfile(
    val id: String,
    val label: String = id,
    val `package`: String? = null,
    val activity: String? = null,
    val action: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val extrasArray: Map<String, List<String>> = emptyMap(),
    val flags: List<String> = emptyList(),
    val display: String = "same",
    val generic: Boolean = false
)

@Serializable
data class FoldersManifest(val folders: List<FolderDef> = emptyList())

@Serializable
data class FolderDef(
    val id: String,
    val label: String = id,
    val rule: JsonElement? = null,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val apps: Boolean = false,
    val sort: String = "title:asc",
    val limit: Int = 0
)

@Serializable
data class GuideFile(val built: String = "", val games: Map<String, GuideEntry> = emptyMap())

@Serializable
data class GuideEntry(
    val fit: Int = 0,
    val hook: String = "",
    val flags: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val auto: Boolean = true
)

@Serializable
data class UserData(
    val favourites: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val profileOverrides: Map<String, String> = emptyMap(),
    /** Home layout: "list" or "grid"; grid columns are editable here or cycled on the device. */
    val homeLayout: String = "list",
    val gridColumns: Int = 5,
    /** Browse screens (system, folder, apps, search): "list" or "grid". */
    val browseLayout: String = "list",
    /** Home tile order: "alpha", "count", "generation" (all generated, fixed) or "custom" (the user's [layout]). */
    val homeSort: String = "alpha",
    /**
     * The custom home layout, in order. Tile ids: `system:<systemId>`, `folder:<folderId>`, a game id, `app:<pkg>`,
     * `shortcut:<pkg>/<shortcutId>`. Only this layout can hold games, apps and shortcuts; the generated sorts cannot.
     */
    val layout: List<String> = emptyList(),
    /** Shortcuts the user opted in to (ids `shortcut:<pkg>/<id>`); every other launcher shortcut stays out of the lists. */
    val shortcuts: Set<String> = emptySet()
)
