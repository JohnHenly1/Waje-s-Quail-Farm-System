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
const TOPIC_TASK_REMINDERS = "task_reminders";
const CHANNEL_ALERTS = "alerts_channel";
const CHANNEL_TASK_REMINDER = "task_reminder_channel";

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
  if (percent >= 100) return ["Refilled", "Water tank has been successfully refilled to full capacity."];
  if (percent <= 0) return ["Emergency", "Water tank is empty. Refill immediately to prevent disruptions."];
  if (percent <= 15) return ["Critical", "Water level is critically low. Immediate action is required."];
  if (percent <= 25) return ["Warning", "Water level is low. Refill the water tank soon."];
  if (percent <= 50) return ["Notice", "Water level is decreasing. Monitor the water supply."];
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

    const message = `Missed Task: ${title} was scheduled for ${day}/${month + 1}/${year}`;
    await raiseAlert({
      message,
      type: "Critical",
      topic: TOPIC_TASK_REMINDERS,
      title: "Missed Task",
      body: message,
      channel: CHANNEL_TASK_REMINDER,
    });
  }
});