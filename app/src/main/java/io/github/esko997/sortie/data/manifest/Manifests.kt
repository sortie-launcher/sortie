package io.github.esko997.sortie.data.manifest

import io.github.esko997.sortie.data.journal.JournalEntry
import io.github.esko997.sortie.data.magazine.MagazineClipping
import io.github.esko997.sortie.data.reference.ReferenceCard
import io.github.esko997.sortie.data.user.GameProgress
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
    /** Control notes shown on the companion while the game is played ("L2/R2 strafe · hold Select = menu"); hand-written. */
    val controls: String = "",
    val flags: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val auto: Boolean = true
)

/**
 * What a game needs beyond the built-in controller, as recorded by the user on the device. [UNKNOWN] is the default and claims
 * nothing: it is not a compatibility statement and draws no badge. Serialised by [key] (stable, lower-case).
 */
@Serializable(with = ControllerSupportSerializer::class)
enum class ControllerSupport(val key: String, val label: String, val badge: String) {
    UNKNOWN("unknown", "Not recorded", ""),
    GAMEPAD("gamepad", "Plays on the controller", "Pad OK"),
    POINTER("pointer", "Needs a pointer (mouse / light gun)", "Pointer"),
    MOTION("motion", "Needs motion controls", "Motion"),
    TOUCH("touch", "Needs the touch screen", "Touch")
}

/** Unknown keys (a newer build's value) decode as [ControllerSupport.UNKNOWN] instead of failing the whole user.json. */
object ControllerSupportSerializer : KSerializer<ControllerSupport> {
    override val descriptor = PrimitiveSerialDescriptor("io.github.esko997.sortie.ControllerSupport", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ControllerSupport) = encoder.encodeString(value.key)

    override fun deserialize(decoder: Decoder): ControllerSupport {
        val key = decoder.decodeString()
        return ControllerSupport.entries.firstOrNull { it.key == key } ?: ControllerSupport.UNKNOWN
    }
}

/**
 * The user's own per-game notes: what the game needs ([controller]), personal [mappingNotes] (supplementing the guide's
 * `controls`, which stay separate), and [returnNote] — "where was I?", shown on the companion while browsing and playing.
 */
@Serializable
data class GameAnnotation(
    val controller: ControllerSupport = ControllerSupport.UNKNOWN,
    val mappingNotes: String = "",
    val returnNote: String = ""
) {
    /** Nothing recorded: the entry is dropped from [UserData.gameAnnotations] rather than stored. */
    fun isEmpty(): Boolean = controller == ControllerSupport.UNKNOWN && mappingNotes.isBlank() && returnNote.isBlank()

    /** Surrounding whitespace trimmed; an all-blank note becomes empty. */
    fun normalised(): GameAnnotation = copy(mappingNotes = mappingNotes.trim(), returnNote = returnNote.trim())
}

/** Slots in the companion dock. */
const val DOCK_SLOTS = 5

/** Reserved id of the built-in Favourites folder (tile `folder:favourites`); a `folders.json` entry with this id is ignored. */
const val FAVOURITES_FOLDER_ID = "favourites"

/** Reserved id of the built-in Want to play folder (tile `folder:want-to-play`); a `folders.json` entry with this id is ignored. */
const val WANT_TO_PLAY_FOLDER_ID = "want-to-play"

@Serializable
data class UserData(
    val favourites: Set<String> = emptySet(),
    /**
     * Games queued to play later, shown by the built-in [WANT_TO_PLAY_FOLDER_ID] folder; independent of [favourites] and never
     * removed on launch. Ids that are not (or no longer) in the index are kept and resolve again once the game is indexed.
     */
    val wantToPlay: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val profileOverrides: Map<String, String> = emptyMap(),
    /** Home layout: "list" or "grid"; grid columns are editable here or cycled on the device. */
    val homeLayout: String = "list",
    /** Vertical grid: tiles per row. Horizontal grid: see [gridRows]. */
    val gridColumns: Int = 5,
    /** Grids scroll sideways (rows of square tiles; the Thor's wide top screen) instead of down. Lists are unaffected. */
    val gridHorizontal: Boolean = false,
    /** Horizontal grid: tiles per column; more rows = smaller tiles, denser. */
    val gridRows: Int = 3,
    /** Apps screen grid: tiles per row (app icons are small, so denser than the game grid). */
    val appColumns: Int = 8,
    /** Browse screens (system, folder, apps, search): "list" or "grid". */
    val browseLayout: String = "list",
    /** Home tile order: "alpha", "count", "generation" (generated, fixed, systems only) or "custom" (built from [layout]). */
    val homeSort: String = "alpha",
    /**
     * The custom home layout, in order. Tile ids: `system:<systemId>`, `folder:<folderId>`, `apps`, `continue`, a game id,
     * `app:<pkg>`, `shortcut:<pkg>/<shortcutId>`. Continue, every smart folder and Apps show on Custom even when absent here
     * (Continue first, the others appended); moving a tile saves the whole order. The generated sorts hold systems only.
     */
    val layout: List<String> = emptyList(),
    /** Shortcuts the user opted in to (ids `shortcut:<pkg>/<id>`); every other launcher shortcut stays out of the lists. */
    val shortcuts: Set<String> = emptySet(),
    /** Per-system default launch profile chosen on the device (system id → profile id); overrides the manifest's first choice. */
    val systemProfiles: Map<String, String> = emptyMap(),
    /** Companion-screen dock: [DOCK_SLOTS] slots of app/shortcut ids (`""` = empty), unfurled from the bottom-left dot. */
    val dock: List<String> = emptyList(),
    /** Hero rotation on the idle companion screen (and the ambient tint it drives). */
    val heroRotation: Boolean = true,
    /** Home-key switcher as overlay windows over the running games (needs the draw-over-apps grant) rather than Sortie's screens. */
    val overlaySwitcher: Boolean = true,
    /** Device stats (CPU, GPU, RAM, temperatures, fan): "off", "companion", "overlay" or "both" (see DeviceStatsMode). */
    val deviceStats: String = "off",
    /** Seconds between device-stats polls while a stats surface is shown: 2, 5 or 10. */
    val deviceStatsInterval: Int = 5,
    /** Seconds between device-stats polls while a Perf view is shown (switcher page or companion screen): 1 or 2. */
    val perfInterval: Int = 2,
    /** What the companion screen of a dual-screen device shows: "companion" (its panel) or "perf" (see CompanionView). */
    val companionView: String = "companion",
    /** Per-game controller requirement, mapping notes and "where was I?" notes (game id → annotation); empty entries are absent. */
    val gameAnnotations: Map<String, GameAnnotation> = emptyMap(),
    /** Independent of favourites and Want to play; a game has at most one personal shelf status. */
    val gameProgress: Map<String, GameProgress> = emptyMap(),
    /** Personal excerpts, recommendation clippings and notes; magazine packs stay read-only. */
    val magazineClippings: List<MagazineClipping> = emptyList(),
    /** Locally authored/imported reference cards keyed by exact library game id. */
    val referenceCards: Map<String, List<ReferenceCard>> = emptyMap(),
    /** Dated personal journal entries; recorded sessions remain in the play-history database. */
    val journalEntries: List<JournalEntry> = emptyList(),
    /** Cocoon directory last imported from, for re-runs: offered first next time "Import from Cocoon…" opens; null = never. */
    val cocoonDir: String? = null
)
