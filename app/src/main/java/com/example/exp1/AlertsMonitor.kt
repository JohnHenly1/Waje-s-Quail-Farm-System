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

    // Bumped from "alerts_channel" -> a new channel id, because Android
    // freezes a notification channel's sound/vibration settings the first
    // time it's created on a device. Any device that already had the old
    // channel would keep whatever (non-buzzing) settings it was created
    // with, no matter what we change here. A new id forces Android to
    // create it fresh with vibration on.
    private const val CHANNEL_ID = "alerts_channel_v2"
    private const val PREFS_NAME = "auto_alerts_tracker"
    private const val WATER_TIER_PREF_KEY = "last_water_tier"

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
    // FIX: dedup used to be keyed by the *rendered message text* — which is
    // built from the item's `name` alone. Two different feed items that
    // happen to share a name (or both fall back to the generic "Item" label
    // when `name` is missing) produced the exact same message string, so
    // the second item's notification got silently swallowed by
    // wasAlreadyAlertedToday() as if it were a duplicate of the first.
    // Dedup keys are now prefixed with the Firestore document id, so every
    // item gets its own independent notification regardless of what any
    // other item is named.
    //
    // FIX: that per-item dedup key was still day-based and, worse, never
    // reset once an item recovered — "${doc.id}:depleted" stayed marked
    // "alerted today" for the rest of the day even after the item was
    // restocked, so a depleted → restocked → depleted-again cycle only
    // ever notified the first time. Now tracks the item's *last alerted
    // state* (mirroring raiseWaterLevelAlert's tier tracking below) with
    // no day limit, and clears that state the moment the item recovers to
    // "fine" — so the next depletion is treated as a fresh transition, not
    // a repeat of the same day's alert.
    private fun inventoryAlertState(qty: Long, status: String): String? {
        if (qty == 0L) return "DEPLETED"
        return if (status == "Low Stock" || status == "Medium") status else null
    }

    private fun startInventoryListener() {
        inventoryListener?.remove()
        inventoryListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("feed")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                if (!AccountManager(appContext).isAlertsEnabled()) return@addSnapshotListener
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                for (dc in snapshots.documentChanges) {
                    if (dc.type != DocumentChange.Type.ADDED && dc.type != DocumentChange.Type.MODIFIED) continue
                    val doc = dc.document
                    val qty = doc.getLong("quantity") ?: 0L
                    val name = doc.getString("name") ?: "Item"
                    val status = doc.getString("status") ?: ""
                    val currentState = inventoryAlertState(qty, status)

                    val stateKey = "inv_state_${doc.id}"
                    val lastState = prefs.getString(stateKey, null)
                    if (currentState != lastState) {
                        prefs.edit().putString(stateKey, currentState).apply()
                    }
                    if (currentState == null || currentState == lastState) continue

                    val message = if (currentState == "DEPLETED")
                        "Inventory Alert: $name is STOCK DEPLETED"
                    else
                        "Inventory Alert: $name is currently $currentState"
                    val title = if (currentState == "DEPLETED") "Inventory Alert" else "Inventory Update"
                    val type = if (currentState == "DEPLETED") "Critical" else "Inventory"

                    FarmRepository.addAlert(message, type)
                    showLocalNotification(title, message)
                }
            }
    }

    // ── Water level ──────────────────────────────────────────────────────
    // FIX: restored the low-level tiers (Notice/Warning/Critical/Emergency)
    // that had been stripped out, leaving only the >50% "Filled" case and no
    // alert at all for a low or empty tank. Now every band from empty to
    // filled produces a notification.
    fun resolveWaterLevelAlert(percent: Int): Triple<Int, String, String>? {
        return when {
            percent > 50 -> Triple(percent, "Filled", "Water tank is filled and at a healthy level.")
            percent in 21..50 -> Triple(percent, "Notice", "Water tank is getting low. Consider refilling soon.")
            percent in 11..20 -> Triple(percent, "Warning", "Water tank is low. Please refill soon.")
            percent in 1..10 -> Triple(percent, "Critical", "Water tank is critically low. Refill immediately.")
            percent <= 0 -> Triple(percent, "Emergency", "Water tank is empty. Refill immediately.")
            else -> null
        }
    }

    // FIX: this used to dedupe by exact message text, once per calendar day —
    // so once today's "Water Level Emergency" notification had fired, the
    // tank could refill, drain back to empty, refill, drain again... and it
    // would stay silent for the rest of the day. Now it tracks only the
    // *last tier that was actually notified* and fires again the moment the
    // tier changes to something different, with no day limit. Repeated
    // Firebase updates that don't change the tier (e.g. 0% written again)
    // still won't spam duplicate notifications for the same unchanged state.
    private fun raiseWaterLevelAlert(percent: Int) {
        val (_, label, description) = resolveWaterLevelAlert(percent) ?: return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTier = prefs.getString(WATER_TIER_PREF_KEY, null)
        if (lastTier == label) return
        prefs.edit().putString(WATER_TIER_PREF_KEY, label).apply()

        val message = "Water Level $label: $description"
        FarmRepository.addAlert(message, "Water Level")
        if (label == "Emergency") {
            AckRepository.raiseCritical("Water Level $label", message) {
                showLocalNotification("Water Level $label", description)
            }
        } else {
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
    private val vibratePattern = longArrayOf(0, 400, 200, 400, 200, 400)
    // Strictly-incrementing id instead of hashing message+timestamp, so two
    // notifications fired within the same millisecond (e.g. two inventory
    // items both hitting 0 in the same batch) can never collide onto the
    // same notification id and silently overwrite one another.
    private val notificationIdCounter = java.util.concurrent.atomic.AtomicInteger(1000)

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
                vibrationPattern = vibratePattern
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
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // Pre-O devices read vibration off the builder directly (O+ reads
            // it from the channel above); setting both covers every version.
            .setVibrate(vibratePattern)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            // Make sure every post buzzes even if a notification with this
            // same id is already showing, instead of silently updating it.
            .setOnlyAlertOnce(false)

        try {
            // A unique, ever-increasing id per post so a repeat alert always
            // shows as its own new, buzzing notification instead of
            // silently colliding with or updating one already on screen.
            NotificationManagerCompat.from(appContext).notify(
                notificationIdCounter.incrementAndGet(),
                builder.build()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}