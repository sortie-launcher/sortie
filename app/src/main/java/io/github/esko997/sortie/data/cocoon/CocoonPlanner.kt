package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.data.setup.LibraryConfiguration
import io.github.esko997.sortie.data.setup.LibraryPreset
import io.github.esko997.sortie.data.setup.LibraryPresets
import java.io.File
import java.util.Locale

/** A gamelist entry resolved to a Sortie game id (`<systemId>/<file name on disk>`, as the indexer will make it). */
data class CocoonMatch(val platform: String, val game: CocoonGame, val gameId: String, val systemId: String, val file: File)

/** One Cocoon artwork file to copy; [kind] is covers|marquees|screenshots, [target] `<media>/<kind>/<rom stem>.<ext>`. */
data class ArtworkCopy(val gameId: String, val kind: String, val source: File, val target: File, val bytes: Long)

/** A system to create with LibrarySetupStore.saveSystem; [profiles] are the ones not configured yet. */
data class NewSystem(val system: SystemDef, val profiles: List<LaunchProfile>, val fromPreset: Boolean)

/** Cocoon's emulator for a system, to become `UserData.systemProfiles[systemId]` unless the user already chose one. */
data class SystemProfileChoice(val systemId: String, val profileId: String, val installed: Boolean)

/** A user folder from the backup: id "cocoon-<n>", include = matched game ids; [unresolved] members had no Sortie id. */
data class FolderPlan(val def: FolderDef, val unresolved: Int)

/** A real Cocoon session (backup only) as Sortie will record it. */
data class SessionPlan(val gameId: String, val profileId: String?, val startedAt: Long, val endedAt: Long)

/** Everything an import would do, for the preview and the applier. Built by [CocoonPlanner]; nothing is written while planning. */
data class CocoonImportPlan(
    val cocoonDir: File,
    val backup: CocoonBackup?,
    /** Roots as absolute paths or card-relative (relative when under cardRoot, like Library setup writes). */
    val newSystems: List<NewSystem>,
    /** systemId → media path to set when an existing system has none (needed by artwork copy), relative to card root when possible. */
    val mediaDirs: Map<String, String>,
    val systemProfiles: List<SystemProfileChoice>,
    /** Game ids and app:<pkg> ids. */
    val favourites: List<String>,
    val hidden: List<String>,
    /** Backup only. */
    val profileOverrides: Map<String, String>,
    /** Backup only. */
    val sessions: List<SessionPlan>,
    /** Backup only. */
    val folders: List<FolderPlan>,
    val artwork: List<ArtworkCopy>,
    val artworkBytes: Long,
    val matched: Int,
    val unmatched: List<String>,
    val ambiguous: List<String>,
    /** Artwork references whose volume is not mounted or whose file is gone ("unavailable"). */
    val missingVolume: Int,
    /** Games with playcount > 0 (preview text only; no sessions are made from it). */
    val playStats: Int,
    /** Shortcut platform game names, for re-pin advice. */
    val droidDeck: List<String>,
    /** "switch: no ROM folder found", … */
    val skippedPlatforms: List<String>,
    val notes: List<String>,
    /**
     * Copies of Sortie's old default Favourites folder in folders.json ([LibraryConfiguration.legacyFavourites] when planned);
     * removed with the Favourites category, since the built-in Favourites folder replaces them. Customised entries with a
     * built-in folder's id are not counted and never removed.
     */
    val legacyFavourites: Int = 0
)

/**
 * Turns a Cocoon library (+ optional backup) and the discovered roots into an import plan. Pure apart from reading the file
 * system through the injected functions (artwork sources, ES-DE media/gamelist folders, existing media listings) and
 * canonicalising paths; never writes.
 *
 * Rules (docs/COCOON_IMPORT.md "Identity", "Systems and roots"): a Cocoon entry matches only within its platform's alias
 * group, case-insensitively with an exact-case hit preferred; ambiguous and missing entries are reported, never guessed, and
 * their flags are dropped. A discovered root already owned by a configured system maps to that system, which is not changed
 * (apart from a media folder when it has none and artwork is copied); unowned roots create systems, from a preset when the
 * alias table names one. A later index pass still applies Sortie's extension and skip rules, so the applier filters ids
 * against the index.
 */
