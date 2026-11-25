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
- **It can't get lost** (2026-11-14, after it was dragged under the status bar): it always stays fully inside the safe area — below the status bar, above the navigation bar, inside the sides — while dragging, when saved, on rotation and at start (a bad saved position is pulled back). **⚙ → Floating button → Reset button position** puts it at the left edge, half-way down. (`BubblePlace.kt`, tested.)
- **Never in the keyboard's way**: the button never takes keyboard focus; when a keyboard opens over it, it moves just above it (or hides if there's no room) and comes back when the keyboard closes. Seen through the accessibility helper's window list (keyboard bounds only, no content).
- **On top of other apps' overlays**: Android puts app overlays below the status bar and keyboard (that's the highest an app may go); among overlays, if another app's lands on the button, the dock re-adds it on top (at most every 3 s, never while touched).
- **Status marks** (on the button's outline; ⚙ → GPS to turn either off): nothing extra while all is fine. When something is off a short arc grows from the bottom of the outline — **GPS to the left, Wi-Fi to the right** — about 1/10 of the circle for a warning, 1/6 when it's gone. A problem shows only after lasting 8 s and clears after 3 s fine (`Ring.kt`, tested), so blips stay off the screen.
  - GPS: orange = barely (worse than ±50 m, or no fix for 15–60 s) · grey dashed = no sky (tunnel / car park) · red = gone (60 s+) · pink = parked position shown. The fix age is the freshest of three sources (`GpsDot.kt`).
  - Wi-Fi: yellow = link getting poor · orange = no internet / link bad · blue = joining · purple = Bluetooth kicked · red = lost · grey dashed = Wi-Fi off (`WifiDot.kt`).
  - **Why the first version showed garbage** (fixed 2026-11-10): the dock service wasn't a *location* foreground service, so without root's background-location grant Android 10 stopped its GPS fixes whenever Waze was in front (stale → orange → red; opening the GPS doctor brought them back). It now runs as a location service. And this unit reports no satellites / NMEA at all — the judges now notice ("sky known") and don't call it "chip silent" or "no sky".
- **Wi-Fi link meter**: round trip to the hotspot every 2 s, rolling ~30 s: average + share lost — great ≤ 50 ms (< 10 % lost), ok ≤ 150 ms, poor ≤ 400 ms, bad beyond; the limits grow with the link's own normal (median of ~10 min: great ≤ 2.5×, ok ≤ 5×, poor ≤ 10×). Uses `ping`, or a TCP probe when the unit doesn't let apps ping (before, every ping then counted as lost and the link looked "bad") (`LinkQuality.kt`, tested). Shown on the Wi-Fi doctor; poor/bad colours the ring before the connection drops.
- **Bluetooth as Android sees it** (`BtKick.kt`): public profile checks (hands-free client / audio sink = the car side of a phone), connection events and a re-check every 10 s; the Debug log shows the adapter, paired devices with link state, each profile and vendor Bluetooth apps — many retrofit units run the phone on a separate module Android can't see, then the kick can't work through Android.
- **Satellites** (GPS doctor): a **sky plot** (centre = straight up, rim = horizon, north up; colour = system, filled = used in the fix, dashed = heard only, brighter = stronger, number = satellite) next to a table per system — heard / strong / with orbit data / used / best signal — the concrete satellites in the fix ("GPS 12 5 25 · BeiDou 22 19"), and **why** in plain words when it doesn't add up: e.g. "8 strong satellites (8 BeiDou) but only 0 with orbit data — the chip must download each satellite's orbit (broadcast every 30 s) before it can use it…", too weak, all on one side of the sky, or strong-but-unused systems while fixed. The unit's own raw-measurement support is shown too. `Sky.kt` (tested), `SkyPlotView.kt`.
- **🛰 Satellites on the dock map**: the same sky drawn around the van on the map (north-up) — which side the good satellites are on, which side is blocked. 🛰 chip toggles it.
- **🐞 Run diagnostics** (Debug screen; also once per start): the GPS chip's config and process, the Bluetooth module, the CAN box (vendor apps, properties, services, broadcasts mentioning speed), sensors — into the log (`Diagnostics.kt`).
- **🐞 Debug log** (button in both doctors): the current inputs behind the marks (each GPS fix source with its age and accuracy, Wi-Fi link, service state, root) and every change — level, judge, parked position, Wi-Fi action, Bluetooth kick — newest first, with a snapshot every minute. Made to be screenshotted; also sent to MapPal every 2 min (`/api/dock/logs`, server keeps ≤ 50 MB; switch on the Debug screen). `DebugLog.kt`.
- **📊 Fix report** (GPS doctor): per fix — worked / tries, 95 % range, time to fix, as first fix, vs just waiting — a verdict per fix, the best per kind of trouble, how many tries calibration still needs and the next run's order, the last runs (`FixReport.kt`, tested). Statistics from before 2026-11-10 were reset once: those runs were started by the background-location bug.
- **iPhone link + real internet, in parallel** (2026-11-13, owner's handoff after Wi-Fi trouble): every 30 s, on connect, when a doctor opens and on **Check now**, three checks run at the same time over Wi-Fi — ping the iPhone (172.20.10.1); a **fresh DNS lookup** of a random, never-cached name straight to the hotspot's DNS server (only the iPhone's mobile data can answer it, not its cache); and **generate_204** expecting 204 (≤ 3 s = working). Verdict (`NetVerdict.kt`, tested): works / slow / iPhone has no mobile data / hotspot stalled / DNS problem / blocked. Lost pings alone no longer mean "no internet" (seen live: 13–40 % lost at −53 dBm with the web working). **Stall recovery**: Wi-Fi says connected but the iPhone silent for 20 s and a fresh check fails too → forced rejoin, then Wi-Fi off/on + join; once a minute at most. Android's own connectivity-check setting is left alone.
- **Wi-Fi lost toast**: the moment Wi-Fi drops, a message says whether it was coming — "Reception was bad before the drop (-79 dBm, ping 340 ms, 30 % lost)" or "Link was fine right up to the drop (…) — sudden: the iPhone ended it or Android hung up". Also saved with the drop in the Wi-Fi doctor's history.
- **New place from the phone** → a card appears at the top of the screen: tap the name or **Drive · Waze** (one tap), or **Maps** for Google Maps. The button shows an amber count of unseen places.
- **Places list and map**: pinned and unseen first, then recently used, each with the area map (tap = show on the big map), direction + distance and town. Up front (list, map pins, slide menu): places **within 300 km, or sent / driven to in the last 3 days** (the next destination can be far ahead); pinned and new always. The rest sits under **Show more** — all history kept. Where the car is comes from the GPS, else the position saved before the last cold start (it used to be "unknown" after a cold start, which put every place on the map). Area maps are made once per place while there's signal (≤ 4 OSM tiles, same User-Agent as the map) and kept as files, so they show offline (`Thumbs.kt`).
- **Waze** is opened with a name search (`waze.com/ul?q=<name, address>&navigate=yes`) — the only form that started a route with the place's name in field tests (Waze test #9; the 🧪 Waze test screen is gone). Tap the **name** = navigate (Waze; set Google Maps as default in ⚙ if ever wanted). Small **G** = the place in Google Maps beside Waze (split screen). Area map picture (or 🗺) = show on map. Long-press = pin / delete / open original link.
- **Split screen — Waze keeps the route, Google Maps beside it**:
  - Slide menu → **Google Maps** always opens in split screen next to Waze.
  - New place card → **Preview**: the place in Google Maps beside Waze (name, photos, reviews) without touching the route; **Drive · Waze** when you decide.
  - Slide menu → **🔎 Nearby**: big buttons over Waze — Fuel, Toilet, Café, Groceries, Parking, Pharmacy, Camper area, Just Maps — each a Google Maps search around you in the other half.
  - **Split screen is checked, not assumed** (2026-11-13, KSW firmware): each method is followed by a look at the real window state (`dumpsys activity activities`, `SplitState.kt`, tested) — Maps must be in one split half and Waze in the other. Order: already split → **root-task** (Waze's own task put into the primary half with Android's IActivityTaskManager.setTaskWindowingModeSplitScreenPrimary via `RootSplit.kt` through the unit's adb, then Maps adjacent) → `am start --windowingMode` → the accessibility toggle (now only if a split divider really appears). If none splits, Waze is brought back to the front. Every step is in the debug log (tag SPLIT). Waze goes in the **right** half, Maps on the left (⚙ → Split screen to flip; applies the next time split screen starts — an already-open split keeps its sides).
- **Self-update can't hang any more** (2026-11-13: stuck on "Downloading v40"): the download has connect/read timeouts, goes to a temp file checked against its size, retries 3 times; a check stuck for 6 min gets a fresh thread; the root install gets 2 min. Every step logged (tag UPD).
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
  - **Parked position after a cold start** (`ParkedPosition.kt`, `ParkedLogic.kt`; on by default, ⚙ → GPS): the unit cold-starts its GPS at every ignition, and in a garage that means no position — Waze won't plan a route. The last good real GPS fix is saved every 15 s while driving. After a start with **no sky for 12 s** (45 s while the unit hasn't reported satellites yet; and the van not moving), that position is given to every app as the GPS position (Android mock location: gps, network, fused providers, once a second), so Waze can set a destination. Android pauses the real GPS chip meanwhile, so it hands back **as soon as the van moves (8 s of driving)**, or at the first real fix — **any accuracy** — or satellite heard during a 30 s real-GPS check every 30 s (every 60 s on units with a motion sensor). **Only right after boot, never mid-travel:** the dock must have started within 3 min of the head unit booting (a dock restart later — self-update while driving, crash — never restores, even stopped in a tunnel), only in the first 5 min after boot, never once the van has moved or the real GPS had a fix since boot, shown at most 20 min; units without an accelerometer check the real GPS every minute instead of every 4. With open sky at start it never mocks (that would only delay the real fix). The button refuses while driving. Needs the "mock location app" permission — granted with root (`appops set com.docunest.dock android:mock_location allow`), else Developer options → Select mock location app → Dock. Doctor screen: saved position, what it's doing, **Use parked position now** / **Hand back to real GPS**. Mock positions are flagged by Android and ignored by the doctor, statistics and glitch recorder; the 📡 chip shows "Parked spot · no GPS yet".
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

## Road ahead on the dock map + GTA mini-map (2026-11-16)

Owner: "if there is a route, I want to see what's along the route and what's close to me … show me
where I am … where is my floating circular thingy from GTA?"

- **Dock map** (tap the button): every offline place (packs, `OfflinePois`) drawn as its icon.
  Buttons above the map: ⛽ Diesel · 🅿️ Rest · 🛒 Shops · 🚻 WC & water · ⛺ Camper · ⚠️ Limits ·
  🛝 Kids · 🏰 Cool stuff (all on; tap = off/on) and 🔎 Filter (name/brand/facts: "lidl", "24/7").
  Zoomed far out only diesel / rest / camper show (plus everything on the route).
- **Trip** (`Trip.kt`): destination = Waze's (newest `RECENTS` row in a root copy of Waze's
  `user.db`, deleted right after; checked every 30 s when the file changed) or the place driven to
  from the dock (Drive on a place / long-pressed pin). Start locked where the van was. Route line
  from MapPal `/api/dock/road` (OSRM); with no signal a dashed straight line until the route can be
  fetched. A Waze stop on the way (≤ 3 km off the route) keeps the long trip. New route after
  > 700 m off it for a minute; arrived within 250 m. Saved in `files/trip.json`.
- **Views** (right side of the map): Auto · Route · 3 km · 25 km · 80 km. Auto = 3 km in town
  (< 35 km/h) or when the next diesel / rest / shop / aire is ≤ 3 km ahead, 25 km when it's
  ≤ 25 km, else 80 km. The map follows the van, looking ahead along the route; dragging stops
  following until a view is tapped.
- **Lists** (left column tabs): 📍 Places (sent / saved) · 🛣 Along route ("in 12 km · ~8 min ·
  right · diesel ✓") · 🎯 Near me (≈ 15 km, nearest first). Tap = on the map + Drive.
- **Mini-map** (`MiniMap.kt`): round, over Waze, on by default; north-up, van arrow in the middle,
  route + places; zoom by speed (~1.2 / 3 / 6 km across); bottom label = next diesel. Tap = dock
  map, drag = move. ◉ Mini on the map or Settings → Mini-map (size, reset position).

## Police ahead flash (2026-11-17)

Owner: "Add police alert. Like big red flashing outline with glow … Waze default is not enough to
catch eyes if focused on road. I need something flashy! But don't cover maps".

- `Police.kt`: reads Waze's DEBUG log (`/data/data/com.waze/spdlog.logdata`, root, only the new
  bytes every 2 s, starting at the end; follows rotation) and parses `add_alert_action { … type:
  POLICE … position { lon/lat_times_1000000 } … }` blocks (monorepo docs/ksw/docs/waze-debug-log.md).
  Reports kept 90 min, older than 3 h ignored. Waze's log level is checked every 30 min (Settings shows
  it; "Turn Waze's detail log back on" re-applies DEBUG — Waze restarts for a few seconds).
- Ahead (owner: "better to err on more alerting than less"): along the dock's route (≤ 300 m off it),
  else within ±60° of the GPS course (±100° when Waze marks it on its route); anything within 500 m
  whatever the direction; within 1 km when stopped. Flash from ~90 s before it (1.5–4 km by speed)
  until well passed (150 m behind / 400 m away); each report flashes once.
- **Drive recording** (`WazeTap`, owner: "Build logging"; Settings switch, on): Waze's useful log lines
  into the dock's debug log (tag WAZE, uploaded to MapPal): road type on change + every 30 s with the
  distance to the next turn, road matched yes/no, the turn list when a route is (re)calculated, route
  lifecycle (calculating / changed / near destination / no progress), every community report as one
  line (police all, others ≤ 40 an hour), lines mentioning ETA / street / speed limit / camera (each
  kind once per 10 min), and 30 raw non-noise lines every 10 min.
- `PoliceFlash`: full-screen touch-through overlay; red / blue glow on all four edges (70 dp deep,
  12 dp line), pulsing, ~20 fps; "🚓 POLICE · 650 m · street" tag top centre. The mini-map ring flashes
  too; 🚓 on both maps. Settings → Police ahead: on/off, "Test the flash (6 s)".
- The first 3 raw police blocks go to the dock's debug log (POLICE), to check the format on a drive.

## Mini-map after the first drive (2026-11-17, evening)

Seen on the unit (build 72, Foz do Arelho → Caldas): the screen is **1280×480 at 160 dpi** (1 dp = 1 px), not 1920×720.

- **Modes** (`MiniModes.kt`): 🏙 City (every place, 2 km across, no diesel label), 🛣 Autobahn (places along the route plus diesel / rest / aires around, 15 km, "⛽ 23 km" in whole km, 100 m steps in the last 2 km, refreshed every 15 s), 🧭 Explore (1 km, every place, no route line). Auto switches City ↔ Autobahn on the speed averaged over ~2.5 min (85 / 70 km/h). Waze road types aren't used yet: seen so far 15, 7, 2, 5; no motorway drive logged.
- **Smooth**: drawn ~15×/s (`FRAME_MS`), and between GPS fixes the van moves on along its course (`MiniModes.predict`). Heading and zoom glide. It used to redraw every 500 ms, jumping on every fix.
- **Route line only while on course** (`OnCourse`: shows after 8 s within 120 m, hides after 10 s beyond 250 m). The dock's route is OSRM's, not Waze's.
- **30 % smaller** by default (168 dp, new pref key) and the zoom dot is faint grey until touched.
- **Tap or hold the middle** opens the big view (2× the old size, square, over Waze; the dock map no longer opens). Pinch and pan; tap a place to get a card with Waze and ✕. Buttons: ✕ · ◎ follow · Auto · City · Autobahn · Explore · Hide. A tap outside or 60 s idle closes it.
- **Hide** switches the mini-map off. The **"Mini-map" icon** in the app list (`MiniMapLauncher`) brings it back.

## Police round 2 + average speed sections (2026-11-25)

Owner: "the outline is a bit too much … it alerts only if I'm looking straight at the police … I need something that goes either on my route or
shows proximity or the direction … 5 blocks away brings less value … a special mode when I'm in an average speed zone (Waze doesn't always show them)".

