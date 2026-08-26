package com.example.exp1

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
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
        var currentName = currentEmail ?: "User"

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
                        currentName = name
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
                    FarmRepository.logLogout(currentName, currentEmail ?: "", currentRole, "manual")
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
    // verification-code procedure for that one person, and a "Delete"
    // action to remove the pending profile entirely — both behind their
    // own confirmation popups.
    // ─────────────────────────────────────────────────────────────────────

    private data class PendingUser(
        val email: String,
        val name: String,
        val role: String,
        val isActive: Boolean
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
            .setTitle("Farm Staff Pending Account")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()

        addButton.setOnClickListener {
            dialog.dismiss()
            showAddUserDialog(activity, ownerEmail)
        }

        dialog.show()

        val items = mutableListOf<PendingUser>()
        lateinit var adapter: PendingUserAdapter

        fun refreshEmptyState() {
            if (items.isEmpty()) {
                listView.visibility = View.GONE
                emptyText.visibility = View.VISIBLE
            } else {
                listView.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
            }
        }

        adapter = PendingUserAdapter(
            activity = activity,
            items = items,
            onActivateClicked = { user ->
                showUnlockConfirmationDialog(activity, ownerEmail, user) {
                    user.let {
                        val idx = items.indexOf(it)
                        if (idx != -1) {
                            items[idx] = it.copy(isActive = true)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            },
            onDeactivateClicked = { user ->
                showDeactivateConfirmationDialog(activity, ownerEmail, user) {
                    val idx = items.indexOf(user)
                    if (idx != -1) {
                        items[idx] = user.copy(isActive = false)
                        adapter.notifyDataSetChanged()
                    }
                }
            },
            onDeleteClicked = { user ->
                showDeletePendingUserConfirmationDialog(activity, ownerEmail, user) {
                    items.remove(user)
                    adapter.notifyDataSetChanged()
                    refreshEmptyState()
                }
            }
        )
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
                            role = doc.getString("role") ?: "staff",
                            isActive = doc.getBoolean("isActive") ?: false
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                refreshEmptyState()
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Could not load users: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /** Modern, card-style row: initial avatar, name/role/email, active/inactive badge, Activate/Deactivate + Delete actions. */
    private class PendingUserAdapter(
        private val activity: Activity,
        private val items: MutableList<PendingUser>,
        private val onActivateClicked: (PendingUser) -> Unit,
        private val onDeactivateClicked: (PendingUser) -> Unit,
        private val onDeleteClicked: (PendingUser) -> Unit
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
                text = if (user.isActive) "Active — can access the app" else "Inactive — locked out of the app"
                setTextColor(if (user.isActive) Color.parseColor("#1E8E3E") else Color.parseColor("#C08A00"))
                textSize = 11.5f
            })
            card.addView(textColumn)

            val actionColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val toggleButton: View = try {
                MaterialButton(activity).apply {
                    text = if (user.isActive) "Deactivate" else "Activate"
                    textSize = 12f
                    cornerRadius = (14 * dp).toInt()
                    setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                }
            } catch (e: Throwable) {
                android.widget.Button(activity).apply {
                    text = if (user.isActive) "Deactivate" else "Activate"
                }
            }
            toggleButton.setOnClickListener {
                if (user.isActive) onDeactivateClicked(user) else onActivateClicked(user)
            }
            actionColumn.addView(toggleButton)

            val deleteButton = TextView(activity).apply {
                text = "Delete"
                setTextColor(Color.parseColor("#D93025"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (8 * dp).toInt(), 0, 0)
                setOnClickListener { onDeleteClicked(user) }
            }
            actionColumn.addView(deleteButton)

            card.addView(actionColumn)

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
    /**
     * Tapping anywhere in the dialog outside the currently focused EditText
     * dismisses the keyboard and clears focus from that field. Taps that
     * land inside an EditText/Spinner are consumed by that view and never
     * reach this listener, so normal typing/selecting is unaffected.
     *
     * IMPORTANT: takes the Dialog itself (not the host Activity) to check
     * focus. A Dialog has its own separate Window from the Activity, so
     * activity.currentFocus never sees a view focused inside the dialog —
     * it always returns null/wrong-view for dialog content, which is why
     * this previously did nothing when tapping outside a field.
     */
    private fun dismissKeyboardOnOutsideTouch(dialog: Dialog, rootView: View) {
        rootView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val focused = dialog.currentFocus
                if (focused is EditText) {
                    val outRect = android.graphics.Rect()
                    focused.getGlobalVisibleRect(outRect)
                    if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        focused.clearFocus()
                        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(focused.windowToken, 0)
                    }
                }
            }
            v.performClick()
            false
        }
    }

    /**
     * ArrayAdapter using custom row layouts (R.layout.spinner_item_black /
     * R.layout.spinner_dropdown_item_black) that hard-code a white row
     * background with black text. This avoids the earlier issue where
     * forcing only the text color to black left the dropdown's highlighted
     * row using the theme's (dark) selection background, making the black
     * text unreadable — only the font color changes, the dropdown box
     * styling itself is untouched.
     */
    private fun blackTextArrayAdapter(context: Context, items: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(context, R.layout.spinner_item_black, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_black)
        return adapter
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD USER  (owner fills in the new person's info; this ONLY saves the
    // pre-fill profile with status "invited". No verification code is
    // generated and no email is sent here — that only happens from the
    // "Unlock" action in the pending-users list.)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * ZIP/postal codes for Balanga City and each municipality of Bataan
     * (PHLPost). Used to auto-fill the postal code field once a city /
     * municipality is picked in the Add User dialog.
     */
    private val bataanPostalCodes: Map<String, String> = mapOf(
        "Abucay" to "2114",
        "Bagac" to "2107",
        "Balanga City" to "2100",
        "Dinalupihan" to "2110",
        "Hermosa" to "2111",
        "Limay" to "2103",
        "Mariveles" to "2105",
        "Morong" to "2108",
        "Orani" to "2112",
        "Orion" to "2102",
        "Pilar" to "2101",
        "Samal" to "2113"
    )

    /**
     * Barangays per municipality/city in Bataan, used to populate the
     * barangay dropdown once a municipality/city is selected. An
     * "Other (type manually)" option is always appended so a barangay
     * that isn't listed (or a sitio/purok-level detail) can still be
     * entered as free text.
     */
    private val bataanBarangays: Map<String, List<String>> = mapOf(
        "Abucay" to listOf(
            "Bangkal", "Calaylayan", "Capitangan", "Gabon", "Laon",
            "Mabatang", "Omboy", "Salian", "Wawa"
        ),
        "Bagac" to listOf(
            "Atilano L. Ricardo", "Bagumbayan", "Banawang", "Binuangan", "Binukawan",
            "Ibaba", "Ibis", "Pag-asa", "Parang", "Paysawan",
            "Quinawan", "San Antonio", "Saysain", "Tabing-Ilog"
        ),
        "Balanga City" to listOf(
            "Bagong Silang", "Bagumbayan", "Cabog-Cabog", "Camacho", "Cataning",
            "Central", "Cupang North", "Cupang Proper", "Cupang West", "Dangcol",
            "Doña Francisca", "Ibayo", "Lote", "Malabia", "Munting Batangas",
            "Poblacion", "Pto. Rivas Ibaba", "Pto. Rivas Itaas", "San Jose", "Sibacan",
            "Talisay", "Tanato", "Tenejero", "Tortugas", "Tuyo"
        ),
        "Dinalupihan" to listOf(
            "Aquino", "Bangal", "Bayan-bayanan", "Bonifacio", "Burgos",
            "Colo", "Daang Bago", "Dalao", "Del Pilar", "Gen. Luna",
            "Gomez", "Happy Valley", "Jose C. Payumo, Jr.", "Kataasan", "Layac",
            "Luacan", "Mabini Ext.", "Mabini Proper", "Magsaysay", "Maligaya",
            "Naparing", "New San Jose", "Old San Jose", "Padre Dandan", "Pag-asa",
            "Pagalanggang", "Payangan", "Pentor", "Pinulot", "Pita",
            "Rizal", "Roosevelt", "Roxas", "Saguing", "San Benito",
            "San Isidro", "San Pablo", "San Ramon", "San Simon", "Santa Isabel",
            "Santo Niño", "Sapang Balas", "Torres Bugauen", "Tubo-tubo", "Tucop",
            "Zamora"
        ),
        "Hermosa" to listOf(
            "A. Rivera", "Almacen", "Bacong", "Balsic", "Bamban",
            "Burgos-Soliman", "Cataning", "Culis", "Daungan", "Judge Roman Cruz Sr.",
            "Mabiga", "Mabuco", "Maite", "Mambog-Mandama", "Palihan",
            "Pandatung", "Pulo", "Saba", "Sacrifice Valley", "San Pedro",
            "Santo Cristo", "Sumalo", "Tipo"
        ),
        "Limay" to listOf(
            "Alangan", "Duale", "Kitang 2 & Luz", "Kitang I", "Lamao",
            "Landing", "Poblacion", "Reformista", "Saint Francis II", "San Francisco de Asis",
            "Townsite", "Wawa"
        ),
        "Mariveles" to listOf(
            "Alas-asin", "Alion", "Balon-Anito", "Baseco Country", "Batangas II",
            "Biaan", "Cabcaben", "Camaya", "Ipag", "Lucanin",
            "Malaya", "Maligaya", "Mt. View", "Poblacion", "San Carlos",
            "San Isidro", "Sisiman", "Townsite"
        ),
        "Morong" to listOf(
            "Binaritan", "Mabayo", "Nagbalayong", "Poblacion", "Sabang"
        ),
        "Orani" to listOf(
            "Apollo", "Bagong Paraiso", "Balut", "Bayan", "Calero",
            "Centro I", "Centro II", "Dona", "Kabalutan", "Kaparangan",
            "Maria Fe", "Masantol", "Mulawin", "Pag-asa", "Paking-Carbonero",
            "Palihan", "Pantalan Bago", "Pantalan Luma", "Parang Parang", "Puksuan",
            "Sibul", "Silahis", "Tagumpay", "Tala", "Talimundoc",
            "Tapulao", "Tenejero", "Tugatog", "Wawa"
        ),
        "Orion" to listOf(
            "Arellano", "Bagumbayan", "Balagtas", "Balut", "Bantan",
            "Bilolo", "Calungusan", "Camachile", "Daang Bago", "Daang Bilolo",
            "Daang Pare", "General Lim", "Kapunitan", "Lati", "Lusungan",
            "Puting Buhangin", "Sabatan", "San Vicente", "Santa Elena", "Santo Domingo",
            "Villa Angeles", "Wakas", "Wawa"
        ),
        "Pilar" to listOf(
            "Ala-uli", "Bagumbayan", "Balut I", "Balut II", "Bantan Munti",
            "Burgos", "Del Rosario", "Diwa", "Landing", "Liyang",
            "Nagwaling", "Panilao", "Pantingan", "Poblacion", "Rizal",
            "Santa Rosa", "Wakas North", "Wakas South", "Wawa"
        ),
        "Samal" to listOf(
            "East Calaguiman", "East Daang Bago", "Gugo", "Ibaba", "Imelda",
            "Lalawigan", "Palili", "San Juan", "San Roque", "Santa Lucia",
            "Sapa", "Tabing Ilog", "West Calaguiman", "West Daang Bago"
        )
    )

    private const val OTHER_BARANGAY_LABEL = "Other (type manually)"

    fun showAddUserDialog(activity: Activity, ownerEmail: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_invite_user, null)
        val editName = dialogView.findViewById<EditText>(R.id.inviteName)
        val editEmail = dialogView.findViewById<EditText>(R.id.inviteEmail)
        val editBirthday = dialogView.findViewById<EditText>(R.id.inviteBirthday)
        val editStreet = dialogView.findViewById<EditText>(R.id.inviteAddressStreet)
        val spinnerCity = dialogView.findViewById<Spinner>(R.id.inviteAddressCity)
        val editState = dialogView.findViewById<EditText>(R.id.inviteAddressState)
        val editPostal = dialogView.findViewById<EditText>(R.id.inviteAddressPostal)
        val spinnerBarangay = dialogView.findViewById<Spinner>(R.id.inviteAddressBarangay)
        val editBarangayCustom = dialogView.findViewById<EditText>(R.id.inviteAddressBarangayOther)

        // Force black text on every text input and dropdown in this dialog,
        // regardless of theme / night mode / whatever the XML currently sets.
        listOf(editName, editEmail, editBirthday, editStreet, editState, editPostal, editBarangayCustom)
            .forEach { it.setTextColor(Color.BLACK) }

        // Full Name: hard-block digits (and any other disallowed symbol) at
// the keystroke level, instead of only flagging them after the fact
// via the TextWatcher error below. Only letters, spaces, hyphens and
// commas are allowed — no numbers, no other special characters.
        val nameAllowedRegex = Regex("^[A-Za-z\\s,-]*$")
        var lastInvalidNameWarningAt = 0L
        editName.filters = editName.filters + android.text.InputFilter { source, start, end, _, _, _ ->
            val piece = source.subSequence(start, end)
            if (nameAllowedRegex.matches(piece)) {
                null
            } else {
                // Throttle so holding an invalid key (or pasting a bad string)
                // doesn't stack multiple dialogs on top of each other.
                val now = System.currentTimeMillis()
                if (now - lastInvalidNameWarningAt > 800) {
                    lastInvalidNameWarningAt = now
                    AlertDialog.Builder(activity)
                        .setTitle("Invalid Character")
                        .setMessage("Full name can only contain letters, spaces, hyphens (-) and commas (,). Numbers and other special characters are not allowed.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                ""
            }
        }

        // Address is fixed to Bataan province; City is a dropdown of every
        // municipality plus the one component city in Bataan.
        editState.setText("Bataan")
        editState.isEnabled = false
        val cityPlaceholder = "Select City / Municipality"
        val bataanCities = listOf(
            cityPlaceholder,
            "Abucay", "Bagac", "Balanga City", "Dinalupihan", "Hermosa",
            "Limay", "Mariveles", "Morong", "Orani", "Orion", "Pilar", "Samal"
        )
        val cityAdapter = blackTextArrayAdapter(activity, bataanCities)
        spinnerCity.adapter = cityAdapter
        val rbStaff = dialogView.findViewById<android.widget.RadioButton>(R.id.radioInviteStaff)

        fun updateBarangaySpinner(city: String) {
            val options = mutableListOf("Select Barangay")
            bataanBarangays[city]?.let { options.addAll(it) }
            options.add(OTHER_BARANGAY_LABEL)
            val barangayAdapter = blackTextArrayAdapter(activity, options)
            spinnerBarangay.adapter = barangayAdapter
            spinnerBarangay.setSelection(0)
            editBarangayCustom.visibility = View.GONE
            editBarangayCustom.setText("")
        }
        updateBarangaySpinner("") // nothing selected yet

        spinnerBarangay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = parent?.getItemAtPosition(position)?.toString() ?: ""
                if (selected == OTHER_BARANGAY_LABEL) {
                    editBarangayCustom.visibility = View.VISIBLE
                } else {
                    editBarangayCustom.visibility = View.GONE
                    editBarangayCustom.setText("")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerCity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val cityName = bataanCities[position]
                    editPostal.setText(bataanPostalCodes[cityName] ?: "")
                    updateBarangaySpinner(cityName)
                } else {
                    editPostal.setText("")
                    updateBarangaySpinner("")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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

// Tap anywhere in the dialog outside an active input to dismiss the keyboard.
        dismissKeyboardOnOutsideTouch(dialog, dialogView)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editName.text.toString().trim()
            val invitedEmail = editEmail.text.toString().trim().lowercase()
            val birthday = editBirthday.text.toString().trim()
            val street = editStreet.text.toString().trim()
            val citySelected = spinnerCity.selectedItemPosition > 0
            val city = if (citySelected) spinnerCity.selectedItem.toString() else ""
            val state = editState.text.toString().trim() // always "Bataan"
            val postal = editPostal.text.toString().trim()
            val barangaySelectedText = spinnerBarangay.selectedItem?.toString() ?: ""
            val barangaySelected = spinnerBarangay.selectedItemPosition > 0 && barangaySelectedText != OTHER_BARANGAY_LABEL
            val barangay = when {
                barangaySelected -> barangaySelectedText
                barangaySelectedText == OTHER_BARANGAY_LABEL -> editBarangayCustom.text.toString().trim()
                else -> ""
            }
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
            if (birthday.isEmpty()) {
                Toast.makeText(activity, "Please enter the user's birthday", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isAtLeast18(birthday)) {
                Toast.makeText(activity, "User must be at least 18 years old", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Province is always "Bataan" now, so it shouldn't be what decides
            // whether an address was "started" — base that on street/city/barangay/postal.
            val anyAddress = street.isNotEmpty() || citySelected || postal.isNotEmpty() || barangay.isNotEmpty()
            if (anyAddress) {
                if (street.isEmpty()) {
                    Toast.makeText(activity, "Please enter the street", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!citySelected) {
                    Toast.makeText(activity, "Please select a city / municipality", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (barangay.isEmpty()) {
                    Toast.makeText(activity, "Please select or enter the barangay", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (postal.isEmpty()) {
                    Toast.makeText(activity, "Please enter the postal code", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val addressError = validateAddress(street, barangay, city, state, postal)
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
                mapOf(
                    "street" to street,
                    "barangay" to barangay,
                    "city" to city,
                    "state" to state,
                    "postalCode" to postal
                )
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
        val nameRegex = Regex("^[A-Za-z\\s,-]+$")
        if (!nameRegex.matches(trimmed)) {
            return "Name may contain only letters, spaces, hyphens and commas (no digits or other symbols)"
        }
        return null
    }

    private fun validateAddress(street: String, barangay: String, city: String, state: String, postal: String): String? {
        if (street.length < 5) return "Street address must be at least 5 characters"
        if (barangay.length < 2) return "Barangay must be at least 2 characters"
        if (city.length < 2) return "City must be at least 2 characters"
        if (state.length < 2) return "State/Province must be at least 2 characters"
        if (postal.length < 3) return "Postal code must be at least 3 characters"

        val invalidChars = "!@#$%^&*()=[]{}|;':\",<>?"
        if (street.any { it in invalidChars } || barangay.any { it in invalidChars } ||
            city.any { it in invalidChars } ||
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
            "isActive" to false,
            "setupCompleted" to false,
            "invitedBy" to ownerEmail,
            "invitedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        if (birthday.isNotEmpty()) prefillData["birthday"] = birthday
        if (addressMap != null) prefillData["address"] = addressMap

        db.collection("user_access").document(invitedEmail)
            .set(prefillData, SetOptions.merge())
            .addOnSuccessListener {
                FarmRepository.logStaffCreated(ownerEmail, ownerEmail, "owner", name, invitedEmail)
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
    private fun showUnlockConfirmationDialog(activity: Activity, ownerEmail: String, user: PendingUser, onActivated: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Activate ${user.name}?")
            .setMessage("This will generate a verification code and email it to ${user.email} so they can set up their account. Their account will be marked Active and able to access the mobile app once set up.\n\nProceed?")
            .setPositiveButton("Activate") { _, _ ->
                unlockAndSendVerificationCode(activity, FirebaseFirestore.getInstance(), ownerEmail, user.email, user.role, onActivated)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Confirms before flipping an already-activated pending account back to
     * Inactive. This revokes their ability to access the mobile app without
     * deleting their saved profile.
     */
    private fun showDeactivateConfirmationDialog(activity: Activity, ownerEmail: String, user: PendingUser, onDeactivated: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Deactivate ${user.name}?")
            .setMessage("${user.name} (${user.email}) will no longer be able to access the mobile application until reactivated.\n\nProceed?")
            .setPositiveButton("Deactivate") { _, _ ->
                FirebaseFirestore.getInstance().collection("user_access").document(user.email)
                    .update("isActive", false)
                    .addOnSuccessListener {
                        FarmRepository.logStaffUpdated(
                            ownerEmail, ownerEmail, "owner",
                            user.name, user.email,
                            details = "Deactivated staff account for ${user.email}"
                        )
                        Toast.makeText(activity, "${user.name} is now Inactive.", Toast.LENGTH_SHORT).show()
                        onDeactivated()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(activity, "Failed to deactivate user: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The actual verification-code procedure: generates a one-time code,
     * stores it, marks the account isActive=true, and emails the code to
     * the pending user. Only ever runs after the owner explicitly taps
     * Activate and confirms.
     */
    private fun unlockAndSendVerificationCode(
        activity: Activity,
        db: FirebaseFirestore,
        ownerEmail: String,
        invitedEmail: String,
        role: String,
        onActivated: () -> Unit
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
                db.collection("user_access").document(invitedEmail)
                    .update("isActive", true)
                    .addOnCompleteListener {
                        onActivated()
                    }
                sendInviteEmailViaAppsScript(activity, invitedEmail, code, role)
                showCodeResultDialog(activity, code, invitedEmail, role)
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Failed to generate invite code: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE  (per-row action in the pending-users list — removes the
    // pending profile entirely, plus any invite code already issued to it.)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Confirms before permanently deleting a pending user's profile, since
     * it isn't easily undone (they'd need to be added again from scratch).
     */
    private fun showDeletePendingUserConfirmationDialog(
        activity: Activity,
        ownerEmail: String,
        user: PendingUser,
        onDeleted: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${user.name}?")
            .setMessage("This permanently removes ${user.name}'s pending profile (${user.email}). If they were already unlocked, their existing invite code will be removed too.\n\nThis can't be undone. Proceed?")
            .setPositiveButton("Delete") { _, _ ->
                deletePendingUser(activity, ownerEmail, user, onDeleted)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Deletes the user_access/{email} doc and, best-effort, any invite_codes
     * docs that were issued to that email. Only ever runs after the owner
     * explicitly taps Delete and confirms.
     */
    private fun deletePendingUser(activity: Activity, ownerEmail: String, user: PendingUser, onDeleted: () -> Unit) {
        val db = FirebaseFirestore.getInstance()
        db.collection("user_access").document(user.email).delete()
            .addOnSuccessListener {
                FarmRepository.logDeletion(
                    module = "Staff",
                    message = "$ownerEmail deleted staff account: ${user.name} (${user.email})",
                    userName = ownerEmail,
                    userEmail = ownerEmail,
                    role = "owner",
                    details = "Removed pending staff account for ${user.email}"
                )

                db.collection("invite_codes")
                    .whereEqualTo("invitedEmail", user.email)
                    .get()
                    .addOnSuccessListener { docs ->
                        docs.documents.forEach { it.reference.delete() }
                    }
                Toast.makeText(activity, "${user.name} removed.", Toast.LENGTH_SHORT).show()
                onDeleted()
            }
            .addOnFailureListener { e ->
                Toast.makeText(activity, "Failed to remove user: ${e.message}", Toast.LENGTH_LONG).show()
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
                // Apps Script cold-starts can take well over 10s to respond even
                // though MailApp.sendEmail() already went through — a short
                // timeout here was firing a false "email failed to send" toast
                // for invites that actually arrived. Give it more room.
                conn.connectTimeout = 25000
                conn.readTimeout = 25000

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

                // Always logged, regardless of outcome — right now this is
                // the ONLY place the raw Apps Script response is visible
                // anywhere, since it's otherwise read and then discarded.
                // Check Logcat (tag "InviteEmail") when an invite doesn't
                // arrive even though the app reported success.
                android.util.Log.d("InviteEmail", "Apps Script response ($responseCode): $responseText")

                // Apps Script web apps deployed with "Execute as: Me" will
                // very often return HTTP 200 even when something inside the
                // script failed (e.g. MailApp.sendEmail() throwing because
                // Gmail's daily send quota was hit, or the script's own
                // try/catch swallowing the error and returning a JSON body
                // instead of a non-200 status). Relying on responseCode
                // alone means a genuinely failed send gets reported to the
                // owner as "Invite email sent" — which is exactly the "the
                // app says sent but nothing arrives" symptom. Parse the body
                // too, and treat any explicit failure signal in it as a
                // failure regardless of the HTTP status.
                val scriptError: String? = if (responseText.isNotBlank()) {
                    try {
                        val json = JSONObject(responseText)
                        when {
                            json.optBoolean("success", true).not() ->
                                json.optString("error", "The invite service reported a failure.")
                            json.has("error") ->
                                json.optString("error")
                            json.optBoolean("ok", true).not() ->
                                json.optString("message", "The invite service reported a failure.")
                            else -> null
                        }
                    } catch (_: Exception) {
                        // Not JSON (e.g. an Apps Script HTML error page) —
                        // if the HTTP status already looked fine but the
                        // body isn't the JSON success response we expect,
                        // don't guess at its meaning; fall through to the
                        // responseCode-based check below.
                        null
                    }
                } else null

                Handler(Looper.getMainLooper()).post {
                    if (responseCode != 200) {
                        Toast.makeText(
                            activity,
                            "User saved, but the email failed to send (code $responseCode).",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (scriptError != null) {
                        Toast.makeText(
                            activity,
                            "User saved, but the email failed to send: $scriptError",
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
            } catch (e: java.net.SocketTimeoutException) {
                // The send may well have completed on the Apps Script side —
                // Gmail/Apps Script can still be processing after our socket
                // gives up — so don't tell the owner it failed outright.
                android.util.Log.e("InviteEmail", "Timed out waiting for Apps Script response", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        activity,
                        "User saved. Taking longer than expected to confirm the invite email. It may still arrive please check with $email before resending.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("InviteEmail", "Failed to reach Apps Script endpoint", e)
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
        // Phrased as in-progress, not already-done: this dialog is shown
        // right after kicking off sendInviteEmailViaAppsScript() on a
        // background thread, not after it actually finishes — the real
        // outcome (sent / failed, with the specific reason) arrives moments
        // later as the toast from that function.
        val message = "Sending an invite email to $email now — a toast will confirm once it's done.\n\nCode: $code\nRole: $roleDisplayName\n\nIt will expire in 24 hours.\n\nTheir name, birthday and address are already saved — they'll only be asked to set a password."
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