/**
 * Server-side mirror of AlertsMonitor.kt's detection logic.
 *
 * WHY THIS EXISTS: AlertsMonitor's live Firestore/RTDB listeners only run
 * while the Android app's process is alive (started once from
 * WajeApplication.onCreate()). That means low stock, a bad water level, or
 * an overdue task only produced a notification if the app happened to still
 * be running. These functions watch the same data server-side — where
 * nothing depends on any device being open — and push an FCM notification
 * to the device(s) via topic. WajeFirebaseMessagingService.kt on the client
 * receives and displays it.
 *
 * Dedup strategy is copied from FarmRepository.addAlert(): a deterministic
 * doc ID of `${yyyy-MM-dd}_${sanitizedMessage}` under farm_data/shared/alert.
 * We .create() that doc (not .set()) — if it already exists today, create()
 * throws ALREADY_EXISTS and we skip the push. This reuses the exact same
 * "alert" collection the app already reads/displays in AlertsActivity, so
 * server-raised alerts show up in-app too, and both sides can never
 * double-alert for the same thing on the same day.
 */

const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onValueWritten } = require("firebase-functions/v2/database");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { setGlobalOptions } = require("firebase-functions/v2");
const logger = require("firebase-functions/logger");

// Same region as the RTDB instance (asia-southeast1), so the RTDB trigger
// is valid and everything lives together.
setGlobalOptions({ region: "asia-southeast1" });

initializeApp();
const db = getFirestore();

const TOPIC_FARM_ALERTS = "farm_alerts";
const CHANNEL_ALERTS = "alerts_channel";
const CHANNEL_TASK_REMINDER = "task_reminder_channel";

// FIX: task-assignment email used to be sent from here via Gmail SMTP
// (nodemailer). Removed — this file requires the Blaze billing plan to
// even deploy (Cloud Functions v2 runs on Cloud Run/Cloud Build), which
// this project isn't on; see scripts/checkAlerts.js for the actual
// Blaze-free replacement that runs in production. Email now goes out
// directly from the Android client (ScheduleActivity.sendTaskAssignment
// EmailsDirect()) the instant a task is saved, via the same Apps Script
// web app the invite-email flow already uses — no Gmail SMTP credentials,
// no server-side hop, and no dependency on this file (or its GitHub
// Actions equivalent) being deployed/running at all. Push notifications
// stay here since they still need something that runs independent of
// whichever device made the assignment.

// ── Shared dedupe + push helper ──────────────────────────────────────────
// Mirrors FarmRepository.addAlert()'s ID scheme exactly so both the app and
// these functions write into the same de-duplicated document space.
function todayKey() {
  // yyyy-MM-dd in the server's default timezone, matching the client's
  // SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) closely enough for
  // same-day dedup purposes.
  return new Date().toISOString().slice(0, 10);
}

function sanitize(message) {
  return message.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 80);
}

// Small stand-in for a stable per-message notification ID (doesn't need to
// match Java's String.hashCode() exactly — just needs to be stable and not
// collide often — the Firestore doc ID is the real dedupe key).
function stableId(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (Math.imul(31, hash) + str.charCodeAt(i)) | 0;
  }
  return hash;
}

/**
 * Writes the dedup/alert doc and, only if it didn't already exist, sends the
 * FCM push. Returns true if a push was sent.
 *
 * `dedupKey` overrides the default day+message dedup ID. That default meant
 * an alert could only ever fire once per calendar day for a given message —
 * fine for a one-off event, but wrong for a repeatable condition like stock
 * depletion or a water-level state: depleted → restocked → depleted again
 * should alert both times, not just the first. Callers that track their own
 * state transitions (see onFeedWrite/onWaterLevelWrite) pass a dedupKey
 * scoped to this specific write event instead, so dedup only protects
 * against the same event retrying, not against a genuine second occurrence
 * later the same day.
 */