- Police (`Police.evaluate`): **on the route** = along the dock's route (≤ 300 m off) or, with no dock route, a report Waze marks on its route
  and roughly ahead (±100°) → full red/blue flash from ~90 s before (1.5–4 km). Edge line 4 dp (was 12), glow unchanged. **Nearby, off the
  route** (any direction, ≤ 400 m under 50 km/h, ≤ 800 m faster) → soft red glow only on the side it's on, "🚓 400 m" and an arrow. The old
  ±60° "in front of you" cone is gone. Mini-map ring flashes only for "on the route".
- Average speed sections (`AvgZone.kt`): OSM `enforcement=average_speed` relations (from/to nodes, else first/last device; limit from the
  relation or its section ways) → pack table `zone` (Portugal: 23 sections, 15 with a limit). In = within 120 m of a start, > 15 km/h, heading to
  its end (±50°); average = distance driven fix by fix ÷ time since the start; out = within 50 m of the end / 400 m past the closest point /
  far too long. Panel at the top: "⏱ AVERAGE SPEED 100 · you 97 · now 104" + "3.2 km left · max 108 km/h for the rest"; steady edge glow green /
  amber (within 3 of the limit) / red (over). Settings → Police ahead: on/off + "Test the average-speed panel". Older packs have no `zone`
  table → nothing shown until the packs are updated.

