package com.example.aiassistant

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.content.ContextCompat.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level controller for automating actions across the system
 * This class coordinates between the UIAutomator, AccessibilityService,
 * DeviceController and other components to provide a unified interface for automation
 */
class AutomationController(private val context: Context) {
    
    companion object {
        private const val TAG = "AutomationController"
        
        // Mapping of popular app names to package names
        private val knownApps = mapOf(
            "واتساب" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "فيسبوك" to "com.facebook.katana",
            "facebook" to "com.facebook.katana",
            "انستجرام" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "تويتر" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "سناب شات" to "com.snapchat.android",
            "snapchat" to "com.snapchat.android",
            "يوتيوب" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "جوجل" to "com.google.android.googlequicksearchbox",
            "google" to "com.google.android.googlequicksearchbox",
            "جيميل" to "com.google.android.gm",
            "gmail" to "com.google.android.gm",
            "خرائط" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "تيليجرام" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "تيك توك" to "com.zhiliaoapp.musically",
            "tiktok" to "com.zhiliaoapp.musically"
        )
        
        // Commands that can be used to control apps
        private val appCommands = mapOf(
            "afth" to CommandType.OPEN_APP,         // افتح
            "eftah" to CommandType.OPEN_APP,        // إفتح
            "fta7" to CommandType.OPEN_APP,         // فتح
            "shaghal" to CommandType.OPEN_APP,      // شغل
            "sha8al" to CommandType.OPEN_APP,       // شغّل
            "ektob" to CommandType.TYPE_TEXT,       // اكتب
            "ekteb" to CommandType.TYPE_TEXT,       // إكتب
            "etbaa" to CommandType.TYPE_TEXT,       // إطبع
            "otbaa" to CommandType.TYPE_TEXT,       // أطبع
            "ed8at" to CommandType.TAP,             // إضغط
            "edhghat" to CommandType.TAP,           // إضغط
            "onqor" to CommandType.TAP,             // أنقر
            "onkor" to CommandType.TAP,             // أنكر
            "mrer" to CommandType.SWIPE,            // مرر
            "swipe" to CommandType.SWIPE,           // سوايب
            "screenshot" to CommandType.SCREENSHOT, // سكرين شوت
            "sawer" to CommandType.SCREENSHOT       // صور
        )
        
        // Screen coordinates for common UI elements on popular apps
        private val appUIElements = ConcurrentHashMap<String, Map<String, Pair<Float, Float>>>()
        
        init {
            // Initialize known elements positions (based on percentage of screen)
            // WhatsApp
            appUIElements["com.whatsapp"] = mapOf(
                "compose_button" to Pair(0.9f, 0.9f),          // New chat button
                "chat_input" to Pair(0.5f, 0.95f),             // Text input field
                "send_button" to Pair(0.95f, 0.95f),           // Send button
                "attach_button" to Pair(0.1f, 0.95f),          // Attachment button
                "back_button" to Pair(0.05f, 0.05f),           // Back button
                "menu_button" to Pair(0.95f, 0.05f),           // Menu button
                "first_chat" to Pair(0.5f, 0.15f),             // First chat in the list
                "search_button" to Pair(0.9f, 0.05f)           // Search button
            )
            
            // Facebook
            appUIElements["com.facebook.katana"] = mapOf(
                "news_feed" to Pair(0.5f, 0.3f),               // News feed
                "profile" to Pair(0.1f, 0.05f),                // Profile button
                "post_input" to Pair(0.5f, 0.2f),              // New post input
                "like_button" to Pair(0.2f, 0.8f),             // Like button on first post
                "comment_button" to Pair(0.5f, 0.8f),          // Comment button on first post
                "share_button" to Pair(0.8f, 0.8f),            // Share button on first post
                "menu_button" to Pair(0.95f, 0.05f)            // Menu button
            )
            
            // Instagram
            appUIElements["com.instagram.android"] = mapOf(
                "home" to Pair(0.15f, 0.95f),                  // Home button
                "search" to Pair(0.35f, 0.95f),                // Search button
                "add_post" to Pair(0.5f, 0.95f),               // Add post button
                "activity" to Pair(0.65f, 0.95f),              // Activity button
                "profile" to Pair(0.85f, 0.95f),               // Profile button
                "first_story" to Pair(0.15f, 0.15f),           // First story
                "first_post" to Pair(0.5f, 0.3f),              // First post
                "like_button" to Pair(0.2f, 0.85f),            // Like button on first post
                "comment_button" to Pair(0.5f, 0.85f),         // Comment button on first post
                "share_button" to Pair(0.8f, 0.85f)            // Share button on first post
            )
            
            // Twitter/X
            appUIElements["com.twitter.android"] = mapOf(
                "home" to Pair(0.15f, 0.95f),                  // Home button
                "search" to Pair(0.35f, 0.95f),                // Search button
                "notifications" to Pair(0.65f, 0.95f),         // Notifications button
                "messages" to Pair(0.85f, 0.95f),              // Messages button
                "compose" to Pair(0.85f, 0.85f),               // Compose tweet button
                "tweet_input" to Pair(0.5f, 0.2f),             // Tweet input field
                "first_tweet" to Pair(0.5f, 0.3f),             // First tweet
                "like_button" to Pair(0.65f, 0.85f),           // Like button on first tweet
                "retweet_button" to Pair(0.5f, 0.85f),         // Retweet button on first tweet
                "reply_button" to Pair(0.35f, 0.85f)           // Reply button on first tweet
            )
            
            // YouTube
            appUIElements["com.google.android.youtube"] = mapOf(
                "home" to Pair(0.15f, 0.95f),                  // Home button
                "shorts" to Pair(0.35f, 0.95f),                // Shorts button
                "upload" to Pair(0.5f, 0.95f),                 // Upload button
                "subscriptions" to Pair(0.65f, 0.95f),         // Subscriptions button
                "library" to Pair(0.85f, 0.95f),               // Library button
                "search" to Pair(0.9f, 0.05f),                 // Search button
                "first_video" to Pair(0.5f, 0.2f),             // First video
                "like_button" to Pair(0.35f, 0.85f),           // Like button on video
                "dislike_button" to Pair(0.45f, 0.85f),        // Dislike button on video
                "comment_button" to Pair(0.65f, 0.85f),        // Comment button on video
                "share_button" to Pair(0.85f, 0.85f)           // Share button on video
            )
        }
        
        /**
         * Get real screen coordinates from percentage-based coordinates
         */
        fun getScreenCoordinates(context: Context, xPercent: Float, yPercent: Float): Pair<Float, Float> {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            display.getSize(size)
            
            return Pair(xPercent * size.x, yPercent * size.y)
        }
        
        /**
         * Get the package name for a known app
         */
        fun getPackageNameForApp(appName: String): String? {
            return knownApps[appName.lowercase()]
        }
        
        /**
         * Parse automation command from text
         */
        fun parseCommand(text: String): AutomationCommand? {
            val words = text.split(" ")
            if (words.size < 2) return null
            
            // Try to identify command type
            val commandText = words[0].replace("أ", "ا").replace("إ", "ا").lowercase()
            val commandType = appCommands[commandText] ?: return null
            
            when (commandType) {
                CommandType.OPEN_APP -> {
                    val appName = words.subList(1, words.size).joinToString(" ")
                    val packageName = getPackageNameForApp(appName) ?: appName
                    return AutomationCommand(commandType, packageName)
                }
                
                CommandType.TYPE_TEXT -> {
                    if (words.size < 3) return null
                    // Format expected: "اكتب [text] في [app]"
                    // Find "في" or "in" to separate text from app
                    val inIndex = words.indexOfFirst { it == "في" || it == "in" || it == "على" || it == "on" }
                    if (inIndex > 1 && inIndex < words.size - 1) {
                        val text = words.subList(1, inIndex).joinToString(" ")
                        val appName = words.subList(inIndex + 1, words.size).joinToString(" ")
                        val packageName = getPackageNameForApp(appName) ?: appName
                        return AutomationCommand(commandType, packageName, text)
                    }
                    // No app specified, just the text
                    val text = words.subList(1, words.size).joinToString(" ")
                    return AutomationCommand(commandType, null, text)
                }
                
                CommandType.TAP -> {
                    if (words.size < 3) return null
                    // Format expected: "اضغط [element] في [app]"
                    val inIndex = words.indexOfFirst { it == "في" || it == "in" || it == "على" || it == "on" }
                    if (inIndex > 1 && inIndex < words.size - 1) {
                        val element = words.subList(1, inIndex).joinToString(" ")
                        val appName = words.subList(inIndex + 1, words.size).joinToString(" ")
                        val packageName = getPackageNameForApp(appName) ?: appName
                        return AutomationCommand(commandType, packageName, element)
                    }
                    // Might be coordinates
                    try {
                        if (words.size >= 3) {
                            val xCoord = words[1].toFloat()
                            val yCoord = words[2].toFloat()
                            return AutomationCommand(commandType, null, coordinates = Pair(xCoord, yCoord))
                        }
                    } catch (e: NumberFormatException) {
                        // Not coordinates, continue
                    }
                    
                    // Just element name
                    val element = words.subList(1, words.size).joinToString(" ")
                    return AutomationCommand(commandType, null, element)
                }
                
                CommandType.SWIPE -> {
                    if (words.size < 5) return null
                    // Format expected: "مرر من [x1] [y1] إلى [x2] [y2]"
                    val fromIndex = words.indexOfFirst { it == "من" || it == "from" }
                    val toIndex = words.indexOfFirst { it == "إلى" || it == "الى" || it == "to" }
                    
                    if (fromIndex >= 0 && toIndex >= 0 && fromIndex < toIndex && toIndex < words.size - 2) {
                        try {
                            val x1 = words[fromIndex + 1].toFloat()
                            val y1 = words[fromIndex + 2].toFloat()
                            val x2 = words[toIndex + 1].toFloat()
                            val y2 = words[toIndex + 2].toFloat()
                            return AutomationCommand(commandType, null, 
                                startCoordinates = Pair(x1, y1), 
                                endCoordinates = Pair(x2, y2))
                        } catch (e: NumberFormatException) {
                            // Not valid coordinates
                        }
                    }
                    
                    // Simple directional swipe: "مرر لأعلى/لأسفل/لليسار/لليمين"
                    val direction = words.getOrNull(1)
                    if (direction != null) {
                        return when (direction.lowercase()) {
                            "لأعلى", "up" -> {
                                AutomationCommand(commandType, null, swipeDirection = "up")
                            }
                            "لأسفل", "down" -> {
                                AutomationCommand(commandType, null, swipeDirection = "down")
                            }
                            "لليسار", "left" -> {
                                AutomationCommand(commandType, null, swipeDirection = "left")
                            }
                            "لليمين", "right" -> {
                                AutomationCommand(commandType, null, swipeDirection = "right")
                            }
                            else -> null
                        }
                    }
                }
                
                CommandType.SCREENSHOT -> {
                    return AutomationCommand(commandType, null)
                }
            }
            
            return null
        }
    }
    
