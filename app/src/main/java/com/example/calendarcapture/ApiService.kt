package com.example.calendarcapture

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

// Data classes at top of file
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
    val parseMethod: String = "openai",
    val openaiKey: String = "",
    val geminiKey: String = "",
    val claudeKey: String = ""
)

class ApiService {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun performOcr(provider: String, bitmap: Bitmap, settings: ApiSettings): String {
        return when (provider) {
            "openai-vision" -> callOpenAIVision(bitmap, settings.openaiKey)
            "gemini-vision" -> callGeminiVision(bitmap, settings.geminiKey)
            "claude-vision" -> callClaudeVision(bitmap, settings.claudeKey)
            else -> throw IllegalArgumentException("Unknown OCR provider: $provider")
        }
    }

    suspend fun performParse(provider: String, text: String, settings: ApiSettings): EventData {
        return when (provider) {
            "openai" -> callOpenAIParse(text, settings.openaiKey)
            "gemini" -> callGeminiParse(text, settings.geminiKey)
            "claude" -> callClaudeParse(text, settings.claudeKey)
            else -> throw IllegalArgumentException("Unknown parse provider: $provider")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildParsePrompt(text: String): String {
        val now = LocalDateTime.now()
        val nowStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        return """
You are an event extraction assistant. Current date/time: $nowStr

Extract event details from the text below into a JSON object with these exact keys:
{
  "title": "event name",
  "start": "ISO datetime (YYYY-MM-DDTHH:MM:SS)",
  "end": "ISO datetime (YYYY-MM-DDTHH:MM:SS)",
  "location": "location text (single line, no newlines)",
  "hasTime": true/false,
  "recurrence": "RRULE format if repeating, empty string otherwise"
}

CRITICAL RULES:
1. If no end time/date: add 1 hour to start
2. If no time specified: set hasTime=false (all-day event)
3. If time mentioned but no date: if time is after current hour (${now.hour}), use today; else use tomorrow
4. For "every [day]": use RRULE format: "FREQ=WEEKLY;BYDAY=XX" where XX is MO,TU,WE,TH,FR,SA,SU
5. For "every day": "FREQ=DAILY"
6. For "every month": "FREQ=MONTHLY"
7. Parse natural language: "tomorrow", "next Wednesday", "lunch" means noon
8. Location must be single line - replace newlines with commas
9. Return ONLY valid JSON, no markdown, no explanations

Text to parse:
---
$text
---

JSON:
        """.trimIndent()
    }

    private suspend fun callOpenAIVision(bitmap: Bitmap, apiKey: String): String {
        if (apiKey.isEmpty()) throw IllegalArgumentException("OpenAI API key is missing")

        val base64 = bitmapToBase64(bitmap)
        val json = JSONObject().apply {
            put("model", "gpt-4o-mini")
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

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        return executeRequest(request) { response ->
            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private suspend fun callGeminiVision(bitmap: Bitmap, apiKey: String): String {
        if (apiKey.isEmpty()) throw IllegalArgumentException("Gemini API key is missing")

        val base64 = bitmapToBase64(bitmap)
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

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(json.toString().toRequestBody(JSON))
            .build()

        return executeRequest(request) { response ->
            JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private suspend fun callClaudeVision(bitmap: Bitmap, apiKey: String): String {
        if (apiKey.isEmpty()) throw IllegalArgumentException("Claude API key is missing")

        val base64 = bitmapToBase64(bitmap)
        val json = JSONObject().apply {
            put("model", "claude-3-haiku-20240307")
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

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        return executeRequest(request) { response ->
            JSONObject(response)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private suspend fun callOpenAIParse(text: String, apiKey: String): EventData {
        if (apiKey.isEmpty()) throw IllegalArgumentException("OpenAI API key is missing")

        val json = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", buildParsePrompt(text))
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

        return executeRequest(request) { response ->
            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            parseEventJson(content)
        }
    }

    private suspend fun callGeminiParse(text: String, apiKey: String): EventData {
        if (apiKey.isEmpty()) throw IllegalArgumentException("Gemini API key is missing")

        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", buildParsePrompt(text))
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(json.toString().toRequestBody(JSON))
            .build()

        return executeRequest(request) { response ->
            val content = JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            parseEventJson(content)
        }
    }

    private suspend fun callClaudeParse(text: String, apiKey: String): EventData {
        if (apiKey.isEmpty()) throw IllegalArgumentException("Claude API key is missing")

        val json = JSONObject().apply {
            put("model", "claude-3-haiku-20240307")
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", buildParsePrompt(text))
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(json.toString().toRequestBody(JSON))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()

        return executeRequest(request) { response ->
            val content = JSONObject(response)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")

            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
                .replace("<json>", "")
                .replace("</json>", "")
                .trim()

            parseEventJson(cleaned)
        }
    }

    private fun parseEventJson(jsonString: String): EventData {
        val json = JSONObject(jsonString)

        return EventData(
            title = json.optString("title", "Untitled Event"),
            start = json.optString("start", "").takeIf { it.isNotEmpty() }?.let {
                LocalDateTime.parse(it.substring(0, 19))
            },
            end = json.optString("end", "").takeIf { it.isNotEmpty() }?.let {
                LocalDateTime.parse(it.substring(0, 19))
            },
            location = json.optString("location", "").replace("\n", ", "),
            hasTime = json.optBoolean("hasTime", true),
            isAllDay = !json.optBoolean("hasTime", true),
            recurrence = json.optString("recurrence", "")
        )
    }

    private suspend fun <T> executeRequest(request: Request, parser: (String) -> T): T {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("API Error: ${response.code} - ${response.message}")
                }
                val body = response.body?.string()
                    ?: throw IOException("Empty response body")
                parser(body)
            }
        }
    }
}