# Reelbox

A player for your **Xtream Codes** video server. One codebase runs on **iPhone, Android, Samsung (Tizen) projectors/TVs and MacBook**.

- **Browse**: movies, series (seasons and episodes) and live channels. Categories, sorting, filtering, and accent-insensitive search across everything.
- **Bookmarks**:
  - *Favorites* for movies, series and channels.
  - *Moments*: bookmark a position inside a video, with a note.
  - *History*: continue watching and resume where you stopped.
  - Export/import as JSON to move them between devices.
- **Player**:
  - Resume and start over.
  - ±10 s / +30 s skips.
  - Audio-track, subtitle and speed menus.
  - Next-episode countdown.
  - Keyboard, TV-remote and media-key support.
  - Picture-in-picture, AirPlay, lock-screen / Control Center controls.
  - Picture fixes for old videos: Fit/Fill/Stretch, forced shape, zoom, automatic black-bar removal.
  - Screen stays awake while playing.
  - "Open in VLC" when a format can't be played.
- **Offline**:
  - Download movies, episodes or whole seasons.
  - Pause and resume downloads; they continue from the same byte after an app restart.
  - The catalog, search and your bookmarks keep working without internet.

## Family account & sync
Signing in is optional. You use the **same email and password as MapPal**: the shared backend is `apps/api` in `docunest/monorepo`, and MapPal's users were imported into it once.

Once signed in, these follow you across every device:
- favorites, bookmarks (moments) and where you stopped watching
- picture settings (zoom, shape, black bars)
- your Xtream server logins: a new phone just signs in and the server is there

How it behaves:
- **Offline**: changes queue up while offline and sync when back online.
- **Conflicts**: the newest change wins.
- **Signing out**: keeps everything on the device.
- **Encryption**: server logins are encrypted in the database.

Ways to sign in:
- **Setup screen**: the *Family account* section.
- **Settings**: the *Family account* panel.
- **Without typing** (the projector): open a single-use link from `POST /admin/login-links`. It looks like `…/#/login?token=…&api=…`.

Build with `VITE_API_URL=https://<api>.vercel.app` to pre-fill the server address.

## How the pieces fit

```
                 ┌──────────── same web app (React + Vite) ────────────┐
 iPhone/Android  │  Capacitor native shell  → talks to server directly │
 Samsung Tizen   │  .wgt package            → talks to server directly │
 Mac / any browser│  installable PWA        → via server/proxy.mjs      │
                 └─────────────────────────────────────────────────────┘
                                         │
                                 your Xtream server
```

Browsers block requests to most Xtream panels: the panels send no CORS headers and are often plain `http`. Native apps and Tizen aren't affected. For browser use, run the tiny included proxy (`server/proxy.mjs`, no dependencies). It serves the app and forwards `/xt/*` to your server, including byte-range requests, redirects and HLS playlists. The app detects it automatically.

## Quick start (development)

```bash
npm install
npm run mock                                     # fake Xtream server on :9090 (login demo/demo)
XTREAM_URL=http://localhost:9090 npm run dev     # app on http://localhost:5173
```

Point `XTREAM_URL` at your real server to use your own library.

## Install on each device

### MacBook — web app
```bash
npm install && npm run build
XTREAM_URL=http://YOUR-SERVER:PORT npm run serve      # http://localhost:8080
```
Open `http://localhost:8080`, then either:
- **Chrome**: ⋮ → *Cast, save and share* → *Install page as app*, or
- **Safari**: *File → Add to Dock*.

`localhost` counts as a secure context, so offline downloads work.

Alternative on Apple-silicon Macs: run the iPhone app on the Mac. In Xcode, choose the target *My Mac (Designed for iPad)*.

