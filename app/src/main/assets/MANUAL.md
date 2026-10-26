# Sortie manual

Sortie is an offline, controller-first game launcher. It lists the games already on your device and starts them with the
emulators you have installed. Set up your library on the device (below), or use a library prepared on a computer.
Preferences, bookmarks and play history live on the device. Sortie has no internet access and never downloads games,
BIOS files or emulators.

## Getting started

1. **All files access.** On first start Sortie asks for Android's *All files access*. It is needed to read your game folders
   and to keep Sortie's settings and your bookmarks. **Open settings** goes to the switch; if your device opens a different page,
   look for Settings → Apps → Special app access → All files access → Sortie. Everything else Sortie can ask for is optional.

2. **Install your emulators first** and finish each one's own first-run setup. Sortie only starts emulators that are already
   installed. BIOS or firmware files, RetroArch cores (RetroArch → Online Updater) and emulator settings are set up inside
   each emulator; Sortie cannot do that for you and does not supply them.

3. **Set up library** on Home (later **Settings → Library and emulators**) → **Add a system…**. Pick the kind of games, e.g.
   *PlayStation 2* or *Game Boy Advance*, or **Something else…** to name your own. Then:
   - **Add a folder…** and browse to the folder that holds the game files themselves, then **Use this folder**. Add as many
     folders as you need; they can be on internal storage or a memory card.
   - Check **File types**. The suggestion fits most libraries; leaving it empty removes the extension filter. Hidden files
     and recognised support files, such as images, saves and text files, are still skipped.
   - Under **Emulators**, Sortie tries installed entries in order unless you have a per-game or Settings default.
     **Add an emulator…** adds another; select one to move it up/down or remove it. **Open with…** is optional: add it
     explicitly if you want Android to ask which app to use. Preset systems show **Before the first game** with requirements and tested launch methods.
   - **Save.** Sortie writes the settings, reads the folders and shows how many games it found, plus a few games to try.

4. **Test a game.** Open one from the result and choose **Play**. Sortie can only tell that the emulator opened: check that
   the game itself runs, then come back to Sortie.

5. Optional: **Settings → Default home app** makes Sortie your Home screen; the same entry switches back to another launcher.

Repeat **Add a system…** for each kind of game. You can leave Library while it saves or indexes: the work carries on.

### What Sortie finds

- Only files **directly inside** a chosen folder are listed. Subfolders are not searched: add each one as another folder.
  The folder check tells you when subfolders hold matching files the list will miss.
- Files must end in one of the system's file types. Two files with the same name in different folders of one system appear
  as a single game.
- PSP: Sortie hands disc images (ISO, CSO, CHD) to PPSSPP. Homebrew kept as an **EBOOT.PBP** inside its own folder is not found, and a loose
  **.elf** or **.prx** may not start from Sortie; start such homebrew from inside PPSSPP.
- After copying new games, use **Library setup → Re-index every system** (or **Settings → Diagnostics → Re-index everything**).
- After downloading RetroArch cores, use RetroArch's **Quit** action before starting a game here. Save before changing games.
  RetroArch 1.22 closes itself when asked to open a different game while it is running, whether on the same core or
  another. Sortie then says so on the game's page, e.g. *RetroArch closed while switching cores — press Play again*: press
  **Play** again and it starts. Sortie does not force-close a running game when you switch away to read or use another app.

### Maintaining your library

- Select a system in Library to rename it, add, replace or remove folders, change file types or reorder emulators. A system
  keeps its identity when edited, so its favourites, bookmarks and play history stay attached.
- **BIOS and save folders** (optional): the BIOS folder lets Sortie mark games whose listed BIOS files are missing; save
  folders let the **💾 started** filter recognise matching filenames, not whether you have actually played. Emulators keep their own BIOS and save settings.
- **Remove from library…** takes a system off Sortie's lists only. Game files, saves and BIOS files stay where they are;
  favourites, bookmarks and play history are kept and return if the games are added again under the same system.
- A library prepared on a computer keeps working. Library changes only what you edit; other systems and settings stay
  exactly as written. If a settings file can't be read, Sortie says so and will not save over it. If the settings changed
  on the card while you were editing, **Reload** keeps your draft and shows the current settings before you save again.
- **Where these settings live**, at the bottom of Library, shows the **sortie** folder Sortie uses. After you save in Library,
  Sortie keeps using that folder.

### Missing storage, permissions and failed launches

- If the memory card holding your games or Sortie's **sortie** folder is removed, Sortie keeps its index of your games and
  does not start a new library elsewhere. Until the card is back, its systems, games and the bookmarks stored on it may be
  missing from the lists. **Reinsert the card** (or turn All files access back on), then re-index. Do not set the library up
  again on other storage: your bookmarks and preferences are in the original **sortie** folder.
- After reconnecting saved settings, **Library setup → Try again** may restart Sortie so every screen reads the restored
  library and preferences. Returning to Sortie can do the same. Changes made while storage is missing are not saved;
  Sortie will not replace a restored preferences file with the empty defaults it had while that file was unavailable.
- A folder that is missing or unreadable is reported after indexing, and its games are kept until it is back.
- When a game can't start, Sortie says why: file not present, emulator not installed, no emulator set up, or the emulator
  refused the request. **Settings → Diagnostics** keeps the exact launch attempts.
