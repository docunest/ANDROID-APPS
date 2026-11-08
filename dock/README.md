# Dock — car head-unit floating dock

Replaces the head unit's floating dock. Backend: MapPal (`/api/dock/*`,
docunest/MapPal `lib/dock/`).

## What it does (built for fewest taps while driving)

| Gesture on the floating button | Result |
|---|---|
| **Tap** | Opens the dock panel: ★ Save this spot, places list + map. Tap again to close. |
| **Press and hold** (~0.3 s), **slide**, **lift** | Slide menu: YouTube, Google Maps, (DVR once set), ★ Save spot, the 6 most recent nearby places and **Show more…** (full history). Each place shows its **photo** (MapPal: Wikipedia when there's an article that is the place, else Google's; kept on the unit **30 days**, "no photo" remembered a week — fetched ahead for the places up front, shown offline, never re-downloaded on every look; `Photos.kt`) — or, without one, a round **map of the area around it** (OpenStreetMap, ~3.7 km across, pin in the middle), direction + distance (`↗ ~23 km`), the town from its address and how long ago — so it's recognised by where it is, not only by name. Lift on a place → Waze starts navigating; lift on its green **G** end → that place in **Google Maps beside Waze** (photos, reviews, hours — for a café or side quest a passenger sent; the Waze route stays). Never lift in between. |
| **Drag** | Move the button anywhere (position is remembered). |

- The button is small (50 dp) and 45 % transparent when idle, fully visible while touched. Both are adjustable in ⚙.
- **GPS dot** (bottom-left of the button, always fully visible, ⚙ → GPS to hide): 🩷 pink = parked position shown (emulated) · 🔵 blue = really good (fresh fix ±10 m) · 🟢 green = OK (±25 m) · 🟠 orange = barely (worse than ±25 m, or fixes 5–30 s late) · ⚫ black = no sky — tunnel / car park / known no-GPS spot (the auto doctor waits) · 🔴 red = gone (no fix 30 s+, or location off) · grey = GPS not in use. Only real GPS fixes count, never the emulated one. Logic + tests: `GpsDot.kt`.
- **New place from the phone** → a card appears at the top of the screen: tap the name or **Drive · Waze** (one tap), or **Maps** for Google Maps. The button shows an amber count of unseen places.
- **Places list and map**: pinned and unseen first, then recently used, each with the area map (tap = show on the big map), direction + distance and town. Up front (list, map pins, slide menu): places **within 300 km, or sent / driven to in the last 3 days** (the next destination can be far ahead); pinned and new always. The rest sits under **Show more** — all history kept. Where the car is comes from the GPS, else the position saved before the last cold start (it used to be "unknown" after a cold start, which put every place on the map). Area maps are made once per place while there's signal (≤ 4 OSM tiles, same User-Agent as the map) and kept as files, so they show offline (`Thumbs.kt`).
- **Waze** is opened with a name search (`waze.com/ul?q=<name, address>&navigate=yes`) — the only form that started a route with the place's name in field tests (Waze test #9; 🧪 buttons still in the app for now). Tap the **name** = navigate (Waze; set Google Maps as default in ⚙ if ever wanted). Small **G** = the place in Google Maps beside Waze (split screen). Area map picture (or 🗺) = show on map. Long-press = pin / delete / open original link.
- **Split screen — Waze keeps the route, Google Maps beside it**:
  - Slide menu → **Google Maps** always opens in split screen next to Waze.
  - New place card → **Preview**: the place in Google Maps beside Waze (name, photos, reviews) without touching the route; **Drive · Waze** when you decide.
  - Slide menu → **🔎 Nearby**: big buttons over Waze — Fuel, Toilet, Café, Groceries, Parking, Pharmacy, Camper area, Just Maps — each a Google Maps search around you in the other half.
  - **Google Maps never takes over Waze**: every Google Maps launch in the dock (slide menu, list **G**, map bar, new-place card, Google Maps as default app, fallbacks) goes through split screen. If split screen can't be made, it says so and does **not** open Maps — put Waze in split screen by hand (recent apps → Waze icon → Split screen) and try again. From the dock's own panel, the panel closes first so Waze gets the other half.
  - How: Dock's tiny Accessibility helper presses Android's own "split screen" (switched on by root automatically; ⚙ → Split screen otherwise). Fallbacks: root `am start --windowingMode`, or plain launch-adjacent. Pick the method in ⚙ if one misbehaves.
