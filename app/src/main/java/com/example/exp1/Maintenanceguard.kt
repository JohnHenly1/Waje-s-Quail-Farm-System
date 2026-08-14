package com.example.exp1

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * App-wide maintenance-mode gate.
 *
 * Backed by a live listener on system_settings/app_status — the same doc
 * the website's App Settings page writes (see services/appSettings.js on
 * the web side). Jobs:
 *
 *  1. Force-logout + redirect STAFF. The moment maintenanceMode flips from
 *     off -> on, whichever screen is currently on-screen (there are 16
 *     Activities in AndroidManifest.xml — Dashboard, Schedule, Feed
 *     Inventory, Egg Count, Alerts, Profile, etc.) gets torn down and a
 *     staff user is sent back to MainActivity's login screen, which shows
 *     the maintenance message. Wired up once here, centrally, via
 *     Application.ActivityLifecycleCallbacks, rather than adding a
 *     duplicate listener to every individual Activity.
 *
 *  2. Let the OWNER through, with a persistent on-screen indicator. The
 *     owner is exempt from the redirect above (see MainActivity's own
 *     owner bypass at the login gate for the equivalent "entering fresh"
 *     case) — but they still need to *know* the app is in that state while
 *     they use it. A small amber banner reading "Maintenance Mode is ON —
 *     other users are logged out" gets injected into whichever screen the
 *     owner is currently on, and re-injected every time they navigate to a
 *     new one, for as long as maintenanceMode stays true. It's added to
 *     android.R.id.content — the FrameLayout every Activity's window
 *     already has as its content root — so this works across all 16
 *     screens without editing each one's layout XML individually.
 *
 *  3. isUnderMaintenance: a cached, synchronous flag any write path can
 *     check before proceeding — defense in depth for the brief window
 *     between maintenanceMode flipping on and step 1 actually reaching the
 *     screen (network latency, a write already mid-flight, etc). See the
 *     guarded functions in FarmRepository. (The owner isn't exempted from
 *     this — the flag itself doesn't distinguish roles; if it needs to,
 *     FarmRepository's own callers already know the caller's role and can
 *     check that before deciding whether to treat a maintenance rejection
 *     as an error.)
 *
 * Deliberately NOT enforced via Firestore Security Rules: this project's
 * Firestore is shared with the website admin panel, and both apps
 * authenticate identically (anonymous auth, no per-platform claim) — rules
 * can't tell "the mobile app" apart from "the website", so a rules-level
 * write block would also lock the owner out of the website tools they'd
 * need to turn maintenance back off. This gate is app-side and targets the
 * mobile app specifically, which is what was actually asked for.
 */
object MaintenanceGuard : Application.ActivityLifecycleCallbacks {

    private const val DEFAULT_MESSAGE =
        "The app is currently under maintenance. Please try again later."

    private const val BANNER_TAG = "maintenance_guard_banner"

    @Volatile
    var isUnderMaintenance: Boolean = false
        private set

    private var maintenanceMessage: String = DEFAULT_MESSAGE
    private var listenerRegistration: ListenerRegistration? = null
    private var currentActivity: Activity? = null
    private var started = false

    /** Call once, from WajeApplication.onCreate(). Safe to call more than once. */
    fun start(app: Application) {
        if (started) return
        started = true

        app.registerActivityLifecycleCallbacks(this)

        listenerRegistration = FirebaseFirestore.getInstance()
            .collection("system_settings")
            .document("app_status")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Transient read error (offline, auth not ready yet on a
                    // brand-new install, etc). Leave isUnderMaintenance as
                    // it was — the listener auto-retries on its own once the
                    // underlying issue clears, same as any other Firestore
                    // listener in this app.
                    return@addSnapshotListener
                }

                val wasUnderMaintenance = isUnderMaintenance
                isUnderMaintenance = snapshot?.getBoolean("maintenanceMode") ?: false
                maintenanceMessage = snapshot?.getString("message")?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_MESSAGE

                if (isUnderMaintenance && !wasUnderMaintenance) {
                    handleMaintenanceTurnedOn()
                }

                // Re-evaluate the banner on every snapshot, not just the
                // off->on transition — covers maintenance turning back off
                // (banner should disappear) and the message text being
                // edited while it's already on (banner text should update)
                // for an owner who's currently sitting on some screen.
                refreshBannerOnCurrentActivity()
            }
    }

    private fun handleMaintenanceTurnedOn() {
        val activity = currentActivity ?: return

        // MainActivity already shows the maintenance message itself via its
        // own listener (see MainActivity.checkMaintenanceThenProceed) —
        // nothing to force out of there.
        if (activity is MainActivity) return

        val accountManager = AccountManager(activity)
        val username = accountManager.getCurrentUsername()
        val role = if (username != null) accountManager.getRole(username) else null

        if (RoleManager(role).isOwner) {
            // Owner stays in — banner (added below, after this function
            // returns) is their indicator instead of a redirect.
            return
        }

        if (username != null) {
            val name = accountManager.getCachedName(username)
            val email = accountManager.getEmail(username) ?: username
            // Best-effort audit write — mirrors the manual-logout entry
            // NavigationHelper's nav_logout already writes, just tagged
            // "maintenance" instead of "manual" so it's distinguishable in
            // the web Activity Logs. Not blocking the redirect on this.
            FarmRepository.logLogout(name, email, role ?: "staff", "maintenance")
        }
        accountManager.clearSession()

        Toast.makeText(activity, maintenanceMessage, Toast.LENGTH_LONG).show()

        val intent = Intent(activity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        activity.startActivity(intent)
        activity.finish()
    }

    // ── Owner maintenance banner ─────────────────────────────────────────────

    private fun refreshBannerOnCurrentActivity() {
        val activity = currentActivity ?: return
        if (activity is MainActivity) return // has its own dedicated UI for this

        val accountManager = AccountManager(activity)
        val username = accountManager.getCurrentUsername()
        val role = if (username != null) accountManager.getRole(username) else null
        val shouldShow = isUnderMaintenance && RoleManager(role).isOwner

        if (shouldShow) showBanner(activity) else hideBanner(activity)
    }

    private fun showBanner(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val existing = root.findViewWithTag<TextView>(BANNER_TAG)
        if (existing != null) {
            existing.text = bannerText()
            return
        }

        val banner = TextView(activity).apply {
            tag = BANNER_TAG
            text = bannerText()
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B8860B")) // dark amber — reads clearly as a warning without being pure red/error-colored
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        root.addView(banner, params)
    }

    private fun hideBanner(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = root.findViewWithTag<TextView>(BANNER_TAG) ?: return
        root.removeView(existing)
    }

    private fun bannerText(): String =
        "⚠ Maintenance Mode is ON — other users are signed out. Only you (owner) can access the app."

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    // ── Application.ActivityLifecycleCallbacks ──────────────────────────────
    // Tracks whichever Activity is currently on screen, so
    // handleMaintenanceTurnedOn()/refreshBannerOnCurrentActivity() above know
    // where to act — and re-applies the banner every time the owner
    // navigates to a new screen while maintenance is still on, since it was
    // only added to the previous Activity's window.
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        refreshBannerOnCurrentActivity()
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}