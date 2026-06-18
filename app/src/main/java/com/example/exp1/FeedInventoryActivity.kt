package com.example.exp1

import android.app.AlertDialog
import android.content.DialogInterface
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.NumberFormat
import java.util.Locale

class FeedInventoryActivity : AppCompatActivity() {

    // ──────────────────────────────────────────────────────────────────────────
    // Data model  (mirrors Firestore fields)
    // farm_data → shared → feed → {docId}
    //   name        : String
    //   invNumber   : String   e.g. "#INV0001"
    //   description : String
    //   category    : String   "Feed" | "Supplements"
    //   location    : String   e.g. "Shop 1"
    //   quantity    : Long
    //   initialQuantity : Long (Baseline for auto-status)
    //   unitPrice   : Double
    //   status      : String   "In Stock" | "Medium" | "Low Stock"
    //   updatedAt   : Timestamp
    // ──────────────────────────────────────────────────────────────────────────
    private data class FeedItem(
        val firestoreId: String,
        val name: String,
        val invNumber: String,
        val description: String,
        val category: String,
        val location: String,
        val quantity: Long,
        val initialQuantity: Long,
        val unitPrice: Double,
        val status: String
    ) {
        val totalValue: Double get() = quantity * unitPrice
    }

    private val allItems    = mutableListOf<FeedItem>()
    private var activeTab   = "All Items"
    private var searchQuery = ""

    private val statusOptions = arrayOf("In Stock", "Medium", "Low Stock")
    private val categoryOptions = arrayOf(
        "Feed", "Supplements"
    )

    private lateinit var accountManager: AccountManager
    private lateinit var roleManager: RoleManager
    private var feedListener: ListenerRegistration? = null

    private val db            = FirebaseFirestore.getInstance()
    private val auth          = FirebaseAuth.getInstance()
    private val feedCol       get() = db.collection("farm_data").document("shared").collection("feed")
    private val historyCol    get() = db.collection("farm_data").document("shared").collection("feed_history")
    // inventory_history is the new structured audit trail (Feature 2)
    private val auditCol      get() = db.collection("inventory_history")
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    // View references
    private lateinit var totalInventoryTv: TextView
    private lateinit var totalItemsTv: TextView
    private lateinit var lowStockTv: TextView
    private lateinit var locationsTv: TextView
    private lateinit var itemCountTv: TextView
    private lateinit var inventoryList: LinearLayout

    // Tab views
    private lateinit var tabAllItems: TextView
    private lateinit var tabFeed: TextView
    private lateinit var tabSupplements: TextView

    // ──────────────────────────────────────────────────────────────────────────
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

        bindViews()
        setupToolbar(currentUsername)
        setupFilterTabs()
        NavigationHelper.setupBottomNavigation(this)
        startFirestoreListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        feedListener?.remove()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // View binding
    // ──────────────────────────────────────────────────────────────────────────
    private fun bindViews() {
        totalInventoryTv = findViewById(R.id.totalInventoryValue)
        totalItemsTv     = findViewById(R.id.totalItemsValue)
        lowStockTv       = findViewById(R.id.lowStockValue)
        locationsTv      = findViewById(R.id.locationsValue)
        itemCountTv      = findViewById(R.id.itemCountLabel)
        inventoryList    = findViewById(R.id.inventoryList)

        tabAllItems      = findViewById(R.id.tabAllItems)
        tabFeed          = findViewById(R.id.tabFeed)
        tabSupplements   = findViewById(R.id.tabSupplements)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Toolbar
    // ──────────────────────────────────────────────────────────────────────────
    private fun setupToolbar(currentUsername: String) {
        findViewById<View>(R.id.backButton).setOnClickListener {
            startActivity(
                Intent(this, DashboardActivity::class.java)
                    .putExtra("username", currentUsername)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            finish()
        }

        findViewById<View>(R.id.searchButton).setOnClickListener { showSearchDialog() }

        // History button — opens the inventory audit trail screen
        findViewById<View>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, InventoryHistoryActivity::class.java))
        }

