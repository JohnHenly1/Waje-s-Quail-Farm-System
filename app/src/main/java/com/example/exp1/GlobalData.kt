package com.example.exp1

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object GlobalData {

    data class AlertItem(
        val message: String,
        val timestamp: String,
        val type: String,
        var isRead: Boolean = false
    )

    private var prefs: SharedPreferences? = null
    private const val PREFS_NAME = "global_data_prefs"
    private const val KEY_ALERTS = "alerts"

    // Call this from MyApplication.onCreate()
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    @Synchronized
    fun addAlert(message: String, timestamp: String, type: String = "Inventory") {
        val list = loadAlerts().toMutableList()

        // Prevent duplicates: same message text is always a duplicate regardless of timestamp.
        // Firestore is the source of truth; this local cache should never hold two entries
        // with the same message.
        val isDuplicate = list.any { it.message == message }
        if (isDuplicate) return

        list.add(0, AlertItem(message, timestamp, type))

        // Keep last 100 alerts
        val trimmedList = if (list.size > 100) list.take(100) else list
        saveAlerts(trimmedList)
    }

    /**
     * Replaces the current local alerts with a new list (useful for syncing with cloud).
     * Deduplicates by message before saving so old Firestore duplicates (written before
     * the deterministic-ID fix) do not appear multiple times in the UI.
     */
    @Synchronized
    fun syncWithCloud(cloudAlerts: List<AlertItem>) {
        // Keep only the first occurrence of each unique message (list is newest-first)
        val deduped = cloudAlerts.distinctBy { it.message }
        saveAlerts(deduped)
    }

    /**
     * Returns alerts, optionally hiding anything at/older than [clearedBeforeMillis].
     * That cutoff comes from AccountManager.getAlertsClearedBefore() and is
     * per-user/per-device — passing 0L (the default) returns the full shared
     * history untouched, which is what the underlying cloud data still holds
     * for every other account.
     */
    @Synchronized
    fun getAlerts(clearedBeforeMillis: Long = 0L): List<AlertItem> {
        val all = loadAlerts()
        if (clearedBeforeMillis <= 0L) return all
        return all.filter { timestampMillis(it.timestamp) > clearedBeforeMillis }
    }

    @Synchronized
    fun markAllAsRead() {
        saveAlerts(loadAlerts().map { it.copy(isRead = true) })
    }

    /**
     * Clears this device's local alert cache only. Does NOT touch the shared
     * Firestore alert history — use AccountManager.setAlertsClearedNow() +
     * the [clearedBeforeMillis] filter on getAlerts()/getUnreadCount() for the
     * "Clear All" button, so one user's clear can't wipe alerts other users
     * still need to see.
     */
    @Synchronized
    fun clearAlerts() = saveAlerts(emptyList())

    /**
     * Remove all local alerts whose message contains the given string.
     * Used when a task is marked Done so its reminder disappears from the list.
     */
    @JvmStatic
    @Synchronized
    fun removeAlertsContaining(substring: String) {
        val filtered = loadAlerts().filterNot {
            it.message.contains(substring, ignoreCase = true)
        }
        saveAlerts(filtered)
    }

    @Synchronized
    fun getUnreadCount(clearedBeforeMillis: Long = 0L): Int =
        getAlerts(clearedBeforeMillis).count { !it.isRead }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    // Same format FarmRepository/AlertsActivity/DashboardActivity render
    // timestamps in. Anything that fails to parse is treated as "now" so a
    // malformed timestamp is never accidentally hidden by an old clear.
    private val timestampFormat =
        java.text.SimpleDateFormat("yyyy/MM/dd hh:mm a", java.util.Locale.getDefault())

    private fun timestampMillis(timestamp: String): Long =
        try {
            timestampFormat.parse(timestamp)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

    private fun loadAlerts(): List<AlertItem> {
        val raw = prefs?.getString(KEY_ALERTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                AlertItem(
                    message   = obj.optString("message"),
                    timestamp = obj.optString("timestamp"),
                    type      = obj.optString("type", "Inventory"),
                    isRead    = obj.optBoolean("isRead", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAlerts(list: List<AlertItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("message",   item.message)
                put("timestamp", item.timestamp)
                put("type",      item.type)
                put("isRead",    item.isRead)
            })
        }
        prefs?.edit()?.putString(KEY_ALERTS, arr.toString())?.apply()
    }
}