package com.example.calendarcapture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendarcapture.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.net.URL

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefsHelper by lazy { PreferencesHelper(this) }
    private val apiService by lazy { ApiService(this) }

    private var currentTestImage: Bitmap? = null
    private val DEFAULT_TEST_IMAGE_URL = "https://busboys-uploads.s3.us-west-2.amazonaws.com/wp-content/uploads/2025/09/12232115/IMG_0234-300x300.jpeg"

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadImageFromUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadKeys()
        loadDefaultTestImage()
        setupRadioButtons()
        setupDropdownListeners()
        setupClickListeners()
        updateVisibleRows()
        loadSavedSelections()
        loadLog()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadKeys() {
        val settings = prefsHelper.getSettings()
        binding.editOpenaiKey.setText(settings.openaiKey)
        binding.editGeminiKey.setText(settings.geminiKey)
    }

    private fun loadDefaultTestImage() {
        addLog("Loading default test image...")
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val url = URL(DEFAULT_TEST_IMAGE_URL)
                    BitmapFactory.decodeStream(url.openConnection().apply { connect() }.getInputStream())
                }
                currentTestImage = bitmap
                binding.testImage.setImageBitmap(bitmap)
                addLog("✓ Default test image loaded", "SUCCESS")
            } catch (e: Exception) {
                addLog("Failed to load default image: ${e.message}", "ERROR")
            }
        }
    }

    private fun setupRadioButtons() {
        binding.radioGroupOcr.setOnCheckedChangeListener { _, checkedId ->
            // Hide all config rows first
            binding.ocrOpenaiConfig.visibility = View.GONE
            binding.ocrGeminiConfig.visibility = View.GONE

            // Show selected config row and save method
            val ocrMethod = when (checkedId) {
                R.id.radio_mlkit -> "mlkit"
                R.id.radio_openai_ocr -> {
                    binding.ocrOpenaiConfig.visibility = View.VISIBLE
                    "openai-vision"
                }
                R.id.radio_gemini_ocr -> {
                    binding.ocrGeminiConfig.visibility = View.VISIBLE
                    "gemini-vision"
                }
                else -> "mlkit"
            }
            saveOcrMethod(ocrMethod)
        }

        binding.radioGroupParse.setOnCheckedChangeListener { _, checkedId ->
            // Hide all config rows first
            binding.parseOpenaiConfig.visibility = View.GONE
            binding.parseGeminiConfig.visibility = View.GONE

            // Show selected config row and save method
            val parseMethod = when (checkedId) {
                R.id.radio_openai_parse -> {
                    binding.parseOpenaiConfig.visibility = View.VISIBLE
                    "openai"
                }
                R.id.radio_gemini_parse -> {
                    binding.parseGeminiConfig.visibility = View.VISIBLE
                    "gemini"
                }
                else -> "gemini"
            }
            saveParseMethod(parseMethod)
        }
    }

    private fun setupDropdownListeners() {
        binding.spinnerOpenaiOcrModel.setOnItemClickListener { _, _, position, _ ->
            (binding.spinnerOpenaiOcrModel.adapter as? ArrayAdapter<String>)?.getItem(position)?.let { model ->
                if (model != "Select model...") saveModelSelection("openai", true, model)
            }
        }

        binding.spinnerGeminiOcrModel.setOnItemClickListener { _, _, position, _ ->
            (binding.spinnerGeminiOcrModel.adapter as? ArrayAdapter<String>)?.getItem(position)?.let { model ->
                if (model != "Select model...") saveModelSelection("gemini", true, model)
            }
        }

        binding.spinnerOpenaiParseModel.setOnItemClickListener { _, _, position, _ ->
            (binding.spinnerOpenaiParseModel.adapter as? ArrayAdapter<String>)?.getItem(position)?.let { model ->
                if (model != "Select model...") saveModelSelection("openai", false, model)
            }
        }

        binding.spinnerGeminiParseModel.setOnItemClickListener { _, _, position, _ ->
            (binding.spinnerGeminiParseModel.adapter as? ArrayAdapter<String>)?.getItem(position)?.let { model ->
                if (model != "Select model...") saveModelSelection("gemini", false, model)
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSaveKeys.setOnClickListener { saveKeys() }
        binding.btnUploadTestImage.setOnClickListener {
            imagePickerLauncher.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }

        binding.btnFetchOpenaiOcr.setOnClickListener { fetchModelsForProvider("openai", true) }
        binding.btnTestOpenaiOcr.setOnClickListener { testOcr("openai") }
        binding.btnFetchGeminiOcr.setOnClickListener { fetchModelsForProvider("gemini", true) }
        binding.btnTestGeminiOcr.setOnClickListener { testOcr("gemini") }
        binding.btnFetchOpenaiParse.setOnClickListener { fetchModelsForProvider("openai", false) }
        binding.btnTestOpenaiParse.setOnClickListener { testParse("openai") }
        binding.btnFetchGeminiParse.setOnClickListener { fetchModelsForProvider("gemini", false) }
        binding.btnTestGeminiParse.setOnClickListener { testParse("gemini") }

        binding.btnCopyLog.setOnClickListener { copyLogToClipboard() }
        binding.btnClearLog.setOnClickListener {
            prefsHelper.clearLog()
            loadLog()
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnQuit.setOnClickListener { finish() }
    }

    private fun saveKeys() {
        val settings = prefsHelper.getSettings().copy(
            openaiKey = binding.editOpenaiKey.text.toString(),
            geminiKey = binding.editGeminiKey.text.toString()
        )
        prefsHelper.saveSettings(settings)
        addLog("✓ API keys saved", "SUCCESS")
        updateVisibleRows()
    }

    private fun saveOcrMethod(method: String) {
        prefsHelper.saveSettings(prefsHelper.getSettings().copy(ocrMethod = method))
        addLog("OCR method set to: $method")
    }

    private fun saveParseMethod(method: String) {
        prefsHelper.saveSettings(prefsHelper.getSettings().copy(parseMethod = method))
        addLog("Parse method set to: $method")
    }

    private fun loadSavedSelections() {
        val settings = prefsHelper.getSettings()

        when (settings.ocrMethod) {
            "mlkit" -> binding.radioMlkit.isChecked = true
            "openai-vision" -> {
                binding.radioOpenaiOcr.isChecked = true
                binding.ocrOpenaiConfig.visibility = View.VISIBLE
            }
            "gemini-vision" -> {
                binding.radioGeminiOcr.isChecked = true
                binding.ocrGeminiConfig.visibility = View.VISIBLE
            }
        }

        when (settings.parseMethod) {
            "openai" -> {
                binding.radioOpenaiParse.isChecked = true
                binding.parseOpenaiConfig.visibility = View.VISIBLE
            }
            "gemini" -> {
                binding.radioGeminiParse.isChecked = true
                binding.parseGeminiConfig.visibility = View.VISIBLE
            }
        }
    }

    private fun updateVisibleRows() {
        val settings = prefsHelper.getSettings()
        binding.radioOpenaiOcr.visibility = if (settings.openaiKey.isNotEmpty()) View.VISIBLE else View.GONE
        binding.radioGeminiOcr.visibility = if (settings.geminiKey.isNotEmpty()) View.VISIBLE else View.GONE
        binding.radioOpenaiParse.visibility = if (settings.openaiKey.isNotEmpty()) View.VISIBLE else View.GONE
        binding.radioGeminiParse.visibility = if (settings.geminiKey.isNotEmpty()) View.VISIBLE else View.GONE

        if (settings.openaiKey.isNotEmpty()) {
            loadCachedModels("openai", true)
            loadCachedModels("openai", false)
        }
        if (settings.geminiKey.isNotEmpty()) {
            loadCachedModels("gemini", true)
            loadCachedModels("gemini", false)
        }
    }

    private fun loadCachedModels(provider: String, isOcr: Boolean) {
        prefsHelper.getCachedModels(provider).takeIf { it.isNotEmpty() }?.let {
            populateModelDropdown(provider, isOcr, it)
            addLog("Loaded cached models for $provider")
        }
    }

    private fun fetchModelsForProvider(provider: String, isOcr: Boolean) {
        val apiKey = when (provider) {
            "openai" -> prefsHelper.getSettings().openaiKey
            "gemini" -> prefsHelper.getSettings().geminiKey
            else -> ""
        }

        if (apiKey.isEmpty()) {
            addLog("No API key for $provider", "ERROR")
            return
        }

        addLog("Fetching models for $provider...")
        lifecycleScope.launch {
            try {
                val models = when (provider) {
                    "openai" -> fetchOpenAIModelsViaSelf(apiKey)
                    "gemini" -> fetchGeminiModelsViaSelf(apiKey)
                    else -> emptyList()
                }

                if (models.isNotEmpty()) {
                    prefsHelper.saveCachedModels(provider, models)
                    populateModelDropdown(provider, isOcr, models)
                    addLog("✓ Connected to $provider - loaded ${models.size} models", "SUCCESS")
                } else {
                    addLog("No models returned from $provider", "WARN")
                }
            } catch (e: Exception) {
                addLog("Failed to fetch $provider models: ${e.message}", "ERROR")
            }
        }
    }

    private suspend fun fetchOpenAIModelsViaSelf(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val json = org.json.JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("role", "user")
                put("content", """Return JSON: {"models": ["gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-5", "gpt-5-mini"]}""")
            }))
            put("response_format", org.json.JSONObject().put("type", "json_object"))
        }

        val client = okhttp3.OkHttpClient()
        client.newCall(okhttp3.Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val result = org.json.JSONObject(response.body?.string() ?: "")
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val modelsArray = org.json.JSONObject(result).getJSONArray("models")
            List(modelsArray.length()) { modelsArray.getString(it) }
        }
    }

    private suspend fun fetchGeminiModelsViaSelf(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val json = org.json.JSONObject().apply {
            put("contents", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("parts", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("text", """Return JSON array: ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash-exp", "gemini-2.0-flash"]""")
                }))
            }))
            put("generationConfig", org.json.JSONObject().put("response_mime_type", "application/json"))
        }

        val client = okhttp3.OkHttpClient()
        client.newCall(okhttp3.Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val result = org.json.JSONObject(response.body?.string() ?: "")
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
            val modelsArray = org.json.JSONArray(result)
            List(modelsArray.length()) { modelsArray.getString(it) }
                .filter { !it.contains("1.5") && !it.contains("pro-vision") }
        }
    }

    private fun populateModelDropdown(provider: String, isOcr: Boolean, models: List<String>) {
        val dropdown = when {
            provider == "openai" && isOcr -> binding.spinnerOpenaiOcrModel
            provider == "openai" && !isOcr -> binding.spinnerOpenaiParseModel
            provider == "gemini" && isOcr -> binding.spinnerGeminiOcrModel
            provider == "gemini" && !isOcr -> binding.spinnerGeminiParseModel
            else -> return
        }

        val testButton = when {
            provider == "openai" && isOcr -> binding.btnTestOpenaiOcr
            provider == "openai" && !isOcr -> binding.btnTestOpenaiParse
            provider == "gemini" && isOcr -> binding.btnTestGeminiOcr
            provider == "gemini" && !isOcr -> binding.btnTestGeminiParse
            else -> return
        }

        val items = listOf("Select model...") + models
        dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))

        val savedModel = if (isOcr) {
            if (provider == "openai") prefsHelper.getSettings().openaiOcrModel else prefsHelper.getSettings().geminiOcrModel
        } else {
            if (provider == "openai") prefsHelper.getSettings().openaiModel else prefsHelper.getSettings().geminiModel
        }

        if (savedModel.isNotEmpty() && items.contains(savedModel)) {
            dropdown.setText(savedModel, false)
            testButton.isEnabled = true
        } else {
            dropdown.setText("Select model...", false)
        }
    }

    private fun saveModelSelection(provider: String, isOcr: Boolean, model: String) {
        val settings = prefsHelper.getSettings()
        val updated = if (isOcr) {
            if (provider == "openai") settings.copy(openaiOcrModel = model) else settings.copy(geminiOcrModel = model)
        } else {
            if (provider == "openai") settings.copy(openaiModel = model) else settings.copy(geminiModel = model)
        }
        prefsHelper.saveSettings(updated)

        val testButton = when {
            provider == "openai" && isOcr -> binding.btnTestOpenaiOcr
            provider == "openai" && !isOcr -> binding.btnTestOpenaiParse
            provider == "gemini" && isOcr -> binding.btnTestGeminiOcr
            provider == "gemini" && !isOcr -> binding.btnTestGeminiParse
            else -> return
        }
        testButton.isEnabled = true
        addLog("Saved ${if (isOcr) "OCR" else "Parse"} model for $provider: $model")
    }

    private fun testOcr(provider: String) {
        currentTestImage ?: run {
            addLog("No test image loaded", "ERROR")
            return
        }

        val settings = prefsHelper.getSettings()
        val model = if (provider == "openai") settings.openaiOcrModel else settings.geminiOcrModel
        addLog("Testing OCR with $provider using model $model...")
        binding.tvOcrResult.text = "Processing..."

        lifecycleScope.launch {
            try {
                val text = apiService.performOcr("$provider-vision", currentTestImage!!, settings)
                binding.tvOcrResult.text = text
                addLog("✓ OCR successful - ${text.length} chars", "SUCCESS")
            } catch (e: Exception) {
                binding.tvOcrResult.text = "Error: ${e.message}"
                addLog("OCR failed: ${e.message}", "ERROR")
            }
        }
    }

    private fun testParse(provider: String) {
        val text = binding.editTestText.text.toString()
        if (text.isEmpty()) {
            addLog("No test text", "ERROR")
            return
        }

        val settings = prefsHelper.getSettings()
        val model = if (provider == "openai") settings.openaiModel else settings.geminiModel
        addLog("Testing Parse with $provider ($model)...")
        binding.tvParseResult.text = "Processing..."

        lifecycleScope.launch {
            try {
                val event = apiService.performParse(provider, text, settings)
                binding.tvParseResult.text = org.json.JSONObject().apply {
                    put("title", event.title)
                    put("start", event.start?.toString() ?: "null")
                    put("end", event.end?.toString() ?: "null")
                    put("location", event.location)
                    put("isAllDay", event.isAllDay)
                }.toString(2)
                addLog("✓ Parse successful", "SUCCESS")
            } catch (e: Exception) {
                binding.tvParseResult.text = "Error: ${e.message}"
                addLog("Parse failed: ${e.message}", "ERROR")
            }
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use {
                currentTestImage = BitmapFactory.decodeStream(it)
                binding.testImage.setImageBitmap(currentTestImage)
                addLog("✓ Image uploaded", "SUCCESS")
            }
        } catch (e: Exception) {
            addLog("Failed to load image: ${e.message}", "ERROR")
        }
    }

    private fun addLog(message: String, level: String = "INFO") {
        prefsHelper.addLog(message, level)
        loadLog()
    }

    private fun loadLog() {
        binding.tvLog.text = prefsHelper.getLog().ifEmpty { "No logs yet" }
    }

    private fun copyLogToClipboard() {
        val log = prefsHelper.getLog()
        if (log.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }

        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("CalendarCapture Log", log))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        addLog("Log copied to clipboard")
    }
}