        val addBtn = findViewById<View>(R.id.addButton)
        if (roleManager.canEditFarm()) {
            addBtn.visibility = View.VISIBLE
            addBtn.setOnClickListener { showAddEditDialog(null) }
        } else {
            addBtn.visibility = View.GONE
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Filter tabs
    // ──────────────────────────────────────────────────────────────────────────
    private val tabMap by lazy {
        mapOf(
            "All Items"   to tabAllItems,
            "Feed"        to tabFeed,
            "Supplements" to tabSupplements
        )
    }

    private fun setupFilterTabs() {
        tabMap.forEach { (label, tv) ->
            tv.setOnClickListener { selectTab(label) }
        }
        highlightTab("All Items")
    }

    private fun selectTab(tab: String) {
        activeTab = tab
        highlightTab(tab)
        renderList()
    }

    private fun highlightTab(selected: String) {
        tabMap.forEach { (label, tv) ->
            if (label == selected) {
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
    // Firestore listener  —  farm_data/shared/feed
    // ──────────────────────────────────────────────────────────────────────────
    private fun startFirestoreListener() {
        feedListener = feedCol.addSnapshotListener { snaps, err ->
            if (err != null) {
                Toast.makeText(this, "Error loading feed: ${err.message}", Toast.LENGTH_SHORT).show()
                return@addSnapshotListener
            }
            allItems.clear()
            snaps?.documents?.forEach { doc ->
                val qty = doc.getLong("quantity") ?: 0L
                val initQty = doc.getLong("initialQuantity") ?: qty
                allItems.add(
                    FeedItem(
                        firestoreId = doc.id,
                        name        = doc.getString("name")        ?: "Unnamed",
                        invNumber   = doc.getString("invNumber")   ?: "#INV----",
                        description = doc.getString("description") ?: "",
                        category    = doc.getString("category")    ?: "Feed",
                        location    = doc.getString("location")    ?: "Location Info",
                        quantity    = qty,
                        initialQuantity = initQty,
                        unitPrice   = doc.getDouble("unitPrice")   ?: 0.0,
                        status      = doc.getString("status")      ?: "In Stock"
                    )
                )
            }
            updateSummaryStats()
            renderList()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Summary Stats
    // ──────────────────────────────────────────────────────────────────────────
    private fun updateSummaryStats() {
        val filteredForStats = allItems.filter { item ->
            activeTab == "All Items" || item.category == activeTab
        }

        val totalValue  = filteredForStats.sumOf { it.totalValue }
        val lowCount    = filteredForStats.count { it.status == "Low Stock" }
        val locationSet = filteredForStats.map { it.location }.toSet()

        totalInventoryTv.text = currency.format(totalValue)
        totalItemsTv.text     = filteredForStats.size.toString()
        lowStockTv.text       = lowCount.toString()
        locationsTv.text      = locationSet.size.toString()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // List rendering
    // ──────────────────────────────────────────────────────────────────────────
    private fun renderList() {
        val filtered = allItems.filter { item ->
            val matchesTab = activeTab == "All Items" || item.category == activeTab
            val matchesSearch = searchQuery.isEmpty() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.invNumber.contains(searchQuery, ignoreCase = true) ||
                    item.description.contains(searchQuery, ignoreCase = true) ||
                    item.category.contains(searchQuery, ignoreCase = true) ||
                    item.location.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }

        itemCountTv.text = "${filtered.size} items found"
        inventoryList.removeAllViews()

        if (filtered.isEmpty()) {
            val empty = TextView(this).apply {
                text    = "No feed items found"
                gravity = android.view.Gravity.CENTER
                setPadding(0, 48, 0, 48)
                setTextColor(ContextCompat.getColor(this@FeedInventoryActivity, R.color.dark_gray))
            }
            inventoryList.addView(empty)
        } else {
            val inflater = LayoutInflater.from(this)
            filtered.forEach { item ->
                val card = inflater.inflate(R.layout.item_feed_inventory, inventoryList, false)
                bindItemCard(card, item)
                inventoryList.addView(card)
            }
        }

        updateSummaryStats()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Card binding
    // ──────────────────────────────────────────────────────────────────────────
    private fun bindItemCard(card: View, item: FeedItem) {
        card.findViewById<TextView>(R.id.itemName).text        = item.name
        card.findViewById<TextView>(R.id.invNumber).text       = item.invNumber
        card.findViewById<TextView>(R.id.itemDescription).text = item.description
        card.findViewById<TextView>(R.id.categoryTag).text     = item.category
        card.findViewById<TextView>(R.id.locationTag).text     = item.location
        card.findViewById<TextView>(R.id.quantityText).text    = item.quantity.toString()
        card.findViewById<TextView>(R.id.unitPriceText).text   = currency.format(item.unitPrice)
        card.findViewById<TextView>(R.id.totalValueText).text  = currency.format(item.totalValue)

        val qtyLabel = card.findViewById<TextView>(R.id.quantityLabel)
        qtyLabel.text = when (item.category) {
            "Feed" -> "Quantity (Sack)"
            "Supplements" -> "Quantity (Bottle)"
            else -> "Quantity"
        }

        val badge = card.findViewById<TextView>(R.id.statusBadge)
        badge.text = item.status
        when (item.status) {
            "In Stock"  -> applyBadgeStyle(badge, "#E8F5E9", "#2E7D32")
            "Medium"    -> applyBadgeStyle(badge, "#FFF3E0", "#F57C00")
            "Low Stock" -> applyBadgeStyle(badge, "#FFEBEE", "#C62828")
            else        -> applyBadgeStyle(badge, "#E8F5E9", "#2E7D32")
        }

        // Card tap — staff can update quantity; owner opens full edit dialog
        card.setOnClickListener {
            if (item.quantity <= 0L) {
                showZeroQuantityPopup(item)
            } else if (roleManager.canEditFarm()) {
                showAddEditDialog(item)
            } else if (roleManager.canUpdateInventoryQuantity()) {
                showQuantityUpdateDialog(item)
            }
        }

        val editBtn = card.findViewById<View>(R.id.editButton)
        if (roleManager.canEditFarm()) {
            editBtn.visibility = View.VISIBLE
            editBtn.setOnClickListener { showAddEditDialog(item) }
        } else if (roleManager.canUpdateInventoryQuantity()) {
            // Staff see the edit button but it opens the quantity-only dialog
            editBtn.visibility = View.VISIBLE
            editBtn.setOnClickListener { showQuantityUpdateDialog(item) }
        } else {
            editBtn.visibility = View.GONE
        }

        val deleteBtn = card.findViewById<View>(R.id.deleteButton)
        if (roleManager.canDeleteFeedItem()) {
            deleteBtn.visibility = View.VISIBLE
            deleteBtn.setOnClickListener { showDeleteConfirmation(item) }
        } else {
            deleteBtn.visibility = View.GONE
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
    // Staff-only: quantity update dialog
    // Allows staff to change ONLY the stock quantity.  All other fields are
    // read-only and displayed for context.  The write is performed via a
    // Firestore transaction so the stock update and audit log are atomic.
    // ──────────────────────────────────────────────────────────────────────────
    private fun showQuantityUpdateDialog(item: FeedItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_food, null)

        val nameInput     = dialogView.findViewById<EditText>(R.id.foodNameInput)
        val descInput     = dialogView.findViewById<EditText>(R.id.foodDescInput)
        val invInput      = dialogView.findViewById<EditText>(R.id.foodInvInput)
        val locationInput = dialogView.findViewById<EditText>(R.id.foodLocationInput)
        val qtyInput      = dialogView.findViewById<EditText>(R.id.foodQtyInput)
        val qtyLabel      = dialogView.findViewById<TextView>(R.id.foodQtyLabel)
        val priceInput    = dialogView.findViewById<EditText>(R.id.foodPriceInput)
        val catSpinner    = dialogView.findViewById<Spinner>(R.id.categorySpinner)
        val statusSpinner = dialogView.findViewById<Spinner>(R.id.statusSpinner)
        val existSpinner  = dialogView.findViewById<Spinner>(R.id.existingItemsSpinner)

        // Hide fields and selectors that staff must not interact with
        statusSpinner.visibility = View.GONE
        dialogView.findViewById<TextView>(R.id.statusLabel)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.selectItemLabel)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.selectItemContainer)?.visibility = View.GONE

        // Pre-fill all fields
        nameInput.setText(item.name)
        descInput.setText(item.description)
        invInput.setText(item.invNumber)
        locationInput.setText(item.location)
        qtyInput.setText(item.quantity.toString())
        priceInput.setText(item.unitPrice.toString())

        val catAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categoryOptions)
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        catSpinner.adapter = catAdapter
        catSpinner.setSelection(categoryOptions.indexOf(item.category).coerceAtLeast(0))

        qtyLabel.text = when (item.category) {
            "Feed" -> "Quantity (Sack)"
            "Supplements" -> "Quantity (Bottle)"
            else -> "Quantity"
        }

        // Lock every field except quantity
        nameInput.isEnabled     = false
        descInput.isEnabled     = false
        invInput.isEnabled      = false
        locationInput.isEnabled = false
        priceInput.isEnabled    = false
        catSpinner.isEnabled    = false
        existSpinner.isEnabled  = false

        val dialog = AlertDialog.Builder(this)
            .setTitle("Update Stock Quantity")
            .setView(dialogView)
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newQty = qtyInput.text.toString().toLongOrNull()
                if (newQty == null) {
                    qtyInput.error = "Enter a valid quantity"
                    return@setOnClickListener
                }
                if (newQty < 0) {
                    qtyInput.error = "Quantity cannot be negative"
                    return@setOnClickListener
                }
                if (newQty == item.quantity) {
                    // No change — nothing to write
                    dialog.dismiss()
                    return@setOnClickListener
                }
                commitQuantityUpdate(item, newQty, "Stock updated") {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Atomic quantity update + audit log  (staff quantity-only edits)
    // Thin wrapper around [commitFeedChange] with no extra field changes.
    // ──────────────────────────────────────────────────────────────────────────
    private fun commitQuantityUpdate(
        item: FeedItem,
        newQty: Long,
        notes: String,
        onSuccess: () -> Unit
    ) {
        commitFeedChange(
            item         = item,
            feedDocRef   = feedCol.document(item.firestoreId),
            fieldUpdates = emptyMap(),
            newQty       = newQty,
            notes        = notes,
            onSuccess    = onSuccess
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Atomic feed update + audit log  (shared by staff quantity edits and owner
    // restocks/edits).  [fieldUpdates] carries any owner-only fields (name,
    // description, invNumber, location, unitPrice, category); quantity,
    // initialQuantity, status, and updatedAt are always recomputed here.
    // Uses a Firestore transaction so the stock write and the audit entry
    // always succeed or fail together. No audit entry is written when the
    // quantity is unchanged. Negative stock is rejected before any write.
    // ──────────────────────────────────────────────────────────────────────────
    private fun commitFeedChange(
        item: FeedItem,
        feedDocRef: com.google.firebase.firestore.DocumentReference,
        fieldUpdates: Map<String, Any>,
        newQty: Long,
        notes: String,
        onSuccess: () -> Unit
    ) {
        if (newQty < 0) {
            Toast.makeText(this, "Quantity cannot be negative", Toast.LENGTH_SHORT).show()
            return
        }

        val uid          = auth.currentUser?.uid ?: "unknown"
        val editorName   = accountManager.getCurrentUsername() ?: "User"
        val editorRole   = roleManager.role
        val quantityDiff = newQty - item.quantity
        val action       = if (quantityDiff >= 0) "add" else "deduct"

        val auditDocRef = auditCol.document()   // auto-id

        val newInitialQty = if (newQty > item.initialQuantity) newQty else item.initialQuantity
        val newStatus     = calculateStatus(newQty, newInitialQty)

        db.runTransaction { transaction ->
            // Read the current document to guard against concurrent edits
            val snapshot   = transaction.get(feedDocRef)
            val currentQty = snapshot.getLong("quantity") ?: item.quantity
            if (currentQty != item.quantity) {
                // Another write occurred between load and save; abort to prevent
                // a silent overwrite — the Firestore listener will refresh the UI.
                throw Exception("Item was modified concurrently. Please try again.")
            }

            // Write 1: update stock quantity/status plus any owner-edited fields
            val stockUpdate = fieldUpdates.toMutableMap()
            stockUpdate["quantity"]        = newQty
            stockUpdate["initialQuantity"] = newInitialQty
            stockUpdate["status"]          = newStatus
            stockUpdate["updatedAt"]       = FieldValue.serverTimestamp()
            transaction.update(feedDocRef, stockUpdate)

            // Write 2: create audit log entry — only when quantity actually changed
            if (quantityDiff != 0L) {
                val auditEntry = mapOf(
                    "productId"       to item.firestoreId,
                    "productName"     to item.name,
                    "category"        to item.category,
                    "action"          to action,
                    "quantityBefore"  to item.quantity,
                    "quantityChanged" to quantityDiff,
                    "quantityAfter"   to newQty,
                    "editedByUid"     to uid,
                    "editedByName"    to editorName,
                    "editedByRole"    to editorRole,
                    "timestamp"       to FieldValue.serverTimestamp(),
                    "notes"           to notes
                )
                transaction.set(auditDocRef, auditEntry)
            }
            null
        }.addOnSuccessListener {
            onSuccess()
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Atomic create: new feed item + initial-stock audit entry (batch write).
    // A transaction isn't needed here since there is no existing document to
    // read/guard — a batch already guarantees both writes succeed or fail
    // together. No audit entry is written when the starting quantity is zero.
    // ──────────────────────────────────────────────────────────────────────────
    private fun commitNewFeedItem(
        data: Map<String, Any>,
        qty: Long,
        name: String,
        category: String,
        onSuccess: () -> Unit
    ) {
        if (qty < 0) {
            Toast.makeText(this, "Quantity cannot be negative", Toast.LENGTH_SHORT).show()
            return
        }

        val uid        = auth.currentUser?.uid ?: "unknown"
        val editorName = accountManager.getCurrentUsername() ?: "User"
        val editorRole = roleManager.role

        val newFeedRef  = feedCol.document()   // pre-generate id so it can be reused below
        val auditDocRef = auditCol.document()

        val batch = db.batch()
        batch.set(newFeedRef, data)

        if (qty != 0L) {
            val auditEntry = mapOf(
                "productId"       to newFeedRef.id,
                "productName"     to name,
                "category"        to category,
                "action"          to "add",
                "quantityBefore"  to 0L,
                "quantityChanged" to qty,
                "quantityAfter"   to qty,
                "editedByUid"     to uid,
                "editedByName"    to editorName,
                "editedByRole"    to editorRole,
                "timestamp"       to FieldValue.serverTimestamp(),
                "notes"           to "Initial stock added"
            )
            batch.set(auditDocRef, auditEntry)
        }

        batch.commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Add failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Atomic delete: remove feed item + closing audit entry (batch write).
    // ──────────────────────────────────────────────────────────────────────────
    private fun commitFeedDelete(item: FeedItem, onSuccess: () -> Unit) {
        val uid        = auth.currentUser?.uid ?: "unknown"
        val editorName = accountManager.getCurrentUsername() ?: "User"
        val editorRole = roleManager.role

        val feedDocRef  = feedCol.document(item.firestoreId)
        val auditDocRef = auditCol.document()

        val batch = db.batch()
        batch.delete(feedDocRef)

        if (item.quantity != 0L) {
            val auditEntry = mapOf(
                "productId"       to item.firestoreId,
                "productName"     to item.name,
                "category"        to item.category,
                "action"          to "deduct",
                "quantityBefore"  to item.quantity,
                "quantityChanged" to -item.quantity,
                "quantityAfter"   to 0L,
                "editedByUid"     to uid,
                "editedByName"    to editorName,
                "editedByRole"    to editorRole,
                "timestamp"       to FieldValue.serverTimestamp(),
                "notes"           to "Item deleted"
            )
            batch.set(auditDocRef, auditEntry)
        }

        batch.commit()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Zero Quantity Popup
    // ──────────────────────────────────────────────────────────────────────────
    private fun showZeroQuantityPopup(item: FeedItem) {
        if (roleManager.canEditFarm()) {
            AlertDialog.Builder(this)
                .setTitle("Out of Stock: ${item.name}")
                .setMessage("This supply is currently at 0 quantity. Would you like to restock it or delete the entry?")
                .setPositiveButton("Restock") { _, _ -> showAddEditDialog(item) }
                .setNegativeButton("Delete") { _, _ -> showDeleteConfirmation(item) }
                .setNeutralButton("Cancel", null)
                .show()
        } else if (roleManager.canUpdateInventoryQuantity()) {
            AlertDialog.Builder(this)
                .setTitle("Out of Stock: ${item.name}")
                .setMessage("This supply is currently at 0 quantity. You can update the stock quantity if stock has been replenished.")
                .setPositiveButton("Update Quantity") { _, _ -> showQuantityUpdateDialog(item) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Out of Stock")
                .setMessage("\"${item.name}\" has run out of stock. Please notify the owner for replenishment.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Search dialog
    // ──────────────────────────────────────────────────────────────────────────
    private fun showSearchDialog() {
        val editText = EditText(this).apply {
            hint = "Search by name, INV number, or category…"
            setText(searchQuery)
            setPadding(48, 24, 48, 24)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    renderList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Search Feed Items")
            .setView(editText)
            .setPositiveButton("Search") { _: DialogInterface, _: Int ->
                searchQuery = editText.text.toString().trim()
                renderList()
            }
            .setNegativeButton("Clear") { _: DialogInterface, _: Int ->
                searchQuery = ""
                renderList()
            }
            .show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Add / Edit dialog  (owner only)
    // ──────────────────────────────────────────────────────────────────────────
    private fun showAddEditDialog(item: FeedItem?) {
        val isEdit     = item != null
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_food, null)

        val nameInput     = dialogView.findViewById<EditText>(R.id.foodNameInput)
        val descInput     = dialogView.findViewById<EditText>(R.id.foodDescInput)
        val invInput      = dialogView.findViewById<EditText>(R.id.foodInvInput)
        val locationInput = dialogView.findViewById<EditText>(R.id.foodLocationInput)
        val qtyInput      = dialogView.findViewById<EditText>(R.id.foodQtyInput)
        val qtyLabel      = dialogView.findViewById<TextView>(R.id.foodQtyLabel)
        val priceInput    = dialogView.findViewById<EditText>(R.id.foodPriceInput)
        val catSpinner    = dialogView.findViewById<Spinner>(R.id.categorySpinner)
        val statusSpinner = dialogView.findViewById<Spinner>(R.id.statusSpinner)
        val existSpinner  = dialogView.findViewById<Spinner>(R.id.existingItemsSpinner)

        statusSpinner.visibility = View.GONE
        dialogView.findViewById<TextView>(R.id.statusLabel)?.let { it.visibility = View.GONE }

        val catAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categoryOptions)
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        catSpinner.adapter = catAdapter

        val spinnerData = mutableListOf("--- Create New Item ---")
        spinnerData.addAll(allItems.map { "${it.name} (${it.location})" })
        val existAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerData)
        existAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        existSpinner.adapter = existAdapter

        if (isEdit) {
            dialogView.findViewById<View>(R.id.selectItemLabel)?.visibility = View.GONE
            dialogView.findViewById<View>(R.id.selectItemContainer)?.visibility = View.GONE
        }

        existSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) {
                    val selected = allItems[pos - 1]
                    nameInput.setText(selected.name)
                    descInput.setText(selected.description)
                    invInput.setText(selected.invNumber)
                    locationInput.setText(selected.location)
                    priceInput.setText(selected.unitPrice.toString())
                    catSpinner.setSelection(categoryOptions.indexOf(selected.category).coerceAtLeast(0))
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        catSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = categoryOptions[position]
                qtyLabel.text = when (selected) {
                    "Feed" -> "Quantity (Sack)"
                    "Supplements" -> "Quantity (Bottle)"
                    else -> "Quantity"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (isEdit && item != null) {
            nameInput.setText(item.name)
            descInput.setText(item.description)
            invInput.setText(item.invNumber)
            locationInput.setText(item.location)
            qtyInput.setText(item.quantity.toString())
            priceInput.setText(item.unitPrice.toString())
            catSpinner.setSelection(categoryOptions.indexOf(item.category).coerceAtLeast(0))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEdit) "Edit Feed Item" else "Add Feed Item")
            .setView(dialogView)
            .setPositiveButton(if (isEdit) "Update" else "Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameInput.error = "Name cannot be empty"
                    return@setOnClickListener
                }

                val location = locationInput.text.toString().trim().ifEmpty { "Location Info" }
                val price    = priceInput.text.toString().toDoubleOrNull() ?: 0.0
                val qtyIn    = qtyInput.text.toString().toLongOrNull()
                if (qtyIn == null) {
                    qtyInput.error = "Enter a valid quantity"
                    return@setOnClickListener
                }
                if (qtyIn < 0) {
                    qtyInput.error = "Quantity cannot be negative"
                    return@setOnClickListener
                }
                val cat      = catSpinner.selectedItem.toString()
                var desc     = descInput.text.toString().trim()
                val inv      = invInput.text.toString().trim().ifEmpty { generateInvNumber() }

                val matchingItem = allItems.find {
                    it.name.equals(name, true) &&
                            it.location.equals(location, true) &&
                            it.unitPrice == price
                }

                val nameExists = allItems.any { it.name.equals(name, true) }
                if (nameExists && matchingItem == null) {
                    if (!desc.contains(location, true)) {
                        desc = if (desc.isEmpty()) "Located at $location" else "$desc ($location)"
                    }
                }

                if (matchingItem != null) {
                    val finalQty = if (isEdit && item?.firestoreId == matchingItem.firestoreId) {
                        qtyIn
                    } else {
                        matchingItem.quantity + qtyIn
                    }

                    val fieldUpdates = mapOf<String, Any>(
                        "name"        to name,
                        "description" to desc
                    )

                    commitFeedChange(
                        item         = matchingItem,
                        feedDocRef   = feedCol.document(matchingItem.firestoreId),
                        fieldUpdates = fieldUpdates,
                        newQty       = finalQty,
                        notes        = if (isEdit) "Stock updated" else "Stock restocked",
                        onSuccess    = {
                            logHistory(if (isEdit) "UPDATE" else "RESTOCK", matchingItem.name, qtyIn, price, matchingItem.category)
                            if (isEdit && item != null && item.firestoreId != matchingItem.firestoreId) {
                                feedCol.document(item.firestoreId).delete()
                            }
                            dialog.dismiss()
                        }
                    )
                } else {
                    if (isEdit && item != null) {
                        val fieldUpdates = mapOf<String, Any>(
                            "name"        to name,
                            "description" to desc,
                            "invNumber"   to inv,
                            "location"    to location,
                            "unitPrice"   to price,
                            "category"    to cat
                        )
                        commitFeedChange(
                            item         = item,
                            feedDocRef   = feedCol.document(item.firestoreId),
                            fieldUpdates = fieldUpdates,
                            newQty       = qtyIn,
                            notes        = "Stock updated",
                            onSuccess    = {
                                logHistory("UPDATE", name, qtyIn, price, cat)
                                dialog.dismiss()
                            }
                        )
                    } else {
                        val status = calculateStatus(qtyIn, qtyIn)
                        val data = hashMapOf<String, Any>(
                            "name"            to name,
                            "description"     to desc,
                            "invNumber"       to inv,
                            "location"        to location,
                            "quantity"        to qtyIn,
                            "initialQuantity" to qtyIn,
                            "unitPrice"       to price,
                            "category"        to cat,
                            "status"          to status,
                            "updatedAt"       to FieldValue.serverTimestamp()
                        )
                        commitNewFeedItem(data, qtyIn, name, cat) {
                            logHistory("ADDED", name, qtyIn, price, cat)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delete confirmation  (owner only)
    // ──────────────────────────────────────────────────────────────────────────
    private fun showDeleteConfirmation(item: FeedItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Feed Item")
            .setMessage("Are you sure you want to delete \"${item.name}\"?")
            .setPositiveButton("Delete") { _: DialogInterface, _: Int ->
                commitFeedDelete(item) {
                    logHistory("DELETED", item.name, item.quantity, item.unitPrice, item.category)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Legacy feed_history log entry — preserved for backward compatibility with
     * [FeedHistoryActivity] which reads from farm_data/shared/feed_history.
     */
    private fun logHistory(type: String, name: String, qty: Long, price: Double, category: String) {
        val entry = hashMapOf(
            "type"      to type,
            "name"      to name,
            "quantity"  to qty,
            "unitPrice" to price,
            "category"  to category,
            "timestamp" to FieldValue.serverTimestamp()
        )
        historyCol.add(entry)
    }

    private fun calculateStatus(qty: Long, initialQty: Long): String {
        if (initialQty <= 0) return "In Stock"
        val ratio = qty.toDouble() / initialQty.toDouble()
        return when {
            ratio <= 0.2 -> "Low Stock"
            ratio <= 0.5 -> "Medium"
            else -> "In Stock"
        }
    }

    private fun generateInvNumber(): String {
        val next = (allItems.size + 1).toString().padStart(4, '0')
        return "#INV$next"
    }
}