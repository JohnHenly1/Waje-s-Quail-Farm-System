package com.example.exp1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
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

class AlertsActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager

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
            updateAlertsList()
            Toast.makeText(this, "All alerts cleared", Toast.LENGTH_SHORT).show()
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
    }

    private fun showNotificationPrefsDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_notification_preferences, null)
        builder.setView(dialogView)

        val switchAlerts = dialogView.findViewById<SwitchCompat>(R.id.switchAlerts)
        val switchGlobalData = dialogView.findViewById<SwitchCompat>(R.id.switchGlobalData)
        val switchSchedule = dialogView.findViewById<SwitchCompat>(R.id.switchSchedule)

        // Load current preferences
        switchAlerts.isChecked = accountManager.isAlertsEnabled()
        switchGlobalData.isChecked = accountManager.isGlobalDataEnabled()
        switchSchedule.isChecked = accountManager.isScheduleEnabled()

        builder.setTitle("Notification Preferences")
            .setPositiveButton("Save") { _, _ ->
                accountManager.saveNotificationPreferences(
                    switchAlerts.isChecked(),
                    switchGlobalData.isChecked(),
                    switchSchedule.isChecked()
                )
                Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateAlertsList()
    }

    private fun updateAlertsList() {
        val container = findViewById<LinearLayout>(R.id.alertsContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val alerts = GlobalData.getAlerts()

        // Update counters
        findViewById<TextView>(R.id.totalCount).text = alerts.size.toString()
        findViewById<TextView>(R.id.criticalCount).text = alerts.count { it.type == "Critical" }.toString()
        findViewById<TextView>(R.id.warningCount).text = alerts.count { it.type == "System" }.toString()

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
                if (alert.type == "System") {
                    icon.setImageResource(R.drawable.ic_schedule)
                    icon.setColorFilter(getColor(R.color.dark_green))
                }

                // Dim the alert if it's already read
                if (alert.isRead) {
                    itemView.alpha = 0.5f
                } else {
                    itemView.alpha = 1.0f
                }

                // Cascading animation effect
                slideUp.startOffset = (index * 50).toLong()
                itemView.startAnimation(slideUp)

                container.addView(itemView)
            }
        }
    }
}
