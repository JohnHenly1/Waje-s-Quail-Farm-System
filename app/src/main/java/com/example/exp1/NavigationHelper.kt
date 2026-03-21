package com.example.exp1

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object NavigationHelper {

    fun setupBottomNavigation(activity: Activity) {
        val homeButton = activity.findViewById<LinearLayout>(R.id.homeButton)
        val analyticsButton = activity.findViewById<LinearLayout>(R.id.analyticsButton)
        val cameraButton = activity.findViewById<LinearLayout>(R.id.CameraButton)
        val scheduleButton = activity.findViewById<LinearLayout>(R.id.scheduleButton)
        val profileButton = activity.findViewById<LinearLayout>(R.id.profileButton)

        // Try to get username from intent, or fallback to a global constant/pref if needed
        val username = activity.intent.getStringExtra("username")

        // Highlight current activity button
        val activityName = activity.localClassName
        when {
            activityName.contains("DashboardActivity") || activity is DashboardActivity -> highlightButton(homeButton)
            activityName.contains("AnalyticsActivity") || activity is AnalyticsActivity -> highlightButton(analyticsButton)
            activityName.contains("ScheduleActivity") || activity is ScheduleActivity -> highlightButton(scheduleButton)
            activityName.contains("ProfileActivity") || activity is ProfileActivity -> highlightButton(profileButton)
        }

        homeButton?.setOnClickListener {
            if (activity !is DashboardActivity) {
                val intent = Intent(activity, DashboardActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }

        analyticsButton?.setOnClickListener {
            if (activity !is AnalyticsActivity) {
                val intent = Intent(activity, AnalyticsActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }

        cameraButton?.setOnClickListener {
            // Camera functionality or Activity can be added here
        }

        scheduleButton?.setOnClickListener {
            if (activity !is ScheduleActivity) {
                val intent = Intent(activity, ScheduleActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }

        profileButton?.setOnClickListener {
            if (activity !is ProfileActivity) {
                val intent = Intent(activity, ProfileActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }
    }

    fun setupSideMenu(activity: Activity, drawerLayout: DrawerLayout) {
        val navigationView = activity.findViewById<NavigationView>(R.id.sideMenu)
        
        val accountManager = AccountManager(activity)
        var username = activity.intent.getStringExtra("username")
        if (username == null || username.isEmpty()) {
            username = accountManager.getCurrentUsername()
        }
        
        updateDrawerHeader(navigationView, username ?: "User")
        
        navigationView?.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_dashboard -> {
                    if (activity !is DashboardActivity) {
                        val intent = Intent(activity, DashboardActivity::class.java)
                        intent.putExtra("username", username)
                        activity.startActivity(intent)
                    }
                }
                R.id.nav_settings -> {
                    // Navigate to settings if it exists
                }
                R.id.nav_help -> {
                    // Navigate to help
                }
                R.id.nav_logout -> {
                    // Clear the session before logging out
                    accountManager.clearSession()

                    val intent = Intent(activity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    activity.startActivity(intent)
                    activity.finish()
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun updateDrawerHeader(navigationView: NavigationView?, username: String) {
        val headerView = navigationView?.getHeaderView(0)
        val userNameTextView = headerView?.findViewById<TextView>(R.id.userName)
        val userInitialTextView = headerView?.findViewById<TextView>(R.id.userInitial)
        
        userNameTextView?.text = username
        if (username.isNotEmpty()) {
            userInitialTextView?.text = username[0].uppercaseChar().toString()
        }
    }

    fun setupNotificationButton(activity: Activity) {
        val notificationButton = activity.findViewById<View>(R.id.notificationButton)
        val username = activity.intent.getStringExtra("username")
        notificationButton?.setOnClickListener {
            if (activity !is AlertsActivity) {
                val intent = Intent(activity, AlertsActivity::class.java)
                intent.putExtra("username", username)
                activity.startActivity(intent)
            }
        }
    }

    private fun highlightButton(button: LinearLayout?) {
        button?.let {
            it.setBackgroundResource(R.drawable.nav_item_glow)
            it.alpha = 1.0f
            it.scaleX = 1.05f
            it.scaleY = 1.05f
        }
    }
}
