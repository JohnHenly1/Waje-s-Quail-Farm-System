package com.example.exp1

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import com.google.android.material.navigation.NavigationView
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

object NavigationHelper {

    fun setupBottomNavigation(activity: Activity) {
        val homeButton = activity.findViewById<LinearLayout>(R.id.homeButton)
        val analyticsButton = activity.findViewById<LinearLayout>(R.id.analyticsButton)
        val cameraButton = activity.findViewById<LinearLayout>(R.id.CameraButton)
        val scheduleButton = activity.findViewById<LinearLayout>(R.id.scheduleButton)
        val profileButton = activity.findViewById<LinearLayout>(R.id.profileButton)

        val username = activity.intent.getStringExtra("username")

        val activityName = activity.localClassName
        when {
            activityName.contains("DashboardActivity") || activity is DashboardActivity -> highlightButton(homeButton)
            activityName.contains("AnalyticsActivity") || activity is AnalyticsActivity -> highlightButton(analyticsButton)
            activityName.contains("ScheduleActivity") || activity is ScheduleActivity -> highlightButton(scheduleButton)
            activityName.contains("ProfileActivity") || activity is ProfileActivity -> highlightButton(profileButton)
        }

        homeButton?.setOnClickListener {
            if (activity !is DashboardActivity) {
                val intent = Intent(activity, DashboardActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }

        analyticsButton?.setOnClickListener {
            if (activity !is AnalyticsActivity) {
                val intent = Intent(activity, AnalyticsActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }

        cameraButton?.setOnClickListener { }

        scheduleButton?.setOnClickListener {
            if (activity !is ScheduleActivity) {
                val intent = Intent(activity, ScheduleActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }

        profileButton?.setOnClickListener {
            if (activity !is ProfileActivity) {
                val intent = Intent(activity, ProfileActivity::class.java)
                intent.putExtra("username", username)
                intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                activity.startActivity(intent)
            }
        }
    }

    fun setupSideMenu(activity: Activity, drawerLayout: DrawerLayout) {
        val navigationView = activity.findViewById<NavigationView>(R.id.sideMenu)
        
        val accountManager = AccountManager(activity)
        val currentEmail = accountManager.getCurrentUsername()
        
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
                    val currentRole = accountManager.getRole(currentEmail ?: "")
                    val currentRm = RoleManager(currentRole)
                    if (currentRm.canGenerateInviteCodes()) {
                        showGenerateInviteCodeDialog(activity, currentEmail ?: "")
                    } else {
                        Toast.makeText(activity, "Only owners can generate invite codes.", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.nav_dashboard -> {
                    if (activity !is DashboardActivity) {
                        val intent = Intent(activity, DashboardActivity::class.java)
                        intent.putExtra("username", currentEmail)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        activity.startActivity(intent)
                    }
                }
                R.id.nav_settings -> {
                    if (activity !is ProfileActivity) {
                        val intent = Intent(activity, ProfileActivity::class.java)
                        intent.putExtra("username", currentEmail)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        activity.startActivity(intent)
                    }
                }
                R.id.nav_help -> {
                    if (activity !is ProfileActivity) {
                        val intent = Intent(activity, ProfileActivity::class.java)
                        intent.putExtra("username", currentEmail)
                        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        activity.startActivity(intent)
                    }
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
                val intent = Intent(activity, AlertsActivity::class.java)
                intent.putExtra("username", username)
                activity.startActivity(intent)
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
        val roles = arrayOf("staff", "backup_owner")
        val roleLabels = arrayOf("Staff", "Backup Owner")
        var selectedIndex = 0

        AlertDialog.Builder(activity)
            .setTitle("Generate Invite Code")
            .setMessage("Select the role for the new user, then tap Generate.")
            .setSingleChoiceItems(roleLabels, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Generate") { _, _ ->
                val role = roles[selectedIndex]
                val code = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 6)
                    .uppercase()

                val db = FirebaseFirestore.getInstance()
                db.collection("invite_codes")
                    .document(code)
                    .set(mapOf(
                        "role"      to role,
                        "createdBy" to ownerEmail,
                        "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ))
                    .addOnSuccessListener {
                        showCodeResultDialog(activity, code, roleLabels[selectedIndex])
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            activity,
                            "Failed to generate code: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCodeResultDialog(activity: Activity, code: String, roleLabel: String) {
        val message =
            "Share this 6-character code with the new $roleLabel:\n\n" +
                    "  Code:  $code\n\n" +
                    "They enter it in the app:\n" +
                    "Register screen → \"Enter Invite Code\" → type the code above → sign in with Google."

        AlertDialog.Builder(activity)
            .setTitle("Invite Code Ready")
            .setMessage(message)
            .setPositiveButton("Share via Email") { _, _ ->
                sendCodeByEmail(activity, code, roleLabel)
            }
            .setNeutralButton("Copy & Close") { _, _ ->
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Invite Code", code)
                )
                Toast.makeText(activity, "Code copied: $code", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun sendCodeByEmail(activity: Activity, code: String, roleLabel: String) {
        val subject = "You're invited to Waje's Quail Farm System"
        val body = """
Hello,

You have been invited to join Waje's Quail Farm System as a $roleLabel.

Your invite code: $code

To get started:
1. Download the app.
2. On the login screen, tap "Register Account".
3. Tap "Enter Invite Code".
4. Type the code above and sign in with your Google account.

— Waje's Quail Farm System
        """.trimIndent()

        try {
            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            activity.startActivity(Intent.createChooser(emailIntent, "Send invite via…"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not open email app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
