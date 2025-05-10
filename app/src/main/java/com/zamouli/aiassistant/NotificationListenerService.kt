package com.example.aiassistant

import android.app.Notification
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for listening to notifications
 * This provides functionality to read, dismiss, and interact with notifications
 */
class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationService"
        
        // Flag for service connection status
        private val isServiceConnected = AtomicBoolean(false)
        
        // Store for recent notifications
        private val notificationsStore = ConcurrentHashMap<String, StatusBarNotification>()
        
        // Check if the service is connected
        fun isServiceConnected(): Boolean {
            return isServiceConnected.get()
        }
        
        // Get recent notifications
        fun getRecentNotifications(): List<Map<String, String>> {
            return notificationsStore.values.map { sbn ->
                val notification = sbn.notification
                val extras = notification.extras
                
                val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
                
                mapOf(
                    "id" to sbn.id.toString(),
                    "packageName" to sbn.packageName,
                    "postTime" to sbn.postTime.toString(),
                    "title" to title,
                    "text" to text
                )
            }
        }
        
        // Get notifications from a specific app
        fun getNotificationsFromApp(packageName: String): List<Map<String, String>> {
            return getRecentNotifications().filter { it["packageName"] == packageName }
        }
        
        // Clear notifications store
        fun clearNotificationsStore() {
            notificationsStore.clear()
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationListenerService created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected.set(false)
        Log.d(TAG, "NotificationListenerService destroyed")
    }
    
    override fun onBind(intent: Intent): IBinder? {
        val binder = super.onBind(intent)
        isServiceConnected.set(true)
        Log.d(TAG, "NotificationListenerService bound")
        return binder
    }
    
    override fun onUnbind(intent: Intent): Boolean {
        val result = super.onUnbind(intent)
        isServiceConnected.set(false)
        Log.d(TAG, "NotificationListenerService unbound")
        return result
    }
    
    /**
     * Called when a notification is posted
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "Notification posted: ${sbn.packageName}")
        
        // Store notification in our map
        val key = "${sbn.packageName}:${sbn.id}"
        notificationsStore[key] = sbn
        
        // Limit the size of our store to the most recent 50 notifications
        if (notificationsStore.size > 50) {
            val oldest = notificationsStore.entries.minByOrNull { it.value.postTime }?.key
            oldest?.let { notificationsStore.remove(it) }
        }
        
        // Parse notification
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        
        // Log notification details
        Log.d(TAG, "Notification: $title - $text")
    }
    
    /**
     * Called when a notification is removed
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Log.d(TAG, "Notification removed: ${sbn.packageName}")
        
        // Remove from our store
        val key = "${sbn.packageName}:${sbn.id}"
        notificationsStore.remove(key)
    }
    
    /**
     * Dismiss all notifications from a specific app
     */
    fun dismissNotificationsFromApp(packageName: String): Boolean {
        return try {
            // Get active notifications
            val activeNotifications = activeNotifications ?: return false
            
            // Find notifications from the specified app
            val notificationsToCancel = activeNotifications.filter { it.packageName == packageName }
            
            // Cancel each notification
            for (sbn in notificationsToCancel) {
                cancelNotification(sbn.key)
            }
            
            Log.d(TAG, "Dismissed ${notificationsToCancel.size} notifications from $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notifications: ${e.message}")
            false
        }
    }
    
    /**
     * Dismiss a specific notification by id
     */
    fun dismissNotification(id: Int): Boolean {
        return try {
            // Get active notifications
            val activeNotifications = activeNotifications ?: return false
            
            // Find notification with the specified id
            val notificationToCancel = activeNotifications.find { it.id == id }
            
            // Cancel the notification if found
            if (notificationToCancel != null) {
                cancelNotification(notificationToCancel.key)
                Log.d(TAG, "Dismissed notification with id $id")
                return true
            }
            
            Log.d(TAG, "No notification found with id $id")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification: ${e.message}")
            false
        }
    }
    
    /**
     * Get all active notifications
     */
    fun getActiveNotifications(): List<Map<String, String>> {
        return try {
            val activeNotifications = activeNotifications ?: return emptyList()
            
            activeNotifications.map { sbn ->
                val notification = sbn.notification
                val extras = notification.extras
                
                val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
                
                mapOf(
                    "id" to sbn.id.toString(),
                    "packageName" to sbn.packageName,
                    "postTime" to sbn.postTime.toString(),
                    "title" to title,
                    "text" to text
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active notifications: ${e.message}")
            emptyList()
        }
    }
}