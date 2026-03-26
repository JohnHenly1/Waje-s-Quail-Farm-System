package com.example.exp1

import android.content.Context
import android.content.SharedPreferences

class AccountManager(context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("quail_farm_accounts", Context.MODE_PRIVATE)

    fun registerAccount(username: String, email: String, password: String): Boolean {
        // Check if account already exists
        if (accountExists(username)) {
            return false
        }
        
        // Save account data with username as key
        sharedPreferences.edit().apply {
            putString("${username}_email", email)
            putString("${username}_password", password)
            putString("${username}_registered", "true")
            
            // Also store email -> username mapping for login purposes
            putString("email_${email}", username)
            apply()
        }
        return true
    }

    fun accountExists(username: String): Boolean {
        return sharedPreferences.getString("${username}_registered", null) != null
    }

    fun accountExistsByEmail(email: String): Boolean {
        return sharedPreferences.getString("email_$email", null) != null
    }

    fun validateLogin(username: String, password: String): Boolean {
        if (!accountExists(username)) {
            return false
        }
        
        val storedPassword = sharedPreferences.getString("${username}_password", "")
        val isValid = storedPassword == password
        if (isValid) {
            saveCurrentSession(username)
        }
        return isValid
    }

    fun validateLoginByEmail(email: String, password: String): Boolean {
        val username = sharedPreferences.getString("email_$email", null) ?: return false
        return validateLogin(username, password)
    }

    fun getEmail(username: String): String? {
        return sharedPreferences.getString("${username}_email", null)
    }

    fun getUsernameByEmail(email: String): String? {
        return sharedPreferences.getString("email_$email", null)
    }

    fun updateProfile(oldUsername: String, newUsername: String, newEmail: String): Boolean {
        val password = sharedPreferences.getString("${oldUsername}_password", "") ?: ""
        
        sharedPreferences.edit().apply {
            // Remove old keys if username changed
            if (oldUsername != newUsername) {
                // Check if new username is already taken
                if (accountExists(newUsername)) return false
                
                remove("${oldUsername}_email")
                remove("${oldUsername}_password")
                remove("${oldUsername}_registered")
                
                // Remove old email mapping
                val oldEmail = getEmail(oldUsername)
                if (oldEmail != null) remove("email_$oldEmail")
            }
            
            // Save new data
            putString("${newUsername}_email", newEmail)
            putString("${newUsername}_password", password)
            putString("${newUsername}_registered", "true")
            putString("email_${newEmail}", newUsername)
            
            // Update session if it was the current user
            if (getCurrentUsername() == oldUsername) {
                putString("current_user_session", newUsername)
            }
            apply()
        }
        return true
    }

    fun updatePassword(username: String, oldPassword: String, newPassword: String): Boolean {
        val storedPassword = sharedPreferences.getString("${username}_password", "")
        if (storedPassword != oldPassword) return false
        
        sharedPreferences.edit().putString("${username}_password", newPassword).apply()
        return true
    }

    // Farm Stats Management
    fun saveFarmStats(totalBirds: Int, activeCages: Int) {
        sharedPreferences.edit().apply {
            putInt("total_birds", totalBirds)
            putInt("active_cages", activeCages)
            apply()
        }
    }

    fun getTotalBirds(): Int {
        return sharedPreferences.getInt("total_birds", 0)
    }

    fun getActiveCages(): Int {
        return sharedPreferences.getInt("active_cages", 0)
    }

    // Session Management
    fun saveCurrentSession(username: String) {
        sharedPreferences.edit().putString("current_user_session", username).apply()
    }

    fun getCurrentUsername(): String? {
        return sharedPreferences.getString("current_user_session", null)
    }

    fun clearSession() {
        sharedPreferences.edit().remove("current_user_session").apply()
    }

    // Notification Preferences
    fun saveNotificationPreferences(alertsEnabled: Boolean, globalDataEnabled: Boolean, scheduleEnabled: Boolean) {
        val username = getCurrentUsername() ?: "default"
        sharedPreferences.edit().apply {
            putBoolean("${username}_pref_alerts", alertsEnabled)
            putBoolean("${username}_pref_global_data", globalDataEnabled)
            putBoolean("${username}_pref_schedule", scheduleEnabled)
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

    // Language & Region
    fun saveLanguageRegion(language: String, region: String, province: String) {
        val username = getCurrentUsername() ?: "default"
        sharedPreferences.edit().apply {
            putString("${username}_language", language)
            putString("${username}_region", region)
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
        return sharedPreferences.getString("${username}_region", "National Capital Region (NCR)") ?: "National Capital Region (NCR)"
    }

    fun getSelectedProvince(): String {
        val username = getCurrentUsername() ?: "default"
        return sharedPreferences.getString("${username}_province", "Metro Manila") ?: "Metro Manila"
    }
}
