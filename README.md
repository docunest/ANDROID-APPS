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
  - Screen stays awake while playing.
  - "Open in VLC" when a format can't be played.
- **Offline**:
  - Download movies, episodes or whole seasons.
  - Pause and resume downloads; they continue from the same byte after an app restart.
  - The catalog, search and your bookmarks keep working without internet.

## Family account & sync
Signing in is optional. You use the **same email and password as MapPal**: the shared backend is `apps/api` in this monorepo, and MapPal's users were imported into it once.

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

### iPhone — web app (no Apple ID, free)
Open Reelbox in **Safari**, tap **Share → Add to Home Screen**. The app shows a hint for this. It then runs full-screen with its own icon. Downloads, offline mode and the picture controls all work.

It needs **https**, because iPhone only allows offline storage on secure pages. Pick one free option:

- **Docker + Caddy** on any always-on machine (ideally the Xtream server itself).
  - **Domain**: a free name from duckdns.org pointing at your IP.
  - **Router**: forward ports 80 and 443 to that machine.
  - **Start it**:
    ```bash
    cd deploy && cp .env.example .env    # set DOMAIN and XTREAM_URL
    docker compose up -d --build
    ```
  - Caddy gets and renews a Let's Encrypt certificate by itself. Then open `https://<your-name>.duckdns.org` on the iPhone.
- **Tailscale**: no domain and no port forwarding. Install Tailscale (free) on the server and the iPhone.
  - On the server run `XTREAM_URL=… npm run serve`, then `tailscale serve --bg 8080`.
  - Open the `https://<machine>.<tailnet>.ts.net` address it prints.
  - Downside: the iPhone must have Tailscale on.

> Safari cannot decode **.mkv/.avi**. Those titles get an "Open in VLC" button (VLC for iOS is free). MP4 and HLS play in Reelbox.

Optional, later: there's also a native iOS project (`npm run ios`), but building it needs a Mac with Xcode and an Apple ID.

### Android
- **APK**: `npm run cap:sync && cd android && ./gradlew assembleDebug`, then sideload `android/app/build/outputs/apk/debug/app-debug.apk`.
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
(cd ../api && npm run build) && TEST_DATABASE_URL=postgres://postgres:pg@localhost:5433/postgres npm run test:sync
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
server/proxy.mjs       static server + Xtream proxy (no deps)
server/mock-xtream.mjs fake server for dev/tests
tizen/config.xml       Tizen widget manifest
scripts/               icon rendering, Tizen packaging, native project patches
```

## Known limits
- **Samsung**: the Tizen build is tested in Chromium with a TV user agent and `file://` loading. The **AVPlay** path is written to Samsung's API spec but has not run on real hardware yet.
- **No EPG / TV guide** for live channels yet.
- **Native app downloads** can't be paused mid-file; they restart if interrupted. Browser downloads resume.
