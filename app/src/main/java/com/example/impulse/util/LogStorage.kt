package com.example.impulse.util

object LogStorage {
    private val logs = mutableListOf<String>()
    
    fun addLog(message: String) {
        // Добавляем временную метку
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logs.add("[$timestamp] $message")
        
        // Ограничиваем размер логов для предотвращения переполнения памяти
        if (logs.size > 1000) {
            logs.removeAt(0)
        }
    }
    
    fun getLogs(): List<String> = logs.toList()
    fun clearLogs() = logs.clear()
}