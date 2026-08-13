# garmin-ble-android-mcp

[日本語版 README はこちら](README_ja.md)

Android app that acts as a BLE bridge between a Garmin watch and a PC. Connects to the watch via Bluetooth LE, then streams heart rate data to the PC over WebSocket — so the PC doesn't need Bluetooth hardware.

## Why?

`garmin-ble-mcp` connects directly from Linux via `gatttool`. This app lets you use an Android phone as the BLE adapter instead, which is useful when:
- Your PC has no Bluetooth
- BLE is unreliable on the PC
- The phone is already paired and closer to the watch
- **You're away from home** — if your PC and phone are connected via [Tailscale](https://tailscale.com/), the MCP server works from anywhere without any extra configuration

## How it works

```
Garmin Watch  --[BLE]-->  Android App  --[WebSocket:8765]-->  PC (garmin-ble-mcp)
```

The app connects to the watch, enables HR notifications (including Garmin's proprietary CCCD), and broadcasts each HR measurement as JSON over a WebSocket server running on port 8765.

## Usage

### 1. Watch Setup

Enable **Heart Rate Broadcast** on the Garmin watch:

1. Long-press the **top-right button**
2. Open **Controls**
3. Tap **Heart Rate Broadcast**

### 2. App Setup

1. Open this project in Android Studio
2. Build and install on your Android phone (minSdk 26 / Android 8.0+)
3. Open the app and tap **Start**
   - The app connects to the watch and starts the WebSocket server
   - A persistent notification appears in the status bar
4. To stop, tap **Stop** in the app or **Stop** in the notification

The app runs as a foreground service — it keeps running when you close the app. Swiping it away from recents will stop it.

### 3. PC Setup

Install `websocket-client`:

```bash
pip install websocket-client
```

Find your phone's Tailscale (or local Wi-Fi) IP, then pass it as `bridge_host`:

```bash
# Direct Python
uv run python hrv_reader.py 120 --bridge 100.x.x.x
uv run python hr_reader.py --bridge 100.x.x.x

# Via Claude MCP
get_hrv_analysis(bridge_host="100.x.x.x")
get_realtime_heart_rate(bridge_host="100.x.x.x")
```

## WebSocket Message Format

The app sends JSON messages on port `8765`:

```json
// HR data (sent on every notification from the watch)
{"type": "hr", "hr": 72, "rr": [834, 812, 798]}

// Status changes
{"type": "status", "status": "connected"}
{"type": "status", "status": "disconnected"}
{"type": "status", "status": "error: <message>"}
```

`rr` contains RR intervals in milliseconds. The array may be empty if the watch doesn't send raw RR data.

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `Cannot connect to bridge` | App not running or wrong IP | Tap Start in the app and check the IP address |
| `Connected to bridge but no HR data` | Watch not in broadcast mode | Enable Heart Rate Broadcast on the watch |

## MAC Address

The app connects to `64:A3:37:07:83:FD` (Garmin Vivoactive 5). To use a different watch, update `GARMIN_ADDR` in `BleManager.kt`.

## License

MIT
