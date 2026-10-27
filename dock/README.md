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
- **Map** (OpenStreetMap, no Google services needed): tap a pin → Drive. **Long-press anywhere** → drop a pin → Drive. Faded pins were found by name search (approximate).
- **★ Save this spot**: one tap saves the GPS position; MapPal names it (nearest campsite/beach/viewpoint from OSM, else the address). Works offline — syncs when there's signal.
- Links sent while the car was off arrive within seconds of switching on: the app starts on boot and keeps a long-poll open to MapPal (server answers the moment something arrives, ~1 s), re-asking at once on wake / network.

## First install (once)

1. GitHub → **Releases** → newest `dock-vN` → download `dock.apk` (or Actions → "Dock APK" → artifact).
2. Install it on the head unit (USB stick + file manager, or `adb install dock.apk`).
3. Open **Dock** → ⚙ → **Grant all with root** (overlay + location + battery whitelist in one go), then **Show button**.
4. ⚙ → **Server**: your MapPal address (e.g. `https://xxx.vercel.app`) → **Save + test**. The token is pre-filled.

After that it **updates itself** — silently on the rooted head unit; on a phone without root Android shows one "Update" tap the first time (after allowing "install unknown apps" for Dock once), and on Android 12+ later updates are silent too: every push to `dock/` builds a new release, and the app installs it silently with root (`pm install -r`) on start and every 3 h (⚙ → Check now to force). Signing key `dock/dock.keystore` is committed on purpose so every build installs over the previous one.

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
