package com.example.calendarcapture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendarcapture.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val ocrHandler = OcrHandler()
    private val apiService by lazy { ApiService(this) }
    private val calendarHelper = CalendarHelper()
    private val prefsHelper by lazy { PreferencesHelper(this) }

    private var currentBitmap: Bitmap? = null
    private var currentOcrText: String = ""
    private var currentEvent: EventData? = null
    private var isShareFlow: Boolean = false
    private var isOnFieldsScreen: Boolean = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadImageFromUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()
        setupTextWatcher()

        val isShare = when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    handleSharedImage(intent)
                    true
                } else if (intent.type == "text/plain") {
                    handleSharedText(intent)
                    true
                } else false
            }
            Intent.ACTION_PROCESS_TEXT -> {
                handleProcessText(intent)
                true
            }
            else -> false
        }

        isShareFlow = isShare

        if (!isShare) {
            showCaptureScreen()
            prefsHelper.addLog("App launched - showing Capture screen", "INFO")
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "CalendarCapture"
    }

    private fun setupTextWatcher() {
        binding.editManualText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnParseText.isEnabled = !s.isNullOrEmpty()
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnAddToCalendar.setOnClickListener {
            addToCalendar()
        }

        binding.btnParseText.setOnClickListener {
            val text = binding.editManualText.text.toString()
            if (text.isNotEmpty()) {
                binding.manualEntryCard.visibility = View.GONE
                processText(text)
            }
        }

        binding.btnUploadImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            imagePickerLauncher.launch(intent)
        }

        binding.btnQuitFields.setOnClickListener {
            finish()
        }

        binding.btnReset.setOnClickListener {
            resetToCaptureScreen()
        }

        binding.logoPositiveRdMain.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://positiverandd.com"))
            startActivity(intent)
        }
    }

    private fun showCaptureScreen() {
        isOnFieldsScreen = false
        binding.apply {
            manualEntryCard.visibility = View.VISIBLE
            imagePreview.visibility = View.GONE
            ocrTextCard.visibility = View.GONE
            eventDetailsCard.visibility = View.GONE
            fieldsButtons.visibility = View.GONE
            btnReset.visibility = View.GONE
            statusContainer.visibility = View.GONE
        }
        invalidateOptionsMenu()
    }

    private fun showFieldsScreen() {
        isOnFieldsScreen = true
        binding.apply {
            manualEntryCard.visibility = View.GONE
            btnReset.visibility = View.GONE
        }
        invalidateOptionsMenu()
    }

    private fun resetToCaptureScreen() {
        currentBitmap = null
        currentOcrText = ""
        currentEvent = null

        binding.apply {
            imagePreview.setImageBitmap(null)
            editManualText.setText("")
            btnParseText.isEnabled = false
        }

        showCaptureScreen()
        prefsHelper.addLog("Reset to Capture screen", "INFO")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        // Hide "Capture" option when already on capture screen
        menu.findItem(R.id.action_capture)?.isVisible = isOnFieldsScreen

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_capture -> {
                resetToCaptureScreen()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_quit -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleSharedImage(intent: Intent) {
        (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
            loadImageFromUri(uri)
        }
    }

    private fun handleSharedText(intent: Intent) {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            processText(text)
        }
    }

    private fun handleProcessText(intent: Intent) {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrEmpty()) {
            processText(text)
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                currentBitmap = bitmap
                binding.imagePreview.setImageBitmap(bitmap)
                binding.imagePreview.visibility = View.VISIBLE
                binding.manualEntryCard.visibility = View.GONE
                processImage(bitmap)
            }
        } catch (e: Exception) {
            showError("Failed to load image: ${e.message}")
            prefsHelper.addLog("Image load error: ${e.message}", "ERROR")
        }
    }

    private fun processImage(bitmap: Bitmap) {
        val settings = prefsHelper.getSettings()

        // Determine OCR provider based on what was configured in settings
        val ocrProvider = when (settings.ocrMethod) {
            "mlkit" -> "ML Kit"
            "openai-vision" -> "OpenAI Vision"
            "gemini-vision" -> "Gemini Vision"
            else -> settings.ocrMethod
        }

        showLoading("Extracting text with $ocrProvider...", settings.ocrMethod)
        prefsHelper.addLog("Starting OCR with ${settings.ocrMethod}", "INFO")

        lifecycleScope.launch {
            try {
                val text = when (settings.ocrMethod) {
                    "mlkit" -> ocrHandler.performOcr(bitmap)
                    else -> apiService.performOcr(settings.ocrMethod, bitmap, settings)
                }

                currentOcrText = text
                binding.tvOcrText.setText(text)
                binding.ocrTextCard.visibility = View.VISIBLE

                if (text.isEmpty()) {
                    hideLoading()
                    showError("No text found in image")
                    prefsHelper.addLog("OCR returned empty text", "WARN")
                    binding.btnReset.visibility = View.VISIBLE
                    return@launch
                }

                prefsHelper.addLog("OCR extracted ${text.length} chars", "INFO")

                updateStatus("Parsing with ${settings.parseMethod}...", settings.parseMethod)
                val event = apiService.performParse(settings.parseMethod, text, settings)
                currentEvent = event

                prefsHelper.addLog("Parse result: start=${event.start}, end=${event.end}, title='${event.title}'", "INFO")

                if (event.start == null && event.end == null) {
                    hideLoading()

                    if (isShareFlow) {
                        prefsHelper.addLog("Share flow: No date info, going to Fields with empty data", "WARN")
                        showFieldsScreen()
                        displayEvent(event)
                    } else {
                        showError("No date information found")
                        prefsHelper.addLog("Capture: No date info, staying on Capture screen", "WARN")
                        binding.btnReset.visibility = View.VISIBLE
                    }
                    return@launch
                }

                prefsHelper.addLog("Parsed successfully", "INFO")
                showFieldsScreen()
                displayEvent(event)
                hideLoading()

            } catch (e: Exception) {
                hideLoading()
                showError("Error: ${e.message}")
                prefsHelper.addLog("Processing error: ${e.message}", "ERROR")
                binding.btnReset.visibility = View.VISIBLE
                e.printStackTrace()
            }
        }
    }
    private fun processText(text: String) {
        val settings = prefsHelper.getSettings()

        currentOcrText = text
        binding.tvOcrText.setText(text)
        binding.ocrTextCard.visibility = View.VISIBLE

        showLoading("Parsing with ${settings.parseMethod}...", settings.parseMethod)
        prefsHelper.addLog("Parsing text (${text.length} chars) with ${settings.parseMethod}", "INFO")

        lifecycleScope.launch {
            try {
                val event = apiService.performParse(settings.parseMethod, text, settings)
                currentEvent = event

                prefsHelper.addLog("Parse result: start=${event.start}, end=${event.end}, title='${event.title}'", "INFO")

                if (event.start == null && event.end == null) {
                    hideLoading()
                    showError("No date information found")
                    prefsHelper.addLog("Parse found no date/time info - staying on Capture screen", "WARN")

                    // KEEP manual entry card visible so user can try again
                    binding.manualEntryCard.visibility = View.VISIBLE
                    binding.btnParseText.isEnabled = true
                    return@launch
                }

                // SUCCESS - hide manual entry and show fields
                binding.manualEntryCard.visibility = View.GONE
                prefsHelper.addLog("Parsed successfully", "INFO")
                showFieldsScreen()
                displayEvent(event)
                hideLoading()
            } catch (e: Exception) {
                hideLoading()
                showError("Error parsing: ${e.message}")
                prefsHelper.addLog("Parse error: ${e.message}", "ERROR")

                // KEEP manual entry card visible so user can try again
                binding.manualEntryCard.visibility = View.VISIBLE
                binding.btnParseText.isEnabled = true
                e.printStackTrace()
            }
        }
    }
    private fun displayEvent(event: EventData) {
        // Always use 12-hour format
        val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

        binding.apply {
            eventDetailsCard.visibility = View.VISIBLE
            fieldsButtons.visibility = View.VISIBLE

            editTitle.setText(event.title)
            editLocation.setText(event.location)
            checkboxAllDay.isChecked = event.isAllDay

            event.start?.let { start ->
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                editStartDate.setText(start.format(dateFormatter))
                editStartTime.setText(start.format(timeFormatter))
                editStartTime.isEnabled = !event.isAllDay
            }

            event.end?.let { end ->
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                editEndDate.setText(end.format(dateFormatter))
                editEndTime.setText(end.format(timeFormatter))
                editEndTime.isEnabled = !event.isAllDay
            }

            if (event.recurrence.isNotEmpty()) {
                tvRecurrence.text = "Repeats: ${parseRecurrenceToHuman(event.recurrence)}"
                tvRecurrence.visibility = View.VISIBLE
            } else {
                tvRecurrence.visibility = View.GONE
            }

            checkboxAllDay.setOnCheckedChangeListener { _, isChecked ->
                editStartTime.isEnabled = !isChecked
                editEndTime.isEnabled = !isChecked
            }

            updateStatus("Ready", null)
        }
    }

    private fun parseRecurrenceToHuman(rrule: String): String {
        return when {
            rrule.contains("FREQ=DAILY") -> "Daily"
            rrule.contains("FREQ=WEEKLY") -> {
                val days = when {
                    rrule.contains("BYDAY=MO") -> "Monday"
                    rrule.contains("BYDAY=TU") -> "Tuesday"
                    rrule.contains("BYDAY=WE") -> "Wednesday"
                    rrule.contains("BYDAY=TH") -> "Thursday"
                    rrule.contains("BYDAY=FR") -> "Friday"
                    rrule.contains("BYDAY=SA") -> "Saturday"
                    rrule.contains("BYDAY=SU") -> "Sunday"
                    else -> ""
                }
                if (days.isNotEmpty()) "Every $days" else "Weekly"
            }
            rrule.contains("FREQ=MONTHLY") -> "Monthly"
            rrule.contains("FREQ=YEARLY") -> "Yearly"
            else -> rrule
        }
    }

    private fun addToCalendar() {
        val event = currentEvent ?: return

        try {
            // Always use 12-hour format
            val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

            val startDate = binding.editStartDate.text.toString()
            val startTime = binding.editStartTime.text.toString()
            val endDate = binding.editEndDate.text.toString()
            val endTime = binding.editEndTime.text.toString()

            val newStart = if (startDate.isNotEmpty() && startTime.isNotEmpty()) {
                val parsedTime = java.time.LocalTime.parse(startTime, timeFormatter)
                val parsedDate = java.time.LocalDate.parse(startDate, dateFormatter)
                LocalDateTime.of(parsedDate, parsedTime)
            } else {
                event.start
            }

            val newEnd = if (endDate.isNotEmpty() && endTime.isNotEmpty()) {
                val parsedTime = java.time.LocalTime.parse(endTime, timeFormatter)
                val parsedDate = java.time.LocalDate.parse(endDate, dateFormatter)
                LocalDateTime.of(parsedDate, parsedTime)
            } else {
                event.end
            }

            val editedEvent = event.copy(
                title = binding.editTitle.text.toString(),
                location = binding.editLocation.text.toString(),
                isAllDay = binding.checkboxAllDay.isChecked,
                start = newStart,
                end = newEnd
            )

            val intent = calendarHelper.createCalendarIntent(this, editedEvent, currentOcrText)
            startActivity(intent)
            Toast.makeText(this, "Opening calendar...", Toast.LENGTH_SHORT).show()
            prefsHelper.addLog("Event added: ${editedEvent.title}", "INFO")

            finish()

        } catch (e: Exception) {
            showError("Failed to open calendar: ${e.message}")
            prefsHelper.addLog("Calendar error: ${e.message}", "ERROR")
        }
    }

    private fun showLoading(message: String, llmProvider: String? = null) {
        binding.apply {
            statusContainer.visibility = View.VISIBLE
            tvStatus.text = message

            llmProvider?.let { provider ->
                val gifResId = when {
                    provider.contains("openai") -> R.drawable.chatgpt
                    provider.contains("gemini") -> R.drawable.gemini
                    provider.contains("claude") -> R.drawable.claude
                    else -> null
                }

                if (gifResId != null) {
                    llmLogo.setImageResource(gifResId)
                    llmLogo.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        (llmLogo.drawable as? AnimatedImageDrawable)?.start()
                    }
                } else {
                    llmLogo.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                }
            } ?: run {
                llmLogo.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
            }

            btnAddToCalendar.isEnabled = false
        }
    }

    private fun hideLoading() {
        binding.apply {
            statusContainer.visibility = View.GONE
            progressBar.visibility = View.GONE
            llmLogo.visibility = View.GONE
            btnAddToCalendar.isEnabled = true
        }
    }

    private fun updateStatus(message: String, llmProvider: String? = null) {
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.progressBar.visibility = View.GONE

        llmProvider?.let { provider ->
            val gifResId = when {
                provider.contains("openai") -> R.drawable.chatgpt
                provider.contains("gemini") -> R.drawable.gemini
                provider.contains("claude") -> R.drawable.claude
                else -> null
            }

            if (gifResId != null) {
                binding.llmLogo.setImageResource(gifResId)
                binding.llmLogo.visibility = View.VISIBLE
            } else {
                binding.llmLogo.visibility = View.GONE
            }
        } ?: run {
            binding.llmLogo.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.progressBar.visibility = View.GONE
        binding.llmLogo.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHandler.close()
    }
}