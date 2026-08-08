package com.example.exp1

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Automatically records a "Logout" activity-log entry when the user exits or
 * closes the mobile app without pressing the in-app Logout button — mirroring
 * what the website does when a browser tab/window is closed.
 *
 * How it works: [androidx.lifecycle.ProcessLifecycleOwner] tracks the
 * lifecycle of the *whole app process*, not a single Activity. Unlike an
 * individual Activity's onStop (which fires constantly as the user navigates
 * between this app's own screens), ProcessLifecycleOwner.onStop only fires
 * once the very last Activity of the app has stopped and nothing new has
 * started — i.e. the user actually left the app (Home button, task switch,
 * swiping it away, screen lock, etc.). That is the mobile equivalent of a
 * browser tab/window closing.
 *
 * A short grace period is used before the logout is actually written: if the
 * user comes right back (e.g. they briefly glanced at a notification) the
 * pending write is cancelled, so momentary interruptions don't spam the
 * Activity Logs.
 *
 * Duplicate-avoidance: manual logout (ProfileActivity's Logout button)
 * already calls FarmRepository.logLogout() and clears the session itself
 * (AccountManager.clearSession()) before the app is ever backgrounded. By the
 * time onStop fires afterwards, getCurrentUsername() is already null, so the
 * grace-period check below finds no active session and writes nothing —
 * guaranteeing a Logout is recorded at most once per login session.
 *
 * Reopening the app after being fully exited is unaffected by this class:
 * that flow is still entirely driven by MainActivity's existing
 * cached-session check (accountManager.getCurrentUsername()), which this
 * class does not change.
 */
class AppSessionTracker(context: Context) : DefaultLifecycleObserver {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLogoutRunnable: Runnable? = null

    companion object {
        private const val EXIT_GRACE_PERIOD_MS = 3000L
    }

    override fun onStart(owner: LifecycleOwner) {
        // The app came back to the foreground before the grace period
        // elapsed — cancel any pending implicit-logout write.
        pendingLogoutRunnable?.let { handler.removeCallbacks(it) }
        pendingLogoutRunnable = null
    }

    override fun onStop(owner: LifecycleOwner) {
        val accountManager = AccountManager(appContext)
        val email = accountManager.getCurrentUsername() ?: return // no active session — nothing to log

        val runnable = Runnable {
            pendingLogoutRunnable = null
            // Re-check: the session may have already ended (manual logout,
            // account switch) during the grace period.
            if (accountManager.getCurrentUsername() == email) {
                val role = accountManager.getRole(email)
                val name = accountManager.getCachedName(email)
                FarmRepository.logLogout(name, email, role)
            }
        }
        pendingLogoutRunnable = runnable
        handler.postDelayed(runnable, EXIT_GRACE_PERIOD_MS)
    }
}
