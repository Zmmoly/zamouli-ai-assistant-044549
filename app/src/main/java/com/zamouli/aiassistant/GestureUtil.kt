package com.example.aiassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Utility class for creating and executing advanced gestures
 * Provides methods for common gesture patterns and multi-touch operations
 */
class GestureUtil private constructor() {
    companion object {
        private const val TAG = "GestureUtil"
        
        // Default durations for gestures in milliseconds
        private const val DEFAULT_TAP_DURATION = 50L
        private const val DEFAULT_LONG_PRESS_DURATION = 1000L
        private const val DEFAULT_SWIPE_DURATION = 300L
        private const val DEFAULT_SCROLL_DURATION = 400L
        
        /**
         * Create a tap gesture at specified coordinates
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun createTapGesture(x: Float, y: Float, duration: Long = DEFAULT_TAP_DURATION): GestureDescription {
            val path = Path()
            path.moveTo(x, y)
            
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            return builder.build()
        }
        
        /**
         * Create a long press gesture at specified coordinates
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun createLongPressGesture(
            x: Float, 
            y: Float, 
            duration: Long = DEFAULT_LONG_PRESS_DURATION
        ): GestureDescription {
            val path = Path()
            path.moveTo(x, y)
            
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            return builder.build()
        }
        
        /**
         * Create a swipe gesture from one point to another
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun createSwipeGesture(
            startX: Float, 
            startY: Float, 
            endX: Float, 
            endY: Float, 
            duration: Long = DEFAULT_SWIPE_DURATION
        ): GestureDescription {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)
            
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            return builder.build()
        }
        
        /**
         * Create a scroll gesture (longer swipe)
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun createScrollGesture(
            startX: Float, 
            startY: Float, 
            endX: Float, 
            endY: Float, 
            duration: Long = DEFAULT_SCROLL_DURATION
        ): GestureDescription {
            return createSwipeGesture(startX, startY, endX, endY, duration)
        }
        
        /**
         * Create a pinch gesture (zoom in/out)
         * This requires two paths for the two fingers
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun createPinchGesture(
            centerX: Float, 
            centerY: Float, 
            startRadius: Float, 
            endRadius: Float, 
            duration: Long = DEFAULT_SWIPE_DURATION
        ): GestureDescription {
            // First finger path (top-left to bottom-right)
            val path1 = Path()
            path1.moveTo(centerX - startRadius, centerY - startRadius)
            path1.lineTo(centerX - endRadius, centerY - endRadius)
            
            // Second finger path (bottom-right to top-left)
            val path2 = Path()
            path2.moveTo(centerX + startRadius, centerY + startRadius)
            path2.lineTo(centerX + endRadius, centerY + endRadius)
            
            val stroke1 = GestureDescription.StrokeDescription(path1, 0, duration)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0, duration)
            
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke1)
            builder.addStroke(stroke2)
            
            return builder.build()
        }
        
        /**
         * Create a rotate gesture
         * This requires two paths for the two fingers, moving in a circular motion
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun createRotateGesture(
            centerX: Float, 
            centerY: Float, 
            radius: Float, 
            startAngle: Float, 
            endAngle: Float, 
            duration: Long = DEFAULT_SWIPE_DURATION
        ): GestureDescription {
            // Convert angles to radians
            val startRad = Math.toRadians(startAngle.toDouble())
            val endRad = Math.toRadians(endAngle.toDouble())
            
            // First finger path
            val path1 = Path()
            val startX1 = centerX + (radius * Math.cos(startRad)).toFloat()
            val startY1 = centerY + (radius * Math.sin(startRad)).toFloat()
            val endX1 = centerX + (radius * Math.cos(endRad)).toFloat()
            val endY1 = centerY + (radius * Math.sin(endRad)).toFloat()
            
            path1.moveTo(startX1, startY1)
            
            // Add arc for smoother rotation
            val sweepAngle = endAngle - startAngle
            path1.addArc(
                centerX - radius, 
                centerY - radius, 
                centerX + radius, 
                centerY + radius, 
                startAngle, 
                sweepAngle
            )
            
            // Second finger path (opposite side)
            val path2 = Path()
            val startX2 = centerX - (radius * Math.cos(startRad)).toFloat()
            val startY2 = centerY - (radius * Math.sin(startRad)).toFloat()
            val endX2 = centerX - (radius * Math.cos(endRad)).toFloat()
            val endY2 = centerY - (radius * Math.sin(endRad)).toFloat()
            
            path2.moveTo(startX2, startY2)
            
            // Add arc for smoother rotation
            path2.addArc(
                centerX - radius, 
                centerY - radius, 
                centerX + radius, 
                centerY + radius, 
                startAngle + 180, 
                sweepAngle
            )
            
            val stroke1 = GestureDescription.StrokeDescription(path1, 0, duration)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0, duration)
            
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke1)
            builder.addStroke(stroke2)
            
            return builder.build()
        }
        
        /**
         * Execute a gesture and wait for completion using CompletableFuture
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun executeGesture(
            service: AccessibilityService, 
            gesture: GestureDescription
        ): CompletableFuture<Boolean> {
            val future = CompletableFuture<Boolean>()
            
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        future.complete(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        future.complete(false)
                    }
                },
                Handler(Looper.getMainLooper())
            )
            
            return future
        }
        
        /**
         * Find a node by text and click on it
         */
        fun findAndClickNodeByText(
            service: AccessibilityService, 
            text: String
        ): Boolean {
            try {
                val rootNode = service.rootInActiveWindow ?: return false
                
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    val result = performClickOnNode(node)
                    if (result) {
                        return true
                    }
                }
                
                rootNode.recycle()
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Error finding and clicking node by text: ${e.message}")
                return false
            }
        }
        
        /**
         * Find a node by ID and click on it
         */
        fun findAndClickNodeById(
            service: AccessibilityService, 
            viewId: String
        ): Boolean {
            try {
                val rootNode = service.rootInActiveWindow ?: return false
                
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                for (node in nodes) {
                    val result = performClickOnNode(node)
                    if (result) {
                        return true
                    }
                }
                
                rootNode.recycle()
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Error finding and clicking node by ID: ${e.message}")
                return false
            }
        }
        
        /**
         * Perform a click on a node
         */
        private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // Try to find a clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        return result
                    }
                    val temp = parent.parent
                    parent.recycle()
                    parent = temp
                }
            }
            return false
        }
        
        /**
         * Perform screen scroll in a direction
         */
        fun performScroll(
            service: AccessibilityService, 
            direction: Int
        ): Boolean {
            return try {
                val rootNode = service.rootInActiveWindow ?: return false
                val result = rootNode.performAction(direction)
                rootNode.recycle()
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error performing scroll: ${e.message}")
                false
            }
        }
        
        /**
         * Get screen dimensions from an AccessibilityService
         */
        fun getScreenDimensions(service: AccessibilityService): Point {
            val rootNode = service.rootInActiveWindow
            val screenBounds = Rect()
            
            rootNode?.getBoundsInScreen(screenBounds)
            rootNode?.recycle()
            
            return Point(screenBounds.right, screenBounds.bottom)
        }
    }
}