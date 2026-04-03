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
    //    farm_data/shared/alerts/{id}      ← sub-collection under document "shared"


    private val statsDoc  get() = db.collection("farm_data").document("stats")
    private val sharedDoc get() = db.collection("farm_data").document("shared")

    private val tasksCol  get() = sharedDoc.collection("tasks")
    private val feedCol   get() = sharedDoc.collection("feed")
    private val alertsCol get() = sharedDoc.collection("alerts")

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
        tasksCol.add(taskData)
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun updateTaskStatus(firestoreId: String, newStatus: String, onDone: ((Exception?) -> Unit)? = null) {
        tasksCol.document(firestoreId).update("status", newStatus)
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun deleteTask(firestoreId: String, onDone: ((Exception?) -> Unit)? = null) {
        tasksCol.document(firestoreId).delete()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun deleteTasksByGroupId(groupId: String, onDone: ((Exception?) -> Unit)? = null) {
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
        val batch = db.batch()
        for (data in taskDataList) batch.set(tasksCol.document(), data)
        batch.commit()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun batchDeleteTasks(firestoreIds: List<String>, onDone: ((Exception?) -> Unit)? = null) {
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
        feedCol.add(mapOf(
            "name"      to name,
            "status"    to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    fun updateFeedItem(firestoreId: String, name: String, status: String, onDone: ((Exception?) -> Unit)? = null) {
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

    fun deleteFeedItem(firestoreId: String, onDone: ((Exception?) -> Unit)? = null) {
        feedCol.document(firestoreId).delete()
            .addOnSuccessListener { onDone?.invoke(null) }
            .addOnFailureListener { e -> onDone?.invoke(e) }
    }

    // -- Shared Alerts ----------------------------------------------------------------------------

    fun addAlert(message: String, type: String, onDone: ((Exception?) -> Unit)? = null) {
        alertsCol.add(mapOf(
            "message"   to message,
            "type"      to type,
            "timestamp" to FieldValue.serverTimestamp(),
            "isRead"    to false
        )).addOnSuccessListener { onDone?.invoke(null) }
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
}