## Wi-Fi doctor asks before cutting a connection (2026-11-20)

Owner: "way too aggressive … keeps disconnecting my phone … if you are dropping the Wi-Fi connection, it must be confirmed by me".
Evidence (log of 15 Nov, ~4 h): 12 drops started by the dock itself — 7 "hotspot stalled" forced reconnects, 5 Starlink ↔ iPhone switches.

- `WifiAsk.kt`: everything that would cut a working connection — forced reconnect of a quiet hotspot, switching Starlink ↔ iPhone, the
  Bluetooth off/on kick — shows a card at the top ("why" + **Leave it** / **Reconnect**). Nothing happens without Yes. The card closes by itself
  (nothing done) when the problem clears or after 90 s; the same question isn't asked again within 5 min, and "Leave it" silences that kind
  for 30 min. Every question and answer is in the debug log (`WIFI ASK [...]`).
- Asking needs stronger evidence than the old automatic reconnect: iPhone silent ≥ 60 s (was 45 s) **and** the internet check failed twice in a
  row (was once). Lost pings, a slow web check or a short internet gap don't count.
- While Wi-Fi is already down, joining one of your networks stays automatic (nothing to cut): 2 s after the hotspot first shows up, then
  6 / 10 / 20 / 30 s. (Build 77–78 waited for 5 s of *continuous* visibility and deferred to Android; on the unit the hotspot flickers in and
  out of the scan list every few seconds, so the first join came 43 s after a drop instead of 3 s. Reverted in build 79; a flicker no longer
  restarts the wait, "visible" = seen in the last 12 s.) No automatic Wi-Fi off/on any more.
