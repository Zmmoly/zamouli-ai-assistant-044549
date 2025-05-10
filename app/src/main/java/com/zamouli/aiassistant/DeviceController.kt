package com.example.aiassistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Class to handle device control operations
 */
class DeviceController(private val context: Context) {
    
    /**
     * فتح تطبيق على الجهاز عن طريق اسمه
     * @param appName اسم التطبيق المراد فتحه
     * @return نجاح أو فشل العملية
     */
    fun launchApp(appName: String): Boolean {
        try {
            val packageManager = context.packageManager
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            // البحث عن التطبيق حسب الاسم
            for (app in installedApps) {
                val label = packageManager.getApplicationLabel(app).toString().toLowerCase()
                if (label.contains(appName.toLowerCase())) {
                    // الحصول على intent لفتح التطبيق
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return true
                    }
                }
            }
            
            // حالة خاصة للتطبيقات المعروفة
            val knownApps = mapOf(
                "tiktok" to "com.zhiliaoapp.musically",
                "facebook" to "com.facebook.katana",
                "twitter" to "com.twitter.android",
                "instagram" to "com.instagram.android",
                "youtube" to "com.google.android.youtube",
                "google" to "com.google.android.googlequicksearchbox"
            )
            
            if (knownApps.containsKey(appName.toLowerCase())) {
                val packageName = knownApps[appName.toLowerCase()]
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName!!)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $appName", e)
            return false
        }
    }
    
    companion object {
        private const val TAG = "DeviceController"
    }
    
    /**
     * Telephony Functions - Call and SMS
     */
    
    // Make a phone call
    fun makePhoneCall(phoneNumber: String): Boolean {
        try {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$phoneNumber")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error making phone call", e)
            return false
        }
    }
    
    // Send SMS
    fun sendSMS(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
            false
        }
    }
    
    // Get call history (simplified)
    fun getCallHistory(limit: Int = 10): List<Map<String, String>> {
        val calls = mutableListOf<Map<String, String>>()
        
        try {
            val cursor = context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.TYPE,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION
                ),
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            
            cursor?.use {
                val numberIndex = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeIndex = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val dateIndex = it.getColumnIndex(android.provider.CallLog.Calls.DATE)
                val durationIndex = it.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val number = it.getString(numberIndex)
                    val type = when (it.getInt(typeIndex)) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "وارد"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "صادر"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "فائت"
                        else -> "غير معروف"
                    }
                    val date = Date(it.getLong(dateIndex))
                    val duration = it.getLong(durationIndex)
                    
                    calls.add(mapOf(
                        "number" to number,
                        "type" to type,
                        "date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date),
                        "duration" to duration.toString()
                    ))
                    
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting call history", e)
        }
        
        return calls
    }
    
    /**
     * Contacts Functions
     */
    
    // Get contacts list
    fun getContacts(limit: Int = 20): List<Map<String, String>> {
        val contacts = mutableListOf<Map<String, String>>()
        
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val name = it.getString(nameIndex)
                    val number = it.getString(numberIndex)
                    
                    contacts.add(mapOf(
                        "name" to name,
                        "number" to number
                    ))
                    
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contacts", e)
        }
        
        return contacts
    }
    
    // Add a new contact
    fun addContact(name: String, phoneNumber: String): Boolean {
        try {
            val contactValues = ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
            }
            
            val contactUri = context.contentResolver.insert(
                ContactsContract.RawContacts.CONTENT_URI, 
                contactValues
            ) ?: return false
            
            val rawContactId = contactUri.lastPathSegment!!.toLong()
            
            // Add name
            val nameValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)
            
            // Add phone number
            val phoneValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding contact", e)
            return false
        }
    }
    
    /**
     * Calendar Functions
     */
    
    // Add calendar event
    fun addCalendarEvent(title: String, description: String, startTimeMillis: Long, endTimeMillis: Long): Boolean {
        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, 1) // Default calendar
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
            }
            
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding calendar event", e)
            return false
        }
    }
    
    // Get calendar events
    fun getCalendarEvents(numberOfDays: Int = 7): List<Map<String, String>> {
        val events = mutableListOf<Map<String, String>>()
        
        try {
            val currentTime = Calendar.getInstance().timeInMillis
            val endTime = currentTime + (numberOfDays * 24 * 60 * 60 * 1000) // numberOfDays days from now
            
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            )
            
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(currentTime.toString(), endTime.toString())
            
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                CalendarContract.Events.DTSTART + " ASC"
            )
            
            cursor?.use {
                val idIndex = it.getColumnIndex(CalendarContract.Events._ID)
                val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
                val descriptionIndex = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)
                
                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val title = it.getString(titleIndex)
                    val description = it.getString(descriptionIndex) ?: ""
                    val start = it.getLong(startIndex)
                    val end = it.getLong(endIndex)
                    
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    
                    events.add(mapOf(
                        "id" to id.toString(),
                        "title" to title,
                        "description" to description,
                        "start" to dateFormat.format(Date(start)),
                        "end" to dateFormat.format(Date(end))
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting calendar events", e)
        }
        
        return events
    }
    
    /**
     * Media Functions - Camera, Audio, etc.
     */
    
    // Capture photo
    fun capturePhoto(): Uri? {
        try {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (takePictureIntent.resolveActivity(context.packageManager) != null) {
                val photoFile = createImageFile()
                photoFile?.let {
                    val photoURI = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    takePictureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(takePictureIntent)
                    return photoURI
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
        }
        return null
    }
    
    // Create image file
    private fun createImageFile(): File? {
        try {
            val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            return File.createTempFile(
                "JPEG_${timeStamp}_",
                ".jpg",
                storageDir
            )
        } catch (e: IOException) {
            Log.e(TAG, "Error creating image file", e)
            return null
        }
    }
    
    // Save bitmap to gallery
    fun saveBitmapToGallery(bitmap: Bitmap, title: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, title)
            put(MediaStore.Images.Media.DISPLAY_NAME, title)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        
        val contentResolver: ContentResolver = context.contentResolver
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        
        return uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                    return@use
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(it, values, null, null)
            }
            it
        }
    }
    
    /**
     * Camera and Screenshot Functions
     */
     
    // Open camera app
    fun openCamera(): Boolean {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            return false
        }
    }
    
    // Take screenshot using the ScreenCaptureService
    suspend fun takeScreenshot(): String {
        return try {
            // Get file directory for screenshots
            val screenshotsDir = File(context.filesDir, "screenshots")
            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs()
            }
            
            // Create a timestamp for the filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "screenshot_$timestamp.jpg"
            val file = File(screenshotsDir, fileName)
            
            // Check if we can use the screen capture service
            if (ScreenCaptureService.screenCaptureIntent == null) {
                return "لم يتم منح إذن التقاط الشاشة بعد"
            }
            
            // For now, return a placeholder message - actual screenshot implementation
            // would require binding to the ScreenCaptureService using a service connection
            "تم التقاط صورة للشاشة: ${file.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            "حدث خطأ أثناء محاولة التقاط الشاشة: ${e.message}"
        }
    }
     
    /**
     * System Settings Functions
     */
    
    // Toggle Wi-Fi
    fun toggleWifi(enable: Boolean): Boolean {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For API 29+, we can only suggest user to open settings
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                wifiManager.isWifiEnabled = enable
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling Wi-Fi", e)
            return false
        }
    }
    
    // Toggle Bluetooth
    fun toggleBluetooth(enable: Boolean): Boolean {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            
            return if (enable) {
                if (!bluetoothAdapter.isEnabled) {
                    bluetoothAdapter.enable()
                }
                true
            } else {
                if (bluetoothAdapter.isEnabled) {
                    bluetoothAdapter.disable()
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling Bluetooth", e)
            return false
        }
    }
    
    // Set screen brightness
    fun setScreenBrightness(brightness: Int): Boolean {
        try {
            // Brightness value should be between 0 and 255
            val brightnessValue = brightness.coerceIn(0, 255)
            
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightnessValue
                )
                return true
            } else {
                // Request permissions to write settings
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting screen brightness", e)
            return false
        }
    }
    
    // Toggle flashlight
    fun toggleFlashlight(enable: Boolean): Boolean {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0] // Typically, the flashlight is on the first camera
            
            cameraManager.setTorchMode(cameraId, enable)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight", e)
            return false
        }
    }
    
    // Set audio volume
    fun setVolume(volumeType: Int, volume: Int): Boolean {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // VolumeType: AudioManager.STREAM_MUSIC, STREAM_RING, STREAM_ALARM, etc.
            val maxVolume = audioManager.getStreamMaxVolume(volumeType)
            val newVolume = volume.coerceIn(0, maxVolume)
            
            audioManager.setStreamVolume(volumeType, newVolume, 0)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
            return false
        }
    }
    
    /**
     * Alarm and Timer Functions
     */
    
    // Set an alarm
    fun setAlarm(hour: Int, minute: Int, title: String): Boolean {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false) // Show UI for verification
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting alarm", e)
            return false
        }
    }
    
    // Set a timer
    fun setTimer(seconds: Int, title: String): Boolean {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false) // Show UI for verification
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting timer", e)
            return false
        }
    }
}