# Ponas

A home screen for a stripped-down kid tablet: big picture tiles, and each tile is a small
HTML/JS game from this repo. You install the APK once. On the tablet you choose which games to
install, and installed games then update themselves whenever you push changes to GitHub.

```
phone / Claude ──push──▶ GitHub main ──Actions──▶ GitHub Pages ──────────▶ tablet
                          games/ sdk/              launcher + manifest     install (PIN) / auto-update
```

- **App** (`android/`): Kotlin with plain Android Views. Its only dependencies are
  `androidx.core` and `androidx.webkit`, and it needs only two permissions: `INTERNET` and
  `ACCESS_NETWORK_STATE`.
  - **Isolation:** each game runs in its own fresh browser process, which is ended when the game
    closes, so nothing leaks from one game into the next.
  - **Own storage:** each game has its own origin, so its saved progress stays private to it.
  - **Network:** the only site a game can reach is ntfy.sh, and only if the game asks for it.
  - **Screen:** works in both orientations.
- **Games** (`games/<id>/`): self-contained pages that work offline.
- **Shared kit** (`sdk/ponas.js`): gives every game the same orange pause button, close button,
  ▶ start and resume overlays, save/load, sound, and ntfy multiplayer.
- **Site build** (`tools/build-site.mjs`): checks every game against the rules and builds the
  Pages site. That's the launcher, `manifest.json`, and each game with `ponas.js` and its own
  `game.json`. The tablet re-downloads a game only when its content hash changes.

**Pages:** https://eb07-lab.github.io/ponas-app/ shows every game, with the same tiles as the
tablet, for playing in any browser. https://eb07-lab.github.io/ponas-app/manifest.json is the
catalog the tablet reads.

---

## 1. One-time setup

### 1a. Create the signing keystore

Android only installs an update if it is signed with **the same key** as the installed app.
You create that key once, and CI uses it for every build.

`keytool` comes with a Java JDK, and your Mac doesn't have one. To get it:
`brew install --cask temurin@17` installs the JDK. It's only needed for this step, and you can
uninstall it afterwards.

```sh
cd ~/Documents            # anywhere outside the repo
keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore ponas-release.jks \
  -alias ponas \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -dname "CN=Ponas, O=eb07-lab, C=LT"
```

It asks for a password. Choose a strong one and write it down. (With PKCS12 the key password
is the same as the keystore password.)

Copy the keystore to the clipboard as base64:

```sh
base64 -i ponas-release.jks | pbcopy
```

> ⚠️ **Back up `ponas-release.jks` and its password** in a password manager and one more safe
> place. If you lose the key or it changes, updates **won't install**. You'd have to uninstall
> Ponas, which also deletes the child's saved progress, and set everything up again. Never
> commit the keystore; `.gitignore` already blocks `*.jks`.

### 1b. Add the four secrets

GitHub → **eb07-lab/ponas-app → Settings → Secrets and variables → Actions → New repository
secret**. Add each of these:

| Name                | Value                                        |
|---------------------|----------------------------------------------|
| `KEYSTORE_BASE64`   | paste the clipboard from `base64 … \| pbcopy` |
| `KEYSTORE_PASSWORD` | the password you chose                       |
| `KEY_ALIAS`         | `ponas`                                      |
| `KEY_PASSWORD`      | the same password again                      |

### 1c. Turn on GitHub Pages

GitHub → **Settings → Pages → Build and deployment → Source: GitHub Actions**.

Then go to **Actions → Publish games → Run workflow**, on `main`. When it's green, open
https://eb07-lab.github.io/ponas-app/manifest.json. You should see JSON with a `balionai` game.

### 1d. Build the first APK

After the secrets are added: **Actions → Build APK → Run workflow** (on `main`).
When it's green, open the run and download the **ponas-apk** artifact. It's a zip; unzip it to
get `app-release.apk`.

To get a proper release with the APK attached, go to **Releases → Draft a new release**,
create the tag `v0.1.0` and publish it. The workflow then builds and attaches
`app-release.apk` to the release.

Every build also produces **ponas-debug-apk**. It installs as a separate app
(`lt.eb07.ponas.debug`, named "Ponas debug"), so it never conflicts with the release. The
debug build has WebView debugging turned on (see Troubleshooting).

---

## 2. Install on the tablet

On the tablet: **Settings → Developer options → Wireless debugging**. Note the IP and port.
The port changes after every reboot.

On the Mac:

