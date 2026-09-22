/**
 * Standalone replacement for functions/index.js.
 *
 * WHY THIS EXISTS: Cloud Functions (2nd gen) requires the Blaze plan because
 * it runs on Cloud Run/Cloud Build, which Google requires a billing account
 * for — even if you stay inside the free quota. Firestore, Realtime
 * Database, and FCM sending do NOT require Blaze; they work fine on the free
 * Spark plan via the Admin SDK. So instead of a Cloud Function that reacts
 * to writes in real time, this script POLLS the same data on a timer (run by
 * GitHub Actions, cron-job.org, or any scheduler) and does the exact same
 * dedup + push logic as the original functions/index.js.
 *
 * The event-triggered checks (onFeedWrite, onWaterLevelWrite) become simple
 * "read current state, check the condition, alert if needed" checks — since
 * the dedup key is `${day}_${sanitizedMessage}`, running this every few
 * minutes and re-checking current state has the same effect as reacting to
 * every write, just with up-to-N-minutes latency instead of instant.
 *
 * No changes are needed on the Android app side — WajeFirebaseMessagingService.kt
 * and PushTopics.kt don't know or care whether the push came from a Cloud
 * Function or this script; both just call FCM's send API.
 */

const { initializeApp, cert } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getDatabase } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");

// ── Config ────────────────────────────────────────────────────────────────
const RTDB_URL = "https://exp1-cff54-default-rtdb.asia-southeast1.firebasedatabase.app";
const TOPIC_FARM_ALERTS = "farm_alerts";
const CHANNEL_ALERTS = "alerts_channel";
const CHANNEL_TASK_REMINDER = "task_reminder_channel";

// Service account JSON comes from an env var (see .github/workflows/check-alerts.yml)
// so the key never has to be committed to the repo.
const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
if (!serviceAccountJson) {
  console.error("Missing FIREBASE_SERVICE_ACCOUNT_KEY env var.");
  process.exit(1);
}

// FIX: task-assignment email now goes out directly from the Android client
// (ScheduleActivity.sendTaskAssignmentEmailsDirect(), same Apps Script web
// app as the invite-email flow) the instant a task is saved — not from
// here. This script never got a reliable email delivery path of its own:
// it depends on this GitHub Actions workflow actually running on schedule,
// with FIREBASE_SERVICE_ACCOUNT_KEY correctly configured, before an email
// would go out at all. Sending directly from the device that just made the
// assignment removes that whole dependency chain. Push notifications stay
// here (see raiseAssignmentAlertForNewAssignees below) since those still
// need something that runs even when the assigning device isn't the one
// receiving the notification.

initializeApp({
  credential: cert(JSON.parse(serviceAccountJson)),
  databaseURL: RTDB_URL,
});

const db = getFirestore();
const rtdb = getDatabase();

// ── Helpers — mirror functions/index.js exactly ─────────────────────────
function todayKey() {
  return new Date().toISOString().slice(0, 10);
}

function sanitize(message) {
  return message.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 80);
}

function stableId(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (Math.imul(31, hash) + str.charCodeAt(i)) | 0;
  }
  return hash;
}

function topicForUser(email) {
  return "user_" + email.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "_");
}

function parseAssignedTo(raw) {
  if (Array.isArray(raw)) {
    return raw.map((v) => (v == null ? "" : String(v).trim())).filter((v) => v.length > 0);
  }
  if (typeof raw === "string" && raw.trim().length > 0) return [raw.trim()];
  return [];
}

// ── Acknowledgment requests + SMS ─────────────────────────────────────────
// SMS goes through the same Apps Script web app the Android app already uses.
const APPS_SCRIPT_URL = process.env.APPS_SCRIPT_URL;
const APPS_SCRIPT_SECRET = process.env.APPS_SCRIPT_SECRET;

// Must match AckRepository.idFor() on the phone (`yyyy-MM-dd_<sanitized message>`, Manila date)
// so a phone and this script never both raise (and SMS) the same alert.
function ackIdFor(message) {
  const day = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Manila" }).format(new Date());
  return `${day}_${sanitize(message)}`;
}

async function getRecipients(onlyEmails) {
  const snap = await db.collection("user_access").where("status", "==", "approved").get();
  const wanted = onlyEmails ? new Set(onlyEmails.map((e) => e.trim().toLowerCase())) : null;
  return snap.docs
    .filter((d) => d.get("isActive") !== false)
    .filter((d) => !wanted || wanted.has(d.id.trim().toLowerCase()))
    .map((d) => ({ email: d.id, phone: d.get("phoneNumber") || null }));
}

async function sendSms(numbers, message, priority) {
  const list = [...new Set(numbers.filter(Boolean))];
  if (list.length === 0) return;
  if (!APPS_SCRIPT_URL || !APPS_SCRIPT_SECRET) {
    console.log("SMS skipped: APPS_SCRIPT_URL / APPS_SCRIPT_SECRET not set.");
    return;
  }
  try {
    const res = await fetch(APPS_SCRIPT_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ secret: APPS_SCRIPT_SECRET, type: "sms", numbers: list, message, priority }),
    });
    console.log(`SMS to ${list.length} number(s): ${res.status} ${(await res.text()).slice(0, 200)}`);
  } catch (e) {
    console.error("SMS failed:", e.message);
  }
}

