# Sortie manual

Sortie is an offline, controller-first game launcher. Card contents (systems, folders, launchers, art and guide notes) are
written on the desktop and deployed. Preferences, personal game notes and play history live on the device.

## Controls

- **A** open · **B** back · **X** swap which screen has the controller (dual-screen devices)
- **Hold A** on anything: the context sheet (favourite, add to home, remove, launch with…)
- **Hold R1** on a game, app or shortcut: favourite. On a system or folder tile: jump to a random game in it
- **L1 / R1** (tap): previous / next system in lists; on Home: previous / next sort; in "By Console": previous / next console
- **R2** on a game (in any list, grid or on Home) or on a game's page: add to / remove from *Want to play*, a queue separate
  from favourites. The *Want to play* folder is always on **Home → Custom**; launching a game keeps it queued
- **Y** on Home: search. On a game's page: favourite
- **Select**: list ↔ grid; on a game's page: "launch with…" (pick the launcher once)
- **Home key**: the switcher (see below)
- **Swipe down from the top edge**: the status bar

## Home

**L1/R1** cycle the sorts: *Alphabetical*, *Game count*, *Generation* (grouped 8-bit → modern), *Custom* and *By Console*
(a strip of consoles with that console's games underneath). The first three sorts show emulation systems only.

- **Custom** always includes every smart folder and **Apps**, alongside your pinned systems, games, apps and shortcuts.
- **Continue** shows the last game you played and starts first on Custom; it can be moved like any other tile.
- Hold A on a system, game, app or shortcut and choose *Add to home*. On Custom, hold A → *Move* lifts a tile; the D-pad moves
  it, A drops it, B cancels. Existing pins keep their order. Smart folders, Apps and Continue can be moved but not removed.
- **Apps** lists installed apps and the shortcuts you have opted in to ("Browse all shortcuts…").
- **📖**, beside Settings, opens this manual directly. **Magazines** opens the offline reading shelf; **Journal** opens your week in games.

## Lists and grids

Each system or folder is a list or a grid (**Select** toggles). Grids can scroll sideways in rows — better on a wide top screen —
or downward in columns; Settings chooses, and rows/columns set the preferred tile size. On smaller screens, home and game
grids automatically use fewer rows or columns when necessary to keep tiles readable. Swapping screens does not change your
saved density or scroll direction; moving back restores the preferred density when it fits. Lists remain lists.
Your current page, back history, search, filters, scroll position and controller selection follow you across an X screen swap.

Small app and shortcut tiles automatically show only their icons instead of cramped labels. Larger tiles and list rows
keep their names; icon-only tiles still expose names to accessibility services. Adjust **Settings → App tiles per row**
to change the Apps grid density. The label cutoff adapts to tile width and your text size.

On a narrow Home screen, header actions move below the clock, sort choices scroll sideways (touch or D-pad), and controller
hints wrap as whole button/action pairs.

System and folder filter chips: **★★★★+** (fit 4 or better), **✍ notes** (guide or personal notes), **💾 started** (a save or state exists
on the card), **on device** (hide placeholders for games not copied yet). **🎲 Pick one** opens a random game from what is shown.
The title stays above these controls. On smaller screens, the controls wrap onto additional rows rather than shrinking.

## Search and filters

Search accepts a title, tag, genre, developer or description fragment. Text is optional: pick **System**, **Tags** or
**Genre** to explore without typing. Tags include the badges on game pages, including hack and translation flags.

- Pick several values in a filter to match **any** of them; different filters must **all** match.
- **More** offers favourites, Want to play, unplayed (never launched), fit ★★★★+ and **On device only** (on by default).
- **A / tap** toggles a picker value; **Done / B** closes the picker. Its counts describe the library, not the other active filters.
- Active filters appear below the buttons. Tap one to remove it; **Clear filters** keeps your search text.
- The game count includes all matches. More results load as you scroll; the initial page is not a search limit.
- **D-pad Down** from the search box focuses results and closes the keyboard, including after you close it yourself.
  With no results it goes to the filter buttons. **Up** from results reaches those buttons.
- Open a game and return with **B**: your text, filters and browsing position remain.

## Offline magazines

**Home → Magazines** opens curated recommendation issues: an editorial, contents, illustrated articles, gameplay and
mechanics explanations, reception and sources. Everything is bundled on the card; Sortie does not download anything.

- **A / tap** opens an issue or article. **B** goes article → contents → shelf → Home.
- In an article, **D-pad Up/Down** scrolls; **Left/Right** pages; **L1/R1** changes article.
- **A** in an article opens its listed game's library page, not the game itself. A recommendation is not proof the ROM is
  installed; if it is not indexed here, Sortie says so.
- **Continue reading** and per-article positions survive leaving the reader and restarting Sortie. An X screen swap keeps
  the open page. Reading memory is app-private, not included in the current preference/history backup.
- On two-screen devices, the other screen becomes a reading companion while an issue is open. **Sections** jumps to a
  heading in the current article and highlights your place; **Articles** opens another article in that issue. **Previous**
  and the folded **Next article** corner turn articles without losing their reading positions. The controls follow an
  **X** swap and return the controller to the reading screen after a touch. Backing out to the shelf restores the normal
  companion; single-screen devices keep the controls above.
- Copy a complete issue to **sortie/magazines/<issue-id>/** with **issue.json**, **articles/** and **media/** inside.
  Reopen Magazines after copying. Incomplete or unsupported issues show an explanation without hiding the others.
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

## A game's page

Art, fit stars, hook, description, play time and (when written) control notes. **A** launches with the first working launcher;
**Select** lets you pick a launcher for this game permanently. The order tried: this game's choice → the system default from
Settings → the manifest's order → "Open with…".

**Add notes / Edit notes** opens your own notes and controls:
- **Where was I?**: save slot, next objective or anything useful when returning.
- **What it needs**: Pad OK, Pointer, Motion, Touch or Not set. This is your assessment, not an automatic compatibility test.
- **My mapping notes**: your button bindings or emulator setup.

**Save** writes to the card; **Cancel / B** discards the draft. **Clear all**, then Save, removes your entry. These notes do not
change the desktop guide. The compact controller badge also appears beside the game in grids and lists.

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
- While that game is running, tap **Reference cards** on the companion. Use its arrows to change cards and **Close** to return
  to Now playing. These are touch controls: controller focus returns to the game, so reading does not capture its buttons.
  Single-screen devices can read and edit the same cards on the game page.

Cards are short references, not a PDF or web browser. Markdown images/links are not fetched; import a picture as its own card.
Imports over 256 KiB of Markdown or 24 MiB per image are refused. Each game can hold up to 200 cards, with 100,000 text
characters per card. Missing images show an explanation; text remains available.

## Weekly gaming journal

**Home → Journal** shows Monday–Sunday weeks from Sortie's recorded play history. **L1/R1** or the week buttons browse;
**This week** returns to today. Sessions crossing midnight are split across the relevant days. Unfinished sessions and
sessions with an unknown length are labelled and add no invented play time.

- **A / tap** a day starts a dated note; choose a recorded session to write about that session. **New note** works without
  any play history. You can change the date, optionally link a game, and write what happened or what you want to try next.
- **Add screenshots…** lets you explicitly choose up to 12 pictures per note. Sortie copies those pictures onto the card;
  it does not capture the screen or scan your gallery. Large images are resized to keep them practical on the handheld.
- Open an attached picture to pan/zoom; **A** toggles zoom, **L2/R2** adjusts it, and **B** closes the viewer.
- **Save** commits the note and opens its week, including future dates. **Cancel / B** discards the draft and newly imported
  attachments. Editing or deleting a journal note does not change the recorded session. Deletion requires confirmation.

Notes, references, shelf verdicts and clippings are yours, stored separately from the desktop-authored game guide.

## The other screen (companion)

While you browse, the companion follows the focused game: art, hook, description, notes and play stats — read without leaving
the list. This also works when another game is running behind Sortie. When you return to gameplay, it shows that game's timer,
**Where was I?** reminder and control notes. With nothing selected or playing, it rotates through recommended unplayed games;
tapping one opens it.
The line below a game's title shows its platform, year, developer and genre when available, separately from the guide hook.

- **+** (bottom-right): start any app on that screen.
- **Dot** (bottom-left): the dock. Tap to unfurl five slots up the left edge; the controller moves to them. A / tap an empty
  slot to pin an app, A / tap a pinned app to start it there, hold A / long-press to unpin, B folds it away.
- **Battery** (top-left) and **bell** (top-right): the bell opens the notification shade.

Touching the companion hands the controller back to the other screen a moment later.

## The switcher (Home key)

Shows what is running on each screen. **A** brings an app to the front, **Y** moves it to the other screen, **✕** closes it,
*Quick swap* exchanges the two screens, *Open app…* starts something on a freed screen, *Sortie* returns here. With the overlay
switcher on (Settings) it draws over the running games; off, it opens as a normal Sortie screen.
When opened over Sortie, the overlay leaves your current page or companion underneath; **B** returns there without raising
an old background app. This also holds after swapping screens: Home does not reset the browsing page or move navigation back.
Android Settings and other security-sensitive screens can deliberately hide third-party overlays. Leave that screen with
**Back** first, or turn off *Overlay switcher* in Sortie's Settings to use the normal-screen version. Sortie respects this
Android protection; granting overlay permission does not override it.
Choosing or moving a picture-in-picture player restores its existing task to fullscreen. Internal controller-focus changes
do not ask the player to enter PiP again. If you do not want an app to use PiP at all, disable its **Picture-in-picture**
permission in Android's app settings.

## Settings

Layouts (home/browse grid, sideways grids, rows and columns, app tile density), hero rotation, the overlay switcher, a default
launcher per system, the optional **Radial keyboard**, **Backup and restore**, Diagnostics and this manual.
Card contents and launcher definitions stay desktop-authored.

## Radial keyboard

An optional Android keyboard, not a global controller shortcut. It works in normal text fields, including Chrome and Sortie's
search and notes. In **Settings → Radial keyboard**, choose **Enable radial keyboard…**, enable **Sortie radial keyboard** in
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

In Settings, **Export backup…** saves your preferences, favourites, Want to play queue, Finished/Shelved status and verdicts,
custom home, dock, game notes, reference-card text, magazine clippings, journal entries and completed play history to a JSON
file in a location you choose. An unfinished session is not included.

**Restore from backup…** previews the contents before asking for confirmation. Quit any running game and return to Sortie first.
Restoring replaces preferences, but merges history: newer sessions stay, and restoring the same file twice does not duplicate them.
A safety copy is saved first under **sortie/backups/** on the card. Restoring that copy recovers your previous preferences;
it cannot remove history merged by a restore.

Reference images and journal screenshots are **not embedded in the JSON backup**. Copy **sortie/reference/** and
**sortie/journal/media/** alongside it to preserve those attachments. Restoring the JSON alone keeps the text and metadata,
but images are unavailable until their files are copied back to the same relative paths.

Backups do not contain ROMs, artwork, emulator saves or desktop manifests. Android shortcut pins, permissions and the default
home app are not restored. Shortcut IDs remain in the backup, but missing shortcuts must be pinned again from their source app.

## Play log and saves

Every launch is a session; sessions end when the game's window is gone. Minutes, session counts and "last played" feed the
*Continue* tile, the *Recently played* / *Play next* folders and the **💾 started** chip (which reads the card's save folders).
