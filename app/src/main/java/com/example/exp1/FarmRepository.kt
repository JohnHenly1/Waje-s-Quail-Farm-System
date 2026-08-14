package com.example.exp1

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

object FarmRepository {

    private val db = FirebaseFirestore.getInstance()

    // Paths ---------------------------------------------------------------------------------------
    //
    //  Firestore layout:
    //    farm_data/stats                   ← document (birds, cages, start date)
    //    farm_data/shared/tasks/{id}       ← sub-collection under document "shared"
    //    farm_data/shared/feed/{id}        ← sub-collection under document "shared"
    //    farm_data/shared/alert/{id}      ← sub-collection under document "shared"


    private val statsDoc  get() = db.collection("farm_data").document("stats")
    private val sharedDoc get() = db.collection("farm_data").document("shared")

    private val tasksCol  get() = sharedDoc.collection("tasks")
    private val feedCol   get() = sharedDoc.collection("feed")
    private val alertsCol get() = sharedDoc.collection("alert")

    // Top-level "activity_logs" collection — same collection the website writes
    // to (see quail-web/src/services/activityService.js:logActivity). Writing
    // here directly (instead of into a module-specific sub-collection that the
    // website has to translate) is what lets a deletion made on either platform
    // show up immediately in the web Activity Logs "Deleted" section.
    private val activityLogsCol get() = db.collection("activity_logs")

    // Defense-in-depth for MaintenanceGuard: guards functional-module writes
    // (tasks/feed/alerts) so nothing goes through in the brief window between
    // maintenanceMode flipping on and the force-logout redirect actually
    // reaching the screen. Deliberately NOT applied to logLogin/logLogout/
    // log*(...) below — those are audit writes, including the "maintenance"
    // logout entry MaintenanceGuard itself writes, which needs to go through.
    private fun blockedByMaintenance(onDone: ((Exception?) -> Unit)?): Boolean {
        if (!MaintenanceGuard.isUnderMaintenance) return false
        onDone?.invoke(Exception("This action is unavailable while the app is under maintenance."))
        return true
    }

    //  Farm Stats----------------------------------------------------------------------------------

