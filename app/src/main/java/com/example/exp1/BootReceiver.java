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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        boolean isBootEvent =
                Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                        "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction());

        if (!isBootEvent) return;

        // 1. Re-create notification channel (wiped on some devices after reboot)
        createNotificationChannel(context);

        // 2. Re-schedule all future alarms from Firestore
        rescheduleAlarmsFromFirestore(context);
    }

    // -------------------------------------------------------------------------
    // Notification channel
    // -------------------------------------------------------------------------

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "task_reminder_channel",
                    "Task Reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for farm tasks");
            channel.enableLights(true);
            channel.enableVibration(true);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    // -------------------------------------------------------------------------
    // Re-schedule alarms
    // -------------------------------------------------------------------------

    private void rescheduleAlarmsFromFirestore(Context context) {
        // Firebase Auth sign-in state is not guaranteed at BOOT_COMPLETED.
        // Use addAuthStateListener so we only proceed once it resolves.
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

                String uid = user.getUid();
                FirebaseFirestore.getInstance()
                        .collection("users").document(uid).collection("tasks")
                        .get()
                        .addOnSuccessListener(querySnapshot -> {
                            AlarmManager alarmManager =
                                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                            if (alarmManager == null) return;
                            int rescheduled = 0;
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
                                    if (alarmCal.before(Calendar.getInstance())) continue;

                                    Intent alarmIntent =
                                            new Intent(context, ScheduleActivity.TaskAlarmReceiver.class);
                                    alarmIntent.putExtra("taskTitle", title);
                                    alarmIntent.putExtra("taskCategory", category);

                                    int rc = (title + yearL.intValue() + monthL.intValue()
                                            + dayL.intValue() + hm[0] + hm[1]).hashCode();
                                    PendingIntent pi = PendingIntent.getBroadcast(
                                            context, rc, alarmIntent,
                                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                                    scheduleAlarm(context, alarmManager,
                                            alarmCal.getTimeInMillis(), pi);
                                    rescheduled++;
                                } catch (Exception e) {
                                    Log.e(TAG, "Error rescheduling: " + e.getMessage());
                                }
                            }
                            Log.d(TAG, "Boot restore: rescheduled " + rescheduled + " alarm(s).");
                        })
                        .addOnFailureListener(e ->
                                Log.e(TAG, "Firestore fetch failed on boot: " + e.getMessage()));

                auth.removeAuthStateListener(this);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Alarm scheduling (mirrors ScheduleActivity.scheduleNotification)
    // -------------------------------------------------------------------------

    private void scheduleAlarm(Context context, AlarmManager am,
                               long triggerAtMillis, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                // Exact alarm permission not granted — use inexact fallback
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);

                // Prompt user to grant exact alarm permission
                try {
                    Intent settingsIntent = new Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    settingsIntent.setData(
                            android.net.Uri.parse("package:" + context.getPackageName()));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(settingsIntent);
                } catch (Exception ignored) { /* settings screen unavailable */ }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6–11
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        } else {
            // Android 5 and below
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    // -------------------------------------------------------------------------
    // Time parser  "08:30 AM" / "08:30 PM"  →  { hour24, minute }
    // -------------------------------------------------------------------------

    /**
     * Parses a time string in "hh:mm AM/PM" format into a two-element int array
     * where [0] = hour in 24-hour format and [1] = minute.
     * Returns null if parsing fails.
     */
    private int[] parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            Date date = sdf.parse(timeStr.trim());
            if (date == null) return null;
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            return new int[]{
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
            };
        } catch (ParseException e) {
            Log.e(TAG, "Cannot parse time: " + timeStr + " — " + e.getMessage());
            return null;
        }
    }
}