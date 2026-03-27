package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
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

    private var username: String = "User"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        //  Get username safely
        username = intent.getStringExtra("username") ?: "User"

        //  Initialize drawer
        drawerLayout = findViewById(R.id.drawerLayout)

        //  Safe insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Setup UI
        setupNavigation()
        setupServerTime()
        updateWelcomeMessage()
        setupButtons()
        
        // Apply Animations
        applyEntranceAnimations()
    }

    private fun applyEntranceAnimations() {
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        
        findViewById<View>(R.id.welcomeCard)?.startAnimation(fadeIn)
        
        val aiCard = findViewById<View>(R.id.aiCard)
        slideUp.startOffset = 100
        aiCard?.startAnimation(slideUp)
        
        val shortcutsGrid = findViewById<View>(R.id.shortcutsGrid)
        slideUp.startOffset = 200
        shortcutsGrid?.startAnimation(slideUp)
        
        val statsGrid = findViewById<View>(R.id.statsGrid)
        slideUp.startOffset = 300
        statsGrid?.startAnimation(slideUp)
    }

    private fun showLoading(action: () -> Unit) {
        val loadingLayout = findViewById<View>(R.id.loadingLayout)
        val loadingIcon = findViewById<View>(R.id.loadingIcon)
        
        if (loadingLayout != null && loadingIcon != null) {
            loadingLayout.visibility = View.VISIBLE
            val rotate = AnimationUtils.loadAnimation(this, R.anim.rotate)
            loadingIcon.startAnimation(rotate)
            
            // Simulate network/loading delay
            handler.postDelayed({
                loadingLayout.visibility = View.GONE
                loadingIcon.clearAnimation()
                action()
            }, 800)
        } else {
            action()
        }
    }

    // Navigation Setup
    private fun setupNavigation() {
        try {
            NavigationHelper.setupSideMenu(this, drawerLayout)

            findViewById<android.view.View>(R.id.imageButton)?.setOnClickListener {
                drawerLayout.openDrawer(GravityCompat.START)
            }

            NavigationHelper.setupBottomNavigation(this)
            NavigationHelper.setupNotificationButton(this)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Time updater
    private fun setupServerTime() {
        val serverTimeText = findViewById<TextView?>(R.id.serverTimeText)
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

    // Welcome Text
    private fun updateWelcomeMessage() {
        findViewById<TextView?>(R.id.welcome_text)?.text = "Hi, $username!"
    }

    // Buttons
    private fun setupButtons() {

        // Analytics
        findViewById<LinearLayout?>(R.id.analyticsButton)?.setOnClickListener {
            showLoading {
                val intent = Intent(this, AnalyticsActivity::class.java)
                intent.putExtra("username", username)
                startActivity(intent)
            }
        }

        // Schedule
        findViewById<android.widget.ImageButton?>(R.id.scheduleButton1)?.setOnClickListener {
            showLoading {
                val intent = Intent(this, ScheduleActivity::class.java)
                intent.putExtra("username", username)
                startActivity(intent)
            }
        }

        // Feed Inventory
        findViewById<android.view.View?>(R.id.feedInventoryButton)?.setOnClickListener {
            showLoading {
                val intent = Intent(this, FeedInventoryActivity::class.java)
                intent.putExtra("username", username)
                startActivity(intent)
            }
        }

        // Egg Count
        findViewById<android.view.View?>(R.id.eggCountButton)?.setOnClickListener {
            showLoading {
                val intent = Intent(this, EggCountActivity::class.java)
                intent.putExtra("username", username)
                startActivity(intent)
            }
        }

        // Reports
        findViewById<android.view.View?>(R.id.reportButton)?.setOnClickListener {
            showLoading {
                val intent = Intent(this, AnalyticsActivity::class.java)
                intent.putExtra("username", username)
                startActivity(intent)
            }
        }

        // Tasks
        findViewById<android.view.View?>(R.id.tasksButton)?.setOnClickListener {
            showLoading {
                val intent = Intent(this, ScheduleActivity::class.java)
                intent.putExtra("username", username)
                startActivity(intent)
            }
        }
    }
}
