# Sortie: specification (living document)

Current implementation reference, reviewed against main on 26 October 2026. Milestone labels record the original grouping,
not unfinished release gates. Performance targets are goals; dated acceptance paragraphs describe only their named builds.

## Purpose

Sortie browses and launches a handheld's game library. The library can be set up entirely on the device (Library setup), or
curated on a desktop by the user's own tooling, which then owns naming, metadata, artwork, deduplication, backups and taste
scoring. Sortie reads those files, maintains an app-private index and play history, and writes configuration and personal
edits as portable files. The play-log file is a best-effort mirror, not the authoritative history or a complete backup.

## Principles

1. **Fast over pretty.** Cold start under two seconds with 8,000 entries is a target, not a measured guarantee.
   Browse the cached index; index an empty library, after manifest changes, setup/import or an explicit re-index.
   Incremental updates compare path, size, modification time and metadata stamps. Lists and grids suit different windows.
2. **Portable configuration and personal data.** Library configuration and user edits live as plain files under one
   root. The index, authoritative play history, reading positions, daily picks and companion state are app-private;
   backups carry preferences, completed history and the companion, not every app-private cache.
3. **No gatekeeping of launchers.** Launch profiles are editable user data. Library setup ships presets, not a closed
   compatibility list; custom profiles can target other installed apps. An explicitly configured "Open with…" profile
   delegates to Android's chooser, but the chosen app must support the file and intent.
4. **Controller first, touch second.** One consistent button grammar on every screen.
5. **Offline by construction.** The manifest declares no network permission.
6. **Release-ready hygiene.** Idiomatic Kotlin, ktlint, MIT, no secrets or personal device identifiers in code.
   Device-specific configuration lives in manifests; reusable emulator presets live in Library setup.

## Data model

Library inputs (ROMs and artwork are read-only; setup can edit configuration):

- `roms/<system>/` and similar folders: immediate game files under each `systems.json` root, not recursive scans.
  Zero-byte files are indexed as non-playable placeholders and hidden by the default on-device filter. Syncthing temp
  files mark their target names as "arriving".
- `scraped-esde/gamelists/<system>/gamelist.xml` : ES-DE gamelist (name, desc, rating, releasedate,
  developer, publisher, genre, players). Only `<path>`, `<name>` and the listed fields are used.
- `scraped-esde/downloaded_media/<system>/{covers,screenshots,marquees,...}/<stem>.png|jpg`.
- `sortie/guide.json` : optional taste layer from a desktop guide builder (fit 1-5, hook, flags,
  tags) keyed by system + filename. It takes precedence over the bundled curated guide.
- APK asset `game-blurbs.json` : the bundled public identity catalog (original hooks, flags and control notes; no fit or
  taste tags), keyed by immutable release UUIDs, matched by scoped payload fingerprints and exact curated aliases.
  An `auto: false` card entry wins as a whole, including intentional blanks; otherwise the bundle fills empty fields.
  Authored/auto is precedence, not human authorship. See [guide format](formats/guide.md) and `data/guide/CuratedGuide.kt`.
- `sortie/systems.json`, `sortie/launchers.json`, `sortie/folders.json`, `sortie/device.json` :
  see docs/formats.
- APK assets at `magazines/<issue-id>/` (only in builds made with a magazine content directory; see RELEASE.md) and
  optional card packs at `sortie/magazines/<issue-id>/`: offline zines with `issue.json`, Markdown and local images
  (format v2; v1 external packs are imported). See formats/README.md.
  Reading positions are app-private preferences, separate from card data and exported backups.

Outputs (owned by Sortie, append-only or small):

- `sortie/playlog.jsonl`: append-only start/end events mirrored from Room sessions (see formats); never read back as history.
- `sortie/user.json` : favourites, queue, bookmarks, controller requirements, Finished/Shelved, reference-card metadata,
  clippings, per-game overrides and layout/switcher/device-stats preferences (see formats/user.md).
- `sortie/archive/personal-notes-<hash>.{md,json}` : readable and structured copies of legacy notes and journal entries,
  saved before those fields are removed from `user.json` or an older backup is restored.
- App-private `files/tonight.json` : each local date's saved Tonight set (newest two dates; device-local, not on the card
  and not in backups; see Feature set → Tonight).

Entities: System, Game (a launchable file with metadata), App (an installed Android app treated as
a launchable), Folder (a system folder, a smart folder, or a manual folder), LaunchProfile.

## Feature set

### Core (M1)
- System folders, one per configured system, ordered and named by `systems.json`.
- Game list per folder with cover thumbnail, title, fit stars, players, year, hack mark (explicit evidence only:
  formats/README.md, Modified-game marks).
- Search across games with optional text; OR within systems/tags/genres, AND between dimensions. Tags include game-page flags.
  More filters: favourites, Want to play, never launched, fit ≥ 4, on-device-only (default). Filter before paging; show full count.
- Detail view: hero art, description, hook, tags, play time, last played, launch button, overrides.
- Launch through profiles with ordered fallbacks; per-game override; "open with" fallback.
- Favourites folder holding games and apps alike.
- Play log and "Continue" row.
- Quick exit: an emulator gone within 3 s of the start, with no Home press since, ends the session uncounted (no Room
  row; `playlog.jsonl` end has `quickExit: true`) and says so beside Play for 12 s (a toast off the page), naming a
  RetroArch core or game switch (`launch/QuickExit.kt`, LAUNCHERS.md). No automatic retry.
