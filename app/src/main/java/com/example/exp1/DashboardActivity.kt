package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        // Setup side menu (header, navigation, and opening button)
        NavigationHelper.setupSideMenu(this, drawerLayout)
        findViewById<android.view.View>(R.id.imageButton)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Setup Server Time Update
        setupServerTime()

        // Update welcome message
        val username = intent.getStringExtra("username") ?: "User"
        updateWelcomeMessage(username)

        // Setup bottom navigation
        NavigationHelper.setupBottomNavigation(this)
        
        // Setup notification button
        NavigationHelper.setupNotificationButton(this)

        // Setup other buttons
        setupScheduleButton()
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

    private fun setupAnalyticsButton() {
        findViewById<LinearLayout>(R.id.analyticsButton)?.setOnClickListener {
            val intent = Intent(this, AnalyticsActivity::class.java)
            intent.putExtra("username", intent.getStringExtra("username"))
            startActivity(intent)
        }
    }

    private fun setupScheduleButton() {
        findViewById<android.widget.ImageButton>(R.id.scheduleButton1)?.setOnClickListener {
            val intent = Intent(this, ScheduleActivity::class.java)
            intent.putExtra("username", intent.getStringExtra("username"))
            startActivity(intent)
        }
    }

    private fun updateWelcomeMessage(username: String) {
        findViewById<TextView>(R.id.welcome_text)?.text = "Hi, $username!"
    }
}