- **Something on the unit switches Wi-Fi off** ~65 s into a drop and every ~65 s after (seen 17, 18, 20 Nov; ~6 s off each time, which empties
  the scan list). Not Dock: Dock logs every Wi-Fi restart it does and none happened then. When Wi-Fi goes off and Dock didn't do it, Dock now
  logs Android's own lines about it (`setWifiEnabled package=…`, SelfRecovery, WifiController) to name the culprit.
- "Reconnect now" / "Kick Bluetooth now" on the doctor screen still act at once (you asked).
- Each internet check is no longer single-shot: DNS = 2 queries (3 s + 4 s), web = HTTPS generate_204 then plain-HTTP generate_204 on a
  second host (≤ 12 s each), and the checks are waited for as long as their own timeouts (it used to give up after 8 s while the web request
  could take 12 s, so a slow-but-working answer counted as a failure). The two failed checks must be ≥ 20 s apart. The 2-s ping has a hard
  3-s limit (a hung ping process counts as one lost answer).

## Waze ignoring a sent link — hold to restart (2026-11-17, changed 11-20 and 11-25)

History: Waze sometimes takes a link and does nothing (after killing Waze the same link worked). Two automatic fixes guessed wrong: "restart if
no search 12 s after a link" fired while Waze was slowly planning or the owner was looking at the route; "same place sent twice in 5 min" fired
while the owner was mid-planning another route, and the restarted Waze asked "resume the previous drive?" and dropped the link — a loop.

