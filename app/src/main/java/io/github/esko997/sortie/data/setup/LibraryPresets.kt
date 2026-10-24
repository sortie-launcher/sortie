package io.github.esko997.sortie.data.setup

import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef

/**
 * A ready-made system for library setup. [system] is a template: [SystemDef.roots] is empty (the user picks folders) and
 * [SystemDef.profiles] lists ids from [profiles] in fallback order. [instructions] states what the user still has to do
 * and what has (not) been tested, in plain words.
 */
data class LibraryPreset(val system: SystemDef, val profiles: List<LaunchProfile>, val instructions: String = "")

/**
 * The built-in catalog: common systems with their usual file extensions and launch profiles. Two levels of evidence, kept
 * apart in labels and instructions:
 * - device-tested: the profiles in docs/LAUNCHERS.md, each seen booting a game on an AYN Thor (Android 13) on 2026-10-23;
 * - source-verified: other official package variants of the same apps (RetroArch AArch64/32-bit, PPSSPP Gold) whose
 *   activity, intent extras and data folders were checked against the upstream source (RetroArch
 *   pkg/android/phoenix/build.gradle flavors + frontend/drivers/platform_unix.c; PPSSPP android/build.gradle.kts flavors +
 *   AndroidManifest.xml, 2026-10-24) but not run on a device.
 * Every emulator-specific constant (package, activity, intent shape, RetroArch core paths) lives here and in launchers.json,
 * never in UI code. Nothing here promises a game runs: an app version, missing BIOS, or a missing RetroArch core can still
 * stop it, which the instructions say. The UI detects whether each profile's [LaunchProfile.package] is installed; a
 * [LaunchProfile.generic] profile needs no package.
 */
object LibraryPresets {
    private const val RETROARCH = "com.retroarch"

    /**
     * One official RetroArch build. All flavors share the namespace `com.retroarch` (so the activity class is the same) and
     * differ only by application id; the native frontend reads ROM/LIBRETRO/CONFIGFILE/IME/DATADIR/SDCARD/EXTERNAL extras
     * and looks for cores under `DATADIR/cores`, i.e. each package's own private folder.
     */
    private class RetroArchBuild(val pkg: String, val idPrefix: String, val appLabel: String)

    /** retroarch.com / Google Play build; device-tested. */
    private val RA_TESTED = RetroArchBuild(RETROARCH, "ra", "RetroArch")

    /** The `aarch64` and `ra32` flavors from retroarch.com; source-verified, not device-tested. */
    private val RA_SOURCE_ONLY = listOf(
        RetroArchBuild("$RETROARCH.aarch64", "ra64", "RetroArch (AArch64)"),
        RetroArchBuild("$RETROARCH.ra32", "ra32", "RetroArch (32-bit)")
    )

    private fun retroArch(core: String, label: String, build: RetroArchBuild = RA_TESTED) = LaunchProfile(
        id = "${build.idPrefix}-$core",
        label = "${build.appLabel} · $label",
        `package` = build.pkg,
        activity = "com.retroarch.browser.retroactivity.RetroActivityFuture",
        action = "android.intent.action.MAIN",
        extras = mapOf(
            "ROM" to "\${file}",
            "LIBRETRO" to "/data/user/0/${build.pkg}/cores/${core}_libretro_android.so",
            "CONFIGFILE" to "/storage/emulated/0/Android/data/${build.pkg}/files/retroarch.cfg",
            "IME" to "",
            "DATADIR" to "/data/user/0/${build.pkg}",
            "SDCARD" to "/storage/emulated/0",
            "EXTERNAL" to "/storage/emulated/0/Android/data/${build.pkg}/files"
        ),
        // Keep its singleInstance onNewIntent path: CLEAR_TASK can hang RetroArch's native onDestroy.
        // Do not use QUITFOCUS: switching to Sortie must not kill a running game's unsaved state.
        flags = listOf("CLEAR_TOP")
    )

    /** RetroArch cores whose boot through [retroArch] was seen on the device; the others use the same verified launch method. */
    private val BOOTED_CORES = setOf("fbneo", "snes9x", "mednafen_ngp", "mupen64plus_next", "ppsspp", "gambatte")

    private val OPEN_WITH = LaunchProfile(id = "open-with", label = "Open with…", generic = true)