- Bookmarks: one text bookmark per game (`user.bookmarks`), edited on the game page (Save replaces, Cancel/B keeps,
  Clear → Remove bookmark deletes) and shown there and on the companion. They replace the former journal and personal mapping/"where was I?"
  notes; older writing is archived readable under `sortie/archive/` before migration (formats/user.md). After a game really
  ends (`launch/SessionEndWatch.kt`: its own task, identified through Shizuku after launch, absent from a trustworthy task
  list) the navigation offers a nonmodal invitation once, never on transient Home/switcher/companion visits, quick exits or
  without Shizuku; Settings → Bookmarks turns it off. The return check runs when the navigation is resumed and focused with
  no switcher overlay open (the non-focusable overlay's dismissal counts, with no polling); Not now or the 45 s timeout ends that
  session's invitation for good. Curated hooks and other guide text are never edited or overridden by bookmarks.
- Diagnostics: launch log with the exact intent and its adb equivalent; "why did this fail".

### Smart folders (M2)
- Rules in `folders.json`: regex over name/path/system; predicates over genre, players, year,
  tags, fit, play time, last played, favourite, flags; AND/OR/NOT; manual include/exclude lists.
- Live counts; "Pick one" (random) scoped to any folder.
- Built-in folders: Favourites, Want to play, Finished and Shelved. Other smart folders come from `folders.json`;
  Unplayed 4+, Recently added, Short sessions and Two-player are possible configurations, not shipped defaults.

### Dual screen (M3, revised in feedback round 2)
- Roles follow the navigation activity's current display, not a fixed top/bottom layout; X exchanges navigation and
  companion. `screens.presentationIndex` selects the secondary presentation display. The companion uses
  `CompanionActivity` on the default display and `SecondaryHomeActivity` (SECONDARY_HOME) on the selected second display,
  where Android can return to it after an emulator exits. Without a second display, navigation uses the single-screen layout.
  The manifest's `screens.navigation` affects the default game-launch target, not navigation's initial placement.
- Launch target display per profile (ActivityOptions.setLaunchDisplayId).
- Controller anchoring: the controller follows the game's display. Companion screens are touch only and hand the controller
  back to the other screen when they appear or are tapped (`AnchorActivity`).
- App switcher (Shizuku): Home shows each screen's running apps on both screens, including while reading or when no external
  app is running; apps can be opened, raised, moved between screens, swapped or closed. Companion-side carousel is touch,
  navigation-side is controller. Shizuku must authorize the current Sortie installation, not merely be running.
- Opening the overlay preserves the foreground Sortie surface when Home covers navigation or a companion; otherwise the
  external app is restored beneath it. A primary HOME instance created after X relays to existing navigation rather than
  replacing it, including after swapping back. B leaves that page/display intact.
- Only HOME/SECONDARY_HOME intents marked `android.intent.extra.FROM_HOME_KEY` summon the overlay (or Sortie's explicit
  switcher request); framework fallback launches do not. A temporary primary HOME focuses the existing own task with
  `ActivityManager.AppTask.moveToFront()` or starts its companion before finishing. If neither succeeds, it remains a usable
  Home instead of leaving an empty focused Home task that the framework would continually relaunch.
- If Android reports that a protected foreground window hides the switcher overlay, Sortie dismisses the overlay and
  opens the activity-based switcher; it does not bypass overlay protection. The ordinary switcher's Sortie action returns
  to Home. Single-screen devices show Running apps and omit cross-screen move/swap/controller-target actions.
- AYN panel guard (Thor): the vendor's Thor-button panel leaks one network callback per opening (system uid cap 250, shared
  with system_server). At the cap its next opening crashes inside `onAttachedToWindow`, its crash handler keeps the process,
  and the undrawn window keeps input focus on its display, so Sortie there gets "no focused window" ANRs. With Shizuku,
  when the shell connects and when Sortie returns (at most once a minute), `ScreenMover.guardVendorPanel` restarts it with
  `am crash` at 150 requests while no panel window has a surface, or after seeing its focused, surfaceless window twice.
  See docs/HARDENING.md.
- Device stats (Settings, off by default; companion, switcher overlay or both): the readings players opened the Thor-button
  panel for. Companion: a 52 dp strip down each side of the panel (left CPU/GPU load with clocks and RAM, right CPU, GPU
  and battery °C and the fan), wrapped around `CompanionScreen` by the companion host so the panel and its corner controls
  narrow instead of being covered. Overlay: one line beside the controller window's "Running" title. FPS and power draw
  are not shown. Sources: one `sh` per poll through the Shizuku shell (`DeviceStatsProbe`, builtins only, every read
  optional): `/proc/stat` deltas (apps may not read it), the fastest core's `scaling_cur_freq`, kgsl `gpu_busy_percentage`
  and `gpuclk`, the hottest `cpu*`/`gpu*` thermal zone, and the Thor fan tachometer `/sys/class/gpio5_pwm2/speed`
  (unlabelled; 0 when stopped, a few hundred to 3,900 seen; shown as rpm). RAM (ActivityManager) and the battery
  temperature (sticky battery broadcast) need no shell. `DeviceStatsMonitor` polls only while a surface collects it, and
  surfaces collect through `whileShown`: only while resumed (the lifecycle's state flow) on a lit display (display-change
  callbacks; the overlay window's owner stays resumed while the screen sleeps). The collecting coroutine switches on those
  events itself, never on recomposition: a stopped window or a dark screen draws no frames, so a gate read in composition
  would keep polling. Interval 2/5/10 s (default 5); the poll after the first comes 1 s later so CPU load shows at once.
- Perf view (`ui/PerfViews.kt`, `stats/PerfHistory.kt`): the same readings large, each with a two-minute sparkline (CPU
  load and clock, GPU load and clock, RAM, CPU/GPU/battery °C, fan), then every core's clock and every thermal zone grouped
  by type (`cpuss-0…3` → `cpuss`; single zones under Other; Qualcomm's `*bcl*`/`*ibat*`/`vbat`/`socd` limit and gauge
  zones and sensors reading -40 °C left out). A metric with no reading in the window gets no tile; FPS and power are not
  shown. Three places: (1) a page in each switcher overlay window: the "Perf" chip (touch, both windows) or L1/R1 (controller
  window, hint bar "R1 perf") open it; B, L1/R1 or the "Apps" chip return to the app list, and the card that last had the
  focus takes it again (`SwitcherPages`: per-display page and last focused card, reset on every summons; the overlay's B
  goes back a page before it closes the switcher). The controller window's page holds the focus and scrolls with the
  D-pad. (2) Dual-screen devices: Settings → Companion screen shows Companion/Perf (`user.companionView`); Perf replaces
  the hero/now-playing panel inside `CompanionScreen`'s corner layout (battery, bell, dock, '+' stay; the switcher,
  launcher, dock picker and magazine contents still take the panel while open) and drops the side strips.
  (3) Apps → Performance: an independent `PerformanceActivity` on either display, sharing the page and visibility-gated
  sampling, with D-pad scrolling and B/Close to close only that task.
  Polling: `DeviceStatsMonitor.perfWhileShown` has the same lifecycle and display gate as `whileShown` and while
  collected counts as a Perf view: polls every `user.perfInterval` (1 or 2 s, default 2) with the detail probe
  (`DeviceStatsProbe.DETAIL_SCRIPT`, every zone: 0.12 s of system time on the Thor against 0.10 s for the plain probe); a
  Perf view opening during a slow wait ends it at once. Every poll goes into a 128-entry ring (`StatsHistory`, one float per
  metric) owned by the polling coroutine, so it exists only while some stats surface is shown and starts empty after; Perf
  polls carry an immutable 120 s snapshot. Sparkline ranges: loads 0–100 %, RAM 0–total, clocks and fan 0–highest seen (fan
  at least 1,000 rpm), temperatures the values seen ± 2 °C and at least 10 °C tall.

