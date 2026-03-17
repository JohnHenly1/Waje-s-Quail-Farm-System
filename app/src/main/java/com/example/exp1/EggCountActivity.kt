package com.example.exp1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class EggCountActivity : AppCompatActivity() {

    data class CollectionEntry(
        val date: String,
        val gradeA: Int,
        val gradeB: Int,
        val gradeC: Int,
        val total: Int
    )

    private val dailyLog = listOf(
        CollectionEntry("Mar 10, 2026", 420, 180, 50, 650),
        CollectionEntry("Mar 9, 2026", 398, 195, 55, 648),
        CollectionEntry("Mar 8, 2026", 410, 170, 48, 628),
        CollectionEntry("Mar 7, 2026", 385, 188, 62, 635),
        CollectionEntry("Mar 6, 2026", 405, 192, 53, 650)
    )

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

        setupUI()
        NavigationHelper.setupBottomNavigation(this)
    }

    private fun setupUI() {
        // Since you requested to remove values/set to 0 for now
        findViewById<TextView>(R.id.todayTotalValue).text = "0"
        findViewById<TextView>(R.id.gradeAValue).text = "0"
        findViewById<TextView>(R.id.gradeBValue).text = "0"
        findViewById<TextView>(R.id.gradeCValue).text = "0"
        findViewById<TextView>(R.id.gradeAPercent).text = "0%"
        findViewById<TextView>(R.id.gradeBPercent).text = "0%"
        findViewById<TextView>(R.id.gradeCPercent).text = "0%"

        // We can still show the log structure but with empty or 0 data if preferred.
        val container = findViewById<LinearLayout>(R.id.collectionLogList)
        val inflater = LayoutInflater.from(this)

        for ((index, entry) in dailyLog.withIndex()) {
            val itemView = inflater.inflate(R.layout.item_collection_log, container, false)
            
            val dateTxt = itemView.findViewById<TextView>(R.id.logDate)
            val totalTxt = itemView.findViewById<TextView>(R.id.logTotal)
            val gATxt = itemView.findViewById<TextView>(R.id.logGradeA)
            val gBTxt = itemView.findViewById<TextView>(R.id.logGradeB)
            val gCTxt = itemView.findViewById<TextView>(R.id.logGradeC)
            val badge = itemView.findViewById<TextView>(R.id.todayBadge)

            dateTxt.text = entry.date
            totalTxt.text = "0"
            gATxt.text = "0"
            gBTxt.text = "0"
            gCTxt.text = "0"
            
            if (index == 0) badge.visibility = View.VISIBLE

            container.addView(itemView)
        }
    }
}