    // References to other components
    private val uiAutomator = UIAutomator(context)
    private val deviceController = DeviceController(context)
    
    // Store screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0
    
    init {
        // Get screen dimensions
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)
        screenWidth = size.x
        screenHeight = size.y
    }
    
    /**
     * Execute an automation command
     */
    suspend fun executeCommand(command: AutomationCommand): String {
        Log.d(TAG, "Executing command: $command")
        
        when (command.commandType) {
            CommandType.OPEN_APP -> {
                return openApp(command.packageName ?: "")
            }
            
            CommandType.TYPE_TEXT -> {
                return typeText(command.text ?: "", command.packageName)
            }
            
            CommandType.TAP -> {
                return tap(command.text, command.packageName, command.coordinates)
            }
            
            CommandType.SWIPE -> {
                return swipe(command.startCoordinates, command.endCoordinates, command.swipeDirection, command.packageName)
            }
            
            CommandType.SCREENSHOT -> {
                return takeScreenshot()
            }
        }
    }
    
    /**
     * Open an app by package name
     */
    private fun openApp(packageName: String): String {
        return withErrorHandling {
            if (uiAutomator.launchApp(packageName)) {
                "تم فتح التطبيق بنجاح"
            } else {
                deviceController.openApp(packageName)
                "تم فتح التطبيق"
            }
        }
    }
    
    /**
     * Type text in the current focused field or in a specific app
     */
    private fun typeText(text: String, packageName: String?): String {
        return withErrorHandling {
            if (packageName != null) {
                // Find the text input field in the app
                val inputFieldId = when (packageName) {
                    "com.whatsapp" -> "com.whatsapp:id/entry"
                    "com.facebook.katana" -> "com.facebook.katana:id/composer_status_text"
                    "com.twitter.android" -> "com.twitter.android:id/composer_text"
                    else -> ""
                }
                
                if (inputFieldId.isNotEmpty()) {
                    uiAutomator.inputText(text, packageName, inputFieldId)
                } else {
                    // If we don't know the input field ID, try to tap where it usually is and then type
                    val appElements = appUIElements[packageName]
                    val inputCoords = appElements?.get("chat_input") ?: appElements?.get("post_input")
                                    ?: Pair(0.5f, 0.8f)  // Default position near bottom center
                    
                    val (x, y) = getScreenCoordinates(context, inputCoords.first, inputCoords.second)
                    uiAutomator.tap(x, y)
                    
                    // Use fallback to inject text if direct input fails
                    uiAutomator.inputText(text, packageName, "")
                }
                "تم كتابة النص: $text"
            } else {
                // No specific app, just try to type in the current focused field
                uiAutomator.inputText(text, "*", "")
                "تم كتابة النص: $text"
            }
        }
    }
    
    /**
     * Tap on an element or coordinates
     */
    private fun tap(elementName: String?, packageName: String?, coordinates: Pair<Float, Float>?): String {
        return withErrorHandling {
            if (coordinates != null) {
                // Direct tap on coordinates
                uiAutomator.tap(coordinates.first, coordinates.second)
                "تم النقر على الإحداثيات: (${coordinates.first}, ${coordinates.second})"
            } else if (elementName != null && packageName != null) {
                // Try to find and tap on a known element in a specific app
                val appElements = appUIElements[packageName]
                val elementCoords = appElements?.get(elementName.lowercase().replace(" ", "_"))
                
                if (elementCoords != null) {
                    val (x, y) = getScreenCoordinates(context, elementCoords.first, elementCoords.second)
                    uiAutomator.tap(x, y)
                    "تم النقر على عنصر: $elementName"
                } else {
                    // Try to find element by text
                    uiAutomator.clickElementByText(packageName, elementName)
                    "تم محاولة النقر على: $elementName"
                }
            } else if (elementName != null) {
                // Try to find element by text in current app
                uiAutomator.clickElementByText("*", elementName)
                "تم محاولة النقر على: $elementName"
            } else {
                "لم يتم تحديد موقع النقر بشكل صحيح"
            }
        }
    }
    
    /**
     * Perform a swipe gesture
     */
    private fun swipe(
        startCoords: Pair<Float, Float>?, 
        endCoords: Pair<Float, Float>?,
        direction: String?,
        packageName: String?
    ): String {
        return withErrorHandling {
            if (startCoords != null && endCoords != null) {
                // Direct swipe between coordinates
                uiAutomator.swipe(startCoords.first, startCoords.second, endCoords.first, endCoords.second)
                "تم التمرير من (${startCoords.first}, ${startCoords.second}) إلى (${endCoords.first}, ${endCoords.second})"
            } else if (direction != null) {
                // Directional swipe
                val (startX, startY, endX, endY) = when (direction) {
                    "up" -> Pair(
                        Pair(screenWidth / 2f, screenHeight * 0.7f),
                        Pair(screenWidth / 2f, screenHeight * 0.3f)
                    )
                    "down" -> Pair(
                        Pair(screenWidth / 2f, screenHeight * 0.3f),
                        Pair(screenWidth / 2f, screenHeight * 0.7f)
                    )
                    "left" -> Pair(
                        Pair(screenWidth * 0.7f, screenHeight / 2f),
                        Pair(screenWidth * 0.3f, screenHeight / 2f)
                    )
                    "right" -> Pair(
                        Pair(screenWidth * 0.3f, screenHeight / 2f),
                        Pair(screenWidth * 0.7f, screenHeight / 2f)
                    )
                    else -> Pair(
                        Pair(screenWidth / 2f, screenHeight * 0.7f),
                        Pair(screenWidth / 2f, screenHeight * 0.3f)
                    ) // Default to up
                }
                
                uiAutomator.swipe(startX.first, startX.second, endY.first, endY.second)
                "تم التمرير إلى $direction"
            } else {
                "لم يتم تحديد اتجاه التمرير بشكل صحيح"
            }
        }
    }
    
    /**
     * Take a screenshot of the current screen
     */
    private suspend fun takeScreenshot(): String {
        return withErrorHandling {
            val screenshotPath = withContext(Dispatchers.IO) {
                uiAutomator.takeScreenshot()
            }
            
            if (screenshotPath.isNotEmpty()) {
                "تم التقاط صورة للشاشة: $screenshotPath"
            } else {
                "فشل التقاط صورة للشاشة"
            }
        }
    }
    
    /**
     * Helper for error handling in automation commands
     */
    private inline fun withErrorHandling(block: () -> String): String {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing automation command: ${e.message}")
            "حدث خطأ أثناء تنفيذ الأمر: ${e.message}"
        }
    }
    
    /**
     * Check if the automation services are properly set up
     */
    fun checkRequiredServices(): Boolean {
        val missingPermissions = uiAutomator.checkRequiredPermissions()
        
        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Missing automation permissions: $missingPermissions")
            
            // Show dialog to guide the user to enable necessary services
            uiAutomator.showPermissionsDialog(missingPermissions)
            return false
        }
        
        return true
    }
}

/**
 * Types of automation commands
 */
enum class CommandType {
    OPEN_APP,
    TYPE_TEXT,
    TAP,
    SWIPE,
    SCREENSHOT
}

/**
 * Data class representing an automation command
 */
data class AutomationCommand(
    val commandType: CommandType,
    val packageName: String?,
    val text: String? = null,
    val coordinates: Pair<Float, Float>? = null,
    val startCoordinates: Pair<Float, Float>? = null,
    val endCoordinates: Pair<Float, Float>? = null,
    val swipeDirection: String? = null
)