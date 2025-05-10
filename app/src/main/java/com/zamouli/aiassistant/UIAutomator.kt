package com.example.aiassistant

import android.app.AlertDialog
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Advanced UI automation class that provides direct input injection and UI interaction
 * Note: This requires special permissions that are typically only available to system apps or
 * with root/adb access. In a real Android app, not all methods in this class will work without
 * these permissions.
 */
class UIAutomator(private val context: Context) {
    
    companion object {
        private const val TAG = "UIAutomator"
        
        // Flag to track if we have the necessary permissions
        private var hasInjectPermissions = false
        
        // INJECT_EVENTS permission requires root or system privileges
        // We'll try to check if the current process has this permission
        private fun checkInjectPermission(context: Context): Boolean {
            return try {
                // This is a loose check, the actual permission might still be unavailable
                context.checkCallingOrSelfPermission("android.permission.INJECT_EVENTS") == 
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Log.e(TAG, "Error checking INJECT_EVENTS permission: ${e.message}")
                false
            }
        }
    }
    
    // The AutomationService reference for standard automation
    private var automationService: AutomationService? = null
    
    // Use accessibility service as fallback for input injection
    private var useAccessibilityFallback = true
    
    init {
        hasInjectPermissions = checkInjectPermission(context)
        Log.d(TAG, "UIAutomator initialized with inject permissions: $hasInjectPermissions")
    }
    
    // Set the automation service reference
    fun setAutomationService(service: AutomationService) {
        automationService = service
    }
    
    /**
     * Open Accessibility Settings to enable the automation service
     */
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    
    /**
     * Check if automation service is connected
     */
    fun isAutomationServiceConnected(): Boolean {
        return AutomationService.isServiceConnected()
    }
    
    /**
     * Check for all required permissions and settings
     */
    fun checkRequiredPermissions(): List<String> {
        val missingPermissions = mutableListOf<String>()
        
        // Check Accessibility Service
        if (!AutomationService.isServiceConnected()) {
            missingPermissions.add("accessibility_service")
        }
        
        // Check Notification Listener Service
        if (!NotificationListenerService.isServiceConnected()) {
            missingPermissions.add("notification_listener")
        }
        
        // Check for system alert window permission
        if (!Settings.canDrawOverlays(context)) {
            missingPermissions.add("overlay_permission")
        }
        
        // Check for inject events permission (rarely available)
        if (!hasInjectPermissions) {
            missingPermissions.add("inject_events")
        }
        
        return missingPermissions
    }
    
