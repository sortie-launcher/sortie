# Hardening: UI stress and known platform failures

Sortie lives between games, video apps, two screens and vendor software it does not control. This page covers the stress
harness used to find where that breaks, the invariants it checks, and the failures found so far with their causes.

## Running the stress harness

```
tools/stress/ui-stress.py --serial SERIAL --minutes 90 [--seed N] [--no-kill] [--out DIR]
```

adb runs inside the `ubuntu` distrobox by default; set `SORTIE_ADB_WRAPPER=""` to call adb directly. Displays (and their
physical ids for screenshots), the card's `sortie/` folder and a Game Boy ROM for the RetroArch scenario are discovered on
the device. Everything is seeded: the seed is printed and part of the output folder name, so a run can be replayed.

Scenarios (weighted, random): Home and the switcher overlay with D-pad/A/B/Y/Select inside it (A, Y and Select only while
the overlay is up); rapid Home; X screen swaps (single, double, triple); launching apps on either display; a RetroArch game
with Sortie's default flags; moving a task to the other display; Recents; Back bursts; controller bursts on Sortie;
force-stopping Sortie; "clear all"; sleep/wake; launching then pressing Home and X before the app has drawn; trim-memory;
the magazine reader (shelf → last issue → article, reading, X and L1/R1 mid-article, reader menu); the Hangar (Workshop,
Gallery, X while open). Reader and Hangar steps return Sortie to its Home route, bring navigation to the top display
(X) and locate controls with `uiautomator`. That dumps the *active* window, which on two screens can be the companion's
carousel below, so the harness first taps Home's top-left corner on the top display. `--no-kill` drops the force-stop
scenarios for single-process leak runs.

Where `uiautomator dump` cannot work, the run logs one `skip` event with reason `uiautomator unavailable` and leaves out
the reader and Hangar scenarios; the rest run as usual. It is declared on "null root node" or after three dumps in a row
that produce no hierarchy; a single failed dump is logged as `uiautomator dump failed` and retried later. The Retroid
Pocket Nova is such a device: every dump, for every app, fails with "null root node returned by UiTestAutomationBridge",
most likely because Retroid's Game Assistant accessibility service (`com.rp.gameassistant`) is bound. Check the Nova
with screenshots and key input instead.

Safety: the card's `user.json`, `systems.json`, `launchers.json`, `folders.json` and `device.json` are copied first and
restored if changed (L1/R1 on Home legitimately changes the saved sort). The harness never presses A in Sortie outside the
overlay or the reader, never launches games through Sortie (no play-log entries), never edits clippings, never uses care,
rename, salvage or equip in the Hangar, never uninstalls, mutes media volume and keeps the screen on for the run, then
restores both. Opening articles does move "Continue reading".

If the device disappears mid-run (see the system crash below), teardown says so; once adb is back,
`tools/stress/ui-stress.py --serial SERIAL --restore-only --out <that run's folder>` restores its settings and card files.

Output (`~/.cache/sortie-hardening/<serial>-<time>-s<seed>/`): `events.jsonl` (every action, metric sample, log hit and
anomaly), `logcat.txt`, and `anomalies/<n>/` with both screens, task/focus dumps, the 40 preceding actions and log hits.

### Invariants

- No crash or ANR (any app's are recorded; Sortie's are failures) and no ANR dialog left on screen.
- Focus: on the focused display, a Sortie activity that is the focused app must get a focused window within 8 s; no
  display's focus may be held by the AYN panel's window (see below).
- After Home settles: one Sortie navigation task, no lingering `AnchorActivity` task, Sortie or its overlay visible, every
  display showing something, at most two switcher overlay windows.
- Metrics every two minutes: PSS split (Java, native, graphics), live Activities/ViewRootImpls/AppContexts, frames
  rendered, network requests held by the AYN panel, the system uid and Sortie. More than 12 live activities for three
  samples in a row is reported as a leak (single samples include instances that have not been collected yet).

## Findings

### AYN Thor: the Thor-button panel leaks until the bottom screen locks up

Observed 25 October 2026 after an evening of games on the top screen and video on the bottom one. In order: the Thor button
stopped opening its panel, Home stopped bringing up the switcher, and Sortie on the bottom screen showed "isn't responding"
on every press, again after restarting it.

- `com.odin.dualscreen.assistant` (system uid, persistent) registers a ConnectivityManager callback in its status bar view
  (`InternetSpeedView`) each time the panel opens and never unregisters it. After 32 days' uptime it held 213 requests;
  system_server held 36: 249 of the 250 allowed per system uid.
