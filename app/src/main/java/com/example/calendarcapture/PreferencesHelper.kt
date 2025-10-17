package com.example.calendarcapture

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class PreferencesHelper(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "CalendarCapturePrefs"
        private const val KEY_OCR_METHOD = "ocr_method"
        private const val KEY_PARSE_METHOD = "parse_method"
        private const val KEY_OPENAI_KEY = "openai_key"
        private const val KEY_GEMINI_KEY = "gemini_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_OPENAI_OCR_MODEL = "openai_ocr_model"
        private const val KEY_GEMINI_OCR_MODEL = "gemini_ocr_model"
        private const val KEY_LOG = "debug_log"
        private const val KEY_CACHED_MODELS_PREFIX = "cached_models_"
        private const val MAX_LOG_LINES = 500
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSettings(settings: ApiSettings) {
        prefs.edit().apply {
            putString(KEY_OCR_METHOD, settings.ocrMethod)
            putString(KEY_PARSE_METHOD, settings.parseMethod)
            putString(KEY_OPENAI_KEY, settings.openaiKey)
            putString(KEY_GEMINI_KEY, settings.geminiKey)
            putString(KEY_OPENAI_MODEL, settings.openaiModel)
            putString(KEY_GEMINI_MODEL, settings.geminiModel)
            putString(KEY_OPENAI_OCR_MODEL, settings.openaiOcrModel)
            putString(KEY_GEMINI_OCR_MODEL, settings.geminiOcrModel)
            apply()
        }
    }

    fun getSettings(): ApiSettings {
        return ApiSettings(
            ocrMethod = prefs.getString(KEY_OCR_METHOD, "mlkit") ?: "mlkit",
            parseMethod = prefs.getString(KEY_PARSE_METHOD, "gemini") ?: "gemini",
            openaiKey = prefs.getString(KEY_OPENAI_KEY, "") ?: "",
            geminiKey = prefs.getString(KEY_GEMINI_KEY, "") ?: "",
            openaiModel = prefs.getString(KEY_OPENAI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash",
            openaiOcrModel = prefs.getString(KEY_OPENAI_OCR_MODEL, "gpt-4o") ?: "gpt-4o",
            geminiOcrModel = prefs.getString(KEY_GEMINI_OCR_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash"
        )
    }

    fun saveCachedModels(provider: String, models: List<String>) {
        val json = JSONArray()
        models.forEach { json.put(it) }
        prefs.edit().putString("${KEY_CACHED_MODELS_PREFIX}$provider", json.toString()).apply()
    }

    fun getCachedModels(provider: String): List<String> {
        val jsonString = prefs.getString("${KEY_CACHED_MODELS_PREFIX}$provider", null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            List(jsonArray.length()) { i -> jsonArray.getString(i) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addLog(message: String, level: String = "INFO") {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] [$level] $message"

        val currentLog = prefs.getString(KEY_LOG, "") ?: ""
        val lines = currentLog.split("\n").toMutableList()

        lines.add(0, logEntry)

        // Keep only last MAX_LOG_LINES
        if (lines.size > MAX_LOG_LINES) {
            lines.subList(MAX_LOG_LINES, lines.size).clear()
        }

        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun getLog(): String {
        return prefs.getString(KEY_LOG, "") ?: ""
    }

    fun clearLog() {
        prefs.edit().putString(KEY_LOG, "").apply()
    }
}