package com.example.calendarcapture

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

class PreferencesHelper(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("CalendarCapturePrefs", Context.MODE_PRIVATE)

    fun getSettings(): ApiSettings {
        return ApiSettings(
            ocrMethod = prefs.getString("ocr_method", "mlkit") ?: "mlkit",
            parseMethod = prefs.getString("parse_method", "openai") ?: "openai",
            openaiKey = prefs.getString("openai_key", "") ?: "",
            geminiKey = prefs.getString("gemini_key", "") ?: "",
            claudeKey = prefs.getString("claude_key", "") ?: ""
        )
    }

    fun saveSettings(settings: ApiSettings) {
        prefs.edit().apply {
            putString("ocr_method", settings.ocrMethod)
            putString("parse_method", settings.parseMethod)
            putString("openai_key", settings.openaiKey)
            putString("gemini_key", settings.geminiKey)
            putString("claude_key", settings.claudeKey)
            apply()
        }
    }

    fun saveOcrMethod(method: String) {
        prefs.edit().putString("ocr_method", method).apply()
    }

    fun saveParseMethod(method: String) {
        prefs.edit().putString("parse_method", method).apply()
    }

    fun saveApiKey(provider: String, key: String) {
        prefs.edit().putString("${provider}_key", key).apply()
    }

    // Time format preference
    fun is24HourFormat(): Boolean {
        return prefs.getBoolean("use_24_hour", false)
    }

    fun set24HourFormat(use24Hour: Boolean) {
        prefs.edit().putBoolean("use_24_hour", use24Hour).apply()
    }

    // Logging functionality
    fun addLog(message: String, type: String = "INFO") {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] [$type] $message"

        val currentLog = prefs.getString("app_log", "") ?: ""
        val newLog = "$logEntry\n$currentLog"

        // Keep only last 100 lines
        val lines = newLog.split("\n").take(100)
        prefs.edit().putString("app_log", lines.joinToString("\n")).apply()
    }

    fun getLog(): String {
        return prefs.getString("app_log", "No logs yet") ?: "No logs yet"
    }

    fun clearLog() {
        prefs.edit().putString("app_log", "").apply()
    }
}