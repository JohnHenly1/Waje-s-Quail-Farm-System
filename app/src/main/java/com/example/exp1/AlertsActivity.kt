package com.example.exp1

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AlertsActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager
    private lateinit var roleManager: RoleManager
    private var farmAlertsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var inventoryListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var tasksListener: com.google.firebase.firestore.ListenerRegistration? = null

    private lateinit var alertsRecyclerView: RecyclerView
    private lateinit var alertsAdapter: AlertsAdapter

    // Coalesces rapid-fire Firestore snapshot events (e.g. a burst of writes) into a
    // single re-render instead of rebuilding the list once per event. This is one of
    // the things that made the screen lag/ANR when a lot of alerts came in at once.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pendingListUpdate = Runnable { updateAlertsList() }

    private var activeFilter = "All"

    companion object {
        // Per-session flag: run integrity checks only once per app launch, not on every
        // screen open. Firestore-side dedup in FarmRepository.addAlert() is the real
        // guard; this just avoids unnecessary network queries on repeated visits.
        private var integrityChecksRanThisSession = false

        // Cloud alert history can grow unbounded. Rendering every single alert ever
        // recorded is what actually caused the lag/crash with "too many notifications" —
        // there's no functional need to show more than the most recent ones at once.
        private const val MAX_RENDERED_ALERTS = 200

        // Debounce window for coalescing bursts of Firestore snapshot events.
        private const val LIST_UPDATE_DEBOUNCE_MS = 150L
    }

    @SuppressLint("MissingInflatedId")
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

        // RecyclerView replaces the old approach of removeAllViews() + re-inflating
        // every alert row from scratch on every refresh. RecyclerView recycles rows
        // and DiffUtil (inside AlertsAdapter) only re-binds what actually changed.
        alertsRecyclerView = findViewById(R.id.alertsRecyclerView)
        alertsAdapter = AlertsAdapter()
        alertsRecyclerView.layoutManager = LinearLayoutManager(this)
        alertsRecyclerView.adapter = alertsAdapter
        alertsRecyclerView.setHasFixedSize(true)
        // Disabling the built-in add/remove/change animations. With potentially
        // hundreds of rows changing (read-state flips, new alerts, filter switches),
        // animating every change is expensive for no real benefit here. Re-enable
        // (remove this line) if you want RecyclerView's default fade animations back.
        alertsRecyclerView.itemAnimator = null

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
        // Refresh button to manually force re-checks
        findViewById<View>(R.id.refreshButton)?.setOnClickListener {
            updateAlertsList()
            checkMissedTasks()
            checkInventoryStock()
            Toast.makeText(this, "Refreshed alerts", Toast.LENGTH_SHORT).show()
        }
        NavigationHelper.setupBottomNavigation(this)

        // Data integrity checks — guarded by session flag so they only run once
        // per app launch. FarmRepository.addAlert() also does Firestore-side dedup
        // as the authoritative guard against cross-device duplicates.
        if (!integrityChecksRanThisSession) {
            integrityChecksRanThisSession = true
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
            // Debounced instead of an immediate runOnUiThread { updateAlertsList() } —
            // a burst of Firestore snapshot events (e.g. several docs changing at once)
            // used to trigger a full list rebuild for each one individually.
            scheduleAlertsListUpdate()
        }

        // Start live listeners when activity is visible
        startLiveInventoryListener()
        startLiveTasksListener()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(pendingListUpdate)
        farmAlertsListener?.remove()
        farmAlertsListener = null
        inventoryListener?.remove()
        inventoryListener = null
        tasksListener?.remove()
        tasksListener = null
    }

    private fun scheduleAlertsListUpdate() {
        uiHandler.removeCallbacks(pendingListUpdate)
        uiHandler.postDelayed(pendingListUpdate, LIST_UPDATE_DEBOUNCE_MS)
    }

    private fun updateAlertsList() {
        val allAlerts = GlobalData.getAlerts()

        // Filter logic updated to correctly handle category separation
        val alerts = if (activeFilter == "All") {
            allAlerts
        } else {
            allAlerts.filter {
                when (activeFilter) {
                    "Inventory" -> it.type == "Inventory" || (it.type == "Critical" && it.message.contains("Inventory", true))
                    "Schedule" -> it.type == "Schedule" || (it.type == "Critical" && it.message.contains("Missed", true))
                    else -> true
                }
            }
        }

        // Parse each timestamp ONCE and reuse it for both sorting and the relative-time
        // label below, instead of parsing the same string twice per alert on every
        // single refresh (the old code parsed once for sorting and again inside
        // getRelativeTime() for every visible row).
        val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
        val withDates = alerts.map { alert ->
            val parsed = try { sdf.parse(alert.timestamp) } catch (e: Exception) { Date(0) }
            alert to parsed
        }
        val sortedWithDates = withDates.sortedByDescending { it.second }

        // Cap how many rows we actually build/bind. Cloud alert history can grow
        // unbounded; rendering thousands of them is what caused the lag/crash — there's
        // no practical reason to show more than the most recent alerts at a time.
        val capped = sortedWithDates.take(MAX_RENDERED_ALERTS)
        val sortedAlerts = capped.map { it.first }

        // Summary Cards - Count based on current filtered view and priority
        val urgentCount = sortedAlerts.count { it.type == "Critical" || it.message.contains("EMPTY", true) || it.message.contains("DEPLETED", true) }
        val generalCount = sortedAlerts.count { it.type != "Critical" && !it.message.contains("EMPTY", true) && !it.message.contains("DEPLETED", true) }

        findViewById<TextView>(R.id.criticalCount)?.text = urgentCount.toString()
        findViewById<TextView>(R.id.warningCount)?.text = generalCount.toString()
        findViewById<TextView>(R.id.totalCount)?.text = sortedAlerts.size.toString()

        updateSummaryLabels()

        val displayList: List<DisplayAlert> = if (capped.isEmpty()) {
            listOf(AlertsAdapter.emptyPlaceholder("No $activeFilter alerts available"))
        } else {
            capped.map { (alert, parsedDate) ->
                DisplayAlert(
                    alert = alert,
                    relativeTime = getRelativeTime(alert, parsedDate),
                    stableId = "${alert.timestamp}|${alert.message}"
                )
            }
        }

        // ListAdapter.submitList() diffs the old/new lists on a background thread and
        // only rebinds the rows that actually changed — this is what replaces the old
        // removeAllViews() + re-inflate-everything-from-scratch approach.
        alertsAdapter.submitList(displayList)
    }

    // Live snapshot listener for feed/inventory updates while activity runs
    private fun startLiveInventoryListener() {
        // remove existing to avoid duplicates
        inventoryListener?.remove()
        inventoryListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("feed")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                for (dc in snapshots.documentChanges) {
                    val doc = dc.document
                    val qty = doc.getLong("quantity") ?: 0L
                    val name = doc.getString("name") ?: "Item"
                    if (dc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED || dc.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED) {
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
    }

    // Live snapshot listener for tasks to detect missed tasks immediately
    private fun startLiveTasksListener() {
        tasksListener?.remove()
        tasksListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("tasks")
            .whereEqualTo("status", "Pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                val now = Calendar.getInstance()
                for (doc in snapshots.documents) {
                    val year  = doc.getLong("year")?.toInt()  ?: 0
                    val month = doc.getLong("month")?.toInt() ?: 0
                    val day   = doc.getLong("day")?.toInt()   ?: 0
                    val title = doc.getString("title") ?: "Task"

                    val taskDate = Calendar.getInstance()
                    val hour = doc.getLong("hour")?.toInt() ?: 23
                    val minute = doc.getLong("minute")?.toInt() ?: 59
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
                warningLabel.text = "Alerts"
                totalLabel.text = "All Notifications"
            }
        }
    }

    private fun getRelativeTime(alert: GlobalData.AlertItem, parsedDate: Date): String {
        val now = System.currentTimeMillis()
        val diff = now - parsedDate.time

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} mins ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} hours ago"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)} days ago"
            else -> alert.timestamp.split(" ")[0]
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

    // Show a local system notification immediately when an alert is added
    private fun showLocalNotification(title: String, message: String) {
        val channelId = "alerts_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Farm Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important farm alerts: inventory, schedule, system"
                enableLights(true)
                enableVibration(true)
                setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(this).notify(message.hashCode(), builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
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