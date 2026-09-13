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
    // Only fires when the level moves above 50% — signals the tank is at a
    // healthy/filled level. Everything at or below 50% is intentionally
    // silent now (previously had separate Notice/Warning/Critical/Emergency
    // tiers for low levels; those were removed).
    fun resolveWaterLevelAlert(percent: Int): Triple<Int, String, String>? {
        return if (percent > 50) {
            Triple(percent, "Filled", "Water tank is filled and at a healthy level.")
        } else {
            null
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
    // FIX: this used to notify for EVERY pending task in the shared collection,
    // on every device that had Schedule alerts on — so a staff member's phone
    // would buzz for tasks assigned to other staff, or to no one at all. Now
    // only raises a notification on THIS device if the task's assignedTo list
    // actually includes the currently logged-in user, mirroring the same
    // "assignedToMe" filtering ScheduleActivity already uses for what's shown
    // on-screen.
    private fun startTasksListener() {
        tasksListener?.remove()
        tasksListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("tasks")
            .whereEqualTo("status", "Pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                val accountManager = AccountManager(appContext)
                if (!accountManager.isScheduleEnabled()) return@addSnapshotListener
                val currentUserEmail = accountManager.getCurrentUsername() ?: return@addSnapshotListener
                val now = Calendar.getInstance()
                for (doc in snapshots.documents) {
                    if (!isAssignedTo(doc.get("assignedTo"), currentUserEmail)) continue

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

    /**
     * Reads a task's `assignedTo` field defensively — new docs store a
     * List<String> (multi-assign), older docs stored a single String email —
     * and checks (case-insensitively) whether it contains [email]. Mirrors
     * ScheduleActivity.parseAssignedTo()'s normalization.
     */
    private fun isAssignedTo(raw: Any?, email: String): Boolean {
        val assignees: List<String> = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
        return assignees.any { it.equals(email, ignoreCase = true) }
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