package com.example.exp1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class AlertsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_alerts)

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

        updateAlertsList()
        NavigationHelper.setupBottomNavigation(this)
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
            for (alert in alerts) {
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

                container.addView(itemView)
            }
        }
    }
}
