package com.example.exp1

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class FeedHistoryActivity : AppCompatActivity() {

    private lateinit var historyList: LinearLayout
    private val db = FirebaseFirestore.getInstance()
    private val historyCol = db.collection("farm_data").document("shared").collection("feed_history")
    private val currency = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    private var activeCategory = "All"
    private var activeAction = "All Actions"
    private var currentFilteredDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var tabAll: TextView
    private lateinit var tabFeed: TextView
    private lateinit var tabSupplements: TextView

    private lateinit var chipAllActions: TextView
    private lateinit var chipAdded: TextView
    private lateinit var chipRestock: TextView
    private lateinit var chipDeleted: TextView

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                writeCsvToUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_feed_history)

        // Handle edge-to-edge window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        historyList = findViewById(R.id.historyList)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        
        findViewById<View>(R.id.clearHistoryButton).setOnClickListener { showClearConfirmation() }
        findViewById<View>(R.id.exportButton).setOnClickListener { startExportCsv() }

        bindFilters()
        loadHistory()
    }

    private fun bindFilters() {
        tabAll = findViewById(R.id.tabAll)
        tabFeed = findViewById(R.id.tabFeed)
        tabSupplements = findViewById(R.id.tabSupplements)

        chipAllActions = findViewById(R.id.chipAllActions)
        chipAdded = findViewById(R.id.chipAdded)
        chipRestock = findViewById(R.id.chipRestock)
        chipDeleted = findViewById(R.id.chipDeleted)

        val catTabs = mapOf("All" to tabAll, "Feed" to tabFeed, "Supplements" to tabSupplements)
        catTabs.forEach { (name, tv) ->
            tv.setOnClickListener {
                activeCategory = name
                updateTabStyles(catTabs, activeCategory)
                loadHistory()
            }
        }

        val actionChips = mapOf(
            "All Actions" to chipAllActions,
            "ADDED" to chipAdded,
            "RESTOCK" to chipRestock,
            "DELETED" to chipDeleted
        )
        actionChips.forEach { (name, tv) ->
            tv.setOnClickListener {
                activeAction = name
                updateTabStyles(actionChips, activeAction)
                loadHistory()
            }
        }
    }

    private fun updateTabStyles(map: Map<String, TextView>, active: String) {
        map.forEach { (name, tv) ->
            if (name == active) {
                tv.setBackgroundResource(R.drawable.rounded_dark_green)
                tv.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setBackgroundResource(R.drawable.rounded_gray_bg)
                tv.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun loadHistory() {
        historyCol.orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    Toast.makeText(this, "Error loading history: ${err.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                
                historyList.removeAllViews()
                if (snaps == null || snaps.isEmpty) {
                    currentFilteredDocs.clear()
                    showEmptyState()
                    return@addSnapshotListener
                }

                val filtered = snaps.documents.filter { doc ->
                    val type = doc.getString("type") ?: ""
                    val actionMatches = activeAction == "All Actions" || type == activeAction
                    val cat = doc.getString("category") ?: "All"
                    val catMatches = activeCategory == "All" || cat == activeCategory
                    actionMatches && catMatches
                }

                currentFilteredDocs.clear()
                currentFilteredDocs.addAll(filtered)

                if (filtered.isEmpty()) {
                    showEmptyState()
                    return@addSnapshotListener
                }

                filtered.forEach { doc ->
                    val type = doc.getString("type") ?: "UPDATE"
                    val name = doc.getString("name") ?: "Unknown"
                    val qty = doc.getLong("quantity") ?: 0L
                    val price = doc.getDouble("unitPrice") ?: 0.0
                    val ts = doc.getTimestamp("timestamp")?.toDate() ?: Date()
                    
                    val card = LayoutInflater.from(this).inflate(R.layout.item_feed_inventory, historyList, false)
                    
                    card.findViewById<TextView>(R.id.itemName).text = name
                    card.findViewById<TextView>(R.id.itemDescription).text = "Action: $type"
                    card.findViewById<TextView>(R.id.invNumber).text = dateFormat.format(ts)
                    card.findViewById<TextView>(R.id.quantityText).text = qty.toString()
                    card.findViewById<TextView>(R.id.unitPriceText).text = currency.format(price)
                    card.findViewById<TextView>(R.id.totalValueText).text = currency.format(qty * price)
                    
                    card.findViewById<View>(R.id.editButton).visibility = View.GONE
                    card.findViewById<View>(R.id.deleteButton).visibility = View.GONE
                    
                    val badge = card.findViewById<TextView>(R.id.statusBadge)
                    badge.text = type
                    when(type) {
                        "DELETED" -> applyBadgeStyle(badge, "#FFEBEE", "#C62828")
                        "ADDED"   -> applyBadgeStyle(badge, "#E8F5E9", "#2E7D32")
                        "RESTOCK" -> applyBadgeStyle(badge, "#E3F2FD", "#1565C0")
                        else      -> applyBadgeStyle(badge, "#F5F5F5", "#616161")
                    }
                    
                    historyList.addView(card)
                }
            }
    }

    private fun showEmptyState() {
        val empty = TextView(this).apply {
            text = "No history records found for this filter"
            gravity = android.view.Gravity.CENTER
            setPadding(0, 50, 0, 0)
        }
        historyList.addView(empty)
    }

    private fun applyBadgeStyle(badge: TextView, bgHex: String, textHex: String) {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(android.graphics.Color.parseColor(bgHex))
        }
        badge.background = bg
        badge.setTextColor(android.graphics.Color.parseColor(textHex))
    }

    private fun showClearConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to delete all history records? This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllHistoryDocs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllHistoryDocs() {
        historyCol.get().addOnSuccessListener { snaps ->
            val batch = db.batch()
            snaps.documents.forEach { batch.delete(it.reference) }
            batch.commit().addOnSuccessListener {
                Toast.makeText(this, "History cleared successfully", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener {
                Toast.makeText(this, "Failed to clear history: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startExportCsv() {
        if (currentFilteredDocs.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "Inventory_History_${System.currentTimeMillis()}.csv")
        }
        createDocumentLauncher.launch(intent)
    }

    private fun writeCsvToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter()
                // Header
                writer.write("Date,Item Name,Action,Category,Quantity,Unit Price,Total Value\n")
                
                currentFilteredDocs.forEach { doc ->
                    val name = doc.getString("name") ?: "N/A"
                    val type = doc.getString("type") ?: "N/A"
                    val cat = doc.getString("category") ?: "N/A"
                    val qty = doc.getLong("quantity") ?: 0L
                    val price = doc.getDouble("unitPrice") ?: 0.0
                    val ts = doc.getTimestamp("timestamp")?.toDate() ?: Date()
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(ts)
                    
                    writer.write("\"$dateStr\",\"$name\",\"$type\",\"$cat\",$qty,$price,${qty * price}\n")
                }
                writer.flush()
                Toast.makeText(this, "History exported successfully", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