async function raiseAlert({ message, type, topic, title, body, channel, dedupKey }) {
  const docId = dedupKey || `${todayKey()}_${sanitize(message)}`;
  const alertRef = db.collection("farm_data").doc("shared").collection("alert").doc(docId);

  try {
    await alertRef.create({
      message,
      type,
      timestamp: FieldValue.serverTimestamp(),
      dayKey: todayKey(),
      isRead: false,
    });
  } catch (e) {
    if (e.code === 6 || e.code === "already-exists") {
      // Same event retried, or (default key only) already alerted today.
      return false;
    }
    throw e;
  }

  await getMessaging().send({
    topic,
    data: {
      title,
      body,
      channel,
      notifId: String(stableId(message + docId)),
    },
    android: { priority: "high" },
  });

  logger.info(`Alert raised + pushed: ${message}`);
  return true;
}

// ── Per-user push targeting for schedule/task alerts ─────────────────────
// FIX: task reminders used to broadcast to one shared "task_reminders" topic
// that every device with Schedule alerts on was subscribed to — so a task
// notification meant for one staff member landed on everyone's phone,
// regardless of that task's `assignedTo` list. Each device now subscribes
// (see PushTopics.kt) to a topic derived from its OWN logged-in user's
// email, and this function pushes individually to each assignee's topic
// instead of one shared topic.

// MUST exactly mirror PushTopics.kt's topicForUser() sanitization — any
// mismatch means a push silently goes to a topic no device subscribed to.
function topicForUser(email) {
  return "user_" + email.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "_");
}

// Reads `assignedTo` defensively: new docs store an array (multi-assign),
// older docs stored a single string email. Mirrors
// ScheduleActivity.parseAssignedTo() / AlertsMonitor.isAssignedTo().
function parseAssignedTo(raw) {
  if (Array.isArray(raw)) {
    return raw.map((v) => (v == null ? "" : String(v).trim())).filter((v) => v.length > 0);
  }
  if (typeof raw === "string" && raw.trim().length > 0) return [raw.trim()];
  return [];
}

/**
 * Task-specific alert raiser. Writes the same shared alert record raiseAlert()
 * does (deduped per day+message, so it still shows once in the Alerts screen
 * regardless of how many assignees there are), then pushes to EACH assignee's
 * personal topic individually, with its own per-assignee-per-day dedup doc —
 * so two assignees on the same task each get exactly one push today, and one
 * assignee's push isn't blocked just because another assignee already
 * triggered the shared alert record.
 *
 * Tasks with no assignees are skipped entirely: with the old broadcast topic
 * everyone found out about every task anyway, but per the app's requirement
 * that schedule notifications only go to assigned individuals, an
 * unassigned task has no one to notify.
 *
 * `dedupKey` overrides the default day+message dedup ID. The default (day +
 * truncated message) is right for checkOverdueTasks, which polls repeatedly
 * and needs day-scoped dedup so it doesn't re-alert every 15 minutes — but
 * it's wrong for onTaskAssigned, an event-driven trigger that already only
 * fires for genuinely new assignees. Reusing the day-scoped key there caused
 * two real bugs: (1) sanitize() truncates to 80 chars, and since the
 * taskId sits at the END of the assignment message, a long title could
 * silently chop it off, making two different tasks collide on the same
 * dedup doc; (2) same-day re-assignment (unassign + reassign the same
 * person later that day — e.g. after a declined task) produced the exact
 * same message text, so it hit the first assignment's dedup doc and never
 * pushed again. Callers that pass an explicit dedupKey bypass both issues.
 */
