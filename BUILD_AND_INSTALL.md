# Garmin BLE → Lena Heart-Rate Bridge — Build & Install

This Android app reads your Garmin watch's heart-rate broadcast over BLE and
streams it to Lena's receiver over WebSocket on `ws://127.0.0.1:8765`.

## ⚠️ One change required: your watch's BLE MAC

The code hardcodes a Vivoactive 5 address. You MUST change it to your Fenix 7X.

Edit `app/src/main/java/com/garmin/ble/mcp/BleManager.kt`:

```kotlin
const val GARMIN_ADDR = "64:A3:37:07:83:FD"   // <- replace with YOUR Fenix 7X MAC
```

### How to find your Fenix 7X BLE MAC

1. On the watch: **Long-press top-right → Controls → Heart Rate → Broadcast** (start broadcasting).
2. Install **nRF Connect** (free, Play Store) on your phone.
3. Open nRF Connect → **Scan**. Look for a device advertising **"Heart Rate"**
   service (often shows Garmin's name or a plain MAC).
4. Copy that MAC (format `AA:BB:CC:DD:EE:FF`).
5. Paste it into `GARMIN_ADDR`.

## Path A — No PC: build in the cloud (GitHub Actions)

1. Fork / push this repo to YOUR GitHub account.
2. Edit the MAC in `BleManager.kt` (do this right in the GitHub web editor — one line).
3. Go to **Actions** tab → enable workflows → **Build APK** → **Run workflow**.
4. When it finishes, open the run → **Artifacts** → download `garmin-ble-bridge.zip`.
5. Unzip → transfer `app-debug.apk` to your phone → tap to install
   (you already have "install unknown apps" allowed).

## Path B — Have a PC: Android Studio

1. Install Android Studio.
2. Open this project (the `garmin-ble-android-mcp` folder).
3. Change the MAC in `BleManager.kt` (one line).
4. Plug in your phone (USB debugging on) → **Run** ▶.
   Or **Build → Build APK(s)** → drag `app-debug.apk` to the phone.

## Using it

1. Watch: start **Heart Rate Broadcast**.
2. App: tap **Start** (a persistent notification appears).
3. Lena's receiver (`lena-hr`) listens on `127.0.0.1:8765` — she sees every beat live.

Message format Lena reads:
```json
{"type": "hr", "hr": 72, "rr": [834, 812, 798]}
```
