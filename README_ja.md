# garmin-ble-android-mcp

GarminウォッチとPCの間をつなぐAndroid BLEブリッジアプリ。ウォッチにBluetooth LE で接続し、心拍データをWebSocket経由でPCに転送します。PCにBluetooth不要。

## なぜこれが必要か？

`garmin-ble-mcp` はLinuxから `gatttool` で直接BLE接続します。このアプリはその代わりにAndroidスマホをBLEアダプターとして使えるようにします。以下のケースで便利です：

- PCにBluetoothがない
- PCのBLEが不安定
- スマホがすでにウォッチと近くにある
- **外出先でも使いたい** — PCとスマホを [Tailscale](https://tailscale.com/) でつないでおけば、どこにいてもMCPサーバーがそのまま使える

## 仕組み

```
Garminウォッチ --[BLE]--> Androidアプリ --[WebSocket:8765]--> PC (garmin-ble-mcp)
```

アプリはウォッチに接続してHR通知を有効化し、受信した心拍データをWebSocketサーバー（port 8765）からPCへ配信します。

## 使い方

### 1. ウォッチの準備

Garminウォッチで**心拍転送モード**を有効にしてください：

1. **右上のボタン**を長押し
2. **コントロール**を開く
3. **心拍転送**をタップ

### 2. アプリの操作

1. Android StudioでビルドしてスマホにインストールするかAPKを直接インストール（minSdk 26 / Android 8.0以上）
2. アプリを開いて **Start** をタップ
   - ウォッチに接続してWebSocketサーバーが起動する
   - 通知バーに常駐通知が表示される
3. 停止するにはアプリ内の **Stop** または通知バーの **Stop** をタップ

アプリはフォアグラウンドサービスとして動作するため、アプリを閉じても動き続けます。アプリ履歴からスワイプして消すとサービスも停止します。

### 3. PC側の設定

`websocket-client` をインストール：

```bash
pip install websocket-client
```

スマホのTailscale IP（または Wi-Fi IP）を確認して `bridge_host` として渡す：

```bash
# Python直接実行
uv run python hrv_reader.py 120 --bridge 100.x.x.x
uv run python hr_reader.py --bridge 100.x.x.x

# Claude MCPから
get_hrv_analysis(bridge_host="100.x.x.x")
get_realtime_heart_rate(bridge_host="100.x.x.x")
```

## WebSocketメッセージ形式

アプリはport `8765` でJSON形式のメッセージを送信します：

```json
// 心拍データ（ウォッチから通知が来るたびに送信）
{"type": "hr", "hr": 72, "rr": [834, 812, 798]}

// ステータス変化
{"type": "status", "status": "connected"}
{"type": "status", "status": "disconnected"}
{"type": "status", "status": "error: <message>"}
```

`rr` はRR間隔（ミリ秒）の配列です。ウォッチがRRデータを送らない場合は空配列になります。

## トラブルシューティング

| エラー | 原因 | 対処 |
|--------|------|------|
| `Cannot connect to bridge` | アプリが起動していないか、IPが間違っている | アプリでStartをタップしてIPアドレスを確認 |
| `Connected to bridge but no HR data` | ウォッチがブロードキャストモードでない | ウォッチで心拍転送を有効にする |

## MACアドレス

`BleManager.kt` の `GARMIN_ADDR` に接続先のMACアドレスが書かれています（初期値: `64:A3:37:07:83:FD`）。別のウォッチを使う場合はここを変更してください。

## ライセンス

MIT