- The next opening threw `TooManyRequestsException` inside `StatusBar.onAttachedToWindow`. The panel's crash handler
  (`com.tamsiree.rxkit.crash.TCrashProvider`) kept the process alive, and its new window never drew (`mHasSurface=false`,
  `mDrawState=NO_SURFACE`) yet remained `mCurrentFocus` on display 4.
- With focus on a window that cannot receive input, InputDispatcher found no focused window on display 4. Sortie's
  MainActivity there was the focused app, so every key became a 5 s timeout blamed on Sortie ("Application does not have a
  focused window"); its main thread was idle. Force-stopping Sortie or restarting it changed nothing.
- `am force-stop` skips persistent processes; `am crash com.odin.dualscreen.assistant` restarts the panel, which releases
  all its callbacks and the stuck window. Rebooting also fixes it but turns off wireless debugging.

Sortie's guard (`ScreenMover.guardVendorPanel`, Shizuku only, at most once a minute when the shell connects and when Sortie
returns) restarts the panel at 150 held requests while no panel window has a surface, or after seeing its focused,
surfaceless window twice 1.5 s apart. It acts only on a complete probe: exit status 0, each dump's header and closing
field present, one surface line per counted panel window and a final `end`; anything partial does nothing, since restarting
an open panel is worse than waiting a minute. The panel's button broadcast (`action.tcc.button.key.event`) is protected, so
the leak itself cannot be reproduced from adb; the decision logic is unit-tested (including truncated replies) and the probe
was run on the live panel and replayed against the dumps captured in the stuck state.

### Back on Home sent Sortie away (fixed)

On a device where Sortie's navigation runs in an ordinary task (started from its icon or another launcher, as on the DMG),
Back on Home moved the task to the back (Android 12+ default for root activities), leaving Recents or a stale app on
screen: reproduced with Home, Home, B. Home now consumes Back. On the Thor, a HOME-type task ignores `moveTaskToBack`, which
hid this.

### AYN Thor: system_server crash while the switcher overlay closes

25 October, 05:51, segment 3 (0.1.0-beta.1037): `*** FATAL EXCEPTION IN SYSTEM PROCESS: android.display`,
`NullPointerException: Invalid Surface(name=Sortie switcher) … mNativeObject is null` in
`CollectWindowContentFrameStatsUtils.getWindowContentFrameStats`, called from `WindowState.setHasSurface`. That class is
not in AOSP: the Thor's WindowManager collects frame stats from every visible window whenever one gains a surface. Both
switcher overlay windows were being removed at that instant (their input channels were disposed in the same
millisecond), and the vendor code read one whose surface had just been released. system_server restarted (a soft
reboot), which switched wireless debugging off.

The race is in the firmware, but Sortie made it likely: `SwitcherOverlay.show()` removed and re-added both windows on
every Home press, even while the same overlay was already up. It now keeps them when displays and controller screen are
unchanged. Checked on the Thor: across Home, Home and five rapid Home presses the two overlay windows kept their ids; B
removed them and the next Home created new ones. Any app's window churn can still trigger the vendor bug.

### Seen under stress but not Sortie's

- RetroArch: "no focused window" ANRs after launching a game and pressing Home/X within seconds, a main thread that hangs
  for many minutes after rapid menu relaunches (pause/stop timeouts, black screen; Home still works), and native SIGSEGV /
  SIGBUS in `Thread-3`. Earlier testing already showed RetroArch 1.22.2 finishing and recreating `RetroActivityFuture`
  across launches.
- DocumentsUI (RecyclerView animation `IllegalArgumentException`) and PermissionController (Back during a permission
  prompt, NPE) crashed under Back bursts.
- The Thor's card-level `ra-gambatte` profile still carries `CLEAR_TASK`, which can hang RetroArch's activity teardown
  (see LAUNCHERS.md); Sortie never overwrites custom profiles.

### Checked and clean

- Thor, 90 min, ~1,600 iterations on 0.1.0-beta.1033 (with the Hangar): no Sortie crash, ANR, stuck focus, duplicate
  navigation task or lingering anchor; overlays never leaked. Live Activities peaked at 31 during swap storms and fell to
  2 within a minute of idling: uncollected instances, not a leak. Sortie and the panel held no network requests.
- Thor, 0.1.0-beta.1037: two further segments (~35 min and ~20 min of activity, reader and Hangar included) found no
  Sortie crash or ANR before the system crash above.
