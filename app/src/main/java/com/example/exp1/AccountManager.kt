package com.example.exp1

import android.content.Context
import android.content.SharedPreferences

/**
 * AccountManager — local session management.
 *
 * Role is now stored per-email in SharedPreferences (mirrored from Firestore
 * user_access.role when the user logs in). Use RoleManager to check permissions.
 */
class AccountManager(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("quail_farm_accounts", Context.MODE_PRIVATE)

    // ── Account registration (local cache only) ────────────────────────────────

    fun registerAccount(username: String, email: String, password: String, role: String = "staff"): Boolean {
        if (accountExists(username)) return false
        sharedPreferences.edit().apply {
            putString("${username}_email", email)
            putString("${username}_password", password)
            putString("${username}_role", role)
            putString("${username}_registered", "true")
            putString("email_${email}", username)
            apply()
        }
        return true
    }

    /** Called at login to refresh the cached role from Firestore's user_access doc. */
    fun updateCachedRole(username: String, role: String) {
        sharedPreferences.edit().putString("${username}_role", role).apply()
    }

    fun getRole(username: String): String =
        sharedPreferences.getString("${username}_role", "staff") ?: "staff"

    /** Convenience: role for the currently logged-in user. */
    fun getCurrentRole(): String {
        val username = getCurrentUsername() ?: return "staff"
        return getRole(username)
    }

    fun accountExists(username: String): Boolean =
        sharedPreferences.getString("${username}_registered", null) != null

    fun accountExistsByEmail(email: String): Boolean =
        sharedPreferences.getString("email_$email", null) != null

    fun validateLogin(username: String, password: String): Boolean {
        if (!accountExists(username)) return false
        val stored = sharedPreferences.getString("${username}_password", "")
        return if (stored == password) { saveCurrentSession(username); true } else false
    }

    fun validateLoginByEmail(email: String, password: String): Boolean {
        val username = sharedPreferences.getString("email_$email", null) ?: return false
        return validateLogin(username, password)
    }

    fun getEmail(username: String): String? =
        sharedPreferences.getString("${username}_email", null)

    fun getUsernameByEmail(email: String): String? =
        sharedPreferences.getString("email_$email", null)

    fun updateProfile(oldUsername: String, newUsername: String, newEmail: String): Boolean {
        val password = sharedPreferences.getString("${oldUsername}_password", "") ?: ""
        val role     = getRole(oldUsername)
        sharedPreferences.edit().apply {
            if (oldUsername != newUsername) {
                if (accountExists(newUsername)) return false
                remove("${oldUsername}_email")
                remove("${oldUsername}_password")
                remove("${oldUsername}_role")
                remove("${oldUsername}_registered")
                val oldEmail = getEmail(oldUsername)
                if (oldEmail != null) remove("email_$oldEmail")
            }
            putString("${newUsername}_email", newEmail)
            putString("${newUsername}_password", password)
            putString("${newUsername}_role", role)
            putString("${newUsername}_registered", "true")
            putString("email_${newEmail}", newUsername)
            if (getCurrentUsername() == oldUsername) putString("current_user_session", newUsername)
            apply()
        }
        return true
    }

    fun updatePassword(username: String, oldPassword: String, newPassword: String): Boolean {
        val stored = sharedPreferences.getString("${username}_password", "")
        if (stored != oldPassword) return false
        sharedPreferences.edit().putString("${username}_password", newPassword).apply()
        return true
    }

    // ── Session ────────────────────────────────────────────────────────────────

    fun saveCurrentSession(username: String) {
        sharedPreferences.edit().putString("current_user_session", username).apply()
    }

    fun getCurrentUsername(): String? =
        sharedPreferences.getString("current_user_session", null)

    fun clearSession() {
        sharedPreferences.edit().remove("current_user_session").apply()
    }

    // ── Notification preferences ───────────────────────────────────────────────

    fun saveNotificationPreferences(alertsEnabled: Boolean, globalDataEnabled: Boolean, scheduleEnabled: Boolean) {
        val username = getCurrentUsername() ?: "default"
        sharedPreferences.edit().apply {
            putBoolean("${username}_pref_alerts",      alertsEnabled)
            putBoolean("${username}_pref_global_data", globalDataEnabled)
            putBoolean("${username}_pref_schedule",    scheduleEnabled)
            apply()
        }
    }

    fun isAlertsEnabled(): Boolean {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getBoolean("${username}_pref_alerts", true)
    }

    fun isGlobalDataEnabled(): Boolean {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getBoolean("${username}_pref_global_data", true)
    }

    fun isScheduleEnabled(): Boolean {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getBoolean("${username}_pref_schedule", true)
    }

    // ── Language & Region ──────────────────────────────────────────────────────

    fun saveLanguageRegion(language: String, region: String, province: String) {
        val username = getCurrentUsername() ?: "default"
        sharedPreferences.edit().apply {
            putString("${username}_language", language)
            putString("${username}_region",   region)
            putString("${username}_province", province)
            apply()
        }
    }

    fun getSelectedLanguage(): String {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getString("${username}_language", "English") ?: "English"
    }

    fun getSelectedRegion(): String {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getString("${username}_region", "National Capital Region (NCR)")
            ?: "National Capital Region (NCR)"
    }

    fun getSelectedProvince(): String {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getString("${username}_province", "Metro Manila") ?: "Metro Manila"
    }
}