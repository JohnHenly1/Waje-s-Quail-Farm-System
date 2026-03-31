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

    // Camera state -----------------------------------------------------------
    private var photoUri: Uri? = null

    // Launcher: take a photo with the camera app
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && photoUri != null) {
                Toast.makeText(this, "Photo saved!", Toast.LENGTH_SHORT).show()
            }
        }

    // Launcher: request camera permission
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)

        username = intent.getStringExtra("username") ?: "User"
        drawerLayout = findViewById(R.id.drawerLayout)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupNavigation()
        setupServerTime()
        updateWelcomeMessage()
        setupButtons()
        applyEntranceAnimations()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTimeRunnable)
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

            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )

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

    fun showLoading(action: () -> Unit) {
        val loadingLayout = findViewById<View>(R.id.loadingLayout)
        val loadingIcon   = findViewById<View>(R.id.loadingIcon)

        if (loadingLayout != null && loadingIcon != null) {
            loadingLayout.visibility = View.VISIBLE
            val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
            loadingIcon.startAnimation(jump)

            handler.postDelayed({
                loadingLayout.visibility = View.GONE
                loadingIcon.clearAnimation()
                action()
            }, 1200)
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
        findViewById<TextView?>(R.id.welcome_text)?.text = "Hi, $username!"
    }

    private fun setupButtons() {
        findViewById<LinearLayout?>(R.id.CameraButton)?.setOnClickListener {
            handleCameraClick()
        }

        findViewById<LinearLayout?>(R.id.analyticsButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, AnalyticsActivity::class.java)
                    .putExtra("username", username))
            }
        }

        findViewById<android.widget.ImageButton?>(R.id.scheduleButton1)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, ScheduleActivity::class.java)
                    .putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.feedInventoryButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, FeedInventoryActivity::class.java)
                    .putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.eggCountButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, EggCountActivity::class.java)
                    .putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.water_level)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, WaterSensorActivity::class.java)
                    .putExtra("username", username))
            }
        }

        findViewById<android.view.View?>(R.id.tasksButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, ScheduleActivity::class.java)
                    .putExtra("username", username))
            }
        }
    }
}