```sh
adb connect 172.20.10.2:PORT
adb devices                              # also lists a duplicate mDNS entry; ignore it and use -s
adb -s 172.20.10.2:PORT install -r ~/Downloads/app-release.apk
adb -s 172.20.10.2:PORT shell cmd package set-home-activity lt.eb07.ponas/.HomeActivity
adb -s 172.20.10.2:PORT shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

You can also choose the home app on the tablet instead: **Settings → Apps → Default apps →
Home app → Ponas**.

Logs:

```sh
adb -s 172.20.10.2:PORT logcat --pid=$(adb -s 172.20.10.2:PORT shell pidof lt.eb07.ponas)
adb -s 172.20.10.2:PORT logcat -s PonasSync     # sync messages only
```

### Installing games (parent only)

Tap the small **⬇ button in the bottom-right corner** of the home screen and enter the PIN
(default **1234**; change it!). The **Games** screen lists every game in the catalog plus every
game already installed. Each one shows its version, date, size, and whether it goes online.

- **Install** downloads the game and adds its tile.
- **Update** appears when a newer version exists. Installed games also update automatically
  when the tablet syncs (at most every 15 min, or right away with Refresh).
- **Remove** takes the tile away. Saved progress stays, in case you install the game again.
- A game that was taken out of the catalog stays installed until you remove it.

### Parent menu

Hold the **top-right corner for 3 seconds**, then enter the PIN. The menu has these options:

- Install / remove games
- Refresh games now
- Sync status and last error
- Open Android Settings
- Open normal launcher
- Change PIN
- Versions (app, manifest, and each game's hash)

"Open normal launcher" starts Launcher3, but pressing Home still returns to Ponas. To stop
using Ponas as the home screen, go to **Settings → Apps → Default apps → Home app** and pick
Launcher3.

Optional extra lock: **Settings → Security → App pinning** keeps the child inside Ponas even if
they swipe the navigation bar in. To unpin, hold Back + Overview.

---

## 3. Phone workflow (adding or changing a game)

1. On your phone, ask Claude (claude.ai/code, repo `eb07-lab/ponas-app`), for example: *"add a
   game in games/spalvos where the child taps the balloon of the colour they hear"*.
2. Claude follows `CLAUDE.md`. It copies `templates/game`, builds the game and its icon, runs
   `node tools/build-site.mjs` and `node tools/smoke-test.mjs` (both orientations), and commits
   to `main`.
3. **Actions → Publish games** runs, which takes about 1 minute. If a game breaks a rule (too
   big, missing icon, external URL…), the run fails and nothing changes on the tablet.
4. Try the game on your phone at https://eb07-lab.github.io/ponas-app/.
5. **New game:** on the tablet, tap ⬇ → PIN → **Install**. **Changed game:** it updates by
   itself within about 15 minutes, or right away with parent menu → Refresh.
6. You don't need the Mac, adb, or a new APK.

**Multiplayer games** (such as Linkėjimai) talk through ntfy.sh on the topic
`bimmer-car-93204-lit-<game-id>`. Open the same game on the tablet and on your phone (from the
Pages site). ntfy.sh is free with no account, but topics are public and the free server allows
only about 1 message every 5 s per device after a short burst. That suits turn-based games and
"send a picture", not fast real-time play.

To try a game in a phone browser before the child sees it:
https://eb07-lab.github.io/ponas-app/

To hide a game without deleting it, set `"enabled": false` in its `meta.json`.

---

## 4. Acceptance test

1. **Install the APK** as described in section 2 and set Ponas as the home app. You should see
   the Ponas home screen with just the **Kamera** tile and a hint to install games.
2. **Install games.** Tap ⬇ (bottom-right) → PIN → the Games screen lists Balionai, Piešimas
   and Linkėjimai. Install all three and go back: three tiles appear, plus Kamera. Press Home:
   Ponas comes back. Press Back on the home screen: nothing happens.
3. **Game runs.** Tap Balionai → ▶. You hear rising beeps as the card counts its dots. Tap the
   balloon with the same number of dots and it pops. The **orange pause** button shows a big ▶
   to resume. The **✕** button, Back, or two fingers held for 1.5 s all return home.
   **Rotate the tablet:** the game rearranges itself.
4. **Isolation.** Open and close a few games. In
   `adb -s … shell ps -A | grep ponas`, the `lt.eb07.ponas:game` process disappears each time a
   game closes.
5. **Multiplayer.** Open Linkėjimai on the tablet and
   https://eb07-lab.github.io/ponas-app/games/linkejimai/ on your phone. Tap the heart on one
   device and it pops up big on the other. The cloud icon turns solid when connected.
6. **Offline.** Turn on airplane mode. Open Balionai again: it still works, with sound, and the
   stars from before are still there.
7. **Update.** Turn airplane mode off. On GitHub (the phone is fine), change something visible,
   for example `"color"` in `games/balionai/meta.json` or the balloon colours in `game.js`.
   Commit to `main` and wait for **Publish games** to go green.
8. **Tablet updates.** Open Parent menu → **Refresh games now**. You'll see a toast saying
   "Games are up to date", and the change shows up without reinstalling. The ⬇ Games screen and
   **Versions** show the new version.
9. **Camera.** The Kamera tile opens the camera app.
10. **No Wi‑Fi on a fresh install** (optional): run
   `adb -s … shell pm clear lt.eb07.ponas`, turn Wi‑Fi off, and open Ponas. It shows the Wi‑Fi
   picture and "Prijunkite Wi‑Fi / Connect Wi‑Fi". Turn Wi‑Fi on and within about 30 s it
   switches to the "install games" hint.

---

## 5. Troubleshooting

**Checking sync status:** go to Parent menu → *Sync status and last error*.

| Message | Meaning / fix |
|---|---|
| `Offline: no network connection` | Wi‑Fi or the hotspot is off. Games keep working; sync retries later. |
| `Manifest download failed: … HTTP 404` | Pages isn't enabled or hasn't deployed yet. Check step 1c, and that https://eb07-lab.github.io/ponas-app/manifest.json opens. |
| `Manifest download failed: … UnknownHost / timeout` | The hotspot has no internet, or the connection is too slow. Try again. |
| `<id>: … size N, expected M (Pages may still be deploying)` | The CDN is still serving old files. Wait 5–10 min and Refresh. The old version stays until then. |
| `<id>: … checksum mismatch` | Same as above. If it keeps happening, re-run **Publish games**. |
| New game doesn't appear | Check that **Actions → Publish games** is green. A red run means `build-site` rejected the game; the log says why. Also check `"enabled": true`. New games must be installed from ⬇ on the tablet; they never appear by themselves. |

- **Pages URL:** it's always `https://eb07-lab.github.io/ponas-app/`. The address is set in
  `android/.../SyncManager.kt` (`BASE_URL`). If the repo is renamed or moved, that line needs
  to change, and so does the APK.