    private val ARMSX2 = LaunchProfile(
        id = "armsx2-uri",
        label = "ARMSX2 (content URI)",
        `package` = "come.nanodata.armsx2",
        activity = "com.armsx2.MainActivity",
        action = "android.intent.action.VIEW",
        data = "\${uri}",
        flags = listOf("GRANT_READ_URI_PERMISSION")
    )
    private val DUCKSTATION = LaunchProfile(
        id = "duckstation",
        label = "DuckStation",
        `package` = "com.github.stenzek.duckstation",
        activity = "com.github.stenzek.duckstation.EmulationActivity",
        action = "android.intent.action.MAIN",
        extras = mapOf("bootPath" to "\${file}")
    )
    private val PPSSPP = LaunchProfile(
        id = "ppsspp",
        label = "PPSSPP",
        `package` = "org.ppsspp.ppsspp",
        activity = "org.ppsspp.ppsspp.PpssppActivity",
        action = "android.intent.action.VIEW",
        data = "\${uri}",
        mimeType = "application/octet-stream",
        flags = listOf("GRANT_READ_URI_PERMISSION", "CLEAR_TASK", "CLEAR_TOP")
    )

    /** Gold flavor: own application id, same `org.ppsspp.ppsspp` namespace, manifest and activity (source-verified). */
    private val PPSSPP_GOLD = PPSSPP.copy(id = "ppsspp-gold", label = "PPSSPP Gold", `package` = "org.ppsspp.ppssppgold")
    private val DOLPHIN = LaunchProfile(
        id = "dolphin",
        label = "Dolphin",
        `package` = "org.dolphinemu.dolphinemu",
        activity = "org.dolphinemu.dolphinemu.ui.main.MainActivity",
        action = "android.intent.action.MAIN",
        extras = mapOf("AutoStartFile" to "\${file}")
    )
    private val MELONDS = LaunchProfile(
        id = "melonds",
        label = "melonDS",
        `package` = "me.magnum.melonds",
        activity = "me.magnum.melonds.ui.emulator.EmulatorActivity",
        action = "me.magnum.melonds.LAUNCH_ROM",
        extras = mapOf("uri" to "\${uri}"),
        flags = listOf("GRANT_READ_URI_PERMISSION", "CLEAR_TASK", "CLEAR_TOP")
    )
    private val LIME3DS = LaunchProfile(
        id = "lime3ds-file",
        label = "Lime3DS (file URI)",
        `package` = "io.github.lime3ds.android",
        activity = "org.citra.citra_emu.activities.EmulationActivity",
        action = "android.intent.action.VIEW",
        data = "file://\${file}",
        mimeType = "application/octet-stream",
        flags = listOf("CLEAR_TASK", "CLEAR_TOP")
    )
    private val MUPEN_FZ = LaunchProfile(
        id = "mupen-fz",
        label = "Mupen64Plus FZ Pro",
        `package` = "org.mupen64plusae.v3.fzurita.pro",
        activity = "paulscode.android.mupen64plusae.SplashActivity",
        action = "android.intent.action.VIEW",
        data = "file://\${file}"
    )
    private val EMUCOREV = LaunchProfile(
        id = "emucorev",
        label = "EmuCoreV",
        `package` = "com.sbro.emucorev",
        activity = "com.sbro.emucorev.core.vita.Emulator",
        action = "com.sbro.emucorev.action.LAUNCH",
        extras = mapOf("titleId" to "\${titleid}"),
        extrasArray = mapOf("AppStartParameters" to listOf("-r", "\${titleid}"))
    )

    /** Per-profile caveats appended to a preset's instructions. */
    private val PROFILE_NOTES = mapOf(
        ARMSX2.id to "ARMSX2 is started with a content URI (a plain file path only opens its library). Set up the PS2 BIOS inside ARMSX2.",
        DUCKSTATION.id to "DuckStation needs a PlayStation BIOS set up inside DuckStation.",
        PPSSPP.id to "PPSSPP receives a read-only content URI. Complete its first-run setup first. Disc images were tested; " +
            "loose .elf/.prx homebrew may need to be opened inside PPSSPP instead.",
        PPSSPP_GOLD.id to "PPSSPP Gold (org.ppsspp.ppssppgold) uses the same activity and intent as PPSSPP according to its source; it " +
            "was not tested on a device.",
        DOLPHIN.id to "Dolphin starts the file directly.",
        MELONDS.id to "melonDS receives the game as a content URI; some games need DS BIOS/firmware set up inside melonDS.",
        LIME3DS.id to "Lime3DS boots .3ds/.cci files by path; encrypted dumps and installed-only (CIA) titles do not start this way. " +
            "Forks and successors with another package name are not covered by this profile.",
        MUPEN_FZ.id to "The profile targets the paid Mupen64Plus FZ Pro package; the free edition has a different package and is untested.",
        EMUCOREV.id to "EmuCoreV starts titles that are already installed inside EmuCoreV, by title id: the id comes from the file name " +
            "(e.g. PCSE00001) or from a `<file>.titleid` text file next to it.",
        OPEN_WITH.id to "Open with… asks Android which app should open the file. Add it explicitly if wanted; Android may offer apps that cannot play the game."
    )

