package com.example.exp1;

import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ScheduleActivity extends AppCompatActivity {

    // Recurrence constants
    private static final String RECUR_ONCE     = "Once";
    private static final String RECUR_DAILY    = "Daily";
    private static final String RECUR_WEEKDAYS = "Weekdays (Mon-Fri)";
    private static final String RECUR_WEEKENDS = "Weekends (Sat-Sun)";
    private static final String RECUR_WEEKLY   = "Once a Week";
    private static final String RECUR_BIWEEKLY = "Every 2 Weeks";
    private static final String RECUR_MONTHLY  = "Monthly";

    private static final String[] RECURRENCE_OPTIONS = {
            RECUR_ONCE, RECUR_DAILY, RECUR_WEEKDAYS, RECUR_WEEKENDS,
            RECUR_WEEKLY, RECUR_BIWEEKLY, RECUR_MONTHLY
    };

    //  Calendar / UI fields
    private Calendar currentWeekCalendar;
    private TextView monthText;
    private TextView[] dayTextViews;
    private TextView[] dayLabelViews;
    private View[] dayContainers;
    private Calendar today;
    private Calendar selectedDate;
    private GestureDetector gestureDetector;

    private LinearLayout tasksContainer;
    private TextView doneCount, ongoingCount, pendingCount;
    private List<Task> taskList = new ArrayList<>();

    // Firebase
    private FirebaseFirestore db;
    private String currentUserEmail;
    private ListenerRegistration tasksListener;

    private final String[] monthNames = {
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
    };

    //  Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_schedule);

        createNotificationChannel();

        db = FirebaseFirestore.getInstance();
        currentUserEmail = getIntent().getStringExtra("username");
        if (currentUserEmail == null || currentUserEmail.isEmpty()) {
            currentUserEmail = "default_user";
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        today = Calendar.getInstance();
        selectedDate = (Calendar) today.clone();
        currentWeekCalendar = (Calendar) today.clone();
        alignCalendarToMonday(currentWeekCalendar);

        monthText      = findViewById(R.id.month);
        tasksContainer = findViewById(R.id.tasksContainer);
        doneCount      = findViewById(R.id.doneCount);
        ongoingCount   = findViewById(R.id.ongoingCount);
        pendingCount   = findViewById(R.id.pendingCount);

        dayTextViews = new TextView[]{
                findViewById(R.id.day1), findViewById(R.id.day2), findViewById(R.id.day3),
                findViewById(R.id.day4), findViewById(R.id.day5), findViewById(R.id.day6),
                findViewById(R.id.day7)
        };
        dayLabelViews = new TextView[]{
                findViewById(R.id.dayLabel1), findViewById(R.id.dayLabel2), findViewById(R.id.dayLabel3),
                findViewById(R.id.dayLabel4), findViewById(R.id.dayLabel5), findViewById(R.id.dayLabel6),
                findViewById(R.id.dayLabel7)
        };
        dayContainers = new View[]{
                findViewById(R.id.dayContainer1), findViewById(R.id.dayContainer2), findViewById(R.id.dayContainer3),
                findViewById(R.id.dayContainer4), findViewById(R.id.dayContainer5), findViewById(R.id.dayContainer6),
                findViewById(R.id.dayContainer7)
        };

        setupDayClickListeners();

        findViewById(R.id.imageButton).setOnClickListener(v -> {
            Intent intent = new Intent(ScheduleActivity.this, DashboardActivity.class);
            intent.putExtra("username", getIntent().getStringExtra("username"));
            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            finish();
        });

        findViewById(R.id.AddScheduleBtn).setOnClickListener(v -> showAddScheduleDialog());
        findViewById(R.id.seeCalendarBtn).setOnClickListener(v -> showFullCalendar());
        findViewById(R.id.bulkDeleteBtn).setOnClickListener(v -> showBulkDeleteDialog());
        findViewById(R.id.taskDetailsBtn).setOnClickListener(v -> showAllTaskDetails());

        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        updateCalendarUI();
        setupSwipeGestures();
        listenToTasks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tasksListener != null) tasksListener.remove();
    }

    //  Firestore — CRUD

    private void listenToTasks() {
        tasksListener = db.collection("users")
                .document(currentUserEmail)
                .collection("tasks")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        Toast.makeText(this, "Failed to load tasks: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    taskList.clear();
                    if (snapshots != null) {
                        for (QueryDocumentSnapshot doc : snapshots) {
                            Task task = new Task(
                                    doc.getId(),
                                    doc.getString("title"),
                                    doc.getString("category"),
                                    doc.getString("time"),
                                    doc.getString("status"),
                                    doc.getLong("year")  != null ? doc.getLong("year").intValue()  : 0,
                                    doc.getLong("month") != null ? doc.getLong("month").intValue() : 0,
                                    doc.getLong("day")   != null ? doc.getLong("day").intValue()   : 0,
                                    doc.getString("recurrence") != null ? doc.getString("recurrence") : RECUR_ONCE,
                                    doc.getString("recurrenceGroupId")
                            );
                            taskList.add(task);
                        }
                    }
                    updateTasksUI();
                });
    }

    /** Save a single task document. */
    private void addTaskToFirestore(Task task) {
        db.collection("users")
                .document(currentUserEmail)
                .collection("tasks")
                .add(buildTaskMap(task))
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error saving task: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    /**
     * Generates all recurrence dates (up to 1 year) and saves each as a
     * separate Firestore document sharing the same recurrenceGroupId.
     */
    private void addRecurringTasks(String title, String category, String time,
                                   String recurrence, Calendar startCal,
                                   int selHour, int selMinute) {
        String groupId = java.util.UUID.randomUUID().toString();
        List<Calendar> dates = generateRecurrenceDates(recurrence, startCal);

        com.google.firebase.firestore.WriteBatch batch = db.batch();
        for (Calendar cal : dates) {
            Task t = new Task(null, title, category, time, "Pending",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH), recurrence, groupId);
            DocumentReference ref = db.collection("users")
                    .document(currentUserEmail).collection("tasks").document();
            batch.set(ref, buildTaskMap(t));
            scheduleNotification(t, selHour, selMinute);
        }

        batch.commit()
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, dates.size() + " tasks scheduled!", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error scheduling: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private Map<String, Object> buildTaskMap(Task task) {
        Map<String, Object> data = new HashMap<>();
        data.put("title",              task.title);
        data.put("category",           task.category);
        data.put("time",               task.time);
        data.put("status",             task.status);
        data.put("year",               task.year);
        data.put("month",              task.month);
        data.put("day",                task.day);
        data.put("recurrence",         task.recurrence);
        data.put("recurrenceGroupId",  task.recurrenceGroupId);
        data.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        return data;
    }

    private void updateTaskStatus(Task task) {
        if (task.firestoreId == null) return;
        db.collection("users").document(currentUserEmail)
                .collection("tasks").document(task.firestoreId)
                .update("status", task.status)
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error updating status: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void deleteTaskFromFirestore(Task task) {
        if (task.firestoreId == null) return;
        db.collection("users").document(currentUserEmail)
                .collection("tasks").document(task.firestoreId)
                .delete()
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error deleting: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    /** Deletes every document that belongs to the same recurring series. */
    private void deleteRecurringSeriesFromFirestore(Task task) {
        if (task.recurrenceGroupId == null) { deleteTaskFromFirestore(task); return; }
        db.collection("users").document(currentUserEmail).collection("tasks")
                .whereEqualTo("recurrenceGroupId", task.recurrenceGroupId)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    for (QueryDocumentSnapshot doc : querySnapshot) batch.delete(doc.getReference());
                    batch.commit()
                            .addOnSuccessListener(unused ->
                                    Toast.makeText(this, "All recurring tasks deleted", Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e ->
                                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                });
    }

    private void bulkDeleteFromFirestore(List<Task> tasksToDelete) {
        com.google.firebase.firestore.WriteBatch batch = db.batch();
        for (Task task : tasksToDelete) {
            if (task.firestoreId != null) {
                batch.delete(db.collection("users").document(currentUserEmail)
                        .collection("tasks").document(task.firestoreId));
            }
        }
        batch.commit()
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Selected tasks deleted", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Error deleting tasks: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }


    //  Recurrence date generator (max 1 year / 365 instances)

    private List<Calendar> generateRecurrenceDates(String recurrence, Calendar start) {
        List<Calendar> dates = new ArrayList<>();
        Calendar limit = (Calendar) start.clone();
        limit.add(Calendar.YEAR, 1);
        final int MAX = 365;

        switch (recurrence) {

            case RECUR_ONCE:
                dates.add((Calendar) start.clone());
                break;

            case RECUR_DAILY: {
                Calendar cur = (Calendar) start.clone();
                while (!cur.after(limit) && dates.size() < MAX) {
                    dates.add((Calendar) cur.clone());
                    cur.add(Calendar.DAY_OF_MONTH, 1);
                }
                break;
            }

            case RECUR_WEEKDAYS: {
                Calendar cur = (Calendar) start.clone();
                while (!cur.after(limit) && dates.size() < MAX) {
                    int dow = cur.get(Calendar.DAY_OF_WEEK);
                    if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY)
                        dates.add((Calendar) cur.clone());
                    cur.add(Calendar.DAY_OF_MONTH, 1);
                }
                break;
            }

            case RECUR_WEEKENDS: {
                Calendar cur = (Calendar) start.clone();
                while (!cur.after(limit) && dates.size() < MAX) {
                    int dow = cur.get(Calendar.DAY_OF_WEEK);
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
                        dates.add((Calendar) cur.clone());
                    cur.add(Calendar.DAY_OF_MONTH, 1);
                }
                break;
            }

            case RECUR_WEEKLY: {
                Calendar cur = (Calendar) start.clone();
                while (!cur.after(limit) && dates.size() < MAX) {
                    dates.add((Calendar) cur.clone());
                    cur.add(Calendar.DAY_OF_MONTH, 7);
                }
                break;
            }

            case RECUR_BIWEEKLY: {
                Calendar cur = (Calendar) start.clone();
                while (!cur.after(limit) && dates.size() < MAX) {
                    dates.add((Calendar) cur.clone());
                    cur.add(Calendar.DAY_OF_MONTH, 14);
                }
                break;
            }

            case RECUR_MONTHLY: {
                Calendar cur = (Calendar) start.clone();
                while (!cur.after(limit) && dates.size() < MAX) {
                    dates.add((Calendar) cur.clone());
                    cur.add(Calendar.MONTH, 1);
                }
                break;
            }

            default:
                dates.add((Calendar) start.clone());
                break;
        }
        return dates;
    }

    //  Dialog — Add Schedule

    private void showAddScheduleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_schedule, null);

        EditText    editTaskTitle     = dialogView.findViewById(R.id.editTaskTitle);
        Spinner     spinnerCategory   = dialogView.findViewById(R.id.spinnerCategory);
        Spinner     spinnerRecurrence = dialogView.findViewById(R.id.spinnerRecurrence);  // NEW
        TextView    textTime          = dialogView.findViewById(R.id.textTime);
        TextView    txtCurrentMonth   = dialogView.findViewById(R.id.txtCurrentMonth);
        GridLayout  calendarGrid      = dialogView.findViewById(R.id.calendarGrid);
        ImageButton btnPrevMonth      = dialogView.findViewById(R.id.btnPrevMonth);
        ImageButton btnNextMonth      = dialogView.findViewById(R.id.btnNextMonth);

        // Category spinner
        String[] categories = {"Feeding","Watering","Cleaning","Egg Collection","Lighting","Health Check"};
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(catAdapter);

        // Recurrence spinner
        ArrayAdapter<String> recAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, RECURRENCE_OPTIONS);
        recAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRecurrence.setAdapter(recAdapter);

        // Time picker
        final String[] selectedTime = {"08:00 AM"};
        final int[] selHour   = {8};
        final int[] selMinute = {0};
        textTime.setOnClickListener(v ->
                new TimePickerDialog(this, (view, hourOfDay, minute) -> {
                    selHour[0]   = hourOfDay;
                    selMinute[0] = minute;
                    String amPm  = (hourOfDay < 12) ? "AM" : "PM";
                    int h = (hourOfDay > 12) ? hourOfDay - 12 : (hourOfDay == 0 ? 12 : hourOfDay);
                    selectedTime[0] = String.format(Locale.getDefault(), "%02d:%02d %s", h, minute, amPm);
                    textTime.setText(selectedTime[0]);
                }, selHour[0], selMinute[0], false).show()
        );

        // Mini calendar — single start-date selection
        final long[]     pickedStartDate = {0};
        final Calendar   viewCalendar    = Calendar.getInstance();
        viewCalendar.set(Calendar.DAY_OF_MONTH, 1);
        final TextView[] lastSelectedTv  = {null};

        Runnable updateGrid = new Runnable() {
            @Override
            public void run() {
                calendarGrid.removeAllViews();
                txtCurrentMonth.setText(
                        new SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                                .format(viewCalendar.getTime()));

                for (String d : new String[]{"S","M","T","W","Th","F","S"})
                    calendarGrid.addView(makeHeaderCell(d));

                Calendar cal     = (Calendar) viewCalendar.clone();
                int firstDow     = cal.get(Calendar.DAY_OF_WEEK) - 1;
                for (int i = 0; i < firstDow; i++) calendarGrid.addView(makeSpacer());

                int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= daysInMonth; i++) {
                    final int  day   = i;
                    final int  month = cal.get(Calendar.MONTH);
                    final int  year  = cal.get(Calendar.YEAR);
                    Calendar dateCal = Calendar.getInstance();
                    dateCal.set(year, month, day, 0, 0, 0);
                    dateCal.set(Calendar.MILLISECOND, 0);
                    final long dateKey = dateCal.getTimeInMillis();

                    TextView tv = makeDayCell(String.valueOf(i));
                    if (pickedStartDate[0] == dateKey) {
                        tv.setBackgroundResource(R.drawable.bg_dayselected);
                        tv.setTextColor(Color.WHITE);
                        lastSelectedTv[0] = tv;
                    }
                    tv.setOnClickListener(v -> {
                        if (lastSelectedTv[0] != null) {
                            lastSelectedTv[0].setBackground(null);
                            lastSelectedTv[0].setTextColor(Color.BLACK);
                        }
                        pickedStartDate[0] = dateKey;
                        lastSelectedTv[0]  = tv;
                        tv.setBackgroundResource(R.drawable.bg_dayselected);
                        tv.setTextColor(Color.WHITE);
                    });
                    calendarGrid.addView(tv);
                }
            }
        };

        btnPrevMonth.setOnClickListener(v -> { viewCalendar.add(Calendar.MONTH, -1); updateGrid.run(); });
        btnNextMonth.setOnClickListener(v -> { viewCalendar.add(Calendar.MONTH,  1); updateGrid.run(); });
        updateGrid.run();

        new AlertDialog.Builder(this)
                .setTitle("Add New Task")
                .setView(dialogView)
                .setPositiveButton("Schedule", (dialog, which) -> {
                    String title      = editTaskTitle.getText().toString().trim();
                    String category   = spinnerCategory.getSelectedItem().toString();
                    String recurrence = spinnerRecurrence.getSelectedItem().toString();

                    if (title.isEmpty()) {
                        Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (pickedStartDate[0] == 0) {
                        Toast.makeText(this, "Please select a start date", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Calendar startCal = Calendar.getInstance();
                    startCal.setTimeInMillis(pickedStartDate[0]);

                    if (recurrence.equals(RECUR_ONCE)) {
                        Task newTask = new Task(null, title, category, selectedTime[0], "Pending",
                                startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH),
                                startCal.get(Calendar.DAY_OF_MONTH), RECUR_ONCE, null);
                        addTaskToFirestore(newTask);
                        scheduleNotification(newTask, selHour[0], selMinute[0]);
                        Toast.makeText(this, "Task scheduled!", Toast.LENGTH_SHORT).show();
                    } else {
                        List<Calendar> preview = generateRecurrenceDates(recurrence, startCal);
                        new AlertDialog.Builder(this)
                                .setTitle("Confirm Recurring Task")
                                .setMessage("This will create " + preview.size()
                                        + " task(s) over 1 year (" + recurrence + ").\n\nContinue?")
                                .setPositiveButton("Yes, Schedule", (d2, w2) ->
                                        addRecurringTasks(title, category, selectedTime[0],
                                                recurrence, startCal, selHour[0], selMinute[0]))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    //  Delete dialog — single or entire series

    private void showDeleteOptions(Task task) {
        boolean isRecurring = task.recurrenceGroupId != null
                && !RECUR_ONCE.equals(task.recurrence);

        if (!isRecurring) {
            new AlertDialog.Builder(this)
                    .setTitle("Delete Task")
                    .setMessage("Are you sure you want to delete this task?")
                    .setPositiveButton("Delete", (d, w) -> deleteTaskFromFirestore(task))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Delete Recurring Task")
                    .setItems(new String[]{"Delete this task only", "Delete all in this series"},
                            (d, which) -> {
                                if (which == 0) deleteTaskFromFirestore(task);
                                else            deleteRecurringSeriesFromFirestore(task);
                            })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    //  Other UI dialogs / task list

    private void showBulkDeleteDialog() {
        if (taskList.isEmpty()) {
            Toast.makeText(this, "No tasks to delete", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] taskTitles = new String[taskList.size()];
        boolean[] sel       = new boolean[taskList.size()];
        List<Integer> indices = new ArrayList<>();

        for (int i = 0; i < taskList.size(); i++) {
            Task t = taskList.get(i);
            String tag = RECUR_ONCE.equals(t.recurrence) ? "" : "  [" + t.recurrence + "]";
            taskTitles[i] = t.title + " (" + t.time + ")" + tag;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_selected_tasks)
                .setMultiChoiceItems(taskTitles, sel, (dialog, which, isChecked) -> {
                    if (isChecked) indices.add(which);
                    else           indices.remove(Integer.valueOf(which));
                })
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (indices.isEmpty()) {
                        Toast.makeText(this, R.string.no_tasks_selected, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<Task> toDelete = new ArrayList<>();
                    for (int idx : indices) toDelete.add(taskList.get(idx));
                    bulkDeleteFromFirestore(toDelete);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAllTaskDetails() {
        if (taskList.isEmpty()) {
            Toast.makeText(this, "No tasks assigned", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder details = new StringBuilder();
        for (Task task : taskList) {
            details.append("Task: ").append(task.title).append("\n");
            details.append("Category: ").append(task.category).append("\n");
            details.append("Time: ").append(task.time).append("\n");
            details.append("Date: ").append(task.day).append(" ")
                    .append(monthNames[task.month]).append(" ").append(task.year).append("\n");
            details.append("Recurrence: ").append(task.recurrence).append("\n");
            details.append("Status: ").append(task.status).append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("All Assigned Tasks")
                .setMessage(details.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private void updateTasksUI() {
        if (tasksContainer == null) return;
        tasksContainer.removeAllViews();

        int done = 0, ongoing = 0, pending = 0;
        int selYear  = selectedDate.get(Calendar.YEAR);
        int selMonth = selectedDate.get(Calendar.MONTH);
        int selDay   = selectedDate.get(Calendar.DAY_OF_MONTH);

        for (Task task : taskList) {
            if (task.year != selYear || task.month != selMonth || task.day != selDay) continue;

            View taskView = getLayoutInflater().inflate(R.layout.item_schedule_task, tasksContainer, false);

            TextView    titleTv         = taskView.findViewById(R.id.taskTitle);
            TextView    categoryTv      = taskView.findViewById(R.id.taskCategory);
            TextView    timeTv          = taskView.findViewById(R.id.taskTime);
            View        statusIndicator = taskView.findViewById(R.id.statusIndicator);
            ImageButton deleteBtn       = taskView.findViewById(R.id.deleteTaskBtn);

            titleTv.setText(task.title);
            categoryTv.setText(task.category);

            // Show recurrence badge next to time when applicable
            String timeLabel = task.time;
            if (!RECUR_ONCE.equals(task.recurrence)) {
                timeLabel += "  •  " + task.recurrence;
            }
            timeTv.setText(timeLabel);

            taskView.setOnClickListener(v -> {
                String[] statuses = {"Pending", "Ongoing", "Done"};
                new AlertDialog.Builder(this)
                        .setTitle("Update Status")
                        .setItems(statuses, (dialog, which) -> {
                            task.status = statuses[which];
                            updateTaskStatus(task);
                        })
                        .show();
            });

            deleteBtn.setOnClickListener(v -> showDeleteOptions(task));

            switch (task.status) {
                case "Done":
                    done++;
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_done);
                    break;
                case "Ongoing":
                    ongoing++;
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_ongoing);
                    break;
                default:
                    pending++;
                    statusIndicator.setBackgroundResource(R.drawable.bg_status_pending);
                    break;
            }
            tasksContainer.addView(taskView);
        }

        if (doneCount    != null) doneCount.setText(String.valueOf(done));
        if (ongoingCount != null) ongoingCount.setText(String.valueOf(ongoing));
        if (pendingCount != null) pendingCount.setText(String.valueOf(pending));

        View placeholder = findViewById(R.id.noTasksPlaceholder);
        if (placeholder != null)
            placeholder.setVisibility(tasksContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    //  Calendar UI/UX, Design


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "task_reminder_channel", "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for farm tasks");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void setupDayClickListeners() {
        for (int i = 0; i < 7; i++) {
            final int index = i;
            dayContainers[i].setOnClickListener(v -> {
                selectedDate = (Calendar) currentWeekCalendar.clone();
                selectedDate.add(Calendar.DAY_OF_MONTH, index);
                updateCalendarUI();
                updateTasksUI();
            });
        }
    }

    private void alignCalendarToMonday(Calendar cal) {
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            cal.add(Calendar.DAY_OF_MONTH, -1);
    }

    private void showFullCalendar() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedDate.set(year, month, dayOfMonth);
            currentWeekCalendar = (Calendar) selectedDate.clone();
            alignCalendarToMonday(currentWeekCalendar);
            updateCalendarUI();
            updateTasksUI();
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateCalendarUI() {
        if (isSameDay(selectedDate, today)) {
            monthText.setText(new SimpleDateFormat("'Today, 'MMMM d", Locale.getDefault())
                    .format(today.getTime()));
        } else {
            monthText.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
                    .format(selectedDate.getTime()));
        }
        Calendar tempCal = (Calendar) currentWeekCalendar.clone();
        for (int i = 0; i < 7; i++) {
            dayTextViews[i].setText(String.valueOf(tempCal.get(Calendar.DAY_OF_MONTH)));
            if (isSameDay(tempCal, selectedDate)) {
                dayContainers[i].setBackgroundResource(R.drawable.bg_dayselected);
                dayTextViews[i].setTextColor(Color.WHITE);
                dayLabelViews[i].setTextColor(Color.parseColor("#E5E7EB"));
            } else {
                dayContainers[i].setBackgroundResource(R.drawable.bg_day);
                dayTextViews[i].setTextColor(Color.parseColor("#111827"));
                dayLabelViews[i].setTextColor(Color.parseColor("#4B5563"));
            }
            tempCal.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private void setupSwipeGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 100 && Math.abs(velocityX) > 100) {
                    currentWeekCalendar.add(Calendar.DAY_OF_MONTH, dx < 0 ? 7 : -7);
                    updateCalendarUI();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.main).setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event); return true;
        });
        findViewById(R.id.scrollView).setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event); return false;
        });
    }

    private boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
                && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private void scheduleNotification(Task task, int hour, int minute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                return;
            }
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(task.year, task.month, task.day, hour, minute, 0);
        if (calendar.before(Calendar.getInstance())) return;

        Intent intent = new Intent(this, TaskAlarmReceiver.class);
        intent.putExtra("taskTitle", task.title);
        intent.putExtra("taskCategory", task.category);
        int rc = (task.title + task.year + task.month + task.day + hour + minute).hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(this, rc, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
    }

    //  Mini-calendar cell factories

    private TextView makeHeaderCell(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 10, 0, 10);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.GRAY);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = GridLayout.LayoutParams.WRAP_CONTENT;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        tv.setLayoutParams(p);
        return tv;
    }

    private TextView makeDayCell(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 20, 0, 20);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setTextColor(Color.BLACK);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = GridLayout.LayoutParams.WRAP_CONTENT;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        tv.setLayoutParams(p);
        return tv;
    }

    private View makeSpacer() {
        View v = new View(this);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = 1;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        v.setLayoutParams(p);
        return v;
    }

    //  Data model and Firestore

    private static class Task {
        String firestoreId;
        String title, category, time, status;
        int    year, month, day;
        String recurrence;         // "Once", "Daily", "Weekdays (Mon-Fri)", and such
        String recurrenceGroupId;  // UUID shared by all docs in a recurring series

        Task(String firestoreId, String title, String category, String time,
             String status, int year, int month, int day,
             String recurrence, String recurrenceGroupId) {
            this.firestoreId       = firestoreId;
            this.title             = title;
            this.category          = category;
            this.time              = time;
            this.status            = status;
            this.year              = year;
            this.month             = month;
            this.day               = day;
            this.recurrence        = recurrence;
            this.recurrenceGroupId = recurrenceGroupId;
        }
    }


    //  Alarm Receiver

    public static class TaskAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            AccountManager accountManager = new AccountManager(context);
            if (!accountManager.isScheduleEnabled()) return;

            String title    = intent.getStringExtra("taskTitle");
            String category = intent.getStringExtra("taskCategory");
            String timestamp = new SimpleDateFormat("yyyy/MM/dd hh:mm a",
                    Locale.getDefault()).format(new Date());

            if (accountManager.isGlobalDataEnabled()) {
                GlobalData.INSTANCE.addAlert(
                        "Task Reminder: " + title + " (" + category + ")", timestamp, "System");
            }

            if (accountManager.isAlertsEnabled()) {
                Intent alertIntent = new Intent(context, AlertsActivity.class);
                PendingIntent pi = PendingIntent.getActivity(context, 0, alertIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                NotificationCompat.Builder builder =
                        new NotificationCompat.Builder(context, "task_reminder_channel")
                                .setSmallIcon(R.drawable.ic_notifications)
                                .setContentTitle("Task Reminder: " + title)
                                .setContentText("It's time for " + category)
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setContentIntent(pi)
                                .setAutoCancel(true);

                try {
                    NotificationManagerCompat.from(context)
                            .notify((int) System.currentTimeMillis(), builder.build());
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}