package com.example.exp1

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeedInventoryActivity : AppCompatActivity() {

    private data class InventoryItem(
        val firestoreId: String,
        var name: String,
        var status: String       // "Full" | "Medium" | "Low"
    )

    private val inventory      = mutableListOf<InventoryItem>()
    private val statusOptions  = arrayOf("Full", "Medium", "Low")

    private lateinit var accountManager: AccountManager
    private lateinit var roleManager:    RoleManager
    private var feedListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_feed_inventory)

        accountManager = AccountManager(this)
        roleManager    = RoleManager(accountManager.getCurrentRole())

        val currentUsername = accountManager.getCurrentUsername() ?: "User"

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Back button
        findViewById<View>(R.id.backButton).setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java)
                .putExtra("username", currentUsername)
                .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
        }

        // Add button — visible to owner / backup_owner only
        val addButton = findViewById<View>(R.id.addButton)
        if (roleManager.canEditFarm()) {
            addButton.visibility = View.VISIBLE
            addButton.setOnClickListener { showAddEditDialog(null) }
        } else {
            addButton.visibility = View.GONE
        }

        findViewById<View>(R.id.historyButton).setOnClickListener { showHistoryFromFirestore() }

        NavigationHelper.setupBottomNavigation(this)

        // updates UI whenever anyone changes feed data
        feedListener = FarmRepository.listenToFeed { docs ->
            inventory.clear()
            docs.forEach { doc ->
                inventory.add(InventoryItem(
                    firestoreId = doc["firestoreId"] as? String ?: "",
                    name        = doc["name"]        as? String ?: "",
                    status      = doc["status"]      as? String ?: "Full"
                ))
            }
            updateUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        feedListener?.remove()
    }

    // -- UI update --------------------------------------------------------------------------------

    private fun updateUI() {
        setupInventoryList()
        setupSummary()
    }

    private fun setupSummary() {
        findViewById<TextView>(R.id.totalItemsValue)?.text = inventory.size.toString()
        findViewById<TextView>(R.id.lowStockValue)?.text   = inventory.count { it.status == "Low" }.toString()
    }

    private fun setupInventoryList() {
        val container = findViewById<LinearLayout>(R.id.inventoryList) ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (item in inventory) {
            val itemView = inflater.inflate(R.layout.item_feed_inventory, container, false)

            itemView.findViewById<TextView>(R.id.itemName).text   = item.name
            itemView.findViewById<TextView>(R.id.statusText).text = "Status: ${item.status}"

            val badge = itemView.findViewById<TextView>(R.id.statusBadge)
            badge.text = item.status.uppercase()
            when (item.status) {
                "Full"   -> { badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_green)
                    badge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark)) }
                "Medium" -> { badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_orange)
                    badge.setTextColor(ContextCompat.getColor(this, R.color.orange)) }
                "Low"    -> { badge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.light_orange)
                    badge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)) }
            }

            // Edit button — all roles; staff can only change status, not name
            itemView.findViewById<View>(R.id.editButton).setOnClickListener {
                showAddEditDialog(item)
            }

            // Delete button — owner / backup_owner only
            val deleteBtn = itemView.findViewById<View>(R.id.deleteButton)
            if (roleManager.canDeleteFeedItem()) {
                deleteBtn.visibility = View.VISIBLE
                deleteBtn.setOnClickListener { showDeleteConfirmation(item) }
            } else {
                deleteBtn.visibility = View.GONE
            }

            container.addView(itemView)
        }
    }

    // -- Dialogs ----------------------------------------------------------------------------------

    private fun showAddEditDialog(item: InventoryItem?) {
        val isEdit  = item != null
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (isEdit) "Edit Food Status" else "Add New Food")

        val view        = layoutInflater.inflate(R.layout.dialog_add_food, null)
        val nameInput   = view.findViewById<EditText>(R.id.foodNameInput)
        val statusSpinner = view.findViewById<Spinner>(R.id.statusSpinner)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, statusOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        statusSpinner.adapter = adapter

        if (isEdit) {
            nameInput.setText(item!!.name)
            // Staff cannot change the item name — only the status
            nameInput.isEnabled = roleManager.canEditFarm()
            statusSpinner.setSelection(statusOptions.indexOf(item.status))
        }

        builder.setView(view)
        builder.setPositiveButton(if (isEdit) "Update" else "Add") { _, _ ->
            val name   = nameInput.text.toString().trim()
            val status = statusSpinner.selectedItem.toString()
            if (name.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            if (isEdit) {
                if (roleManager.canEditFarm()) {
                    // Owner / backup_owner → full update
                    FarmRepository.updateFeedItem(item!!.firestoreId, name, status) { err ->
                        if (err != null) Toast.makeText(this, "Update failed: ${err.message}", Toast.LENGTH_SHORT).show()
                        else if (status == "Low") FarmRepository.addAlert("Feed Low: $name is now LOW", "Critical")
                    }
                } else {
                    // Staff → status only
                    FarmRepository.updateFeedStatus(item!!.firestoreId, status) { err ->
                        if (err != null) Toast.makeText(this, "Update failed: ${err.message}", Toast.LENGTH_SHORT).show()
                        else if (status == "Low") FarmRepository.addAlert("Feed Low: ${item.name} is now LOW", "Critical")
                    }
                }
            } else {
                // Adding new item → only owner/backup_owner can add
                FarmRepository.addFeedItem(name, status) { err ->
                    if (err != null) Toast.makeText(this, "Add failed: ${err.message}", Toast.LENGTH_SHORT).show()
                    else if (status == "Low") FarmRepository.addAlert("Feed Low: $name added with LOW status", "Critical")
                }
            }

        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showDeleteConfirmation(item: InventoryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Food")
            .setMessage("Are you sure you want to delete ${item.name}?")
            .setPositiveButton("Delete") { _, _ ->
                FarmRepository.deleteFeedItem(item.firestoreId) { err ->
                    if (err != null) Toast.makeText(this, "Delete failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHistoryFromFirestore() {
        // For a live history, listen to alerts of type "Inventory" or "Critical"
        // We show the last 30 shared alerts as a simple dialog.
        FarmRepository.listenToAlerts { docs ->
            // Show once then detach
        }

        // Simple dialog showing recent alerts from Firestore
        val builder  = AlertDialog.Builder(this)
        builder.setTitle("Feed History (Shared)")
        builder.setMessage("History is now synced in real-time via Firestore.\nCheck the Alerts screen for all inventory changes.")
        if (roleManager.canClearAlerts()) {
            builder.setNeutralButton("Clear All Alerts") { _, _ ->
                FarmRepository.clearAllAlerts { err ->
                    val msg = if (err == null) "Alerts cleared" else "Error: ${err.message}"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setPositiveButton("Close", null)
        builder.show()
    }
}