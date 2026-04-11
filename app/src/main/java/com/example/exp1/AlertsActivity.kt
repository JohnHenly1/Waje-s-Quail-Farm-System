package com.example.exp1

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AlertsActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager
    private var farmAlertsListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_alerts)

        accountManager = AccountManager(this)

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
            GlobalData.clearAlerts()
            // Also clear from Firestore if user is admin?
            // For now, just local.
            updateAlertsList()
            Toast.makeText(this, "All local alerts cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.markAllReadBtn).setOnClickListener {
            GlobalData.markAllAsRead()
            updateAlertsList()
            Toast.makeText(this, "All alerts marked as read", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            showNotificationPrefsDialog()
        }

        // Apply initial fade-in to the main container
        findViewById<View>(R.id.alertsContainer).startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))

        updateAlertsList()
        NavigationHelper.setupBottomNavigation(this)
        
        // Check for missed tasks immediately
        checkMissedTasks()
    }

    private fun checkMissedTasks() {
        if (!accountManager.isScheduleEnabled()) return
        
        val now = Calendar.getInstance()
        val timestamp = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()).format(now.time)

        FirebaseFirestore.getInstance().collection("farm_data")
            .document("shared").collection("tasks")
            .whereEqualTo("status", "Pending")
            .get()
            .addOnSuccessListener { snapshots ->
                for (doc in snapshots) {
                    val year = doc.getLong("year")?.toInt() ?: 0
                    val month = doc.getLong("month")?.toInt() ?: 0
                    val day = doc.getLong("day")?.toInt() ?: 0
                    val title = doc.getString("title") ?: "Task"
                    
                    val taskDate = Calendar.getInstance()
                    taskDate.set(year, month, day, 23, 59) // End of that day
                    
                    if (taskDate.before(now)) {
                        GlobalData.addAlert("Missed Task: $title was scheduled for ${day}/${month + 1}/${year}", timestamp, "Schedule")
                    }
                }
                updateAlertsList()
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

        // Load current preferences
        switchAlerts.isChecked = accountManager.isAlertsEnabled()
        switchGlobalData.isChecked = accountManager.isGlobalDataEnabled()
        switchSchedule.isChecked = accountManager.isScheduleEnabled()
        switchEggCount.isChecked = accountManager.isEggCountEnabled()

        // Set time button text
        val hour = accountManager.getEggCountHour()
        val minute = accountManager.getEggCountMinute()
        btnEggCountTime.text = String.format("%02d:%02d", hour, minute)

        // Time picker for egg count
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
                    switchAlerts.isChecked(),
                    switchGlobalData.isChecked(),
                    switchSchedule.isChecked(),
                    switchEggCount.isChecked(),
                    eggHour,
                    eggMinute
                )
                Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show()
                // Schedule or cancel egg count notification
                if (switchEggCount.isChecked()) {
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
        updateAlertsList()
        
        // Listen to FarmRepository alerts (Firestore)
        farmAlertsListener = FarmRepository.listenToAlerts { alerts ->
            val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
            
            for (alert in alerts) {
                val message = alert["message"] as? String ?: continue
                val type = alert["type"] as? String ?: "Inventory"
                
                val firestoreTs = alert["timestamp"]
                val timestampStr = when (firestoreTs) {
                    is com.google.firebase.Timestamp -> sdf.format(firestoreTs.toDate())
                    is String -> firestoreTs
                    else -> "Just now"
                }

                GlobalData.addAlert(message, timestampStr, type)
            }
            runOnUiThread { updateAlertsList() }
        }
    }

    override fun onPause() {
        super.onPause()
        farmAlertsListener?.remove()
        farmAlertsListener = null
    }

    private fun updateAlertsList() {
        val container = findViewById<LinearLayout>(R.id.alertsContainer)
        if (container == null) return
        
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val alerts = GlobalData.getAlerts()

        // Update counters
        findViewById<TextView>(R.id.totalCount)?.text = alerts.size.toString()
        findViewById<TextView>(R.id.criticalCount)?.text = alerts.count { it.type == "Critical" }.toString()
        findViewById<TextView>(R.id.warningCount)?.text = alerts.count { it.type == "System" || it.type == "Inventory" || it.type == "Schedule" }.toString()

        if (alerts.isEmpty()) {
            val emptyTxt = TextView(this)
            emptyTxt.text = "No alerts available"
            emptyTxt.textAlignment = View.TEXT_ALIGNMENT_CENTER
            emptyTxt.setPadding(32, 64, 32, 32)
            container.addView(emptyTxt)
        } else {
            val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
            for ((index, alert) in alerts.withIndex()) {
                val itemView = inflater.inflate(R.layout.item_inventory_history, container, false)
                val actionTxt = itemView.findViewById<TextView>(R.id.historyAction)
                val dateTxt = itemView.findViewById<TextView>(R.id.historyDate)
                val icon = itemView.findViewById<ImageView>(R.id.historyIcon)

                actionTxt.text = alert.message
                dateTxt.text = alert.timestamp

                // Customizing based on type
                when (alert.type) {
                    "System" -> {
                        icon.setImageResource(R.drawable.lc_egg)
                        icon.setBackgroundResource(R.drawable.rounded_green_bg)
                        icon.backgroundTintList = getColorStateList(R.color.light_green)
                        icon.setColorFilter(getColor(R.color.dark_green))
                    }
                    "Inventory" -> {
                        icon.setImageResource(R.drawable.ic_shopping_bag)
                        icon.setBackgroundResource(R.drawable.rounded_green_bg)
                        icon.backgroundTintList = getColorStateList(R.color.light_orange)
                        icon.setColorFilter(getColor(R.color.orange))
                    }
                    "Schedule" -> {
                        icon.setImageResource(R.drawable.ic_calendar)
                        icon.setBackgroundResource(R.drawable.rounded_green_bg)
                        icon.backgroundTintList = getColorStateList(R.color.light_blue)
                        icon.setColorFilter(getColor(R.color.dark_blue))
                    }
                    "Critical" -> {
                        icon.setImageResource(R.drawable.ic_alert_circle)
                        icon.setBackgroundResource(R.drawable.rounded_green_bg)
                        icon.backgroundTintList = getColorStateList(R.color.light_red)
                        icon.setColorFilter(getColor(R.color.red))
                    }
                    else -> {
                        icon.setImageResource(R.drawable.ic_info)
                        icon.setBackgroundResource(R.drawable.rounded_green_bg)
                        icon.backgroundTintList = getColorStateList(R.color.light_gray)
                        icon.setColorFilter(getColor(R.color.dark_gray))
                    }
                }

                if (alert.isRead) {
                    itemView.alpha = 0.6f
                } else {
                    itemView.alpha = 1.0f
                }

                slideUp.startOffset = (index * 50).toLong()
                itemView.startAnimation(slideUp)

                container.addView(itemView)
            }
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
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
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
                val channel = android.app.NotificationChannel(
                    "egg_count_channel",
                    "Egg Count Reminders",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Daily egg count reminders"
                    enableLights(true)
                    enableVibration(true)
                }
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }

            val accountManager = AccountManager(context)
            if (!accountManager.isAlertsEnabled() || !accountManager.isEggCountEnabled()) return

            val database = FirebaseDatabase.getInstance()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            database.getReference("egg_collections").child(today).get().addOnSuccessListener { snapshot ->
                val total = (snapshot.child("total").value as? Long)?.toInt() ?: 0
                val message = "Today's egg count summary: $total eggs collected."

                val timestamp = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()).format(Date())
                GlobalData.addAlert(message, timestamp, "System")

                val notificationIntent = Intent(context, AlertsActivity::class.java)
                notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val builder = androidx.core.app.NotificationCompat.Builder(context, "egg_count_channel")
                    .setSmallIcon(R.drawable.ic_notifications)
                    .setContentTitle("Daily Egg Count Reminder")
                    .setContentText(message)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)

                try {
                    androidx.core.app.NotificationManagerCompat.from(context).notify(2002, builder.build())
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
                
                // Reschedule for tomorrow
                val hour = accountManager.getEggCountHour()
                val minute = accountManager.getEggCountMinute()
                rescheduleNext(context, hour, minute)
            }
        }
        
        private fun rescheduleNext(context: Context, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, EggCountNotificationReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            
            val calendar = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
            
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    }
}