### Home and browse layout (feedback round 3)
- Home sorts: Alphabetical, Game count and Generation are generated, fixed and systems-only. Custom retains the user's pin
  order and automatically includes every smart folder, Apps and Continue (the last game played, first unless moved).
  By Console shows a console strip and its games. Sort chips select the view.
- "Add to home" / "Remove from home" on systems, games, apps and shortcuts (hold A or long press). Smart folders, Apps and
  Continue always live on Custom and cannot be removed. Every Custom tile supports Move: the lifted tile has a blue glow,
  the D-pad moves it one slot per press, A drops, B cancels; touch drops it on the tapped tile.
- Browse screens (system lists, folders, search, Apps) share one list↔grid setting. Grid tiles are 1:1: cropped cover with
  the title over a bottom gradient, ★ when favourited, focus glow in the cover's palette accent; apps and shortcuts show their
  icon on slate with the label below. Columns = `gridColumns`.
- Header and hints (`user.browseChrome`, `BrowseChromeMode`: Auto/Expanded/Compact, Settings cycles, unknown reads Auto)
  apply to Home and every browse screen alike. Auto decides from the window's actual constraints (compact under 600 dp
  height or width, divided by a font scale above 1), never from whether the device is dual-screen. Compact folds the
  header to a strip when focus enters the grid or a drag scrolls it; tap, pull down, the ▾ chevron or Up from the first
  row reveals it (▴ folds it), it stays open with nothing to browse, and the folded state is saved per destination.
  Compact hints are one light row: up to three of A/B/Y/Select by width, then a Controls dialog listing every command.
  The hint bar stays in the measured layout, never over the last row of tiles.
- Smaller Home windows keep header actions in a horizontally scrolling rail; the scroll content includes space for the
  focus glow. Wide windows retain wrapping actions. Settings choices accept direct taps while A still cycles the row.
- Focus feedback is a finite flare and spring settling to a steady glow, with a held-confirm press state cleared on
  key release, focus loss or window-focus loss. Navigation, image zoom and ambient palette changes are finite transitions;
  hidden ambient changes snap without animating. Home's clock and the companion's elapsed timer pause while hidden.
- Game detail groups primary and personal (bookmark, What it needs, reference cards, shelf) actions separately, scales the cover to the available width, and expands
  launch choices beside the actions. Select toggles that panel; closing from its list restores the opener's focus.
  Artwork-derived accents meet 4.5:1 contrast against both the backdrop and the selected shell canvas.
- Launcher shortcuts are opt-in: Apps lists only `user.shortcuts` (pinned to Sortie or added with "Add shortcut from…" are
  opted in automatically; "Browse all shortcuts…" toggles the rest). Smart folders see a shortcut only if it is opted in,
  favourited or placed on home.
- Home's header: the Split-S mark (`R.drawable.sortie_mark`, content description "Sortie", not a control) instead of a
  heading, the clock and count, then Apps, Search, Extras and Settings. Extras is a context sheet: Tonight, Hangar (when
  a mecha stage hosts Home) and Magazines; A opens, B/Cancel closes and returns focus to Extras, as does the Hangar's
  closing. Library setup and the manual live in Settings; the empty library keeps its Set up/Manage library button.
- Built-in apps: Magazines and Performance have separate non-exported, singleTask activities and distinct task affinities.
  Apps and screen-targeted pickers offer both; Home's Extras → Magazines and game-detail Featured in use the same reader host.
  Two-screen entry asks here/other; single-screen entry opens directly. Reopen resumes one instance; X moves only that
  built-in with saved state. The switcher includes these two named tasks but still excludes Sortie's HOME/companion/anchors.
  Every activity owns its hotkey dispatcher, so visible windows never consume each other's controller events.
  A single-screen built-in consumes X without moving or activating the focused item.
  Built-in resume neither starts a companion nor runs navigation's play-session-return handling. Game launches from the
  reader use that window for the "same" display rule and do not run navigation's after-launch hook.
