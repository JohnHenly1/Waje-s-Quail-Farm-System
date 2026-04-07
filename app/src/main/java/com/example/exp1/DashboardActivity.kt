package com.example.exp1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateTimeRunnable: Runnable

    private var username: String = "User"
    private var displayName: String = "User"
    private lateinit var accountManager: AccountManager
    private var userRole: String = "staff"

    private var photoUri: Uri? = null

    private var eggListener: ValueEventListener? = null
    private var feedListener: ListenerRegistration? = null
    private lateinit var eggsTodayText: TextView
    private lateinit var feedRemainingText: TextView

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && photoUri != null) {
                Toast.makeText(this, "Photo saved!", Toast.LENGTH_SHORT).show()
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        accountManager = AccountManager(this)
        username = intent.getStringExtra("username") ?: accountManager.getCurrentUsername() ?: "User"
        userRole = accountManager.getRole(username)

        drawerLayout = findViewById(R.id.drawerLayout)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupNavigation()
        setupServerTime()
        
        // Show personalized loading on entry
        showLoading("Syncing Farm Stats...") {
            fetchUserData()
            setupButtons()
            setupStats()
            applyEntranceAnimations()
            checkAdminAccess()
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
        return userRole == "owner" || userRole == "backup_owner"
    }

    override fun onDestroy() {
        super.onDestroy()
        eggListener?.let { FirebaseDatabase.getInstance().getReference("egg_collections").removeEventListener(it) }
        feedListener?.remove()
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
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyEntranceAnimations() {
        val fadeIn  = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        findViewById<View>(R.id.welcomeCard)?.startAnimation(fadeIn)
        findViewById<View>(R.id.aiCard)?.startAnimation(slideUp)
        findViewById<View>(R.id.shortcutsGrid)?.startAnimation(slideUp)
        findViewById<View>(R.id.statsGrid)?.startAnimation(slideUp)
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
        findViewById<TextView?>(R.id.welcome_text)?.text = "Hi, $displayName!"
    }

    private fun setupButtons() {
        findViewById<LinearLayout?>(R.id.CameraButton)?.setOnClickListener {
            handleCameraClick()
        }

        findViewById<LinearLayout?>(R.id.analyticsButton)?.setOnClickListener {
            showLoading("Generating Reports...") {
                startActivity(Intent(this, AnalyticsActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.widget.ImageButton?>(R.id.scheduleButton1)?.setOnClickListener {
            showLoading("Fetching Tasks...") {
                startActivity(Intent(this, ScheduleActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.feedInventoryButton)?.setOnClickListener {
            showLoading("Checking Inventory...") {
                startActivity(Intent(this, FeedInventoryActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.eggCountButton)?.setOnClickListener {
            showLoading("Loading Egg Records...") {
                startActivity(Intent(this, EggCountActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.water_level)?.setOnClickListener {
            showLoading("Reading Sensors...") {
                startActivity(Intent(this, WaterSensorActivity::class.java).putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.tasksButton)?.setOnClickListener {
            showLoading("Fetching Tasks...") {
                startActivity(Intent(this, ScheduleActivity::class.java).putExtra("username", username))
            }
        }
    }

    private fun setupStats() {
        eggsTodayText = findViewById(R.id.total_eggs_value)
        feedRemainingText = findViewById(R.id.feed_remaining_value)

        // Setup egg listener
        val dbDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
        val eggRef = FirebaseDatabase.getInstance().getReference("egg_collections").child(dbDate)
        eggListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val total = snapshot.child("total").getValue(Int::class.java) ?: 0
                eggsTodayText.text = total.toString()
            }
            override fun onCancelled(error: DatabaseError) {
                eggsTodayText.text = "0"
            }
        }
        eggRef.addValueEventListener(eggListener!!)

        // Setup feed listener
        val feedCol = FirebaseFirestore.getInstance().collection("farm_data").document("shared").collection("feed")
        feedListener = feedCol.addSnapshotListener { querySnapshot, e ->
            if (e != null) {
                feedRemainingText.text = "0"
                return@addSnapshotListener
            }
            val docs = querySnapshot?.documents ?: emptyList()
            val remaining = docs.count { (it["status"] as? String) != "Low" }
            feedRemainingText.text = remaining.toString()
        }
    }
}
