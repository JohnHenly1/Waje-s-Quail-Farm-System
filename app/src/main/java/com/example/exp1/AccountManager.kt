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
}
