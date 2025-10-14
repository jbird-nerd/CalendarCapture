package com.example.calendarcapture

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.time.ZoneId

class CalendarHelper {
    
    fun createCalendarIntent(context: Context, event: EventData, ocrText: String): Intent {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            
            // Title
            putExtra(CalendarContract.Events.TITLE, event.title)
            
            // Location
            if (event.location.isNotEmpty()) {
                putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
            }
            
            // Description with OCR text
            val description = buildString {
                append("Created by CalendarCapture")
                if (ocrText.isNotEmpty()) {
                    append("\n\n")
                    append(ocrText)
                }
            }
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            
            // All-day event
            putExtra(CalendarContract.Events.ALL_DAY, event.isAllDay)
            
            // Start and end times
            event.start?.let { start ->
                val startMillis = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            }
            
            event.end?.let { end ->
                val endMillis = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            }
            
            // Recurrence rule
            if (event.recurrence.isNotEmpty()) {
                putExtra(CalendarContract.Events.RRULE, event.recurrence)
            }
        }
        
        return intent
    }
}
