package com.example.exp1

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * App-wide alerts watcher: inventory stock, overdue tasks, and water level.
 *
 * FIX: this detection logic used to live entirely inside AlertsActivity —
 * a one-time check in onCreate() (gated so it only ran once per app launch),
 * plus live Firestore/RTDB listeners started in onResume() and torn down in
 * onPause(). That meant a low-stock feed item, an overdue task, or a water
 * tank crossing a threshold only produced an alert + system notification if
 * a staff member happened to have the Notifications screen open right then
 * — otherwise nothing fired until they next opened the app AND navigated to
 * that screen themselves.
 *
 * Moved here and started once from WajeApplication.onCreate(), mirroring
 * MaintenanceGuard's pattern, so the same checks keep running for as long as
 * the app process is alive — regardless of which screen (if any) is
 * currently on-screen. AlertsActivity no longer owns any of this detection;
 * it just displays the resulting alert list.
 */
object AlertsMonitor {

    private const val CHANNEL_ID = "alerts_channel"
    private const val PREFS_NAME = "auto_alerts_tracker"

    private var started = false
    private lateinit var appContext: Context

    private var inventoryListener: ListenerRegistration? = null
    private var tasksListener: ListenerRegistration? = null
    private var waterLevelRef: DatabaseReference? = null
    private var waterLevelListener: ValueEventListener? = null

    /** Call once, from WajeApplication.onCreate(). Safe to call more than once. */
    fun start(app: Application) {
        if (started) return
        started = true
        appContext = app.applicationContext

        createNotificationChannel()

        startInventoryListener()
        startTasksListener()
        startWaterLevelListener()
    }

    // ── Day-based dedup so the same threshold doesn't re-alert repeatedly ───
    // Shared SharedPreferences file/keys that AlertsActivity previously used,
    // so a message already alerted today stays deduped either way.
    private fun wasAlreadyAlertedToday(message: String): Boolean {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return prefs.getString(message, "") == today
    }

    private fun markAsAlertedToday(message: String) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        prefs.edit().putString(message, today).apply()
    }

    // ── Inventory ────────────────────────────────────────────────────────
    private fun startInventoryListener() {
        inventoryListener?.remove()
        inventoryListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("feed")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                if (!AccountManager(appContext).isAlertsEnabled()) return@addSnapshotListener
                for (dc in snapshots.documentChanges) {
                    if (dc.type != DocumentChange.Type.ADDED && dc.type != DocumentChange.Type.MODIFIED) continue
                    val doc = dc.document
                    val qty = doc.getLong("quantity") ?: 0L
                    val name = doc.getString("name") ?: "Item"
                    if (qty == 0L) {
                        val message = "Inventory Alert: $name is STOCK DEPLETED"
                        if (!wasAlreadyAlertedToday(message)) {
                            FarmRepository.addAlert(message, "Critical")
                            markAsAlertedToday(message)
                            showLocalNotification("Inventory Alert", message)
                        }
                    } else {
                        val status = doc.getString("status") ?: ""
                        if (status == "Low Stock" || status == "Medium") {
                            val message = "Inventory Alert: $name is currently $status"
                            if (!wasAlreadyAlertedToday(message)) {
                                FarmRepository.addAlert(message, "Inventory")
                                markAsAlertedToday(message)
                                showLocalNotification("Inventory Update", message)
                            }
                        }
                    }
                }
            }
    }

    // ── Water level ──────────────────────────────────────────────────────
    // Maps a water level percentage to the alert threshold it falls under
    // (if any). Checked most-severe-first so e.g. 0% resolves to
    // "Emergency", not "Notice". percent >= 100 ("Refilled") is what covers
    // the "notify when the tank shows full" case.
    fun resolveWaterLevelAlert(percent: Int): Triple<Int, String, String>? {
        return when {
            percent >= 100 -> Triple(100, "Refilled", "Water tank has been successfully refilled to full capacity.")
            percent <= 0 -> Triple(0, "Emergency", "Water tank is empty. Refill immediately to prevent disruptions.")
            percent <= 15 -> Triple(15, "Critical", "Water level is critically low. Immediate action is required.")
            percent <= 25 -> Triple(25, "Warning", "Water level is low. Refill the water tank soon.")
            percent <= 50 -> Triple(50, "Notice", "Water level is decreasing. Monitor the water supply.")
            else -> null
        }
    }

    private fun raiseWaterLevelAlert(percent: Int) {
        val (_, label, description) = resolveWaterLevelAlert(percent) ?: return
        val message = "Water Level $label: $description"
        if (!wasAlreadyAlertedToday(message)) {
            FarmRepository.addAlert(message, "Water Level")
            markAsAlertedToday(message)
            showLocalNotification("Water Level $label", description)
        }
    }

    private fun startWaterLevelListener() {
        waterLevelRef?.let { ref -> waterLevelListener?.let { ref.removeEventListener(it) } }
        waterLevelRef = FirebaseDatabase.getInstance().getReference("water_level")
        waterLevelListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!AccountManager(appContext).isAlertsEnabled()) return
                if (!snapshot.exists()) return
                val percent = snapshot.child("percentage").getValue(Long::class.java)?.toInt() ?: return
                raiseWaterLevelAlert(percent)
            }

            override fun onCancelled(error: DatabaseError) {
                // Ignore — listener auto-retries, same as elsewhere in this app.
            }
        }
        waterLevelRef?.addValueEventListener(waterLevelListener!!)
    }

    // ── Missed / overdue tasks ───────────────────────────────────────────
    private fun startTasksListener() {
        tasksListener?.remove()
        tasksListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("tasks")
            .whereEqualTo("status", "Pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                if (!AccountManager(appContext).isScheduleEnabled()) return@addSnapshotListener
                val now = Calendar.getInstance()
                for (doc in snapshots.documents) {
                    val year = doc.getLong("year")?.toInt() ?: 0
                    val month = doc.getLong("month")?.toInt() ?: 0
                    val day = doc.getLong("day")?.toInt() ?: 0
                    val title = doc.getString("title") ?: "Task"
                    val hour = doc.getLong("hour")?.toInt() ?: 23
                    val minute = doc.getLong("minute")?.toInt() ?: 59

                    val taskDate = Calendar.getInstance()
                    taskDate.set(year, month, day, hour, minute)

                    if (taskDate.before(now)) {
                        val message = "Missed Task: $title was scheduled for ${day}/${month + 1}/${year}"
                        if (!wasAlreadyAlertedToday(message)) {
                            FarmRepository.addAlert(message, "Critical")
                            markAsAlertedToday(message)
                            showLocalNotification("Missed Task", message)
                        }
                    }
                }
            }
    }

    // ── System notification ─────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Farm Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important farm alerts: inventory, schedule, water level"
                enableLights(true)
                enableVibration(true)
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            }
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun showLocalNotification(title: String, message: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(appContext).notify(message.hashCode(), builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
