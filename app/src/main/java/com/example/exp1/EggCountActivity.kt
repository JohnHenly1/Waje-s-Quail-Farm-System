package com.example.exp1

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EggCountActivity : AppCompatActivity() {

    data class CollectionEntry(
        val date: String,
        val gradeA: Int,
        val gradeB: Int,
        val gradeC: Int,
        val total: Int
    )

    private var weekOffset = 0
    private val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var accountManager: AccountManager
    private var userRole: String = "staff"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_egg_count)

        accountManager = AccountManager(this)
        val currentUsername = accountManager.getCurrentUsername()
        userRole = accountManager.getRole(currentUsername ?: "")

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

        val refreshBtn = findViewById<View>(R.id.refreshButton)
        if (isAdmin()) {
            refreshBtn.visibility = View.VISIBLE
            refreshBtn.setOnClickListener {
                showLoading {
                    refreshData()
                }
            }
        } else {
            refreshBtn.visibility = View.GONE
        }

        findViewById<View>(R.id.prevWeekBtn).setOnClickListener {
            weekOffset--
            setupUI()
        }

        findViewById<View>(R.id.nextWeekBtn).setOnClickListener {
            if (weekOffset < 0) {
                weekOffset++
                setupUI()
            }
        }

        setupUI()
        NavigationHelper.setupBottomNavigation(this)
    }

    private fun isAdmin(): Boolean {
        return userRole == "owner" || userRole == "backup_owner"
    }

    fun showLoading(action: () -> Unit) {
        val loadingLayout = findViewById<View>(R.id.loadingLayout)
        val loadingIcon   = findViewById<View>(R.id.loadingIcon)

        if (loadingLayout != null && loadingIcon != null) {
            loadingLayout.visibility = View.VISIBLE
            val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
            loadingIcon.startAnimation(jump)

            handler.postDelayed({
                loadingLayout.visibility = View.GONE
                loadingIcon.clearAnimation()
                action()
            }, 1200)
        } else {
            action()
        }
    }

    private fun refreshData() {
        Toast.makeText(this, "Checking for hardware updates...", Toast.LENGTH_SHORT).show()
        setupUI() 
    }

    private fun setupUI() {
        findViewById<TextView>(R.id.todayTotalValue).text = "0"
        findViewById<TextView>(R.id.gradeAValue).text = "0"
        findViewById<TextView>(R.id.gradeBValue).text = "0"
        findViewById<TextView>(R.id.gradeCValue).text = "0"
        findViewById<TextView>(R.id.gradeAPercent).text = "0%"
        findViewById<TextView>(R.id.gradeBPercent).text = "0%"
        findViewById<TextView>(R.id.gradeCPercent).text = "0%"

        updateWeekRangeText()
        setupCollectionLog()
    }

    private fun updateWeekRangeText() {
        val weekRangeTxt = findViewById<TextView>(R.id.weekRangeTxt)
        if (weekOffset == 0) {
            weekRangeTxt.text = "This Week"
        } else if (weekOffset == -1) {
            weekRangeTxt.text = "Last Week"
        } else {
            weekRangeTxt.text = "${Math.abs(weekOffset)} Weeks Ago"
        }
    }

    private fun setupCollectionLog() {
        val container = findViewById<LinearLayout>(R.id.collectionLogList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.WEEK_OF_YEAR, weekOffset)
        
        for (i in 0 until 7) {
            val dateStr = sdf.format(calendar.time)
            
            val itemView = inflater.inflate(R.layout.item_collection_log, container, false)
            val dateTxt = itemView.findViewById<TextView>(R.id.logDate)
            val badge = itemView.findViewById<TextView>(R.id.todayBadge)

            dateTxt.text = dateStr
            
            val todayStr = sdf.format(Calendar.getInstance().time)
            badge.visibility = if (dateStr == todayStr) View.VISIBLE else View.GONE

            container.addView(itemView)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
    }
}
