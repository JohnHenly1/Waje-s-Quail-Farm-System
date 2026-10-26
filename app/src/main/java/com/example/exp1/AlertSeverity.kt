package com.example.exp1

enum class Severity { RED, ORANGE, BLUE, OTHER }

// Mirrors the branch order and colors in AlertsAdapter.onBindViewHolder().
fun severityOf(alert: GlobalData.AlertItem): Severity {
    val msg = alert.message
    return when {
        alert.type == "Egg Count" || alert.type == "System" || msg.contains("Egg", true) ->
            Severity.OTHER

        alert.type == "Inventory" || msg.contains("Inventory", true) -> {
            if (msg.contains("EMPTY", true) || msg.contains("DEPLETED", true) || msg.contains("LOW", true))
                Severity.RED else Severity.ORANGE
        }

        alert.type == "Water Level" -> when {
            msg.contains("Emergency", true) || msg.contains("Critical", true) -> Severity.RED
            msg.contains("Warning", true) -> Severity.ORANGE
            msg.contains("Refilled", true) -> Severity.OTHER
            else -> Severity.BLUE
        }

        alert.type == "Schedule" || msg.contains("Missed", true) ->
            if (msg.contains("Missed", true)) Severity.RED else Severity.BLUE

        else -> Severity.OTHER
    }
}