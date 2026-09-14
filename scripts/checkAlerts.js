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
    if (e.code === 6 || e.code === "already-exists") return false; // already alerted today
    throw e;
  }

  await getMessaging().send({
    topic,
    data: { title, body, channel, notifId: String(stableId(message)) },
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
      data: { title, body, channel, notifId: String(stableId(message + email)) },
      android: { priority: "high" },
    });
    anyPushed = true;
  }

  if (anyPushed) console.log(`Task alert pushed to [${assignees.join(", ")}]: ${message}`);
  return anyPushed;
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
  if (Number.isNaN(percent) || percent <= 50) return;

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

// ── Entry point ───────────────────────────────────────────────────────────
async function main() {
  await Promise.all([checkInventory(), checkWaterLevel(), checkOverdueTasks()]);
  console.log("Check complete.");
  process.exit(0);
}

main().catch((err) => {
  console.error("Check failed:", err);
  process.exit(1);
});