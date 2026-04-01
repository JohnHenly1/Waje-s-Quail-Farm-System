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

    // Call this from MyApplication.onCreate() — passing `this` (the Application)
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun addAlert(message: String, timestamp: String, type: String = "Inventory") {
        val list = loadAlerts().toMutableList()
        list.add(0, AlertItem(message, timestamp, type))
        saveAlerts(list)
    }

    fun getAlerts(): List<AlertItem> = loadAlerts()

    fun markAllAsRead() {
        saveAlerts(loadAlerts().map { it.copy(isRead = true) })
    }

    fun clearAlerts() = saveAlerts(emptyList())

    fun getUnreadCount(): Int = loadAlerts().count { !it.isRead }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

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