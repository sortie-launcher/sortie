package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.setup.LibraryPresets
import java.util.Locale

/**
 * The emulator a Cocoon `<cocoon:defaultplayerid>` names, as Sortie sees it: [profileId] is the Sortie launch profile id
 * (`ra-<core>`, `ra64-<core>`, or a preset profile such as `armsx2-uri`), [retroArchCore] the libretro core when it is a
 * RetroArch player (so a profile can be synthesised with LibraryPresets.retroArch when no preset has that core), and
 * [packageName] the Android package that runs it.
 */
data class CocoonPlayer(val profileId: String, val retroArchCore: String?, val packageName: String?)

/**
 * Name tables between Cocoon and Sortie. Cocoon's platform ids follow ES-DE folder names but merge some aliases into one
 * platform (tg16 = roms/pcengine + roms/tg16, fbneo ↔ arcade, ngpc ↔ ngp) and prefix its player ids with yet other names
 * (`master.…`, `3ds.…`). Only aliases seen on the two measured devices or named by ES-DE are listed; an unknown platform maps
 * to itself.
 */
internal object CocoonAliases {
    /** Cocoon platform → Sortie preset id (LibraryPresets.preset) when they differ; identity otherwise. */
    val PLATFORM_TO_PRESET: Map<String, String> = mapOf(
        "tg16" to "pcengine",
        "turbografx16" to "pcengine",
        "ngpc" to "ngp",
        "master" to "mastersystem",
        "sms" to "mastersystem",
        "sg1000" to "sg-1000",
        "tgcd" to "tg-cd",
        "pcenginecd" to "tg-cd",
        "3ds" to "n3ds",
        "fbneo" to "arcade",
        "megadrive" to "genesis",
        "32x" to "sega32x",
        "megacd" to "segacd",
        "gamecube" to "gc"
    )

    /**
     * Platforms whose Cocoon gamelists interleave with each other's folders: alias groups for matching. supergrafx, neogeo and
     * the CPS boards keep their own folders in Cocoon and stay alone.
     */
    val GROUPS: List<Set<String>> = listOf(
        setOf("tg16", "pcengine", "turbografx16"),
        setOf("tg-cd", "tgcd", "pcenginecd"),
        setOf("fbneo", "arcade", "mame"),
        setOf("ngp", "ngpc"),
        setOf("mastersystem", "master", "sms"),
        setOf("sg-1000", "sg1000"),
        setOf("n3ds", "3ds"),
        setOf("genesis", "megadrive"),
        setOf("sega32x", "32x"),
        setOf("segacd", "megacd"),
        setOf("gc", "gamecube")
    )

    /** Names for custom systems Cocoon has and no preset covers; others get their id capitalised. */
    val LABELS: Map<String, String> = mapOf(
        "channelf" to "Channel F",
        "cps1" to "CPS-1",
        "cps2" to "CPS-2",
        "cps3" to "CPS-3",
        "mame" to "MAME",
        "naomi" to "NAOMI",
        "sg-1000" to "SG-1000",
        "supergrafx" to "SuperGrafx",
        "switch" to "Switch"
    )

