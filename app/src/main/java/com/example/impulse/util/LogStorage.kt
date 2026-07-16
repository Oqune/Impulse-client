package com.example.impulse.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogStorage {
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        logs.add("[$timestamp] $message")

        if (logs.size > 1000) {
            logs.removeAt(0)
        }
    }

    fun getLogs(): List<String> = logs.toList()
    fun clearLogs() = logs.clear()
}