    private data class Spec(
        val id: String,
        val label: String,
        val generation: String,
        val colour: String,
        val extensions: List<String>,
        val profiles: List<LaunchProfile>,
        val bios: List<String> = emptyList(),
        val note: String = ""
    )

    private const val C128 = "#f59e0b"
    private const val CWII = "#14b8a6"
    private const val CHAND = "#ec4899"
    private const val C64 = "#10b981"
    private const val C32 = "#8b5cf6"
    private const val CARCADE = "#ef4444"
    private const val C16 = "#3b82f6"
    private const val C8 = "#64748b"

    private val specs: List<Spec> = listOf(
        Spec(
            "ps2",
            "PlayStation 2",
            "128-bit",
            C128,
            listOf(".iso", ".chd", ".cso"),
            listOf(ARMSX2)
        ),
        Spec(
            "gc",
            "GameCube",
            "128-bit",
            C128,
            listOf(".iso", ".rvz", ".gcz", ".gcm", ".ciso"),
            listOf(DOLPHIN)
        ),
        Spec(
            "wii",
            "Wii",
            "Wii",
            CWII,
            listOf(".iso", ".rvz", ".wbfs", ".wia", ".gcz"),
            listOf(DOLPHIN)
        ),
        Spec(
            "dreamcast",
            "Dreamcast",
            "128-bit",
            C128,
            listOf(".chd", ".gdi", ".cdi", ".m3u"),
            listOf(retroArch("flycast", "Flycast")),
            note = "Use .m3u playlists for multi-disc games and keep their .chd files in a separate folder, or each disc is listed too."
        ),
        Spec(
            "psx",
            "PlayStation",
            "32-bit",
            C32,
            listOf(".chd", ".cue", ".pbp", ".m3u", ".iso"),
            listOf(DUCKSTATION, retroArch("swanstation", "SwanStation")),
            note = "Use .cue (not .bin) for multi-track games; a game folder holding both .m3u and .cue files lists each of them."
        ),
        Spec(
            "saturn",
            "Saturn",
            "32-bit",
            C32,
            listOf(".chd", ".cue", ".m3u"),
            listOf(retroArch("yabasanshiro", "YabaSanshiro"), retroArch("kronos", "Kronos")),
            note = "YabaSanshiro can start most games without a BIOS; Kronos needs a Saturn BIOS in RetroArch's system folder."
        ),
        Spec(
            "n64",
            "Nintendo 64",
            "64-bit",
            C64,
            listOf(".z64", ".n64", ".v64", ".zip"),
            listOf(MUPEN_FZ, retroArch("mupen64plus_next", "Mupen64Plus-Next"))
        ),
        Spec(
            "psp",
            "PSP",
            "handheld",
            CHAND,
            listOf(".iso", ".cso", ".chd", ".pbp", ".elf", ".prx"),
            listOf(PPSSPP, PPSSPP_GOLD, retroArch("ppsspp", "PPSSPP core")),
            note = "Homebrew kept as <name>/EBOOT.PBP in its own folder is not found (subfolders are not searched and every such file " +
                "has the same name); add the .elf/.prx or a renamed .pbp directly to the folder instead."
        ),
        Spec(
            "nds",
            "Nintendo DS",
            "handheld",
            CHAND,
            listOf(".nds", ".zip"),
            listOf(MELONDS, retroArch("melondsds", "melonDS DS"), retroArch("desmume", "DeSmuME"))
        ),
        Spec(
            "n3ds",
            "Nintendo 3DS",
            "handheld",
            CHAND,
            listOf(".3ds", ".cci"),
            listOf(LIME3DS)
        ),
        Spec(
            "psvita",
            "PlayStation Vita",
            "handheld",
            CHAND,
            emptyList(),
            listOf(EMUCOREV),
            note = "No extension filter: every file in the folder (except notes, images and .titleid files) is listed. Add one if your " +
                "folder holds other files."
        ),
        Spec("gba", "Game Boy Advance", "handheld", CHAND, listOf(".gba", ".zip", ".7z"), listOf(retroArch("mgba", "mGBA"))),
        Spec("gbc", "Game Boy Color", "8-bit", C8, listOf(".gbc", ".zip", ".7z"), listOf(retroArch("gambatte", "Gambatte"))),
        Spec("gb", "Game Boy", "8-bit", C8, listOf(".gb", ".zip", ".7z"), listOf(retroArch("gambatte", "Gambatte"))),
        Spec("ngp", "Neo Geo Pocket", "handheld", CHAND, listOf(".ngp", ".ngc", ".zip"), listOf(retroArch("mednafen_ngp", "Beetle NeoPop"))),
        Spec("wonderswan", "WonderSwan", "handheld", CHAND, listOf(".ws", ".wsc", ".zip"), listOf(retroArch("mednafen_wswan", "Beetle WonderSwan"))),
        Spec("virtualboy", "Virtual Boy", "handheld", CHAND, listOf(".vb", ".zip"), listOf(retroArch("mednafen_vb", "Beetle VB"))),
        Spec("atarilynx", "Atari Lynx", "handheld", CHAND, listOf(".lnx", ".zip"), listOf(retroArch("handy", "Handy")), bios = listOf("lynxboot.img")),
        Spec(
            "arcade",
            "Arcade",
            "arcade",
            CARCADE,
            listOf(".zip", ".7z"),
            listOf(retroArch("fbneo", "FinalBurn Neo"), retroArch("mame", "MAME")),
            note = "Arcade sets must match the emulator's romset version. BIOS archives such as neogeo.zip are hidden from the list " +
                "and can stay in the same folder."
        ),
        Spec(
            "neogeo",
            "Neo Geo",
            "arcade",
            CARCADE,
            listOf(".zip", ".7z"),
            listOf(retroArch("fbneo", "FinalBurn Neo")),
            note = "neogeo.zip (the BIOS) can stay in the game folder; it is hidden from the list."
        ),
        Spec(
            "neogeocd",
            "Neo Geo CD",
            "arcade",
            CARCADE,
            listOf(".chd", ".cue"),
            listOf(retroArch("neocd", "NeoCD")),
            bios = listOf("neocd.zip")
        ),
        Spec(
            "genesis",
            "Mega Drive / Genesis",
            "16-bit",
            C16,
            listOf(".md", ".gen", ".smd", ".bin", ".zip", ".7z"),
            listOf(retroArch("genesis_plus_gx", "Genesis Plus GX"))
        ),
        Spec(
            "segacd",
            "Sega CD",
            "16-bit",
            C16,
            listOf(".chd", ".cue", ".m3u"),
            listOf(retroArch("genesis_plus_gx", "Genesis Plus GX")),
            bios = listOf("bios_CD_U.bin")
        ),
        Spec("sega32x", "32X", "16-bit", C16, listOf(".32x", ".zip", ".7z"), listOf(retroArch("picodrive", "PicoDrive"))),
        Spec("snes", "Super Nintendo", "16-bit", C16, listOf(".sfc", ".smc", ".zip", ".7z"), listOf(retroArch("snes9x", "Snes9x"))),
        Spec("pcengine", "PC Engine / TurboGrafx-16", "16-bit", C16, listOf(".pce", ".zip", ".7z"), listOf(retroArch("mednafen_pce", "Beetle PCE"))),
        Spec(
            "tg-cd",
            "PC Engine CD",
            "16-bit",
            C16,
            listOf(".chd", ".cue", ".m3u"),
            listOf(retroArch("mednafen_pce", "Beetle PCE")),
            bios = listOf("syscard3.pce")
        ),
        Spec(
            "amiga",
            "Amiga",
            "16-bit",
            C16,
            listOf(".adf", ".hdf", ".lha", ".ipf", ".m3u"),
            listOf(retroArch("puae", "PUAE")),
            bios = listOf("kick34005.A500", "kick40068.A1200")
        ),
        Spec("mastersystem", "Master System", "8-bit", C8, listOf(".sms", ".zip", ".7z"), listOf(retroArch("genesis_plus_gx", "Genesis Plus GX"))),
        Spec("gamegear", "Game Gear", "8-bit", C8, listOf(".gg", ".zip", ".7z"), listOf(retroArch("genesis_plus_gx", "Genesis Plus GX"))),
        Spec("nes", "NES", "8-bit", C8, listOf(".nes", ".zip", ".7z"), listOf(retroArch("mesen", "Mesen"), retroArch("fceumm", "FCEUmm"))),
        Spec("fds", "Famicom Disk System", "8-bit", C8, listOf(".fds", ".zip"), listOf(retroArch("fceumm", "FCEUmm")), bios = listOf("disksys.rom")),
        Spec(
            "msx",
            "MSX",
            "8-bit",
            C8,
            listOf(".rom", ".mx1", ".mx2", ".dsk", ".zip"),
            listOf(retroArch("bluemsx", "blueMSX")),
            note = "blueMSX also needs its Machines and Databases folders in RetroArch's system folder."
        ),
        Spec("zxspectrum", "ZX Spectrum", "8-bit", C8, listOf(".tzx", ".tap", ".z80", ".sna", ".zip"), listOf(retroArch("fuse", "Fuse")))
    )

