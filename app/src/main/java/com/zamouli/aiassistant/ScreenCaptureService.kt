package com.example.aiassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for capturing screenshots of the screen
 * This service uses the MediaProjection API to capture the screen
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val VIRTUAL_DISPLAY_NAME = "AI_Assistant_Screen_Capture"
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
        
        // The intent data for screen capture
        var screenCaptureIntent: Intent? = null
        
        // Flag for service state
        private val isRunning = AtomicBoolean(false)
        
        // Check if the service is running
        fun isServiceRunning(): Boolean {
            return isRunning.get()
        }
    }
    
    // Binder for local binding
    private val binder = LocalBinder()
    
    // MediaProjection related objects
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // Screen metrics
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    // Capture state flag
    private var isCaptureInProgress = AtomicBoolean(false)
    
    /**
     * Local binder class for binding to the service
     */
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }
    
    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        
        // Create notification channel for Android 8.0+
        createNotificationChannel()
        
        // Start as foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Get screen metrics
        getScreenMetrics()
    }
    
    /**
     * Create the notification channel required for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "شاشة زمولي"
            val descriptionText = "يسمح لزمولي بالتقاط الشاشة لمساعدتك"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create the notification for foreground service
     */
    private fun createNotification(): Notification {
        // Create intent for notification tap action
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        // Build the notification
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Replace with your app icon
            .setContentTitle("زمولي يعمل في الخلفية")
            .setContentText("يقوم زمولي بمراقبة الشاشة للمساعدة")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
        
        return builder.build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        isRunning.set(false)
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    /**
     * Get screen metrics (width, height, density)
     */
    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
        
        Log.d(TAG, "Screen metrics: $screenWidth x $screenHeight, density: $screenDensity")
    }
    
    /**
     * Start screen capture
     */
    private fun startCapture() {
        if (screenCaptureIntent == null) {
            Log.e(TAG, "Screen capture intent is null, cannot start capture")
            return
        }
        
        if (virtualDisplay != null) {
            Log.d(TAG, "Virtual display already exists, reusing it")
            return
        }
        
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // في Android 14، يجب تعيين قيمة callback لتجاوز قيود المهلة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, screenCaptureIntent!!).apply {
                registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        stopSelf()
                    }
                }, Handler(Looper.getMainLooper()))
            }
        } else {
            // قبل Android 14
            mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, screenCaptureIntent!!)
        }
        
        // Create ImageReader for screen capture
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        
        // Create virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        
        Log.d(TAG, "Screen capture started")
    }
    
    /**
     * Stop screen capture
     */
    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        Log.d(TAG, "Screen capture stopped")
    }
    
    /**
     * Take a screenshot and save it to a file
     */
    fun takeScreenshot(callback: (File?) -> Unit) {
        if (isCaptureInProgress.getAndSet(true)) {
            Log.d(TAG, "Screenshot capture already in progress")
            callback(null)
            return
        }
        
        try {
            // Start capture if not already started
            if (virtualDisplay == null) {
                startCapture()
            }
            
            // Return early if the virtual display failed to initialize
            if (virtualDisplay == null || imageReader == null) {
                Log.e(TAG, "Virtual display or image reader is null")
                isCaptureInProgress.set(false)
                callback(null)
                return
            }
            
            // Handler for the main thread
            val handler = Handler(Looper.getMainLooper())
            
            // Add an image availability listener to the ImageReader
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        // Get image data
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth
                        
                        // Create bitmap from buffer data
                        val bitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        // Crop bitmap to the screen size
                        val croppedBitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            screenWidth,
                            screenHeight
                        )
                        bitmap.recycle()
                        
                        // Save bitmap to file
                        val screenshotFile = saveScreenshotToFile(croppedBitmap)
                        croppedBitmap.recycle()
                        
                        // Return the file in the callback
                        handler.post {
                            callback(screenshotFile)
                            isCaptureInProgress.set(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image: ${e.message}")
                        handler.post {
                            callback(null)
                            isCaptureInProgress.set(false)
                        }
                    } finally {
                        // Close the image to release its resources
                        image.close()
                    }
                } else {
                    Log.e(TAG, "Acquired image is null")
                    handler.post {
                        callback(null)
                        isCaptureInProgress.set(false)
                    }
                }
                
                // Remove the listener after getting an image
                reader.setOnImageAvailableListener(null, null)
            }, handler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            isCaptureInProgress.set(false)
            callback(null)
        }
    }
    
    /**
     * Save the bitmap to a file in the app's private storage
     */
    private fun saveScreenshotToFile(bitmap: Bitmap): File? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "screenshot_$timestamp.jpg"
        
        // Get the directory for screenshots
        val screenshotsDir = File(filesDir, "screenshots")
        if (!screenshotsDir.exists()) {
            screenshotsDir.mkdirs()
        }
        
        val file = File(screenshotsDir, fileName)
        
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            Log.d(TAG, "Saved screenshot to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot: ${e.message}")
            null
        }
    }
    
    /**
     * Get the URI for the screenshot file for sharing
     */
    fun getScreenshotUri(file: File): android.net.Uri? {
        return try {
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting URI for screenshot: ${e.message}")
            null
        }
    }
}