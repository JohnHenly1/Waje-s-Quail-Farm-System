package com.example.exp1

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.util.Patterns
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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

        // Hide Add User if role is staff. NOTE: the menu item id is left as
        // "nav_invite_user" to avoid requiring a menu XML id change, but its
        // label is switched to "Add User" here. If you'd rather rename the id
        // itself, update it in the nav menu XML and swap the id below too.
        val navMenu = navigationView.menu
        val inviteItem = navMenu.findItem(R.id.nav_invite_user)
        inviteItem?.title = "Add User"
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
                        // "Add User" now opens the member list first; adding
                        // a brand new person happens from the "+" button there.
                        showUserListDialog(activity, currentEmail ?: "")
                    } else {
                        Toast.makeText(activity, "Only owners can add users.", Toast.LENGTH_SHORT).show()
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

    // ─────────────────────────────────────────────────────────────────────
    // USER LIST  ("Add User" side-menu entry opens this first)
    // Shows only PENDING ("invited") users — approved/active users don't
    // clutter this screen. Each row has an "Unlock" action that runs the
    // verification-code procedure for that one person, after confirmation.
    // ─────────────────────────────────────────────────────────────────────

    private data class PendingUser(
        val email: String,
        val name: String,
        val role: String
    )

    fun showUserListDialog(activity: Activity, ownerEmail: String) {
        val dp = activity.resources.displayMetrics.density

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val addButton = MaterialButtonOrPlainButton(activity, "+  Add User")
        root.addView(addButton)

        val listView = ListView(activity).apply {
            divider = null
            dividerHeight = (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (360 * dp).toInt()
            ).apply { topMargin = (14 * dp).toInt() }
        }
        root.addView(listView)

        val emptyText = TextView(activity).apply {
            text = "No pending invites. Tap \"Add User\" to invite your first team member."
            setTextColor(Color.parseColor("#8A8A8E"))
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
            visibility = View.GONE
        }
        root.addView(emptyText)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Farm Users — Pending")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()

        addButton.setOnClickListener {
            dialog.dismiss()
            showAddUserDialog(activity, ownerEmail)
        }

        dialog.show()

        val items = mutableListOf<PendingUser>()
        val adapter = PendingUserAdapter(activity, items) { user ->
            showUnlockConfirmationDialog(activity, ownerEmail, user)
        }
        listView.adapter = adapter

        FirebaseFirestore.getInstance().collection("user_access")
            .whereEqualTo("status", "invited")
            .get()
            .addOnSuccessListener { docs ->
                items.clear()
                docs.documents.forEach { doc ->
                    items.add(
                        PendingUser(
                            email = doc.id,
                            name = doc.getString("name") ?: doc.id,
                            role = doc.getString("role") ?: "staff"
                        )
                    )
                }
                if (items.isEmpty()) {
                    listView.visibility = View.GONE
                    emptyText.visibility = View.VISIBLE
                } else {
                    listView.visibility = View.VISIBLE
                    emptyText.visibility = View.GONE
                    adapter.notifyDataSetChanged()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Could not load users: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /** Modern, card-style row: initial avatar, name/role/email, pending badge, Unlock action. */
    private class PendingUserAdapter(
        private val activity: Activity,
        private val items: MutableList<PendingUser>,
        private val onUnlockClicked: (PendingUser) -> Unit
    ) : BaseAdapter() {

        private val dp = activity.resources.displayMetrics.density

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val user = items[position]

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 18 * dp
                    setColor(Color.parseColor("#F4F4F7"))
                }
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val avatar = TextView(activity).apply {
                text = (user.name.firstOrNull() ?: '?').uppercaseChar().toString()
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        if (user.role == "owner") Color.parseColor("#5B6DFF")
                        else Color.parseColor("#34A853")
                    )
                }
            }
            card.addView(avatar)

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * dp).toInt()
                    marginEnd = (10 * dp).toInt()
                }
            }
            textColumn.addView(TextView(activity).apply {
                text = user.name
                setTextColor(Color.parseColor("#1C1C1E"))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            textColumn.addView(TextView(activity).apply {
                text = "${RoleManager.displayName(user.role)}  •  ${user.email}"
                setTextColor(Color.parseColor("#8A8A8E"))
                textSize = 12.5f
            })
            textColumn.addView(TextView(activity).apply {
                text = "Pending — not unlocked yet"
                setTextColor(Color.parseColor("#C08A00"))
                textSize = 11.5f
            })
            card.addView(textColumn)

            val unlockButton: View = try {
                MaterialButton(activity).apply {
                    text = "Unlock"
                    textSize = 12f
                    cornerRadius = (14 * dp).toInt()
                    setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                }
            } catch (e: Throwable) {
                android.widget.Button(activity).apply { text = "Unlock" }
            }
            unlockButton.setOnClickListener { onUnlockClicked(user) }
            card.addView(unlockButton)

            return card
        }
    }

    /** Small helper so we don't hard-depend on a MaterialButton style resource. */
    private fun MaterialButtonOrPlainButton(activity: Activity, label: String): View {
        return try {
            MaterialButton(activity).apply { text = label }
        } catch (e: Throwable) {
            android.widget.Button(activity).apply { text = label }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD USER  (owner fills in the new person's info; this ONLY saves the
    // pre-fill profile with status "invited". No verification code is
    // generated and no email is sent here — that only happens from the
    // "Unlock" action in the pending-users list.)
    // ─────────────────────────────────────────────────────────────────────

    fun showAddUserDialog(activity: Activity, ownerEmail: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_invite_user, null)
        val editName = dialogView.findViewById<EditText>(R.id.inviteName)
        val editEmail = dialogView.findViewById<EditText>(R.id.inviteEmail)
        val editBirthday = dialogView.findViewById<EditText>(R.id.inviteBirthday)
        val editStreet = dialogView.findViewById<EditText>(R.id.inviteAddressStreet)
        val editCity = dialogView.findViewById<EditText>(R.id.inviteAddressCity)
        val editState = dialogView.findViewById<EditText>(R.id.inviteAddressState)
        val editPostal = dialogView.findViewById<EditText>(R.id.inviteAddressPostal)
        val rbStaff = dialogView.findViewById<android.widget.RadioButton>(R.id.radioInviteStaff)

        editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                editName.error = getNameValidationError(s.toString())
            }
        })

        editEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val emailText = s.toString().trim()
                editEmail.error = if (emailText.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(emailText).matches()) {
                    "Invalid email format"
                } else null
            }
        })

        val calendar = Calendar.getInstance()
        editBirthday.setOnClickListener {
            val maxBirthday = Calendar.getInstance().apply { add(Calendar.YEAR, -18) }
            val listener = DatePickerDialog.OnDateSetListener { _, y, m, d ->
                calendar.set(y, m, d)
                val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                editBirthday.setText(sdf.format(calendar.time))
            }
            val picker = DatePickerDialog(
                activity, listener,
                maxBirthday.get(Calendar.YEAR), maxBirthday.get(Calendar.MONTH), maxBirthday.get(Calendar.DAY_OF_MONTH)
            )
            picker.datePicker.maxDate = maxBirthday.timeInMillis
            picker.show()
        }

        // Update availability, same pattern as the original invite dialog,
        // but counting "invited" (pending) staff too so open invites reserve a spot.
        val db = FirebaseFirestore.getInstance()
        var isRoleFull = false

        db.collection("system_settings").document("role_limits").get()
            .addOnSuccessListener { limitDoc ->
                val staffLimit = limitDoc.getLong("staff_limit") ?: 5L
                db.collection("user_access")
                    .whereEqualTo("role", "staff")
                    .whereIn("status", listOf("approved", "invited"))
                    .get()
                    .addOnSuccessListener { docs ->
                        val available = (staffLimit - docs.size()).coerceAtLeast(0)
                        rbStaff.text = "Farm Staff ($available spots left)"
                        isRoleFull = available <= 0
                        if (isRoleFull) {
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
            .setPositiveButton("Add User", null)
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editName.text.toString().trim()
            val invitedEmail = editEmail.text.toString().trim().lowercase()
            val birthday = editBirthday.text.toString().trim()
            val street = editStreet.text.toString().trim()
            val city = editCity.text.toString().trim()
            val state = editState.text.toString().trim()
            val postal = editPostal.text.toString().trim()
            val selectedRole = "staff"

            if (name.isEmpty()) {
                Toast.makeText(activity, "Please enter the user's name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nameError = getNameValidationError(name)
            if (nameError != null) {
                Toast.makeText(activity, nameError, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (invitedEmail.isEmpty()) {
                Toast.makeText(activity, "Please enter an email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(invitedEmail).matches()) {
                Toast.makeText(activity, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (birthday.isNotEmpty() && !isAtLeast18(birthday)) {
                Toast.makeText(activity, "User must be at least 18 years old", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val anyAddress = street.isNotEmpty() || city.isNotEmpty() || state.isNotEmpty() || postal.isNotEmpty()
            if (anyAddress) {
                if (street.isEmpty() || city.isEmpty() || state.isEmpty() || postal.isEmpty()) {
                    Toast.makeText(activity, "Please fill all address fields or leave them all empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val addressError = validateAddress(street, city, state, postal)
                if (addressError != null) {
                    Toast.makeText(activity, addressError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            if (isRoleFull) {
                Toast.makeText(activity, "Farm Staff is full — free up a spot first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val addressMap = if (anyAddress) {
                mapOf("street" to street, "city" to city, "state" to state, "postalCode" to postal)
            } else null

            // Check whether this email already belongs to an active account.
            db.collection("user_access").document(invitedEmail).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists() && doc.getString("status") == "approved") {
                        Toast.makeText(activity, "This email already belongs to an active user.", Toast.LENGTH_SHORT).show()
                    } else {
                        showAddUserConfirmationDialog(activity, invitedEmail, selectedRole) {
                            savePendingUser(
                                activity, db, ownerEmail, invitedEmail, selectedRole,
                                name, birthday, addressMap, dialog
                            )
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(activity, "Error checking email: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun getNameValidationError(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val nameRegex = Regex("^[A-Za-z._,\\s'-]+$")
        if (!nameRegex.matches(trimmed)) {
            return "Name may contain letters, spaces, commas, periods, underscores, hyphens and apostrophes (no digits or other symbols)"
        }
        return null
    }

    private fun validateAddress(street: String, city: String, state: String, postal: String): String? {
        if (street.length < 5) return "Street address must be at least 5 characters"
        if (city.length < 2) return "City must be at least 2 characters"
        if (state.length < 2) return "State/Province must be at least 2 characters"
        if (postal.length < 3) return "Postal code must be at least 3 characters"

        val invalidChars = "!@#$%^&*()=[]{}|;':\",<>?"
        if (street.any { it in invalidChars } || city.any { it in invalidChars } ||
            state.any { it in invalidChars } || postal.any { it in invalidChars }) {
            return "Address contains invalid characters"
        }
        return null
    }

    private fun isAtLeast18(bday: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            sdf.isLenient = false
            val birthDate = sdf.parse(bday) ?: return false
            val birthCal = Calendar.getInstance().apply { time = birthDate }
            val today = Calendar.getInstance()
            var age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
            if (today.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) age--
            age >= 18
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Asks the owner to confirm before saving the pending profile. Note this
     * step no longer sends anything — it only writes the pre-fill data.
     */
    private fun showAddUserConfirmationDialog(activity: Activity, email: String, role: String, onConfirm: () -> Unit) {
        val roleDisplayName = RoleManager.displayName(role)
        AlertDialog.Builder(activity)
            .setTitle("Confirm Add User")
            .setMessage("Add $email as $roleDisplayName?\n\nThis only saves their profile as pending. You'll unlock them (and send their setup code) separately from the Farm Users list.")
            .setPositiveButton("Save") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Writes the owner-provided profile straight into user_access/{email}
     * with status "invited" (so it shows up in the pending list and
     * SetupAccountActivity can find/pre-fill it later). Deliberately does
     * NOT generate an invite code or send an email — that's the Unlock
     * action's job.
     */
    private fun savePendingUser(
        activity: Activity,
        db: FirebaseFirestore,
        ownerEmail: String,
        invitedEmail: String,
        selectedRole: String,
        name: String,
        birthday: String,
        addressMap: Map<String, String>?,
        parentDialog: AlertDialog
    ) {
        val prefillData = mutableMapOf<String, Any>(
            "name" to name,
            "email" to invitedEmail,
            "role" to selectedRole,
            "status" to "invited",
            "setupCompleted" to false,
            "invitedBy" to ownerEmail,
            "invitedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        if (birthday.isNotEmpty()) prefillData["birthday"] = birthday
        if (addressMap != null) prefillData["address"] = addressMap

        db.collection("user_access").document(invitedEmail)
            .set(prefillData, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(
                    activity,
                    "$name added as pending. Open Farm Users and tap Unlock when you're ready to send their setup code.",
                    Toast.LENGTH_LONG
                ).show()
                parentDialog.dismiss()
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Failed to save user info: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNLOCK  (per-row action in the pending-users list — this is where the
    // verification code is actually generated and emailed.)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Confirms before running the verification-code procedure, since it
     * immediately emails the user and isn't easily undone.
     */
    private fun showUnlockConfirmationDialog(activity: Activity, ownerEmail: String, user: PendingUser) {
        AlertDialog.Builder(activity)
            .setTitle("Unlock ${user.name}?")
            .setMessage("This will generate a verification code and email it to ${user.email} so they can set up their account.\n\nProceed?")
            .setPositiveButton("Unlock") { _, _ ->
                unlockAndSendVerificationCode(activity, FirebaseFirestore.getInstance(), ownerEmail, user.email, user.role)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The actual verification-code procedure: generates a one-time code,
     * stores it, and emails it to the pending user. Only ever runs after
     * the owner explicitly taps Unlock and confirms.
     */
    private fun unlockAndSendVerificationCode(
        activity: Activity,
        db: FirebaseFirestore,
        ownerEmail: String,
        invitedEmail: String,
        role: String
    ) {
        val expirationTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
        val code = "%06d".format(Random.nextInt(1000000))

        db.collection("invite_codes")
            .document(code)
            .set(mapOf(
                "role" to role,
                "invitedEmail" to invitedEmail,
                "createdBy" to ownerEmail,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "expiresAt" to expirationTime
            ))
            .addOnSuccessListener {
                sendInviteEmailViaAppsScript(activity, invitedEmail, code, role)
                showCodeResultDialog(activity, code, invitedEmail, role)
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Failed to generate invite code: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Sends the invite email by calling a Google Apps Script web app, which uses
     * Gmail's MailApp to send the message under the "Waje Quail Farm" display name.
     * This runs on a background thread since it's a blocking network call.
     */
    private fun sendInviteEmailViaAppsScript(activity: Activity, email: String, code: String, role: String) {
        val scriptUrl = "https://script.google.com/macros/s/AKfycbx-_H4Jy4KTuZQSPTMCxTAIKIAJxGMAaIzGF-uKB0m05YLWb1Flgdor-wGD-ieOym_0/exec"
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
                            "User saved, but the email failed to send (code $responseCode).",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            activity,
                            "Invite email sent to $email. Ask them to check their inbox or spam at Gmail.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        activity,
                        "User saved, but the email failed to send: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun showCodeResultDialog(activity: Activity, code: String, email: String, role: String) {
        val roleDisplayName = RoleManager.displayName(role)
        val message = "An invite email has been automatically sent to $email.\n\nCode: $code\nRole: $roleDisplayName\n\nIt will expire in 24 hours.\n\nTheir name, birthday and address are already saved — they'll only be asked to set a password."
        AlertDialog.Builder(activity)
            .setTitle("User Unlocked")
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