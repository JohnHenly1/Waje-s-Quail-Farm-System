package com.example.exp1

class RoleManager(val role: String) {

    companion object {
        const val OWNER         = "owner"
        const val BACKUP_OWNER  = "backup_owner"
        const val STAFF         = "staff"

        fun displayName(role: String): String = when (role) {
            OWNER        -> "Owner"
            BACKUP_OWNER -> "Backup Owner"
            STAFF        -> "Staff"
            else         -> "Staff"
        }
    }

    /** Can add/edit/delete feed items, tasks, farm stats */
    fun canEditFarm(): Boolean = role == OWNER || role == BACKUP_OWNER

    // Can delete feed items (staff can only update status)
    fun canDeleteFeedItem(): Boolean = role == OWNER || role == BACKUP_OWNER

    // Can add new tasks
    fun canAddTask(): Boolean = role == OWNER || role == BACKUP_OWNER

    //Can delete tasks
    fun canDeleteTask(): Boolean = role == OWNER || role == BACKUP_OWNER

    // Can update task status (all roles)
    fun canUpdateTaskStatus(): Boolean = true

    // Can approve / reject user access requests
    fun canManageUsers(): Boolean = role == OWNER

    // Can generate invite codes
    fun canGenerateInviteCodes(): Boolean = role == OWNER || role == BACKUP_OWNER

    // Can clear global alerts
    fun canClearAlerts(): Boolean = role == OWNER || role == BACKUP_OWNER

    // Can change farm settings (birds, cages, start date)
    fun canChangeFarmSettings(): Boolean = role == OWNER || role == BACKUP_OWNER
}