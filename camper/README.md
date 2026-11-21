# Camper — wall screen + always-on climate control

One app on the Sony Xperia Z1 on the camper wall (Android 11, 32-bit). Nothing else installed:

- **Kiosk**: it is the phone's HOME app, so it opens after every boot or power cut. It's fullscreen with no Android bars,
  the screen stays on, the backlight dims at night, and Back never leaves it.
- **Controller**: a foreground service polls the devices every 30 s and applies the rules, independent of the screen.
  It keeps a wake lock, a Wi-Fi lock and a multicast lock (for discovery), and restarts on boot, update and power-on.
- **UI**: the same web UI (`app/src/main/assets/web`) is served on port 8080. The kiosk shows it from `127.0.0.1`;
  phones on the camper Wi-Fi open `http://<z1-ip>:8080`.

No cloud is used at runtime: both devices are controlled over the local network.

| Device | Protocol (ported to Kotlin, byte-checked against the Python libraries) |
|---|---|
| Beok TOL47 floor thermostat | Tuya local 3.5: AES-GCM frames, session-key handshake. Found by ID with the 3.5 "app" broadcast to UDP 7000. **DP 104 (factory reset) is refused.** |
| Midea portable AC | Midea V3: 8370 transport (token handshake, AES-CBC), 5A5A packets, AA frames. Found by ID via UDP 6445. Beeps off. |
| Weather | Open-Meteo, location rounded to 0.1°. |

## Rules (same as the Python agent)

- Nothing is written until you pick something; until then the dial mirrors the devices.
- Calibrated inside temperature = 50/50 of the thermostat and the AC sensor. "Control heating by" can pick either raw sensor.
- Floor: Off / On (24°) / Auto (night 23°, cloudy 22°, sunny 20°). The thermostat keeps regulating itself;
  the app shifts its setpoint by the sensor offset, so if the app dies the floor still works.
- Cool / Cool+: AC cool 24° on low fan / 22° on high fan, floor off. Dehumidifier: AC dry with a timer, alongside heating.
- Diesel / Max are shown but refused until the heater is connected.

## Setup on the Z1 (once, over adb)

```sh
adb install -r camper.apk
adb shell mkdir -p /sdcard/Android/data/com.docunest.camper/files
adb push camper.env /sdcard/Android/data/com.docunest.camper/files/camper.env   # device keys, never in git
adb shell cmd package set-home-activity com.docunest.camper/.MainActivity
adb shell dumpsys deviceidle whitelist +com.docunest.camper
adb shell am start -n com.docunest.camper/.MainActivity
```

`camper.env` keys:
- `TUYA_THERMOSTAT_ID`, `TUYA_THERMOSTAT_LOCAL_KEY`
- `MIDEA_AC_ID`, `MIDEA_AC_TOKEN`, `MIDEA_AC_KEY`
- `CAMPER_LAT`, `CAMPER_LON` (city level)

To leave the kiosk: Settings → Apps → Default apps → Home app.

## Build

Every push to `camper/` runs the protocol tests and publishes release `camper-vN` (`.github/workflows/camper.yml`).
The signing key `camper.keystore` is committed on purpose, so builds install over each other.
Locally: `./gradlew testDebugUnitTest assembleRelease`.

Planned: self-update from GitHub releases (like `dock`), Victron over Bluetooth, the diesel heater.
