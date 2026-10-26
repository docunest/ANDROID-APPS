# Ponas — instructions for Claude

Ponas is a kid-tablet app. The Android APK (`android/`) is installed once and is also the tablet's
home screen. Games are small HTML/JS folders in `games/<id>/`. Pushing to `main` runs
`.github/workflows/pages.yml`, which validates every game and publishes them to
https://eb07-lab.github.io/ponas-app/ (a browser launcher plus `manifest.json`).

On the tablet, the parent (Egle) picks which games to install. The picker is behind a PIN.
Installed games update themselves: within ~15 min, or right away with the parent menu → Refresh.
**Game changes never need a new APK.**

Egle often works from her phone. The usual request is "add/change a game in games/<id> where …".
Do it, run the checks below, commit straight to `main` and push.

## Hard rules

- **Adding or editing games: only touch `games/<id>/`.** Never edit `android/`, `sdk/`,
  `tools/` or `.github/` unless asked.
- **Never hand-edit or commit `manifest.json`, `_site/` or `_shots/`.** They are generated and
  git-ignored.
- **Don't put `ponas.js` or `game.json` in a game folder.** The build adds them.
- Ask before: adding Android permissions, dependencies or native libraries; adding a network
  host (only `ntfy.sh` is allowed); loosening any limit below.

## The device (hard limits)

- 7" budget tablet: Allwinner A133 (4× Cortex-A53, weak GPU), **2 GB RAM with ~1.1 GB free**,
  Android 14 Go (background apps get killed), **32-bit only**.
- No Google Play Services, Play Store or Chrome. Games run in Android System WebView 151.
- **Viewport: about 640×375 CSS px in landscape, or 375×640 in portrait.** The tablet can be
  turned either way, and games must work in both. Touch only.
- Wi‑Fi only, often offline. Games must work offline once installed, except the multiplayer part.
- The player is a small child who **may not be able to read**.

## How a game runs

- On the tablet, each game opens in **its own fresh browser process**, which is thrown away
  when the game closes. So there's no leftover memory, timers or sound between games, and there's
  nothing to clean up yourself.
- Each game has **its own origin** on the tablet:
  `https://<id>.appassets.androidplatform.net/games/<id>/index.html`. Its localStorage is
  private to it and survives updates.
- On GitHub Pages, all games share one origin. That's why storage keys are prefixed; `Ponas.save`
  does this for you.
- The only network access is `https://ntfy.sh`, and only for games that list it in `meta.json`.
  Everything else is blocked, both by the app and by a Content-Security-Policy.

## The shared kit: `sdk/ponas.js`

Every game **must** include it, before the game's own script:

```html
<script src="ponas.js"></script>
```

It automatically adds these, so **don't build your own**:
- an orange **pause** button (top-left) and a **close** button (top-right), always visible;
- a big green **▶ start overlay** (the first tap, which also unlocks sound);
- a **pause overlay** with a big ▶ to resume. It also appears when the app goes to the
  background. CSS animations freeze while paused.

**Keep the top 72 px of the screen free of game controls**, because the buttons sit there. A
decoration or a score in the middle of the top strip is fine.

API:

| Call | What it does |
|---|---|
| `Ponas.onStart(fn)` | runs once after the ▶ tap. Start prompts, music and timers here. You can draw the first screen before it. |
| `Ponas.onPause(fn)` / `Ponas.onResume(fn)` | stop and restart your timers and loops. `Ponas.paused` is true while paused, so ignore taps then. |
| `Ponas.save(key, value)` / `Ponas.load(key, fallback)` / `Ponas.clear()` | JSON in localStorage, stored as `<id>:<key>`. Use it for progress and settings. |
| `Ponas.tone(freq, {delay, dur, type, vol})` | a quick beep through WebAudio. It stays silent before the first tap and while paused. |
| `Ponas.audio()` | the shared AudioContext, for your own sounds (noise, decoded mp3/ogg). It's `null` before the first tap. |
| `Ponas.onResize(fn(w, h, orientation))` / `Ponas.orientation()` | for layouts CSS can't handle. |
| `Ponas.close()` | leave the game. The close button already calls this. |
| `Ponas.net.channel(room?)` | multiplayer over ntfy.sh (see below). |
| `Ponas.id`, `Ponas.inApp` | the game id; `inApp` is true on the tablet and false in a browser. |

## Game rules (every game)

- **Folder:** `games/<id>/`. `<id>` uses only lowercase letters, digits and dashes (for example
  `spalvos`), because it becomes part of a web address. Contents: `index.html`, `meta.json`,
  `icon.png`, plus any js/css/images/sounds. File names use only `A-Z a-z 0-9 . _ - /`.
- **`meta.json`:**
  ```json
  { "title": "Spalvos", "icon": "icon.png", "color": "#FFB300", "order": 40, "version": "1", "enabled": true }
  ```
  - `title`: a short Lithuanian word (the tile label).
  - `color`: a bright `#RRGGBB` tile background.
  - `order`: tile position, lower comes first. Taken so far: balionai 10, piesimas 20, linkejimai 30.
  - `version`: **bump it when you change a game** ("1" → "2"). The parent sees it on the install
    screen. Updates are detected by content hash anyway.
  - `enabled: false` hides the game from the install list. It stays on tablets that already
    have it.
  - `"network": ["ntfy.sh"]` is needed only if the game uses `Ponas.net`.
