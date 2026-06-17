package com.example.exp1

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.util.Patterns
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import com.google.android.material.navigation.NavigationView
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.random.Random

object NavigationHelper {

    fun setupBottomNavigation(activity: Activity) {
        val homeButton = activity.findViewById<LinearLayout>(R.id.homeButton)
        val analyticsButton = activity.findViewById<LinearLayout>(R.id.analyticsButton)
        val cameraButton = activity.findViewById<LinearLayout>(R.id.CameraButton)
        val scheduleButton = activity.findViewById<LinearLayout>(R.id.scheduleButton)
        val profileButton = activity.findViewById<LinearLayout>(R.id.profileButton)

        val accountManager = AccountManager(activity)
        val currentEmail = accountManager.getCurrentUsername()

        val activityName = activity.localClassName
        when {
            activityName.contains("DashboardActivity") || activity is DashboardActivity -> highlightButton(homeButton)
            activityName.contains("AnalyticsActivity") || activity is AnalyticsActivity -> highlightButton(analyticsButton)
            activityName.contains("ScheduleActivity") || activity is ScheduleActivity -> highlightButton(scheduleButton)
            activityName.contains("ProfileActivity") || activity is ProfileActivity -> highlightButton(profileButton)
        }

        applyTouchGlow(homeButton)
        applyTouchGlow(analyticsButton)
        applyTouchGlow(cameraButton)
        applyTouchGlow(scheduleButton)
        applyTouchGlow(profileButton)

        homeButton?.setOnClickListener {
            if (activity !is DashboardActivity) {
                navigateTo(activity, DashboardActivity::class.java, "Opening Dashboard...", currentEmail)
            }
        }

        analyticsButton?.setOnClickListener {
            if (activity !is AnalyticsActivity) {
                navigateTo(activity, AnalyticsActivity::class.java, "Generating Reports...", currentEmail)
            }
        }

        cameraButton?.setOnClickListener {
            navigateTo(activity, EggCountActivity::class.java, "Opening Camera...", currentEmail)
        }

        scheduleButton?.setOnClickListener {
            if (activity !is ScheduleActivity) {
                navigateTo(activity, ScheduleActivity::class.java, "Fetching Tasks...", currentEmail)
            }
        }

        profileButton?.setOnClickListener {
            if (activity !is ProfileActivity) {
                navigateTo(activity, ProfileActivity::class.java, "Syncing Profile...", currentEmail)
            }
        }
    }

