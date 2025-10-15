package com.example.calendarcapture

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.calendarcapture.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefsHelper by lazy { PreferencesHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupClickListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSettings() {
        val settings = prefsHelper.getSettings()

        // OCR method
        when (settings.ocrMethod) {
            "mlkit" -> binding.radioOcrMlkit.isChecked = true
            "openai-vision" -> binding.radioOcrOpenai.isChecked = true
            "gemini-vision" -> binding.radioOcrGemini.isChecked = true
            "claude-vision" -> binding.radioOcrClaude.isChecked = true
        }

        // Parse method
        when (settings.parseMethod) {
            "openai" -> binding.radioParseOpenai.isChecked = true
            "gemini" -> binding.radioParseGemini.isChecked = true
            "claude" -> binding.radioParseClaude.isChecked = true
        }

        // API keys
        binding.editOpenaiKey.setText(settings.openaiKey)
        binding.editGeminiKey.setText(settings.geminiKey)
        binding.editClaudeKey.setText(settings.claudeKey)

        // Time format
        binding.checkbox24Hour.isChecked = prefsHelper.is24HourFormat()
    }

    private fun setupClickListeners() {
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val ocrMethod = when {
            binding.radioOcrMlkit.isChecked -> "mlkit"
            binding.radioOcrOpenai.isChecked -> "openai-vision"
            binding.radioOcrGemini.isChecked -> "gemini-vision"
            binding.radioOcrClaude.isChecked -> "claude-vision"
            else -> "mlkit"
        }

        val parseMethod = when {
            binding.radioParseOpenai.isChecked -> "openai"
            binding.radioParseGemini.isChecked -> "gemini"
            binding.radioParseClaude.isChecked -> "claude"
            else -> "openai"
        }

        val settings = ApiSettings(
            ocrMethod = ocrMethod,
            parseMethod = parseMethod,
            openaiKey = binding.editOpenaiKey.text.toString().trim(),
            geminiKey = binding.editGeminiKey.text.toString().trim(),
            claudeKey = binding.editClaudeKey.text.toString().trim()
        )

        // Validate that required keys are present
        val errors = mutableListOf<String>()

        if (ocrMethod != "mlkit" && getKeyForMethod(ocrMethod, settings).isEmpty()) {
            errors.add("API key required for $ocrMethod")
        }

        if (getKeyForMethod(parseMethod, settings).isEmpty()) {
            errors.add("API key required for $parseMethod")
        }

        if (errors.isNotEmpty()) {
            Toast.makeText(this, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
            return
        }

        prefsHelper.saveSettings(settings)

        // Save time format preference
        prefsHelper.set24HourFormat(binding.checkbox24Hour.isChecked)

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun getKeyForMethod(method: String, settings: ApiSettings): String {
        return when {
            method.contains("openai") -> settings.openaiKey
            method.contains("gemini") -> settings.geminiKey
            method.contains("claude") -> settings.claudeKey
            else -> ""
        }
    }
}