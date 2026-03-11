package com.example.exp1

import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView

class AlertsActivity : AppCompatActivity() {

    data class Notification(
        val id: Int,
        val type: String,
        val title: String,
        val description: String,
        val timestamp: String,
        val iconRes: Int
    )

    private val notifications = listOf(
        Notification(1, "critical", "Critical: Feed Stock Low", "Vitamin Mix is at 16% capacity. Immediate restocking required.", "5 minutes ago", R.drawable.ic_alert_triangle),
        Notification(2, "critical", "Low Water Level - Section C", "Water tank in Section C is below 30%. Refill immediately.", "12 minutes ago", R.drawable.ic_alert_triangle),
        Notification(3, "warning", "Egg Production Below Average", "Today's production is 8% below weekly average. Monitor quail health.", "1 hour ago", R.drawable.ic_alert_circle),
        Notification(4, "warning", "Temperature Alert", "Coop temperature reached 32°C. Consider ventilation adjustment.", "2 hours ago", R.drawable.ic_alert_circle),
        Notification(5, "info", "Scheduled Maintenance Reminder", "Monthly equipment check is due in 3 days.", "4 hours ago", R.drawable.ic_info),
        Notification(6, "info", "Feed Order Delivered", "Your order of Layer Feed Premium (200kg) has been delivered.", "6 hours ago", R.drawable.ic_info),
        Notification(7, "info", "Daily Report Ready", "Your production report for March 9, 2026 is now available.", "1 day ago", R.drawable.ic_notifications),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_alerts)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
    }

    private fun setupUI() {
        // Back button
        findViewById<ImageButton>(R.id.backButton)?.setOnClickListener {
            finish()
        }

        // Settings button
        findViewById<ImageButton>(R.id.settingsButton)?.setOnClickListener {
            Toast.makeText(this, "Settings clicked", Toast.LENGTH_SHORT).show()
        }

        // Update counts
        val criticalCount = notifications.count { it.type == "critical" }
        val warningCount = notifications.count { it.type == "warning" }
        val totalCount = notifications.size

        findViewById<TextView>(R.id.criticalCount)?.text = criticalCount.toString()
        findViewById<TextView>(R.id.warningCount)?.text = warningCount.toString()
        findViewById<TextView>(R.id.totalCount)?.text = totalCount.toString()

        // Populate notifications list
        val container = findViewById<LinearLayout>(R.id.notificationsContainer)
        for (notification in notifications) {
            container?.addView(createNotificationCard(notification))
        }

        // Mark all as read button
        findViewById<android.widget.Button>(R.id.markAllReadButton)?.setOnClickListener {
            Toast.makeText(this, "All notifications marked as read", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationCard(notification: Notification): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            cardElevation = 0f
            radius = 16f
            setCardBackgroundColor(getNotificationBackgroundColor(notification.type))
            strokeWidth = 2
            strokeColor = getNotificationBorderColor(notification.type)
        }

        val cardContent = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        // Icon container
        val iconContainer = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                marginEnd = 12
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setBackgroundColor(getNotificationIconBgColor(notification.type))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val icon = android.widget.ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setImageResource(notification.iconRes)
            setColorFilter(getNotificationIconColor(notification.type))
        }
        iconContainer.addView(icon)

        // Content layout
        val content = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            orientation = LinearLayout.VERTICAL
        }

        // Title
        val title = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
            text = notification.title
            textSize = 14f
            setTextColor(resources.getColor(R.color.black, null))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // Description
        val description = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            text = notification.description
            textSize = 13f
            setTextColor(resources.getColor(R.color.dark_gray, null))
        }

        // Bottom row with timestamp and urgent badge
        val bottomRow = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }

        val timestamp = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = notification.timestamp
            textSize = 11f
            setTextColor(resources.getColor(R.color.dark_gray, null))
        }

        if (notification.type == "critical") {
            val urgentBadge = android.widget.Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "URGENT"
                textSize = 9f
                setTextColor(resources.getColor(R.color.white, null))
                setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                setPadding(8, 2, 8, 2)
                isAllCaps = true
            }
            bottomRow.addView(timestamp)
            bottomRow.addView(urgentBadge)
        } else {
            bottomRow.addView(timestamp)
        }

        content.addView(title)
        content.addView(description)
        content.addView(bottomRow)

        cardContent.addView(iconContainer)
        cardContent.addView(content)

        card.addView(cardContent)
        return card
    }

    private fun getNotificationBackgroundColor(type: String): Int {
        return when (type) {
            "critical" -> resources.getColor(android.R.color.holo_red_light, null)
            "warning" -> 0xFFFFEBEE.toInt()
            "info" -> 0xFFF1F5FE.toInt()
            else -> resources.getColor(R.color.white, null)
        }
    }

    private fun getNotificationBorderColor(type: String): Int {
        return when (type) {
            "critical" -> resources.getColor(android.R.color.holo_red_dark, null)
            "warning" -> 0xFFFF6F00.toInt()
            "info" -> resources.getColor(R.color.dark_green, null)
            else -> 0xFFCCCCCC.toInt()
        }
    }

    private fun getNotificationIconBgColor(type: String): Int {
        return when (type) {
            "critical" -> 0xFFFFCDD2.toInt()
            "warning" -> 0xFFFFE0B2.toInt()
            "info" -> 0xFFDCEDC8.toInt()
            else -> 0xFFF5F5F5.toInt()
        }
    }

    private fun getNotificationIconColor(type: String): Int {
        return when (type) {
            "critical" -> resources.getColor(android.R.color.holo_red_dark, null)
            "warning" -> 0xFFFF6F00.toInt()
            "info" -> resources.getColor(R.color.dark_green, null)
            else -> 0xFF666666.toInt()
        }
    }
}

