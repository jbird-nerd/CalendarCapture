package com.example.calendarcapture

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.calendarcapture.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val ocrHandler = OcrHandler()
    private val apiService = ApiService()
    private val calendarHelper = CalendarHelper()
    private val prefsHelper by lazy { PreferencesHelper(this) }

    private var currentBitmap: Bitmap? = null
    private var currentOcrText: String = ""
    private var currentEvent: EventData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()

        // Handle shared image or text
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    handleSharedImage(intent)
                } else if (intent.type == "text/plain") {
                    handleSharedText(intent)
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                handleProcessText(intent)
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "CalendarCapture"
    }

    private fun setupClickListeners() {
        binding.btnAddToCalendar.setOnClickListener {
            addToCalendar()
        }

        binding.btnReprocess.setOnClickListener {
            // Get the edited OCR text
            currentOcrText = binding.tvOcrText.text.toString()

            // Reparse with the edited text
            val settings = prefsHelper.getSettings()
            showLoading("Reparsing text...")

            lifecycleScope.launch {
                try {
                    val event = apiService.performParse(settings.parseMethod, currentOcrText, settings)
                    currentEvent = event
                    displayEvent(event)
                    hideLoading()
                } catch (e: Exception) {
                    hideLoading()
                    showError("Error reparsing: ${e.message}")
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
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
                processImage(bitmap)
            }
        } catch (e: Exception) {
            showError("Failed to load image: ${e.message}")
        }
    }

    private fun processImage(bitmap: Bitmap) {
        val settings = prefsHelper.getSettings()

        showLoading("Processing image...")

        lifecycleScope.launch {
            try {
                // Step 1: OCR
                updateStatus("Extracting text...")
                val text = when (settings.ocrMethod) {
                    "mlkit" -> ocrHandler.performOcr(bitmap)
                    else -> apiService.performOcr(settings.ocrMethod, bitmap, settings)
                }

                currentOcrText = text
                binding.tvOcrText.setText(text)
                binding.ocrTextCard.visibility = View.VISIBLE

                if (text.isEmpty()) {
                    showError("No text found in image")
                    hideLoading()
                    return@launch
                }

                // Step 2: Parse
                updateStatus("Parsing event details...")
                val event = apiService.performParse(settings.parseMethod, text, settings)
                currentEvent = event

                // Step 3: Display results
                displayEvent(event)
                hideLoading()

            } catch (e: Exception) {
                hideLoading()
                showError("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun processText(text: String) {
        val settings = prefsHelper.getSettings()

        currentOcrText = text
        binding.tvOcrText.setText(text)
        binding.ocrTextCard.visibility = View.VISIBLE

        showLoading("Parsing event details...")

        lifecycleScope.launch {
            try {
                val event = apiService.performParse(settings.parseMethod, text, settings)
                currentEvent = event
                displayEvent(event)
                hideLoading()
            } catch (e: Exception) {
                hideLoading()
                showError("Error parsing: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun displayEvent(event: EventData) {
        val use24Hour = prefsHelper.is24HourFormat()
        val timeFormatter = if (use24Hour) {
            DateTimeFormatter.ofPattern("HH:mm")
        } else {
            DateTimeFormatter.ofPattern("h:mm a")
        }

        binding.apply {
            eventDetailsCard.visibility = View.VISIBLE
            btnAddToCalendar.visibility = View.VISIBLE
            btnReprocess.visibility = View.VISIBLE

            // Title
            editTitle.setText(event.title)

            // Location
            editLocation.setText(event.location)

            // All-day checkbox
            checkboxAllDay.isChecked = event.isAllDay

            // Start date/time
            event.start?.let { start ->
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

                editStartDate.setText(start.format(dateFormatter))
                editStartTime.setText(start.format(timeFormatter))
                editStartTime.isEnabled = !event.isAllDay
            }

            // End date/time
            event.end?.let { end ->
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

                editEndDate.setText(end.format(dateFormatter))
                editEndTime.setText(end.format(timeFormatter))
                editEndTime.isEnabled = !event.isAllDay
            }

            // Recurrence
            if (event.recurrence.isNotEmpty()) {
                tvRecurrence.text = "Repeats: ${parseRecurrenceToHuman(event.recurrence)}"
                tvRecurrence.visibility = View.VISIBLE
            } else {
                tvRecurrence.visibility = View.GONE
            }

            // All-day toggle
            checkboxAllDay.setOnCheckedChangeListener { _, isChecked ->
                editStartTime.isEnabled = !isChecked
                editEndTime.isEnabled = !isChecked
            }

            updateStatus("Ready")
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
            val use24Hour = prefsHelper.is24HourFormat()
            val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val timeFormatter = if (use24Hour) {
                DateTimeFormatter.ofPattern("HH:mm")
            } else {
                DateTimeFormatter.ofPattern("h:mm a")
            }

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

            // Get edited values from UI
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

            // Close the activity after adding to calendar
            finish()

        } catch (e: Exception) {
            showError("Failed to open calendar: ${e.message}")
        }
    }

    private fun showLoading(message: String) {
        binding.apply {
            statusContainer.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            tvStatus.text = message
            btnAddToCalendar.isEnabled = false
            btnReprocess.isEnabled = false
        }
    }

    private fun hideLoading() {
        binding.apply {
            statusContainer.visibility = View.GONE
            progressBar.visibility = View.GONE
            btnAddToCalendar.isEnabled = true
            btnReprocess.isEnabled = true
        }
    }

    private fun updateStatus(message: String) {
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.statusContainer.visibility = View.VISIBLE
        binding.tvStatus.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHandler.close()
    }
}