- Only All files access is required. Everything else is optional and requested only where you turn that feature on:
  Shizuku (the switcher and moving apps between screens, the companion's bell, device stats beyond RAM and battery
  temperature, and a few automatic fixes such as timing play while Home only shows the switcher), *Display over other
  apps* (the overlay switcher), the default Home role and the radial keyboard. If Android can't open the right settings
  page, Sortie tells you where to find it.

## Controls

- **A** open · **B** back · **X** swap which screen has the controller (dual-screen devices)
- **Hold A** on a game, app, shortcut, system or folder tile: its context sheet (the choices depend on the item)
- **Hold R1** on a game, app or shortcut: favourite. On a system or folder tile: jump to a random game in it
- **L1 / R1** (tap): previous / next system in lists; on Home: previous / next sort; in "By Console": previous / next console;
  in the switcher overlay: the Perf page and back
- **R2** on a game (in any list, grid or on Home) or on a game's page: add to / remove from *Want to play*, a queue separate
  from favourites. The *Want to play* folder is always on **Home → Custom**; launching a game keeps it queued
- **Y** on Home: search. On a game's page: favourite
- **L2**: while the after-playing bookmark card shows, leave a note. In a magazine article: browse the picture sequence in view
- **Select**: list ↔ grid; on a game's page: **Launch with…** (choosing an emulator saves that game's preference and launches it)
- **Home key**: the switcher when Sortie is the default Home app and Shizuku is ready; otherwise Android's usual Home action
- **Swipe down from the top edge**: the status bar
- **Controls**: the hint bar shows the current screen's buttons. When it is compact (see Settings → Header and hints) it
  keeps only the main ones; tap or select **Controls** at its end for the full list

## Home

