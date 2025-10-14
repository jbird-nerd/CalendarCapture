package com.example.calendarcapture

import android.content.Context
import android.content.SharedPreferences

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
}