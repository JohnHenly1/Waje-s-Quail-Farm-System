package com.example.exp1

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.appcompat.widget.ListPopupWindow
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AlertsActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager
    private lateinit var roleManager: RoleManager
    private var farmAlertsListener: com.google.firebase.firestore.ListenerRegistration? = null

    private lateinit var alertsRecyclerView: RecyclerView
    private lateinit var alertsAdapter: AlertsAdapter

    // Coalesces rapid-fire Firestore snapshot events (e.g. a burst of writes) into a
    // single re-render instead of rebuilding the list once per event. This is one of
    // the things that made the screen lag/ANR when a lot of alerts came in at once.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pendingListUpdate = Runnable { updateAlertsList() }

    private var activeFilter = "All"
    private var activeSeverity: Severity? = null   // null = no severity filter
    private enum class DateFilter { TODAY, WEEK, MONTH, YEAR, CUSTOM }
    private var activeDate: DateFilter? = null
    private var rangeStart = 0L    // inclusive
    private var rangeEnd = 0L      // exclusive
    private var dateLabel = ""     // text shown in the date dropdown
    private val alertDateFormat = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
    private var severityCounts: Map<Severity, Int> = emptyMap()

    companion object {
        // Cloud alert history can grow unbounded. Rendering every single alert ever
        // recorded is what actually caused the lag/crash with "too many notifications" —
        // there's no functional need to show more than the most recent ones at once.
        private const val MAX_RENDERED_ALERTS = 200

        // Debounce window for coalescing bursts of Firestore snapshot events.
        private const val LIST_UPDATE_DEBOUNCE_MS = 150L
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_alerts)

        accountManager = AccountManager(this)
        roleManager = RoleManager(accountManager.getCurrentRole())

        try {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // RecyclerView replaces the old approach of removeAllViews() + re-inflating
        // every alert row from scratch on every refresh. RecyclerView recycles rows
        // and DiffUtil (inside AlertsAdapter) only re-binds what actually changed.
        alertsRecyclerView = findViewById(R.id.alertsRecyclerView)
        alertsAdapter = AlertsAdapter()
        alertsRecyclerView.layoutManager = LinearLayoutManager(this)
        alertsRecyclerView.adapter = alertsAdapter
        alertsRecyclerView.setHasFixedSize(false)
        // Disabling the built-in add/remove/change animations. With potentially
        // hundreds of rows changing (read-state flips, new alerts, filter switches),
        // animating every change is expensive for no real benefit here. Re-enable
        // (remove this line) if you want RecyclerView's default fade animations back.
        alertsRecyclerView.itemAnimator = null

        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.clearAllButton).setOnClickListener {
            // Alerts live in ONE shared Firestore collection for the whole farm.
            // Deleting those documents (the old behavior) cleared them for every
            // account, not just the person who tapped the button. Instead, record
            // a per-user "cleared before now" cutoff and filter with it locally —
            // this user's list empties out, everyone else's is untouched, and any
            // new alert raised after this point still shows up normally.
            accountManager.setAlertsClearedNow()
            updateAlertsList()
            Toast.makeText(this, "Alerts cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.markAllReadBtn).setOnClickListener {
            GlobalData.markAllAsRead()
            // Sync read status to cloud
            FarmRepository.markAllAlertsRead()
            updateAlertsList()
            Toast.makeText(this, "All alerts marked as read", Toast.LENGTH_SHORT).show()
        }

        // ROLE-BASED VISIBILITY: Notification preference only available to owners/backup
        val settingsBtn = findViewById<View>(R.id.settingsButton)
        if (roleManager.canEditFarm()) {
            settingsBtn.visibility = View.VISIBLE
            settingsBtn.setOnClickListener {
                showNotificationPrefsDialog()
            }
        } else {
            settingsBtn.visibility = View.GONE
        }

        setupFilters()
        setupDropdowns()
        updateAlertsList()
        // Refresh button just re-renders the current alert list from
        // GlobalData/cloud — the underlying detection (inventory, tasks,
        // water level) now runs continuously in AlertsMonitor (started from
        // WajeApplication), not just while this screen is open, so there's
        // nothing left to manually re-trigger here.
        findViewById<View>(R.id.refreshButton)?.setOnClickListener {
            updateAlertsList()
            Toast.makeText(this, "Refreshed alerts", Toast.LENGTH_SHORT).show()
        }
        NavigationHelper.setupBottomNavigation(this)
    }

    private fun setupFilters() {
        val filters = mapOf(
            "All" to findViewById<TextView>(R.id.filterAll),
            "Inventory" to findViewById<TextView>(R.id.filterInventory),
            "Schedule" to findViewById<TextView>(R.id.filterSchedule),
            "Water Level" to findViewById<TextView>(R.id.filterWaterLevel)
        )

        // Hide the Egg Count filter tab
        findViewById<TextView>(R.id.filterEggCount)?.visibility = View.GONE

        filters.forEach { (name, view) ->
            view?.setOnClickListener {
                activeFilter = name
                updateFilterUI(filters)
                updateAlertsList()
            }
        }
    }

    private fun updateFilterUI(filters: Map<String, TextView?>) {
        filters.forEach { (name, view) ->
            if (name == activeFilter) {
                view?.setBackgroundResource(R.drawable.rounded_dark_green)
                view?.setTextColor(Color.WHITE)
                view?.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                view?.setBackgroundResource(R.drawable.rounded_gray_bg)
                view?.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
                view?.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        // Keep Egg Count tab hidden regardless of selection
        findViewById<TextView>(R.id.filterEggCount)?.visibility = View.GONE
    }
    private fun severityLabels(): List<String> = when (activeFilter) {
        "Inventory"   -> listOf("Depleted / Low", "Reorder Soon", "Info", "Others")
        "Schedule"    -> listOf("Overdue", "Pending", "Due Today", "Others")
        "Water Level" -> listOf("Emergency / Critical", "Warning", "Notice / Update", "Refilled")
        else          -> listOf("Red Alerts", "Orange Alerts", "Blue Alerts", "Others")
    }
    // Generic white dropdown anchored under a view
    private fun showDropdown(anchor: View, options: List<String>, onPick: (Int) -> Unit) {
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.width = anchor.width
        popup.isModal = true
        popup.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        popup.setAdapter(object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#212121"))
                tv.textSize = 14f
                return tv
            }
        })
        popup.setOnItemClickListener { _, _, pos, _ ->
            popup.dismiss()
            onPick(pos)
        }
        popup.show()
    }

    private fun setupDropdowns() {
        findViewById<TextView>(R.id.severityDropdown).setOnClickListener { v ->
            val labels = severityLabels()
            val sevs = Severity.values()
            val options = listOf("All status") +
                    sevs.mapIndexed { i, s -> "${labels[i]} (${severityCounts[s] ?: 0})" }
            showDropdown(v, options) { pos ->
                activeSeverity = if (pos == 0) null else sevs[pos - 1]
                updateAlertsList()
            }
        }

        findViewById<TextView>(R.id.dateDropdown).setOnClickListener { v ->
            val options = listOf("Any date", "Today", "Weekly…", "Monthly…", "Yearly…", "Custom range…")
            showDropdown(v, options) { pos ->
                when (pos) {
                    0 -> { activeDate = null; updateAlertsList() }
                    1 -> { activeDate = DateFilter.TODAY; updateAlertsList() }
                    2 -> showWeekPicker()
                    3 -> showMonthPicker()
                    4 -> showYearPicker()
                    5 -> showCustomPicker()
                }
            }
        }
    }

    private fun updateDropdownLabels(scope: List<GlobalData.AlertItem>) {
        severityCounts = scope.groupingBy { severityOf(it) }.eachCount()

        val green = ContextCompat.getColor(this, R.color.dark_green)
        val normal = Color.parseColor("#212121")

        val sevView = findViewById<TextView>(R.id.severityDropdown)
        val sevText = activeSeverity?.let { severityLabels()[it.ordinal] } ?: "All status"
        sevView.text = "$sevText  ▾"
        sevView.setTextColor(if (activeSeverity != null) green else normal)
        sevView.setTypeface(null, if (activeSeverity != null) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        val dateText = when (activeDate) {
            null -> "Any date"
            DateFilter.TODAY -> "Today"
            else -> dateLabel
        }
        val dateView = findViewById<TextView>(R.id.dateDropdown)
        dateView.text = "$dateText  ▾"
        dateView.setTextColor(if (activeDate != null) green else normal)
        dateView.setTypeface(null, if (activeDate != null) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // Returns [start, end) in millis for the selected date filter.
// ---------- Date filter: ranges + minimalist white / green-edge pickers ----------

    private val greenColor: Int get() = ContextCompat.getColor(this, R.color.dark_green)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dateRangeFor(f: DateFilter): Pair<Long, Long> {
        if (f == DateFilter.TODAY) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val s = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return s to cal.timeInMillis
        }
        return rangeStart to rangeEnd   // WEEK / MONTH / YEAR / CUSTOM use the picked range
    }

    private fun setRange(f: DateFilter, start: Long, endExclusive: Long, label: String) {
        activeDate = f
        rangeStart = start
        rangeEnd = endExclusive
        dateLabel = label
        updateAlertsList()
    }

    private fun rangeLabel(startMs: Long, endInclusiveMs: Long): String {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val sameYear =
            Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.YEAR) == thisYear &&
                    Calendar.getInstance().apply { timeInMillis = endInclusiveMs }.get(Calendar.YEAR) == thisYear
        val f = SimpleDateFormat(if (sameYear) "MMM d" else "MMM d, yyyy", Locale.getDefault())
        return "${f.format(Date(startMs))} – ${f.format(Date(endInclusiveMs))}"
    }

    // White box, optional green edge
    private fun boxBg(
        filled: Boolean,
        edge: Int = Color.parseColor("#D0D5DD"),
        radius: Int = 10,
        strokeDp: Int = 1
    ) = android.graphics.drawable.GradientDrawable().apply {
        setColor(if (filled) greenColor else Color.WHITE)
        setStroke(dp(strokeDp), if (filled) greenColor else edge)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pickCell(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = android.view.Gravity.CENTER
        textSize = 14f
        setTextColor(if (selected) Color.WHITE else Color.parseColor("#212121"))
        setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        background = boxBg(selected)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        setOnClickListener { onClick() }
    }

    private fun cellGrid(cells: List<TextView>, columns: Int = 3): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(columns).forEach { rowCells ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowCells.forEach { row.addView(it) }
            col.addView(row)
        }
        return col
    }

    // Shared dialog: white background, green edge, green title, text buttons
    private fun showGreenDialog(
        title: String,
        applyText: String? = null,
        onApply: (() -> Unit)? = null,
        content: (AlertDialog) -> View
    ) {
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(greenColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(content(dialog))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        fun action(label: String, color: Int, bold: Boolean, onClick: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(color)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(10), dp(8), dp(10))
                setOnClickListener { onClick() }
            })
        }
        action("Cancel", Color.parseColor("#757575"), false) { dialog.dismiss() }
        if (applyText != null && onApply != null) {
            action(applyText, greenColor, true) { onApply(); dialog.dismiss() }
        }
        root.addView(actions)

        dialog.setView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(boxBg(false, greenColor, 16, 2))
    }

    // ---- Weekly: pick a week (Mon–Sun) ----
    private fun showWeekPicker() {
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
        }
        showGreenDialog("Select week") { dialog ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            repeat(26) { i ->
                val start = cal.timeInMillis
                val endIncl = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
                val endExcl = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
                val range = "${fmt.format(cal.time)} – ${fmt.format(endIncl.time)}"
                val selected = activeDate == DateFilter.WEEK && rangeStart == start
                val tag = when (i) { 0 -> "This week · "; 1 -> "Last week · "; else -> "" }

                list.addView(TextView(this).apply {
                    text = "$tag$range"
                    textSize = 14f
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#212121"))
                    if (selected) setTypeface(null, android.graphics.Typeface.BOLD)
                    background = if (selected) boxBg(true) else null
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener {
                        setRange(DateFilter.WEEK, start, endExcl, range)
                        dialog.dismiss()
                    }
                })
                cal.add(Calendar.DAY_OF_YEAR, -7)
            }
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
                addView(list)
            }
        }
    }

    // ---- Monthly: pick a year, then a month ----
    private fun showMonthPicker() {
        var year = Calendar.getInstance().apply {
            if (activeDate == DateFilter.MONTH) timeInMillis = rangeStart
        }.get(Calendar.YEAR)
        val months = java.text.DateFormatSymbols.getInstance().shortMonths

        showGreenDialog("Select month") { dialog ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val gridHolder = FrameLayout(this)
            val yearText = TextView(this).apply {
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#212121"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            fun render() {
                yearText.text = year.toString()
                val cells = (0 until 12).map { m ->
                    val picked = Calendar.getInstance().apply {
                        if (activeDate == DateFilter.MONTH) timeInMillis = rangeStart
                    }
                    val selected = activeDate == DateFilter.MONTH &&
                            picked.get(Calendar.YEAR) == year && picked.get(Calendar.MONTH) == m
                    pickCell(months[m], selected) {
                        val s = Calendar.getInstance().apply { clear(); set(year, m, 1) }
                        val e = (s.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                        val label = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(s.time)
                        setRange(DateFilter.MONTH, s.timeInMillis, e.timeInMillis, label)
                        dialog.dismiss()
                    }
                }
                gridHolder.removeAllViews()
                gridHolder.addView(cellGrid(cells))
            }

            fun arrow(symbol: String, delta: Int) = TextView(this).apply {
                text = symbol
                textSize = 22f
                setTextColor(greenColor)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(4), dp(16), dp(4))
                setOnClickListener { year += delta; render() }
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(arrow("‹", -1))
                addView(yearText)
                addView(arrow("›", 1))
            }

            render()
            box.addView(header)
            box.addView(gridHolder)
            box
        }
    }

    // ---- Yearly: pick a year ----
    private fun showYearPicker() {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        showGreenDialog("Select year") { dialog ->
            val cells = (0 until 12).map { i ->
                val y = thisYear - i
                val selected = activeDate == DateFilter.YEAR &&
                        Calendar.getInstance().apply { timeInMillis = rangeStart }.get(Calendar.YEAR) == y
                pickCell(y.toString(), selected) {
                    val s = Calendar.getInstance().apply { clear(); set(y, Calendar.JANUARY, 1) }
                    val e = (s.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
                    setRange(DateFilter.YEAR, s.timeInMillis, e.timeInMillis, y.toString())
                    dialog.dismiss()
                }
            }
            cellGrid(cells)
        }
    }

    // ---- Custom: From / To boxes with green edge ----
    private fun showCustomPicker() {
        fun dayStart(ms: Long) = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val today = dayStart(System.currentTimeMillis())
        var from = if (activeDate == DateFilter.CUSTOM) rangeStart else today
        var to = if (activeDate == DateFilter.CUSTOM) dayStart(rangeEnd - 1) else today
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        showGreenDialog("Custom range", "Apply", {
            val s = minOf(from, to)
            val e = maxOf(from, to)
            val endExcl = Calendar.getInstance().apply {
                timeInMillis = e
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            setRange(DateFilter.CUSTOM, s, endExcl, rangeLabel(s, e))
        }) { _ ->
            fun dateBox(caption: String, getter: () -> Long, setter: (Long) -> Unit): View {
                val value = TextView(this).apply {
                    text = fmt.format(Date(getter()))
                    textSize = 15f
                    setTextColor(Color.parseColor("#212121"))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    background = boxBg(false, greenColor, 10, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                }
                value.setOnClickListener {
                    val c = Calendar.getInstance().apply { timeInMillis = getter() }
                    android.app.DatePickerDialog(
                        this, R.style.GreenDatePicker,
                        { _, y, m, d ->
                            val picked = Calendar.getInstance().apply { clear(); set(y, m, d) }.timeInMillis
                            setter(picked)
                            value.text = fmt.format(Date(picked))
                        },
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
                return LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, dp(10))
                    addView(TextView(context).apply {
                        text = caption
                        textSize = 12f
                        setTextColor(Color.parseColor("#757575"))
                        setPadding(dp(2), 0, 0, dp(4))
                    })
                    addView(value)
                }
            }

            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(dateBox("From", { from }, { from = it }))
                addView(dateBox("To", { to }, { to = it }))
            }
        }
    }
    private fun inRange(alert: GlobalData.AlertItem, range: Pair<Long, Long>): Boolean {
        // "Just now" / unparseable timestamps are treated as brand-new alerts
        val t = try { alertDateFormat.parse(alert.timestamp)?.time } catch (e: Exception) { null }
            ?: System.currentTimeMillis()
        return t >= range.first && t < range.second
    }


    // Tracker to prevent auto-alerts from reappearing on the same day once cleared.
    // Same SharedPreferences file/keys AlertsMonitor uses, so an alert already
    // raised there today stays deduped here too (used by checkEggCountLogs()).
    private fun wasAlreadyAlertedToday(message: String): Boolean {
        val prefs = getSharedPreferences("auto_alerts_tracker", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return prefs.getString(message, "") == today
    }

    private fun markAsAlertedToday(message: String) {
        val prefs = getSharedPreferences("auto_alerts_tracker", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        prefs.edit().putString(message, today).apply()
    }

    private fun checkEggCountLogs() {
        if (!accountManager.isEggCountEnabled()) return

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        FirebaseDatabase.getInstance().getReference("egg_collections").child(todayStr).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    val dayStamp = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
                    val message = "Log Missing: No egg collection recorded for today yet."
                    val existingAlerts = GlobalData.getAlerts()

                    if (existingAlerts.none { it.message == message && it.timestamp.startsWith(dayStamp) } && !wasAlreadyAlertedToday(message)) {
                        FarmRepository.addAlert(message, "Egg Count")
                        markAsAlertedToday(message)
                    }
                }
            }
    }

    private fun showNotificationPrefsDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_notification_preferences, null)
        builder.setView(dialogView)

        val switchAlerts = dialogView.findViewById<SwitchCompat>(R.id.switchAlerts)
        val switchGlobalData = dialogView.findViewById<SwitchCompat>(R.id.switchGlobalData)
        val switchSchedule = dialogView.findViewById<SwitchCompat>(R.id.switchSchedule)
        val switchEggCount = dialogView.findViewById<SwitchCompat>(R.id.switchEggCount)
        val btnEggCountTime = dialogView.findViewById<Button>(R.id.btnEggCountTime)

        switchAlerts.isChecked = accountManager.isAlertsEnabled()
        switchGlobalData.isChecked = accountManager.isGlobalDataEnabled()
        switchSchedule.isChecked = accountManager.isScheduleEnabled()
        switchEggCount.isChecked = accountManager.isEggCountEnabled()

        val hour = accountManager.getEggCountHour()
        val minute = accountManager.getEggCountMinute()
        btnEggCountTime.text = String.format("%02d:%02d", hour, minute)

        btnEggCountTime.setOnClickListener {
            val timePicker = android.app.TimePickerDialog(this, { _, selectedHour, selectedMinute ->
                btnEggCountTime.text = String.format("%02d:%02d", selectedHour, selectedMinute)
            }, hour, minute, false)
            timePicker.show()
        }

        builder.setTitle("Notification Preferences")
            .setPositiveButton("Save") { _, _ ->
                val selectedTime = btnEggCountTime.text.toString().split(":")
                val eggHour = selectedTime[0].toInt()
                val eggMinute = selectedTime[1].toInt()
                accountManager.saveNotificationPreferences(
                    switchAlerts.isChecked,
                    switchGlobalData.isChecked,
                    switchSchedule.isChecked,
                    switchEggCount.isChecked,
                    eggHour,
                    eggMinute
                )
                PushTopics.syncSubscriptions(this)
                Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show()
                if (switchEggCount.isChecked) {
                    scheduleEggCountNotification(eggHour, eggMinute)
                } else {
                    cancelEggCountNotification()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()

        farmAlertsListener = FarmRepository.listenToAlerts { alerts ->
            val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
            val mappedAlerts = alerts.map { alert ->
                val firestoreTs = alert["timestamp"]
                val timestampStr = when (firestoreTs) {
                    is com.google.firebase.Timestamp -> sdf.format(firestoreTs.toDate())
                    is String -> firestoreTs
                    else -> "Just now"
                }
                GlobalData.AlertItem(
                    message = alert["message"] as? String ?: "",
                    timestamp = timestampStr,
                    type = alert["type"] as? String ?: "Inventory",
                    isRead = alert["isRead"] as? Boolean ?: false
                )
            }
            GlobalData.syncWithCloud(mappedAlerts)
            // Debounced instead of an immediate runOnUiThread { updateAlertsList() } —
            // a burst of Firestore snapshot events (e.g. several docs changing at once)
            // used to trigger a full list rebuild for each one individually.
            scheduleAlertsListUpdate()
        }
        // Inventory / task / water-level detection now runs continuously in
        // AlertsMonitor (started once from WajeApplication), not per-Activity —
        // nothing to start here beyond the alerts-list listener above.
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(pendingListUpdate)
        farmAlertsListener?.remove()
        farmAlertsListener = null
    }

    private fun scheduleAlertsListUpdate() {
        uiHandler.removeCallbacks(pendingListUpdate)
        uiHandler.postDelayed(pendingListUpdate, LIST_UPDATE_DEBOUNCE_MS)
    }

    private fun updateAlertsList() {
        // Only hides alerts this account has cleared (see clearAllButton above) —
        // the underlying shared alert history is untouched, so other users'
        // lists are unaffected.
        val allAlerts = GlobalData.getAlerts(accountManager.getAlertsClearedBefore())

        // Filter logic updated to correctly handle category separation
        val categoryFiltered = if (activeFilter == "All") {
            allAlerts
        } else {
            allAlerts.filter {
                when (activeFilter) {
                    "Inventory" -> it.type == "Inventory" || (it.type == "Critical" && it.message.contains("Inventory", true))
                    "Schedule" -> it.type == "Schedule" || (it.type == "Critical" && it.message.contains("Missed", true))
                    "Water Level" -> it.type == "Water Level"
                    else -> true
                }
            }
        }


        val range = activeDate?.let { dateRangeFor(it) }
        val dateFiltered = if (range == null) categoryFiltered
        else categoryFiltered.filter { inRange(it, range) }

        updateDropdownLabels(dateFiltered)   // status counts respect the date range

        val alerts = activeSeverity
            ?.let { s -> dateFiltered.filter { severityOf(it) == s } }
            ?: dateFiltered

        // Parse each timestamp ONCE and reuse it for both sorting and the relative-time
        // label below, instead of parsing the same string twice per alert on every
        // single refresh (the old code parsed once for sorting and again inside
        // getRelativeTime() for every visible row).
        val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
        val withDates = alerts.map { alert ->
            val parsed = try { sdf.parse(alert.timestamp) } catch (e: Exception) { Date(0) }
            alert to parsed
        }
        val sortedWithDates = withDates.sortedByDescending { it.second }

        // Cap how many rows we actually build/bind. Cloud alert history can grow
        // unbounded; rendering thousands of them is what caused the lag/crash — there's
        // no practical reason to show more than the most recent alerts at a time.
        val capped = sortedWithDates.take(MAX_RENDERED_ALERTS)
        val sortedAlerts = capped.map { it.first }

        // Summary Cards - Count based on current filtered view and priority
        val urgentCount = sortedAlerts.count { severityOf(it) == Severity.RED }
        val generalCount = sortedAlerts.size - urgentCount
        findViewById<TextView>(R.id.criticalCount)?.text = urgentCount.toString()
        findViewById<TextView>(R.id.warningCount)?.text = generalCount.toString()
        findViewById<TextView>(R.id.totalCount)?.text = sortedAlerts.size.toString()

        updateSummaryLabels()

        val displayList: List<DisplayAlert> = if (capped.isEmpty()) {
            listOf(AlertsAdapter.emptyPlaceholder(
                if (activeSeverity != null || activeDate != null) "No alerts match these filters"
                else "No $activeFilter alerts available"
            ))
        } else {
            capped.map { (alert, parsedDate) ->
                DisplayAlert(
                    alert = alert,
                    relativeTime = getRelativeTime(alert, parsedDate),
                    stableId = "${alert.timestamp}|${alert.message}"
                )
            }
        }

        // ListAdapter.submitList() diffs the old/new lists on a background thread and
        // only rebinds the rows that actually changed — this is what replaces the old
        // removeAllViews() + re-inflate-everything-from-scratch approach.
        alertsAdapter.submitList(displayList) {
            // runs after the list is committed, so the RecyclerView resizes to the new row count
            alertsRecyclerView.requestLayout()
        }
    }

    private fun updateSummaryLabels() {
        val criticalLabel = findViewById<TextView>(R.id.criticalLabel)
        val warningLabel = findViewById<TextView>(R.id.warningLabel)
        val totalLabel = findViewById<TextView>(R.id.totalLabel)

        when (activeFilter) {
            "Inventory" -> {
                criticalLabel.text = "Stock Depleted"
                warningLabel.text = "Low Stock"
                totalLabel.text = "Inventory Alerts"
            }
            "Schedule" -> {
                criticalLabel.text = "Overdue Tasks"
                warningLabel.text = "Pending Today"
                totalLabel.text = "Scheduled Duty"
            }
            "Water Level" -> {
                criticalLabel.text = "Critical/Empty"
                warningLabel.text = "Low Level"
                totalLabel.text = "Water Alerts"
            }
            else -> {
                criticalLabel.text = "Urgent Issues"
                warningLabel.text = "Alerts"
                totalLabel.text = "All Notifications"
            }
        }
    }

    private fun getRelativeTime(alert: GlobalData.AlertItem, parsedDate: Date): String {
        val now = System.currentTimeMillis()
        val diff = now - parsedDate.time

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} mins ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} hours ago"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)} days ago"
            else -> alert.timestamp.split(" ")[0]
        }
    }

    private fun scheduleEggCountNotification(hour: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, EggCountNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun cancelEggCountNotification() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, EggCountNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }

    class EggCountNotificationReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "egg_count_channel",
                    "Egg Count Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Daily egg count reminders"
                    enableLights(true)
                    enableVibration(true)
                    setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm != null) nm.createNotificationChannel(channel)
            }

            val accountManager = AccountManager(context)
            if (!accountManager.isAlertsEnabled() || !accountManager.isEggCountEnabled()) return

            val database = FirebaseDatabase.getInstance()
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            database.getReference("egg_collections").child(todayStr).get().addOnSuccessListener { snapshot ->
                val total = (snapshot.child("total").value as? Long)?.toInt() ?: 0
                val message = "Today's egg count summary: $total eggs collected."

                val timestamp = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()).format(Date())
                GlobalData.addAlert(message, timestamp, "System")

                val notificationIntent = Intent(context, MainActivity::class.java)
                notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val builder = androidx.core.app.NotificationCompat.Builder(context, "egg_count_channel")
                    .setSmallIcon(R.drawable.ic_notifications)
                    .setContentTitle("Daily Egg Count Reminder")
                    .setContentText(message)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                    .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)

                try {
                    androidx.core.app.NotificationManagerCompat.from(context).notify(2002, builder.build())
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }
}