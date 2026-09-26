# Ponas — instructions for Claude

Ponas is a kid-tablet shell app. The Android APK (`android/`) is installed once. Games are
self-contained HTML/JS folders in `games/<id>/`. Every push to `main` that touches `games/**`
runs `.github/workflows/pages.yml`, which builds `manifest.json` and publishes to
https://eb07-lab.github.io/ponas-app/. The tablet syncs from there within ~15 min, or right away
from Parent menu → Refresh. **Game changes never need a new APK.**

The owner (Egle) often works from her phone. Usually the request is "add/change a game in
games/<id> where …": do it, run the checks below, and commit straight to `main`.

## Hard rules

- **Only touch `games/<id>/` when adding or editing games. Never edit `android/` unless asked.**
  Don't edit `.github/` or `tools/` unless asked either.
- **Never hand-edit or commit `manifest.json`.** CI generates it (it's in .gitignore).
- Ask before adding permissions, dependencies or native libraries to the Android app, or before
  loosening any of the limits below.

## The device (hard limits)

- 7" budget tablet, Allwinner A133 (4× Cortex-A53, weak GPU), **2 GB RAM with ~1.1 GB free**,
  Android 14 Go (low_ram: background apps get killed), **32-bit only (armeabi-v7a)**.
- No Google Play Services, Play Store or Chrome. Games run in Android System WebView 151.
- Screen: **WebView viewport ≈ 640×375 CSS px, landscape**. Touch only.
- Wi‑Fi only, often offline. Everything must work offline after the first sync.
- The player is a small child who **may not be able to read**.

## Game rules (every game)

- Folder: `games/<id>/` where `<id>` is lowercase letters, digits and dashes (e.g. `spalvos`).
  Contents: `index.html`, `meta.json`, `icon.png`, plus any js/css/images/sounds.
- `meta.json`:
  `{ "title": "Spalvos", "icon": "icon.png", "color": "#FFB300", "order": 20, "enabled": true }`
  - `title`: short Lithuanian word (the tile label). `color`: bright `#RRGGBB` tile background.
  - `order`: tile position (lower = first). Existing: `balionai` = 10.
  - `enabled: false` hides the game from the tablet without deleting it.
- `icon.png`: **exactly 256×256 PNG** (transparent background is fine; the tile colour shows
  behind it). One big, simple picture — the child recognises the game by it. Make it with Python
  Pillow (`pip install pillow --break-system-packages`) or ImageMagick.
- **Landscape, fits 640×375 with no scrolling**, `user-scalable=no`. Easiest: design inside a
  fixed 640×375 `#stage` and scale it (see `templates/game/index.html` and `games/balionai/`).
- **Touch targets ≥ 80 px.** Use `pointerdown`, `touch-action: none`, no text selection.
- **No reading required**: pictures, sounds, animations. Any text is Lithuanian. Don't rely on
  speechSynthesis (the tablet probably has no Lithuanian voice). Short recorded prompts
  (mp3/ogg/m4a in the game folder, decoded with WebAudio) are fine.
- **Fully self-contained**: no CDNs, web fonts, analytics or any network calls. The app blocks
  all other origins anyway, and `tools/build-manifest.mjs` fails on external URLs.
- Vanilla JS. A tiny library only if it's vendored into the game folder.
- **Under 2 MB per game** (the build fails above that). Compress images and sounds.
- Light animation only: animate `transform`/`opacity`, a few elements at a time, no big canvases
  redrawn every frame, no blur/filter effects.
- **Sound through WebAudio, unlocked on the first tap** (a big ▶ start button works well).
  Suspend the AudioContext on `visibilitychange`.
- **Progress in localStorage with an `<id>:` key prefix** (all games share one origin).
- **No ads, outside links, tracking, or timers that pressure the child.** Mistakes get a gentle
  sound and another try, never a penalty.
- File names: only `A-Z a-z 0-9 . _ - /`.

## Adding a game (checklist)

1. `cp -r templates/game games/<id>` and replace `GAME_ID` / `GAME_TITLE`.
2. Build the game; draw `icon.png` (256×256).
3. `node tools/build-manifest.mjs` → must print `✓ <id> …` and no errors.
4. Test in a 640×375 viewport: `npx serve games`, then the browser's responsive mode set to
   640×375 (with touch emulation). Or use Playwright headless if there's no browser. Check:
   no scrolling, no console errors, no network requests, sound after the first tap.
5. Commit to `main` with a short message (e.g. `games/spalvos: add colour matching game`) and push.
6. Tell Egle: the tile appears on the tablet within ~15 min, or right away via Parent menu
   (hold the top-right corner for 3 s, PIN) → Refresh games now. She can also try the game in
   a phone browser at https://eb07-lab.github.io/ponas-app/ once the "Publish games" action is green.

## Repo map

- `games/<id>/` — the games (edit these).
- `templates/game/` — starting point for a new game (not published).
- `tools/build-manifest.mjs` — validates games and writes manifest.json (Node, no deps).
- `.github/workflows/pages.yml` — publishes games to GitHub Pages.
- `.github/workflows/android.yml` — builds the APK (only when `android/**` changes or on `v*` tags).
- `android/` — the Kotlin shell app (HomeActivity, GameActivity, SyncManager, ParentMenu).
  Games load from `https://appassets.androidplatform.net/games/<id>/index.html`.