**L1/R1** cycle the sorts: *Alphabetical*, *Game count*, *Generation* (grouped 8-bit → modern), *Custom* and *By Console*
(a strip of consoles with that console's games underneath). The first three sorts show emulation systems only.

- **Custom** always includes every smart folder and **Apps**, alongside your pinned systems, games, apps and shortcuts.
- **Favourites** is always in **Custom**, even when empty. It includes your favourited games, installed apps and shortcuts;
  hidden items stay hidden. A star before a list-row title marks a favourite; **Fit 4+** is a separate guide-rating filter.
- **Continue** shows the last game you played and starts first on Custom; it can be moved like any other tile.
- Hold A on a system, game, app or shortcut and choose *Add to home*. On Custom, hold A → *Move* lifts a tile; the D-pad moves
  it, A drops it, B cancels. Existing pins keep their order. Smart folders, Apps and Continue can be moved but not removed.
- **Apps** includes Sortie's built-in **Magazines** and **Performance**, installed apps and the shortcuts you have opted
  in to ("Browse all shortcuts…"). When another app offers to add a shortcut, Sortie names it and asks first: **Add** or **Cancel**.
- The header starts with the amber Split-S mark (a screen reader reads it as "Sortie"; it is not a button), the clock and the
  game count. Its buttons are **Apps**, **Search**, **Extras** and **Settings**.
- **Extras** opens a short menu: **Tonight** (see below), **Hangar** (the companion's home) and **Magazines**. **A** opens the
  focused choice; **B** or **Cancel** closes the menu and puts the focus back on Extras.
- **Settings** holds **Library and emulators** and, under Help, **Sortie manual** (this manual).
- With no games yet, Home shows **Set up library** (or **Manage library** when systems are set up but no games were found).
  Apps, Magazines and the manual work without any games.

## Lists and grids

Each system or folder is a list or a grid (**Select** toggles). Grids can scroll sideways in rows — better on a wide top screen —
or downward in columns; Settings chooses, and rows/columns set the preferred tile size. On smaller screens, home and game
grids automatically use fewer rows or columns when necessary to keep tiles readable. Swapping screens does not change your
saved density or scroll direction; moving back restores the preferred density when it fits. Lists remain lists.
Your current page, back history, search, filters, scroll position and controller selection follow you across an X screen swap.

Small app and shortcut tiles automatically show only their icons instead of cramped labels. Larger tiles and list rows
keep their names; icon-only tiles still expose names to accessibility services. Adjust **Settings → App tiles per row**
to change the Apps grid density. The label cutoff adapts to tile width and your text size.

The small **hack** mark on game tiles and list rows identifies a hack, translation or other patched build. It uses
explicit guide flags and filename markers, not guesses from the description. A missing mark does not guarantee an
unmodified release. The favourite star and personal controller-support badge remain separate.

On narrow portrait screens, Home puts its actions on a sideways-scrolling row below the mark and clock. Short landscape
screens put that row beside them, leaving room for tiles. Larger screens keep the wrapping header. Sort choices scroll sideways
(touch or D-pad), and controller hints wrap as whole button/action pairs. Switching list/grid retains the selected tile.
**Settings → Header and hints** can fold this header and the hints out of the way while you browse (see Settings).

System and folder filter chips: **Fit 4+** (guide rating 4 or 5), **✍ notes** (a curated guide entry, including
the built-in AI-assisted guide, or your bookmark), **💾 started** (a matching save or state exists in the configured save
folders), **on device** (hide zero-byte placeholders; not a live file or emulator check).
**🎲 Pick one** opens a random item from what is shown. The title stays above these controls; on smaller screens they wrap.

## Search and filters

Search accepts a title, tag, genre, developer or description fragment. Text is optional: pick **System**, **Tags** or
**Genre** to explore without typing. Tags include the badges on game pages, including hack and translation flags.

- Pick several values in a filter to match **any** of them; different filters must **all** match.
- **More** offers favourites, Want to play, unplayed (never launched), **Fit 4+** and **On device only (default)**.
  The More count counts changes from those defaults, not the default on-device setting itself.
  Like the browse chip, **On device only** excludes zero-byte placeholders; it does not check whether a card is still mounted.
- **A / tap** toggles a picker value; **Done / B** closes the picker. Its counts describe the library, not the other active filters.
- Active filters appear below the buttons. Tap one to remove it; **Clear filters** keeps your search text.
- The game count includes all matches. More results load as you scroll; the initial page is not a search limit.
- Opening Search focuses the text box and opens the keyboard; returning from a game keeps your browsing focus instead.
- **D-pad Down** from the search box focuses results and closes the keyboard, including after you close it yourself.
  With no results it goes to the filter buttons. **Up** from results reaches those buttons.
- Open a game and return with **B**: your text, filters and browsing position remain.

In multiline bookmark, verdict and reference-card editors, controller Up/Down moves between fields and controls rather than moving
the text caret. Down from the last field goes to **Save** when there are changes; Cancel and Clear remain deliberate choices.
Touch and keyboard arrows still position the caret, and **A** on a text field brings up the keyboard.

## Hangar companion (experimental)

The little mech on Home is an optional, experimental companion, about a centimetre tall on any screen. He lives above the
interface, in its free spaces: beside the clock, at the end of the sort chips, peeking over tiles or visiting the hint bar.
He keeps clear of labels and controls, and only stops where enough of him shows to tap. The bar stays its normal height
and full width, with a light transparent gradient. If nowhere has room, he hides rather than stretching the interface.
On a dual-screen handheld he also roams the other screen (near the battery and bell, on or beside **Want to play**, over
the game's picture, by the **+**, with his base under the bell) and can walk off one screen and onto the other. There is
only one of him.

- While you can see him he breathes, blinks, shifts his weight and sometimes glances toward your selection. When you pause
  browsing he explores: looking before he moves, walking along clear edges, crouching into a small jump and settling after
  landing. If there is no safe route, he slips out of sight and reappears elsewhere instead. Reading and other quiet views
  keep him calmer. Now and then he naps, sits on an edge with his legs dangling, pokes the battery (or the clock on Home),
  or stands under the battery and lifts it a few pixels, straining. Any button or
  touch stops that at once and puts the battery back. When you come back from a game he waves and says what the session
  earned; news from the Hangar shows in a speech bubble where there is empty space. After a few quiet minutes he dozes
  until you press something. On a dual-screen handheld he keeps living on the other screen while you play (hide him in the
  Hangar if you would rather he didn't). He stops whenever his own screen is off or his Sortie window is hidden behind
  another app.
- Touch him: a **tap** pets him (a happy bounce and a little heart; it wakes him if he dozed off, and adds a little bond
  at most once every few minutes). **Hold still** for half a second and let go to open the Hangar. **Hold, then move** to
  pick him up and carry him: he dangles from your finger, and when you let go he drops to the nearest place he can stand,
  with a little hop. On a stacked dual-screen handheld, a **flick while carrying** can throw him through the edge facing
  the other screen. He keeps his momentum, arcs onto a safe perch and settles after landing. A gentle release near that
  edge still hands him across. The receiving screen must already show Sortie and have room for him; he never opens
  anything over a game. Otherwise he stays on this screen. Animate off skips the flight.
  A quick swipe before picking him up still scrolls whatever is under him.
- **Extras → Hangar** on Home opens the Hangar with the controller. In an article, **Select → Visit the hangar** also works.
  **B** returns from Gallery to Workshop, then closes the Hangar without losing your place; on Home the focus returns to
  **Extras**.
- **Workshop** lets you name your companion, tune it, charge it, or put it to sleep. Charge and condition never run down
  while you are away, and neither can stop you playing or using the companion.
- Choose a part slot and **Equip** an owned part, or wear a complete family set. Mixing families is encouraged.
- You start with twelve parts and three salvage credits. Each credit finds one new part, optionally in a chosen slot.
  New completed game sessions earn a credit per five accumulated whole minutes (up to 120 minutes from one session).
  Earlier play, abandoned sessions, apps and launcher shortcuts do not count. Recorded time is not a measure of active input.
- **Gallery** compares all three families and idle, walking, sleeping and cheering animations. Parts labelled **preview**
  are not unlocked; a preview build can only be worn when you own every part.
- At the bottom of Workshop, turn **Animate companion** off and he stays put at one spot, or **Hide companion** and he
  goes away entirely. **Show companion** in the Hangar brings him back without losing parts.

Your companion survives app restarts and in-place updates, and **Backup and restore** carries him too (see there).

## Built-in apps

**Apps → Magazines** and **Apps → Performance** open as separate app windows, not pages inside the switcher.
**Extras → Magazines** on Home opens the same reader. With two screens, choose **Open here** or **Open on the other screen**;
on one screen it opens directly. The companion's **+** and the switcher's **Open app…** also list both built-ins and already
know which screen you chose.

- Reopening a built-in brings back its existing window. **X / Other screen** moves only that built-in, keeping the reader's
  page and position; it does not swap the launcher or move the app on the other screen.
  On a single-screen device, **X** does nothing; it never opens the selected item instead.
- **Close** closes only that built-in. **B** closes Performance immediately; in Magazines it first goes back through the
  article, contents and shelf. An article opened from a game page's **Featured in** returns to that game page with B.
- The switcher lists **Magazines** and **Performance** separately, so each can be brought forward, moved or closed.
  Opening a built-in needs no Shizuku; the switcher's task-management features still do.
- Performance uses the same live readings as the switcher's Perf page and the optional companion Perf mode.
  Its polling pauses when the window is hidden or its display is asleep. It does not add an always-on overlay.

## Offline magazines

**Extras → Magazines** (or **Apps → Magazines**) opens illustrated offline zines: editorials, contents, gameplay and mechanics, history, reception and
sources. Some Sortie builds include a set of issues; a build without them starts with an empty shelf. Either way you can add
issue packs on the card yourself. Nothing is downloaded while reading.
Pictures in issues belong to their rights holders and are credited in each issue’s sources.

- **A / tap** opens an issue or article. **B** goes article → contents → shelf → closes the reader.
- The shelf and an issue's contents show each issue's number large (No. 010). The newest issue comes first; issues
  published the same day are ordered by number.
- In an article, **D-pad Up/Down** scrolls; **Left/Right** pages; **L1/R1** changes article.
- Pictures keep their shape: wide diagrams can use the full reading width, tall pictures stop at about two thirds of the
  screen, and small pixel art is enlarged without blurring. **Tap a picture**, or press **R2** when the hint bar shows
  **view picture**, to see it full screen on black. Pinch, double-tap, **A** or **L2/R2** zoom; **D-pad** or the stick pans
  a zoomed picture; **Left/Right** (when not zoomed), **L1/R1** or **Previous/Next** step through the article's pictures;
  **Y** shows or hides the caption. **B**, Back, a single tap or **Close** returns to the article exactly where you were.
  On two-screen devices the picture opens on the screen you are reading on; the companion screen shows no pictures.
- Some articles contain a **picture sequence** (experimental): two to twelve captioned pictures meant to be seen in order,
  such as a route or a before and after. On a wide screen they form one row with the chosen picture in the middle and its
  neighbours at the sides; swipe the row to choose a picture, or tap one to choose it and see it full screen. With a
  controller, press **L2** while the hint bar offers **browse images**: **Left/Right** then choose a picture instead of
  turning the page, **R2** opens the chosen one full screen, **Up/Down** still scroll, and **L2** or **B** stop browsing.
  Browsing also stops when the sequence scrolls out of view. On a narrow screen the pictures are stacked like ordinary
  pictures. The chosen picture is remembered with your reading position; browsing does not resume on its own.
- **Games mentioned** in an issue lists games regardless of ownership. **All / On this device** toggles the local filter.
  Placeholders, detected Syncthing transfers and unreadable files are excluded. Other copy methods may not be detected:
  finish copying before playing. Availability is not a compatibility promise.
- **A** in an article opens its game references or a matching library page, never launches the game. Multiple local copies
  are offered separately; games not found here remain listed.
- **Featured in** on a game page opens the articles that discuss it. **B** returns to the game page.
- **Continue reading** and per-article positions survive leaving the reader and restarting Sortie. **X / Other screen**
  keeps the open page while moving just the reader. Reading memory is app-private, not included in the current preference/history backup.
- On two-screen devices, if the other screen is showing Sortie's companion, it becomes a reading companion while an issue
  is visible. Opening the reader never replaces another app on that screen. **Sections** jumps to a heading in the current
  article and highlights your place; **Articles** opens another article in that issue. **Previous** and the folded
  **Next article** corner turn articles without losing their reading positions. The bottom-left **+** opens an app on the
  companion's screen, so a video can share the device with your article. Closing the picker returns to the same contents
  tab and position. Backing out to the shelf restores the normal companion; single-screen devices keep the controls above.
- Copy a complete extra issue into **sortie/magazines/**, in a folder named after the issue's id, with **issue.json**, **articles/** and **media/** inside.
  Reopen Magazines after copying. One entry is shown per issue ID: the higher revision wins, and your card copy wins a tie.
  Updates never overwrite your card pack. An incomplete or unsupported card issue is explained; a valid built-in edition
  remains readable instead of being hidden.
- **Y** in an article enters highlight mode: **Up/Down** chooses a paragraph, bullet or quote, and **A** opens its note editor.
  **Save** keeps the highlighted passage and your optional note; **Y / B** leaves highlight mode. Touch users can long-press a passage.
- **Select** opens the reader menu: clip the whole recommendation, edit its note, or open **Scrapbook**. The magazine shelf
  also has **Your scrapbook** (or press Select there). A clipping keeps a short excerpt and links, not a second copy of the article.
- In Scrapbook, **A / tap** offers the original passage/article, its game page, editing and removal. A game link opens its
  library page, never launches it. Saved text and notes remain readable if the issue is removed; missing or changed sources
  are explained rather than jumping to an unrelated paragraph. Removing a clipping also removes its personal note.

## Want to play

A queue for games you want to come back to, separate from favourites:
- Tap **Want to play** on a companion hero, including a rotating suggestion.
- Press **R2** on a focused game in a grid/list or on its game page.
- Or hold **A** and choose **Want to play** in the context sheet.

The **Want to play** folder is always on **Home → Custom**, even when empty. Its contents update automatically. The same
actions remove a queued game; launching it does not. Hidden games stay hidden; games temporarily missing from the library
reappear when indexed again. The queue is saved on the card and included in backups.

## Tonight

**Extras → Tonight** on Home suggests up to five of your games for today, one at a time: a gameplay screenshot (or the
cover), the title and system, why it was picked, a short excerpt of the guide's hook (the full text is on the game's page),
and **Play**, **Details** and, for a Field Notes pick, **Read article**, which opens that exact article. The chips along the
top, or **L1/R1**, move between the picks; when a pick's text is longer than the screen, move onto it and scroll with the
D-pad. The buttons stay in view. **B** goes back. Returning from Details or the article (B) brings you back to the same pick.

Each pick follows one plain rule, and its reason says which rule and what it used:
- **Pick up again**: a game you have played, bookmarked games first, then the most recently played. Its bookmark is shown.
- **From Field Notes**: a game that a magazine reference matches to exactly one available local copy, articles you haven't
  finished first. If several copies match, Tonight leaves that reference for you to choose from in the reader.
- **Familiar ground**: an unplayed game by the same developer as one of your five most-played games, or else in the same genre.
- **Something different**: an unplayed game on a system and in a genre your play history hasn't touched, or else one of the two.
- **Wild card**: today's random draw from the rest of your library.

When a rule has nothing to work with (no play history yet, no playable Field Notes game), that pick is labelled
**Discovery** and says why. It then tries Want to play, an unplayed game with an authored guide hook, the best-rated unplayed
game in your library's metadata, then any unplayed game. When the set is chosen, Hidden, Finished, Shelved, zero-byte
placeholders, detected Syncthing transfers and missing games are left out. Other copy methods may not be detected, so
finish copying before playing. Alternate indexed variants and repeated titles are skipped; a small library gets fewer than five.

There are no questions, settings or rerolls, and nothing is fetched. Your first visit each day saves a set on this device;
later visits keep it, even after a restart, re-index or more play. A visit that crosses midnight keeps its original date,
including trips to Details and back. Leave Tonight and open it again to get the new day's set.
A saved pick that becomes unavailable (removed, hidden, a placeholder, a detected Syncthing transfer or missing) stays in
place, dimmed with the reason, instead of being replaced. These file checks do not guarantee that its emulator is ready.

- If the day's picks can't be saved, Tonight says so instead of showing a set it could not keep. Free some storage on the
  device, then open Tonight again.
- While the library is still indexing, Tonight waits for it to finish. If nothing can be played, it says so and saves
  nothing, so a later visit that day can choose.
- The saved picks stay inside the app on this device: they are not on the card and not in backups.

## A game's page

Art, fit stars, hook, description, play time, your bookmark and (when supplied) control notes. **Play** launches with the
first emulator that accepts the request; **A** activates the focused control, initially Play. **Select / Launch with…**
opens the emulator list: choosing an installed one saves it for this game and launches it immediately. **Clear override**
returns the game to the normal order without launching.
The order tried is this game's choice → **Settings → Default launcher per system** → the system's Emulators list in
Library setup. A per-game or one-off choice never changes the system default. **Open with…** is tried only when explicitly
chosen or included in that list. If it is the first configured emulator, Android keeps asking. Only a normal launch that
falls through earlier configured emulators to Open with… can remember the selected app as a system default;
**Default launcher per system → Manifest default** clears it. Cancelling Android's chooser does not start a play session.
If nothing starts, a message says why.
If Sortie detects a return within about three seconds without a Home press and cannot see the emulator still running,
the page reports a quick exit next to **Play** for about twelve seconds (a short message elsewhere if you have left the page).
Sortie does not retry by itself.
**Other versions**, when shown, lists grouped variants; choosing **Play** beside one launches that file directly.

**Leave a note** (or **Edit bookmark** once there is one) opens the game's **bookmark**: one short text about where you are
and what to do next, such as the level, boss or save slot. Each game has one bookmark.
- **Save** replaces the previous bookmark. **Cancel / B** keeps it exactly as it was.
- **Clear**, then **Remove bookmark**, deletes it.
- Up to 2,000 characters; a longer draft shows its length and can't be saved until shortened. Nothing is cut off.
- If the card can't be written, the editor stays open with your draft and says so; **Save** tries again.

The bookmark shows on the game's page and on the companion screen, and Tonight's **Pick up again** favours bookmarked games.

**What it needs** records your own assessment: Pad OK, Pointer, Motion, Touch or Not set. This is not an automatic
compatibility test. The guide's control notes are shown beside it for reference and are never edited there. **Save**
keeps the choice; **Cancel / B** discards it. The compact controller badge also appears beside the game in grids and lists.

**After playing.** When a game you started from Sortie has closed, a small card at the bottom of Sortie's main screen can ask
*Leave a text note about where you are in the game for when you return?* It is not a dialog: it takes no focus and opens no
keyboard. **Leave a note** (or **L2**) opens that game's bookmark editor; **Not now** dismisses it, and so does launching
something else or leaving it for 45 seconds. It appears at most once per session, only on the main screen (never on the
companion screen, a built-in app, the switcher or a quick visit to Home while the game runs) and not when the game closed
within about three seconds of starting. Telling a closed game from a visit to Home needs Shizuku: without it, or whenever
Sortie can't be sure the game ended, no card appears and you leave bookmarks from the game's page. Turn the card off with
**Settings → Bookmarks → Offer a bookmark after playing**.

## The built-in curated guide

Sortie comes with short, curated notes about more than a thousand games: a hook, badges such as *hack*,
*Japanese only* or *long*, and sometimes control notes. This AI-assisted editorial content attaches to matching copies in
your library and appears on the game page, the companion screen and Tonight. No extra guide file is needed.

- Matching uses platform and names, not the game's contents. Common region, language, disc and revision labels are
  ignored; edition names help distinguish variants, and ambiguous matches are left out. A modified game with an unchanged
  retail filename can still receive retail notes. Many games have no curated note; that is expected.
- A **guide.json** in your Sortie folder takes priority. Entries classified as authored are kept exactly as supplied,
  including blank fields. For auto-classified entries, the built-in guide only fills missing hooks, control notes and
  badges; it never supplies fit stars or taste tags. These classifications describe precedence, not human authorship.
- Updates to the built-in guide arrive with app updates and apply at the next index pass (**Library setup → Re-index every
  system** runs one).
- Your bookmarks, controller assessments, verdicts, reference cards and clippings are stored separately and never change
  the guide's text, built in or on the card.

## Finished and Shelved

On a game's page, choose **Mark finished or shelved**. **Finished** is your completed shelf; **Shelved** is for games you have
set aside. Add an optional verdict (up to 280 characters), then **Save**. **Cancel / B** leaves the saved state alone.

Both folders always appear under **Home → Custom**, even when empty. A game can be on only one of these shelves, but its
favourite and Want to play status stay independent. **Change shelf or verdict** lets you switch shelves or revise the verdict;
choose **Neither → Take off shelf** to remove both the shelf status and verdict. Hidden games stay hidden, and a temporarily
missing game returns to its shelf when indexed again. Sortie never guesses completion from play time.

## Offline reference cards

Open **Reference cards** on a game's page to keep controls, maps, move lists or your own reminders:

- **New card** writes a titled Markdown note. **Import Markdown** copies a UTF-8 text file; **Import image** copies a PNG,
  JPEG, WebP or GIF from Android's file picker. Nothing is downloaded or linked to a temporary picker permission.
- **Edit** changes the title and text. Image cards can have notes beneath the picture. **Delete** asks before removing a card
  and its copied image; the original imported file is untouched.
- **L1/R1** changes cards. **D-pad** scrolls text or pans a zoomed image; **A** toggles image zoom and **L2/R2** zooms out/in.
  Touch supports scrolling, pinch/drag and double-tap zoom. **B** returns to the card list.
- While that game is running, tap **Reference card** or **Reference cards** on the companion (shown once the game has a card).
  Use its arrows to change cards and **Close** to return to Now playing. These are touch controls: controller focus returns
  to the game, so reading does not capture its buttons. Single-screen devices use the game page to read and edit cards.

Cards are short references, not a PDF or web browser. Markdown images/links are not fetched; import a picture as its own card.
Imports over 256 KiB of Markdown or 24 MiB per image are refused. Images are also limited to 16,384 pixels on either edge
and 64 million pixels total. Each game can hold up to 200 cards, with a 120-character title and 100,000 text characters per
card. Missing images show an explanation; text remains available.

## Older journal and notes (archive)

Earlier versions of Sortie had a weekly journal and, per game, *Where was I?* and *My mapping notes* fields. One bookmark per
game replaces them. When Sortie finds that older writing in its settings file (or in a backup being restored), it first
saves all of it, readable, in the **archive** folder inside the **sortie** folder:
- a Markdown file named **personal-notes-**, followed by a code, with every note in full and the journal entries with their
  dates, linked games and sessions, and the paths of their screenshots;
- a JSON file of the same name preserving the old note text and journal data.

Only after both files are written does Sortie save its settings without the old fields. Each *Where was I?* note becomes
that game's bookmark (a bookmark you already have is kept). Mapping notes and journal entries remain only in the archive:
open the Markdown file with any text editor or file manager. Journal screenshots stay untouched in **sortie/journal/media/**.
Your play history is not affected. If the archive can't be written (the card is full or read-only, for example), Sortie
keeps the old settings file as it is and refuses to save settings, export or restore a backup or import from Cocoon,
saying why, until the archive can be written.

## The other screen (companion)

While you browse, the companion follows the focused game: art, hook, description, your bookmark and play stats — read without
leaving the list. This also works when another game is running behind Sortie. When you return to gameplay, it shows that
game's timer, bookmark and the guide's control notes. With nothing selected or playing, it rotates through recommended unplayed games;
tapping one opens it.
The line below a game's title shows its platform, year, developer and genre when available, separately from the guide hook.
The curated blurb is a two-line preview here, with an ellipsis when it runs longer. Open the game's main page to read it in full.

- **+** (bottom-right): start any app on that screen.
- **Dot** (bottom-left): the dock. Tap to unfurl five slots up the left edge; the controller moves to them. A / tap an empty
  slot to pin an app, A / tap a pinned app to start it there, hold A / long-press to unpin, B folds it away.
- **Battery** (top-left) and, when Shizuku is ready, **bell** (top-right): tap the bell to open the notification shade.
- **Device stats** (if turned on in Settings): a thin strip down each side. Left: CPU and GPU load with their clocks, and
  RAM in use of the total. Right: CPU, GPU and battery temperatures in °C and the fan speed (AYN Thor; its sensor gives no
  unit, Sortie shows it as rpm). The panel narrows to make room, so nothing is covered.
- **Perf mode** (Settings → Device stats → *Companion screen shows* → *Perf*): the panel shows the Perf page (see the
  switcher below) instead of the game, live while you play on the other screen. Battery, bell, dock and **+** stay in their
  corners; swipe to see the details. The switcher, the app list from **+** and a magazine's contents still take the panel
  while they are open.

Touching the companion hands the controller back to the other screen a moment later.

## The switcher (Home key)

Shows the apps running on each screen. **A** brings an app to the front; **Select** or its **✕** closes it.
With two screens, **Y** moves it across and **Quick swap** exchanges the screens. **Open app…** starts an app;
**Sortie** returns to Home. On a single-screen device, cross-screen actions are left out.
With **Settings → Overlay over running games (optional)** on, it draws over running apps; off, or without overlay permission,
it uses a normal Sortie screen.
When opened over Sortie, the overlay leaves your current page or companion underneath; **B** returns there without raising
an old background app. This also holds after swapping screens: Home does not reset the browsing page or move navigation back.
With Shizuku available, Home also opens the switcher while reading a magazine or when no external app is running;
**Open app…** can start the first one. If Home does nothing, check **Settings → Diagnostics → Allow Sortie in Shizuku**;
reinstalling Sortie can require authorizing it again even while the Shizuku service is running.
Android Settings and other security-sensitive screens can hide third-party overlays. When Sortie detects that protection,
it opens the normal-screen switcher instead. If the overlay is still hidden, leave the protected screen with **Back**, or
turn off **Overlay over running games (optional)** in Settings. Granting overlay permission does not override Android's protection.
Choosing or moving a picture-in-picture player restores its existing task to fullscreen. Internal controller-focus changes
do not ask the player to enter PiP again. If you do not want an app to use PiP at all, disable its **Picture-in-picture**
permission in Android's app settings.
With **Device stats** set to *Switcher overlay* or *Both*, the overlay's title line also shows the same readings in one row.

**Perf page.** In the overlay switcher, **R1** (or **L1**, or the **Perf** chip on either screen) shows live performance
numbers instead of the app list: CPU load and clock, GPU load and clock, RAM, CPU, GPU and battery temperatures and the
fan, each large with a line of the last two minutes, then every core's clock and every thermal zone, grouped. It needs no
Device stats setting; the normal-screen switcher has no Perf page. **Up/Down** scrolls; **B**, **L1/R1** or the
**Apps** chip return to the app list with the same app selected. While a Perf page is shown Sortie reads every 2 seconds
(or every second, Settings → *Perf view refresh*); readings a device does not offer get no tile, and without Shizuku only
RAM and the battery temperature show. FPS and power draw are not shown.

## Settings

**Library and emulators** (systems, game folders, emulators, BIOS/save folders, re-index), **Default home app** (once you
have a system), layouts (home/browse grid, sideways grids, rows and columns, app tile density, header and hints), hero rotation,
the overlay switcher, **Bookmarks** (whether to offer a bookmark after playing; on by default), device stats, a default
launcher per system, the optional **Radial keyboard**, and under Help **Sortie manual** (this manual), **Backup and restore**,
**Import from Cocoon…** and Diagnostics. Custom smart folders, **guide.json**, logos and launch profiles use prepared files;
they do not require a computer, but have no in-app editor. The curated guide is built in.

**Header and hints (Home and browse)** sets how much room Home, system lists, folders, search and Apps give their header
and button hints: *Auto* (the default), *Expanded* or *Compact*; **A** cycles. *Auto* is compact when the window is short or
narrow (larger text counts), on any device, and returns to the full header when there is room again. *Expanded* always
shows the full header and hints. In compact mode the header folds into a thin strip once you move into the games or scroll
them: tap or pull down the strip, press **Up** on the first row, or use its **▾** button to bring it back (**▴** folds it
again). It stays open while there is nothing to browse. The hints shrink to one light row with up to three main buttons,
and **Controls** lists every command.

**Device stats → Show device stats** puts CPU, GPU, RAM, temperatures and the fan on the companion screen, the switcher
overlay, both, or nowhere (*Off*, the default); **A** cycles. **Device stats refresh** reads them every 2, 5 (the
default) or 10 seconds, only while they are on a screen that is on: hidden, paused or asleep, Sortie reads nothing.
**Perf view refresh** (1 or 2 seconds, default 2) is the pace while a Perf page is shown, and on two-screen handhelds
**Companion screen shows** switches the other screen between its usual panel and the Perf page. CPU and GPU load,
clocks, temperatures and the fan need Shizuku (Sortie reads them as the shell user, read-only); without it only RAM and
the battery temperature show. Readings a device does not offer are left out: the AYANEO Pocket DMG, for one, has no fan
reading.

**Which emulator starts a game.** A per-game **Launch with…** choice first; then the system's **Default launcher per
system** choice made here; otherwise the system's Emulators list from Library setup, in order, skipping emulators that
aren't installed. **Manifest default** clears the Settings choice so the list order applies again.

**Import from Cocoon…** reads the Cocoon launcher's folder (and, if you pick one, a Cocoon backup) and previews the systems,
favourites, hidden games, emulator choices, artwork, folders and play sessions it would add; nothing changes until you choose
**Import**, and Cocoon's own files are never modified. Your preferences and play history are saved as a safety copy first;
restoring it brings the preferences back but does not remove added systems, folders, copied artwork or imported sessions.
Existing systems keep their emulator defaults; new systems start with Cocoon's emulator, or an installed equivalent
RetroArch build when the requested build is absent (the preview explains the substitution). Installed does not guarantee
that its core or BIOS is ready. The preview distinguishes additions from entries already here. A changed library requires
a fresh preview, and a broken library configuration must be repaired before importing.
**Hide** keeps games out of folders and shelves with no unhide button yet. Imported folders appear in Home's **Custom** sort.

## Radial keyboard

An optional Android keyboard, not a global controller shortcut. It works in normal text fields, including Chrome and Sortie's
search and bookmarks. In **Settings → Radial keyboard**, choose **Enable radial keyboard…**, enable **Sortie radial keyboard** in
Android's settings, then return and use **Choose keyboard…** to select it. Android shows its standard warning for third-party
keyboards. Sortie does not enable or select it automatically.

The keyboard overlays the app with transparent space between translucent controls. It does not replace the editor with a
fullscreen text box. Android controls placement and may move a covered text field into view.

- **Left stick**: choose one of eight character groups. Each group shows its four characters in up/right/down/left order.
- **Right stick**: choose a character on the four cardinal directions, or an editing action on a diagonal.
- Right-stick diagonals: **up-left = Shift**, **up-right = Delete**, **down-left = symbols**, **down-right = Space**.
- **A / R1**: enter the selected character or perform the selected action. Selection stays when you release the stick.
- **L2**: hold for capitals. **R2**: hold for numbers/punctuation. Hold both for more symbols.
- **X**: delete; hold to repeat. **Y**: space; hold to repeat.
- **Start**: the field's action (Search, Go, Done, Next, etc.), or a newline in a multiline field.
- **B / Hide**: dismiss the keyboard. Tap B, or hold it to close the keyboard while the button is still down; this does not
  go back a page.

Touch also works: pick a left-hand group, then tap a character or action on the right. Touch Shift/symbols or activate their
diagonals with A to latch a layer; activate again to turn it off. A trigger's temporary layer lasts only while held.
The small middle button performs the current field action.

While the keyboard is visible, its controller controls take precedence over Sortie's browsing shortcuts: R2 changes the
keyboard layer rather than the game queue, and X deletes rather than swapping screens. New text fields clear held/latching
state; numeric fields start on the number layer. There is no prediction or typed-text history, and Sortie has no network
permission. For other languages or a conventional layout, tap **Switch keyboard** or use Settings → **Choose keyboard…**.

## Backup and restore

**Settings → Backup and restore → Export backup…** saves your preferences (including the bookmark setting), favourites, Want to play queue,
Finished/Shelved status and verdicts, custom home, dock, bookmarks, controller assessments (*What it needs*), reference-card
text, magazine clippings, completed play history and the Hangar companion (name, parts, salvage, bond) to a JSON file in a
location you choose. An unfinished session is not included.

**Restore from backup…** previews the contents before asking for confirmation. Quit any running game and return to Sortie first.
Restoring replaces preferences and the companion, but merges history: newer sessions stay, and restoring the same file twice does
not duplicate them. No play session earns the companion salvage twice, whichever device it was credited on. A backup made by an
older Sortie has no companion in it and leaves yours as it is. A backup made before bookmarks may still hold journal entries
and *Where was I?* or mapping notes: the preview says so, and restoring first saves that writing in **sortie/archive/** (see
Older journal and notes) and turns *Where was I?* notes into bookmarks. If that archive can't be written, nothing is restored.
A safety copy is saved first under **sortie/backups/** on the card. Restoring that copy recovers your previous preferences and
companion (his parts and salvage roll back, but sessions already credited never pay again); it cannot remove history merged
by a restore.

Some of your files are **not embedded in the JSON backup** and must be copied separately, keeping their paths inside the
**sortie** folder: reference images (**sortie/reference/**), the archive of older notes (**sortie/archive/**) and old journal
screenshots (**sortie/journal/media/**). Restoring the JSON alone keeps the reference-card text, but reference images are
unavailable until their files are copied back to the same relative paths; the older notes and journal screenshots exist
only in those folders.

Backups do not contain ROMs, BIOS files, artwork, **emulator saves** (each emulator keeps its own; back those up from the
emulator's folders), the game index (rebuilt from your folders), **playlog.jsonl**, or your library settings
(**device.json**, **systems.json**, **launchers.json** and other settings files). To keep a library you set up on the device,
copy the whole **sortie** folder shown in **Library setup → Where these settings live** as well.

Some state lives only inside the app: play-history statistics (Sortie's minutes and sessions come from its own database;
completed sessions are in the backup, **playlog.jsonl** is only a copy for computers and is not read back), magazine reading
positions (*Continue reading*, not exported), Tonight's saved picks, the remembered location of the **sortie** folder and the
game index. Android owns shortcut pins, permissions (All files access, Shizuku, Display over other apps), the keyboard choice
and the default home app; none of them are restored. Shortcut IDs remain in the backup, but missing shortcuts must be pinned
again from their source app.

**Updating Sortie** means opening a newer APK from the same sender and choosing *Update*: Android replaces the app and keeps
its data. **Uninstalling** deletes the app-only state above and Android's grants for Sortie; files in the **sortie** folder and
your games stay on storage. Export a backup first if you ever must uninstall, and never uninstall just because Android
refuses an update: ask whoever sent you the APK.

## Play log and saves

Starting a game, app or shortcut from the main launcher starts a session; cancelling the app chooser does not.
Opening a built-in app or an app beside a game from the companion does not replace that game's session.
When you return to Sortie, Shizuku lets it keep timing while the emulator's task remains; without Shizuku, or when the task
list can't be read, returning ends the session. A return within about three seconds without a Home press is treated as a
failed launch unless Sortie can see the emulator still running, and that attempt is not counted as played.
Recorded time includes idle time, not just active play. Minutes, session counts and "last played" feed Continue and Tonight.
The **💾 started** chip instead checks matching files in **Library setup → BIOS and save folders**; re-index to refresh it.