async function raiseTaskAlertForAssignees({ message, type, assignees, title, body, channel, dedupKey }) {
  if (!assignees || assignees.length === 0) {
    logger.info(`No assignees for task alert, skipping: ${message}`);
    return false;
  }

  const docId = dedupKey || `${todayKey()}_${sanitize(message)}`;
  const alertRef = db.collection("farm_data").doc("shared").collection("alert").doc(docId);
  try {
    await alertRef.create({
      message,
      type,
      timestamp: FieldValue.serverTimestamp(),
      dayKey: todayKey(),
      isRead: false,
    });
  } catch (e) {
    if (e.code !== 6 && e.code !== "already-exists") throw e;
    // Alert record already exists today (e.g. another assignee's run already
    // wrote it) — fine, per-assignee push dedup below is independent of this.
  }

  let anyPushed = false;
  for (const email of assignees) {
    const pushDocId = `${docId}__${sanitize(email)}`;
    const pushRef = db.collection("farm_data").doc("shared").collection("_taskPushLog").doc(pushDocId);
    try {
      await pushRef.create({ email, message, timestamp: FieldValue.serverTimestamp() });
    } catch (e) {
      if (e.code === 6 || e.code === "already-exists") continue; // already pushed to this assignee today
      throw e;
    }

    await getMessaging().send({
      topic: topicForUser(email),
      data: {
        title,
        body,
        channel,
        notifId: String(stableId(message + email)),
      },
      android: { priority: "high" },
    });
    anyPushed = true;
  }

  if (anyPushed) logger.info(`Task alert pushed to [${assignees.join(", ")}]: ${message}`);
  return anyPushed;
}

// ── Inventory: farm_data/shared/feed/{feedId} ────────────────────────────
// Mirrors AlertsMonitor.startInventoryListener().
//
// Classifies a feed doc into an alert state (or null if it's fine). Used on
// both the before- and after-write data so onFeedWrite can alert on the
// TRANSITION into a bad state rather than on every write while it stays bad
// — that's what lets a depleted → restocked → depleted-again cycle alert
// twice instead of the first depletion silently eating the day's only alert.
function feedAlertState(doc) {
  if (!doc) return null;
  const qty = doc.quantity ?? 0;
  if (qty === 0) return "DEPLETED";
  const status = doc.status ?? "";
  if (status === "Low Stock" || status === "Medium") return status;
  return null;
}

exports.onFeedWrite = onDocumentWritten(
  "farm_data/shared/feed/{feedId}",
  async (event) => {
    const after = event.data?.after;
    if (!after || !after.exists) return; // ignore deletes

    const before = event.data?.before;
    const beforeState = before && before.exists ? feedAlertState(before.data()) : null;
    const afterState = feedAlertState(after.data());

    // Not in a bad state, or unchanged from the previous write (redundant
    // save while still e.g. DEPLETED) — nothing new to alert on.
    if (!afterState || afterState === beforeState) return;

    const doc = after.data();
    const name = doc.name ?? "Item";
    const message = afterState === "DEPLETED"
      ? `Inventory Alert: ${name} is STOCK DEPLETED`
      : `Inventory Alert: ${name} is currently ${afterState}`;

    await raiseAlert({
      message,
      type: afterState === "DEPLETED" ? "Critical" : "Inventory",
      topic: TOPIC_FARM_ALERTS,
      title: afterState === "DEPLETED" ? "Inventory Alert" : "Inventory Update",
      body: message,
      channel: CHANNEL_ALERTS,
      dedupKey: event.id, // idempotency against this exact write retrying
    });
  }
);

// ── Water level: /water_level/percentage (RTDB) ──────────────────────────
// Mirrors AlertsMonitor.resolveWaterLevelAlert() + raiseWaterLevelAlert().
function resolveWaterLevelAlert(percent) {
  if (percent > 50) return ["Filled", "Water tank is filled and at a healthy level."];
  return null;
}

exports.onWaterLevelWrite = onValueWritten(
  {
    ref: "/water_level/percentage",
    instance: "exp1-cff54-default-rtdb",
  },
  async (event) => {
    const percent = event.data.after.val();
    if (percent === null || percent === undefined) return;

    const afterResolved = resolveWaterLevelAlert(Number(percent));
    if (!afterResolved) return;

    // Same transition-on-change logic as onFeedWrite: only alert when this
    // write actually crosses into (or changes) the alert state, so refilling
    // then draining again re-alerts instead of being stuck dedup'd all day.
    const beforePercent = event.data.before?.val?.();
    const beforeResolved = beforePercent === null || beforePercent === undefined
      ? null
      : resolveWaterLevelAlert(Number(beforePercent));
    if (beforeResolved && beforeResolved[0] === afterResolved[0]) return;

    const [label, description] = afterResolved;
    const message = `Water Level ${label}: ${description}`;

    await raiseAlert({
      message,
      type: "Water Level",
      topic: TOPIC_FARM_ALERTS,
      title: `Water Level ${label}`,
      body: description,
      channel: CHANNEL_ALERTS,
      dedupKey: event.id, // idempotency against this exact write retrying
    });
  }
);

