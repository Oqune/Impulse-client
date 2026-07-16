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
        private const val AUTO_CONNECT_KEY = "auto_connect"
        private const val BIOMETRIC_ENABLED_KEY = "biometric_enabled"
        private const val CLIENT_NAME_KEY = "client_name"
        // Per-server encryption keys, stored as a JSON map keyed by server id.
        // This keeps the E2E key bound to each server instead of a single
        // global value, and makes it optional (empty string = no encryption).
        private const val ENCRYPTION_KEYS_KEY = "encryption_keys"
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

    fun removeCustomServer(server: ServerConfig) {
        val current = getCustomServers().toMutableList()
        current.removeAll { it.id == server.id }
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

    fun getAutoConnect(): Boolean {
        return prefs.getBoolean(AUTO_CONNECT_KEY, false)
    }

    fun saveAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_CONNECT_KEY, enabled).apply()
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

    /**
     * Returns the encryption key bound to a specific server id.
     * Empty string means "no encryption" (the key is optional).
     */
    fun getEncryptionKey(serverId: String): String {
        val json = prefs.getString(ENCRYPTION_KEYS_KEY, null) ?: return ""
        return try {
            val obj = JSONObject(json)
            obj.optString(serverId, "")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Persists the encryption key for a specific server id. An empty key is
     * stored as well (so a cleared key is remembered as "no encryption").
     */
    fun saveEncryptionKey(serverId: String, key: String) {
        val obj = try {
            val existing = prefs.getString(ENCRYPTION_KEYS_KEY, null)
            if (existing != null) JSONObject(existing) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        obj.put(serverId, key)
        prefs.edit().putString(ENCRYPTION_KEYS_KEY, obj.toString()).apply()
    }
}