- DMG, 90 + 150 min (~3,100 iterations; the second run on the Back fix with 93 Hangar visits, 52 Gallery visits and 93
  RetroArch launches): no Sortie crash or ANR. Every crash and ANR recorded was RetroArch's.

## Testing

### Tiers 1–2: Compose interaction and screenshot tests

`app/src/testDebug/` runs the real `MainActivity`, `SortieApp` graph, view model, Room database (Robolectric's native
SQLite), indexer and stores under Robolectric with native graphics, against a fixture card. They are part of
`testDebugUnitTest`, so `tools/build.sh` and CI run them on every build (27 tests, about 30 s of added test time locally;
release unit tests stay plain JUnit).

- **Fixture** (`testing/FixtureCard.kt`, `resources/fixtures/`): a card with `device.json`, `systems.json` (three systems),
  `launchers.json`, `folders.json` (one folder), `user.json` (custom sort, five-column grid), six ROM stubs with generated PNG
  covers and two magazine issues (No. 007 and No. 012, a long article with a lead, a portrait and a wide figure). The
  Hangar save in `fixtures/app/` has the companion hidden and motion off, so no roaming mech is drawn on Home, the shelf or
  articles, and the Hangar's sprites hold still. `SortieCardRule` writes it, pins the card folder, fixes the displayed clock
  (09:41 UTC, `uiNow`) and hides the bundled Field Notes issues of `-PsortieMagazinesDir` builds, so every build shows the
  same shelf. Pictures load through the app's Coil loader on the main thread with BitmapFactory (Robolectric has no
  ImageDecoder), so a screenshot never races a background load.
- **Controller** (`testing/Controller.kt`): keys go to the focused window the way the input dispatcher sends them, including
  the generic controller fallbacks (A → DPAD_CENTER, B → BACK, Select → MENU) and the first D-pad press leaving touch mode.
- **Interaction tests**: Home, Home, B keeps Sortie on Home; D-pad order through the header, sort chips and custom grid;
  A/B return to the same tile; reader position survives activity recreation; R2 opens the picture in view and B closes it
  with the article unmoved; Home's Hangar button opens the Hangar and B puts the focus back on it; reader Select → Visit
  the hangar, B returns to the article.
- **Screenshots** (Roborazzi): Home, magazine shelf, article at each figure and the Hangar Workshop (from Home's header) on the Thor's top
  (`w833dp-h421dp`) and bottom (`w538dp-h444dp`) screens, the DMG (`w480dp-h551dp`) and the Retroid Pocket Nova
  (`w640dp-h480dp`), recorded at half size with no tolerance into `app/src/testDebug/screenshots/<device>/`. A build run
  with `-Proborazzi.test.verify=true` (`tools/build.sh`, CI) fails on any pixel change; CI uploads the diffs as
  `screenshot-diffs`, locally they are in `app/build/outputs/roborazzi/`.

Re-record after an intended visual change, look at every changed image, and commit them with the change:

```
flock /tmp/sortie-gradle.lock distrobox enter ubuntu -- bash -c 'cd <worktree> && ./gradlew :app:recordRoborazziDebug'
```

Production seams: `uiNow` in `ui/Components.kt`, the wall clock behind Home's clock and "last played"; and the focus-glow
pulse (`design/…/Focus.kt`) times itself on the composition's frame clock instead of `System.nanoTime`, which tests drive.

### Tier 3: recorded device output

`ScreenMover` and `TaskSwitcher` decide from what the shell prints: which tasks `cmd activity stack list` shows on which
display, and what the AYN panel probe reads from `dumpsys connectivity` and `dumpsys window`. The `Replay*Test` classes in
`app/src/test/java/io/github/esko997/sortie/shell/` run them against output recorded on the Thor and the DMG, kept in
`app/src/test/resources/device-captures/`. A fake shell answers from the capture and records every command issued, so
the tests assert exact `move-stack`, `task focus`, `stack remove` and `am crash` commands: which app moves, what Home
reveals, where "Sortie" goes, that Launcher3 and Sortie's own tasks are never touched. The panel tests run the real probe
script in a local `sh` with `pidof` and `dumpsys` printing the recorded dumps, so a probe that misreads the Thor's output
fails here rather than on the device.

They are plain JVM unit tests and run with the others (`tools/build.sh`, or
`./gradlew :app:testDebugUnitTest --tests 'io.github.esko997.sortie.shell.*'`); they need a POSIX `sh`, `grep` and `awk`.
To add a capture (and keep it free of private data), follow the README in the captures folder.

### Tier 4: emulator smoke (CI)

`smoke/` is a separate, self-instrumenting test APK (UiAutomator, `DualScreenSmokeTest`) that drives the debug build on an
Android 13 x86_64 emulator with a second screen, the way a player would: keys go to whichever display has input focus, and
every check reads the task list (`cmd activity stack list`), the focused display (`dumpsys input`) or the accessibility
tree of one display. `tools/emulator/smoke.sh` prepares the emulator and runs it; `.github/workflows/emulator.yml` runs that
script on demand (Actions > emulator > Run workflow, or `gh workflow run emulator.yml --ref BRANCH`, once the workflow is on
main), on pull requests labelled `emulator`, and on pull requests that touch `shell/`, `MainActivity`, `CompanionActivity`,
`SecondaryHomeActivity`, `AnchorActivity`, the app manifest, `smoke/` or `tools/emulator/`. A run takes about 5 minutes
once the booted AVD is cached (build 2, emulator boot and setup 2, the tests under 1) and about 8 when it has to create
the AVD; the cache is saved right after the first boot, before the tests change the emulator.

Covered:

- Home: one navigation task on the primary display, Home drawn there and the controller on that display.
- Companion: Android starts Sortie's SECONDARY_HOME on the second display by itself (the display is added after Sortie
  becomes HOME), and the companion, not Home, is drawn there.
