package com.example.exp1

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AckRequest(
    val id: String,
    val kind: String,          // "alert" | "task"
    val severity: String,      // "critical" | "normal"
    val mode: String,          // "any" = first Accept closes it for everyone, "each" = every recipient answers
    val title: String,
    val message: String,
    val recipients: List<String>,
    val responses: Map<String, Map<String, Any?>>,
    val resolved: Boolean,
    val createdAtMillis: Long
)

/**
 * Accept/Decline requests (critical alerts + task assignments) and the app-wide
 * monitor that turns pending ones into alarms/notifications on this device.
 */
object AckRepository {
    private const val TAG = "AckRepository"
    private const val ALARM_WINDOW_MS = 30 * 60 * 1000L        // only ring for fresh critical requests
    private const val STALE_MS = 3 * 24 * 60 * 60 * 1000L      // ignore pending requests older than this
    private const val DECLINE_NOTICE_WINDOW_MS = 5 * 60 * 1000L
    private const val MONITOR_LIMIT = 30L

    private val db get() = FirebaseFirestore.getInstance()
    private val col get() = db.collection("farm_data").document("shared").collection("ack_requests")

    private data class Person(val email: String, val phone: String?)

    fun userKey(email: String) = email.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    // Same formula as FarmRepository.addAlert's doc id, so devices and the server script agree.
    private fun idFor(message: String): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return "${today}_${message.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)}"
    }

    fun parseAssignees(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is String -> listOfNotNull(raw.trim().takeIf { it.isNotEmpty() })
        else -> emptyList()
    }

    fun isPendingFor(req: AckRequest, email: String): Boolean =
        req.recipients.any { it.equals(email, ignoreCase = true) } &&
                !req.responses.containsKey(userKey(email)) &&
                !(req.mode == "any" && req.resolved)

    // ── Creating requests ────────────────────────────────────────────────────

    private fun fetchPeople(only: Collection<String>?, cb: (List<Person>) -> Unit) {
        val wanted = only?.map { it.trim().lowercase() }?.toSet()
        db.collection("user_access").whereEqualTo("status", "approved").get()
            .addOnSuccessListener { snap ->
                cb(snap.documents
                    .filter { it.getBoolean("isActive") != false }
                    .filter { wanted == null || it.id.trim().lowercase() in wanted }
                    .map { Person(it.id, SmsGateway.normalizePhone(it.getString("phoneNumber"))) })
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "recipient lookup failed: ${e.message}")
                cb(emptyList())
            }
    }

    /** Creates the request only if it doesn't exist yet; whoever creates it sends the SMS (so it goes out once). */
    private fun createRequest(
        id: String, kind: String, severity: String, mode: String,
        title: String, message: String, people: List<Person>,
        sms: String, priority: Boolean, done: ((Boolean) -> Unit)? = null
    ) {
        val ref = col.document(id)
        val data = hashMapOf<String, Any>(
            "kind" to kind, "severity" to severity, "mode" to mode,
            "title" to title, "message" to message,
            "recipients" to people.map { it.email },
            "responses" to hashMapOf<String, Any>(),
            "resolved" to false,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.runTransaction(Transaction.Function<Boolean> { tx ->
            if (tx.get(ref).exists()) false else { tx.set(ref, data); true }
        }).addOnSuccessListener { created ->
            if (created) SmsGateway.send(people.map { it.phone }, sms, priority)
            done?.invoke(true)
        }.addOnFailureListener { e ->
            Log.e(TAG, "createRequest failed: ${e.message}")
            done?.invoke(false)
        }
    }

    /**
     * Critical alert: alarm + SMS for everyone (or just [recipients]). [onFallback] runs if the request
     * couldn't be created (offline, nobody eligible), so the caller can still show a plain notification.
     */
    fun raiseCritical(
        title: String, message: String,
        recipients: List<String>? = null, onFallback: (() -> Unit)? = null
    ) {
        fetchPeople(recipients) { people ->
            if (people.isEmpty()) { onFallback?.invoke(); return@fetchPeople }
            createRequest(
                idFor(message), "alert", "critical", "any", title, message, people,
                "Waje's Quail Farm CRITICAL: $message. Open the app to Accept/Decline.", true
            ) { ok -> if (!ok) onFallback?.invoke() }
        }
    }

    /** One request per task SAVE (a recurring series is one request). Call from ScheduleActivity. */
    @JvmStatic
    fun createTaskAssignmentRequest(
        groupId: String, title: String, category: String?,
        date: String?, time: String?, assignees: List<String>
    ) {
        if (assignees.isEmpty()) return
        fetchPeople(assignees) { people ->
            if (people.isEmpty()) return@fetchPeople
            val whenText = listOfNotNull(date?.takeIf { it.isNotBlank() }, time?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            val detail = buildString {
                append(title)
                if (!category.isNullOrBlank()) append(" ($category)")
                if (whenText.isNotEmpty()) append(", starting $whenText")
            }
            createRequest(
                "assign_$groupId", "task", "normal", "each", "New Task Assigned", "Task: $detail", people,
                "Waje's Quail Farm: New task - $detail. Open the app to Accept/Decline.", false
            )
        }
    }

    // ── Responding ───────────────────────────────────────────────────────────

    fun load(ackId: String, cb: (AckRequest?) -> Unit) {
        col.document(ackId).get()
            .addOnSuccessListener { cb(if (it.exists()) parse(it) else null) }
            .addOnFailureListener { cb(null) }
    }

    fun respond(context: Context, ackId: String, accepted: Boolean, reason: String?, done: ((Boolean) -> Unit)? = null) {
        val ctx = context.applicationContext
        val am = AccountManager(ctx)
        val me = am.getCurrentUsername()
        if (me == null) { done?.invoke(false); return }
        val name = am.getCachedName(me)
        val ref = col.document(ackId)

        ref.get().addOnSuccessListener { snap ->
            if (!snap.exists()) { done?.invoke(false); return@addOnSuccessListener }
            val req = parse(snap)
            val response = hashMapOf<String, Any>(
                "email" to me, "name" to name,
                "status" to if (accepted) "accepted" else "declined",
                "reason" to (reason ?: ""),
                "respondedAt" to FieldValue.serverTimestamp()
            )
            val write = if (accepted && req.mode == "any")
                ref.update(FieldPath.of("responses", userKey(me)), response, "resolved", true, "resolvedBy", me)
            else
                ref.update(FieldPath.of("responses", userKey(me)), response)

            write.addOnSuccessListener {
                AlarmNotifier.cancel(ctx, ackId)
                // Visible to the owner (and everyone) in the Alerts list.
                val verdict = if (accepted) "accepted" else "declined"
                val note = if (accepted) "" else " - Reason: $reason"
                FarmRepository.addAlert("$name $verdict: ${req.message}$note", "Request")
                done?.invoke(true)
            }.addOnFailureListener { e ->
                Log.e(TAG, "respond failed: ${e.message}")
                done?.invoke(false)
            }
        }.addOnFailureListener { done?.invoke(false) }
    }

    // ── Monitor: turns pending requests into alarms/notifications on this device ─

    private var appContext: Context? = null
    private var listener: ListenerRegistration? = null
    private var lastRequests: List<AckRequest> = emptyList()
    private val shown = mutableSetOf<String>()
    private val declineNotified = mutableSetOf<String>()

    /** Call once from WajeApplication.onCreate(). Safe to call again. */
    fun startMonitor(app: Application) {
        appContext = app.applicationContext
        if (listener != null) return
        listener = col.orderBy("createdAt", Query.Direction.DESCENDING).limit(MONITOR_LIMIT)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    // Snapshot listeners die on error (e.g. auth not ready yet) — re-attach shortly.
                    listener?.remove(); listener = null
                    Handler(Looper.getMainLooper()).postDelayed({ startMonitor(app) }, 15_000L)
                    return@addSnapshotListener
                }
                lastRequests = snap.documents.map { parse(it) }
                evaluate()
            }
    }

    /** Re-check pending requests for whoever is logged in now (call after login lands on the dashboard). */
    fun reevaluate(context: Context) {
        if (listener == null) startMonitor(context.applicationContext as Application) else evaluate()
    }

    private fun evaluate() {
        val ctx = appContext ?: return
        val am = AccountManager(ctx)
        val me = am.getCurrentUsername() ?: return
        val myKey = userKey(me)
        val isOwner = RoleManager(am.getCurrentRole()).isOwner
        val now = System.currentTimeMillis()

        for (req in lastRequests) {
            val mine = req.recipients.any { it.equals(me, ignoreCase = true) }
            if (mine) {
                if (isPendingFor(req, me)) {
                    if (now - req.createdAtMillis < STALE_MS && shown.add(req.id)) {
                        val ring = req.severity == "critical" && now - req.createdAtMillis < ALARM_WINDOW_MS
                        // Schedule/task-assignment notifications no longer offer Accept/Decline
                        // directly in the notification shade; tapping still opens the full
                        // Accept/Decline screen. Critical alerts keep the quick actions.
                        AlarmNotifier.show(ctx, req.id, req.title, req.message, ring, quickActions = req.kind != "task")
                    }
                } else {
                    AlarmNotifier.cancel(ctx, req.id)   // answered here/elsewhere, or someone else accepted
                }
            }
            if (isOwner) noticeDeclines(req, myKey, now)
        }
    }

    private fun noticeDeclines(req: AckRequest, myKey: String, now: Long) {
        for ((key, r) in req.responses) {
            if (key == myKey || r["status"] != "declined") continue
            val at = (r["respondedAt"] as? Timestamp)?.toDate()?.time ?: now
            if (now - at > DECLINE_NOTICE_WINDOW_MS) continue
            if (!declineNotified.add("${req.id}|$key")) continue
            AlertsMonitor.showLocalNotification(
                "Request declined",
                "${r["name"] ?: "Staff"} declined: ${req.message} - Reason: ${r["reason"] ?: ""}"
            )
        }
    }

    private fun parse(doc: DocumentSnapshot): AckRequest {
        @Suppress("UNCHECKED_CAST")
        val responses = doc.get("responses") as? Map<String, Map<String, Any?>> ?: emptyMap()
        return AckRequest(
            id = doc.id,
            kind = doc.getString("kind") ?: "alert",
            severity = doc.getString("severity") ?: "normal",
            mode = doc.getString("mode") ?: "any",
            title = doc.getString("title") ?: "Waje's Quail Farm",
            message = doc.getString("message") ?: "",
            recipients = (doc.get("recipients") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            responses = responses,
            resolved = doc.getBoolean("resolved") ?: false,
            createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()
        )
    }
}