Now (owner 2026-11-25) **no guessing**: every Waze button — a place's name in the list, W, "Drive · Waze", the place card's Waze, the new-place
card, the mini-map card — is **tap = send as usual, hold 3 s = restart Waze and send** (`WazeHold.kt`: a light bar fills the button while held;
let go early = nothing). The restart (`WazeLinkCheck.restartAndSend`): stop Waze → start it → wait 8 s (start-up and its "resume?" question)
→ send the link → watch Waze's log for "Executing query" (took it) / startNavigationFlow (driving) → if neither within 12 s, send once more.
A notice at the top says each step. The place menu (pin / delete / link) moved to a long-press on the map thumbnail or 🗺.

## Dock map + mini-map, round 2 (2026-11-17)

Owner after the first drive with v68: "much better … the UI became very crowded … buttons still have
to be easy to reach … the GTA bubble has to show a way longer horizon, not north-up, me off centre …
a draggable dot like a Nest thermostat … a smart auto mode (zoom in near the start/end, out in the
middle, not when just passing a city) … parking as an optional item … tap on the map zooms in, then
a place → Google Maps or Waze".

- **Dock map buttons:** one square icon per kind on top (⛽ ☕ 🛒 🚻 ⛺ 🅿️ ⚠️ 🛝 🏰 🔎), 52 × 48 dp; faded = hidden;
  long-press = its name. Rest areas are now ☕ (🅿️ is parking). Right column: Auto · ＋ · span · － ·
  Route · ⤢ (map full width, list hidden; ⤡ = back) · ◉ Mini. ＋/－ step 1 / 3 / 8 / 25 / 80 / 250 km.
  GPS / Wi-Fi chips are small, bottom-left (hidden while a place card is open).
