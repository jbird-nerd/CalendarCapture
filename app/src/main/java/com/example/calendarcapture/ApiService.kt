package com.example.calendarcapture

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class EventData(
    val title: String = "",
    val start: LocalDateTime? = null,
    val end: LocalDateTime? = null,
    val location: String = "",
    val hasTime: Boolean = true,
    val isAllDay: Boolean = false,
    val recurrence: String = ""
)

data class ApiSettings(
    val ocrMethod: String = "mlkit",
    val parseMethod: String = "gemini",
    val openaiKey: String = "",
    val geminiKey: String = "",
    val openaiModel: String = "gpt-4o-mini",
    val geminiModel: String = "gemini-2.5-flash",
    val openaiOcrModel: String = "gpt-4o",
    val geminiOcrModel: String = "gemini-2.5-flash"
)

class ApiService(private val context: Context) {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val prefsHelper by lazy { PreferencesHelper(context) }

    suspend fun performOcr(provider: String, bitmap: Bitmap, settings: ApiSettings): String {
        prefsHelper.addLog("OCR: Starting with provider=$provider", "DEBUG")

        return when (provider) {
            "openai-vision" -> {
                prefsHelper.addLog("OCR: Calling OpenAI Vision with model ${settings.openaiOcrModel}", "DEBUG")
                callOpenAIVision(bitmap, settings.openaiKey, settings.openaiOcrModel)
            }
            "gemini-vision" -> {
                prefsHelper.addLog("OCR: Calling Gemini Vision with model ${settings.geminiOcrModel}", "DEBUG")
                callGeminiVision(bitmap, settings.geminiKey, settings.geminiOcrModel)
            }
            else -> throw IllegalArgumentException("Unknown OCR provider: $provider")
        }
    }
    suspend fun performParse(provider: String, text: String, settings: ApiSettings): EventData {
        prefsHelper.addLog("Parse: Starting with provider=$provider", "DEBUG")
        prefsHelper.addLog("Parse: Input text='${text.take(100)}...'", "DEBUG")

        return when (provider) {
            "openai" -> {
                prefsHelper.addLog("Parse: Calling OpenAI with model ${settings.openaiModel}", "DEBUG")
                callOpenAIParse(text, settings.openaiKey, settings.openaiModel)
            }
            "gemini" -> {
                prefsHelper.addLog("Parse: Calling Gemini with model ${settings.geminiModel}", "DEBUG")
                callGeminiParse(text, settings.geminiKey, settings.geminiModel)
            }
            else -> throw IllegalArgumentException("Unknown parse provider: $provider")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val bytes = outputStream.toByteArray()
        prefsHelper.addLog("Image: Converted to base64, size=${bytes.size} bytes", "DEBUG")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun buildParsePrompt(text: String): String {
        val now = LocalDateTime.now()
        val zone = java.util.TimeZone.getDefault()
        val zoneName = zone.getDisplayName(false, java.util.TimeZone.SHORT)
        val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val currentHour = now.hour

        return """
You are an event extraction assistant. 
Current local date/time: $nowStr
Current timezone: $zoneName
Current day: ${now.dayOfWeek}
Current hour: $currentHour

Extract event details from the text below into a JSON object with these exact keys:
{
  "title": "event name",
  "start": "ISO datetime (YYYY-MM-DDTHH:MM:SS) or null",
  "end": "ISO datetime (YYYY-MM-DDTHH:MM:SS) or null",
  "location": "Venue Name\nStreet Address, City, State ZIP",
  "hasTime": true/false,
  "recurrence": "RRULE format if repeating, empty string otherwise"
}

CRITICAL DATE/TIME RULES:
1. ALL TIMES MUST BE IN THE USER'S LOCAL TIMEZONE ($zoneName), NOT UTC OR GMT
2. If text shows "8:00PM", output "20:00:00" in the start time

3. SMART DATE/TIME INFERENCE - If you have ANY date/time info, infer the rest:
   - Has DAY but no DATE: Use the next occurrence of that day (if text says "Thursday" and today is Monday, use this coming Thursday)
   - Has TIME but no DATE/DAY: If time > current hour ($currentHour), use today; else use tomorrow
   - Has DATE but no TIME: Set hasTime=false (all-day event), but still set start to DATE at 00:00:00 and end to DATE at 23:59:59
   - Has DATE and DAY but no TIME: Set hasTime=false, use the date provided
   
4. MISSING DATE/TIME - CRITICAL:
   - If NO date, NO day, AND NO time mentioned: Set start=null and end=null
   - Do NOT make up dates if there's no temporal information at all
   
5. END TIME: If start time is known but no end time: add 1-2 hours (2 hours for events, 1 hour for meetings)

6. For "every [day]": use RRULE format: "FREQ=WEEKLY;BYDAY=XX" where XX is MO,TU,WE,TH,FR,SA,SU
7. For "every day": "FREQ=DAILY"
8. Parse natural language: "tomorrow", "next Wednesday", "lunch" means 12:00

9. For location: Format as "Venue Name\nStreet Address, City, State ZIP" on separate lines

10. Return ONLY valid JSON, no markdown, no explanations

Examples:
- "Meeting Thursday" → start: next Thursday 09:00:00, end: next Thursday 10:00:00, hasTime: false
- "Lunch tomorrow at 1pm" → start: tomorrow 13:00:00, end: tomorrow 14:00:00, hasTime: true
- "Party at 8PM" → start: today 20:00:00 (if after current hour) or tomorrow 20:00:00, end: +2 hours
- "Dentist appointment on March 15" → start: 2025-03-15T00:00:00, end: 2025-03-15T23:59:59, hasTime: false
- "Call Mom" → start: null, end: null (no date/time info)
- "what the heck" → start: null, end: null (no date/time info)

Text to parse:
---
$text
---

JSON:
        """.trimIndent()
    }

    private suspend fun callOpenAIVision(bitmap: Bitmap, apiKey: String, model: String): String {
        if (apiKey.isEmpty()) {
            prefsHelper.addLog("OpenAI Vision: API key is missing", "ERROR")
            throw IllegalArgumentException("OpenAI API key is missing")
        }

        prefsHelper.addLog("OpenAI Vision: Converting image to base64", "DEBUG")
        val base64 = bitmapToBase64(bitmap)

        prefsHelper.addLog("OpenAI Vision: Using model $model", "DEBUG")
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Extract all text from this image exactly as it appears.")
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/png;base64,$base64")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 2000)
        }

        prefsHelper.addLog("OpenAI Vision: Request payload size=${json.toString().length} chars", "DEBUG")

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        prefsHelper.addLog("OpenAI Vision: Sending request", "DEBUG")
        return executeRequest(request, "OpenAI Vision") { response ->
            prefsHelper.addLog("OpenAI Vision: Raw response=${response.take(200)}...", "DEBUG")

            val result = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            prefsHelper.addLog("OpenAI Vision: Extracted text='${result.take(100)}...'", "DEBUG")
            result
        }
    }

    private suspend fun callGeminiVision(bitmap: Bitmap, apiKey: String, model: String): String {
        if (apiKey.isEmpty()) {
            prefsHelper.addLog("Gemini Vision: API key is missing", "ERROR")
            throw IllegalArgumentException("Gemini API key is missing")
        }

        prefsHelper.addLog("Gemini Vision: Converting image to base64", "DEBUG")
        val base64 = bitmapToBase64(bitmap)

        prefsHelper.addLog("Gemini Vision: Using model $model", "DEBUG")
        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Extract all text from this image exactly as it appears.")
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/png")
                                put("data", base64)
                            })
                        })
                    })
                })
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        prefsHelper.addLog("Gemini Vision: Request URL=$url", "DEBUG")
        prefsHelper.addLog("Gemini Vision: Request payload size=${json.toString().length} chars", "DEBUG")

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
            .build()

        return executeRequest(request, "Gemini Vision") { response ->
            prefsHelper.addLog("Gemini Vision: Raw response=${response.take(200)}...", "DEBUG")

            val result = JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            prefsHelper.addLog("Gemini Vision: Extracted text='${result.take(100)}...'", "DEBUG")
            result
        }
    }

    private suspend fun callClaudeVision(bitmap: Bitmap, apiKey: String, model: String): String {
        if (apiKey.isEmpty()) {
            prefsHelper.addLog("Claude Vision: API key is missing", "ERROR")
            throw IllegalArgumentException("Claude API key is missing")
        }

        prefsHelper.addLog("Claude Vision: Converting image to base64", "DEBUG")
        val base64 = bitmapToBase64(bitmap)

        prefsHelper.addLog("Claude Vision: Using model $model", "DEBUG")
        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2000)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", base64)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Extract all text from this image exactly as it appears.")
                        })
                    })
                })
            })
        }

        prefsHelper.addLog("Claude Vision: Request payload size=${json.toString().length} chars", "DEBUG")

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        prefsHelper.addLog("Claude Vision: Sending request", "DEBUG")
        return executeRequest(request, "Claude Vision") { response ->
            prefsHelper.addLog("Claude Vision: Raw response=${response.take(200)}...", "DEBUG")

            val result = JSONObject(response)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            prefsHelper.addLog("Claude Vision: Extracted text='${result.take(100)}...'", "DEBUG")
            result
        }
    }

    private suspend fun callOpenAIParse(text: String, apiKey: String, model: String): EventData {
        if (apiKey.isEmpty()) {
            prefsHelper.addLog("OpenAI Parse: API key is missing", "ERROR")
            throw IllegalArgumentException("OpenAI API key is missing")
        }

        val prompt = buildParsePrompt(text)
        prefsHelper.addLog("OpenAI Parse: Using model $model", "DEBUG")
        prefsHelper.addLog("OpenAI Parse: Prompt length=${prompt.length} chars", "DEBUG")

        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        prefsHelper.addLog("OpenAI Parse: Sending request", "DEBUG")
        return executeRequest(request, "OpenAI Parse") { response ->
            prefsHelper.addLog("OpenAI Parse: Raw response=${response.take(300)}...", "DEBUG")

            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            prefsHelper.addLog("OpenAI Parse: Parsed JSON='$content'", "DEBUG")
            parseEventJson(content)
        }
    }

    private suspend fun callGeminiParse(text: String, apiKey: String, model: String): EventData {
        if (apiKey.isEmpty()) {
            prefsHelper.addLog("Gemini Parse: API key is missing", "ERROR")
            throw IllegalArgumentException("Gemini API key is missing")
        }

        val prompt = buildParsePrompt(text)
        prefsHelper.addLog("Gemini Parse: Using model $model", "DEBUG")
        prefsHelper.addLog("Gemini Parse: Prompt length=${prompt.length} chars", "DEBUG")

        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        prefsHelper.addLog("Gemini Parse: Request URL=$url", "DEBUG")

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
            .build()

        return executeRequest(request, "Gemini Parse") { response ->
            prefsHelper.addLog("Gemini Parse: Raw response=${response.take(300)}...", "DEBUG")

            val content = JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            prefsHelper.addLog("Gemini Parse: Parsed JSON='$content'", "DEBUG")
            parseEventJson(content)
        }
    }

    private suspend fun callClaudeParse(text: String, apiKey: String, model: String): EventData {
        if (apiKey.isEmpty()) {
            prefsHelper.addLog("Claude Parse: API key is missing", "ERROR")
            throw IllegalArgumentException("Claude API key is missing")
        }

        val prompt = buildParsePrompt(text)
        prefsHelper.addLog("Claude Parse: Using model $model", "DEBUG")
        prefsHelper.addLog("Claude Parse: Prompt length=${prompt.length} chars", "DEBUG")

        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        prefsHelper.addLog("Claude Parse: Sending request", "DEBUG")
        return executeRequest(request, "Claude Parse") { response ->
            prefsHelper.addLog("Claude Parse: Raw response=${response.take(300)}...", "DEBUG")

            val content = JSONObject(response)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            prefsHelper.addLog("Claude Parse: Response text='$content'", "DEBUG")

            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
                .replace("<json>", "")
                .replace("</json>", "")
                .trim()

            prefsHelper.addLog("Claude Parse: Cleaned JSON='$cleaned'", "DEBUG")
            parseEventJson(cleaned)
        }
    }

    private fun parseEventJson(jsonString: String): EventData {
        prefsHelper.addLog("Parse JSON: Input='$jsonString'", "DEBUG")

        try {
            val json = JSONObject(jsonString)

            val startStr = json.optString("start", "")
            val endStr = json.optString("end", "")

            val start = if (startStr.isNotEmpty() && startStr != "null") {
                LocalDateTime.parse(startStr.substring(0, 19))
            } else null

            val end = if (endStr.isNotEmpty() && endStr != "null") {
                LocalDateTime.parse(endStr.substring(0, 19))
            } else null

            val event = EventData(
                title = json.optString("title", "Untitled Event"),
                start = start,
                end = end,
                location = json.optString("location", ""),
                hasTime = json.optBoolean("hasTime", true),
                isAllDay = !json.optBoolean("hasTime", true),
                recurrence = json.optString("recurrence", "")
            )

            prefsHelper.addLog("Parse JSON: Result - title='${event.title}', start=$start, end=$end, hasTime=${event.hasTime}", "DEBUG")
            return event

        } catch (e: Exception) {
            prefsHelper.addLog("Parse JSON: ERROR - ${e.message}", "ERROR")
            prefsHelper.addLog("Parse JSON: Failed string was: $jsonString", "ERROR")
            throw e
        }
    }

    private suspend fun <T> executeRequest(request: Request, apiName: String, parser: (String) -> T): T {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                prefsHelper.addLog("$apiName: HTTP ${request.method} ${request.url}", "DEBUG")

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val message = response.message

                    prefsHelper.addLog("$apiName: Response HTTP $code $message", "DEBUG")

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error body"
                        prefsHelper.addLog("$apiName: ERROR HTTP $code", "ERROR")
                        prefsHelper.addLog("$apiName: Error message: $message", "ERROR")
                        prefsHelper.addLog("$apiName: Error body: ${errorBody.take(500)}", "ERROR")
                        throw IOException("API Error: $code - $message")
                    }

                    val body = response.body?.string()
                        ?: throw IOException("Empty response body")

                    prefsHelper.addLog("$apiName: Response body length=${body.length} chars", "DEBUG")
                    parser(body)
                }
            } catch (e: IOException) {
                prefsHelper.addLog("$apiName: Network error - ${e.message}", "ERROR")
                throw e
            } catch (e: Exception) {
                prefsHelper.addLog("$apiName: Unexpected error - ${e.javaClass.simpleName}: ${e.message}", "ERROR")
                throw e
            }
        }
    }

    // Fetch available models from APIs

}