# Sortie manual

Sortie is an offline, controller-first game launcher. Everything about *what* is on the card (systems, folders, launchers, art,
notes) is written on the desktop and deployed; everything about *how you use it* lives here. The manual covers the device side.

## Controls

- **A** open · **B** back · **X** swap which screen has the controller (dual-screen devices)
- **Hold A** on anything: the context sheet (favourite, add to home, remove, launch with…)
- **Hold R1** on a game, app or shortcut: favourite. On a system or folder tile: jump to a random game in it
- **L1 / R1** (tap): previous / next system in lists; on Home: previous / next sort; in "By Console": previous / next console
- **Y** on Home: search. On a game's page: favourite
- **Select**: list ↔ grid; on a game's page: "launch with…" (pick the launcher once)
- **Home key**: the switcher (see below)
- **Swipe down from the top edge**: the status bar

## Home

Systems and smart folders as tiles. **L1/R1** cycle the sorts: *Alphabetical*, *Game count*, *Generation* (grouped 8-bit → modern),
*Custom* and *By Console* (a strip of consoles with that console's games underneath).

- **Continue** is always first: the last game you played.
- **Custom** is yours: hold A on any system, folder, game, app or shortcut anywhere and choose *Add to home*. On the custom home,
  hold A → *Move* lifts a tile; the D-pad moves it, A drops it, B cancels.
- **Apps** lists installed apps and the shortcuts you have opted in to ("Browse all shortcuts…").

## Lists and grids

Each system or folder is a list or a grid (**Select** toggles). Grids can scroll sideways in rows — better on a wide top screen —
or downward in columns; Settings chooses, and rows/columns set the tile size.

Filter chips at the top: **★★★★+** (fit 4 or better), **✍ notes** (hand-written notes), **💾 started** (a save or state exists
on the card), **on device** (hide placeholders for games not copied yet). **🎲 Pick one** opens a random game from what is shown.

## A game's page

Art, fit stars, hook, description, play time and (when written) control notes. **A** launches with the first working launcher;
**Select** lets you pick a launcher for this game permanently. The order tried: this game's choice → the system default from
Settings → the manifest's order → "Open with…".

## The other screen (companion)

While you browse, the bottom screen mirrors the selected game: art, hook, description, notes and play stats — read without
leaving the list. With nothing selected it rotates through recommended unplayed games; tapping one opens it. While a game runs,
it shows what is playing, how long, and its control notes.

- **+** (bottom-right): start any app on that screen.
- **Dot** (bottom-left): the dock. Tap to unfurl five slots up the left edge; the controller moves to them. A / tap an empty
  slot to pin an app, A / tap a pinned app to start it there, hold A / long-press to unpin, B folds it away.
- **Battery and bell** (top-right): the bell opens the notification shade.

Touching the companion hands the controller back to the other screen a moment later.

## The switcher (Home key)

Shows what is running on each screen. **A** brings an app to the front, **Y** moves it to the other screen, **✕** closes it,
*Quick swap* exchanges the two screens, *Open app…* starts something on a freed screen, *Sortie* returns here. With the overlay
switcher on (Settings) it draws over the running games; off, it opens as a normal Sortie screen.

## Settings

Layouts (home/browse grid, sideways grids, rows and columns), hero rotation, the overlay switcher, a default launcher per
system, Diagnostics and this manual. Card contents, directories and launcher definitions are deployed from the desktop.

## Play log and saves

Every launch is a session; sessions end when the game's window is gone. Minutes, session counts and "last played" feed the
*Continue* tile, the *Recently played* / *Play next* folders and the **💾 started** chip (which reads the card's save folders).
