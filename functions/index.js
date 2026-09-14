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
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const nodemailer = require("nodemailer");

// Same region as the RTDB instance (asia-southeast1), so the RTDB trigger
// is valid and everything lives together.
setGlobalOptions({ region: "asia-southeast1" });

initializeApp();
const db = getFirestore();

const TOPIC_FARM_ALERTS = "farm_alerts";
const CHANNEL_ALERTS = "alerts_channel";
const CHANNEL_TASK_REMINDER = "task_reminder_channel";

// ── Email (Gmail) ─────────────────────────────────────────────────────────
// Credentials are pulled from Cloud Functions secrets, NOT hardcoded or
// committed. Set them once per project with:
//   firebase functions:secrets:set GMAIL_USER
//   firebase functions:secrets:set GMAIL_APP_PASSWORD
// GMAIL_APP_PASSWORD must be a 16-character Gmail "App Password"
// (myaccount.google.com/apppasswords) for the GMAIL_USER account, not that
// account's normal login password — Gmail rejects SMTP login with the
// regular password if 2FA is on, and app passwords let us send without
// exposing the actual account credentials.
const GMAIL_USER = defineSecret("GMAIL_USER");
const GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD");

// Built lazily (only once a function that declares these secrets actually
// runs) so functions that don't need email never try to read the secrets.
let cachedTransporter = null;
function getMailTransporter() {
  if (!cachedTransporter) {
    cachedTransporter = nodemailer.createTransport({
      service: "gmail",
      auth: {
        user: GMAIL_USER.value(),
        pass: GMAIL_APP_PASSWORD.value(),
      },
    });
  }
  return cachedTransporter;
}

// Best-effort — a failed email should never take down the push notification
// or throw the whole function, since the push is the more important channel.
async function sendAssignmentEmail(toEmail, subject, text) {
  try {
    await getMailTransporter().sendMail({
      from: `"Waje's Quail Farm" <${GMAIL_USER.value()}>`,
      to: toEmail,
      subject,
      text,
    });
    logger.info(`Assignment email sent to ${toEmail}`);
  } catch (e) {
    logger.error(`Failed to email ${toEmail}: ${e.message}`);
  }
}

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
 * Writes the dedup/alert doc and, only if it didn't already exist today,
 * sends the FCM push. Returns true if a push was sent.
 */
async function raiseAlert({ message, type, topic, title, body, channel }) {
  const docId = `${todayKey()}_${sanitize(message)}`;
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
      // Already alerted today — same behaviour as wasAlreadyAlertedToday().
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
      notifId: String(stableId(message)),
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
 */
async function raiseTaskAlertForAssignees({ message, type, assignees, title, body, channel }) {
  if (!assignees || assignees.length === 0) {
    logger.info(`No assignees for task alert, skipping: ${message}`);
    return false;
  }

  const docId = `${todayKey()}_${sanitize(message)}`;
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
exports.onFeedWrite = onDocumentWritten(
  "farm_data/shared/feed/{feedId}",
  async (event) => {
    const after = event.data?.after;
    if (!after || !after.exists) return; // ignore deletes

    const doc = after.data();
    const qty = doc.quantity ?? 0;
    const name = doc.name ?? "Item";

    if (qty === 0) {
      const message = `Inventory Alert: ${name} is STOCK DEPLETED`;
      await raiseAlert({
        message,
        type: "Critical",
        topic: TOPIC_FARM_ALERTS,
        title: "Inventory Alert",
        body: message,
        channel: CHANNEL_ALERTS,
      });
      return;
    }

    const status = doc.status ?? "";
    if (status === "Low Stock" || status === "Medium") {
      const message = `Inventory Alert: ${name} is currently ${status}`;
      await raiseAlert({
        message,
        type: "Inventory",
        topic: TOPIC_FARM_ALERTS,
        title: "Inventory Update",
        body: message,
        channel: CHANNEL_ALERTS,
      });
    }
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

    const resolved = resolveWaterLevelAlert(Number(percent));
    if (!resolved) return;
    const [label, description] = resolved;
    const message = `Water Level ${label}: ${description}`;

    await raiseAlert({
      message,
      type: "Water Level",
      topic: TOPIC_FARM_ALERTS,
      title: `Water Level ${label}`,
      body: description,
      channel: CHANNEL_ALERTS,
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
  { document: "farm_data/shared/tasks/{taskId}", secrets: [GMAIL_USER, GMAIL_APP_PASSWORD] },
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
    const category = afterData.category || "";
    const dateStr = (afterData.year !== undefined && afterData.month !== undefined && afterData.day !== undefined)
      ? `${afterData.day}/${afterData.month + 1}/${afterData.year}`
      : "an upcoming date";
    const timeStr = afterData.time || "";

    // taskId folded into the dedup message so this can't collide with
    // raiseTaskAlertForAssignees' "Missed Task" message, or with another
    // task instance (e.g. a recurring series) that shares the same title
    // and gets assigned on the same day.
    const message = `New Task Assigned: ${title} (${event.params.taskId})`;

    await raiseTaskAlertForAssignees({
      message,
      type: "Task Assigned",
      assignees: newAssignees,
      title: "New Task Assigned",
      body: `You've been assigned: ${title}`,
      channel: CHANNEL_TASK_REMINDER,
    });

    // Email runs alongside the push, not instead of it — each assignee's
    // `assignedTo` entry is their email, so send directly, no lookup needed.
    const emailBody =
      `You've been assigned a new task on Waje's Quail Farm.\n\n` +
      `Task: ${title}\n` +
      `Category: ${category || "N/A"}\n` +
      `Scheduled: ${dateStr}${timeStr ? " at " + timeStr : ""}\n` +
      (afterData.assignedBy ? `Assigned by: ${afterData.assignedBy}\n` : "") +
      `\nOpen the app's Schedule tab to see the full details.`;

    await Promise.all(
      newAssignees.map((email) =>
        sendAssignmentEmail(email, `New Task Assigned: ${title}`, emailBody)
      )
    );
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