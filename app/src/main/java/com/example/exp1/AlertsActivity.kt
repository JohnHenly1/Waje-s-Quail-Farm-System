package com.example.exp1

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AlertsActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager
    private lateinit var roleManager: RoleManager
    private var farmAlertsListener: com.google.firebase.firestore.ListenerRegistration? = null

    private var activeFilter = "All"

    companion object {
        // Time-gate: integrity checks run at most once per hour across the whole process.
        // FarmRepository.addAlert() uses deterministic doc IDs (idempotent .set()) as the
        // real authoritative dedup — this gate just avoids unnecessary Firestore reads.
        private var lastIntegrityCheckMs = 0L
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_alerts)

        accountManager = AccountManager(this)
        roleManager = RoleManager(accountManager.getCurrentRole())

        try {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.clearAllButton).setOnClickListener {
            // Delete alerts from Firestore (Cloud sync)
            FarmRepository.clearAllAlerts { err ->
                if (err == null) {
                    GlobalData.clearAlerts()
                    updateAlertsList()
                    Toast.makeText(this, "All alerts cleared from cloud", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to clear alerts: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<View>(R.id.markAllReadBtn).setOnClickListener {
            GlobalData.markAllAsRead()
            // Sync read status to cloud
            FarmRepository.markAllAlertsRead()
            updateAlertsList()
            Toast.makeText(this, "All alerts marked as read", Toast.LENGTH_SHORT).show()
        }

        // ROLE-BASED VISIBILITY: Notification preference only available to owners/backup
        val settingsBtn = findViewById<View>(R.id.settingsButton)
        if (roleManager.canEditFarm()) {
            settingsBtn.visibility = View.VISIBLE
            settingsBtn.setOnClickListener {
                showNotificationPrefsDialog()
            }
        } else {
            settingsBtn.visibility = View.GONE
        }

        setupFilters()
        updateAlertsList()
        NavigationHelper.setupBottomNavigation(this)

        // Data integrity checks — run at most once per hour.
        // FarmRepository.addAlert() uses deterministic doc IDs so even if this runs
        // multiple times, the same alert just overwrites the same Firestore document.
        val now = System.currentTimeMillis()
        if (now - lastIntegrityCheckMs > CHECK_INTERVAL_MS) {
            lastIntegrityCheckMs = now
            checkMissedTasks()
            checkInventoryStock()
            // checkEggCountLogs() — Egg Count category removed
        }
    }

    private fun setupFilters() {
        val filters = mapOf(
            "All" to findViewById<TextView>(R.id.filterAll),
            "Inventory" to findViewById<TextView>(R.id.filterInventory),
            "Schedule" to findViewById<TextView>(R.id.filterSchedule)
        )

        // Hide the Egg Count filter tab
        findViewById<TextView>(R.id.filterEggCount)?.visibility = View.GONE

        filters.forEach { (name, view) ->
            view?.setOnClickListener {
                activeFilter = name
                updateFilterUI(filters)
                updateAlertsList()
            }
        }
    }

    private fun updateFilterUI(filters: Map<String, TextView?>) {
        filters.forEach { (name, view) ->
            if (name == activeFilter) {
                view?.setBackgroundResource(R.drawable.rounded_dark_green)
                view?.setTextColor(Color.WHITE)
                view?.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                view?.setBackgroundResource(R.drawable.rounded_gray_bg)
                view?.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
                view?.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        // Keep Egg Count tab hidden regardless of selection
        findViewById<TextView>(R.id.filterEggCount)?.visibility = View.GONE
    }

    // Tracker to prevent auto-alerts from reappearing on the same day once cleared
    private fun wasAlreadyAlertedToday(message: String): Boolean {
        val prefs = getSharedPreferences("auto_alerts_tracker", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return prefs.getString(message, "") == today
    }

    private fun markAsAlertedToday(message: String) {
        val prefs = getSharedPreferences("auto_alerts_tracker", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        prefs.edit().putString(message, today).apply()
    }

    private fun checkMissedTasks() {
        if (!accountManager.isScheduleEnabled()) return

        val now = Calendar.getInstance()

        FirebaseFirestore.getInstance().collection("farm_data")
            .document("shared").collection("tasks")
            .whereEqualTo("status", "Pending")
            .get()
            .addOnSuccessListener { snapshots ->
                for (doc in snapshots) {
                    val year  = doc.getLong("year")?.toInt()  ?: 0
                    val month = doc.getLong("month")?.toInt() ?: 0
                    val day   = doc.getLong("day")?.toInt()   ?: 0
                    val title = doc.getString("title") ?: "Task"

                    val taskDate = Calendar.getInstance()
                    taskDate.set(year, month, day, 23, 59)

                    if (taskDate.before(now)) {
                        val message = "Missed Task: $title was scheduled for ${day}/${month + 1}/${year}"
                        // wasAlreadyAlertedToday uses a local SharedPrefs key per device
                        // to prevent re-adding the same alert every time the screen opens.
                        // This is the primary dedup gate; GlobalData.addAlert has a secondary
                        // same-day dedup as a safety net.
                        if (!wasAlreadyAlertedToday(message)) {
                            FarmRepository.addAlert(message, "Critical")
                            markAsAlertedToday(message)
                        }
                    }
                }
            }
    }

    private fun checkInventoryStock() {
        if (!accountManager.isAlertsEnabled()) return

        FirebaseFirestore.getInstance().collection("farm_data")
            .document("shared").collection("feed")
            .get()
            .addOnSuccessListener { snapshots ->
                for (doc in snapshots) {
                    val qty  = doc.getLong("quantity") ?: 0L
                    val name = doc.getString("name") ?: "Item"

                    if (qty == 0L) {
                        val message = "Inventory Alert: $name is STOCK DEPLETED"
                        if (!wasAlreadyAlertedToday(message)) {
                            FarmRepository.addAlert(message, "Critical")
                            markAsAlertedToday(message)
                        }
                    } else {
                        val status = doc.getString("status") ?: ""
                        if (status == "Low Stock" || status == "Medium") {
                            val message = "Inventory Alert: $name is currently $status"
                            if (!wasAlreadyAlertedToday(message)) {
                                FarmRepository.addAlert(message, "Inventory")
                                markAsAlertedToday(message)
                            }
                        }
                    }
                }
            }
    }

    private fun checkEggCountLogs() {
        if (!accountManager.isEggCountEnabled()) return

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        FirebaseDatabase.getInstance().getReference("egg_collections").child(todayStr).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    val dayStamp = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
                    val message = "Log Missing: No egg collection recorded for today yet."
                    val existingAlerts = GlobalData.getAlerts()

                    if (existingAlerts.none { it.message == message && it.timestamp.startsWith(dayStamp) } && !wasAlreadyAlertedToday(message)) {
                        FarmRepository.addAlert(message, "Egg Count")
                        markAsAlertedToday(message)
                    }
                }
            }
    }

    private fun showNotificationPrefsDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_notification_preferences, null)
        builder.setView(dialogView)

        val switchAlerts = dialogView.findViewById<SwitchCompat>(R.id.switchAlerts)
        val switchGlobalData = dialogView.findViewById<SwitchCompat>(R.id.switchGlobalData)
        val switchSchedule = dialogView.findViewById<SwitchCompat>(R.id.switchSchedule)
        val switchEggCount = dialogView.findViewById<SwitchCompat>(R.id.switchEggCount)
        val btnEggCountTime = dialogView.findViewById<Button>(R.id.btnEggCountTime)

        switchAlerts.isChecked = accountManager.isAlertsEnabled()
        switchGlobalData.isChecked = accountManager.isGlobalDataEnabled()
        switchSchedule.isChecked = accountManager.isScheduleEnabled()
        switchEggCount.isChecked = accountManager.isEggCountEnabled()

        val hour = accountManager.getEggCountHour()
        val minute = accountManager.getEggCountMinute()
        btnEggCountTime.text = String.format("%02d:%02d", hour, minute)

        btnEggCountTime.setOnClickListener {
            val timePicker = android.app.TimePickerDialog(this, { _, selectedHour, selectedMinute ->
                btnEggCountTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)
            }, hour, minute, false)
            timePicker.show()
        }

        builder.setTitle("Notification Preferences")
            .setPositiveButton("Save") { _, _ ->
                val selectedTime = btnEggCountTime.text.toString().split(":")
                val eggHour = selectedTime[0].toInt()
                val eggMinute = selectedTime[1].toInt()
                accountManager.saveNotificationPreferences(
                    switchAlerts.isChecked,
                    switchGlobalData.isChecked,
                    switchSchedule.isChecked,
                    switchEggCount.isChecked,
                    eggHour,
                    eggMinute
                )
                Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show()
                if (switchEggCount.isChecked) {
                    scheduleEggCountNotification(eggHour, eggMinute)
                } else {
                    cancelEggCountNotification()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()

        farmAlertsListener = FarmRepository.listenToAlerts { alerts ->
            val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
            val mappedAlerts = alerts.map { alert ->
                val firestoreTs = alert["timestamp"]
                val timestampStr = when (firestoreTs) {
                    is com.google.firebase.Timestamp -> sdf.format(firestoreTs.toDate())
                    is String -> firestoreTs
                    else -> "Just now"
                }
                GlobalData.AlertItem(
                    message = alert["message"] as? String ?: "",
                    timestamp = timestampStr,
                    type = alert["type"] as? String ?: "Inventory",
                    isRead = alert["isRead"] as? Boolean ?: false
                )
            }
            GlobalData.syncWithCloud(mappedAlerts)
            runOnUiThread { updateAlertsList() }
        }
    }

    override fun onPause() {
        super.onPause()
        farmAlertsListener?.remove()
        farmAlertsListener = null
    }

    private fun updateAlertsList() {
        val container = findViewById<LinearLayout>(R.id.alertsContainer) ?: return

        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val allAlerts = GlobalData.getAlerts()

        // Filter logic updated to correctly handle category separation
        val alerts = if (activeFilter == "All") {
            allAlerts
        } else {
            allAlerts.filter {
                when(activeFilter) {
                    "Inventory" -> it.type == "Inventory" || (it.type == "Critical" && it.message.contains("Inventory", true))
                    "Schedule" -> it.type == "Schedule" || (it.type == "Critical" && it.message.contains("Missed", true))
                    else -> true
                }
            }
        }

        // Proper date-based alignment/sorting for the alert list
        val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
        val sortedAlerts = alerts.sortedByDescending {
            try { sdf.parse(it.timestamp) } catch (e: Exception) { Date(0) }
        }

        // Summary Cards - Count based on current filtered view and priority
        val urgentCount = sortedAlerts.count { it.type == "Critical" || it.message.contains("EMPTY", true) || it.message.contains("DEPLETED", true) }
        val generalCount = sortedAlerts.count { it.type != "Critical" && !it.message.contains("EMPTY", true) && !it.message.contains("DEPLETED", true) }

        findViewById<TextView>(R.id.criticalCount)?.text = urgentCount.toString()
        findViewById<TextView>(R.id.warningCount)?.text = generalCount.toString()
        findViewById<TextView>(R.id.totalCount)?.text = sortedAlerts.size.toString()

        updateSummaryLabels()

        if (sortedAlerts.isEmpty()) {
            val emptyTxt = TextView(this)
            emptyTxt.text = "No $activeFilter alerts available"
            emptyTxt.textAlignment = View.TEXT_ALIGNMENT_CENTER
            emptyTxt.setPadding(32, 64, 32, 32)
            container.addView(emptyTxt)
        } else {
            val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
            for ((index, alert) in sortedAlerts.withIndex()) {
                val itemView = inflater.inflate(R.layout.item_alert, container, false)

                val icon = itemView.findViewById<ImageView>(R.id.alertIcon)
                val stripe = itemView.findViewById<View>(R.id.alertAccentStripe)
                val categoryTv = itemView.findViewById<TextView>(R.id.alertCategory)
                val timeTv = itemView.findViewById<TextView>(R.id.alertTime)
                val titleTv = itemView.findViewById<TextView>(R.id.alertTitle)
                val msgTv = itemView.findViewById<TextView>(R.id.alertMessage)
                val pillTv = itemView.findViewById<TextView>(R.id.alertStatusPill)

                msgTv.text = alert.message
                timeTv.text = getRelativeTime(alert.timestamp)

                // Enhanced descriptive UI configuration
                when {
                    alert.type == "Egg Count" || alert.type == "System" || alert.message.contains("Egg", true) -> {
                        setupAlertUI(stripe, icon, pillTv, categoryTv, "EGG PRODUCTION", "PRODUCTION", "#4CAF50", R.drawable.lc_egg)
                        titleTv.text = if (alert.message.contains("summary")) "Daily Production Summary" else "Egg Collection Alert"
                    }
                    alert.type == "Inventory" || alert.message.contains("Inventory", true) || alert.type == "Critical" && alert.message.contains("Inventory", true) -> {
                        val isDepleted = alert.message.contains("EMPTY", true) || alert.message.contains("DEPLETED", true)
                        val isCritical = isDepleted || alert.message.contains("LOW", true)
                        val status = when {
                            isDepleted -> "STOCK DEPLETED"
                            alert.message.contains("LOW", true) -> "CRITICALLY LOW"
                            else -> "REORDER SOON"
                        }
                        setupAlertUI(stripe, icon, pillTv, categoryTv, "INVENTORY", status, if (isCritical) "#F44336" else "#FF9800", R.drawable.ic_shopping_bag)
                        titleTv.text = if (isCritical) "Critical Stock Warning" else "Inventory Update"
                    }
                    alert.type == "Schedule" || alert.message.contains("Missed", true) || alert.type == "Critical" && alert.message.contains("Missed", true) -> {
                        val isMissed = alert.message.contains("Missed", true)
                        setupAlertUI(stripe, icon, pillTv, categoryTv, "SCHEDULE", if (isMissed) "OVERDUE" else "DUE TODAY", if (isMissed) "#B71C1C" else "#2196F3", R.drawable.ic_calendar)
                        titleTv.text = if (isMissed) "Task Overdue Notice" else "Upcoming Task"
                    }
                    else -> {
                        setupAlertUI(stripe, icon, pillTv, categoryTv, "FARM ALERT", "INFO", "#9E9E9E", R.drawable.ic_info)
                        titleTv.text = "Notification"
                    }
                }

                itemView.alpha = if (alert.isRead) 0.6f else 1.0f
                slideUp.startOffset = (index * 50).toLong()
                itemView.startAnimation(slideUp)
                container.addView(itemView)
            }
        }
    }

    private fun updateSummaryLabels() {
        val criticalLabel = findViewById<TextView>(R.id.criticalLabel)
        val warningLabel = findViewById<TextView>(R.id.warningLabel)
        val totalLabel = findViewById<TextView>(R.id.totalLabel)

        when (activeFilter) {
            "Inventory" -> {
                criticalLabel.text = "Stock Depleted"
                warningLabel.text = "Low Stock"
                totalLabel.text = "Inventory Alerts"
            }
            "Schedule" -> {
                criticalLabel.text = "Overdue Tasks"
                warningLabel.text = "Pending Today"
                totalLabel.text = "Scheduled Duty"
            }
            else -> {
                criticalLabel.text = "Urgent Issues"
                warningLabel.text = "General Alerts"
                totalLabel.text = "All Notifications"
            }
        }
    }

    private fun setupAlertUI(stripe: View, icon: ImageView, pill: TextView, cat: TextView, catName: String, statusText: String, colorHex: String, iconRes: Int) {
        val color = Color.parseColor(colorHex)
        stripe.setBackgroundColor(color)
        icon.setImageResource(iconRes)
        icon.setColorFilter(color)
        icon.backgroundTintList = ColorStateList.valueOf(color).withAlpha(30)
        pill.text = statusText
        pill.backgroundTintList = ColorStateList.valueOf(color)
        cat.text = catName
        cat.setTextColor(color)
    }

    private fun getRelativeTime(timestamp: String): String {
        try {
            val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
            val date = sdf.parse(timestamp) ?: return timestamp
            val now = System.currentTimeMillis()
            val diff = now - date.time

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} mins ago"
                diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} hours ago"
                diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)} days ago"
                else -> timestamp.split(" ")[0]
            }
        } catch (e: Exception) {
            return timestamp
        }
    }

    private fun scheduleEggCountNotification(hour: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, EggCountNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun cancelEggCountNotification() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, EggCountNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }

    class EggCountNotificationReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "egg_count_channel",
                    "Egg Count Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Daily egg count reminders"
                    enableLights(true)
                    enableVibration(true)
                    setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm != null) nm.createNotificationChannel(channel)
            }

            val accountManager = AccountManager(context)
            if (!accountManager.isAlertsEnabled() || !accountManager.isEggCountEnabled()) return

            val database = FirebaseDatabase.getInstance()
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            database.getReference("egg_collections").child(todayStr).get().addOnSuccessListener { snapshot ->
                val total = (snapshot.child("total").value as? Long)?.toInt() ?: 0
                val message = "Today's egg count summary: $total eggs collected."

                val timestamp = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()).format(Date())
                GlobalData.addAlert(message, timestamp, "System")

                val notificationIntent = Intent(context, MainActivity::class.java)
                notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val builder = androidx.core.app.NotificationCompat.Builder(context, "egg_count_channel")
                    .setSmallIcon(R.drawable.ic_notifications)
                    .setContentTitle("Daily Egg Count Reminder")
                    .setContentText(message)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                    .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)

                try {
                    androidx.core.app.NotificationManagerCompat.from(context).notify(2002, builder.build())
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }
}