- **Map** (OpenStreetMap, no Google services needed): tap a pin → Drive. **Long-press anywhere** → drop a pin → Drive. Faded pins were found by name search (approximate).
- **★ Save this spot**: one tap saves the GPS position; MapPal names it (nearest campsite/beach/viewpoint from OSM, else the address). Works offline — syncs when there's signal.
- **📡 GPS chip** (top-left of the map): green `±8 m` = fresh GPS fix, amber = no fix, red = GPS/location off. Tap → **GPS doctor**:
  - Live view of the GPS chip itself: satellites heard / used (per system), signal strength (dB-Hz), raw NMEA flowing, last fix, time to first fix, clock vs internet time — plus one sentence on what's wrong (no permission, location off, chip silent = driver, no satellites = antenna, weak signal, stale data, wrong clock).
  - **Fix GPS** first makes sure location is allowed and on, then tries the fixes — just wait (the baseline), fix the clock, fresh orbit data (XTRA/PSDS + time), warm restart, GPS off/on (root), cold start, restart the GPS driver process (root; HAL / `mnld` / `gpsd`) — and after each waits up to 1–3 min for a real GPS fix (only the GPS chip counts, never Wi-Fi/mobile position). Stops at the first that works; **Stop** any time.
  - **Learns what works** (`FixStats.kt`, `FixHistory.kt`): every fix tried is recorded with the kind of trouble (chip silent, no satellites, weak, satellites but no fix…), whether a fix followed and how long it took. Order of the next run: any fix with fewer than **10 counted tries goes first** (least-tried first), so every fix gets measured in every position; once all have 10, fastest expected first (chance it works ÷ seconds it costs, Thompson-sampled so the runner-up still gets tried now and then), per kind of trouble once that has 10 tries. Waits shrink to what the fix actually needs (90 % of its fixes × 1.5 + 15 s, min 45 s) once it has 5+ wins. Tries spoiled by a suddenly blocked sky (tunnel, car park) or Stop are kept but **not counted**; runs started while the GPS worked aren't counted at all. The screen shows per fix: success % with its 95 % range, median time to fix, as-first-fix record, points better than just waiting, and how many tries are still needed; plus the best fix per kind of trouble and the last 10 runs. **Reset** clears it.
  - **Auto doctor** (**on by default**; switch on the GPS doctor screen or ⚙ → GPS): keeps the GPS listened to while the dock runs and starts Fix GPS by itself when it's really broken — with a notification, backing off 10 → 20 → 40 → 60 min after runs that didn't help (30 min after Stop). The tunnel / car-park filter (`SkyJudge.kt`):
    - sky = satellites heard at ≥ 25 dB-Hz; **4+ strong = open sky**, so no fix within 45 s (75 s at start-up) is the chip's fault → fix;
    - fix lost at ≥ 20 km/h with the sky gone = **tunnel** → wait (up to 12 min — Somport 8.6 km at 50 km/h; Vielha, Marão);
    - lost slowly = **under cover** (underground car park, garage, ferry) → wait while parked; **driving 4 min** with no sky (accelerometer road vibration) = not a car park → fix; units without an accelerometer wait 30 min;
    - places where the GPS dropped under cover and came back by itself after 2 min+ are remembered as **no-GPS spots** (250 m) and waited out every time;
    - chip silent 30 s or location switched off → fix at once (tunnels don't do that);
    - after the head unit slept (ignition off) it starts afresh — no "lost 9 hours ago" alarm.
  - The 📡 chip says "Tunnel · waiting" / "Under cover · waiting" / "Fixing GPS…" accordingly. Tests on Vielha, Marão, Lisbon Marquês de Pombal and Noja runs: `SkyJudgeTest.kt`, `FixStatsTest.kt` (incl. a simulation: all 7 fixes reach 10 tries after ~26 outages, then the best one leads).
  - **Parked position after a cold start** (`ParkedPosition.kt`, `ParkedLogic.kt`; on by default, ⚙ → GPS): the unit cold-starts its GPS at every ignition, and in a garage that means no position — Waze won't plan a route. The last good real GPS fix is saved every 15 s while driving. After a start with **no sky for 12 s** (and the van not moving), that position is given to every app as the GPS position (Android mock location: gps, network, fused providers, once a second), so Waze can set a destination. Android pauses the real GPS chip meanwhile, so it hands back **as soon as the van moves (8 s of driving)**, or when a 30 s real-GPS check every 4 min sees sky or gets a fix. **Only right after boot, never mid-travel:** the dock must have started within 3 min of the head unit booting (a dock restart later — self-update while driving, crash — never restores, even stopped in a tunnel), only in the first 5 min after boot, never once the van has moved or the real GPS had a fix since boot, shown at most 20 min; units without an accelerometer check the real GPS every minute instead of every 4. With open sky at start it never mocks (that would only delay the real fix). The button refuses while driving. Needs the "mock location app" permission — granted with root (`appops set com.docunest.dock android:mock_location allow`), else Developer options → Select mock location app → Dock. Doctor screen: saved position, what it's doing, **Use parked position now** / **Hand back to real GPS**. Mock positions are flagged by Android and ignored by the doctor, statistics and glitch recorder; the 📡 chip shows "Parked spot · no GPS yet".
  - If nothing works it says what's left (antenna, module) and offers **Restart head unit** (root, with confirmation).
  - **Sensors**: which motion sensors the unit has (accelerometer, gravity, gyro, compass, rotation, barometer) and a **spirit level** for parking: "front 1.2° up · left side 0.4° down". Tap **Set as level** once, parked on flat ground (the screen leans, so level must be calibrated).
  - **Speed / position glitches**: recorded all the time in the background, from the GPS fixes Waze (or any app) already asks for — no extra GPS use. Flags speed jumps a towing van can't do (> 3.5 m/s² up, > 9 m/s² down), reported speed that disagrees with the distance actually covered over ~5 s (> 20 % and > 18 km/h), and position leaps. Each with time, accuracy, satellites and place, so you can see whether the chip reports a wrong speed or the positions themselves jump. Logic + tests: `GlitchDetector.kt`, `./gradlew testDebugUnitTest`.
- **📶 Wi-Fi chip** (next to 📡): hotspot name in green = internet works, amber = connected but no internet, red = Wi-Fi lost (with how long). Tap → **Wi-Fi doctor**. The watchdog runs all the time inside the dock service:
  - **Why it dropped**, recorded every time with who's at fault: the iPhone ended it (wpa_supplicant disconnect reason from the iPhone, e.g. Personal Hotspot switched off), the hotspot vanished from scans, signal too weak, Android hung up / switched network itself, or Wi-Fi was switched off. With root the 802.11 reason code is read from logcat.
  - **No internet vs broken Wi-Fi**: while connected it pings the hotspot (iPhone 172.20.10.1) and probes the internet once a minute through Wi-Fi (~1 KB). Hotspot answers but no internet = *the iPhone has no mobile data (tunnel)* — not Android's or the iPhone's fault, nothing to fix.
  - **Gets back on by itself**: scans every 5 s while lost. Hotspot visible but Android doesn't rejoin within 20 s → (root) a "user" connect through Android's Wi-Fi service (`RootWifi.kt` via `app_process`), then Wi-Fi off/on + connect; up to 3 tries per drop. That connect also clears Android 10's **no-internet block**: after a hotspot had no internet once (tunnel), Android marks it `NETWORK_SELECTION_PERMANENTLY_DISABLED / NO_INTERNET_PERMANENT` and never auto-joins it again — the screen shows this as "Android auto-join: blocked".
  - **Aggressive since 2026-11-08** (`WifiPlan.kt`, tested): "your networks" = every saved network (read with root) + every one Dock has been on — both iPhone hotspots and Starlink, not just the last one. While down it scans every 2 s and joins whichever of yours shows up: 2 s after it appears, then 6 / 10 / 20 / 30 s apart (every third try restarts Wi-Fi first), no give-up. Mid-handshake joins are left alone.
  - **Bluetooth kick**: none of your networks visible but an iPhone is connected to the car's Bluetooth → Bluetooth off 2 s and on again (root `svc bluetooth`). The iPhone's Shortcuts automation switches Personal Hotspot on when the car's Bluetooth connects *or* disconnects, so the hotspot comes back and Dock joins it. Not during a call, not in the first 25 s after Bluetooth connected by itself (that already fired the automation), then 45 s / 1 / 2 / 5 min apart. Each kick is judged (did one of your networks appear within 60 s, how fast) — the Wi-Fi doctor shows "3 of 4 kicks brought a hotspot back, typically after ~9 s". **Kick Bluetooth** button to test it; switch to turn it off. Needs Android's own Bluetooth: if the unit runs Bluetooth on a separate module, the screen says so and it never kicks (`BtKick.kt`).
  - The GPS doctor no longer switches location off ("Restart GPS") while Wi-Fi is down — Android 10 hides Wi-Fi scan results with location off.
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
