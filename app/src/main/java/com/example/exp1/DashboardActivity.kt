package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateTimeRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)
        
        drawerLayout = findViewById(R.id.drawerLayout)

        try {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Setup Menu Button to open Drawer
        findViewById<ImageButton>(R.id.imageButton)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Setup Sign Out
        findViewById<Button>(R.id.signOutButton)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        // Setup Server Time Update
        setupServerTime()

        // Get username from Intent and update welcome message
        val username = intent.getStringExtra("username") ?: "User"
        updateWelcomeMessage(username)

        // WITH THE FOOTER
        // Setup bottom navigation
        NavigationHelper.setupBottomNavigation(this)
        
        // Setup notification button
        NavigationHelper.setupNotificationButton(this)

        // Setup schedule button
        setupScheduleButton()

        // Setup analytics button
        setupAnalyticsButton()
    }

    private fun setupServerTime() {
        val serverTimeText = findViewById<TextView>(R.id.serverTimeText)
        val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault())
        
        updateTimeRunnable = object : Runnable {
            override fun run() {
                val currentTime = sdf.format(Calendar.getInstance().time)
                serverTimeText?.text = currentTime
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateTimeRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTimeRunnable)
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

    //Card
    private fun updateWelcomeMessage(username: String) {
        try {
            // Try to find the welcome message TextView by scanning through the layout
            val welcomeTextView = findViewById<TextView>(R.id.welcome_text)
            if (welcomeTextView != null) {
                welcomeTextView.text = "Hi, $username!"
            } else {
                // Fallback to recursive search if ID not found (though I added it in XML)
                val foundView = findTextViewWithText("Hi, User!")
                foundView?.text = "Hi, $username!"
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