### iPhone & Android — native app in Expo Go (recommended; works with every server)
The real app (React Native, in `native/`). It runs inside **Expo Go**, a free App Store / Play Store app, so no Apple developer account, no sideloading and no Expo account are needed.
1. **Install Expo Go**: [App Store](https://apps.apple.com/app/expo-go/id982107779) · [Google Play](https://play.google.com/store/apps/details?id=host.exp.exponent).
2. **Open the app**: on the phone, go to **https://docunest.github.io/ANDROID-APPS/reelbox/app/** and tap *Open Reelbox in Expo Go*. Or scan the QR code on that page from a computer. After the first time it is listed on Expo Go's home screen.
3. **Sign in**: use the family account, or type the Xtream server details exactly like in your IPTV app. An address without `https://` is tried as https first, then http.

Why this instead of the web app:
- **No CORS / mixed-content limits.** Apps call the server directly, like any IPTV app, so servers that block web apps work.
- **Player**: iPhone's own player (AVPlayer); ExoPlayer on Android.
  - Picture in picture, AirPlay, background audio and lock-screen controls.
  - The same picture fixes as the web app: Fit/Fill/Stretch, shape, zoom (also by pinching), move (by dragging) and cutting baked-in black bars, with presets for "double boxed" rips. Settings sync with the other devices.
  - Automatic black-bar *detection* is web-only. A crop detected on the Mac or web app does not apply here; set one with the presets, which then sync.
- **Downloads**: kept in the app's storage, resumable, and playable offline.
- **Shared code**: `native/` uses the web app's Xtream client, bookmarks, picture math and family sync (`src/lib`, plus `*.native.ts` twins for storage and network).

How it's published:
- On every push to `main`, `ponas-pages.yml` runs `expo export`, then `native/scripts/publish-pages.mjs`.
- The script writes `ios.json` / `android.json`: Expo Updates manifests pointing at the exported bundle, plus an install page under `/reelbox/app/`.
- Expo Go checks for a newer version when the app starts.

Limits:
- **Expo Go supports only the newest Expo SDK.** When Expo Go updates to a new SDK, bump `expo` in `native/` (`npx expo install expo@latest --fix`) and push.
- **MKV/AVI on iPhone**: AVPlayer can't decode them. Those titles show *Open in VLC* / *Open in Infuse*. Android plays them.

### iPhone — web app (no install at all)
1. **Open the app**: on the iPhone, go to **https://docunest.github.io/ANDROID-APPS/reelbox/** in Safari.
2. **Install it**: tap **Share → Add to Home Screen**. It then runs full-screen with its own icon.
3. **Sign in**: use the family account (MapPal login), or connect your Xtream server directly.

Works on iPhone:
- downloads and offline playback
- picture-in-picture (it also starts by itself when you leave the app mid-video)
- AirPlay
- lock-screen / Control Center controls
- the zoom/aspect fixes

Things to know:
- **Your server must allow browser access.** If it is https *and* sends CORS headers, nothing else is needed. The setup screen says clearly when it doesn't.
- **If it doesn't**, the fix is one header on the server: `Access-Control-Allow-Origin: *`. Otherwise run the bundled proxy (below) on an https address.
- **Plain-http servers** always need the proxy: an https page can't call an http server.
- **MKV/AVI**: Safari can't decode them. Those titles get an "Open in VLC" button (VLC for iOS is free). MP4 and HLS play in Reelbox.

**Proxy, only if needed (Docker + Caddy, free https):**
1. **Domain**: point a free duckdns.org name at the machine.
2. **Router**: forward ports 80 and 443 to it.
3. **Start it**:
   ```bash
   cd deploy && cp .env.example .env    # set DOMAIN and XTREAM_URL
   docker compose up -d --build
   ```
   Or use Tailscale: `tailscale serve --bg 8080` next to `npm run serve`.

### Android
- **APK**: download `reelbox.apk` from the newest `reelbox-v*` release of this repo, open it on the phone. Every build is signed with the same key (`android/app/reelbox.keystore`), so updates install over the old app and keep its data.
- Build it yourself: `npm run cap:sync && cd android && ./gradlew assembleRelease`.
- **Android Studio**: `npm run android` opens the project; Run.

### Samsung projector / TV (Tizen)
```bash
npm run build:tizen          # → build/tizen/ (app folder) + build/Reelbox-unsigned.wgt
```
Samsung devices only install **signed** packages from a PC in Developer Mode:

1. **Install Tizen Studio** (or the Tizen CLI), plus the *Samsung Certificate Extension* and *TV Extensions*.
2. **Turn on Developer Mode** on the projector:
   - Open *Apps* and enter `1 2 3 4 5` with the remote's number keys (or the SmartThings phone app's virtual remote).
   - Switch *Developer mode* on and enter your PC's IP address.
   - Restart the projector.