// Creates the accept/decline request once; the run that creates it also sends the SMS.
async function raiseAckRequest({ message, title, recipients }) {
  if (recipients.length === 0) return null;
  const ackId = ackIdFor(message);
  const ref = db.collection("farm_data").doc("shared").collection("ack_requests").doc(ackId);
  try {
    await ref.create({
      kind: "alert",
      severity: "critical",
      mode: "any",
      title,
      message,
      recipients: recipients.map((r) => r.email),
      responses: {},
      resolved: false,
      createdAt: FieldValue.serverTimestamp(),
    });
  } catch (e) {
    if (e.code === 6 || e.code === "already-exists") return ackId; // a phone or earlier run already raised it
    throw e;
  }
  await sendSms(
    recipients.map((r) => r.phone),
    `Waje's Quail Farm CRITICAL: ${message}. Open the app to Accept/Decline.`,
    true
  );
  return ackId;
}

async function raiseAlert({ message, type, topic, title, body, channel, critical = false }) {
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
    if (e.code === 6 || e.code === "already-exists") return false; // already alerted today
    throw e;
  }

  const extra = {};
  if (critical) {
    const ackId = await raiseAckRequest({ message, title, recipients: await getRecipients() });
    if (ackId) Object.assign(extra, { ackId, critical: "true" });
  }

  await getMessaging().send({
    topic,
    data: { title, body, channel, notifId: String(stableId(message)), ...extra },
    android: { priority: "high" },
  });

  console.log(`Alert raised + pushed: ${message}`);
  return true;
}

async function raiseTaskAlertForAssignees({ message, type, assignees, title, body, channel }) {
  if (!assignees || assignees.length === 0) {
    console.log(`No assignees for task alert, skipping: ${message}`);
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
  }

  const extra = {};
  let anyPushed = false;
  for (const email of assignees) {
    const pushDocId = `${docId}__${sanitize(email)}`;
    const pushRef = db.collection("farm_data").doc("shared").collection("_taskPushLog").doc(pushDocId);
    try {
      await pushRef.create({ email, message, timestamp: FieldValue.serverTimestamp() });
    } catch (e) {
      if (e.code === 6 || e.code === "already-exists") continue; // already pushed today
      throw e;
    }

    await getMessaging().send({
      topic: topicForUser(email),
      data: { title, body, channel, notifId: String(stableId(message + email)), ...extra },
      android: { priority: "high" },
    });
    anyPushed = true;
  }

  if (anyPushed) console.log(`Task alert pushed to [${assignees.join(", ")}]: ${message}`);
  return anyPushed;
}

// FIX: this polling script never had an equivalent of functions/index.js's
// onTaskAssigned — the push that tells a staff member the moment they're
// individually assigned a task. onTaskAssigned detects "new" assignees by
// diffing a write's before/after `assignedTo`, which only exists for a
// real-time Firestore trigger; a poll only ever sees the current state, with
// no "before". So instead of a before/after diff, dedup is a permanent
// (no day component) per-task-per-assignee log doc: the first poll that
// ever sees a given email on a given task's assignedTo list pushes to them
// and writes the log doc; every poll after that is a no-op for that pair,
// even once the task itself is done/deleted or the poll runs for months.
// This intentionally does NOT reuse _taskPushLog (that log is scoped to one
// calendar day, for "Missed Task" reminders that should be able to re-fire
// on a later day) — assignment pushes must fire once ever per assignment.
async function raiseAssignmentAlertForNewAssignees(taskId, data) {
  const assignees = parseAssignedTo(data.assignedTo);
  if (assignees.length === 0) return false;

  const title = data.title || "Task";
  const message = `New Task Assigned: ${title} (${taskId})`;

  const newlyNotified = [];
  for (const email of assignees) {
    const logId = `${taskId}__${sanitize(email)}`;
    const logRef = db.collection("farm_data").doc("shared").collection("_taskAssignPushLog").doc(logId);
    try {
      await logRef.create({ email, taskId, message, timestamp: FieldValue.serverTimestamp() });
    } catch (e) {
      if (e.code === 6 || e.code === "already-exists") continue; // already notified this assignee for this task
      throw e;
    }
    newlyNotified.push(email);
  }
  if (newlyNotified.length === 0) return false;

  for (const email of newlyNotified) {
    await getMessaging().send({
      topic: topicForUser(email),
      data: {
        title: "New Task Assigned",
        body: `You've been assigned: ${title}`,
        channel: CHANNEL_TASK_REMINDER,
        notifId: String(stableId(message + email)),
        ...(data.recurrenceGroupId ? { ackId: `assign_${data.recurrenceGroupId}` } : {}),
      },
      android: { priority: "high" },
    });
  }

  console.log(`Task assignment pushed to [${newlyNotified.join(", ")}]: ${message}`);

  return true;
}