    /**
     * ES-DE's system folder names (`roms/<name>`): every `<name>` of ES-DE's Android `resources/systems/android/es_systems.xml`
     * (gitlab.com/es-de/emulationstation-de, master a529a9d3, 195 systems), copied verbatim. Cocoon's platform ids follow
     * them, so a folder with one of these names holds that system's games: the planner never lets another platform adopt it as
     * a root, however many file names they share.
     */
    val ESDE_SYSTEMS: Set<String> = setOf(
        "3do", "adam", "ags", "amiga", "amiga1200", "amiga600", "amigacd32", "amstradcpc", "android", "androidapps",
        "androidgames", "apple2", "apple2gs", "arcade", "arcadia", "archimedes", "arduboy", "astrocde", "atari2600", "atari5200",
        "atari7800", "atari800", "atarijaguar", "atarijaguarcd", "atarilynx", "atarist", "atarixe", "atomiswave", "bbcmicro",
        "c64", "cdimono1", "cdtv", "chailove", "channelf", "coco", "colecovision", "consolearcade", "cps", "cps1", "cps2",
        "cps3", "crvision", "daphne", "desktop", "doom", "dos", "dragon32", "dreamcast", "easyrpg", "electron", "emulators",
        "epic", "famicom", "fba", "fbneo", "fds", "flash", "fm7", "fmtowns", "fpinball", "gamate", "gameandwatch", "gamecom",
        "gamegear", "gb", "gba", "gbc", "gc", "genesis", "gmaster", "gx4000", "intellivision", "j2me", "kodi", "laserdisc",
        "lcdgames", "lowresnx", "lutris", "lutro", "macintosh", "mame", "mame-advmame", "mark3", "mastersystem", "megacd",
        "megacdjp", "megadrive", "megadrivejp", "megaduck", "mess", "model2", "model3", "moto", "msx", "msx1", "msx2",
        "msxturbor", "mugen", "multivision", "n3ds", "n64", "n64dd", "naomi", "naomi2", "naomigd", "nds", "neogeo", "neogeocd",
        "neogeocdjp", "nes", "ngage", "ngp", "ngpc", "odyssey2", "openbor", "oric", "palm", "pc", "pc88", "pc98", "pcarcade",
        "pcengine", "pcenginecd", "pcfx", "pico8", "plus4", "pokemini", "ports", "ps2", "ps3", "ps4", "psp", "psvita", "psx",
        "pv1000", "quake", "samcoupe", "satellaview", "saturn", "saturnjp", "scummvm", "scv", "sega32x", "sega32xjp",
        "sega32xna", "segacd", "sfc", "sg-1000", "sgb", "snes", "snesna", "solarus", "spectravideo", "steam", "stv", "sufami",
        "supergrafx", "supervision", "supracan", "switch", "symbian", "tanodragon", "tg-cd", "tg16", "ti99", "tic80", "to8",
        "triforce", "trs-80", "type-x", "uzebox", "vectrex", "vic20", "videopac", "vircon32", "virtualboy", "vpinball", "vsmile",
        "wasm4", "wii", "wiiu", "windows", "windows3x", "windows9x", "wonderswan", "wonderswancolor", "x1", "x68000", "xbox",
        "xbox360", "xboxone", "zmachine", "zx81", "zxnext", "zxspectrum"
    )

    /** RetroArch builds by the id segment Cocoon uses: segment → (Sortie profile id prefix, package). */
    private val RETROARCH = mapOf(
        "ra" to ("ra" to "com.retroarch"),
        "ra64" to ("ra64" to "com.retroarch.aarch64"),
        "ra32" to ("ra32" to "com.retroarch.ra32")
    )

    /** Cocoon core names that differ from libretro's. */
    private val CORE_ALIASES = mapOf("mamearcade" to "mame")

    /**
     * Standalone players Cocoon names by a short alias. Azahar is the Lime3DS successor; Sortie's verified 3DS profile is
     * Lime3DS's, and on the Thor Cocoon's `3ds.azahar` runs that very package.
     */
    private val PACKAGE_ALIASES = mapOf("azahar" to "io.github.lime3ds.android", "lime3ds" to "io.github.lime3ds.android")

    private val CORE = Regex("[a-z0-9_]+")

    fun group(platform: String): Set<String> = GROUPS.firstOrNull { platform in it } ?: setOf(platform)

    /**
     * defaultplayerid → (profile id, RetroArch core or package). "<plat>.ra.<core>"→"ra-<core>", "<plat>.ra64.<core>"→"ra64-<core>",
     * "<plat>.<package>"→ by package; aliases: "azahar"→ lime3ds, "mamearcade"→core "mame". The platform prefix is ignored (it is
     * not always the folder name). Null for "__none__" and for an app no preset profile describes (its activity and intent shape
     * are unknown, so no profile can be made up for it).
     */
    fun player(defaultPlayer: String): CocoonPlayer? {
        val raw = defaultPlayer.trim().lowercase(Locale.ROOT)
        if (raw.isEmpty() || raw == "__none__") return null
        // "ra.<core>" with no platform prefix is accepted too
        val rest = if (RETROARCH.keys.any { raw.startsWith("$it.") }) raw else raw.substringAfter('.', "")
        if (rest.isEmpty()) return null
        val build = RETROARCH[rest.substringBefore('.')]
        if (build != null && '.' in rest) {
            val named = rest.substringAfter('.')
            val core = CORE_ALIASES[named] ?: named
            if (!CORE.matches(core)) return null
            return CocoonPlayer("${build.first}-$core", core, build.second)
        }
        val pkg = PACKAGE_ALIASES[rest] ?: rest
        val profile = LibraryPresets.profileFor(pkg) ?: return null
        return CocoonPlayer(profile.id, null, profile.`package`)
    }
}