- **`icon.png`:** exactly **256×256 PNG**. A transparent background is fine, because the tile
  colour shows behind it. One big, simple picture: the child recognises the game by it. Draw it
  with Python Pillow (`pip install pillow --break-system-packages`) at 4× size and downscale,
  then look at the result.
- **Both orientations:** the game must fit 640×375 **and** 375×640 with no scrolling. Use flexbox
  or grid with `vmin`-based sizes, and switch the layout with `@media (orientation: portrait)`. See
  `templates/game/` and `games/balionai/`. Include `user-scalable=no` in the viewport meta tag.
- **Touch targets ≥ 80 px.** Use `pointerdown` (not click), call `e.preventDefault()`, and
  don't depend on text selection or hover.
- **No reading required:** use pictures, sounds and animation. Any text is Lithuanian. Don't rely
  on `speechSynthesis`, since the tablet probably has no Lithuanian voice. Short recorded prompts
  (mp3/ogg/m4a in the game folder, played with `Ponas.audio()`) are fine.
- **Self-contained:** no CDNs, web fonts, analytics or direct network calls. `fetch('https://…')`,
  `new WebSocket(…)` and `new EventSource(…)` in game code all fail the build. Vanilla JS only; a
  tiny library is allowed only if it's vendored into the game folder.
- **Under 2 MB per game** (the build fails above that). Compress images and sounds.
- **Light on the weak GPU:** animate only `transform` and `opacity`, and only a few elements at
  once. No full-screen canvas redrawn every frame, no `filter`/blur on large areas. Clear your
  timers in `onPause`.
- **Kind to the child:** no ads, no outside links, no tracking, no countdowns or timers that
  pressure. A mistake gets a soft sound and another try, never a penalty or a "game over".

## Multiplayer (`Ponas.net`, ntfy.sh)

```js
var chan = Ponas.net.channel();            // topic "bimmer-car-93204-lit-<id>"; channel('x') → "…-<id>-x"
chan.on(function (data, msg) { … });       // messages from OTHER devices (your own are filtered out)
chan.onStatus(function (online) { … });    // show a small online indicator
chan.send({ pic: 'heart' });               // any JSON, under ~3.8 KB
```

- Set `"network": ["ntfy.sh"]` in `meta.json`.
- The second player is usually Egle's phone, running the same game from the Pages site in a
  browser.
- **Limits of the free ntfy.sh:** about 60 messages in a burst, then about 1 message every 5 s per
  device. That's good for turn-based games and "send a picture"; not for real-time action.
  Throttle sends (see `games/linkejimai`: at most one every 3 s, and extra taps only animate
  locally).
- Topics are **public**: anyone who knows the name can read and write them. Never send names,
  photos or anything personal. Ignore messages you don't understand.
- The game must still be playable, or at least calm, when offline.

## Adding a game (checklist)

1. `cp -r templates/game games/<id>`, then replace `GAME_ID` / `GAME_TITLE` and pick `color` and
   `order`.
2. Build the game in `index.html`, plus extra js/css files if it's large. Draw `icon.png`
   (256×256).
3. `node tools/build-site.mjs`. It must print `✓ <id> …` and no errors.
4. Test in both orientations:
   `npm i --no-save playwright && node tools/smoke-test.mjs <id>`. Then **look at**
   `_shots/<id>-landscape.png` and `_shots/<id>-portrait.png`. Fix overlaps with the top
   buttons, anything cut off, and tiny targets. In a real browser: `npx serve _site`, then use
   responsive mode at 640×375 and 375×640 with touch.
5. Commit to `main` with a short message (for example `games/spalvos: add colour matching game`),
   then push. Don't commit `_site/`, `_shots/` or `node_modules/`.
6. Tell Egle:
   - it's live at https://eb07-lab.github.io/ponas-app/games/<id>/ once the "Publish games"
     action is green (about 1 min);
   - on the tablet, install it from the ⬇ button (bottom-right of the home screen, PIN) → Install;
   - updates to games that are already installed arrive within ~15 min, or right away with
     parent menu → Refresh.

## Repo map

- `games/<id>/`: the games (edit these).
- `templates/game/`: starting point for a new game (not published).
- `sdk/ponas.js`: the shared kit (pause/close, overlays, storage, sound, multiplayer). Changing it
  updates every game.
- `tools/build-site.mjs`: validates games and builds `_site/` (launcher, manifest.json,
  games + ponas.js + game.json). Node only, no dependencies.
- `tools/smoke-test.mjs`: headless browser test in both orientations (needs Playwright).
- `.github/workflows/pages.yml`: publishes to GitHub Pages.
- `.github/workflows/android.yml`: builds the APK (only when `android/**` changes or on `v*` tags).
- `android/`: the Kotlin app. HomeActivity (tiles), InstallActivity (parent picks games),
  GameActivity (one game per process), SyncManager (downloads/updates), ParentMenu.
