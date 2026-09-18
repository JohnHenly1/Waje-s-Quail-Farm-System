package com.example.exp1

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
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

/**
 * InventoryHistoryActivity
 *
 * Displays the structured audit trail written to inventory_history/{autoId}
 * by [FeedInventoryActivity] whenever a stock quantity changes.
 *
 * Each entry shows:
 *   [EditorName] Added/Removed N unit ProductName  quantityBefore -> quantityAfter  date/time
 *
 * Records are ordered newest-first.  Filtering by category and action type
 * (add / deduct) is provided via tab and chip controls, matching the pattern
 * used in [FeedHistoryActivity].
 */
class InventoryHistoryActivity : AppCompatActivity() {

    private lateinit var historyList: LinearLayout
    private val db        = FirebaseFirestore.getInstance()
    private val auditCol  = db.collection("inventory_history")
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy  h:mm a", Locale.getDefault())

    private var activeCategory  = "All"
    private var activeAction    = "All"
    private var activeTimeRange = "All"
    private var historyListener: ListenerRegistration? = null
    private val currentFilteredDocs = mutableListOf<DocumentSnapshot>()

    private lateinit var tabAll: TextView
    private lateinit var tabFeed: TextView
    private lateinit var tabSupplements: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipAdd: TextView
    private lateinit var chipDeduct: TextView
    private lateinit var chipTimeAll: TextView
    private lateinit var chipTimeToday: TextView
    private lateinit var chipTimeWeek: TextView
    private lateinit var chipTimeMonth: TextView

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { writeCsvToUri(it) }
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_inventory_history)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        historyList = findViewById(R.id.historyList)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.exportButton).setOnClickListener { startExportCsv() }

        bindFilters()
        runOneTimeAuditNameMigrationIfNeeded()
        startHistoryListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        historyListener?.remove()
    }
    // ──────────────────────────────────────────────────────────────────────────
    // One-time migration: older inventory_history docs were written with the
    // user's email in editedByName (no editedByEmail field). Backfill the real
    // name from user_access/{email} and move the email to editedByEmail.
    // Guarded by a SharedPreferences flag so this only ever runs once.
    // ──────────────────────────────────────────────────────────────────────────
    private fun runOneTimeAuditNameMigrationIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        if (prefs.getBoolean("audit_name_migration_v1_done", false)) return

        auditCol.get().addOnSuccessListener { snaps ->
            val usersCol = db.collection("user_access")
            val docsNeedingFix = snaps.documents.filter { doc ->
                val name  = doc.getString("editedByName")  ?: ""
                val email = doc.getString("editedByEmail") ?: ""
                email.isBlank() && name.contains("@")
            }

            if (docsNeedingFix.isEmpty()) {
                prefs.edit().putBoolean("audit_name_migration_v1_done", true).apply()
                return@addOnSuccessListener
            }

            var remaining = docsNeedingFix.size
            fun maybeFinish() {
                remaining--
                if (remaining == 0) {
                    prefs.edit().putBoolean("audit_name_migration_v1_done", true).apply()
                }
            }

            docsNeedingFix.forEach { doc ->
                val email = doc.getString("editedByName") ?: ""
                usersCol.document(email).get()
                    .addOnSuccessListener { userDoc ->
                        val realName = userDoc.getString("name") ?: email
                        doc.reference.update(
                            mapOf(
                                "editedByName"  to realName,
                                "editedByEmail" to email
                            )
                        ).addOnCompleteListener { maybeFinish() }
                    }
                    .addOnFailureListener { maybeFinish() }
            }
        }
    }
    // ──────────────────────────────────────────────────────────────────────────
    // Filters
    // ──────────────────────────────────────────────────────────────────────────
    private fun bindFilters() {
        tabAll          = findViewById(R.id.tabAll)
        tabFeed         = findViewById(R.id.tabFeed)
        tabSupplements  = findViewById(R.id.tabSupplements)
        chipAll         = findViewById(R.id.chipAll)
        chipAdd         = findViewById(R.id.chipAdd)
        chipDeduct      = findViewById(R.id.chipDeduct)

        val catTabs = mapOf("All" to tabAll, "Feed" to tabFeed, "Supplements" to tabSupplements)
        catTabs.forEach { (name, tv) ->
            tv.setOnClickListener {
                activeCategory = name
                updateTabStyles(catTabs, activeCategory)
                renderFilteredList()
            }
        }
        updateTabStyles(catTabs, activeCategory)

        val actionChips = mapOf("All" to chipAll, "add" to chipAdd, "deduct" to chipDeduct)
        actionChips.forEach { (name, tv) ->
            tv.setOnClickListener {
                activeAction = name
                updateTabStyles(actionChips, activeAction)
                renderFilteredList()
            }
        }
        updateTabStyles(actionChips, activeAction)

        chipTimeAll    = findViewById(R.id.chipTimeAll)
        chipTimeToday  = findViewById(R.id.chipTimeToday)
        chipTimeWeek   = findViewById(R.id.chipTimeWeek)
        chipTimeMonth  = findViewById(R.id.chipTimeMonth)

        val timeChips = mapOf(
            "All"   to chipTimeAll,
            "Today" to chipTimeToday,
            "Week"  to chipTimeWeek,
            "Month" to chipTimeMonth
        )
        timeChips.forEach { (name, tv) ->
            tv.setOnClickListener {
                activeTimeRange = name
                updateTabStyles(timeChips, activeTimeRange)
                renderFilteredList()
            }
        }
        updateTabStyles(timeChips, activeTimeRange)
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

    // ──────────────────────────────────────────────────────────────────────────
    // Firestore real-time listener
    // ──────────────────────────────────────────────────────────────────────────
    private fun startHistoryListener() {
        historyListener = auditCol
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    Toast.makeText(this, "Error loading history: ${err.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                currentFilteredDocs.clear()
                if (snaps != null) currentFilteredDocs.addAll(snaps.documents)
                renderFilteredList()
            }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Rendering
    // ──────────────────────────────────────────────────────────────────────────
    private fun renderFilteredList() {
        val filtered = currentFilteredDocs.filter { doc ->
            val cat    = doc.getString("category") ?: ""
            val action = doc.getString("action")   ?: ""
            val ts     = doc.getTimestamp("timestamp")?.toDate()
            val catMatch    = activeCategory == "All" || cat == activeCategory
            val actionMatch = activeAction == "All" || action == activeAction
            val timeMatch   = isWithinTimeRange(ts, activeTimeRange)
            catMatch && actionMatch && timeMatch
        }

        historyList.removeAllViews()

        if (filtered.isEmpty()) {
            val empty = TextView(this).apply {
                text    = "No history records found for this filter"
                gravity = android.view.Gravity.CENTER
                setPadding(0, 64, 0, 0)
                setTextColor(ContextCompat.getColor(this@InventoryHistoryActivity, R.color.dark_gray))
            }
            historyList.addView(empty)
            return
        }

        val inflater = LayoutInflater.from(this)
        filtered.forEach { doc ->
            val card = inflater.inflate(R.layout.item_inventory_history, historyList, false)
            bindHistoryCard(card, doc)
            historyList.addView(card)
        }
    }

    private fun bindHistoryCard(card: View, doc: DocumentSnapshot) {
        val productName  = doc.getString("productName")   ?: "Unknown"
        val category     = doc.getString("category")      ?: ""
        val action       = doc.getString("action")        ?: "add"
        val editorName   = doc.getString("editedByName")  ?: "Unknown"
        val editorEmail  = doc.getString("editedByEmail") ?: ""
        val editorRole   = doc.getString("editedByRole")  ?: ""
        val qtyBefore    = doc.getLong("quantityBefore")  ?: 0L
        val qtyAfter     = doc.getLong("quantityAfter")   ?: 0L
        val qtyChanged   = doc.getLong("quantityChanged") ?: (qtyAfter - qtyBefore)
        val ts           = doc.getTimestamp("timestamp")?.toDate() ?: Date()

        val unit = when (category) {
            "Feed"        -> "sack"
            "Supplements" -> "bottle"
            else          -> "unit"
        }
        val absChange  = Math.abs(qtyChanged)
        val unitLabel  = if (absChange == 1L) unit else "${unit}s"
        val actionVerb = if (action == "add") "Added" else "Removed"

        // Main title: "[Jomar] Removed 1 bottle Vitamin B"
        card.findViewById<TextView>(R.id.itemName).text =
            "[$editorName] $actionVerb $absChange $unitLabel $productName"

        // Subtitle: editor role, with email underneath (blank-safe for
        // older audit entries written before editedByEmail existed)
        val roleLine = editorRole.replaceFirstChar { it.uppercase() }
        card.findViewById<TextView>(R.id.itemDescription).text =
            if (editorEmail.isNotBlank()) "$roleLine\n$editorEmail" else roleLine

        // Timestamp in place of invNumber
        card.findViewById<TextView>(R.id.invNumber).text = formatRelativeDate(ts)

        // Quantity before → after
        card.findViewById<TextView>(R.id.quantityText).text = "$qtyBefore → $qtyAfter"
        card.findViewById<TextView>(R.id.quantityLabel).text = "Qty Change"

        // Use unitPriceText and totalValueText for changed amount
        card.findViewById<TextView>(R.id.unitPriceText).text =
            if (qtyChanged >= 0) "+$qtyChanged" else "$qtyChanged"
        card.findViewById<TextView>(R.id.totalValueText).text = category

        // Hide edit and delete buttons — audit entries are read-only
        card.findViewById<View>(R.id.editButton).visibility  = View.GONE
        card.findViewById<View>(R.id.deleteButton).visibility = View.GONE

        // Action badge
        val badge = card.findViewById<TextView>(R.id.statusBadge)
        badge.text = action.uppercase()
        if (action == "add") {
            applyBadgeStyle(badge, "#E8F5E9", "#2E7D32")
        } else {
            applyBadgeStyle(badge, "#FFF3E0", "#E65100")
        }
    }

    // "Today"/"This Week"/"This Month" are calendar-based windows anchored to
    // the current moment, not rolling 24h/7d/30d windows.
    private fun isWithinTimeRange(date: Date?, range: String): Boolean {
        if (range == "All" || date == null) return true
        val now   = Calendar.getInstance()
        val entry = Calendar.getInstance().apply { time = date }
        return when (range) {
            "Today" -> now.get(Calendar.DATE)  == entry.get(Calendar.DATE) &&
                    now.get(Calendar.MONTH) == entry.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR)  == entry.get(Calendar.YEAR)
            "Week"  -> now.get(Calendar.WEEK_OF_YEAR) == entry.get(Calendar.WEEK_OF_YEAR) &&
                    now.get(Calendar.YEAR) == entry.get(Calendar.YEAR)
            "Month" -> now.get(Calendar.MONTH) == entry.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR)  == entry.get(Calendar.YEAR)
            else    -> true
        }
    }

    private fun formatRelativeDate(date: Date): String {
        val now      = Calendar.getInstance()
        val then     = Calendar.getInstance().apply { time = date }
        val timePart = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)

        return when {
            now.get(Calendar.DATE)  == then.get(Calendar.DATE) &&
                    now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR)  == then.get(Calendar.YEAR) -> "Today $timePart"

            now.get(Calendar.DATE) - then.get(Calendar.DATE) == 1 &&
                    now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR)  == then.get(Calendar.YEAR) -> "Yesterday $timePart"

            else -> dateFormat.format(date)
        }
    }

    private fun applyBadgeStyle(badge: TextView, bgHex: String, textHex: String) {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(android.graphics.Color.parseColor(bgHex))
        }
        badge.background = bg
        badge.setTextColor(android.graphics.Color.parseColor(textHex))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CSV export
    // ──────────────────────────────────────────────────────────────────────────
    private fun startExportCsv() {
        if (currentFilteredDocs.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "Inventory_Audit_${System.currentTimeMillis()}.csv")
        }
        createDocumentLauncher.launch(intent)
    }

    private fun writeCsvToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val writer = outputStream.bufferedWriter()
                writer.write("Date,Product,Category,Action,Qty Before,Qty Changed,Qty After,Edited By,Role,Notes\n")
                currentFilteredDocs.forEach { doc ->
                    val ts      = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(doc.getTimestamp("timestamp")?.toDate() ?: Date())
                    val name    = doc.getString("productName")  ?: ""
                    val cat     = doc.getString("category")     ?: ""
                    val action  = doc.getString("action")       ?: ""
                    val before  = doc.getLong("quantityBefore") ?: 0L
                    val changed = doc.getLong("quantityChanged") ?: 0L
                    val after   = doc.getLong("quantityAfter")  ?: 0L
                    val editor  = doc.getString("editedByName") ?: ""
                    val role    = doc.getString("editedByRole") ?: ""
                    val notes   = doc.getString("notes")        ?: ""
                    writer.write("\"$ts\",\"$name\",\"$cat\",\"$action\",$before,$changed,$after,\"$editor\",\"$role\",\"$notes\"\n")
                }
                writer.flush()
                Toast.makeText(this, "Audit log exported successfully", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}