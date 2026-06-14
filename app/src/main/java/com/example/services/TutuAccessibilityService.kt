package com.example.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class TutuAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_ACCESSIBILITY_TRIGGER = "com.example.TUTU_ACCESSIBILITY_ACTION"
        const val EXTRA_GESTURE = "extra_gesture"
        const val GESTURE_BACK = 1
        const val GESTURE_HOME = 2
        const val GESTURE_RECENTS = 3
        const val GESTURE_NOTIFICATIONS = 4
    }

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ACCESSIBILITY_TRIGGER) {
                val gesture = intent.getIntExtra(EXTRA_GESTURE, -1)
                handleGestureAction(gesture)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Register intent receiver
        val filter = IntentFilter(ACTION_ACCESSIBILITY_TRIGGER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(gestureReceiver, filter)
        }

        // Configure service behavior programmatically
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        this.serviceInfo = info
    }

    private fun handleGestureAction(gesture: Int) {
        when (gesture) {
            GESTURE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GESTURE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GESTURE_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GESTURE_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Here we can monitor window states if needed to find running app names
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(gestureReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