    fun saveFarmStats(totalBirds: Int, activeCages: Int, onDone: ((Exception?) -> Unit)? = null) {
        val data = mapOf<String, Any>(
            "totalBirds"  to totalBirds,
            "activeCages" to activeCages
        )
        statsDoc.set(data, SetOptions.merge())
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun saveFarmStartDateIfAbsent(onDone: ((Exception?) -> Unit)? = null) {
        statsDoc.get().addOnSuccessListener { doc ->
            if (!doc.exists() || doc.getTimestamp("farmStartDate") == null) {
                statsDoc.set(mapOf("farmStartDate" to Timestamp.now()), SetOptions.merge())
                    .addOnSuccessListener { onDone?.invoke(null) }
                    .addOnFailureListener { e -> onDone?.invoke(e) }
            } else {
                onDone?.invoke(null)
            }
        }.addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun listenToFarmStats(
        onChange: (totalBirds: Int, activeCages: Int, daysRunning: Long) -> Unit
    ): ListenerRegistration {
        return statsDoc.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) { onChange(0, 0, 0); return@addSnapshotListener }
            val birds = snap.getLong("totalBirds")?.toInt() ?: 0
            val cages = snap.getLong("activeCages")?.toInt() ?: 0
            val start = snap.getTimestamp("farmStartDate")
            val days  = if (start != null) {
                val diff = System.currentTimeMillis() - start.toDate().time
                java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff) + 1
            } else 0L
            onChange(birds, cages, days)
        }
    }

    // Tasks ---------------------------------------------------------------------------------------

    fun listenToTasks(onChange: (List<Map<String, Any?>>) -> Unit): ListenerRegistration {
        return tasksCol
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, _ ->
                val list = snaps?.documents?.map { doc ->
                    val data = doc.data?.toMutableMap() ?: mutableMapOf()
                    data["firestoreId"] = doc.id
                    data as Map<String, Any?>
                } ?: emptyList()
                onChange(list)
            }
    }

    fun addTask(taskData: Map<String, Any>, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        tasksCol.add(taskData)
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun updateTaskStatus(firestoreId: String, newStatus: String, extensionMinutes: Int = 0, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        val update = mapOf(
            "status" to newStatus,
            "extensionMinutes" to extensionMinutes,
            "statusUpdatedAt" to FieldValue.serverTimestamp()
        )
        tasksCol.document(firestoreId).update(update)
            .addOnSuccessListener {
                // Status updated in Firestore, Alerts will be handled by the listener in the apps
                onDone?.invoke(null)
            }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun deleteTask(firestoreId: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        tasksCol.document(firestoreId).delete()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun deleteTasksByGroupId(groupId: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        tasksCol.whereEqualTo("recurrenceGroupId", groupId).get()
            .addOnSuccessListener { snap ->
                val batch = db.batch()
                for (doc in snap.documents) batch.delete(doc.reference)
                batch.commit()
                    .addOnSuccessListener { onDone?.invoke(null) }
                    .addOnFailureListener { e -> onDone?.invoke(e) }
            }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun batchAddTasks(taskDataList: List<Map<String, Any>>, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        val batch = db.batch()
        for (data in taskDataList) batch.set(tasksCol.document(), data)
        batch.commit()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun batchDeleteTasks(firestoreIds: List<String>, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        val batch = db.batch()
        for (id in firestoreIds) batch.delete(tasksCol.document(id))
        batch.commit()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    // -- Feed Inventory ---------------------------------------------------------------------------

    fun listenToFeed(onChange: (List<Map<String, Any?>>) -> Unit): ListenerRegistration {
        return feedCol.addSnapshotListener { snaps, _ ->
            val list = snaps?.documents?.map { doc ->
                val data = doc.data?.toMutableMap() ?: mutableMapOf()
                data["firestoreId"] = doc.id
                data as Map<String, Any?>
            } ?: emptyList()
            onChange(list)
        }
    }

    fun addFeedItem(name: String, status: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        feedCol.add(mapOf(
            "name"      to name,
            "status"    to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun updateFeedItem(firestoreId: String, name: String, status: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        feedCol.document(firestoreId).set(
            mapOf(
                "name"      to name,
                "status"    to status,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }
    // Firestore update for staff (status only)
    fun updateFeedStatus(firestoreId: String, status: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        feedCol.document(firestoreId)
            .update("status", status, "updatedAt", FieldValue.serverTimestamp())
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun deleteFeedItem(firestoreId: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        feedCol.document(firestoreId).delete()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    // -- Shared Alerts ----------------------------------------------------------------------------

    fun addAlert(message: String, type: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        // DEDUP STRATEGY: Use a deterministic document ID derived from message + date.
        // .set() with the same ID is idempotent — writing the same alert twice just
        // overwrites the same Firestore document instead of creating a new one.
        // This eliminates duplicates across all devices with no composite index needed
        // and no race conditions from read-then-write (check-then-insert) patterns.
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        // Build a stable doc ID: sanitize message to allowed Firestore ID chars
        val safeMsg = message.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)
        val docId = "${today}_${safeMsg}"
        alertsCol.document(docId).set(mapOf(
            "message"   to message,
            "type"      to type,
            "timestamp" to FieldValue.serverTimestamp(),
            "dayKey"    to today,
            "isRead"    to false
        ), SetOptions.merge())
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun listenToAlerts(onChange: (List<Map<String, Any?>>) -> Unit): ListenerRegistration {
        return alertsCol
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, _ ->
                val list = snaps?.documents?.map { doc ->
                    val data = doc.data?.toMutableMap() ?: mutableMapOf()
                    data["firestoreId"] = doc.id
                    data as Map<String, Any?>
                } ?: emptyList()
                onChange(list)
            }
    }

    fun markAllAlertsRead(onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        alertsCol.whereEqualTo("isRead", false).get()
            .addOnSuccessListener { snap ->
                val batch = db.batch()
                for (doc in snap.documents) batch.update(doc.reference, "isRead", true)
                batch.commit()
                    .addOnSuccessListener { onDone?.invoke(null) }
                    .addOnFailureListener { e -> onDone?.invoke(e) }
            }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun clearAllAlerts(onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        alertsCol.get()
            .addOnSuccessListener { snap ->
                val batch = db.batch()
                for (doc in snap.documents) batch.delete(doc.reference)
                batch.commit()
                    .addOnSuccessListener { onDone?.invoke(null) }
                    .addOnFailureListener { e -> onDone?.invoke(e) }
            }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    // -- Activity Logs -----------------------------------------------------------------------------
    //
    // Field names intentionally mirror activityService.js's logActivity() on
    // the website so both platforms write compatible documents into the same
    // "activity_logs" collection.
    //
    // logDeletion (below) covers every deletion made from the Android app,
    // grouped by [module]:
    //   "Inventory"  → deleted inventory records (feed/supplements)
    //   "Staff"      → deleted staff accounts (formerly labeled "Accounts")
    //   "Schedules"  → deleted scheduled tasks
    //
    // logLogin / logLogout / logStaffCreated / logStaffUpdated cover
    // authentication events and staff-account create/update, so those show
    // up in the web Activity Logs the same way the "Deleted" ones already do.

    private const val DEVICE_LABEL = "Mobile Application"

    fun logLogin(userName: String, userEmail: String, role: String, onDone: ((Exception?) -> Unit)? = null) {
        activityLogsCol.add(mapOf(
            "type"      to "login",
            "message"   to "$userName logged in",
            "userName"  to userName,
            "userEmail" to userEmail,
            "role"      to role,
            "device"    to DEVICE_LABEL,
            "timestamp" to FieldValue.serverTimestamp()
        )).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun logLogout(userName: String, userEmail: String, role: String, logoutType: String, onDone: ((Exception?) -> Unit)? = null) {
        activityLogsCol.add(mapOf(
            "type"       to "logout",
            "message"    to "$userName logged out",
            "userName"   to userName,
            "userEmail"  to userEmail,
            "role"       to role,
            "device"     to DEVICE_LABEL,
            "logoutType" to logoutType,
            "timestamp"  to FieldValue.serverTimestamp()
        )).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun logStaffCreated(
        actorName: String,
        actorEmail: String,
        actorRole: String,
        targetName: String,
        targetEmail: String,
        onDone: ((Exception?) -> Unit)? = null
    ) {
        // Tagged module "Account" (not "Staff") so this lands in the web
        // Activity Logs' "Created → Account" sub-filter. Deletions/updates
        // of staff accounts still use module "Staff" — only the Created
        // category groups staff-account creation under "Account".
        activityLogsCol.add(mapOf(
            "type"            to "create",
            "module"          to "Account",
            "message"         to "$actorName created a new staff account: $targetName ($targetEmail)",
            "userName"        to actorName,
            "userEmail"       to actorEmail,
            "role"            to actorRole,
            "targetUserName"  to targetName,
            "targetUserEmail" to targetEmail,
            "device"          to DEVICE_LABEL,
            "timestamp"       to FieldValue.serverTimestamp()
        )).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    // Fires when a brand-new inventory product (feed/supplement) is added —
    // see FeedInventoryActivity.commitNewFeedItem. Tagged module "Inventory"
    // so it lands in the web Activity Logs' "Created → Inventory" sub-filter.
    // This is distinct from inventory_history (the quantity-change audit
    // trail, surfaced by the website as "Updated → Inventory"): creating a
    // product is logged here regardless of its starting quantity.
    fun logInventoryCreated(
        actorName: String,
        actorEmail: String,
        actorRole: String,
        productName: String,
        category: String,
        metadata: Map<String, Any?>? = null,
        onDone: ((Exception?) -> Unit)? = null
    ) {
        val entry = mutableMapOf<String, Any>(
            "type"      to "create",
            "module"    to "Inventory",
            "message"   to "$actorName added a new inventory product: $productName",
            "userName"  to actorName,
            "userEmail" to actorEmail,
            "role"      to actorRole,
            "details"   to "Added product: $productName ($category)",
            "device"    to DEVICE_LABEL,
            "timestamp" to FieldValue.serverTimestamp()
        )
        if (metadata != null) entry["metadata"] = metadata

        activityLogsCol.add(entry)
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun logStaffUpdated(
        actorName: String,
        actorEmail: String,
        actorRole: String,
        targetName: String,
        targetEmail: String,
        details: String = "",
        onDone: ((Exception?) -> Unit)? = null
    ) {
        activityLogsCol.add(mapOf(
            "type"            to "update",
            "module"          to "Staff",
            "message"         to "$actorName updated staff account: $targetName ($targetEmail)",
            "userName"        to actorName,
            "userEmail"       to actorEmail,
            "role"            to actorRole,
            "targetUserName"  to targetName,
            "targetUserEmail" to targetEmail,
            "details"         to details,
            "device"          to DEVICE_LABEL,
            "timestamp"       to FieldValue.serverTimestamp()
        )).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    // Covers task edits made from the app — currently the "Request 30min
    // Extension" action in ScheduleActivity.showStatusUpdateDialog(), fired
    // when an owner extends a task's deadline. Mirrors logStaffUpdated's
    // shape (type "update") but tagged module "Tasks" so it sorts into the
    // web Activity Logs "Updated → Tasks" sub-filter.
    fun logTaskUpdated(
        actorName: String,
        actorEmail: String,
        actorRole: String,
        message: String,
        details: String = "",
        metadata: Map<String, Any?>? = null,
        onDone: ((Exception?) -> Unit)? = null
    ) {
        val entry = mutableMapOf<String, Any>(
            "type"      to "update",
            "module"    to "Tasks",
            "message"   to message,
            "userName"  to actorName,
            "userEmail" to actorEmail,
            "role"      to actorRole,
            "details"   to details,
            "device"    to DEVICE_LABEL,
            "timestamp" to FieldValue.serverTimestamp()
        )
        if (metadata != null) entry["metadata"] = metadata

        activityLogsCol.add(entry)
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun logDeletion(
        module: String,
        message: String,
        userName: String,
        userEmail: String = "",
        role: String = "",
        details: String = "",
        metadata: Map<String, Any?>? = null,
        onDone: ((Exception?) -> Unit)? = null
    ) {
        val entry = mutableMapOf<String, Any>(
            "type"      to "delete",
            "module"    to module,
            "message"   to message,
            "userName"  to userName,
            "userEmail" to userEmail,
            "role"      to role,
            "details"   to details,
            "device"    to DEVICE_LABEL,
            "timestamp" to FieldValue.serverTimestamp()
        )
        if (metadata != null) entry["metadata"] = metadata

        activityLogsCol.add(entry)
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e ->
                // Deletion logging should never block the deletion itself.
                onDone?.invoke(e)
            }
    }

    /**
     * Delete a specific alert by its message. The doc ID is deterministic
     * (same formula used in addAlert) so no query is needed.
     * Also deletes any alert whose message CONTAINS the given substring,
     * to catch both "Task Reminder: X (category)" and "Missed Task: X" variants.
     */
    fun deleteAlertByMessage(message: String, onDone: ((Exception?) -> Unit)? = null) {
        if (blockedByMaintenance(onDone)) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val safeMsg = message.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)
        val docId = "${today}_${safeMsg}"

        // Delete the exact doc by deterministic ID
        alertsCol.document(docId).delete()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { onDone?.invoke(null) } // ignore if not found

        // Also do a full scan to catch any older docs (pre-deterministic-ID) or
        // "Missed Task" variants that contain the task title
        alertsCol.get().addOnSuccessListener { snap ->
            val batch = db.batch()
            var found = false
            for (doc in snap.documents) {
                val msg = doc.getString("message") ?: ""
                if (msg.contains(message, ignoreCase = true)) {
                    batch.delete(doc.reference)
                    found = true
                }
            }
            if (found) batch.commit()
        }
    }
}