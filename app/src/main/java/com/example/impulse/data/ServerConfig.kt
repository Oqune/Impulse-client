package com.example.impulse.data

import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ipAddress: String,
    val port: Int = 4433,
    val description: String,
    val password: String = ""
) {
    /**
     * WebTransport endpoint. The client uses the framework
     * `android.net.http.WebTransport`, which runs over HTTPS/QUIC (HTTP/3), so
     * the scheme is `https://` (not `wss://`). The server must serve the
     * WebTransport handshake on this host:port.
     *
     * IPv6 addresses are wrapped in brackets per RFC 2732: https://[::1]:4433
     */
    fun getWebTransportUrl(): String {
        val host = if (ipAddress.contains(":") && !ipAddress.startsWith("[")) {
            "[$ipAddress]"
        } else {
            ipAddress
        }
        return "https://$host:$port"
    }

    /**
     * Display-friendly address for UI.
     */
    fun getDisplayAddress(): String {
        val host = if (ipAddress.contains(":") && !ipAddress.startsWith("[")) {
            "[$ipAddress]"
        } else {
            ipAddress
        }
        return "$host:$port"
    }

    companion object {
        val production = ServerConfig(
            id = "prod_001",
            name = "Main Relay",
            ipAddress = "88.83.201.154",
            port = 4433,
            description = "Основной сервер Impulse",
            password = ""
        )

        val defaultServer = production
        val builtInServers = listOf(production)
    }
}
