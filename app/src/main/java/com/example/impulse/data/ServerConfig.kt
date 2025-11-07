package com.example.impulse.data

import java.util.regex.Pattern

data class ServerConfig(
    val name: String,
    val ipAddress: String,
    val port: Int = 8080,
    val description: String
) {
    fun getWebSocketUrl(): String = "ws://$ipAddress:$port"

    companion object {
        val production = ServerConfig(
            name = "Production",
            ipAddress = "192.168.1.50",
            port = 8080,
            description = "Основной продакшн сервер"
        )

        val local = ServerConfig(
            name = "Local",
            ipAddress = "127.0.0.1",
            port = 8080,
            description = "Локальный сервер разработки"
        )

        val defaultServer = production
        val availableServers = listOf(production, local)
    }
}

fun isValidIpAddress(ip: String): Boolean {
    val ipPattern = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )
    return ipPattern.matcher(ip).matches()
}