    /** BIOS archives that live next to arcade games and must not be listed as games; seeded into a new systems.json only. */
    val defaultSkipFiles: List<String> = listOf(
        "neogeo.zip", "neogeo.7z", "pgm.zip", "pgm.7z", "stvbios.zip", "stvbios.7z", "naomi.zip", "naomi.7z",
        "awbios.zip", "awbios.7z", "decocass.zip", "skns.zip", "cvs.zip"
    )

    private fun instructions(spec: Spec, profiles: List<LaunchProfile>): String = buildList {
        val cores = profiles.filter { it.`package` == RETROARCH }
        if (cores.isNotEmpty()) {
            fun coreName(p: LaunchProfile) = p.label.substringAfter(" · ")
            val booted = cores.filter { it.id.removePrefix("${RA_TESTED.idPrefix}-") in BOOTED_CORES }
            add(
                "RetroArch: download the core (${cores.joinToString(transform = ::coreName)}) in RetroArch's Online Updater first; " +
                    "profiles assume each RetroArch app's default folders. The ${RA_TESTED.appLabel} app ($RETROARCH) launch was tested on a " +
                    "device" + (if (booted.isNotEmpty()) " and booted with ${booted.joinToString(transform = ::coreName)}." else "; this core was not.") +
                    " ${RA_SOURCE_ONLY.joinToString(" and ") { "${it.appLabel} (${it.pkg})" }} use the same launch interface with their own " +
                    "folders according to RetroArch's source, but were not tested on a device."
            )
            add(
                "After core setup, use RetroArch's Quit action before starting a game here. Save before changing games. " +
                    "RetroArch 1.22 may close itself on the first attempt to open different content; if you return to Sortie, choose the game again."
            )
        }
        profiles.forEach { p -> PROFILE_NOTES[p.id]?.let { add(it) } }
        if (spec.bios.isNotEmpty()) add("Expects in the BIOS folder: ${spec.bios.joinToString()} (Sortie only shows a badge when they are missing).")
        if (spec.note.isNotEmpty()) add(spec.note)
        add(
            "Only files directly in the chosen folders are listed (subfolders are not searched; add them as extra folders). Two files " +
                "with the same name in different folders appear once."
        )
    }.joinToString("\n")

    val systems: List<LibraryPreset> = specs.mapIndexed { index, spec ->
        // each device-tested RetroArch profile is followed by the same core in the source-verified RetroArch builds
        val profiles = spec.profiles.flatMap { p ->
            if (p.`package` != RETROARCH) {
                listOf(p)
            } else {
                val core = p.id.removePrefix("${RA_TESTED.idPrefix}-")
                listOf(p) + RA_SOURCE_ONLY.map { retroArch(core, p.label.substringAfter(" · "), it) }
            }
        } + OPEN_WITH
        LibraryPreset(
            system = SystemDef(
                id = spec.id,
                label = spec.label,
                generation = spec.generation,
                roots = emptyList(),
                extensions = spec.extensions,
                // A missing emulator should explain itself, not silently offer unrelated apps in Android's wildcard chooser.
                profiles = profiles.filterNot { it.generic }.map { it.id },
                colour = spec.colour,
                order = index,
                bios = spec.bios
            ),
            profiles = profiles,
            instructions = instructions(spec, profiles)
        )
    }

    /** The preset for [systemId], if the catalog has one. */
    fun preset(systemId: String): LibraryPreset? = systems.firstOrNull { it.system.id == systemId }
}