- Offline magazines: shelf → contents → article, B one level back; article D-pad scroll, Left/Right page, L1/R1 previous/next
  article, A game references/library pages (never launch). Saved scroll positions and Continue reading survive reopening;
  X retains the current article. Issue contents list all mentioned games, with an On this device filter. Game detail's
  Featured in opens exact articles and B returns to the game. References distinguish platforms/editions from local filenames;
  several matches remain choices, while placeholders, missing and arriving files are not presented as available.
  APK packs extract atomically into private storage once per catalog digest. The shelf merges by stable issue ID: higher
  revision wins, card wins ties; no card pack is overwritten. A malformed card copy cannot hide a valid bundled issue.
  Bad issues are isolated, missing optional images do not block text, and paths cannot escape an issue. No WebView or remote fetching.
  Shelf entries and the contents masthead show the issue number large ("No. 010": the pack's `number`, else the id's trailing
  digits, else none); the shelf is newest date first, then number, then id.
  Figures are sized from their pixel dimensions (read before the article is shown, so placeholders already have the final
  size and restored positions never shift): text-measure width, full reading width for wide pictures, at most 70% of the
  viewport tall (lead image 55%), whole-number enlargement and no smoothing for small pixel art, caption at the picture's
  width. A tap, or R2 on the picture most in view (hinted), opens a full-screen viewer over the unmoved article: pinch,
  double tap, A and L2/R2 zoom, D-pad pans when zoomed, Left/Right and L1/R1 step pictures, Y toggles the caption, B/Back/tap
  closes. It is a dialog of the reader's window, so it opens on the reader's display; the companion shows no pictures.
  Picture sequences (experimental; syntax in formats/README.md): 2–12 captioned panels in one horizontal row on a view at
  least 560 dp wide (chosen panel centred, up to 55% of the view's height, every panel measured up front so the article
  never moves), stacked as ordinary figures when narrower. Touch swipes settle on a panel; a tap chooses and opens it.
  L2 browses the sequence most in view: ←/→ choose a panel instead of paging, R2 opens it, ↑/↓ still scroll, L2/B or
  scrolling it out of view stop. The chosen panel is saved with the reading position; browsing is never restored.
  While an issue is open, the other display shows section jumps/current heading, article selection and previous/next controls.
  The panel follows navigation across X; session-owned actions reject stale taps, and leaving the issue clears the panel.
  Its bottom-left + opens an app picker for that display; launching returns controller focus to the reader. B or Close
  cancels without losing the contents tab or list position. This also works with the reader on the bottom display.

  Historical Thor acceptance (before the current Home header): opened Prime Video below the article and Clock above it
  after X; the article remained on its own display. Picker cancellation preserved the Articles tab and scrolled Sections
  list pixel-for-pixel. Home opened the
  overlay from both reader placements, and B returned to the article; denying overlay permission used the ordinary
  switcher page instead. Video playback/DRM was not exercised. Shizuku authorization had to be renewed after an earlier
  uninstall/reinstall: Android's package-permission listing alone did not establish Shizuku authorization.

### Tonight

- One saved set per local date of up to five distinct games, shown one at a time (`ui/TonightScreen.kt`): a gameplay
  screenshot (the cover when there is none), title, curated hook (`GameEntity.hook`), the rule's reason, and Play, Details
  and — for a Field Notes pick — Read article, which opens that exact article. Chips along the top or L1/R1 move between
  picks; B leaves. No grid, no setup, no questions, no reroll, no network, no timer.
- Roles and rules (`data/tonight/TonightPicker.kt`), filled resume → Field Notes → familiar → different, then fallbacks,
  then random: **Pick up again** — bookmarked games first, then the most recently played; **From Field Notes** — an
  article reference that resolves (through `MagazineGames.resolve`, the article library lookup) to exactly one available
  file, unread articles first; **Familiar ground** — unplayed, same developer as (else same genre as) one of the five
  most-played games; **Something different** — unplayed, on a system and in a genre the history has never touched (else
  one of the two); **Wild card** — a random draw. Ties follow an order seeded by the date. Reasons state the rule and the
  data it used (dates, play time, bookmark, article), never a guess such as session length.
- A role with nothing to work with (no history yet, no playable Field Notes game) becomes a labelled **Discovery** pick
  saying why: Want to play first, then unplayed games with an authored guide hook, then the best-rated unplayed game, then
  any unplayed game. Never the same id or cleaned title twice; hidden, Finished/Shelved, alternate indexed variants,
  placeholder, arriving and missing files are left out at selection, so a small library gets fewer picks. Availability
  checks establish file presence/readability, not emulator readiness.
- `data/tonight/TonightStore.kt`: the first visit of a date chooses and saves the set (picks, reasons, article) to the
  app-private `tonight.json` (fsynced, replaced by rename) before showing it; later visits, restarts, re-indexes and new
  history reuse it. If that write fails the screen reports it and shows no set (retry by reopening); an unpersisted
  selection is never presented as the day's set. An unreadable `tonight.json` is discarded, so that day's set is chosen
  again. While indexing runs nothing is chosen. The visit date lives in a navigation-scoped view model, so Details and
  back keep it across midnight; a cold process start uses the current date and never restores yesterday's. The newest
  two dates are kept. A saved pick that can no longer be played (left the index, hidden since, not copied, arriving,
  missing) stays in place, disabled, saying why. An empty result (nothing playable yet, e.g. before the first index) is
  not saved, so a later visit that day can choose.
- Layout: Play/Details/Read article stay outside the scrolling text; the text area is D-pad focusable when it overflows
  and resets per pick; the hook is a three-line excerpt (full text on the game page).
- Navigation: `TonightScreen(vm, onGame, onArticle)` — `onGame` opens the game page, `onArticle(issueId, articleId)`
  the magazine reader at that article; the visit's pick and focused control survive both round trips.

### Hangar companion (experimental)

- Lives above Home and the other display's idle panel without changing their layout. `MechaStageHost` (in `:mecha`)
  draws one foreground layer after its content. Normal poses are clipped away from declared solid content and controls,
  including a prop's temporary drawing offset; peek edges and the window bounds also clip the art. Carrying, thrown
  flight and sitting intentionally allow overlap. Screens declare geometry with `Modifier.mechaSpot(id, kind)` / `mechaObstacle(id)`.
  ROOM, FOOTER and BASE are empty-space anchors, not solid obstacles.
  Spot kinds: LEDGE (on an element's top edge), PEEK (head and shoulders above an element), BESIDE_END/START (outside its
  bottom edge), ROOM (inside empty space), EDGE_START/END (partly off screen), FOOTER (inside a bottom-aligned bar, with
  legs clipped at the window edge if necessary), BASE (reserved empty space). Every resting place must leave at least
  40 dp × 40 dp exposed and clear of controls; motion reserves two extra sprite pixels above and beside the art.
  Scale is the smallest whole multiple of the 64 px frame at least 9 mm tall from real dpi (`ydpi`, or the density bucket
  when it reports nonsense): 2× on the Thor, DMG and Nova; one step smaller only if fewer than two spots qualify.
