package com.garmin.ble.mcp

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

class HrWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        onClientConnected?.invoke()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        onClientDisconnected?.invoke()
    }

    override fun onMessage(conn: WebSocket, message: String) {}
    override fun onError(conn: WebSocket?, ex: Exception) {}
    override fun onStart() {}

    fun sendHrData(hr: Int, rrIntervals: List<Int>) {
        val json = JSONObject().apply {
            put("type", "hr")
            put("hr", hr)
            put("rr", JSONArray(rrIntervals))
        }
        broadcast(json.toString())
    }

    fun sendStatus(status: String) {
        val json = JSONObject().apply {
            put("type", "status")
            put("status", status)
        }
        broadcast(json.toString())
    }
}