3. **Create a Samsung certificate**: Tizen Studio → *Certificate Manager* → *Samsung* → *TV*. Sign in with your Samsung account; this ties the certificate to your device's DUID.
4. **Package and install**:
   ```bash
   sdb connect <projector-ip>
   tizen package -t wgt -s <your-cert-profile> -- build/tizen
   tizen install -n build/tizen/Reelbox.wgt -t <device-name-from-"sdb devices">
   ```

Remote support: arrows move focus, OK selects, Back goes back (and exits from Home). Play/pause, FF and REW work in the player. MKV/AVI files open in Samsung's native **AVPlay** engine automatically; *Settings → Video engine* lets you force either engine.

## Running the proxy permanently
```bash
docker build -t reelbox .
docker run -d --restart unless-stopped -p 8080:8080 -e XTREAM_URL=http://YOUR-SERVER:PORT reelbox
```
Environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `XTREAM_URL` | — (required) | Your Xtream server |
| `PORT` | `8080` | Listening port |
| `HOST` | `0.0.0.0` | Listening address |
| `TLS_CERT`, `TLS_KEY` | — | Serve https |
| `STATIC_DIR` | `dist` | Where the built app lives |

The proxy only forwards to `XTREAM_URL`, plus load-balancer hosts that server redirects to. It is not an open proxy.

## Where things are stored

| | Catalog cache, favorites, moments, history | Downloaded videos |
|---|---|---|
| iOS app | WKWebView IndexedDB | `Library/NoCloud/downloads` (not in iCloud backup) |
| Android app | WebView IndexedDB | app-private files |
| Browser/PWA | IndexedDB | Origin Private File System (fallback: IndexedDB chunks) |
| Tizen | IndexedDB | IndexedDB chunks |

The server password is stored on the device, unencrypted, like other Xtream players. Without a family account, bookmarks stay on the device (Settings → Bookmarks backup exports them).

## Tests
```bash
npm run build && npm run test:e2e
# family sign-in + sync across 3 devices, against the real API and a throwaway Postgres (wiped):
API_DIR=<monorepo>/apps/api TEST_DATABASE_URL=postgres://postgres:pg@localhost:5433/postgres npm run test:sync
```
This runs real Chromium against the mock server (ffmpeg generates real H.264/AAC and HLS media). It covers:
- login and error handling
- browse, filter and search
- favorites, moments and resume
- series in both episode formats panels return
- next episode
- live HLS through the proxy
- 302 load-balancer redirects
- downloads, pause → reload → resume (byte-exact via HTTP Range) on both storage drivers
- full offline mode
- direct mode with and without CORS
- iPhone and 1920×1080 TV layouts with remote keys

Screenshots go to `tests/out/`.

## Project layout
```
src/lib/xtream.ts      Xtream API client + types (handles both episode formats)
src/lib/cache.ts       offline-first catalog cache (IndexedDB, stale-while-revalidate)
src/lib/downloads.ts   download manager: native / OPFS / IndexedDB drivers, resumable
src/lib/engines.ts     <video>+hls.js engine and Samsung AVPlay engine
src/lib/spatial.ts     D-pad focus navigation for remotes
src/pages/             Home, Browse, Movie, Show, Player, Search, Library, Downloads, Settings
src/lib/*.native.ts(x) native-app versions of storage (kv), env and session
native/                the native app (Expo / React Native, runs in Expo Go); shares src/lib
server/proxy.mjs       static server + Xtream proxy (no deps)
server/mock-xtream.mjs fake server for dev/tests
tizen/config.xml       Tizen widget manifest
scripts/               icon rendering, Tizen packaging, native project patches
```

## Known limits
- **Samsung**: the Tizen build is tested in Chromium with a TV user agent and `file://` loading. The **AVPlay** path is written to Samsung's API spec but has not run on real hardware yet.
- **No EPG / TV guide** for live channels yet.
- **Native app downloads** can't be paused mid-file; they restart if interrupted. Browser downloads resume.
