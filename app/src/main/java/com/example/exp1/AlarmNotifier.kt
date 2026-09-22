package com.example.exp1

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object AlarmNotifier {
    const val EXTRA_ACK_ID = "ack_id"
    const val EXTRA_TITLE = "ack_title"
    const val EXTRA_MESSAGE = "ack_message"
    const val EXTRA_ALARM = "ack_alarm"
    const val EXTRA_DECLINE = "ack_decline"

    private const val CHANNEL_ALARM = "critical_alarm_channel_v1"
    private const val CHANNEL_REQUEST = "ack_request_channel_v1"
    private const val ACTION_ACCEPT = "com.example.exp1.ACK_ACCEPT"
    private val ALARM_VIBRATION = longArrayOf(0, 800, 400, 800, 400, 800)

    private fun nid(ackId: String) = ackId.hashCode()

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Critical Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Loud alarm for critical farm alerts"
                setSound(alarmSound, attrs)          // alarm stream: audible even on silent/vibrate
                enableVibration(true)
                vibrationPattern = ALARM_VIBRATION
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)                   // only takes effect if the user grants DND access
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUEST, "Requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Task assignments and requests that need your Accept / Decline"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /** Posts (or updates in place) the notification for a request. [alarm] = loud, repeating, full-screen. */
    @Suppress("DEPRECATION")
    fun show(ctx: Context, ackId: String, title: String, message: String, alarm: Boolean) {
        ensureChannels(ctx)
        val id = nid(ackId)

        fun screen(decline: Boolean) = Intent(ctx, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACK_ID, ackId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_ALARM, alarm)
            putExtra(EXTRA_DECLINE, decline)
        }
        val pf = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(ctx, id * 3, screen(false), pf)
        val decline = PendingIntent.getActivity(ctx, id * 3 + 1, screen(true), pf)
        val accept = PendingIntent.getBroadcast(
            ctx, id * 3 + 2,
            Intent(ctx, ActionReceiver::class.java).setAction(ACTION_ACCEPT).putExtra(EXTRA_ACK_ID, ackId), pf
        )

        val builder = NotificationCompat.Builder(ctx, if (alarm) CHANNEL_ALARM else CHANNEL_REQUEST)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(if (alarm) Color.RED else Color.parseColor("#2E7D32"))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .addAction(0, "Accept", accept)
            .addAction(0, "Decline", decline)

        if (alarm) {
            builder.setFullScreenIntent(open, true)
            // Pre-O devices read sound/vibration from the builder; O+ reads them from the channel.
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioManager.STREAM_ALARM)
            builder.setVibrate(ALARM_VIBRATION)
        }

        val n = builder.build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT   // keep sounding until cancelled/opened
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun cancel(ctx: Context, ackId: String) {
        NotificationManagerCompat.from(ctx).cancel(nid(ackId))
    }

    /** Android 14+: full-screen alarms need this special permission; ask once, with a way to the setting. */
    fun promptFullScreenPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34 || activity.isFinishing || activity.isDestroyed) return
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) return
        val prefs = activity.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("fsi_prompted", false)) return
        prefs.edit().putBoolean("fsi_prompted", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("Allow critical alarms")
            .setMessage("To show the alarm screen over your lock screen when a critical farm alert comes in, allow full-screen notifications for this app.")
            .setPositiveButton("Open settings") { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${activity.packageName}"))
                )
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Handles the "Accept" button on the notification without opening the app. */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val ackId = intent.getStringExtra(EXTRA_ACK_ID) ?: return
            val pending = goAsync()
            AckRepository.respond(ctx, ackId, true, null) { ok ->
                if (!ok) Toast.makeText(
                    ctx.applicationContext,
                    "Couldn't send your response - open the app and try again.",
                    Toast.LENGTH_LONG
                ).show()
                pending.finish()
            }
        }
    }
}
