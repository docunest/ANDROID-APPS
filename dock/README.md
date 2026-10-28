# Dock — car head-unit floating dock

Replaces the head unit's floating dock. Backend: MapPal (`/api/dock/*`,
docunest/MapPal `lib/dock/`).

## What it does (built for fewest taps while driving)

| Gesture on the floating button | Result |
|---|---|
| **Tap** | Opens the dock panel: ★ Save this spot, places list + map. Tap again to close. |
| **Press and hold** (~0.3 s), **slide**, **lift** | Slide menu: YouTube, Google Maps, (DVR once set), ★ Save spot, the most recent nearby places (with ~distance) and **Show more…** (full history). Lift on a place → Waze starts navigating. Never lift in between. |
| **Drag** | Move the button anywhere (position is remembered). |

- The button is small (50 dp) and 45 % transparent when idle, fully visible while touched. Both are adjustable in ⚙.
- **New place from the phone** → a card appears at the top of the screen: tap the name or **Drive · Waze** (one tap), or **Maps** for Google Maps. The button shows an amber count of unseen places.
- **Places list**: pinned and unseen first, then recently used, each with ~distance. Places more than 150 km away *and* older than 2 days sit under **Show more** — all history kept.
- **Waze** is opened with a name search (`waze.com/ul?q=<name, address>&navigate=yes`) — the only form that started a route with the place's name in field tests (Waze test #9; 🧪 buttons still in the app for now). Tap the **name** = navigate (Waze; set Google Maps as default in ⚙ if ever wanted). Small **G** = this once in Google Maps. 🗺 = show on map. Long-press = pin / delete / open original link.
- **Split screen — Waze keeps the route, Google Maps beside it**:
  - Slide menu → **Google Maps** always opens in split screen next to Waze.
  - New place card → **Preview**: the place in Google Maps beside Waze (name, photos, reviews) without touching the route; **Drive · Waze** when you decide.
  - Slide menu → **🔎 Nearby**: big buttons over Waze — Fuel, Toilet, Café, Groceries, Parking, Pharmacy, Camper area, Just Maps — each a Google Maps search around you in the other half.
  - How: Dock's tiny Accessibility helper presses Android's own "split screen" (switched on by root automatically; ⚙ → Split screen otherwise). Fallbacks: root `am start --windowingMode`, or plain launch-adjacent. Pick the method in ⚙ if one misbehaves.
- **Map** (OpenStreetMap, no Google services needed): tap a pin → Drive. **Long-press anywhere** → drop a pin → Drive. Faded pins were found by name search (approximate).
- **★ Save this spot**: one tap saves the GPS position; MapPal names it (nearest campsite/beach/viewpoint from OSM, else the address). Works offline — syncs when there's signal.
- **📡 GPS chip** (top-left of the map): green `±8 m` = fresh GPS fix, amber = no fix, red = GPS/location off. Tap → **GPS doctor**:
  - Live view of the GPS chip itself: satellites heard / used (per system), signal strength (dB-Hz), raw NMEA flowing, last fix, time to first fix, clock vs internet time — plus one sentence on what's wrong (no permission, location off, chip silent = driver, no satellites = antenna, weak signal, stale data, wrong clock).
  - **Fix GPS** tries, in order, and after each one waits up to 1–3 min for a real GPS fix (only the GPS chip counts, never Wi-Fi/mobile position); stops at the first that works and tries that one first next time: allow location → switch location/GPS on → ask GPS and wait → fix the clock → fresh orbit data (XTRA/PSDS + time) → warm restart → GPS off/on (root) → cold start → restart the GPS driver process (root; HAL / `mnld` / `gpsd`). Worst case ~16 min; **Stop** any time. Keep the screen open while it runs.
  - If nothing works it says what's left (antenna, module) and offers **Restart head unit** (root, with confirmation).
  - **Sensors**: which motion sensors the unit has (accelerometer, gravity, gyro, compass, rotation, barometer) and a **spirit level** for parking: "front 1.2° up · left side 0.4° down". Tap **Set as level** once, parked on flat ground (the screen leans, so level must be calibrated).
  - **Speed / position glitches**: recorded all the time in the background, from the GPS fixes Waze (or any app) already asks for — no extra GPS use. Flags speed jumps a towing van can't do (> 3.5 m/s² up, > 9 m/s² down), reported speed that disagrees with the distance actually covered over ~5 s (> 20 % and > 18 km/h), and position leaps. Each with time, accuracy, satellites and place, so you can see whether the chip reports a wrong speed or the positions themselves jump. Logic + tests: `GlitchDetector.kt`, `./gradlew testDebugUnitTest`.
