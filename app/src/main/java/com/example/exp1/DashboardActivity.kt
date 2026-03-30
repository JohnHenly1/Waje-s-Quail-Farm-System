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
                // TODO: do something with photoUri (e.g. open a preview screen)
            }
        }

    // Launcher: request camera permission
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }


    //  Lifecycle -----------------------------------------------------------------------------------


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


    //  Camera helpers -------------------------------------------------------------------------------


    /** Entry point — check permission then open camera. */
    private fun handleCameraClick() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> openCamera()

            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Creates a temp file, builds a FileProvider URI, and launches the camera. */
    private fun openCamera() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFile = File.createTempFile("PHOTO_${timestamp}_", ".jpg", cacheDir)

            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",   // must match your AndroidManifest provider authority
                imageFile
            )

            takePictureLauncher.launch(photoUri)

        } catch (e: Exception) {
            // Fallback: open camera app without saving to a specific URI
            val fallbackIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (fallbackIntent.resolveActivity(packageManager) != null) {
                startActivity(fallbackIntent)
            } else {
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    //  UI setup-----------------------------------------------------------------------------------

    private fun applyEntranceAnimations() {
        val fadeIn  = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)

        findViewById<View>(R.id.welcomeCard)?.startAnimation(fadeIn)

        slideUp.startOffset = 100
        findViewById<View>(R.id.aiCard)?.startAnimation(slideUp)

        slideUp.startOffset = 200
        findViewById<View>(R.id.shortcutsGrid)?.startAnimation(slideUp)

        slideUp.startOffset = 300
        findViewById<View>(R.id.statsGrid)?.startAnimation(slideUp)
    }

    private fun showLoading(action: () -> Unit) {
        val loadingLayout = findViewById<View>(R.id.loadingLayout)
        val loadingIcon   = findViewById<View>(R.id.loadingIcon)

        if (loadingLayout != null && loadingIcon != null) {
            loadingLayout.visibility = View.VISIBLE
            val rotate = AnimationUtils.loadAnimation(this, R.anim.rotate)
            loadingIcon.startAnimation(rotate)

            handler.postDelayed({
                loadingLayout.visibility = View.GONE
                loadingIcon.clearAnimation()
                action()
            }, 800)
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

        // -- Camera button -------------------------------------------------------------
        findViewById<LinearLayout?>(R.id.CameraButton)?.setOnClickListener {
            handleCameraClick()
        }

        // -- Analytics -------------------------------------------------------------
        findViewById<LinearLayout?>(R.id.analyticsButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, AnalyticsActivity::class.java)
                    .putExtra("username", username))
            }
        }



        // -- Schedule  -------------------------------------------------------------
        findViewById<android.widget.ImageButton?>(R.id.scheduleButton1)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, ScheduleActivity::class.java)
                    .putExtra("username", username))
            }
        }

        // -- Feed Inventory  -------------------------------------------------------
        findViewById<android.view.View?>(R.id.feedInventoryButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, FeedInventoryActivity::class.java)
                    .putExtra("username", username))
            }
        }

        // -- Egg Coun --------------------------------------------------------------
        findViewById<android.view.View?>(R.id.eggCountButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, EggCountActivity::class.java)
                    .putExtra("username", username))
            }
        }

        //-- Water Level Activity ---------------------------------------------------
        findViewById<android.view.View?>(R.id.water_level)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, WaterSensorActivity::class.java)  // ← FIXED
                    .putExtra("username", username))
            }
        }

        // -- Tasks ------------------------------------------------------------------
        findViewById<android.view.View?>(R.id.tasksButton)?.setOnClickListener {
            showLoading {
                startActivity(Intent(this, ScheduleActivity::class.java)
                    .putExtra("username", username))
            }
        }
    }
}