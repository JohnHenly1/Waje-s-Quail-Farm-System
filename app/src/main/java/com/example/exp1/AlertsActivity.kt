package com.example.exp1

import android.os.Bundle
import android.widget.ImageButton
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

        // Update counts (set to 0 since no data yet)
        val criticalCount = 0
        val warningCount = 0
        val totalCount = 0

        findViewById<TextView>(R.id.criticalCount)?.text = criticalCount.toString()
        findViewById<TextView>(R.id.warningCount)?.text = warningCount.toString()
        findViewById<TextView>(R.id.totalCount)?.text = totalCount.toString()

        // Mark all as read button
        findViewById<android.widget.Button>(R.id.markAllReadButton)?.setOnClickListener {
            Toast.makeText(this, "All notifications marked as read", Toast.LENGTH_SHORT).show()
        }
    }
}