- X and X again: navigation moves to the second display and back, a single MainActivity task each time; CompanionActivity
  covers the primary display, then closes; the companion returns below; the controller follows navigation; no
  AnchorActivity lingers. Quick presses too: X again the moment the controller has followed navigation, and two presses
  350 ms apart, each ending with one navigation task that holds the controller (keys injected directly, timed in the test).
  The end state is always checked; when a slow runner spaces the presses too far apart (1.5 s for the first, 500 ms for the
  pair, which is tried twice) the test is reported as skipped with the measured gap instead of failing.
- Home from another app (Settings) returns to the same navigation task.
- Back on Home leaves Sortie in front, both as HOME and in an ordinary task (started from the launcher intent while another
  app is HOME), the case behind "Back on Home sent Sortie away" above.
- The magazine reader, with the pinned magazines: shelf, issue contents, first article, controller only.
- Any Sortie crash or ANR in the log fails the run, even one the tests recovered from.

Not covered: everything that needs Shizuku (the switcher overlay, moving, swapping and closing apps, the AYN panel guard).
Shizuku is a third-party APK whose server must be started and Sortie authorised in Shizuku's own dialog for the current
installation (an Android permission grant is not enough); there is no headless path, so those stay hardware checks
(`ui-stress.py`). Also not covered: launching games, the Hangar, touch on the companion, vendor firmware.

The second screen is the emulator's own virtual display (`adb emu multidisplay add 1 1240 1080 369 1739`: the emulator's
default flags plus FLAG_PRESENTATION, which Sortie looks for). An overlay display (`settings put global
overlay_display_devices 1240x1080/369,own_content_only,should_show_system_decorations`) does get a SECONDARY_HOME when it
has `should_show_system_decorations`, but Android leaves overlay displays out of accessibility, so no test can read them.
The tests run in their own process because instrumenting Sortie itself makes it an instrumented HOME, which Android never
starts on a secondary display by itself (`RootWindowContainer.canStartHomeOnDisplayArea`).

Run it locally on an API 33 x86_64 emulator (it refuses real devices: it reinstalls Sortie and replaces `/sdcard/sortie`,
`/sdcard/roms`, HOME and the display size):

```
./gradlew assembleDebug :smoke:assembleDebug -PsortieMagazinesDir=/path/to/sortie-zines/issues
ANDROID_SERIAL=emulator-5554 tools/emulator/smoke.sh
```

Results land in `smoke/build/emulator-smoke/` (test output, logcat, task, display and input dumps, a screenshot of the
primary display); CI uploads them as the `emulator-smoke` artifact. The fixture card is `tools/emulator/card/`: two
systems and three placeholder files that are not ROMs. Home is recognised by its "3 games" header, so adding a file means
changing `GAME_COUNT` in the test. `ZINES_COMMIT` in emulator.yml follows build.yml.

Found while building it (fixed by `AnchorGuard`): X again within 1.5 s of a swap's hand-off left the controller on the
companion's screen. Companions skipped their resume hand-off for 1.5 s after any anchor, to stop two companions anchoring
back at each other, so the companion returning under the second swap kept the controller. Now only the one resume an anchor
causes on its own display is skipped, and navigation resuming there retires the anchor.
