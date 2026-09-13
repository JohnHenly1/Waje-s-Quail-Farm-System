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
 * "farm_alerts" (inventory + water level) is intentionally shared by every
 * device — those alerts are meant to reach the whole team.
 *
 * FIX: task/schedule reminders used to also go through one shared topic
 * ("task_reminders"), which meant every device with Schedule alerts on
 * received every OTHER staff member's task notifications too, regardless of
 * who a task was actually assigned to. Each device now instead subscribes to
 * a topic derived from ITS OWN logged-in user's email, and the Cloud
 * Function looks up each task's `assignedTo` list and pushes only to those
 * specific per-user topics (see topicForUser() in functions/index.js, which
 * this sanitization must exactly mirror).
 */
object PushTopics {

    private const val TAG = "PushTopics"
    const val TOPIC_FARM_ALERTS = "farm_alerts"

    private const val PREFS_NAME = "push_topics_tracker"
    private const val KEY_SUBSCRIBED_USER_TOPIC = "subscribed_user_task_topic"

    /**
     * Call on app start, on login, on logout, on token refresh, and right after
     * preferences are saved. Safe to call repeatedly / with no signed-in user.
     */
    fun syncSubscriptions(context: Context) {
        val accountManager = AccountManager(context)
        setTopic(TOPIC_FARM_ALERTS, accountManager.isAlertsEnabled())
        syncUserTaskTopic(context, accountManager)
    }

    /**
     * Subscribes this device to its current user's personal task-reminder topic
     * (gated by the Schedule-alerts toggle), and unsubscribes from whatever
     * per-user topic it was previously on — covers logout, switching accounts on
     * a shared device, and toggling Schedule alerts off.
     */
    private fun syncUserTaskTopic(context: Context, accountManager: AccountManager) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousTopic = prefs.getString(KEY_SUBSCRIBED_USER_TOPIC, null)

        val email = accountManager.getCurrentUsername()
        val scheduleEnabled = accountManager.isScheduleEnabled()
        val newTopic = if (!email.isNullOrBlank() && scheduleEnabled) topicForUser(email) else null

        if (previousTopic != null && previousTopic != newTopic) {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(previousTopic)
                .addOnFailureListener { e -> Log.e(TAG, "Failed to unsubscribe $previousTopic: ${e.message}") }
        }

        if (newTopic != null && newTopic != previousTopic) {
            FirebaseMessaging.getInstance().subscribeToTopic(newTopic)
                .addOnFailureListener { e -> Log.e(TAG, "Failed to subscribe $newTopic: ${e.message}") }
        }

        prefs.edit().putString(KEY_SUBSCRIBED_USER_TOPIC, newTopic).apply()
    }

    /**
     * Deterministic per-user topic name. MUST exactly mirror the sanitization
     * used server-side in functions/index.js's topicForUser() — any mismatch
     * means pushes silently go to a topic this device never subscribed to.
     */
    fun topicForUser(email: String): String {
        return "user_" + email.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
    }

    private fun setTopic(topic: String, subscribed: Boolean) {
        val messaging = FirebaseMessaging.getInstance()
        val task = if (subscribed) messaging.subscribeToTopic(topic) else messaging.unsubscribeFromTopic(topic)
        task.addOnFailureListener { e -> Log.e(TAG, "Failed to sync topic $topic: ${e.message}") }
    }
}