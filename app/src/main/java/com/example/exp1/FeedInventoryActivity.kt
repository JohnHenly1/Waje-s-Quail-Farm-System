package com.example.exp1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class FeedInventoryActivity : AppCompatActivity() {

    data class InventoryItem(
        val name: String,
        val stock: Int,
        val capacity: Int,
        val unit: String,
        val status: String
    )

    private val inventory = listOf(
        InventoryItem("Layer Feed Premium", 450, 500, "kg", "good"),
        InventoryItem("Corn Grains", 180, 300, "kg", "medium"),
        InventoryItem("Protein Supplement", 25, 100, "kg", "low"),
        InventoryItem("Calcium Grit", 65, 150, "kg", "medium"),
        InventoryItem("Vitamin Mix", 8, 50, "kg", "critical"),
        InventoryItem("Oyster Shell", 120, 200, "kg", "good")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_feed_inventory)

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

        setupInventoryList()
        setupSummary()
        NavigationHelper.setupBottomNavigation(this)
    }

    private fun setupSummary() {
        val totalItemsValue = findViewById<TextView>(R.id.totalItemsValue)
        val lowStockValue = findViewById<TextView>(R.id.lowStockValue)

        totalItemsValue.text = inventory.size.toString()
        lowStockValue.text = inventory.count { it.status == "low" || it.status == "critical" }.toString()
    }

    private fun setupInventoryList() {
        val container = findViewById<LinearLayout>(R.id.inventoryList)
        val inflater = LayoutInflater.from(this)

        for (item in inventory) {
            val itemView = inflater.inflate(R.layout.item_feed_inventory, container, false)
            
            val nameTxt = itemView.findViewById<TextView>(R.id.itemName)
            val stockTxt = itemView.findViewById<TextView>(R.id.currentStock)
            val capTxt = itemView.findViewById<TextView>(R.id.capacityText)
            val badge = itemView.findViewById<TextView>(R.id.statusBadge)
            val progress = itemView.findViewById<ProgressBar>(R.id.stockProgressBar)

            nameTxt.text = item.name
            stockTxt.text = item.stock.toString()
            capTxt.text = "/ ${item.capacity} ${item.unit}"
            
            val percentage = (item.stock.toFloat() / item.capacity.toFloat() * 100).toInt()
            badge.text = "$percentage%"
            progress.progress = percentage

            // Apply colors based on status
            when (item.status) {
                "good" -> {
                    badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_green)
                    badge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    progress.progressDrawable = ContextCompat.getDrawable(this, R.drawable.bg_green_gradient)
                }
                "medium" -> {
                    badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_orange)
                    badge.setTextColor(ContextCompat.getColor(this, R.color.orange))
                    progress.progressDrawable = ContextCompat.getDrawable(this, R.drawable.bg_yellow_gradient)
                }
                "low", "critical" -> {
                    badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_orange)
                    badge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    progress.progressDrawable = ContextCompat.getDrawable(this, R.drawable.bg_orange_gradient)
                }
            }

            container.addView(itemView)
        }
    }
}
