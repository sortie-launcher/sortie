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
surfaceless window twice 1.5 s apart. The panel's button broadcast (`action.tcc.button.key.event`) is protected, so the
leak itself cannot be reproduced from adb; the decision logic is unit-tested and the probe was checked against the live
panel and the captured stuck state.

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
every Home press, even while the same overlay was already up. A pending change (draft PR "Switcher overlay: reuse
windows on repeated Home") keeps them when displays and controller screen are unchanged; it needs a Thor run before
release. Any app's window churn can still trigger the vendor bug.

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
