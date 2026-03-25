package com.example.exp1

object GlobalData {
    data class AlertItem(
        val message: String,
        val timestamp: String,
        val type: String, // "Inventory", "System", "Critical"
        var isRead: Boolean = false
    )

    private val alerts = mutableListOf<AlertItem>()

    fun addAlert(message: String, timestamp: String, type: String = "Inventory") {
        alerts.add(0, AlertItem(message, timestamp, type))
    }

    fun getAlerts(): List<AlertItem> {
        return alerts
    }

    fun markAllAsRead() {
        alerts.forEach { it.isRead = true }
    }

    fun clearAlerts() {
        alerts.clear()
    }

    fun getUnreadCount(): Int {
        return alerts.count { !it.isRead }
    }
}
