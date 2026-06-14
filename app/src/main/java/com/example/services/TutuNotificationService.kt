package com.example.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle

data class TutuNotificationData(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

class TutuNotificationService : NotificationListenerService() {

    companion object {
        private val notificationBuffer = mutableListOf<TutuNotificationData>()

        fun getRecentNotifications(): List<TutuNotificationData> {
            synchronized(notificationBuffer) {
                // Return copy of buffered notifications
                return notificationBuffer.toList()
            }
        }

        fun clearNotifications() {
            synchronized(notificationBuffer) {
                notificationBuffer.clear()
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val extras: Bundle? = sbn.notification.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""
        val packageName = sbn.packageName ?: ""

        if (title.isNotEmpty() && text.isNotEmpty() && packageName != "android") {
            val data = TutuNotificationData(
                packageName = packageName,
                title = title,
                text = text,
                timestamp = System.currentTimeMillis()
            )

            synchronized(notificationBuffer) {
                // Add first, keep maximum of last 10 notifications for privacy/memory
                notificationBuffer.add(0, data)
                if (notificationBuffer.size > 10) {
                    notificationBuffer.removeAt(notificationBuffer.size - 1)
                }
            }
        }
    }
}
