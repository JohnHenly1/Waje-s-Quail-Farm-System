package com.example.exp1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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

    // Current offset in weeks (0 = this week, -1 = last week, etc.)
    private var weekOffset = 0
    private val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_egg_count)

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

        findViewById<View>(R.id.refreshButton).setOnClickListener {
            refreshData()
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

    private fun refreshData() {
        Toast.makeText(this, "Checking for hardware updates...", Toast.LENGTH_SHORT).show()
        // Here you would eventually trigger the YOLO algorithm / Database sync
        setupUI() 
    }

    private fun setupUI() {
        // Reset Today's Values to 0
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

        // Generate 7 days for the current week offset
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.WEEK_OF_YEAR, weekOffset)
        
        // Start from the most recent day of that week (e.g., Today if weekOffset is 0)
        for (i in 0 until 7) {
            val dateStr = sdf.format(calendar.time)
            
            val itemView = inflater.inflate(R.layout.item_collection_log, container, false)
            val dateTxt = itemView.findViewById<TextView>(R.id.logDate)
            val totalTxt = itemView.findViewById<TextView>(R.id.logTotal)
            val gATxt = itemView.findViewById<TextView>(R.id.logGradeA)
            val gBTxt = itemView.findViewById<TextView>(R.id.logGradeB)
            val gCTxt = itemView.findViewById<TextView>(R.id.logGradeC)
            val badge = itemView.findViewById<TextView>(R.id.todayBadge)

            dateTxt.text = dateStr
            totalTxt.text = "0"
            gATxt.text = "0"
            gBTxt.text = "0"
            gCTxt.text = "0"
            
            // Show "TODAY" badge only for the actual current date
            val todayStr = sdf.format(Calendar.getInstance().time)
            badge.visibility = if (dateStr == todayStr) View.VISIBLE else View.GONE

            container.addView(itemView)
            calendar.add(Calendar.DAY_OF_YEAR, -1) // Move backwards
        }
    }
}