// ── Status auto-repair ────────────────────────────────────────────────────
// Mirrors FeedInventoryActivity.kt's calculateStatus() exactly. The Android
// app intentionally never rewrites `status` after an item is created (its
// comments say a separate website owns that field) — but nothing was
// reliably keeping `status` in sync with `quantity` on every edit path. This
// recomputes it here so `status` self-heals every 15 minutes regardless of
// which client (website, app, or manual Firestore edit) changed quantity.
function calculateStatus(qty, initialQty) {
  if (initialQty <= 0) return "In Stock";
  const ratio = qty / initialQty;
  if (ratio <= 0.2) return "Low Stock";
  if (ratio <= 0.5) return "Medium";
  return "In Stock";
}

// ── Checks (polling versions of the three Cloud Functions) ──────────────

async function checkInventory() {
  const snapshot = await db.collection("farm_data").doc("shared").collection("feed").get();

  for (const doc of snapshot.docs) {
    const data = doc.data();
    const qty = data.quantity ?? 0;
    const name = data.name ?? "Item";

    // Keep `status` in sync with quantity before evaluating alerts below,
    // so alerts always react to the freshest, correct status.
    const initialQty = data.initialQuantity ?? qty;
    const correctStatus = calculateStatus(qty, initialQty);
    if (data.status !== correctStatus) {
      console.log(`Status corrected for ${name}: ${data.status ?? "(none)"} -> ${correctStatus}`);
      await doc.ref.update({ status: correctStatus });
      data.status = correctStatus;
    }

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
      continue;
    }

    const status = data.status ?? "";
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
}

async function checkWaterLevel() {
  const snapshot = await rtdb.ref("/water_level/percentage").get();
  if (!snapshot.exists()) return;

  const percent = Number(snapshot.val());
  if (Number.isNaN(percent)) return;

  // Mirrors AlertsMonitor.resolveWaterLevelAlert(). Only the Emergency tier
  // (tank empty, <= 0%) rings the alarm; Critical (1..10%) is a plain alert.
  if (percent <= 0) {
    const description = "Water tank is empty. Refill immediately.";
    await raiseAlert({
      message: `Water Level Emergency: ${description}`,
      type: "Water Level",
      topic: TOPIC_FARM_ALERTS,
      title: "Water Level Emergency",
      body: description,
      channel: CHANNEL_ALERTS,
      critical: true,
    });
    return;
  }
  if (percent <= 10) {
    const description = "Water tank is critically low. Refill immediately.";
    await raiseAlert({
      message: `Water Level Critical: ${description}`,
      type: "Water Level",
      topic: TOPIC_FARM_ALERTS,
      title: "Water Level Critical",
      body: description,
      channel: CHANNEL_ALERTS,
    });
    return;
  }
  if (percent <= 50) return;

  const label = "Filled";
  const description = "Water tank is filled and at a healthy level.";
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

async function checkOverdueTasks() {
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
}

// Polling counterpart to onTaskAssigned: scans every Pending task's current
// assignedTo list and pushes to whichever assignees haven't been notified
// for that specific task yet (see raiseAssignmentAlertForNewAssignees).
// Scoped to Pending, same as checkOverdueTasks, so a task stops being
// scanned once it's Done — its assignees have already been notified by then
// anyway, since notification happens on assignment, not on completion.
async function checkTaskAssignments() {
  const snapshot = await db
    .collection("farm_data")
    .doc("shared")
    .collection("tasks")
    .where("status", "==", "Pending")
    .get();

  for (const doc of snapshot.docs) {
    await raiseAssignmentAlertForNewAssignees(doc.id, doc.data());
  }
}

// ── Entry point ───────────────────────────────────────────────────────────
// Promise.allSettled (not Promise.all) is deliberate: if any one check
// throws, Promise.all would reject immediately and process.exit() would run
// right away — potentially cutting off one of the OTHER checks mid-flight
// (e.g. an inventory alert's FCM send not yet finished) even though that
// check was working fine. allSettled always waits for every check to
// finish, success or failure, so one broken check can never take the others
// down with it. The exit code still reflects failure so GitHub Actions
// flags the run — but only after everything that *could* run, did.
async function main() {
  const checks = [
    ["checkInventory", checkInventory],
    ["checkWaterLevel", checkWaterLevel],
    ["checkOverdueTasks", checkOverdueTasks],
    ["checkTaskAssignments", checkTaskAssignments],
  ];

  const results = await Promise.allSettled(checks.map(([, fn]) => fn()));

  let hadFailure = false;
  results.forEach((result, i) => {
    if (result.status === "rejected") {
      hadFailure = true;
      console.error(`${checks[i][0]} failed:`, result.reason);
    }
  });

  console.log(hadFailure ? "Check complete, with errors (see above)." : "Check complete.");
  process.exit(hadFailure ? 1 : 0);
}

main().catch((err) => {
  console.error("Check failed:", err);
  process.exit(1);
});