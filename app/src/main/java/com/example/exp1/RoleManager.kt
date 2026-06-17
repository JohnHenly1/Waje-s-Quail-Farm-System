package com.example.exp1

class RoleManager(val role: String) {

    companion object {
        const val OWNER         = "owner"
        const val STAFF         = "staff"

        fun displayName(role: String): String = when (role) {
            OWNER        -> "Farm Owner"
            STAFF        -> "Farm Staff"
            else         -> "Farm Staff"
        }
    }

    /** Can add/edit/delete feed items, tasks, farm stats */
    fun canEditFarm(): Boolean = role == OWNER

    // Can delete feed items (staff can only update status)
    fun canDeleteFeedItem(): Boolean = role == OWNER

    // Can add new tasks
    fun canAddTask(): Boolean = role == OWNER

    //Can delete tasks
    fun canDeleteTask(): Boolean = role == OWNER

    // Can update task status (all roles)
    fun canUpdateTaskStatus(): Boolean = true

    // Can approve / reject user access requests
    fun canManageUsers(): Boolean = role == OWNER

    // Can generate invite codes
    fun canGenerateInviteCodes(): Boolean = role == OWNER

    // Can clear global alerts
    fun canClearAlerts(): Boolean = role == OWNER

    // Can change farm settings (birds, cages, start date)
    fun canChangeFarmSettings(): Boolean = role == OWNER
}