- Home reserves no companion space. Its natural-height, full-width hint bar has a lighter transparent-to-ink gradient;
  compact hints sit together on the left, Controls on the right, with any safe gap available to Rivet. Each bar owns its
  obstacle IDs across navigation transitions. A short expanded bar may not provide a tappable spot: if neither it nor
  another anchor fits, he hides rather than enlarging the bar or shrinking his tap target. The other display retains its
  base under the bell (`mechaBaseSize`, `Modifier.mechaHomeBase`), available as an ordinary destination, not only a fallback.
- Home spots: after the clock/game count, on the header actions when they wrap below the title, after the sort chips,
  over every laid-out tile (in practice the first visible row: elsewhere the head would be inside another tile), on the
  hint bar's free stretches. Section-title obstacles cover their text, not the blank row beside it. Other display: after
  the battery, before the bell, on and after **Want to play**, over the hero picture (sized to its real aspect ratio), before the **+**, both edges just above the
  bottom. Text, the dock and the detail block are obstacles.
- One mech per process (`MechaPresence`): he is on one display; the latest shown, allowed stage there draws him. He
  stays on that display while its window is paused, adopts the latest active window when his display has none left, and
  changes display only by walking off one screen's edge into the other's (one stroll in three after two strolls on a
  screen, when the other screen is up and has room; or at once when nothing fits here and the other has room), or by
  being carried there. Single-screen devices keep him on Home. The other display's panel stays active while a game runs on
  the first: he lives there during play (players can hide him).
- Shown means resumed on a lit display: `mechaShown` combines the lifecycle's state flow with the display listener
  (`displayLitFlow` in `:design`, shared with device stats), never composition or the frame clock, and the director
  (`whileShown`) exists only while its stage owns the mech. Stopped, backgrounded behind a game, screen off, hidden
  companion: no coroutine, no timer, no frame of his.
- Idle life (`MechaIdle`) uses varied breath timing, occasional blinks and tiny weight shifts or glances. A settled focus
  change can draw his attention after 600 ms, at most once every five seconds; he never requests focus or chases held
  keys. Quiet surfaces use slower idle beats and suppress strolls and vignettes. Only changed poses schedule redraws.
- Movement: a stroll becomes due every 45–90 s, but waits for eight seconds without input and two seconds of settled
  layout. The bounded route search prefers connected floors and nearby steps, checking the swept art, headroom, floor
  support and the exact hop arc against current geometry. A wide anchor can offer multiple destinations. Unreachable
  places remain occasional sink-and-rise visits; there is no walking through tiles or hopping through controls.
  `MechaMotion` supplies looking, leaning, distance-matched walking, crouch, push-off, flight, compression and recovery
  beats. Moving beats interpolate whole-pixel positions on the display clock, with consecutive beats sharing a timeline;
  stationary poses use timers. Hops draw the same analytic arc that collision checking validates.
  Changed geometry stops travel immediately; recovery revalidates the nearest supported position or hides until layout
  settles. Turning motion off in flight lands only at a still-valid destination. Motion off otherwise selects a
  deterministic qualifying spot with no idle animation, vignettes or hops.
- Vignettes (`MechaPlay.vignettes`): one due stroll in three is a short vignette instead, when one fits: a nap where he
  stands (8–12 breaths, a rising Z), a sit on a LEDGE or PEEK anchor's top edge (hips on the edge, drawn over the
  interface with the legs dangling over that anchor's face only, kicking, 7–11 s), a poke of a `MechaProp.POKE` element from
  its BESIDE spot (three jabs, the element jiggles a sprite pixel away), a lift of a `MechaProp.LIFT` element (he stands
  under it with his raised hands at its bottom edge, squats, pushes it up to 3 sprite pixels and strains, about 2.5 s).
  Props are host-declared (`mechaSpot`/`mechaObstacle` `props`): the other screen's battery (poke, lift) and Home's
  clock/game count (poke). A control is never poked or lifted whatever it is marked, nor a focused element
  (`FocusEventModifierNode` on the spot node) or one a finger is on; nothing is sat on while focused. Any key or touch ends
  a vignette: the nudged element is back in place inside the input handler (`MechaStage.poke`), before the input is
  handled, and he returns to a spot.
- Poses are per-part transforms (`MechaRig`: whole-sprite-pixel offsets of the legs, body, arms and head layers over the
  authored frames, nearest-neighbour): breathing, glance, pet bounce, dangle (arms-up frame, legs stretched and swinging),
  strain (arms-up, hunched, wobbling), sit (legs tucked and kicking), poke (arms and head reaching). No new frames.
- Reactions: the first look after a play session closes (any session, games or apps) greets the player with a cheer and
  "Welcome back!" plus the store's credit message; other store messages (care, salvage, rename, equipment, restore) show in
  a 5 s bubble after the Hangar closes. A bubble only appears beside him on empty space; he moves to a spot with room or
  skips it. Three minutes without key or touch input: he dozes (sleep frame, slow breath) until the next input or a pet.
  Resting shows the sleep frame and never strolls; motion off places him still at a spot; hidden: nothing roams.
- Cost: positions and rigs are snapshot state read only while drawing the foreground layer; clipping uses only intersecting
  declared boundaries, without per-frame paths or bitmaps. A lifted or poked element redraws itself. Idle life uses sparse
  changed-pose timers; frame-clock work exists only during short movements. Not shown: no companion work.
