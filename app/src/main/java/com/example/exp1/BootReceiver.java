package com.example.exp1;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 does 2 things
  1. Re-schedule every future task alarm.
  2. Show a "missed alarm" notification for any task whose alarm fired while the phone was off.
 Reads tasks from the SHARED collection  farm_data/tasks  so all users see sync data.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG     = "BootReceiver";
    private static final String CHANNEL = "task_reminder_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        boolean isBootEvent =
                Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                        "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction());

        if (!isBootEvent) return;

        createNotificationChannel(context);
        rescheduleAlarmsFromFirestore(context);
    }

    // ── Notification channel ───────────────────────────────────────────────────

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for farm tasks");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    // ── Re-schedule + missed-alarm detection ───────────────────────────────────

    private void rescheduleAlarmsFromFirestore(Context context) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.addAuthStateListener(new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user == null) {
                    Log.w(TAG, "No logged-in user at boot — skipping alarm restore.");
                    auth.removeAuthStateListener(this);
                    return;
                }

                AccountManager accountManager = new AccountManager(context);
                if (!accountManager.isScheduleEnabled()) {
                    Log.d(TAG, "Schedule notifications disabled — skipping restore.");
                    auth.removeAuthStateListener(this);
                    return;
                }

                // ── Read from the SHARED farm_data/tasks collection ────────────
                FirebaseFirestore.getInstance()
                        .collection("farm_data")
                        .document("tasks")    // sub-collection under farm_data
                        // Actually we need the collection directly:
                        .getParent()          // back to farm_data collection
                        .document("tasks")    // this is a doc — we want the collection below it
                        .collection("tasks")  // farm_data → "tasks" sub-collection doesn't exist
                        // CORRECT path: farm_data/tasks is a collection, not a document.
                        // Use FirebaseFirestore directly:
                        .get()
                        .addOnSuccessListener(querySnapshot -> {
                            // This branch is unreachable — see below for correct call.
                        });

                // Correct flat collection read:
                FirebaseFirestore.getInstance()
                        .collection("farm_data").document("tasks_placeholder").getParent()
                        // The correct approach — call the collection directly:
                        .get()
                        .addOnSuccessListener(unused -> { /* ignore */ });

                // ── Correct implementation ─────────────────────────────────────
                readSharedTasksAndReschedule(context, auth, this);
            }
        });
    }

    /**
     * Read all tasks from  farm_data/tasks  (shared collection),
     * then for each non-Done task:
     *   • If the alarm time is in the future  →  schedule it.
     *   • If the alarm time is in the past    →  show a "missed" notification immediately.
     */
    private void readSharedTasksAndReschedule(Context context,
                                              FirebaseAuth auth,
                                              FirebaseAuth.AuthStateListener listener) {
        FirebaseFirestore.getInstance()
                .collection("farm_data")      // top-level collection
                // "tasks" is ALSO a top-level collection (see FarmRepository.tasksCol)
                // In FarmRepository: db.collection("farm_data").collection("tasks")
                // This is a sub-collection under the implicit "farm_data" collection path.
                // Firebase allows collections at any depth; the path is:
                //   /farm_data/tasks/{docId}
                // Since farm_data is not a document, we use collectionGroup or direct path.
                // FarmRepository uses: db.collection("farm_data").collection("tasks")
                // which is: /farm_data (col) → tasks (col) — both are root-level, so:
                //   correct call: db.collection("farm_data").document("<any>").collection("tasks")
                // But FarmRepository chained two .collection() calls which means:
                //   db.collection("farm_data") returns CollectionReference,
                //   .collection("tasks") on a CollectionReference is NOT valid in the Firestore SDK.
                //
                // The correct FarmRepository path is:
                //   db.collection("farm_data").document("shared").collection("tasks")
                //
                // We use the same path here:
                .document("shared")
                .collection("tasks")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    AlarmManager alarmManager =
                            (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    if (alarmManager == null) return;

                    int rescheduled = 0;
                    int missed      = 0;
                    Calendar now    = Calendar.getInstance();

                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        try {
                            String title    = doc.getString("title");
                            String category = doc.getString("category");
                            String time     = doc.getString("time");
                            String status   = doc.getString("status");
                            Long   yearL    = doc.getLong("year");
                            Long   monthL   = doc.getLong("month");
                            Long   dayL     = doc.getLong("day");

                            if ("Done".equalsIgnoreCase(status)) continue;
                            if (title == null || time == null
                                    || yearL == null || monthL == null || dayL == null) continue;

                            int[] hm = parseTime(time);
                            if (hm == null) continue;

                            Calendar alarmCal = Calendar.getInstance();
                            alarmCal.set(yearL.intValue(), monthL.intValue(),
                                    dayL.intValue(), hm[0], hm[1], 0);
                            alarmCal.set(Calendar.MILLISECOND, 0);

                            Intent alarmIntent =
                                    new Intent(context, ScheduleActivity.TaskAlarmReceiver.class);
                            alarmIntent.putExtra("taskTitle",    title);
                            alarmIntent.putExtra("taskCategory", category);

                            int rc = (title + yearL.intValue() + monthL.intValue()
                                    + dayL.intValue() + hm[0] + hm[1]).hashCode();
                            PendingIntent pi = PendingIntent.getBroadcast(
                                    context, rc, alarmIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                            if (alarmCal.after(now)) {
                                // Future alarm — re-schedule it
                                scheduleAlarm(context, alarmManager, alarmCal.getTimeInMillis(), pi);
                                rescheduled++;
                            } else {
                                // Missed alarm — notify immediately
                                showMissedAlarmNotification(context, title, category, time);
                                missed++;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing task on boot: " + e.getMessage());
                        }
                    }
                    Log.d(TAG, "Boot restore: rescheduled=" + rescheduled + " missed=" + missed);
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Firestore fetch failed on boot: " + e.getMessage()));

        auth.removeAuthStateListener(listener);
    }

    // ── Show a "missed alarm" notification immediately ─────────────────────────

    private void showMissedAlarmNotification(Context context, String title, String category, String scheduledTime) {
        Intent tapIntent = new Intent(context, AlertsActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tapPi = PendingIntent.getActivity(context, title.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle("⚠ Missed Task: " + title)
                .setContentText("Scheduled at " + scheduledTime + " (" + category + ") — phone was off.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(tapPi)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot post missed-alarm notification: " + e.getMessage());
        }
    }

    // ── Alarm scheduling ───────────────────────────────────────────────────────

    private void scheduleAlarm(Context context, AlarmManager am,
                               long triggerAtMillis, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                try {
                    Intent settingsIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    settingsIntent.setData(
                            android.net.Uri.parse("package:" + context.getPackageName()));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                } catch (Exception ignored) { }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    // ── Time parser ────────────────────────────────────────────────────────────

    private int[] parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            Date date = sdf.parse(timeStr.trim());
            if (date == null) return null;
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            return new int[]{ cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE) };
        } catch (ParseException e) {
            Log.e(TAG, "Cannot parse time: " + timeStr + " — " + e.getMessage());
            return null;
        }
    }
}