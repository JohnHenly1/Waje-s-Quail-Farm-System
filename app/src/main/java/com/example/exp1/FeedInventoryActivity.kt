package com.example.exp1

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeedInventoryActivity : AppCompatActivity() {

    data class InventoryItem(
        var name: String,
        var status: String // "Full", "Medium", "Low"
    )

    data class HistoryItem(
        val action: String,
        val timestamp: String
    )

    private val inventory = mutableListOf<InventoryItem>()
    private val history = mutableListOf<HistoryItem>()
    private val statusOptions = arrayOf("Full", "Medium", "Low")

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
            val intent = Intent(this, DashboardActivity::class.java)
            intent.putExtra("username", getIntent().getStringExtra("username"))
            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.addButton).setOnClickListener {
            showAddEditDialog(null)
        }

        findViewById<View>(R.id.historyButton).setOnClickListener {
            showHistoryDialog()
        }

        updateUI()
        NavigationHelper.setupBottomNavigation(this)
    }

    private fun updateUI() {
        setupInventoryList()
        setupSummary()
    }

    private fun setupSummary() {
        val totalItemsValue = findViewById<TextView>(R.id.totalItemsValue)
        val lowStockValue = findViewById<TextView>(R.id.lowStockValue)

        totalItemsValue.text = inventory.size.toString()
        lowStockValue.text = inventory.count { it.status == "Low" }.toString()
    }

    private fun setupInventoryList() {
        val container = findViewById<LinearLayout>(R.id.inventoryList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for ((index, item) in inventory.withIndex()) {
            val itemView = inflater.inflate(R.layout.item_feed_inventory, container, false)

            val nameTxt = itemView.findViewById<TextView>(R.id.itemName)
            val statusTxt = itemView.findViewById<TextView>(R.id.statusText)
            val badge = itemView.findViewById<TextView>(R.id.statusBadge)
            val editBtn = itemView.findViewById<View>(R.id.editButton)
            val deleteBtn = itemView.findViewById<View>(R.id.deleteButton)

            nameTxt.text = item.name
            statusTxt.text = "Status: ${item.status}"
            badge.text = item.status.uppercase()

            // Apply colors based on status
            when (item.status) {
                "Full" -> {
                    badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_green)
                    badge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                }
                "Medium" -> {
                    badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_orange)
                    badge.setTextColor(ContextCompat.getColor(this, R.color.orange))
                }
                "Low" -> {
                    badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_orange)
                    badge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }

            editBtn.setOnClickListener {
                showAddEditDialog(index)
            }

            deleteBtn.setOnClickListener {
                showDeleteConfirmation(index)
            }

            container.addView(itemView)
        }
    }

    private fun showHistoryDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Inventory History")

        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_history, null)
        val container = view.findViewById<LinearLayout>(R.id.historyContainer)

        if (history.isEmpty()) {
            val emptyTxt = TextView(this)
            emptyTxt.text = "No history available"
            emptyTxt.setPadding(20, 20, 20, 20)
            container.addView(emptyTxt)
        } else {
            for (item in history.reversed()) {
                val itemView = inflater.inflate(R.layout.item_inventory_history, container, false)
                val actionTxt = itemView.findViewById<TextView>(R.id.historyAction)
                val dateTxt = itemView.findViewById<TextView>(R.id.historyDate)
                actionTxt.text = item.action
                dateTxt.text = item.timestamp
                container.addView(itemView)
            }
        }

        builder.setView(view)
        builder.setPositiveButton("Close", null)
        builder.setNeutralButton("Clear History") { _, _ ->
            history.clear()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun addToHistory(action: String) {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
        val currentDate = sdf.format(Date())
        history.add(HistoryItem(action, currentDate))

        // Add to global alerts if it's a critical change (low stock)
        if (action.contains("Low")) {
            GlobalData.addAlert(action, currentDate)
        }
    }

    private fun showDeleteConfirmation(position: Int) {
        val item = inventory[position]
        AlertDialog.Builder(this)
            .setTitle("Delete Food")
            .setMessage("Are you sure you want to delete ${item.name}?")
            .setPositiveButton("Delete") { _, _ ->
                addToHistory("Deleted: ${item.name}")
                inventory.removeAt(position)
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddEditDialog(position: Int?) {
        val builder = AlertDialog.Builder(this)
        val isEdit = position != null
        builder.setTitle(if (isEdit) "Edit Food" else "Add New Food")

        val view = layoutInflater.inflate(R.layout.dialog_add_food, null)
        val nameInput = view.findViewById<EditText>(R.id.foodNameInput)
        val statusSpinner = view.findViewById<Spinner>(R.id.statusSpinner)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, statusOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        statusSpinner.adapter = adapter

        if (isEdit) {
            val item = inventory[position!!]
            nameInput.setText(item.name)
            statusSpinner.setSelection(statusOptions.indexOf(item.status))
        }

        builder.setView(view)
        builder.setPositiveButton(if (isEdit) "Update" else "Add") { _, _ ->
            val name = nameInput.text.toString()
            val status = statusSpinner.selectedItem.toString()

            if (name.isNotEmpty()) {
                if (isEdit) {
                    val oldName = inventory[position!!].name
                    val oldStatus = inventory[position!!].status
                    inventory[position!!].name = name
                    inventory[position!!].status = status
                    addToHistory("Updated: $oldName ($oldStatus -> $status)")
                } else {
                    inventory.add(InventoryItem(name, status))
                    addToHistory("Added: $name ($status)")
                }
                updateUI()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}
