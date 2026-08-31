package com.example.exp1

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.io.File
import java.text.DateFormatSymbols
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import kotlin.random.Random

class DashboardActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateTimeRunnable: Runnable

    private var username: String = "User"
    private var displayName: String = "User"
    private lateinit var accountManager: AccountManager
    private var userRole: String = "staff"

    private var photoUri: Uri? = null

    private var roleListener: ListenerRegistration? = null
    private var alertsListener: ListenerRegistration? = null
    private lateinit var notificationBadge: TextView

    // ----------------------------
    // Egg Grade Overview mini widget (mirrors AnalyticsActivity's filter/chart logic)
    // ----------------------------
    private lateinit var eggCollectionsRef: DatabaseReference
    private var eggCollectionsListener: ValueEventListener? = null
    private var eggAllData: TreeMap<String, DailyEggData> = TreeMap()
    private var eggListenerAttached = false

    private lateinit var miniGradePieChart: PieChart
    private lateinit var eggFilterSpinner: Spinner
    private lateinit var eggFilterChoiceCard: CardView
    private lateinit var eggFilterChoiceButton: LinearLayout
    private lateinit var eggFilterChoiceText: TextView
    private lateinit var eggWidgetTotalText: TextView
    private lateinit var miniGradeAProgress: ProgressBar
    private lateinit var miniGradeBProgress: ProgressBar
    private lateinit var miniGradeCProgress: ProgressBar
    private lateinit var miniGradeACount: TextView
    private lateinit var miniGradeBCount: TextView
    private lateinit var miniGradeCCount: TextView

    private var eggCurrentFilter: String = "All Time"
    private val eggFilters = arrayOf("All Time", "Today", "Weekly", "Monthly", "Yearly", "Custom")

    private var eggSelectedMonth = Calendar.getInstance().get(Calendar.MONTH)
    private var eggSelectedMonthYear = Calendar.getInstance().get(Calendar.YEAR)
    private var eggSelectedYear = Calendar.getInstance().get(Calendar.YEAR)
    private var eggCustomStartDate: String? = null
    private var eggCustomEndDate: String? = null
    private var eggSelectedWeekStartDate: String? = null

    private data class DailyEggData(val total: Int, val gradeA: Int, val gradeB: Int, val gradeC: Int)

    companion object {
        private val DATE_KEY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val COLOR_GRADE_A = Color.parseColor("#355E1A")
        private val COLOR_GRADE_B = Color.parseColor("#7C3AED")
        private val COLOR_GRADE_C = Color.parseColor("#F4B400")
        private val COLOR_NO_DATA = Color.parseColor("#D1D5DB")
    }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && photoUri != null) {
                Toast.makeText(this, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        accountManager = AccountManager(this)
        username = intent.getStringExtra("username") ?: accountManager.getCurrentUsername() ?: "User"
        userRole = accountManager.getRole(username)

        // ----------------------------
        // NEW: Force refresh token & sync role
        // ----------------------------
        refreshTokenAndSyncRole()

        drawerLayout = findViewById(R.id.drawerLayout)
        notificationBadge = findViewById(R.id.notificationBadge)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupNavigation()
        setupServerTime()
        setupSwipeToRefresh()
        setupEggOverviewWidget()

        // Show personalized loading on entry
        showLoading(getString(R.string.syncing_farm_stats)) {
            fetchUserData()
            setupButtons()
            applyEntranceAnimations()
            checkAdminAccess()
            setupAlertListener()

            // Handle deep link from notification
            if (intent.getBooleanExtra("OPEN_ALERTS", false)) {
                intent.removeExtra("OPEN_ALERTS") // Prevent re-triggering on rotation/re-entry
                startActivity(Intent(this, AlertsActivity::class.java).putExtra("username", username))
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("OPEN_ALERTS", false) == true) {
            showLoading("Opening Alerts...") {
                intent.removeExtra("OPEN_ALERTS")
                startActivity(Intent(this, AlertsActivity::class.java).putExtra("username", username))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationBadge()
    }

    // ----------------------------
    // NEW: Pull-to-refresh setup
    // ----------------------------
    private fun setupSwipeToRefresh() {
        swipeRefreshLayout.setColorSchemeResources(R.color.dark_green)
        swipeRefreshLayout.setOnRefreshListener {
            refreshDashboardData()
        }
    }

    private fun refreshDashboardData() {
        refreshTokenAndSyncRole()
        fetchUserData()
        updateNotificationBadge()
        updateEggWidget() // egg_collections listener stays live, this just re-renders with latest cached data

        // Give Firestore listeners a moment to settle, then stop the spinner.
        // For a more precise stop, move this into the success/failure callbacks
        // of refreshTokenAndSyncRole()/fetchUserData() instead.
        handler.postDelayed({
            swipeRefreshLayout.isRefreshing = false
        }, 800)
    }

    private fun updateNotificationBadge() {
        val unreadCount = GlobalData.getUnreadCount()
        if (unreadCount > 0) {
            notificationBadge.visibility = View.VISIBLE
            notificationBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
        } else {
            notificationBadge.visibility = View.GONE
        }
    }

    private fun setupAlertListener() {
        val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault())
        alertsListener = FarmRepository.listenToAlerts { alerts ->
            var newAlertAdded = false
            for (alert in alerts) {
                val message = alert["message"] as? String ?: continue
                val type = alert["type"] as? String ?: "Inventory"

                val firestoreTs = alert["timestamp"]
                val timestampStr = when (firestoreTs) {
                    is com.google.firebase.Timestamp -> sdf.format(firestoreTs.toDate())
                    is String -> firestoreTs
                    else -> "Just now"
                }

                // Dedup by message only — timestamp strings can differ by seconds
                // between devices and should not be used as part of the identity check.
                val existing = GlobalData.getAlerts().find { it.message == message }
                if (existing == null) {
                    GlobalData.addAlert(message, timestampStr, type)
                    newAlertAdded = true
                }
            }
            if (newAlertAdded) {
                runOnUiThread { updateNotificationBadge() }
            }
        }
    }

    // ----------------------------
    // NEW FUNCTION: Refresh Firebase token + sync role
    // ----------------------------
    private fun refreshTokenAndSyncRole() {
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.addOnSuccessListener { result ->
            val token = result.token
            // Optional: log token for debugging
            // println("Refreshed token: $token")

            // Fetch user role from Firestore and update local cache
            val currentEmail = accountManager.getCurrentUsername()?.lowercase() ?: return@addOnSuccessListener
            FirebaseFirestore.getInstance().collection("user_access").document(currentEmail)
                .get().addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        userRole = doc.getString("role") ?: "staff"
                        accountManager.updateCachedRole(currentEmail, userRole)
                        checkAdminAccess()
                    }
                }
        }?.addOnFailureListener { e ->
            Toast.makeText(this, getString(R.string.token_refresh_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchUserData() {
        val currentEmail = accountManager.getCurrentUsername() ?: return
        FirebaseFirestore.getInstance().collection("user_access").document(currentEmail).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    displayName = doc.getString("name") ?: "User"
                    updateWelcomeMessage()
                }
            }
    }

    private fun checkAdminAccess() {
        if (!isAdmin()) {
            // Logic for staff restrictions
        }
    }

    private fun isAdmin(): Boolean {
        return RoleManager(userRole).canViewAdminPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        roleListener?.remove()
        alertsListener?.remove()
        eggCollectionsListener?.let { eggCollectionsRef.removeEventListener(it) }
        if (::updateTimeRunnable.isInitialized) {
            handler.removeCallbacks(updateTimeRunnable)
        }
    }

    private fun handleCameraClick() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> openCamera()
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFile = File.createTempFile("PHOTO_${timestamp}_", ".jpg", cacheDir)
            photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            val fallbackIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (fallbackIntent.resolveActivity(packageManager) != null) {
                startActivity(fallbackIntent)
            } else {
                Toast.makeText(this, getString(R.string.no_camera_app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyEntranceAnimations() {
        val fadeIn  = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        findViewById<View>(R.id.welcomeCard)?.startAnimation(fadeIn)
        findViewById<View>(R.id.aiCard)?.startAnimation(slideUp)
        findViewById<View>(R.id.shortcutsGrid)?.startAnimation(slideUp)
    }
    fun showLoading(label: String, action: () -> Unit) {
        val loadingLayout = findViewById<View>(R.id.loadingLayout)
        val loadingIcon   = findViewById<View>(R.id.loadingIcon)
        val statusText    = findViewById<TextView>(R.id.loadingStatusText)
        val progressBar   = findViewById<ProgressBar>(R.id.loadingProgressBar)
        val percentText   = findViewById<TextView>(R.id.loadingPercentageText)

        if (loadingLayout != null && loadingIcon != null) {
            statusText?.text = label
            loadingLayout.visibility = View.VISIBLE
            val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
            loadingIcon.startAnimation(jump)

            var progress = 0
            val progressHandler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    if (progress <= 100) {
                        progressBar?.progress = progress
                        percentText?.text = "${progress}%"
                        progress += 10
                        progressHandler.postDelayed(this, 50)
                    } else {
                        loadingLayout.visibility = View.GONE
                        loadingIcon.clearAnimation()
                        action()
                    }
                }
            }
            progressHandler.post(runnable)
        } else {
            action()
        }
    }

    private fun setupNavigation() {
        try {
            NavigationHelper.setupSideMenu(this, drawerLayout)
            findViewById<android.view.View>(R.id.imageButton)?.setOnClickListener {
                drawerLayout.openDrawer(GravityCompat.START)
            }
            NavigationHelper.setupBottomNavigation(this)
            NavigationHelper.setupNotificationButton(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupServerTime() {
        val serverTimeText = findViewById<TextView?>(R.id.serverTimeText)
        val sdf = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault())
        updateTimeRunnable = object : Runnable {
            override fun run() {
                serverTimeText?.text = sdf.format(Calendar.getInstance().time)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateTimeRunnable)
    }

    private fun updateWelcomeMessage() {
        findViewById<TextView?>(R.id.welcome_text)?.text = getString(R.string.welcome_message_dynamic, displayName)
    }

    private fun setupButtons() {
        findViewById<LinearLayout?>(R.id.analyticsButton)?.setOnClickListener {
            showLoading(getString(R.string.generating_reports)) {
                startActivity(Intent(this, AnalyticsActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.widget.ImageButton?>(R.id.scheduleButton1)?.setOnClickListener {
            showLoading(getString(R.string.fetching_tasks)) {
                startActivity(Intent(this, ScheduleActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.feedInventoryButton)?.setOnClickListener {
            showLoading(getString(R.string.checking_inventory)) {
                startActivity(Intent(this, FeedInventoryActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.eggCountButton)?.setOnClickListener {
            showLoading(getString(R.string.loading_egg_records)) {
                startActivity(Intent(this, EggCountActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.water_level)?.setOnClickListener {
            showLoading(getString(R.string.reading_sensors)) {
                startActivity(Intent(this, WaterSensorActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.tasksButton)?.setOnClickListener {
            showLoading(getString(R.string.fetching_tasks)) {
                startActivity(Intent(this, ScheduleActivity::class.java).putExtra("username", username))
            }
        }

        // Setup AI Smart Tip Shuffle
        val aiCard = findViewById<View>(R.id.aiCard)
        val tips = resources.getStringArray(R.array.smart_tips).toList()

        // remove: showRandomTip(), aiCard random-tip click listener, tips array usage

        aiCard?.setOnClickListener {
            startActivity(Intent(this, ChatBotActivity::class.java).putExtra("username", username))
        }
    }

    // ============================================================
    // Egg Grade Overview mini widget
    // ============================================================

    private fun setupEggOverviewWidget() {
        eggCollectionsRef = FirebaseDatabase.getInstance().reference.child("egg_collections")

        miniGradePieChart = findViewById(R.id.miniGradePieChart)
        eggFilterSpinner = findViewById(R.id.eggFilterSpinner)
        eggFilterChoiceCard = findViewById(R.id.eggFilterChoiceCard)
        eggFilterChoiceButton = findViewById(R.id.eggFilterChoiceButton)
        eggFilterChoiceText = findViewById(R.id.eggFilterChoiceText)
        eggWidgetTotalText = findViewById(R.id.eggWidgetTotalText)
        miniGradeAProgress = findViewById(R.id.miniGradeAProgress)
        miniGradeBProgress = findViewById(R.id.miniGradeBProgress)
        miniGradeCProgress = findViewById(R.id.miniGradeCProgress)
        miniGradeACount = findViewById(R.id.miniGradeACount)
        miniGradeBCount = findViewById(R.id.miniGradeBCount)
        miniGradeCCount = findViewById(R.id.miniGradeCCount)

        setupMiniPieChart()
        setupEggFilterSpinner()
        attachEggRealtimeListener()
    }

    private fun setupMiniPieChart() {
        miniGradePieChart.setUsePercentValues(true)
        miniGradePieChart.description.isEnabled = false
        miniGradePieChart.setExtraOffsets(4f, 4f, 4f, 4f)
        miniGradePieChart.setDrawHoleEnabled(true)
        miniGradePieChart.setHoleColor(Color.WHITE)
        miniGradePieChart.transparentCircleRadius = 40f
        miniGradePieChart.legend.isEnabled = false
        miniGradePieChart.setDrawEntryLabels(false)
    }

    private fun setupEggFilterSpinner() {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, eggFilters) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                view.setBackgroundColor(Color.WHITE)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                view.setBackgroundColor(Color.WHITE)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        eggFilterSpinner.adapter = adapter
        eggFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                eggCurrentFilter = eggFilters[position]
                updateEggFilterChoiceVisibility()
                if (eggCurrentFilter == "Custom" && (eggCustomStartDate == null || eggCustomEndDate == null)) {
                    showEggCustomRangePicker()
                } else {
                    updateEggWidget()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        eggFilterChoiceButton.setOnClickListener {
            when (eggCurrentFilter) {
                "Weekly" -> showEggWeekPicker()
                "Monthly" -> showEggMonthYearPicker()
                "Yearly" -> showEggYearPicker()
                "Custom" -> showEggCustomRangePicker()
            }
        }

        updateEggFilterChoiceVisibility()
    }

    /** Shows/hides the period-choice row and keeps its label in sync with the current filter. */
    private fun updateEggFilterChoiceVisibility() {
        when (eggCurrentFilter) {
            "Weekly" -> {
                eggFilterChoiceCard.visibility = View.VISIBLE
                eggFilterChoiceText.text = eggSelectedWeekStartDate?.let {
                    "${displayEggDate(it)}  —  ${displayEggDate(weekEndDate(it))}"
                } ?: "This Week (last 7 days)"
            }
            "Monthly" -> {
                eggFilterChoiceCard.visibility = View.VISIBLE
                eggFilterChoiceText.text = monthYearLabel(eggSelectedMonth, eggSelectedMonthYear)
            }
            "Yearly" -> {
                eggFilterChoiceCard.visibility = View.VISIBLE
                eggFilterChoiceText.text = eggSelectedYear.toString()
            }
            "Custom" -> {
                eggFilterChoiceCard.visibility = View.VISIBLE
                eggFilterChoiceText.text = if (eggCustomStartDate != null && eggCustomEndDate != null) {
                    "${displayEggDate(eggCustomStartDate!!)}  —  ${displayEggDate(eggCustomEndDate!!)}"
                } else {
                    "Select date range"
                }
            }
            else -> eggFilterChoiceCard.visibility = View.GONE
        }
    }

    private fun monthYearLabel(month: Int, year: Int): String {
        val monthNames = DateFormatSymbols(Locale.getDefault()).months
        return "${monthNames[month]} $year"
    }

    private fun displayEggDate(yyyyMmDd: String): String {
        return try {
            val d = DATE_KEY_FORMAT.parse(yyyyMmDd)
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d!!)
        } catch (e: ParseException) {
            yyyyMmDd
        }
    }

    /** Given a week-start (yyyy-MM-dd, Sunday), returns the Saturday 6 days later. */
    private fun weekEndDate(weekStartYyyyMmDd: String): String {
        return try {
            val c = Calendar.getInstance()
            c.time = DATE_KEY_FORMAT.parse(weekStartYyyyMmDd)!!
            c.add(Calendar.DAY_OF_YEAR, 6)
            DATE_KEY_FORMAT.format(c.time)
        } catch (e: ParseException) {
            weekStartYyyyMmDd
        }
    }

    /** Week picker for the "Weekly" filter — snaps the picked day back to that week's Sunday. */
    private fun showEggWeekPicker() {
        val cal = Calendar.getInstance()
        eggSelectedWeekStartDate?.let {
            try {
                cal.time = DATE_KEY_FORMAT.parse(it)!!
            } catch (e: ParseException) {
                // keep default cal
            }
        }

        val dialog = DatePickerDialog(this, { _, year, month, day ->
            val picked = Calendar.getInstance()
            picked.set(year, month, day, 0, 0, 0)
            val dayOfWeek = picked.get(Calendar.DAY_OF_WEEK) // 1=Sunday ... 7=Saturday
            picked.add(Calendar.DAY_OF_YEAR, -(dayOfWeek - 1))
            eggSelectedWeekStartDate = DATE_KEY_FORMAT.format(picked.time)
            updateEggFilterChoiceVisibility()
            updateEggWidget()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        dialog.setTitle("Pick Any Day in the Week")
        dialog.show()
    }

    /** Month + year picker for the "Monthly" filter. */
    private fun showEggMonthYearPicker() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        val pad = dpToPx(16)
        layout.setPadding(pad, pad, pad, pad)

        val monthPicker = NumberPicker(this)
        val monthNames = DateFormatSymbols(Locale.getDefault()).months
        val twelveMonths = monthNames.copyOfRange(0, 12)
        monthPicker.minValue = 0
        monthPicker.maxValue = 11
        monthPicker.displayedValues = twelveMonths
        monthPicker.value = eggSelectedMonth
        monthPicker.wrapSelectorWheel = true

        val yearPicker = NumberPicker(this)
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        yearPicker.minValue = nowYear - 10
        yearPicker.maxValue = nowYear
        yearPicker.value = eggSelectedMonthYear
        yearPicker.wrapSelectorWheel = false

        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        layout.addView(monthPicker, params)
        layout.addView(yearPicker, params)

        AlertDialog.Builder(this)
            .setTitle("Select Month")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                eggSelectedMonth = monthPicker.value
                eggSelectedMonthYear = yearPicker.value
                updateEggFilterChoiceVisibility()
                updateEggWidget()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Year picker for the "Yearly" filter. */
    private fun showEggYearPicker() {
        val yearPicker = NumberPicker(this)
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        yearPicker.minValue = nowYear - 15
        yearPicker.maxValue = nowYear
        yearPicker.value = eggSelectedYear
        yearPicker.wrapSelectorWheel = false

        val container = FrameLayout(this)
        val fp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        fp.gravity = Gravity.CENTER
        val pad = dpToPx(16)
        container.setPadding(pad, pad, pad, pad)
        container.addView(yearPicker, fp)

        AlertDialog.Builder(this)
            .setTitle("Select Year")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                eggSelectedYear = yearPicker.value
                updateEggFilterChoiceVisibility()
                updateEggWidget()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Start + end date pickers for the "Custom" filter. */
    private fun showEggCustomRangePicker() {
        val startCal = Calendar.getInstance()
        eggCustomStartDate?.let {
            try {
                startCal.time = DATE_KEY_FORMAT.parse(it)!!
            } catch (e: ParseException) {
                // keep default startCal
            }
        }

        val startDialog = DatePickerDialog(this, { _, year, month, day ->
            val chosenStart = Calendar.getInstance()
            chosenStart.set(year, month, day, 0, 0, 0)
            val start = DATE_KEY_FORMAT.format(chosenStart.time)

            var endCal = Calendar.getInstance()
            eggCustomEndDate?.let {
                try {
                    endCal.time = DATE_KEY_FORMAT.parse(it)!!
                } catch (e: ParseException) {
                    // keep default endCal
                }
            }
            if (endCal.timeInMillis < chosenStart.timeInMillis) {
                endCal = chosenStart.clone() as Calendar
            }

            val endDialog = DatePickerDialog(this, { _, year2, month2, day2 ->
                val chosenEnd = Calendar.getInstance()
                chosenEnd.set(year2, month2, day2, 0, 0, 0)
                val end = DATE_KEY_FORMAT.format(chosenEnd.time)

                eggCustomStartDate = start
                eggCustomEndDate = end
                updateEggFilterChoiceVisibility()
                updateEggWidget()
            }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH))
            endDialog.setTitle("Select End Date")
            endDialog.datePicker.minDate = chosenStart.timeInMillis
            endDialog.show()
        }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH))
        startDialog.setTitle("Select Start Date")
        startDialog.show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun attachEggRealtimeListener() {
        if (eggListenerAttached) return // already listening — avoid duplicate Firebase listeners
        eggListenerAttached = true

        eggCollectionsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newData = TreeMap<String, DailyEggData>()
                for (child in snapshot.children) {
                    val dateKey = child.key ?: continue
                    val total = child.child("total").getValue(Long::class.java) ?: 0L
                    val gA = child.child("gradeA").getValue(Long::class.java) ?: 0L
                    val gB = child.child("gradeB").getValue(Long::class.java) ?: 0L
                    val gC = child.child("gradeC").getValue(Long::class.java) ?: 0L
                    newData[dateKey] = DailyEggData(total.toInt(), gA.toInt(), gB.toInt(), gC.toInt())
                }
                eggAllData = newData
                updateEggWidget()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@DashboardActivity, "Database Connection Error", Toast.LENGTH_SHORT).show()
            }
        }
        eggCollectionsRef.addValueEventListener(eggCollectionsListener!!)
    }

    /** Returns the subset of eggAllData matching the currently selected filter. */
    private fun getFilteredEggData(): Map<String, DailyEggData> {
        val filtered = TreeMap<String, DailyEggData>()

        val cal = Calendar.getInstance()
        val today = DATE_KEY_FORMAT.format(cal.time)

        val cal7 = Calendar.getInstance()
        cal7.add(Calendar.DAY_OF_YEAR, -6) // today + previous 6 days = default 7-day window
        val sevenDaysAgo = DATE_KEY_FORMAT.format(cal7.time)

        val weekStart = eggSelectedWeekStartDate ?: sevenDaysAgo
        val weekEnd = eggSelectedWeekStartDate?.let { weekEndDate(it) } ?: today

        val monthlyPrefix = String.format(Locale.getDefault(), "%04d-%02d", eggSelectedMonthYear, eggSelectedMonth + 1)
        val yearlyPrefix = String.format(Locale.getDefault(), "%04d", eggSelectedYear)

        for ((key, value) in eggAllData) {
            val include = when (eggCurrentFilter) {
                "Today" -> key == today
                "Weekly" -> key >= weekStart && key <= weekEnd
                "Monthly" -> key.startsWith(monthlyPrefix)
                "Yearly" -> key.startsWith(yearlyPrefix)
                "Custom" -> eggCustomStartDate != null && eggCustomEndDate != null &&
                        key >= eggCustomStartDate!! && key <= eggCustomEndDate!!
                else -> true // All Time
            }
            if (include) filtered[key] = value
        }
        return filtered
    }

    private fun updateEggWidget() {
        var total = 0
        var a = 0
        var b = 0
        var c = 0

        for (data in getFilteredEggData().values) {
            total += data.total
            a += data.gradeA
            b += data.gradeB
            c += data.gradeC
        }

        eggWidgetTotalText.text = "Total No. of Eggs: $total"

        val entries = mutableListOf<PieEntry>()
        val sliceColors = mutableListOf<Int>()

        if (total > 0) {
            if (a > 0) {
                entries.add(PieEntry(a.toFloat(), "Grade A"))
                sliceColors.add(COLOR_GRADE_A)
            }
            if (b > 0) {
                entries.add(PieEntry(b.toFloat(), "Grade B"))
                sliceColors.add(COLOR_GRADE_B)
            }
            if (c > 0) {
                entries.add(PieEntry(c.toFloat(), "Grade C"))
                sliceColors.add(COLOR_GRADE_C)
            }
        } else {
            entries.add(PieEntry(1f, "No Data"))
            sliceColors.add(COLOR_NO_DATA)
        }

        val dataSet = PieDataSet(entries, "")
        dataSet.setColors(sliceColors)
        dataSet.sliceSpace = 2f
        dataSet.setDrawValues(false) // widget is small — counts/percentages are shown in the breakdown rows instead

        val pieData = PieData(dataSet)
        miniGradePieChart.data = pieData
        miniGradePieChart.invalidate()
        miniGradePieChart.animateY(600)

        val pctA = if (total > 0) (a * 100 / total) else 0
        val pctB = if (total > 0) (b * 100 / total) else 0
        val pctC = if (total > 0) (c * 100 / total) else 0

        miniGradeACount.text = "$a ($pctA%)"
        miniGradeBCount.text = "$b ($pctB%)"
        miniGradeCCount.text = "$c ($pctC%)"

        miniGradeAProgress.progress = pctA
        miniGradeBProgress.progress = pctB
        miniGradeCProgress.progress = pctC
    }

}