    /**
     * Show a dialog explaining required permissions
     */
    fun showPermissionsDialog(missingPermissions: List<String>) {
        val message = StringBuilder()
        message.append("التطبيق يحتاج إلى الأذونات التالية للعمل بشكل كامل:\n\n")
        
        for (permission in missingPermissions) {
            when (permission) {
                "accessibility_service" -> 
                    message.append("• خدمة إمكانية الوصول: للتحكم في شاشة الهاتف وإجراء النقرات التلقائية\n")
                "notification_listener" -> 
                    message.append("• خدمة الإشعارات: للتفاعل مع الإشعارات\n")
                "overlay_permission" -> 
                    message.append("• إذن العرض فوق التطبيقات الأخرى: للتفاعل مع نوافذ التطبيقات الأخرى\n")
                "inject_events" -> 
                    message.append("• إذن حقن الأحداث: للتحكم المباشر في اللمس والإيماءات (قد يتطلب صلاحيات الجذر)\n")
            }
        }
        
        val dialog = AlertDialog.Builder(context)
            .setTitle("أذونات مطلوبة")
            .setMessage(message.toString())
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                when {
                    "accessibility_service" in missingPermissions -> openAccessibilitySettings()
                    "notification_listener" in missingPermissions -> openNotificationSettings()
                    "overlay_permission" in missingPermissions -> openOverlaySettings()
                }
            }
            .setNegativeButton("إلغاء", null)
            .create()
        
        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }
    
    /**
     * Open notification settings
     */
    private fun openNotificationSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    
    /**
     * Open overlay settings
     */
    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    
    /**
     * Tap at specific coordinates on the screen
     * This attempts to use direct input injection, but falls back to accessibility service if needed
     */
    fun tap(x: Float, y: Float): Boolean {
        if (hasInjectPermissions) {
            return try {
                injectTap(x, y)
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting tap: ${e.message}")
                tapWithAccessibility(x, y)
            }
        } else {
            return tapWithAccessibility(x, y)
        }
    }
    
    /**
     * Tap using Accessibility Service
     */
    private fun tapWithAccessibility(x: Float, y: Float): Boolean {
        val operationId = UUID.randomUUID().toString()
        
        // Add pending gesture operation
        AutomationService.addPendingGestureOperation(
            operationId, 
            GestureType.TAP, 
            x, y
        )
        
        return true
    }
    
    /**
     * Perform a swipe gesture from one point to another
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): Boolean {
        if (hasInjectPermissions) {
            return try {
                injectSwipe(startX, startY, endX, endY, duration)
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting swipe: ${e.message}")
                swipeWithAccessibility(startX, startY, endX, endY)
            }
        } else {
            return swipeWithAccessibility(startX, startY, endX, endY)
        }
    }
    
    /**
     * Swipe using Accessibility Service
     */
    private fun swipeWithAccessibility(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val operationId = UUID.randomUUID().toString()
        
        // Add pending gesture operation
        AutomationService.addPendingGestureOperation(
            operationId, 
            GestureType.SWIPE, 
            startX, startY, 
            endX, endY
        )
        
        return true
    }
    
    /**
     * Input text into a text field
     * We need to first find and focus the right element
     */
    fun inputText(text: String, packageName: String, viewId: String): Boolean {
        val operationId = UUID.randomUUID().toString()
        
        // Add pending text input operation
        AutomationService.addPendingInputOperation(
            operationId,
            packageName,
            viewId,
            text
        )
        
        return true
    }
    
    /**
     * Click on a specific UI element by ID
     */
    fun clickElementById(packageName: String, viewId: String): Boolean {
        val operationId = UUID.randomUUID().toString()
        
        // Add pending click operation
        AutomationService.addPendingClickOperation(
            operationId,
            packageName,
            viewId
        )
        
        return true
    }
    
    /**
     * Click on a specific UI element by text
     */
    fun clickElementByText(packageName: String, text: String): Boolean {
        val operationId = UUID.randomUUID().toString()
        
        // Add pending click operation
        AutomationService.addPendingClickOperation(
            operationId,
            packageName,
            "",
            text
        )
        
        return true
    }
    
    /**
     * Launch an app by its package name
     */
    fun launchApp(packageName: String): Boolean {
        return automationService?.launchApp(packageName) ?: run {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching app: ${e.message}")
            }
            false
        }
    }
    
    /**
     * Perform "back" button press
     */
    fun pressBack(): Boolean {
        return automationService?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        ) ?: run {
            try {
                // Try to inject a back key event
                if (hasInjectPermissions) {
                    injectKeyEvent(KeyEvent.KEYCODE_BACK)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error performing back: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Perform "home" button press
     */
    fun pressHome(): Boolean {
        return automationService?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        ) ?: run {
            try {
                // Try to inject a home key event
                if (hasInjectPermissions) {
                    injectKeyEvent(KeyEvent.KEYCODE_HOME)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error performing home: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Perform "recents" button press
     */
    fun pressRecents(): Boolean {
        return automationService?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
        ) ?: false
    }
    
    /**
     * Take a screenshot
     */
    suspend fun takeScreenshot(): String {
        val file = withContext(Dispatchers.IO) {
            // TODO: Implement screenshot functionality
            // This would ideally use the ScreenCaptureService
            null
        }
        
        return file?.absolutePath ?: ""
    }
    
    /**
     * Get UI hierarchy as a string (for debugging or analysis)
     */
    fun getUIHierarchy(): String {
        // TODO: Implement UI hierarchy extraction
        return "UI Hierarchy not available yet"
    }
    
    // Below methods require INJECT_EVENTS permission (usually only available to system apps or with root)
    
    /**
     * Inject a tap event directly
     * Note: This requires INJECT_EVENTS permission
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectTap(x: Float, y: Float): Boolean {
        try {
            // Get the current time in milliseconds since boot
            val now = android.os.SystemClock.uptimeMillis()
            
            // Create the down motion event
            val downEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, x, y, 0
            )
            
            // Set the source to touchscreen
            downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            
            // Inject the down event
            injectInputEvent(downEvent)
            downEvent.recycle()
            
            // Small delay between down and up events
            Thread.sleep(10)
            
            // Create the up motion event
            val upEvent = MotionEvent.obtain(
                now + 10, now + 10, MotionEvent.ACTION_UP, x, y, 0
            )
            
            // Set the source to touchscreen
            upEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            
            // Inject the up event
            injectInputEvent(upEvent)
            upEvent.recycle()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting tap: ${e.message}")
            return false
        }
    }
    
    /**
     * Inject a swipe event directly
     * Note: This requires INJECT_EVENTS permission
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean {
        try {
            // Get the current time in milliseconds since boot
            val now = android.os.SystemClock.uptimeMillis()
            
            // Create the down motion event at the start position
            val downEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, startX, startY, 0
            )
            
            // Set the source to touchscreen
            downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            
            // Inject the down event
            injectInputEvent(downEvent)
            downEvent.recycle()
            
            // Calculate steps for smooth motion
            val steps = 10
            val stepDuration = duration / steps
            
            // Calculate delta per step
            val xDelta = (endX - startX) / steps
            val yDelta = (endY - startY) / steps
            
            // Perform move events
            for (i in 1 until steps) {
                val stepTime = now + (i * stepDuration)
                val moveX = startX + (xDelta * i)
                val moveY = startY + (yDelta * i)
                
                val moveEvent = MotionEvent.obtain(
                    now, stepTime, MotionEvent.ACTION_MOVE, moveX, moveY, 0
                )
                
                moveEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                injectInputEvent(moveEvent)
                moveEvent.recycle()
                
                Thread.sleep(stepDuration)
            }
            
            // Create the up motion event at the end position
            val upEvent = MotionEvent.obtain(
                now, now + duration, MotionEvent.ACTION_UP, endX, endY, 0
            )
            
            upEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            injectInputEvent(upEvent)
            upEvent.recycle()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting swipe: ${e.message}")
            return false
        }
    }
    
    /**
     * Inject a key event directly
     * Note: This requires INJECT_EVENTS permission
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectKeyEvent(keyCode: Int): Boolean {
        try {
            // Get the current time in milliseconds since boot
            val now = android.os.SystemClock.uptimeMillis()
            
            // Create the key down event
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            
            // Inject the down event
            injectInputEvent(downEvent)
            
            // Small delay between down and up events
            Thread.sleep(10)
            
            // Create the key up event
            val upEvent = KeyEvent(now + 10, now + 10, KeyEvent.ACTION_UP, keyCode, 0)
            
            // Inject the up event
            injectInputEvent(upEvent)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting key event: ${e.message}")
            return false
        }
    }
    
    /**
     * Inject an input event
     * Note: This method requires special permissions and may not work on non-rooted devices
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectInputEvent(event: InputEvent): Boolean {
        // This is a stub method as it would require system privileges or root
        // In a real implementation with proper permissions, this would use UiAutomation
        Log.w(TAG, "Injection of input events is not fully implemented - requires system privileges")
        return false
    }
}