// ── New task assignment: farm_data/shared/tasks/{taskId} ─────────────────
// FIX: staff previously only found out about a task via checkOverdueTasks,
// i.e. only AFTER it was already missed. Nothing notified them the moment
// a task was actually assigned. This fires on every write to a task doc and
// notifies (push + email) any assignee who is NEW on this write (present in
// `after` but not in `before`) — so creating a task notifies every initial
// assignee, and later adding a staff member to an existing task notifies
// just that person, without re-pinging assignees who were already on the
// task for an unrelated edit (e.g. marking it done, changing its time).
//
// `assignedTo` entries ARE the staff member's email addresses (see
// ScheduleActivity's assignee selector, which is keyed by email) — so no
// extra user lookup is needed to know who to email.
exports.onTaskAssigned = onDocumentWritten(
  "farm_data/shared/tasks/{taskId}",
  async (event) => {
    const before = event.data?.before;
    const after = event.data?.after;
    if (!after || !after.exists) return; // ignore deletes

    const afterData = after.data();
    const afterAssignees = parseAssignedTo(afterData.assignedTo);
    if (afterAssignees.length === 0) return;

    const beforeAssignees = before && before.exists
      ? parseAssignedTo(before.data().assignedTo)
      : [];
    const newAssignees = afterAssignees.filter((e) => !beforeAssignees.includes(e));
    if (newAssignees.length === 0) return;

    const title = afterData.title || "Task";

    // taskId folded into the dedup message so this can't collide with
    // raiseTaskAlertForAssignees' "Missed Task" message, or with another
    // task instance (e.g. a recurring series) that shares the same title
    // and gets assigned on the same day.
    const message = `New Task Assigned: ${title} (${event.params.taskId})`;

    // Keyed to this specific write (taskId + Firestore event ID), not to
    // "today" + the message text — see raiseTaskAlertForAssignees' doc
    // comment for why the day-scoped default caused pushes to silently stop
    // firing on same-day re-assignments and on long/colliding titles. Event
    // ID retries of this exact write still dedup correctly; a genuinely new
    // write (a real re-assignment) always gets a fresh key and always pushes.
    //
    // Email is NOT sent from here — see the top-of-file note. It goes out
    // directly from the Android client the moment the task is saved.
    await raiseTaskAlertForAssignees({
      message,
      type: "Task Assigned",
      assignees: newAssignees,
      title: "New Task Assigned",
      body: `You've been assigned: ${title}`,
      channel: CHANNEL_TASK_REMINDER,
      dedupKey: `assign_${event.params.taskId}_${event.id}`,
    });
  }
);

// ── Overdue tasks: farm_data/shared/tasks, polled every 15 min ───────────
// Mirrors AlertsMonitor.startTasksListener(). Polled rather than event-driven
// since "became overdue" is a function of time passing, not a write.
exports.checkOverdueTasks = onSchedule("every 15 minutes", async () => {
  const snapshot = await db
    .collection("farm_data")
    .doc("shared")
    .collection("tasks")
    .where("status", "==", "Pending")
    .get();

  const now = new Date();

  for (const doc of snapshot.docs) {
    const data = doc.data();
    const { year, month, day, title, hour = 23, minute = 59 } = data;
    if (year === undefined || month === undefined || day === undefined || !title) continue;

    // month is stored 0-indexed, same convention as Android's Calendar and
    // JS's Date, so no conversion needed.
    const taskDate = new Date(year, month, day, hour, minute);
    if (taskDate >= now) continue;

    const assignees = parseAssignedTo(data.assignedTo);
    const message = `Missed Task: ${title} was scheduled for ${day}/${month + 1}/${year}`;
    await raiseTaskAlertForAssignees({
      message,
      type: "Critical",
      assignees,
      title: "Missed Task",
      body: message,
      channel: CHANNEL_TASK_REMINDER,
    });
  }
});