package com.example.exp1

/**
 * RoleManager — single source of truth for role-based access control (RBAC).
 *
 * Roles
 * ─────
 *  • owner  – Full control: farm settings, users, invites, data, alerts.
 *  • staff  – Operational access: view data, update task status, update stock quantity.
 *  • (any other string) – Treated as UNKNOWN; all privileged checks deny by default.
 *
 * Permission model
 * ────────────────
 *  Permissions are centralised here. Activities/fragments must NOT implement
 *  their own role strings or inline role comparisons — always delegate to this class.
 *
 * Privilege-escalation prevention
 * ────────────────────────────────
 *  • Only the owner may manage users or change farm settings.
 *  • Role assignment (via invite codes or access requests) is capped by
 *    system_settings/role_limits in Firestore; the backend Firestore rules
 *    require a signed-in session for every write.
 *  • Unknown / null roles resolve to UNKNOWN and are denied for all privileged
 *    operations (deny-by-default).
 */
class RoleManager(rawRole: String?) {

    // ── Role constants ────────────────────────────────────────────────────────

    companion object {
        const val OWNER   = "owner"
        const val STAFF   = "staff"
        const val UNKNOWN = "unknown"

        /** Valid roles that the system recognises. */
        val VALID_ROLES = setOf(OWNER, STAFF)

        /**
         * Normalise and validate an incoming role string.
         * Returns [UNKNOWN] for null, blank, or unrecognised values.
         */
        fun normalise(raw: String?): String =
            raw?.trim()?.lowercase()?.takeIf { it in VALID_ROLES } ?: UNKNOWN

        /** Human-readable display name for a role string. */
        fun displayName(role: String?): String = when (normalise(role)) {
            OWNER  -> "Farm Owner"
            STAFF  -> "Farm Staff"
            else   -> "Unknown Role"
        }

        // ── Permission documentation map (for auditing / UI tooltips) ─────────
        /**
         * Returns a map of permission → allowed-roles for documentation purposes.
         * Keep this in sync with the permission functions below.
         */
        val PERMISSION_REGISTRY: Map<String, Set<String>> = mapOf(
            "canEditFarm"                 to setOf(OWNER),
            "canDeleteFeedItem"           to setOf(OWNER),
            "canUpdateInventoryQuantity"  to setOf(OWNER, STAFF),
            "canAddTask"                  to setOf(OWNER),
            "canDeleteTask"               to setOf(OWNER),
            "canUpdateTaskStatus"         to setOf(OWNER, STAFF),
            "canManageUsers"              to setOf(OWNER),
            "canGenerateInviteCodes"      to setOf(OWNER),
            "canClearAlerts"              to setOf(OWNER),
            "canChangeFarmSettings"       to setOf(OWNER),
            "canViewAdminPanel"           to setOf(OWNER),
        )
    }

    // ── Instance role ─────────────────────────────────────────────────────────

    /** Validated, normalised role for this instance. */
    val role: String = normalise(rawRole)

    val isOwner: Boolean get() = role == OWNER
    val isStaff: Boolean get() = role == STAFF
    val isUnknown: Boolean get() = role == UNKNOWN

    // ── Permission functions ──────────────────────────────────────────────────
    //
    // Rule: OWNER has broad access; STAFF has narrow operational access;
    //       UNKNOWN is denied for everything except public read operations.

    /** Add/edit farm data (feed items, farm stats). Owner only. */
    fun canEditFarm(): Boolean = isOwner

    /** Delete a feed item. Owner only. */
    fun canDeleteFeedItem(): Boolean = isOwner

    /**
     * Update the stock quantity of an existing inventory item.
     * Staff are permitted to adjust quantity only; all other fields remain
     * restricted via [canEditFarm].
     */
    fun canUpdateInventoryQuantity(): Boolean = isOwner || isStaff

    /** Add new scheduled tasks. */
    fun canAddTask(): Boolean = isOwner

    /** Delete scheduled tasks. */
    fun canDeleteTask(): Boolean = isOwner

    /** Update the status of an existing task (all valid roles). */
    fun canUpdateTaskStatus(): Boolean = isOwner || isStaff

    /** Approve/reject user access requests and remove users. */
    fun canManageUsers(): Boolean = isOwner

    /** Generate invite codes for new users. */
    fun canGenerateInviteCodes(): Boolean = isOwner

    /** Clear global farm alerts. */
    fun canClearAlerts(): Boolean = isOwner

    /** Change farm-level settings (bird count, cage count, start date). */
    fun canChangeFarmSettings(): Boolean = isOwner

    /** Show the admin panel / admin-only UI sections. */
    fun canViewAdminPanel(): Boolean = isOwner

    // ── Role assignment validation ────────────────────────────────────────────

    /**
     * Returns true if [targetRole] is a role that can be assigned via invite/request.
     * Only [STAFF] may be self-requested or invite-sent.
     * [OWNER] is set only during initial farm setup — never via the invite flow.
     */
    fun isAssignableRole(targetRole: String?): Boolean =
        normalise(targetRole) == STAFF
}