- **adb port changed / "failed to connect":** the wireless-debugging port changes after every
  reboot. Read the new one in Developer options and run `adb connect 172.20.10.2:NEWPORT`. If
  you see "more than one device/emulator", add `-s 172.20.10.2:NEWPORT` to the command.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the APK was signed with a different key. Use the
  original keystore. The only other fix is `adb uninstall lt.eb07.ponas`, which deletes the
  child's progress.
- **A game closes by itself:** Android killed the WebView to free RAM. Ponas returns to the
  home screen instead of crashing. Make the game lighter (smaller images, fewer animations).
- **Inspecting a game on the tablet:** install `ponas-debug.apk`, open the game in
  "Ponas debug", then on the Mac open `chrome://inspect` in Chrome or Edge while adb is
  connected.
- **Tablet still shows an old game:** sync runs at most every 15 min. Use Refresh.
- **Multiplayer doesn't connect:** the game needs `"network": ["ntfy.sh"]` in its `meta.json`,
  and the tablet needs internet access (not just Wi‑Fi). Too many messages in a row hit ntfy's
  free rate limit; wait a minute.

---

## Repo layout

```
android/                     Kotlin shell app (Gradle, AGP 8.7, minSdk 26, targetSdk 34)
games/<id>/                  one folder per game: index.html, meta.json, icon.png, …
templates/game/              starter for new games (not published)
sdk/ponas.js                 shared kit injected into every game (pause/close, overlays, storage, sound, ntfy)
tools/build-site.mjs         validates games, builds _site/ (launcher, manifest.json, games)
tools/smoke-test.mjs         headless test of every game in both orientations (needs Playwright)
.github/workflows/pages.yml  publish games to GitHub Pages
.github/workflows/android.yml build the APK (debug always, signed release with secrets)
CLAUDE.md                    rules for Claude sessions editing this repo
```

Local check (any machine with Node):

```sh
node tools/build-site.mjs         # validates all games, builds _site/ (git-ignored)
npx serve _site                   # launcher + games; use responsive mode at 640×375 and 375×640
npm i --no-save playwright && node tools/smoke-test.mjs   # automatic check, screenshots in _shots/
```

Not built yet: an in-app self-update from GitHub Releases. It would need the
`REQUEST_INSTALL_PACKAGES` permission and is planned for later.
