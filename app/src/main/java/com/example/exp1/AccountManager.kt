package com.example.exp1

import android.content.Context
import android.content.SharedPreferences

/**
 * AccountManager — local session management.
 *
 * Role is now stored per-email in SharedPreferences (mirrored from Firestore
 * user_access.role when the user logins).
 */
class AccountManager(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("quail_farm_accounts", Context.MODE_PRIVATE)

    fun registerAccount(username: String, email: String, password: String, role: String = "staff"): Boolean {
        sharedPreferences.edit().apply {
            putString("${username}_email", email)
            putString("${username}_password", password)
            putString("${username}_role", role)
            putString("${username}_registered", "true")
            
            val usernames = getAllUsernames().toMutableSet()
            usernames.add(username)
            putStringSet("all_usernames_list", usernames)
            
            putString("email_${email}", username)
            apply()
        }
        return true
    }

    fun getAllUsernames(): Set<String> {
        return sharedPreferences.getStringSet("all_usernames_list", emptySet()) ?: emptySet()
    }

    fun getAllUsers(): List<User> {
        val usernames = getAllUsernames()
        return usernames.map { username ->
            User(
                username = username,
                email = getEmail(username) ?: "",
                role = getRole(username)
            )
        }
    }

    data class User(val username: String, val email: String, val role: String)

    fun updateCachedRole(username: String, role: String) {
        sharedPreferences.edit().putString("${username}_role", role).apply()
    }

    fun getRole(username: String): String =
        sharedPreferences.getString("${username}_role", "staff") ?: "staff"

    fun getCurrentRole(): String {
        val username = getCurrentUsername() ?: return "staff"
        return getRole(username)
    }

    fun accountExists(username: String): Boolean =
        sharedPreferences.getString("${username}_registered", null) != null

    fun validateLogin(username: String, password: String): Boolean {
        if (!accountExists(username)) return false
        val stored = sharedPreferences.getString("${username}_password", "")
        return if (stored == password) { saveCurrentSession(username); true } else false
    }

    fun getEmail(username: String): String? =
        sharedPreferences.getString("${username}_email", null)

    fun updatePassword(username: String, oldPassword: String, newPassword: String): Boolean {
        val stored = sharedPreferences.getString("${username}_password", "")
        if (stored != oldPassword) return false
        sharedPreferences.edit().putString("${username}_password", newPassword).apply()
        return true
    }

    fun deleteAccount(username: String): Boolean {
        if (!accountExists(username)) return false
        val email = getEmail(username)

        sharedPreferences.edit().apply {
            remove("${username}_email")
            remove("${username}_password")
            remove("${username}_role")
            remove("${username}_registered")
            if (email != null) remove("email_$email")

            val usernames = getAllUsernames().toMutableSet()
            usernames.remove(username)
            putStringSet("all_usernames_list", usernames)

            if (getCurrentUsername() == username) {
                remove("current_user_session")
            }
            apply()
        }
        return true
    }

    fun saveCurrentSession(username: String) {
        sharedPreferences.edit().putString("current_user_session", username).apply()
    }

    fun getCurrentUsername(): String? =
        sharedPreferences.getString("current_user_session", null)

    fun clearSession() {
        sharedPreferences.edit().remove("current_user_session").apply()
    }

    fun saveFarmStats(totalBirds: Int, activeCages: Int) {
        sharedPreferences.edit().apply {
            putInt("total_birds", totalBirds)
            putInt("active_cages", activeCages)
            apply()
        }
    }

    fun saveNotificationPreferences(alertsEnabled: Boolean, globalDataEnabled: Boolean, scheduleEnabled: Boolean, eggCountEnabled: Boolean, eggCountHour: Int, eggCountMinute: Int) {
        val username = getCurrentUsername() ?: "default"
        sharedPreferences.edit().apply {
            putBoolean("${username}_pref_alerts",      alertsEnabled)
            putBoolean("${username}_pref_global_data", globalDataEnabled)
            putBoolean("${username}_pref_schedule",    scheduleEnabled)
            putBoolean("${username}_pref_egg_count",   eggCountEnabled)
            putInt("${username}_egg_count_hour",       eggCountHour)
            putInt("${username}_egg_count_minute",     eggCountMinute)
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

    fun isEggCountEnabled(): Boolean {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getBoolean("${username}_pref_egg_count", false)
    }

    fun getEggCountHour(): Int {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getInt("${username}_egg_count_hour", 18) // 6 PM default
    }

    fun getEggCountMinute(): Int {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getInt("${username}_egg_count_minute", 0)
    }

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