    private fun applyTouchGlow(view: View?) {
        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
            false
        }
    }

    private fun <T : Activity> navigateTo(currentActivity: Activity, targetClass: Class<T>, label: String, email: String?) {
        if (!isInternetActuallyWorking(currentActivity)) {
            showNoInternetOverlay(currentActivity)
            return
        }

        showGlobalLoading(currentActivity, label) {
            val intent = Intent(currentActivity, targetClass)
            intent.putExtra("username", email)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            currentActivity.startActivity(intent)
        }
    }

    fun isInternetActuallyWorking(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun showNoInternetOverlay(activity: Activity) {
        val loadingLayout = activity.findViewById<View>(R.id.loadingLayout)
        val progressBar = activity.findViewById<ProgressBar>(R.id.loadingProgressBar)
        val percentageText = activity.findViewById<TextView>(R.id.loadingPercentageText)
        val statusText = activity.findViewById<TextView>(R.id.loadingStatusText)
        val noInternetSection = activity.findViewById<View>(R.id.noInternetSection)
        val btnRetry = activity.findViewById<View>(R.id.btnRetryConnection)

        if (loadingLayout != null && noInternetSection != null) {
            statusText?.text = "No Connection. Check your Data and Wifi Connection"
            progressBar?.visibility = View.GONE
            percentageText?.visibility = View.GONE
            noInternetSection.visibility = View.VISIBLE
            loadingLayout.visibility = View.VISIBLE

            btnRetry?.setOnClickListener {
                if (isInternetActuallyWorking(activity)) {
                    loadingLayout.visibility = View.GONE
                    activity.recreate()
                } else {
                    Toast.makeText(activity, "Still no connection...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun showGlobalLoading(activity: Activity, label: String, action: () -> Unit) {
        val loadingLayout = activity.findViewById<View>(R.id.loadingLayout)
        val loadingIcon = activity.findViewById<View>(R.id.loadingIcon)
        val statusText = activity.findViewById<TextView>(R.id.loadingStatusText)
        val progressBar = activity.findViewById<ProgressBar>(R.id.loadingProgressBar)
        val percentText = activity.findViewById<TextView>(R.id.loadingPercentageText)

        if (loadingLayout != null && loadingIcon != null) {
            statusText?.text = label
            loadingLayout.visibility = View.VISIBLE
            val jump = AnimationUtils.loadAnimation(activity, R.anim.quail_jump)
            loadingIcon.startAnimation(jump)

            var progress = 0
            val handler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    if (progress <= 100) {
                        progressBar?.progress = progress
                        percentText?.text = "${progress}%"
                        progress += 10
                        handler.postDelayed(this, 40)
                    } else {
                        loadingLayout.visibility = View.GONE
                        loadingIcon.clearAnimation()
                        action()
                    }
                }
            }
            handler.post(runnable)
        } else {
            action()
        }
    }

    fun setupSideMenu(activity: Activity, drawerLayout: DrawerLayout) {
        val navigationView = activity.findViewById<NavigationView>(R.id.sideMenu)

        val accountManager = AccountManager(activity)
        val currentEmail = accountManager.getCurrentUsername()
        val currentRole = accountManager.getRole(currentEmail ?: "")

        // Hide Invite User if role is staff
        val navMenu = navigationView.menu
        val inviteItem = navMenu.findItem(R.id.nav_invite_user)
        if (currentRole == "staff") {
            inviteItem?.isVisible = false
        } else {
            inviteItem?.isVisible = true
        }

        if (currentEmail != null) {
            FirebaseFirestore.getInstance().collection("user_access").document(currentEmail).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        val name = doc.getString("name") ?: "User"
                        val photoUrl = doc.getString("profilePic") ?: ""
                        updateDrawerHeader(navigationView, name, photoUrl, activity)
                    }
                }
        }

        navigationView?.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_invite_user -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val currentRm = RoleManager(currentRole)
                    if (currentRm.canGenerateInviteCodes()) {
                        showGenerateInviteCodeDialog(activity, currentEmail ?: "")
                    } else {
                        Toast.makeText(activity, "Only owners can generate invite codes.", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.nav_dashboard -> {
                    if (activity !is DashboardActivity) {
                        navigateTo(activity, DashboardActivity::class.java, "Opening Dashboard...", currentEmail)
                    }
                }
                R.id.nav_settings -> {
                    if (activity !is ProfileActivity) {
                        navigateTo(activity, ProfileActivity::class.java, "Loading Settings...", currentEmail)
                    }
                }
                R.id.nav_help -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    showHelpSupportDialog(activity)
                }
                R.id.nav_logout -> {
                    accountManager.clearSession()
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    activity.startActivity(intent)
                    activity.finish()
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun updateDrawerHeader(navigationView: NavigationView?, name: String, photoUrl: String, activity: Activity) {
        val headerView = navigationView?.getHeaderView(0)
        val userNameTextView = headerView?.findViewById<TextView>(R.id.userName)
        val userInitialTextView = headerView?.findViewById<TextView>(R.id.userInitial)
        val userImageView = headerView?.findViewById<ImageView>(R.id.userPhoto)

        userNameTextView?.text = name

        if (photoUrl.isNotEmpty()) {
            userInitialTextView?.visibility = View.GONE
            userImageView?.let {
                it.visibility = View.VISIBLE
                Glide.with(activity).load(photoUrl).circleCrop().into(it)
            }
        } else {
            userImageView?.visibility = View.GONE
            userInitialTextView?.let {
                it.visibility = View.VISIBLE
                if (name.isNotEmpty()) it.text = name[0].uppercaseChar().toString()
            }
        }
    }

    fun setupNotificationButton(activity: Activity) {
        val notificationButton = activity.findViewById<View>(R.id.notificationButton)
        val username = activity.intent.getStringExtra("username")
        notificationButton?.setOnClickListener {
            if (activity !is AlertsActivity) {
                navigateTo(activity, AlertsActivity::class.java, "Fetching Alerts...", username)
            }
        }
    }

    private fun highlightButton(button: LinearLayout?) {
        button?.let {
            it.setBackgroundResource(R.drawable.nav_item_glow)
            it.alpha = 1.0f
            it.scaleX = 1.05f
            it.scaleY = 1.05f
        }
    }

    fun showGenerateInviteCodeDialog(activity: Activity, ownerEmail: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_invite_user, null)
        val editEmail = dialogView.findViewById<EditText>(R.id.inviteEmail)
        val roleGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.inviteRoleGroup)

        val rbStaff = dialogView.findViewById<android.widget.RadioButton>(R.id.radioInviteStaff)

        editEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val email = s.toString().trim()
                if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    editEmail.error = "Invalid email format"
                } else {
                    editEmail.error = null
                }
            }
        })

        // Update availability
        val db = FirebaseFirestore.getInstance()
        db.collection("system_settings").document("role_limits").get()
            .addOnSuccessListener { limitDoc ->
                val staffLimit = limitDoc.getLong("staff_limit") ?: 5L
                db.collection("user_access")
                    .whereEqualTo("role", "staff")
                    .whereEqualTo("status", "approved")
                    .get()
                    .addOnSuccessListener { docs ->
                        val available = (staffLimit - docs.size()).coerceAtLeast(0)
                        rbStaff.text = "Farm Staff ($available spots left)"
                        if (available <= 0) {
                            rbStaff.isEnabled = false
                            rbStaff.text = "Farm Staff (Full)"
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(activity, "Error checking role availability: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Error fetching role limits: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        val builder = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton("Generate Invite", null)
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val invitedEmail = editEmail.text.toString().trim().lowercase()
            if (invitedEmail.isEmpty()) {
                Toast.makeText(activity, "Please enter an email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(invitedEmail).matches()) {
                Toast.makeText(activity, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Only staff invites are supported now
            val selectedRole = "staff"

            // Check role availability
            db.collection("system_settings").document("role_limits").get()
                .addOnSuccessListener { limitDoc ->
                    val limit = limitDoc.getLong("${selectedRole}_limit") ?: 5L
                    db.collection("user_access")
                        .whereEqualTo("role", selectedRole)
                        .whereEqualTo("status", "approved")
                        .get()
                        .addOnSuccessListener { users ->
                            if (users.size() >= limit) {
                                val roleDisplayName = RoleManager.displayName(selectedRole)
                                Toast.makeText(activity, "The limit for $roleDisplayName has been reached.", Toast.LENGTH_LONG).show()
                            } else {
                                // Check if email is already in use
                                db.collection("user_access").document(invitedEmail).get()
                                    .addOnSuccessListener { doc ->
                                        if (doc.exists()) {
                                            Toast.makeText(activity, "This email is already registered.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            // Confirm before actually creating the code and sending the email
                                            showInviteConfirmationDialog(activity, invitedEmail, selectedRole) {
                                                generateAndSendInvite(activity, db, ownerEmail, invitedEmail, selectedRole, dialog)
                                            }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(activity, "Error checking email: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(activity, "Error checking role availability: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(activity, "Error fetching role limits: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * Asks the owner to confirm before the invite is generated and the email
     * is sent, since both actions happen immediately and aren't easily undone.
     */
    private fun showInviteConfirmationDialog(activity: Activity, email: String, role: String, onConfirm: () -> Unit) {
        val roleDisplayName = RoleManager.displayName(role)
        AlertDialog.Builder(activity)
            .setTitle("Confirm Invite")
            .setMessage("Generate an invite code for $email as $roleDisplayName?\n\nAn email will be sent to them automatically.")
            .setPositiveButton("Proceed") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Performs the actual Firestore write and email send for a new invite.
     * Only called after the owner has confirmed via showInviteConfirmationDialog.
     */
    private fun generateAndSendInvite(
        activity: Activity,
        db: FirebaseFirestore,
        ownerEmail: String,
        invitedEmail: String,
        selectedRole: String,
        parentDialog: AlertDialog
    ) {
        val expirationTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
        val code = "%06d".format(Random.nextInt(1000000))

        db.collection("invite_codes")
            .document(code)
            .set(mapOf(
                "role"      to selectedRole,
                "invitedEmail" to invitedEmail,
                "createdBy" to ownerEmail,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "expiresAt" to expirationTime
            ))
            .addOnSuccessListener {
                // Fire-and-forget call to the Apps Script web app,
                // which sends the invite email via Gmail (MailApp).
                sendInviteEmailViaAppsScript(activity, invitedEmail, code, selectedRole)
                showCodeResultDialog(activity, code, invitedEmail, selectedRole)
                parentDialog.dismiss()
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Failed to generate: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Sends the invite email by calling a Google Apps Script web app, which uses
     * Gmail's MailApp to send the message under the "Waje Quail Farm" display name.
     * This runs on a background thread since it's a blocking network call.
     */
    private fun sendInviteEmailViaAppsScript(activity: Activity, email: String, code: String, role: String) {
        val scriptUrl = "https://script.google.com/macros/s/AKfycbxd3Jv_ysFbqaH0Rf5Qw8_Zxv6g2Sy2muDSkISnmPjxk2KMENJF7RA8ybXdQ5GYyMHF/exec"
        val secret = "Red0455"

        Thread {
            try {
                val url = URL(scriptUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val payload = JSONObject().apply {
                    put("secret", secret)
                    put("email", email)
                    put("code", code)
                    put("role", role)
                    put("expiresHours", 24)
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                val responseText = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                Handler(Looper.getMainLooper()).post {
                    if (responseCode != 200) {
                        Toast.makeText(
                            activity,
                            "Invite saved, but the email failed to send (code $responseCode).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        activity,
                        "Invite saved, but the email failed to send: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun showCodeResultDialog(activity: Activity, code: String, email: String, role: String) {
        val roleDisplayName = RoleManager.displayName(role)
        val message = "An invite email has been automatically sent to $email.\n\nCode: $code\nRole: $roleDisplayName\n\nIt will expire in 24 hours."
        AlertDialog.Builder(activity)
            .setTitle("Invite Sent")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    fun showHelpSupportDialog(activity: Activity) {
        val builder = AlertDialog.Builder(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_help_support, null)
        builder.setView(view)
        builder.setPositiveButton("Close", null)
        builder.show()
    }
}