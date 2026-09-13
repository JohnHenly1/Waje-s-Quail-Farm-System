package com.example.exp1

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Bridges the existing local (per-device, SharedPreferences-backed) notification
 * toggles — AccountManager.isAlertsEnabled() / isScheduleEnabled() — to FCM topic
 * subscriptions, so server-side (Cloud Functions) alerts respect the same on/off
 * switches the user already sees in AlertsActivity's Notification Preferences
 * dialog, with no change to how those prefs are stored.
 *
 * Two topics, mirroring AlertsMonitor's two gates:
 *  - "farm_alerts"     -> inventory + water-level pushes (gated by isAlertsEnabled)
 *  - "task_reminders"  -> missed/overdue task pushes      (gated by isScheduleEnabled)
 */
object PushTopics {

    private const val TAG = "PushTopics"
    const val TOPIC_FARM_ALERTS = "farm_alerts"
    const val TOPIC_TASK_REMINDERS = "task_reminders"

    /** Call on app start, on login, on token refresh, and right after preferences are saved. */
    fun syncSubscriptions(context: Context) {
        val accountManager = AccountManager(context)
        setTopic(TOPIC_FARM_ALERTS, accountManager.isAlertsEnabled())
        setTopic(TOPIC_TASK_REMINDERS, accountManager.isScheduleEnabled())
    }

    private fun setTopic(topic: String, subscribed: Boolean) {
        val messaging = FirebaseMessaging.getInstance()
        val task = if (subscribed) messaging.subscribeToTopic(topic) else messaging.unsubscribeFromTopic(topic)
        task.addOnFailureListener { e -> Log.e(TAG, "Failed to sync topic $topic: ${e.message}") }
    }
}