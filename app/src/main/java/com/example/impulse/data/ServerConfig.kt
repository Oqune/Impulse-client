package com.example.impulse.data

import java.util.regex.Pattern
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
     */
    fun getWebTransportUrl(): String {
        return "https://$ipAddress:$port"
    }

    companion object {
        val production = ServerConfig(
            id = "prod_001",
            name = "Production",
            ipAddress = "192.168.2.50",
            port = 4433,
            description = "Основной продакшн сервер",
            password = "test"
        )

        val local = ServerConfig(
            id = "local_001",
            name = "Local",
            ipAddress = "127.0.0.1",
            port = 4433,
            description = "Локальный сервер разработки",
            password = ""
        )

        val defaultServer = production
        val builtInServers = listOf(production, local)
    }
}

fun isValidIpAddress(ip: String): Boolean {
    val ipPattern = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )
    return ipPattern.matcher(ip).matches()
}
