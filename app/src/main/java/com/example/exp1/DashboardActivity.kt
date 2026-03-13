package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class DashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)
        
        try {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Get username from Intent and update welcome message
        val username = intent.getStringExtra("username") ?: "User"
        updateWelcomeMessage(username)

        // WITH THE FOOTER
        // Setup notification button
        setupNotificationButton()

        // Setup schedule button
        setupScheduleButton()

        // Setup analytics button
        setupAnalyticsButton()
    }

    // Setup analytics button navigation
    private fun setupAnalyticsButton() {
        try {
            val analyticsButton = findViewById<LinearLayout>(R.id.analyticsButton)
            analyticsButton?.setOnClickListener {
                val intent = Intent(this, AnalyticsActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Footer elements setupScheduleButton, setupNotificationButton
    // This connects to activity_schedule
    private fun setupScheduleButton() {
        try {
            val scheduleButton = findViewById<ImageButton>(R.id.scheduleButton1)
            scheduleButton?.setOnClickListener {
                val intent = Intent(this, ScheduleActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    // This connects to activity_alerts
    private fun setupNotificationButton() {
        try {
            val notificationButton = findViewById<ImageButton>(R.id.notificationButton)
            notificationButton?.setOnClickListener {
                val intent = Intent(this, AlertsActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //Card
    private fun updateWelcomeMessage(username: String) {
        try {
            // Try to find the welcome message TextView by scanning through the layout
            val welcomeTextView = findTextViewWithText("Hi, User!")
            if (welcomeTextView != null) {
                welcomeTextView.text = "Hi, $username!"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findTextViewWithText(text: String): TextView? {
        try {
            val rootView = findViewById<android.view.View>(R.id.main)
            return findTextViewRecursive(rootView, text)
        } catch (e: Exception) {
            return null
        }
    }

    private fun findTextViewRecursive(view: android.view.View, targetText: String): TextView? {
        if (view is TextView && view.text.toString().contains(targetText)) {
            return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findTextViewRecursive(view.getChildAt(i), targetText)
                if (result != null) return result
            }
        }
        return null
    }
}