class CocoonPlanner(
    val cardRoot: File,
    val sortieDir: File,
    val config: LibraryConfiguration,
    val installed: (String) -> Boolean,
    /** Sortie already has art for it. */
    val existingMedia: (systemId: String, kind: String, stem: String) -> Boolean,
    private val exists: (File) -> Boolean = File::isFile,
    private val length: (File) -> Long = File::length,
    private val isDirectory: (File) -> Boolean = File::isDirectory,
    private val list: (File) -> List<String>? = { it.list()?.toList() }
) {
    fun plan(library: CocoonLibrary, backup: CocoonBackup?, roots: Map<String, List<File>>, listings: List<RootProbe.Listing>): CocoonImportPlan =
        Planning(library, backup, roots, listings).build()

    private sealed interface Outcome {
        class Matched(val gameId: String, val systemId: String, val file: File) : Outcome
        class Ambiguous(val gameIds: List<String>) : Outcome
        object Missing : Outcome
    }

    private class Hit(val gameId: String, val systemId: String, val file: File, val exact: Boolean, val home: Boolean)

    /** A system to be created, gathered over the Cocoon platforms that land on it. */
    private class Draft(val id: String, val preset: LibraryPreset?, val label: String) {
        val platforms = LinkedHashSet<String>()
        val rootKeys = LinkedHashSet<String>()
        val extensions = LinkedHashSet<String>()
        var player: LaunchProfile? = null
    }

    private inner class Planning(
        val library: CocoonLibrary,
        val backup: CocoonBackup?,
        val roots: Map<String, List<File>>,
        listings: List<RootProbe.Listing>
    ) {
        val canonicalPaths = HashMap<String, String>()
        val notes = ArrayList<String>()
        val skipped = ArrayList<String>()
        val configuredIds = config.systems.mapTo(HashSet()) { it.id }
        val configuredById = config.systems.associateBy { it.id }
        val configuredProfiles = config.profiles.associateBy { it.id }
        val presetProfiles = LinkedHashMap<String, LaunchProfile>().also { map ->
            LibraryPresets.systems.forEach { preset -> preset.profiles.forEach { map.putIfAbsent(it.id, it) } }
        }

        /** Canonical directory path → listing; every directory the planner can see files in. */
        val listingByKey = LinkedHashMap<String, RootProbe.Listing>().also { map -> listings.forEach { map.putIfAbsent(key(it.dir), it) } }
        val lowerIndex = HashMap<String, Map<String, List<String>>>()
        val configuredOwner = HashMap<String, SystemDef>().also { map ->
            config.systems.forEach { s -> s.roots.forEach { r -> map.putIfAbsent(key(Indexer.resolve(cardRoot, r)), s) } }
        }

        /** Cocoon platform → keys of its discovered roots (outside the Cocoon dir, listed). */
        val platformKeys = LinkedHashMap<String, List<String>>()

        /** Unowned root key → the platform that creates a system for it. */
        val claims = HashMap<String, String>()
        val newIdOf = HashMap<String, String>()
        val drafts = LinkedHashMap<String, Draft>()
        val groupKeys = HashMap<String, List<String>>()
        val outcomes = HashMap<Pair<String, String>, Outcome>()

        /** Every lower-case folder name that stands for a system; see [claimUnownedRoots]. */
        val systemNames: Set<String> = buildSet {
            addAll(CocoonAliases.ESDE_SYSTEMS)
            CocoonAliases.GROUPS.forEach { addAll(it) }
            addAll(CocoonAliases.PLATFORM_TO_PRESET.keys)
            addAll(CocoonAliases.PLATFORM_TO_PRESET.values)
            LibraryPresets.systems.forEach { addAll(listOf(it.system.id, it.system.folder)) }
            library.platforms.forEach { add(it.id) }
            config.systems.forEach { addAll(listOf(it.id, it.folder)) }
            backup?.roots?.keys?.let { addAll(it) }
        }.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

        /** The folder names that stand for [platform]: its alias group and its preset id. */
        fun ownNames(platform: String): Set<String> =
            (CocoonAliases.group(platform) + (CocoonAliases.PLATFORM_TO_PRESET[platform] ?: platform)).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

        fun build(): CocoonImportPlan {
            library.problems.forEach { notes += "Could not read gamelist $it" }
            val romPlatforms = library.platforms.filter { it.id !in NON_ROM }
            if (library.platforms.any { it.id == POD }) skipped += "$POD: Cocoon's own screens, not games"
            val droidDeck = library.platforms.filter { it.id == SHORTCUT }.flatMap { p -> p.games.map { it.name ?: it.file } }

            for (p in romPlatforms) {
                platformKeys[p.id] = roots[p.id].orEmpty()
                    .filterNot { CocoonPaths.isInside(it, library.dir) }
                    .map { key(it) }
                    .filter { it in listingByKey }
                    .distinct()
            }
            claimUnownedRoots(romPlatforms)

            val matches = ArrayList<CocoonMatch>()
            val unmatched = ArrayList<String>()
            val ambiguous = ArrayList<String>()
            val votes = LinkedHashMap<String, LinkedHashMap<String, Int>>() // systemId → platform → matched games
            for (p in romPlatforms) {
                if (groupDirs(p.id).isEmpty()) {
                    skipped += "${p.id}: no ROM folder found (${count(p.games.size, "game")})"
                    continue
                }
                newIdOf[p.id]?.let { votes.getOrPut(it) { LinkedHashMap() }.putIfAbsent(p.id, 0) }
                for (g in p.games) {
                    when (val o = match(p.id, g.file)) {
                        is Outcome.Matched -> {
                            matches += CocoonMatch(p.id, g, o.gameId, o.systemId, o.file)
                            drafts[o.systemId]?.let { d -> extensionOf(o.file.name)?.let { d.extensions += it } }
                            val forSystem = votes.getOrPut(o.systemId) { LinkedHashMap() }
                            forSystem[p.id] = (forSystem[p.id] ?: 0) + 1
                        }
                        is Outcome.Ambiguous -> ambiguous += "${p.id}/${g.file} (${o.gameIds.joinToString { it.substringBefore('/') }})"
                        Outcome.Missing -> unmatched += "${p.id}/${g.file}"
                    }
                }
            }

            val choices = chooseProfiles(romPlatforms, votes)
            val newSystems = drafts.values.map { newSystem(it) }
            val knownProfiles = LinkedHashMap(configuredProfiles).also { map -> newSystems.forEach { s -> s.profiles.forEach { map.putIfAbsent(it.id, it) } } }

            val (artwork, unavailable, mediaDirs) = planArtwork(matches, newSystems)

            val favourites = LinkedHashSet<String>()
            val hidden = LinkedHashSet<String>()
            matches.forEach { m ->
                if (m.game.favourite) favourites += m.gameId
                if (m.game.hidden) hidden += m.gameId
            }
            val overrides = LinkedHashMap<String, String>()
            val sessions = ArrayList<SessionPlan>()
            val folders = ArrayList<FolderPlan>()
            if (backup != null) {
                backup.favourites.forEach { ref -> resolve(ref)?.let { favourites += it } }
                backup.hidden.forEach { ref -> resolve(ref)?.let { hidden += it } }
                overrides += planOverrides(backup, knownProfiles)
                sessions += planSessions(backup, knownProfiles, newSystems)
                folders += planFolders(backup)
            }

            return CocoonImportPlan(
                cocoonDir = library.dir,
                backup = backup,
                newSystems = newSystems,
                mediaDirs = mediaDirs,
                systemProfiles = choices,
                favourites = favourites.toList(),
                hidden = hidden.toList(),
                profileOverrides = overrides,
                sessions = sessions,
                folders = folders,
                artwork = artwork,
                artworkBytes = artwork.sumOf { it.bytes },
                matched = matches.size,
                unmatched = unmatched,
                ambiguous = ambiguous,
                missingVolume = unavailable,
                playStats = library.platforms.filter { it.id != POD }.sumOf { p -> p.games.count { it.playCount > 0 } },
                droidDeck = droidDeck,
                skippedPlatforms = skipped,
                notes = notes,
                legacyFavourites = config.legacyFavourites
            )
        }

        /**
         * Each discovered root no configured system owns is claimed by the platform with the most of its games there, and that
         * platform gets a system id: the preset id from the alias table, or its own id when the preset id is already a configured
         * system (an existing system is never extended or renamed). Platforms landing on one id share one system.
         *
         * A directory named for a system (ES-DE's folder names, Sortie's presets, the alias tables, this Cocoon's platforms and
         * the configured systems) is only claimable by a platform whose alias group carries that name: `roms/neogeo` full of
         * FBNeo romset names, `roms/gamegear` sharing titles with Genesis or `roms/zxspectrum` holding ports named like CPS1
         * sets belong to their own system, never to the platform that happens to match them. A directory with no system name
         * is claimable when the platform's games make up at least half of its files; the probe's ≥10 %-of-the-platform threshold
         * is enough to find a root but not to own one.
         */
        fun claimUnownedRoots(platforms: List<CocoonPlatform>) {
            val best = HashMap<String, Pair<String, Int>>()
            val foreign = LinkedHashMap<String, LinkedHashSet<String>>() // root key → platforms refused it for its name
            for (p in platforms) {
                val names = p.games.mapTo(HashSet()) { it.file.lowercase(Locale.ROOT) }
                val own = ownNames(p.id)
                for (k in platformKeys[p.id].orEmpty()) {
                    if (k in configuredOwner) continue
                    val listing = listingByKey.getValue(k)
                    val hits = lowered(k).keys.count { it in names }
                    val dirName = listing.dir.name.lowercase(Locale.ROOT)
                    val claimable = when {
                        dirName in own -> true
                        dirName in systemNames -> false.also { foreign.getOrPut(k) { LinkedHashSet() } += p.id }
                        else -> hits * 2 >= listing.files.size
                    }
                    if (!claimable) continue
                    val current = best[k]
                    if (current == null || hits > current.second) best[k] = p.id to hits
                }
            }
            best.forEach { (k, v) -> claims[k] = v.first }
            foreign.filterKeys { it !in claims }.forEach { (k, ps) ->
                notes += "${listingByKey.getValue(k).dir.path} holds games of ${ps.joinToString()} but is named for another system, so it " +
                    "is not added; set it up in Library setup if it is theirs."
            }
            for (p in platforms) {
                val claimed = platformKeys[p.id].orEmpty().filter { claims[it] == p.id }
                if (claimed.isEmpty()) continue
                val presetId = CocoonAliases.PLATFORM_TO_PRESET[p.id] ?: p.id
                val id = listOf(presetId, p.id, "${p.id}-cocoon").firstOrNull { NEW_ID.matches(it) && it !in configuredIds }
                if (id == null) {
                    claimed.forEach { claims.remove(it) }
                    skipped += "${p.id}: no usable system id (ids must use lower-case letters, digits, '.', '_' or '-')"
                    continue
                }
                if (id != presetId && presetId in configuredIds) {
                    notes += "${p.id}: your ${configuredById[presetId]?.label ?: presetId} system does not include " +
                        claimed.joinToString { listingByKey.getValue(it).dir.path } + ", so a separate system \"$id\" is created for it."
                }
                newIdOf[p.id] = id
                val draft = drafts.getOrPut(id) {
                    val preset = LibraryPresets.preset(presetId)
                    val label = when {
                        preset == null -> CocoonAliases.LABELS[id] ?: CocoonAliases.LABELS[p.id] ?: id.replaceFirstChar { it.titlecase(Locale.ROOT) }
                        preset.system.id == id -> preset.system.label
                        else -> "${preset.system.label} (${p.id})"
                    }
                    Draft(id, preset, label)
                }
                draft.platforms += p.id
                draft.rootKeys += claimed
            }
        }

        /**
         * Listed directories a platform's entries may be found in: its alias group's discovered and configured roots, but only
         * those owned by (or claimed for) a group member. The probe can propose a directory of another system because of
         * shared names — ZX Spectrum ports of CPS1 games (`strider.zip`), Game Gear and Genesis sharing many titles — and a
         * match there would file the game under the wrong system.
         */
        fun groupDirs(platform: String): List<String> = groupKeys.getOrPut(platform) {
            val group = CocoonAliases.group(platform)
            val keys = LinkedHashSet<String>()
            group.forEach { q -> platformKeys[q]?.let { keys += it } }
            config.systems.filter { it.id in group || it.folder in group }.forEach { s ->
                s.roots.forEach { r -> key(Indexer.resolve(cardRoot, r)).takeIf { it in listingByKey }?.let { keys += it } }
            }
            keys.filter { k -> ownerPlatformsOf(k).any { it in group } }
        }

        fun systemOfDir(key: String): String? = configuredOwner[key]?.id ?: claims[key]?.let { newIdOf[it] }

        /** The Cocoon platform names a directory stands for: the claiming platform, or the configured owner's id and folder. */
        fun ownerPlatformsOf(key: String): Set<String> = claims[key]?.let { setOf(it) } ?: configuredOwner[key]?.let { setOf(it.id, it.folder) } ?: emptySet()

        /**
         * The platform's own directories win over the rest of its alias group (a `tg16` entry found both under roms/tg16 and
         * roms/pcengine is the tg16 file), and an exact-case name wins over a case-insensitive one. Only hits in the first
         * non-empty tier count, so two systems of one group holding the same name is ambiguous only when neither is "home".
         */
        fun match(platform: String, fileName: String): Outcome = outcomes.getOrPut(platform to fileName) {
            val lower = fileName.lowercase(Locale.ROOT)
            val hits = ArrayList<Hit>()
            for (k in groupDirs(platform)) {
                val names = lowered(k)[lower] ?: continue
                val systemId = systemOfDir(k) ?: continue
                val owners = ownerPlatformsOf(k)
                val home = platform in owners || CocoonAliases.PLATFORM_TO_PRESET[platform] in owners
                names.forEach { n -> hits += Hit("$systemId/$n", systemId, File(listingByKey.getValue(k).dir, n), n == fileName, home) }
            }
            // the same name in two roots of one system is one game for the indexer
            val tiers = listOf(
                hits.filter { it.home && it.exact },
                hits.filter { it.home },
                hits.filter { it.exact },
                hits
            )
            val pick = tiers.firstOrNull { it.isNotEmpty() }?.distinctBy { it.gameId }.orEmpty()
            when (pick.size) {
                0 -> Outcome.Missing
                1 -> pick.single().let { Outcome.Matched(it.gameId, it.systemId, it.file) }
                else -> Outcome.Ambiguous(pick.map { it.gameId })
            }
        }

        /**
         * Cocoon's per-platform emulator becomes the system's profile choice. Several platforms on one system (tg16 and
         * pcengine both on pcengine) vote by matched games. An existing system only gets a choice of a profile already
         * configured: the plan does not change existing systems' launchers.
         */
        fun chooseProfiles(platforms: List<CocoonPlatform>, votes: Map<String, Map<String, Int>>): List<SystemProfileChoice> {
            val byPlatform = HashMap<String, LaunchProfile>()
            for (p in platforms) {
                val raw = p.defaultPlayer ?: continue
                val profile = CocoonAliases.player(raw)?.let { profileFor(it) }
                if (profile == null) {
                    notes += "${p.id}: Cocoon's emulator \"$raw\" has no Sortie launch profile; choose one in Library setup."
                } else {
                    byPlatform[p.id] = profile
                }
            }
            val choices = ArrayList<SystemProfileChoice>()
            for ((systemId, perPlatform) in votes) {
                val ranked = perPlatform.entries.filter { it.key in byPlatform }.sortedByDescending { it.value }
                val chosen = ranked.firstOrNull()?.let { byPlatform.getValue(it.key) } ?: continue
                val others = ranked.map { byPlatform.getValue(it.key) }.filter { it.id != chosen.id }.map { it.label }.distinct()
                val draft = drafts[systemId]
                val label = draft?.label ?: configuredById[systemId]?.label ?: systemId
                if (others.isNotEmpty()) notes += "$label: Cocoon used ${chosen.label} and also ${others.joinToString()}; ${chosen.label} is chosen."
                if (draft != null) {
                    draft.player = chosen
                } else if (chosen.id !in configuredProfiles) {
                    notes += "$label: Cocoon launched it with ${chosen.label}, which is not set up in Sortie; add it in Library setup."
                    continue
                }
                choices += SystemProfileChoice(systemId, chosen.id, chosen.generic || chosen.`package`?.let(installed) ?: true)
            }
            return choices
        }

        /** The configured profile with that id, else the catalog's, else a RetroArch profile made for the core. */
        fun profileFor(player: CocoonPlayer): LaunchProfile? = configuredProfiles[player.profileId]
            ?: presetProfiles[player.profileId]
            ?: player.retroArchCore?.let { core ->
                try {
                    LibraryPresets.retroArch(core, core, player.packageName ?: RETROARCH)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        fun newSystem(d: Draft): NewSystem {
            val rootDirs = d.rootKeys.map { listingByKey.getValue(it).dir }
            val names = (d.platforms + rootDirs.map { it.name } + d.id).filter { SAFE_NAME.matches(it) }.distinct()
            val media = names.map { File(cardRoot, "scraped-esde/downloaded_media/$it") }.firstOrNull(isDirectory)
                ?: File(sortieDir, "media/${d.id}")
            val gamelist = names.map { File(cardRoot, "scraped-esde/gamelists/$it/gamelist.xml") }.firstOrNull(exists)
            val player = d.player
            val preset = d.preset
            val (system, profiles) = if (preset != null) {
                // Cocoon's emulator goes first when the preset lacks it; otherwise the choice alone makes it the default
                val extra = player?.takeIf { it.id !in preset.system.profiles }
                val ids = listOfNotNull(extra?.id) + preset.system.profiles
                preset.system.copy(id = d.id, label = d.label, profiles = ids) to (listOfNotNull(extra) + preset.profiles)
            } else {
                // a profile is required; without Cocoon's emulator the system opens games with Android's chooser
                val ids = listOf(player?.id ?: LibraryPresets.openWith.id)
                val custom = SystemDef(
                    id = d.id,
                    label = d.label,
                    generation = "other",
                    roots = emptyList(),
                    extensions = d.extensions.toList(),
                    profiles = ids
                )
                custom to (listOfNotNull(player) + LibraryPresets.openWith)
            }
            return NewSystem(
                system = system.copy(roots = rootDirs.map { rel(it) }, media = rel(media), gamelist = gamelist?.let { rel(it) }),
                // a configured profile with the same id is the user's (maybe edited) copy: never overwrite it
                profiles = profiles.distinctBy { it.id }.filter { it.id !in configuredProfiles },
                fromPreset = preset != null
            )
        }

        /**
         * Copies for matched games whose system has no art of that kind yet. Cocoon art in ES-DE's tree is already where a
         * system's media points and is skipped; references to a volume that is not mounted or a file that is gone count as
         * unavailable. Existing systems without a media folder get one (only when something is copied for them).
         */
        fun planArtwork(matches: List<CocoonMatch>, newSystems: List<NewSystem>): Triple<List<ArtworkCopy>, Int, Map<String, String>> {
            val mediaOf = HashMap<String, File>()
            newSystems.forEach { s -> s.system.media?.let { mediaOf[s.system.id] = Indexer.resolve(cardRoot, it) } }
            val added = LinkedHashMap<String, File>()
            fun mediaDir(systemId: String): File = mediaOf.getOrPut(systemId) {
                val existing = configuredById[systemId]
                existing?.media?.let { return@getOrPut Indexer.resolve(cardRoot, it) }
                val names = listOfNotNull(existing?.folder, systemId).filter { SAFE_NAME.matches(it) }.distinct()
                val dir = names.map { File(cardRoot, "scraped-esde/downloaded_media/$it") }.firstOrNull(isDirectory)
                    ?: File(sortieDir, "media/$systemId")
                dir.also { added[systemId] = it }
            }
            val stems = HashMap<String, Set<String>>()
            fun stemsIn(dir: File): Set<String> = stems.getOrPut(dir.path) {
                list(dir).orEmpty().filterNot { it.startsWith(".") }.mapTo(HashSet()) { it.substringBeforeLast('.') }
            }

            val copies = ArrayList<ArtworkCopy>()
            val targets = HashSet<String>()
            val needsMedia = HashSet<String>()
            var unavailable = 0
            for (m in matches.distinctBy { it.gameId }) {
                val stem = m.file.name.substringBeforeLast('.')
                for ((kind, ref) in listOf(COVERS to m.game.boxArt, MARQUEES to m.game.logo, SCREENSHOTS to m.game.screenshot)) {
                    val source = ref?.let(CocoonPaths::fromDocumentUri) ?: continue
                    if (!exists(source)) {
                        unavailable++
                        continue
                    }
                    val ext = source.extension
                    if (ext.isEmpty() || existingMedia(m.systemId, kind, stem)) continue
                    val kindDir = File(mediaDir(m.systemId), kind)
                    if (stem in stemsIn(kindDir) || sameDir(source.parentFile, kindDir)) continue
                    val target = File(kindDir, "$stem.$ext")
                    if (!targets.add(target.path)) continue
                    copies += ArtworkCopy(m.gameId, kind, source, target, length(source))
                    needsMedia += m.systemId
                }
            }
            val mediaDirs = added.filterKeys { it in needsMedia }.mapValues { rel(it.value) }
            return Triple(copies, unavailable, mediaDirs)
        }

        /** A backup reference → Sortie id: `app:<pkg>` for apps, the matched game id for games; null otherwise. */
        fun resolve(ref: CocoonRef): String? = when {
            ref.platform == ANDROID_APP -> ref.packageName?.let { LibraryItem.APP_PREFIX + it }
            ref.platform in NON_ROM || ref.file == null -> null
            else -> (match(ref.platform, ref.file) as? Outcome.Matched)?.gameId
        }

        fun planOverrides(backup: CocoonBackup, known: Map<String, LaunchProfile>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var unknown = 0
            for ((ref, raw) in backup.playerOverrides) {
                val gameId = resolve(ref)?.takeIf { LibraryItem.isGameId(it) } ?: continue
                val profileId = CocoonAliases.player(raw)?.profileId
                if (profileId == null || profileId !in known) {
                    unknown++
                    continue
                }
                out[gameId] = profileId
            }
            if (unknown > 0) notes += "${count(unknown, "per-game emulator choice")} use an emulator Sortie has no profile for and are not imported."
            return out
        }

        fun planSessions(backup: CocoonBackup, known: Map<String, LaunchProfile>, newSystems: List<NewSystem>): List<SessionPlan> {
            val systemProfiles = config.systems.associate { it.id to it.profiles } + newSystems.associate { it.system.id to it.system.profiles }
            val seen = HashSet<Pair<String, Long>>()
            val out = ArrayList<SessionPlan>()
            for (s in backup.sessions) {
                val gameId = resolve(s.ref) ?: continue
                if (!seen.add(gameId to s.startedAt)) continue
                val profileId = if (LibraryItem.isGameId(gameId)) {
                    s.emulatorPackage?.let { pkg ->
                        val preferred = systemProfiles[gameId.substringBefore('/')].orEmpty().mapNotNull { known[it] }
                        (preferred + known.values).firstOrNull { it.`package` == pkg }?.id
                    }
                } else {
                    null
                }
                out += SessionPlan(gameId, profileId, s.startedAt, s.endedAt)
            }
            return out
        }

        fun planFolders(backup: CocoonBackup): List<FolderPlan> = backup.folders.mapNotNull { f ->
            val ids = f.members.mapNotNull { resolve(it) }.distinct()
            if (ids.isEmpty()) {
                if (f.members.isNotEmpty()) notes += "Folder \"${f.name}\": none of its ${count(f.members.size, "item")} is in Sortie, so it is not created."
                return@mapNotNull null
            }
            FolderPlan(FolderDef(id = "cocoon-${f.id}", label = f.name, include = ids), unresolved = f.members.size - ids.size)
        }

        fun lowered(key: String): Map<String, List<String>> = lowerIndex.getOrPut(key) {
            listingByKey[key]?.files.orEmpty().groupBy { it.lowercase(Locale.ROOT) }
        }

        fun sameDir(a: File?, b: File): Boolean = a != null && canonicalPath(a) == canonicalPath(b)

        fun canonicalPath(f: File): String = canonicalPaths.getOrPut(f.path) { CocoonPaths.canonical(f).path }

        fun key(f: File): String = canonicalPath(f)

        /** Card-relative when under [cardRoot] (as Library setup writes paths), absolute otherwise. */
        fun rel(f: File): String {
            val path = canonicalPath(f)
            val root = canonicalPath(cardRoot).let { if (it.endsWith("/")) it else "$it/" }
            return if (path.startsWith(root) && path.length > root.length) path.substring(root.length) else path
        }
    }

    private companion object {
        const val POD = "pod"
        const val SHORTCUT = "shortcut"
        const val ANDROID_APP = "android_app"
        const val RETROARCH = "com.retroarch"
        const val COVERS = "covers"
        const val MARQUEES = "marquees"
        const val SCREENSHOTS = "screenshots"

        /** Not ROM platforms: Cocoon's own screens and DroidDeck pins (handled as advice). */
        val NON_ROM = setOf(POD, SHORTCUT)

        /** LibrarySetupStore's rule for new ids. */
        val NEW_ID = Regex("[a-z0-9][a-z0-9._-]*")

        /** A single folder name safe to join into a path. */
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ -]*")

        fun extensionOf(name: String): String? = name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let { ".$it" }

        fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
    }
}