- **📶 Wi-Fi chip** (next to 📡): hotspot name in green = internet works, amber = connected but no internet, red = Wi-Fi lost (with how long). Tap → **Wi-Fi doctor**. The watchdog runs all the time inside the dock service:
  - **Why it dropped**, recorded every time with who's at fault: the iPhone ended it (wpa_supplicant disconnect reason from the iPhone, e.g. Personal Hotspot switched off), the hotspot vanished from scans, signal too weak, Android hung up / switched network itself, or Wi-Fi was switched off. With root the 802.11 reason code is read from logcat.
  - **No internet vs broken Wi-Fi**: while connected it pings the hotspot (iPhone 172.20.10.1) and probes the internet once a minute through Wi-Fi (~1 KB). Hotspot answers but no internet = *the iPhone has no mobile data (tunnel)* — not Android's or the iPhone's fault, nothing to fix.
  - **Gets back on by itself**: scans every 5 s while lost. Hotspot visible but Android doesn't rejoin within 20 s → (root) a "user" connect through Android's Wi-Fi service (`RootWifi.kt` via `app_process`), then Wi-Fi off/on + connect; up to 3 tries per drop. That connect also clears Android 10's **no-internet block**: after a hotspot had no internet once (tunnel), Android marks it `NETWORK_SELECTION_PERMANENTLY_DISABLED / NO_INTERNET_PERMANENT` and never auto-joins it again — the screen shows this as "Android auto-join: blocked".
  - Hotspot not visible → nothing Android can join: a notification says to open Personal Hotspot on the iPhone; it keeps scanning and joins the moment it's back.
  - With root at every start: Wi-Fi scan throttling off, and `cmd wifi set-ipreach-disconnect disabled` so Android doesn't hang up when the iPhone (in power save) misses a few ARP replies. Optional keep-alive ping every 15 s.
  - Needs background location (granted with root automatically) to see Wi-Fi names while Waze is in front.
- Links sent while the car was off arrive within seconds of switching on: the app starts on boot and keeps a long-poll open to MapPal (server answers the moment something arrives, ~1 s), re-asking at once on wake / network.

## First install (once)

1. GitHub → **Releases** → newest `dock-vN` → download `dock.apk` (or Actions → "Dock APK" → artifact).
2. Install it on the head unit (USB stick + file manager, or `adb install dock.apk`).
3. Open **Dock** → ⚙ → **Grant all with root** (overlay + location + battery whitelist in one go), then **Show button**.
4. ⚙ → **Server**: your MapPal address (e.g. `https://xxx.vercel.app`) → **Save + test**. The token is pre-filled.

After that it **updates itself** — silently on the rooted head unit; on a phone without root Android shows one "Update" tap the first time (after allowing "install unknown apps" for Dock once), and on Android 12+ later updates are silent too: every push to `dock/` builds a new release, and the app installs it silently with root (`pm install -r`) as soon as the dock starts (car on) and every 3 minutes — tap the version badge in the dock window to check now. Signing key `dock/dock.keystore` is committed on purpose so every build installs over the previous one.

Set repo variable `MAPPAL_URL` (Settings → Secrets and variables → Actions → Variables) to bake the server address into builds.

## Send a place from the phone

Endpoint: `POST https://<mappal>/api/dock/inbox` with header
`Authorization: Bearer <token>` (or `?token=<token>` in the URL), body
`{"text": "<whatever the share sheet gives>"}`. Short `maps.app.goo.gl`
links, full Google Maps links, Waze, Apple Maps, `geo:` and plain
`lat, lng` all work; the server resolves the place and its name.
Token: MapPal `lib/dock/auth.ts` (`DEFAULT_DOCK_TOKEN`), or the
`DOCK_INBOX_TOKEN` env var on Vercel if set (then put the same value in ⚙).

**iPhone (Shortcuts app)**
1. New shortcut → ⓘ → turn on **Show in Share Sheet**, accept **URLs, Text, Maps URLs**.
2. Add action **Get Contents of URL**: URL `https://<mappal>/api/dock/inbox?token=<token>`, Method **POST**, Request Body **JSON**, field `text` (Text) = **Shortcut Input**.
3. Optional: **Show Notification** "Sent to car ✓". Name it **Send to car**.
4. In Google Maps: open a place → Share → **Send to car**.

**Android phone**: install **HTTP Shortcuts** (free) → new shortcut → Method POST, URL as above, Request body *Custom text*, Content-Type `application/json`, body `{"text": "{shared text}"}` (insert the "shared text" variable) → in shortcut settings enable **Show in share menu**. Then Google Maps → Share → HTTP Shortcuts → Send to car.

Without "display over other apps" (e.g. a phone) new places arrive as a notification with a **Drive** button instead of the on-screen card. The server address is built in (`https://map-pal-mike-base.vercel.app`); change it in ⚙ if MapPal moves.

**Browser fallback**: `https://<mappal>/dock` (logged in) — paste a link, see/delete history.