- Touch (`MechaGesture`, watched at the host in the Initial pass on a finger that went down on his tap target): released
  before 500 ms without moving past touch slop = **pet** (bounce and heart; wakes a doze; `MechaStore.pet` adds 1 bond at most
  once per 5 minutes, the window's start kept in `files/mecha/pet.txt` and written before the bond so a crash can only lose
  a point; repeated pets inside the window write nothing); still for 500 ms = picked
  up (haptic, arms up), released still = **Hangar**; a move after the hold = **carry** (drawn over the interface,
  dangling from the grip point, following the finger), released = **drop** to the nearest qualifying place with a little
  hop, or, on a stacked dual-screen device, across to the other display when released within 48 dp of the edge facing it
  (the default display is the upper screen: its bottom edge, every other display's top edge) and that screen is up with
  room; he drops in there through the facing edge at the same fraction of the width. A move before the hold is a swipe and
  is never consumed, so a scroll that starts on him still scrolls; after the hold every change of that finger is consumed.
  The tap target is the exposed art widened to the frame where that touches no control (at least 40 dp each way, which
  every place he stands guarantees). **Extras → Hangar** on Home stays the controller route (focus returns to Extras on close).
  The Hangar holds Animate and Hide/Show companion. Reader Select → Visit the hangar keeps its controller access. The
  Hangar dialog stays mounted while its rename field uses the keyboard.
- A carried release also estimates velocity from a bounded, preallocated recent-sample buffer, including Android's
  coalesced pointer history. Pauses, insufficient samples and non-carry gestures yield no throw; cancelled carries stay
  local. `MechaThrows` accepts a deliberate flick toward the other display only when it can clear the facing edge within
  450 ms without leaving through a side. Outgoing travel is gravity-affected; entry is a guided arc to a freshly selected
  qualifying perch, followed by landing compression. Coordinates and velocity are normalized across display sizes and
  densities. Flight samples are whole pixels on display frames, not a persistent physics simulation.
  There is one owner throughout. The destination must still be active, current and have room at handoff; its one-shot
  arrival is discarded if that window disappears or is replaced. Layout changes, new input, hidden windows and motion-off
  interrupt flight; a landing is revalidated before it gains a hit target. Animate off permits an immediate handoff.
  No new activity, overlay permission or input interception outside Sortie's own windows is involved. The companion's
  existing controller handback waits until the carry/drop finishes on either display: its transient `AnchorActivity`
  must not pause navigation midway through an incoming landing. Hiding or replacing the owning window releases that hold.
- Historical roaming acceptance (`0.1.0-mecha-roam` debug on the DMG, `0.1.0-beta.1044-roam.1` on the Thor;
  the tap-to-Hangar interaction below predates today's pet/hold gestures): DMG Home at the clock,
  on the wrapped header buttons, on the hint bar (list view) and peeking over first-row tiles (grid view); peek-to-peek
  strolls; 0 frames in 10 s windows between strolls, while dozing (only the clock's minute tick) and with motion off; a
  real 70 s game session greeted with "Welcome back!" plus the credit message; Home Hangar button by controller with focus
  returned on B; tap on him opens the Hangar, a tap beside him reaches the row, a drag starting on him scrolls the list;
  hide removes him, show brings him back; name/parts/salvage unchanged. Thor: Mica at scale 2 beside the sort chips and
  over tiles on top; leaving Home sent him to the bottom panel (battery, Want to play, +); two X swaps showed one mech and
  no crash. The bottom panel's rotating hero still redraws on its own timer (not the mech).
- One named companion, cosmetic Bond/Charge/Condition, tune/charge/rest interactions, motion toggle and hide/show.
  No time-away decay, death, streaks, notifications, network calls or requirement to care before playing.
- Twenty-five authored pixel-art families, each with two complete head/body/arms/legs sets: 50 sets and 200 base parts.
  The original seven families retain their base PNG bytes. Every set uses the same attachment points, draw order and
  eight 64×64 poses in a transparent 512×64 atlas; mixed parts are unrestricted, not procedurally constrained.
  Nearest-neighbour integer scaling keeps hard pixels. `tools/mecha/generate.py` regenerates the production catalog;
  `tools/mecha/test_production.py` checks packaging, semantic masks and reproduction from the authoring sources.
- Each atlas has a sibling `.paint.png` semantic mask with identical dimensions and alpha. Red encodes fixed/primary/secondary,
  green the four lighting tones, and blue Original-finish eligibility. Explicit author-time pigment assignments and detail
  exceptions keep eyes, glass, lights, outlines and mechanisms fixed. Old-family Original eligibility preserves the historical
  coating exactly without runtime colour or face-window guesses.
- Twelve original starter parts and three starter credits. Direct parts cost two credits. Ten two-tone schemes plus Original
  each have independent Standard/Alloy/Holo editions: 2/6/10 credits, except free Original Standard. Editions are reusable
  on any owned part. Coloured Alloy retains pigment with a travelling glint; Holo adds prismatic bands. Original editions
  retain the prior look. Eight material phases share the visible sprite clock; motion off selects phase zero.
  Decode/transform work runs off-main behind a mutex with a 12 MiB LRU keyed by asset and paint, never catalog-wide preload.
- Shop only purchases Parts, Paint and Launcher styles. Workshop applies them: Bot has Assembly/Paint/Care; Launcher has
  Themes/Folders/Buttons. Assembly Parts and Family sets share one draft and Fit action, with family filtering.
  Paint supports a selected slot or all four atomically, live selected-only preview and explicit Apply/Cancel.
  Browsing and cancellation never write; Back cancels a local preview before leaving its page. Rename remains explicit.
- Slot machine stakes 1/3/5 exchange credits only, independently of inventory. All stakes have 5% jackpot (5× returned),
  20% win (2×), 20% stake back (1×), and 55% miss (0×): 85% average return. Entry requires enough funds and room for
  the full jackpot under 50; balances above 46/38/30 respectively cannot take that stake. No payout clipping.
  A queued atomic transaction saves debit, payout, sequence and receipt before its brief reel animation;
  stale confirmations cannot spend twice. Reopened receipts are static and cannot replay a payout.
  Old item-prize receipts remain historical records, not new credit wins. Motion off skips the reel flourish.
- Arcade uses a shared CRT for Flappy Flight, Scrap Catch and Circuit Match. One credit inserts a durable, initially unbound
  coin; choosing a game binds it without another debit. One coin buys one run, with scores/bests only and no economy or
  companion-stat rewards. Cancellation before purchase is free; abandoning a purchased run does not refund it.
  A process-shared session has one active host. Hiding, changing tabs or losing focus pauses it and clears held input;
  returning requires Resume. Restarting the process restarts the same paid seed free. Completed scores are saved atomically
  with ticket consumption, with retry on write failure. No frame loop runs for a hidden game.
- Launcher customization uses free isolated previews and independent ownership/application. Harbor, Plum and Lichen themes
  cost eight credits; Drawers, Cartridges, Pixel and Industrial folder packs cost four; Instrument and Arcade buttons cost six.
  Original remains free. All four hosts observe committed appearance even with Rivet hidden; style changes add no idle loops.
  Folder artwork takes precedence over pack illustrations. Button treatments preserve hit targets, enabled semantics and focus.
  Reader paper and artwork-derived accents retain their own colours.
- First-open Hangar help is three skippable cards covering the optional companion, earning/spending, motion and hiding.
  Skip, completion or Hide saves acknowledgement; Help replays it. Hiding preserves inventory, credits and theme.
  Landscape pages place the preview or CRT beside controls, with a single assembly editor rather than a separate bench.
- Room's completed, non-abandoned game sessions after initialization earn one credit per five accumulated whole minutes,
  capped at 120 counted minutes per session and a 50-credit earning ceiling. Overflow and partial credit are discarded at
  the ceiling, but sessions are still consumed and Bond/Charge statistics advance. Spending below 50 permits only new
  play to earn again. Existing balances above 50 are retained; no extra earning until they fall below the ceiling.
  App/shortcut sessions and prior history do not count. Game ID plus start time prevents double credit after reopening
  or re-importing history. This measures recorded session duration, not active input. Charge drain uses accumulated-minute
  boundaries, independent of how Room batches session notifications.
- State, inventory and credited-session identities are atomically saved together in app-private `files/mecha/state.json`.
  Versions 1–3 migrate to version 4 before publication, preserving parts, equipment, wallet, themes, paid arcade state and
  credited-session identities. Global finish entitlements become Original paint editions; equipped Original finishes retain
  their appearance. Historical item receipts move to a separately typed record. Version-1 at-cap pending minutes are cleared.
  Failed writes leave the prior state intact; failed migration offers nondestructive retry and blocks backup/restore until resolved.
  Unreadable saves require explicit recovery and are preserved before starting fresh. The companion never writes Cocoon,
  library manifests, user preferences or the play log.
- Limits: a finite authored collection, not arbitrary generated artwork or statistical part affixes; no combat or
  test-course simulation. Preserve app data; do not uninstall to change builds.
- Backup (format version 2, docs/formats/user.md): export includes the whole save; restore validates it against the parts
  catalog in the preview, writes it before the history is merged and puts it back if a later step fails. The restored save
  keeps every session either side already credited (`restoredOver`); a backup without a save keeps the device's companion
  and marks the merged sessions credited (`creditedElsewhere`). No session earns salvage twice. The nested companion save
  is version 4, including paint editions, launcher icon/button ownership and selection, and credit-only slot receipts.
  Versions 1–3 remain readable; older app builds cannot read the new save. Do not downgrade after opening the updated Hangar.
- DMG acceptance for `0.1.0-mecha-lab.2` (dock era): rename/care/salvage/equipment, unowned-part preview restrictions,
  four animated poses and hide/show persistence exercised on device; reader Select → Hangar → Gallery → B → B preserves the
  article position and resumes D-pad scrolling.
  A real 310-second game session earned exactly one credit; process restart and in-place upgrade preserved inventory and
  did not repeat the reward. Background frame count stayed fixed with motion enabled. Motion-off sprites stayed still.
  Both JVM variants passed 308 tests. Original card configuration/preferences, all 10,154 game/path rows and 130 history
  rows were preserved; the three smoke sessions were the only appended history.

### Library hygiene (M3)
- Disc-tag and same-stem container grouping (`data/index/Variants.kt`), with alternate files on the game page.
  `.m3u` files can launch playlists; Sortie does not parse their contents to hide every referenced disc.
- Explicit modified-game marks, not automatic hack-to-base-game grouping; badges for configured missing BIOS,
  detected saves, arriving files and placeholders.

### Juice (M4, implemented)
- Artwork-derived, contrast-checked accents and ambient palettes, cached per image.
- Optional default-Home role.
- Per-device manifests for library subsets, emulators and screen roles.

## Non-goals (for now)
Scraping, cloud sync, RetroAchievements UI, themes marketplace, video previews, any network use.

## Controller grammar
A select/launch · hold A context sheet (Favourite · Add to / Remove from home on games, apps and shortcuts; Add to home on
Home's systems and folders; Move · Remove on Custom home tiles) · B back · X swap screen roles · Y favourite toggle (detail) /
search (on Home) / other screen (switcher) · Start search (not available while Sortie is the home app: Android intercepts it) ·
Select launch-profile sheet (detail) / list↔grid (Home, system and folder lists, search, Apps) / close app (switcher) · L1/R1
previous/next system (lists) / sort (Home) / console (By Console) / Perf page (overlay switcher) · R2 Want to play toggle
(games, detail) · L2 leave a note (while the post-session bookmark invitation shows) / browse the picture sequence in view
(magazine article) · hold R1 favourite (games, apps, shortcuts) / random game (system and folder tiles) · Home (physical) app
switcher when Shizuku is available, including while reading. Moving a Custom home tile: D-pad one slot per press, A drop,
B cancel. Long press = hold A for touch. Compact hint bars keep the main buttons and list the rest under Controls.

## Implementation notes for contributors

Code map (`app/src/main/java/io/github/esko997/sortie/`):

- `SortieApp.kt` service locator (`graph`): manifest/user/play-log stores, Room database, indexer, launcher, folders, apps,
  device state and the Shizuku screen mover. The StrictMode file-URI check is relaxed here because emulators need `file://`.
- `MainActivity.kt` navigation, HOME role, debug-only intent hooks and X screen swap (the saved state is handed to the new
  instance in process memory). `SecondaryHomeActivity.kt` / `CompanionActivity.kt` host the other-screen companion;
  `AnchorActivity.kt` moves controller focus to a display; `ShortcutPinActivity.kt` confirms `requestPinShortcut`;
  `RestartActivity.kt` restarts the main process once storage access arrives.
- `data/manifest/` JSON models and the hot-reloading `ManifestStore`; `data/setup/` on-device Library setup (presets,
  merge-preserving transactional manifest commits); `data/index/` incremental indexer, disc/duplicate variants and BIOS/save
  state; `data/db/` Room; `data/folders/` smart-folder rule engine; `data/user/` `user.json` and `playlog.jsonl`;
  `data/backup/` export/restore; `data/magazine/`, `data/reference/` the reading and reference-card features (`data/user/LegacyNotes.kt`
  archives and migrates the former journal and notes); `data/guide/` the bundled curated guide and its precedence;
  `data/tonight/` Tonight's picker and daily store; `data/apps/` installed apps and launcher shortcuts.
- `launch/` intent building and launching with fallbacks (`SessionEndWatch.kt` decides when a game really ended); `shell/` the Shizuku user service, screen mover, app switcher and
  device-stats probe; `stats/` device-stats model, formatting and the poller; `ui/` Compose screens (`ManualScreen` renders
  `assets/MANUAL.md`; `DeviceStatsViews.kt` the stats strips and overlay line).
- `design/` library module (`:design`, still package `io.github.esko997.sortie.ui`): theme colours, `SortieTheme`, the
  controller-focus building blocks (`focusGlow`, `keyLongPress`, `FocusRow`) and `displayLit`/`displayLitFlow` (is this
  window's screen on, from display callbacks) shared by the app and feature modules.
- `mecha/` library module (`:mecha`, package `io.github.esko997.sortie.mecha`): the Hangar companion's rules, save,
  parts catalog and art (`mecha/src/main/assets/mecha/`), roaming (`MechaRoam` geometry/choices), play (`MechaPlay`:
  touch classification, drops, vignette geometry, `MechaProp`), `ui/MechaStage` host, touch watcher and spot modifiers,
  `ui/MechaDirector` behaviour, `ui/MechaLife` the shown gate, rigs and idle life, `ui/MechaPresence` one mech across
  displays, and Hangar UI. It knows no Room, app or navigation types: `SortieApp` maps sessions to `PlaySession` for
  `MechaStore`, `SortieNav` and `CompanionScreen` host stages and declare spots and props, the view model reports returns
  from play, and `BackupManager` carries the save through `backupSave`/`restore`/`undoRestore`.
- `app/src/test/`, `mecha/src/test/` JVM unit tests; `smoke/` emulator smoke tests (`tools/emulator/smoke.sh`,
  docs/HARDENING.md). Personal device paths and serials belong in manifests or environment variables
  (`tools/dev.sh` reads `SORTIE_SERIAL`, `SORTIE_TOP_DISPLAY`, `SORTIE_BOTTOM_DISPLAY`); emulator contracts live in the
  setup presets and launch profiles.

Android behaviour that shaped the design (observed on Android 13 dual-screen hardware):

- A Presentation cannot target the default display, an app cannot move its task between displays, and a HOME task ignores
  `moveTaskToBack`. Hence the companion activity, relaunch-to-swap and finishing on launch from the second screen.
  Compose does not save focus across that relaunch or a different row count, hence `GridMemory`.
- Controller focus follows the display that last started an activity or was last touched. `am task focus` raises a task
  without moving display focus; starting an activity on a display does (`AnchorActivity`). Guard against two companions
  anchoring back at each other, but only for the one resume an anchor causes on its target (`AnchorGuard`): a blanket
  "anchored recently" timer also swallowed the hand-off after a quick second X swap, leaving the controller on the companion.
- `am stack remove` accepts leaf task ids; `cmd activity display move-stack` needs the root task id. Removing a root HOME
  task removes Sortie too. Never remove the system launcher's own tasks.
- Leaving the default display empty makes the system start HOME again; cover it with the companion first.
- Android 13 rejects singleTask/singleInstance activities for SECONDARY_HOME.
- Full-table Room reads concurrent with indexer writes can fail with "Couldn't read row N from CursorWindow": read inside
  a transaction, debounce and catch.
- An installation can disturb the default-Home choice on tested devices; recheck it after updating.
  `SORTIE_HOME=1 tools/dev.sh install` explicitly selects Sortie as Home on a debug development device.
- Measure idle cost with `dumpsys gfxinfo`, `dumpsys meminfo` and `top`, recording which surfaces and animations are visible.
  Visible Rivet motion intentionally draws idle poses and movement; clocks, hero rotation and stats can also update.
  Hidden or screen-off surfaces should stop their own animation and polling, not just draw fewer frames.
  Covers use `CoverImage` (constraint-sized, hardware bitmaps), a fixed 96 MB memory cache and no disk cache.
  No frames is not enough: timers and flows can keep running in a stopped activity or on a dark screen. Work a
  surface starts itself (stats polls, the companion's 20 s hero rotation, which loads a picture and a palette and makes the
  navigation background blend) is gated on `shownFlow` (lifecycle RESUMED and display lit, from their own events;
  `rememberShown()` in Compose). With both Thor screens off the hero rotation cost ~134 jiffies a minute; gated, ~12.
