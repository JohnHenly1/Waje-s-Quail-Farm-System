package com.example.exp1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the data-only FCM pushes sent by the Cloud Functions in /functions.
 * This is what lets alerts fire when the app process isn't running or the
 * screen has been off — AlertsMonitor's live listeners can't do that on their
 * own since they only run while the app is in memory (see AlertsMonitor's
 * class doc). Detection now also happens server-side; this class is purely
 * "receive push -> show the same-looking notification".
 *
 * Expected data payload keys: title, body, channel ("alerts_channel" or
 * "task_reminder_channel"), notifId (stable int-as-string for de-duplication
 * on the notification shade, mirrors FarmRepository's deterministic alert IDs).
 */
class WajeFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Topic subscriptions are per-token; re-assert them so a fresh token
        // (new install, app data cleared, token rotation) still matches the
        // user's current alert/schedule preferences.
        PushTopics.syncSubscriptions(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "Waje's Quail Farm"
        val body = data["body"] ?: message.notification?.body ?: return

        // Accept/Decline requests (critical alerts, task assignments) get the alarm/request UI.
        val ackId = data["ackId"]
        if (!ackId.isNullOrBlank()) {
            // Task-assignment requests use "assign_<groupId>" ack ids (see
            // AckRepository.createTaskAssignmentRequest / checkAlerts.js) - these are schedule
            // notifications and no longer get Accept/Decline buttons in the notification shade;
            // tapping still opens the full Accept/Decline screen.
            val isScheduleAssignment = ackId.startsWith("assign_")
            AlarmNotifier.show(
                applicationContext, ackId, title, body,
                alarm = data["critical"] == "true",
                quickActions = !isScheduleAssignment
            )
            return
        }
        val channelId = data["channel"].takeUnless { it.isNullOrBlank() } ?: "alerts_channel"
        val notifId = data["notifId"]?.toIntOrNull() ?: body.hashCode()

        ensureChannel(channelId)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(this).notify(notifId, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = if (channelId == "task_reminder_channel") "Task Reminders" else "Farm Alerts"
        val description = if (channelId == "task_reminder_channel")
            "Notifications for farm tasks" else "Important farm alerts: inventory, schedule, water level"

        val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
            this.description = description
            enableLights(true)
            enableVibration(true)
            setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}