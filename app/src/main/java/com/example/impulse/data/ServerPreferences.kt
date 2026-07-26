package com.example.impulse.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ServerPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "server_preferences",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val CUSTOM_SERVERS_KEY = "custom_servers"
        private const val SELECTED_SERVER_KEY = "selected_server"
        private const val HIDDEN_SERVERS_KEY = "hidden_servers"
        private const val AUTO_CONNECT_KEY = "auto_connect"
        private const val AUTO_RECONNECT_KEY = "auto_reconnect"
        private const val SERVER_AUTO_CONNECT_PREFIX = "server_auto_connect_"
        private const val SERVER_AUTO_RECONNECT_PREFIX = "server_auto_reconnect_"
        private const val BIOMETRIC_ENABLED_KEY = "biometric_enabled"
        private const val CLIENT_NAME_KEY = "client_name"
    }

    fun getCustomServers(): List<ServerConfig> {
        val json = prefs.getString(CUSTOM_SERVERS_KEY, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            val servers = mutableListOf<ServerConfig>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                servers.add(
                    ServerConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        ipAddress = obj.getString("ipAddress"),
                        port = obj.getInt("port"),
                        description = obj.getString("description"),
                        password = obj.optString("password", "")
                    )
                )
            }
            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCustomServers(servers: List<ServerConfig>) {
        val jsonArray = JSONArray()
        servers.forEach { server ->
            val obj = JSONObject()
            obj.put("id", server.id)
            obj.put("name", server.name)
            obj.put("ipAddress", server.ipAddress)
            obj.put("port", server.port)
            obj.put("description", server.description)
            obj.put("password", server.password)
            jsonArray.put(obj)
        }
        prefs.edit().putString(CUSTOM_SERVERS_KEY, jsonArray.toString()).apply()
    }

    fun addCustomServer(server: ServerConfig) {
        val current = getCustomServers().toMutableList()
        current.add(server)
        saveCustomServers(current)
    }

    fun updateCustomServer(updatedServer: ServerConfig) {
        val current = getCustomServers().toMutableList()
        current.removeAll { it.id == updatedServer.id }
        current.add(updatedServer)
        saveCustomServers(current)
    }

    fun getSelectedServer(): ServerConfig? {
        val json = prefs.getString(SELECTED_SERVER_KEY, null) ?: return null
        return try {
            val obj = JSONObject(json)
            ServerConfig(
                id = obj.getString("id"),
                name = obj.getString("name"),
                ipAddress = obj.getString("ipAddress"),
                port = obj.getInt("port"),
                description = obj.getString("description"),
                password = obj.optString("password", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveSelectedServer(server: ServerConfig) {
        val obj = JSONObject()
        obj.put("id", server.id)
        obj.put("name", server.name)
        obj.put("ipAddress", server.ipAddress)
        obj.put("port", server.port)
        obj.put("description", server.description)
        obj.put("password", server.password)
        prefs.edit().putString(SELECTED_SERVER_KEY, obj.toString()).apply()
    }

    fun getServerAutoConnect(serverId: String): Boolean {
        return prefs.getBoolean("$SERVER_AUTO_CONNECT_PREFIX$serverId", false)
    }

    fun setServerAutoConnect(serverId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$SERVER_AUTO_CONNECT_PREFIX$serverId", enabled).apply()
    }

    fun getServerAutoReconnect(serverId: String): Boolean {
        return prefs.getBoolean("$SERVER_AUTO_RECONNECT_PREFIX$serverId", false)
    }

    fun setServerAutoReconnect(serverId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$SERVER_AUTO_RECONNECT_PREFIX$serverId", enabled).apply()
    }

    fun getBiometricEnabled(): Boolean {
        return prefs.getBoolean(BIOMETRIC_ENABLED_KEY, false)
    }

    fun saveBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(BIOMETRIC_ENABLED_KEY, enabled).apply()
    }

    fun getClientName(): String {
        return prefs.getString(CLIENT_NAME_KEY, "") ?: ""
    }

    fun saveClientName(name: String) {
        prefs.edit().putString(CLIENT_NAME_KEY, name).apply()
    }

    fun getHiddenServers(): Set<String> {
        val json = prefs.getString(HIDDEN_SERVERS_KEY, null) ?: return emptySet()
        return try {
            val jsonArray = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                set.add(jsonArray.getString(i))
            }
            set
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveHiddenServers(serverIds: Set<String>) {
        val jsonArray = JSONArray()
        serverIds.forEach { jsonArray.put(it) }
        prefs.edit().putString(HIDDEN_SERVERS_KEY, jsonArray.toString()).apply()
    }

    fun isServerVisible(serverId: String): Boolean {
        return serverId !in getHiddenServers()
    }

    fun setServerVisible(serverId: String, visible: Boolean) {
        val current = getHiddenServers().toMutableSet()
        if (visible) current.remove(serverId) else current.add(serverId)
        saveHiddenServers(current)
    }
}