- **Left column:** no "Places" heading (the tab says it); the trip row only in the 🛣 tab, whose
  label shows the km left ("🛣 Route · 255 km"); sync status under the list.
- **Tap on the map:** several icons under the finger → zoom in there; one → zoom to it (if far out)
  and its card: what it is, info, "in 12 km · ~8 min on the route" or "3.4 km away", and **Waze**
  (drive; also the dock's trip) / **Google Maps** (its Google card beside Waze: photos, hours, Directions).
- **Auto zoom** (`AutoZoom`, both maps): trip → close-up within 3 km of the start or 5 km of the end;
  mid-trip far out, by the speed averaged over ~2.5 min (75 km/h ± 8 hysteresis), never town level
  (a town on the way doesn't zoom in). No trip: by the averaged speed. Dock map 3 / 25 / 80 km.
- **Mini-map:** heading-up (the road's direction when on the route, else the GPS course; smoothed),
  the van 28 % below the centre (~¾ of the circle ahead). Auto 1.5 / 6 / 18 km across. Zoom dial:
  drag the dot around the ring, 0.5–60 km over a 270° sweep (clockwise = out); tap the dot = Auto
  (green "A"; amber "±" = set by hand, kept until tapped or Settings → "Zoom back to Auto"). Default
  size 240 dp. Places re-read only when the view moved ~15 %.
- **Parking** (pack layer `parking`, group 🅿️, off by default). Owner: "add all parkings that are
  usable, even paid private". Kept: every public / customers / unknown-access `amenity=parking`
  (street-side too, any size) and private ones with `fee=yes`; dropped: private without a fee,
  no / permit / residents / delivery, garage boxes / carports / sheds, bus. Portugal: 62,940 of
  66,217. Info with context icons: ⬇️ underground · 🏢 multi storey · 🛣️ street side · 🚐 campers OK ·
  🚫 no campers · ↕️ max height · 🆓 free · 💶 paid · 🛒 customers · 🔒 private, paid · 👮 guarded ·
  ☂️ covered · 🚗 places. The map draws the most useful one as a badge on the icon (height first,
  then campers, private, free/paid; any kind: 🌙 24/7). Shown when the map is ≤ 12 km across;
  street-side ones smaller and only ≤ 4 km, and never in the lists. Car only (not in the web chunks).

## Offline POI packs (2026-11-16)

Owner: "POI now, packs later" — POIs offline first; offline vector maps and routing later.

- `dock/tools/poi-packs/` + `.github/workflows/dock-poi.yml`: monthly (and on change) GitHub Actions
  downloads Geofabrik's Spain, Portugal, France, Italy and Germany extracts, keeps only the Road ahead
  layers (`filter.txt`: diesel stations — owner: diesel only, no EV chargers / gas-only / LPG / AdBlue info — rest areas, toilets, water, groceries, camper dump, aires/campsites,
  height/weight limits under 4 m / 12 t, playgrounds, picnic, beaches, factories, power plants,
  quarries, dams, named bridges, airports, castles, viewpoints, lighthouses) and writes one SQLite
  per country (`build.py`, table `poi`, index `(layer, lat)`), gzipped, on release `dock-poi` with
  `poi-manifest.json` (size, SHA-256, count, OSM data date). A country that fails keeps last month's pack.
- Dock: Settings → 🗺 Offline map data (`OfflineDataActivity`). Downloads only on Starlink Wi-Fi
  (`WifiPrefer.unmetered`) unless "Download now on this network" is confirmed; resumes after car-off
  (HTTP Range), checks SHA-256 and the place count before replacing a pack. On Starlink it checks
  weekly for newer packs and updates installed ones (`OfflinePacks.tick`, every 3 min from DockService);
  the same Settings button turns into "⬇ Update over Starlink" when newer data is out.
- `OfflinePois.nearest(ctx, lat, lng, layer)` — offline lookup for Road ahead / Nearby.

## Crashes (2026-11-14)

Builds 43–44 restarted the dock every ~30–60 min: the floating button's "stay on top" check read every window's content on every window change (Waze changes windows constantly) and never released those objects (Android 10 requires it) — memory leaked until Android killed the dock. Now the button's window is found by its position, only windows overlapping it are read (and released), the window list is released, and checks are coalesced to twice a second. Picture caches are capped at 8 MB each.
- **"System UI isn't responding"** (same day, same cause): reading a window's content is answered by the app that owns it, on its main thread — on every window change that flooded System UI. The check now uses only window bounds, types and layers (answered by Android itself), at most once a second; re-raising the button at most every 5 s and never in a loop.
- Any dock crash is saved with its stack trace and uploaded at the next start (tag **CRASH**); at each start Android's own crash log (new entries), the last boot reason and kill / ANR / low-memory events are read through root into the log.

## Dock keyboard (2026-11-14)

The stock keyboard covers the whole lower half — in Waze that's the search results list. The **Dock keyboard** (`DockKeyboard.kt`) is a compact panel (~42 % wide) in the bottom corner **opposite the text cursor** (Waze's search on the left → keyboard on the right). Its window spans the screen but is transparent and tells Android not to resize or move the app; only the panel takes touches, so Waze keeps working everywhere else.
- **⇄** flips the side (remembered per app); **▾** shrinks it to a small ⌨ button — sticky per app, so tapping Waze's search again doesn't throw the full panel back up; tap ⌨ to bring it back.
- Digits always on top (coordinates, house numbers); **?#á** page: punctuation, °, Iberian accents (á é í ó ú ã õ ç ñ …); Enter does the app's own action (Waze: Search).
- **iDrive**: push up/down/left/right = move to the neighbouring key, turn = next / previous key, press = type the highlighted key (bright yellow, thick white border). Handles D-pad, Enter/Centre, Tab, page/navigate keys and rotary scrolling; every hardware key it receives is logged (tag KEY) so another controller mapping can be added. `KeyGrid.kt` (tested).
- ⚙ → Dock keyboard → **Use Dock keyboard** (root: `ime enable` + `ime set`); **Previous keyboard** switches back.
- The floating dock button avoids the panel (not the whole transparent window).

## Root without Magisk: the unit's own ADB (2026-11-13)

This head unit is a **userdebug** build: its `su` refuses apps, but its adbd may run as root. The dock speaks the ADB protocol itself (`AdbClient.kt`, tested against a fake adbd and `adb keygen` keys) to **127.0.0.1:5555** and asks adbd to restart as root; everything that needs root (`Root.kt`: forced Wi-Fi rejoin, Wi-Fi restart, Bluetooth off/on, grants, GPS fixes, silent updates) uses `su` when it works, else this (`AdbShell.kt`). The doctors show "Root via adb (root)".

**One-time setup from a laptop** (`adb` connected to the head unit):
```
adb root
adb shell pm grant com.docunest.dock android.permission.ACCESS_BACKGROUND_LOCATION
adb shell appops set com.docunest.dock android:mock_location allow
adb shell appops set com.docunest.dock SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.docunest.dock
adb shell settings put global wifi_scan_throttle_enabled 0
adb shell setprop persist.adb.tcp.port 5555
adb tcpip 5555
```
Then, the first time the dock connects, the head unit asks **"Allow USB debugging?"** — tick *Always allow from this computer* and tap Allow. After that it's silent, also after reboots. Anyone on the same Wi-Fi would also need that tap to get in.

## KSW head unit specifics (verified over ADB, 2026-11-13)

KSW-Q userdebug v4.2.9 (BMW NBT/EVO UI), Android 10, Snapdragon msm8953, SELinux permissive.

- **Phone Bluetooth is an external Goodocom module, not Android's Bluetooth** (Android's stack is off). The dock follows the module's log (tags `goc` / `GocsdkCallbackImp`; READ_LOGS granted via the unit's adb, polled through root until the dock restarts) and drives it by writing `AT#<CMD>\r\n` to `/dev/goc_serial` — **never reads it** (the stock `com.wits.ksw.bt` app does; a second reader breaks it). Bluetooth kick = `AT#CD`, 3 s, `AT#CC<mac>` (reconnects HFP ~7 s, audio ~14 s) — the iPhone's automation fires on both. Calls: module call states + `Settings.System isCalling`. `GocState.kt` (tested with the live lines), `GocBt.kt`.
- **Wi-Fi always prefers the iPhone hotspot** (`Lukas’s iPhone`, curly apostrophe; owner's rule, no toggle): on any other network (Starlink) the Wi-Fi doctor scans every 6 s and switches the moment the iPhone is visible (≥ −85 dBm); another iPhone hotspot also beats Starlink. Retries every 20 s, every 60 s after 3 tries. `WifiPrefer` in `GocState.kt`, tested.

## First install (once)

1. GitHub → **Releases** → newest `dock-vN` → download `dock.apk` (or Actions → "Dock APK" → artifact).
2. Install it on the head unit (USB stick + file manager, or `adb install dock.apk`).
3. Open **Dock** → ⚙ → **Grant all with root** (overlay + location + battery whitelist in one go), then **Show button**.
4. ⚙ → **Server**: your MapPal address (e.g. `https://xxx.vercel.app`) → **Save + test**. The token is pre-filled.

After that it **updates itself** — silently on the rooted head unit; on a phone without root Android shows one "Update" tap the first time (after allowing "install unknown apps" for Dock once), and on Android 12+ later updates are silent too: every push to `dock/` on `main` builds a new release, and the app installs it silently with root (`pm install -r`) as soon as the dock starts (car on) and every 3 minutes — tap the version badge in the dock window to check now. Signing key `dock/dock.keystore` is committed on purpose so every build installs over the previous one.

### Updates over metered mobile data

The head unit's internet is the iPhone hotspot, so every byte counts:

- **Small APK:** R8 code and resource shrinking are on (`app/build.gradle.kts`, keep rules for the `app_process` helpers in `app/proguard-rules.pro`). The map library's PNG icons are swapped for tiny vectors, and offline map-archive readers are left out (`OsmTiles.kt`). A full download was 1.53 MB and is now about **330 KB** (`dock.apk.gz`; `dock.apk` is about 665 KB because its code is stored uncompressed, see below). CI fails if `dock.apk` goes over 850 KB.
- **Updates are patches:** each release also carries `dock-from-<build>.patch` for the 3 builds before it. The unit downloads only the patch and rebuilds the exact new APK from the one it has installed (`DeltaPatch.kt`, made by `tools/delta.py`). Measured: a text fix is about **5 KB** and a small feature about **60 KB**. If the patch is missing, for another build or wrong in any way (SHA-256 check), it downloads `dock.apk.gz`, then `dock.apk`. Older releases' patches are deleted, so the update check stays small.
- **One version:** only `main` publishes Dock releases (other branches just build and test), and only the 10 newest `dock-v*` releases are kept.
- **Proof before publishing:** CI rebuilds every patch with the app's own patcher (`DeltaPatchTest`), and only then creates the release.
- README-only changes don't build a release.
- The version badge (and Debug log, `UPD`) shows what an update cost, e.g. "Updated to v123 (patch 6 KB)".

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
