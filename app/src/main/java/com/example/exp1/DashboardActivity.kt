package com.example.exp1

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import android.view.LayoutInflater
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
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

    private lateinit var widgetPager: ViewPager2
    private lateinit var upcomingTasksContainer: LinearLayout
    private lateinit var upcomingTasksEmptyText: TextView
    private val staffNameCache = mutableMapOf<String, String>()   // ← add this line
    private var upcomingTasksListener: ListenerRegistration? = null
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

    // FIX: notification permission used to only be requested inside ScheduleActivity's
    // onCreate(). On Android 13+ that meant POST_NOTIFICATIONS was never granted for any
    // user who never happened to open the Schedule screen — so inventory, water-level, and
    // schedule notifications could all silently fail to post, regardless of app state,
    // even though the FCM push / BootReceiver / AlarmManager paths that trigger them were
    // firing correctly. DashboardActivity is the screen that stays alive right after every
    // login path (Google sign-in, manual login, cached session, offline mode), so the
    // permission is now requested here instead, app-wide, before the user ever needs to
    // visit Schedule.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        accountManager = AccountManager(this)
        username = intent.getStringExtra("username") ?: accountManager.getCurrentUsername() ?: "User"
        userRole = accountManager.getRole(username)

        requestNotificationPermissionIfNeeded()

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
        setupWidgetPager()

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
        upcomingTasksListener?.remove()
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
    // Egg Grade and upcoming task Overview mini widget
    // ============================================================
    private fun setupWidgetPager() {
        widgetPager = findViewById(R.id.dashboardWidgetPager)

        val adapter = DashboardWidgetPagerAdapter { position, view ->
            when (position) {
                0 -> bindEggOverviewWidget(view)
                1 -> bindUpcomingTasksWidget(view)
            }
        }
        widgetPager.adapter = adapter
        widgetPager.offscreenPageLimit = 1

        val dot0 = findViewById<View>(R.id.dotWidget0)
        val dot1 = findViewById<View>(R.id.dotWidget1)
        widgetPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dot0.setBackgroundResource(if (position == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
                dot1.setBackgroundResource(if (position == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)
                updatePagerHeightForCurrentPage()
            }
        })

        // ViewPager2 wraps an internal RecyclerView as its only child.
        // Whenever a page view attaches, wait for it to finish laying out, then
        // resize the pager to match its natural content height.
        val recyclerView = widgetPager.getChildAt(0) as? RecyclerView
        recyclerView?.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        updatePagerHeightForCurrentPage()
                    }
                })
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun updatePagerHeightForCurrentPage() {
        val recyclerView = widgetPager.getChildAt(0) as? RecyclerView ?: return
        val currentChild = recyclerView.findViewHolderForAdapterPosition(widgetPager.currentItem)?.itemView ?: return

        currentChild.measure(
            View.MeasureSpec.makeMeasureSpec(widgetPager.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val height = currentChild.measuredHeight
        val params = widgetPager.layoutParams
        if (params.height != height) {
            params.height = height
            widgetPager.layoutParams = params
        }
    }

    private fun bindEggOverviewWidget(root: View) {
        eggCollectionsRef = FirebaseDatabase.getInstance().reference.child("egg_collections")

        miniGradePieChart = root.findViewById(R.id.miniGradePieChart)
        eggFilterSpinner = root.findViewById(R.id.eggFilterSpinner)
        eggFilterChoiceCard = root.findViewById(R.id.eggFilterChoiceCard)
        eggFilterChoiceButton = root.findViewById(R.id.eggFilterChoiceButton)
        eggFilterChoiceText = root.findViewById(R.id.eggFilterChoiceText)
        eggWidgetTotalText = root.findViewById(R.id.eggWidgetTotalText)
        miniGradeAProgress = root.findViewById(R.id.miniGradeAProgress)
        miniGradeBProgress = root.findViewById(R.id.miniGradeBProgress)
        miniGradeCProgress = root.findViewById(R.id.miniGradeCProgress)
        miniGradeACount = root.findViewById(R.id.miniGradeACount)
        miniGradeBCount = root.findViewById(R.id.miniGradeBCount)
        miniGradeCCount = root.findViewById(R.id.miniGradeCCount)

        setupMiniPieChart()
        setupEggFilterSpinner()
        attachEggRealtimeListener()
    }

    private fun bindUpcomingTasksWidget(root: View) {
        upcomingTasksContainer = root.findViewById(R.id.upcomingTasksContainer)
        upcomingTasksEmptyText = root.findViewById(R.id.upcomingTasksEmptyText)
        attachUpcomingTasksListener()
    }

    private fun attachUpcomingTasksListener() {
        upcomingTasksListener?.remove()
        upcomingTasksListener = FirebaseFirestore.getInstance()
            .collection("farm_data").document("shared").collection("tasks")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                renderUpcomingTasks(snapshots.documents)
            }
    }

    private fun renderUpcomingTasks(docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        if (!::upcomingTasksContainer.isInitialized) return

        val isOwner = RoleManager(userRole).isOwner
        val currentEmail = accountManager.getCurrentUsername()?.let { accountManager.getEmail(it) }

        val cal = Calendar.getInstance()
        val todayY = cal.get(Calendar.YEAR); val todayM = cal.get(Calendar.MONTH); val todayD = cal.get(Calendar.DAY_OF_MONTH)

        data class Row(val title: String, val category: String, val time: String, val status: String,
                       val assignedTo: List<String>, val assignedBy: String?,
                       val year: Int, val month: Int, val day: Int)
        val rows = mutableListOf<Row>()

        for (doc in docs) {
            val status = doc.getString("status") ?: "Pending"
            if (status == "Done") continue

            val year = doc.getLong("year")?.toInt() ?: continue
            val month = doc.getLong("month")?.toInt() ?: continue
            val day = doc.getLong("day")?.toInt() ?: continue

            val isPastDay = year < todayY || (year == todayY && month < todayM) ||
                    (year == todayY && month == todayM && day < todayD)
            if (isPastDay) continue

            val assignedTo = (doc.get("assignedTo") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            if (!isOwner) {
                if (currentEmail == null || assignedTo.none { it.equals(currentEmail, ignoreCase = true) }) continue
            }

            rows.add(Row(doc.getString("title") ?: "Untitled", doc.getString("category") ?: "",
                doc.getString("time") ?: "", status, assignedTo, doc.getString("assignedBy"),
                year, month, day))
        }

        rows.sortWith(compareBy({ it.year }, { it.month }, { it.day }, { it.time }))
        val topRows = rows.take(5)

        upcomingTasksContainer.removeAllViews()
        upcomingTasksEmptyText.visibility = if (topRows.isEmpty()) View.VISIBLE else View.GONE

        val monthNames = DateFormatSymbols(Locale.getDefault()).months
        for ((index, r) in topRows.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(8), 0, dpToPx(8))
            }

            val (iconRes, iconColor, iconBgColor) = categoryVisual(r.category)
            val iconCircle = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(iconBgColor)
                }
            }
            iconCircle.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(16), dpToPx(16), Gravity.CENTER)
                setImageResource(iconRes)
                setColorFilter(iconColor)
            })
            row.addView(iconCircle)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(10)
                }
            }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this).apply {
                text = r.title
                setTextColor(Color.parseColor("#1F2937"))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            val (pillBg, pillText) = statusColors(r.status)
            titleRow.addView(TextView(this).apply {
                text = r.status
                setTextColor(pillText)
                textSize = 9f
                setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(pillBg)
                }
            })
            textCol.addView(titleRow)

            textCol.addView(TextView(this).apply {
                text = "${r.category}  ·  ${r.day} ${monthNames[r.month]}  ${r.time}"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 10f
                setPadding(0, dpToPx(3), 0, 0)
            })

            val hasAssignedBy = !r.assignedBy.isNullOrBlank()
            val assignText = "To: ${resolveAssignedToLabel(r.assignedTo)}" +
                    if (hasAssignedBy) "   ·   By: ${resolveAssignedByLabel(r.assignedBy)}" else ""
            textCol.addView(TextView(this).apply {
                text = assignText
                setTextColor(Color.parseColor("#16A34A"))
                textSize = 9.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dpToPx(3), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            row.addView(textCol)
            upcomingTasksContainer.addView(row)

            if (index < topRows.lastIndex) {
                upcomingTasksContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
                    setBackgroundColor(Color.parseColor("#F3F4F6"))
                })
            }
        }

        if (widgetPager.currentItem == 1) {
            updatePagerHeightForCurrentPage()
        }
    }

    /** Icon + tint + background color per category, copied 1:1 from ScheduleActivity.getCategoryIconStyle(). */
    private fun categoryVisual(category: String): Triple<Int, Int, Int> {
        val c = category.trim()
        return when {
            c.equals("Watering", ignoreCase = true) ->
                Triple(R.drawable.ic_water_level, Color.parseColor("#0284C7"), Color.parseColor("#E0F2FE"))
            c.equals("Feeding", ignoreCase = true) ->
                Triple(R.drawable.ic_shopping_bag, Color.parseColor("#EA580C"), Color.parseColor("#FFEDD5"))
            c.equals("Cleaning", ignoreCase = true) ->
                Triple(R.drawable.ic_check_circle, Color.parseColor("#059669"), Color.parseColor("#D1FAE5"))
            c.equals("Egg Collection", ignoreCase = true) ->
                Triple(R.drawable.lc_egg, Color.parseColor("#D97706"), Color.parseColor("#FEF3C7"))
            c.equals("Lighting", ignoreCase = true) ->
                Triple(R.drawable.ic_alert_circle, Color.parseColor("#7C3AED"), Color.parseColor("#EDE9FE"))
            c.equals("Health Check", ignoreCase = true) ->
                Triple(R.drawable.ic_alert_triangle, Color.parseColor("#DC2626"), Color.parseColor("#FEE2E2"))
            else ->
                Triple(R.drawable.ic_calendar, Color.parseColor("#6B7280"), Color.parseColor("#F3F4F6"))
        }
    }

    /** Status pill background + text color, copied 1:1 from ScheduleActivity.updateTasksUI(). */
    private fun statusColors(status: String): Pair<Int, Int> {
        return when (status) {
            "Missed" -> Color.parseColor("#FEE2E2") to Color.parseColor("#DC2626")
            "Done" -> Color.parseColor("#DCFCE7") to Color.parseColor("#16A34A")
            "Ongoing" -> Color.parseColor("#DBEAFE") to Color.parseColor("#2563EB")
            else -> Color.parseColor("#FFEDD5") to Color.parseColor("#EA580C") // Pending
        }
    }
    /** Resolves assignedTo emails to display names, same source/cache pattern as ScheduleActivity. */
    private fun resolveAssignedToLabel(assignedTo: List<String>): String {
        if (assignedTo.isEmpty()) return "Owner"
        val labels = mutableListOf<String>()
        for (rawEmail in assignedTo) {
            val email = rawEmail.trim()
            if (email.isEmpty()) continue
            val cached = staffNameCache[email]
            if (cached != null) {
                labels.add(cached)
            } else {
                labels.add(email) // show email until the async lookup resolves
                FirebaseFirestore.getInstance().collection("user_access").document(email).get()
                    .addOnSuccessListener { doc ->
                        val name = doc.getString("name")
                        staffNameCache[email] = if (!name.isNullOrEmpty()) name else email
                        // Re-render so the resolved name replaces the raw email.
                        attachUpcomingTasksListener()
                    }
                    .addOnFailureListener { staffNameCache[email] = email }
            }
        }
        return if (labels.isEmpty()) "Owner" else labels.joinToString(", ")
    }

    /** Resolves the assignedBy email to a display name, same cache as above. */
    private fun resolveAssignedByLabel(assignedBy: String?): String {
        if (assignedBy.isNullOrBlank()) return "Owner"
        val email = assignedBy.trim()
        val cached = staffNameCache[email]
        if (cached != null) return cached

        staffNameCache[email] = email // show email until the async lookup resolves
        FirebaseFirestore.getInstance().collection("user_access").document(email).get()
            .addOnSuccessListener { doc ->
                val name = doc.getString("name")
                staffNameCache[email] = if (!name.isNullOrEmpty()) name else email
                attachUpcomingTasksListener()
            }
            .addOnFailureListener { staffNameCache[email] = email }
        return email
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

    private class DashboardWidgetPagerAdapter(
        private val onBind: (position: Int, view: View) -> Unit
    ) : RecyclerView.Adapter<DashboardWidgetPagerAdapter.WidgetViewHolder>() {

        class WidgetViewHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = 2

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): WidgetViewHolder {
            val layoutRes = if (viewType == 0) R.layout.widget_egg_overview else R.layout.widget_upcoming_tasks
            val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
            return WidgetViewHolder(view)
        }

        override fun onBindViewHolder(holder: WidgetViewHolder, position: Int) {
            onBind(position, holder.itemView)
        }
    }
}