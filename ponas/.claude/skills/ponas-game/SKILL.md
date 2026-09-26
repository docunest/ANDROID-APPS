---
name: ponas-game
description: Create or change a small learning game for the Ponas kid tablet (repo eb07-lab/ponas-app) — Lithuanian, no reading needed, offline, built on sdk/ponas.js, validated, smoke-tested in both orientations and pushed to main.
---

# Make a Ponas game

Ponas is a home screen for a young child's tablet. Every tile is a small offline HTML/JS game from
`games/<id>/`. A push to `main` publishes it to GitHub Pages, and the tablet installs/updates it
from there. The player is a small child who **may not read yet**; any words are **Lithuanian**.
The parent often asks from her phone, so keep the reply short and give her the link to try it.

Repo rules live in `CLAUDE.md` — they win over anything here. This skill is the workflow.

## 1. Understand the ask (don't stall)

From the request, settle: the learning idea (counting, colours, letters, shapes, sounds, animals…),
the one action the child repeats (tap / drag / draw), what "right" and "wrong" feel like, and a
short Lithuanian title. If something is unclear, pick the simplest child-friendly version, say in
one line what you picked, and build it — a game is cheap to change.

Pick an `id`: lowercase Lithuanian word without diacritics, dashes allowed (`spalvos`,
`gyvunai`, `raides`). The `title` in meta.json keeps diacritics (`Gyvūnai`).

## 2. Game design rules (what makes these games work for a small child)

- **No reading required.** Instructions are shown or heard, never written. Numbers as dots,
  colours as colours, letters as big glyphs with a sound. Text on screen only where the text *is*
  the lesson (a letter), and then big.
- **One idea per screen, 2–4 big choices.** Targets ≥ ~25vmin, generous spacing, nothing tiny.
- **Always reacts.** Right → sparkle + happy tones + small reward. Wrong → gentle shake + soft
  "oops" tone, never a penalty, never a game over, no timers that pressure.
- **Self-explaining.** If the child is idle ~10–12 s, replay the prompt/hint (max 2 times per round).
  A tap on the prompt replays it too.
- **Rewards:** a row of 5 stars between the pause and close buttons; 5 stars → a short
  celebration (fanfare, things fly away), then carry on. Save progress with `Ponas.save`.
- **Gentle difficulty ramp** from a saved counter (e.g. Balionai: numbers 1–3, then up to 6 as
  `correct` grows).
- **Sounds** with `Ponas.tone` / `Ponas.audio()` (Web Audio, synthesized). No speech synthesis, no
  external audio. Recorded audio only if the parent supplies files, kept small.
- Bright flat colours, rounded shapes, soft shadows, light background; CSS/SVG drawing, no images
  from the internet. Emoji are OK as pictures only if they render on Android WebView (keep it to
  common ones) — prefer drawn shapes.

## 3. Technical rules (the build rejects the game otherwise)

- Start by copying `templates/game/` to `games/<id>/`. Look at `games/balionai/` as the reference
  game (split into `index.html` + `game.js`).
- `index.html` has the viewport meta and `<script src="ponas.js"></script>` **before** the game
  script. Never copy `ponas.js` or create `game.json` in the folder — the build adds them.
- **Keep the top 72px free** — `ponas.js` puts the orange pause (left) and close (right) buttons
  there. Game area: `position:fixed; top:72px; left:0; right:0; bottom:0`.
- **Both orientations, no scrolling.** Size with `vmin`/`min()`, flex row in landscape, column in
  `@media (orientation: portrait)`. Use `Ponas.onResize` for canvas games.
- Input: `pointerdown` with `e.preventDefault()`; ignore input when `Ponas.paused` or during the
  "busy" animation after a correct answer.
- Lifecycle: draw the first round immediately (it sits behind the ▶ overlay), but start sounds,
  timers and hints in `Ponas.onStart`. Stop all timers in `Ponas.onPause`, restart in
  `Ponas.onResume`. Keep every `setTimeout` in a list so it can be cleared.
- **Offline:** no URLs to other sites anywhere (no CDN, fonts, images, fetch). Everything lives
  in the game folder. Multiplayer only via `Ponas.net.channel()` and then
  `"network": ["ntfy.sh"]` in meta.json (messages are public — never personal data; turn-based
  or "send a picture" only, ~1 msg / 5 s).
- Whole game folder < 2 MB (aim for well under 200 KB). File names: `A-Z a-z 0-9 . _ - /`.
- `meta.json`: `title`, `icon: "icon.png"`, `color` (`#RRGGBB`, the tile colour), `order`
  (after the existing games unless told otherwise), `version: "1"`, `enabled: true`.
  **Changing an existing game: bump `version`.**
- Plain ES5-style JS in an IIFE with `'use strict'` (like the existing games) — the tablet's
  WebView may be old-ish; no modules, no build step, no dependencies.

## 4. Icon (`icon.png`, exactly 256×256 PNG)

Draw it as an SVG that shows the game at a glance (the main object, no words), on a transparent
or rounded-square background that suits the tile `color`. Render it to PNG with the preinstalled
Chromium, e.g.:

```js
// scratch script, not committed
import { chromium } from 'playwright';
const b = await chromium.launch({ executablePath: process.env.CHROMIUM });
const p = await b.newPage({ viewport: { width: 256, height: 256 } });
await p.setContent(`<body style="margin:0;background:transparent">${svg}</body>`);
await p.screenshot({ path: 'games/<id>/icon.png', omitBackground: true });
await b.close();
```

Run it from the repo root after `npm i --no-save playwright` (so `playwright` resolves), with
`CHROMIUM=$(ls -d /opt/pw-browsers/chromium-*/chrome-linux/chrome | head -1)`. Then open the PNG
with Read and look at it.

## 5. Check it

```sh
node tools/build-site.mjs                                  # must print ✓ for the game
npm i --no-save playwright                                 # once per session
CHROMIUM=$(ls -d /opt/pw-browsers/chromium-*/chrome-linux/chrome | head -1) node tools/smoke-test.mjs <id>
```

Both `landscape` and `portrait` must pass. **Then open `_shots/<id>-landscape.png` and
`_shots/<id>-portrait.png` with Read and actually look**: nothing under the pause/close buttons,
nothing cut off, targets big, it's obvious what to do. Fix and re-run until it's right. For
logic that the smoke test can't see (right/wrong answers, star reward, level-up), drive it with a
short Playwright script in the scratchpad.

## 6. Ship it

Commit only `games/<id>/` (and anything the parent explicitly asked to change) straight to `main`
with a short message like `Add game: Spalvos (tap the balloon of the named colour)`. Never commit
`_site/`, `_shots/`, `node_modules/`. Push. (If the push is rejected, fetch and rebase first.)

Reply to the parent in a few lines:
- what the game is and how the child plays it (one or two sentences);
- try it: https://eb07-lab.github.io/ponas-app/games/<id>/ (live ~1 min after **Publish games** is green);
- new game → on the tablet ⬇ → PIN → **Install**; changed game → updates itself within ~15 min
  or Parent menu → **Refresh games now**.

Don't touch `android/`, `sdk/ponas.js`, `tools/` or the workflows unless explicitly asked — a
change to the SDK affects every game, and the app itself needs a new APK.
