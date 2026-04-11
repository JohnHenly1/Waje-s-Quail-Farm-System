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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class ScheduleActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;
    private String RECUR_ONCE;

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

    private FirebaseFirestore db;
    private String currentUserEmail;
    private ListenerRegistration tasksListener;
    private RoleManager roleManager;

    private String[] monthNames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        EdgeToEdge.enable(this);

        RECUR_ONCE = getString(R.string.recur_once);
        monthNames = getResources().getStringArray(R.array.month_names);

        cameraHelper = new CameraHelper(this, (uri, results) -> {
            int gradeA = 0, gradeB = 0, gradeC = 0;
            for (DetectionResult r : results) {
                switch (r.getLabel()) {
                    case "egg_grade_a": gradeA++; break;
                    case "egg_grade_b": gradeB++; break;
                    case "egg_grade_c": gradeC++; break;
                }
            }
            int total = gradeA + gradeB + gradeC;
            Toast.makeText(this, getString(R.string.detected_eggs, total), Toast.LENGTH_SHORT).show();
        });

        setContentView(R.layout.activity_schedule);
        createNotificationChannel();

        db = FirebaseFirestore.getInstance();
        currentUserEmail = getIntent().getStringExtra("username");
        if (currentUserEmail == null || currentUserEmail.isEmpty()) {
            currentUserEmail = "default_user";
        }

        AccountManager accountManager = new AccountManager(this);
        roleManager = new RoleManager(accountManager.getCurrentRole());

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


        ImageButton addBtn = findViewById(R.id.AddScheduleBtn);
        if (roleManager.canAddTask()) {
            addBtn.setVisibility(View.VISIBLE);
            addBtn.setOnClickListener(v -> showAddScheduleDialog());
        } else {
            addBtn.setVisibility(View.GONE);
        }

        findViewById(R.id.seeCalendarBtn).setOnClickListener(v -> showFullCalendar());
        findViewById(R.id.bulkDeleteBtn).setOnClickListener(v -> showBulkDeleteDialog());
        findViewById(R.id.taskDetailsBtn).setOnClickListener(v -> showAllTaskDetails());

        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        LinearLayout cameraButton = findViewById(R.id.CameraButton);
        if (cameraButton != null) {
            cameraButton.setClickable(true);
            cameraButton.setFocusable(true);
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }

        updateCalendarUI();
        setupSwipeGestures();
        listenToTasks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tasksListener != null) tasksListener.remove();
    }

    private void listenToTasks() {
        tasksListener = db.collection("farm_data")
                .document("shared")
                .collection("tasks")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        Toast.makeText(this, getString(R.string.failed_to_load_tasks, e.getMessage()), Toast.LENGTH_SHORT).show();
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
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
        db.collection("farm_data").document("shared")
                .collection("tasks").document(task.firestoreId)
                .update("status", task.status)
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.error_updating_status, e.getMessage()), Toast.LENGTH_SHORT).show());
    }

    private void deleteTaskFromFirestore(Task task) {
        if (task.firestoreId == null) return;
        db.collection("farm_data").document("shared")
                .collection("tasks").document(task.firestoreId)
                .delete()
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.error_deleting, e.getMessage()), Toast.LENGTH_SHORT).show());
    }

    private void deleteRecurringSeriesFromFirestore(Task task) {
        if (task.recurrenceGroupId == null) { deleteTaskFromFirestore(task); return; }
        db.collection("farm_data").document("shared").collection("tasks")
                .whereEqualTo("recurrenceGroupId", task.recurrenceGroupId)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    for (QueryDocumentSnapshot doc : querySnapshot) batch.delete(doc.getReference());
                    batch.commit()
                            .addOnSuccessListener(unused ->
                                    Toast.makeText(this, getString(R.string.all_recurring_deleted), Toast.LENGTH_SHORT).show())
                            .addOnFailureListener(e ->
                                    Toast.makeText(this, getString(R.string.error_deleting, e.getMessage()), Toast.LENGTH_SHORT).show());
                });
    }

    private void bulkDeleteFromFirestore(List<Task> tasksToDelete) {
        com.google.firebase.firestore.WriteBatch batch = db.batch();
        for (Task task : tasksToDelete) {
            if (task.firestoreId != null) {
                batch.delete(db.collection("farm_data").document("shared")
                        .collection("tasks").document(task.firestoreId));
            }
        }
        batch.commit()
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, getString(R.string.selected_schedules_deleted), Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.error_deleting_tasks, e.getMessage()), Toast.LENGTH_SHORT).show());
    }

    private void showAddScheduleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_schedule, null);

        EditText    editTaskTitle        = dialogView.findViewById(R.id.editTaskTitle);
        Spinner     spinnerCategory      = dialogView.findViewById(R.id.spinnerCategory);
        TextView    textTime             = dialogView.findViewById(R.id.textTime);
        TextView    txtCurrentMonth      = dialogView.findViewById(R.id.txtCurrentMonth);
        GridLayout  calendarGrid         = dialogView.findViewById(R.id.calendarGrid);
        ImageButton btnPrevMonth         = dialogView.findViewById(R.id.btnPrevMonth);
        ImageButton btnNextMonth         = dialogView.findViewById(R.id.btnNextMonth);
        Button      btnWeekdays          = dialogView.findViewById(R.id.btnWeekdays);
        Button      btnWeekends          = dialogView.findViewById(R.id.btnWeekends);
        Button      btnFullMonth         = dialogView.findViewById(R.id.btnFullMonth);
        Button      btnFullYear          = dialogView.findViewById(R.id.btnFullYear);
        Button      btnClearSelection    = dialogView.findViewById(R.id.btnClearSelection);
        TextView    txtSummary           = dialogView.findViewById(R.id.txtScheduleSummary);
        TextView    txtPatternSuggestion = dialogView.findViewById(R.id.txtPatternSuggestion);

        String[] categories = getResources().getStringArray(R.array.task_categories);
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(catAdapter);

        final String[] selectedTime = {"08:00 AM"};
        final String[] selectedRecurrence = {RECUR_ONCE};
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

        final List<Long> selectedDates = new ArrayList<>();
        final Calendar   viewCalendar  = Calendar.getInstance();
        viewCalendar.set(Calendar.DAY_OF_MONTH, 1);
        viewCalendar.set(Calendar.HOUR_OF_DAY, 0); viewCalendar.set(Calendar.MINUTE, 0);
        viewCalendar.set(Calendar.SECOND, 0); viewCalendar.set(Calendar.MILLISECOND, 0);

        final int[] patternGap = {0};

        Runnable updateGrid = new Runnable() {
            @Override
            public void run() {
                calendarGrid.removeAllViews();
                txtCurrentMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(viewCalendar.getTime()));
                txtSummary.setText(getString(R.string.total_dates_selected, selectedDates.size()));

                if (selectedDates.size() >= 2) {
                    List<Long> sorted = new ArrayList<>(selectedDates);
                    Collections.sort(sorted);
                    long diff = sorted.get(1) - sorted.get(0);
                    int days = (int) (diff / (1000 * 60 * 60 * 24));
                    if (days > 0) {
                        patternGap[0] = days;
                        txtPatternSuggestion.setVisibility(View.VISIBLE);
                        txtPatternSuggestion.setText(getString(R.string.repeat_every_days_suggestion, days));
                    } else txtPatternSuggestion.setVisibility(View.GONE);
                } else txtPatternSuggestion.setVisibility(View.GONE);

                String[] daysHeaders = {
                    getString(R.string.Sunday).substring(0, 1),
                    getString(R.string.Monday).substring(0, 1),
                    getString(R.string.Tuesday).substring(0, 1),
                    getString(R.string.Wednesday).substring(0, 1),
                    getString(R.string.Thursday).substring(0, 2),
                    getString(R.string.Friday).substring(0, 1),
                    getString(R.string.Saturday).substring(0, 1)
                };
                for (String d : daysHeaders)
                    calendarGrid.addView(makeHeaderCell(d));

                Calendar cal = (Calendar) viewCalendar.clone();
                int firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1;
                for (int i = 0; i < firstDow; i++) calendarGrid.addView(makeSpacer());

                int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= daysInMonth; i++) {
                    final int day = i;
                    final int month = cal.get(Calendar.MONTH);
                    final int year = cal.get(Calendar.YEAR);
                    Calendar dateCal = Calendar.getInstance();
                    dateCal.set(year, month, day, 0, 0, 0);
                    dateCal.set(Calendar.MILLISECOND, 0);
                    final long dateKey = dateCal.getTimeInMillis();

                    TextView tv = makeDayCell(String.valueOf(i));
                    if (selectedDates.contains(dateKey)) {
                        tv.setBackgroundResource(R.drawable.bg_dayselected);
                        tv.setTextColor(Color.WHITE);
                    }
                    tv.setOnClickListener(v -> {
                        if (selectedDates.contains(dateKey)) {
                            selectedDates.remove(dateKey);
                            if (selectedDates.isEmpty()) selectedRecurrence[0] = RECUR_ONCE;
                            else if (selectedDates.size() == 1) selectedRecurrence[0] = RECUR_ONCE;
                            else selectedRecurrence[0] = getString(R.string.recur_custom);
                        } else {
                            selectedDates.add(dateKey);
                            selectedRecurrence[0] = selectedDates.size() > 1 ? getString(R.string.recur_custom) : RECUR_ONCE;
                        }
                        run();
                    });
                    calendarGrid.addView(tv);
                }
            }
        };

        btnWeekdays.setOnClickListener(v -> {
            selectedRecurrence[0] = getString(R.string.recur_weekdays);
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, 1, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= days; i++) {
                    cal.set(Calendar.DAY_OF_MONTH, i);
                    int dow = cal.get(Calendar.DAY_OF_WEEK);
                    if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                        long key = cal.getTimeInMillis();
                        if (!selectedDates.contains(key)) selectedDates.add(key);
                    }
                }
                updateGrid.run();
            }, viewCalendar.get(Calendar.YEAR), viewCalendar.get(Calendar.MONTH), 1).show();
        });

        btnWeekends.setOnClickListener(v -> {
            selectedRecurrence[0] = getString(R.string.recur_weekends);
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, 1, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= days; i++) {
                    cal.set(Calendar.DAY_OF_MONTH, i);
                    int dow = cal.get(Calendar.DAY_OF_WEEK);
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                        long key = cal.getTimeInMillis();
                        if (!selectedDates.contains(key)) selectedDates.add(key);
                    }
                }
                updateGrid.run();
            }, viewCalendar.get(Calendar.YEAR), viewCalendar.get(Calendar.MONTH), 1).show();
        });

        btnFullMonth.setOnClickListener(v -> {
            selectedRecurrence[0] = getString(R.string.recur_monthly);
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, 1, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= days; i++) {
                    cal.set(Calendar.DAY_OF_MONTH, i);
                    long key = cal.getTimeInMillis();
                    if (!selectedDates.contains(key)) selectedDates.add(key);
                }
                updateGrid.run();
            }, viewCalendar.get(Calendar.YEAR), viewCalendar.get(Calendar.MONTH), 1).show();
        });

        btnFullYear.setOnClickListener(v -> {
            View yearDialogView = LayoutInflater.from(this).inflate(R.layout.dialog_year_range, null);
            TextView textStartDate = yearDialogView.findViewById(R.id.textStartDate);
            TextView textEndDate = yearDialogView.findViewById(R.id.textEndDate);
            RadioGroup rgFilter = yearDialogView.findViewById(R.id.rgYearFilter);
            
            final Calendar startCal = Calendar.getInstance();
            final Calendar endCal = Calendar.getInstance();
            endCal.set(Calendar.MONTH, Calendar.DECEMBER);
            endCal.set(Calendar.DAY_OF_MONTH, 31);
            
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            textStartDate.setText(df.format(startCal.getTime()));
            textEndDate.setText(df.format(endCal.getTime()));
            
            textStartDate.setOnClickListener(vStart -> {
                new DatePickerDialog(this, (view, year, month, day) -> {
                    startCal.set(year, month, day);
                    textStartDate.setText(df.format(startCal.getTime()));
                }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)).show();
            });
            
            textEndDate.setOnClickListener(vEnd -> {
                new DatePickerDialog(this, (view, year, month, day) -> {
                    endCal.set(year, month, day);
                    textEndDate.setText(df.format(endCal.getTime()));
                }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show();
            });

            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.select_year_range))
                    .setView(yearDialogView)
                    .setPositiveButton(getString(R.string.confirm), (d, w) -> {
                        try {
                            if (startCal.after(endCal)) { Toast.makeText(this, getString(R.string.start_year_after_end), Toast.LENGTH_SHORT).show(); return; }

                            int checkedId = rgFilter.getCheckedRadioButtonId();
                            if (checkedId == R.id.rbYearlyWeekdays) selectedRecurrence[0] = getString(R.string.recur_yearly_weekdays);
                            else if (checkedId == R.id.rbYearlyWeekends) selectedRecurrence[0] = getString(R.string.recur_yearly_weekends);
                            else selectedRecurrence[0] = getString(R.string.recur_yearly);

                            Calendar cal = (Calendar) startCal.clone();
                            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                            
                            while (!cal.after(endCal)) {
                                int dow = cal.get(Calendar.DAY_OF_WEEK);
                                boolean match = true;
                                if (checkedId == R.id.rbYearlyWeekdays) {
                                    match = (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY);
                                } else if (checkedId == R.id.rbYearlyWeekends) {
                                    match = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY);
                                }

                                if (match) {
                                    long key = cal.getTimeInMillis();
                                    if (!selectedDates.contains(key)) selectedDates.add(key);
                                }
                                cal.add(Calendar.DAY_OF_MONTH, 1);
                            }
                            updateGrid.run();
                        } catch (Exception e) { Toast.makeText(this, getString(R.string.invalid_year), Toast.LENGTH_SHORT).show(); }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        });

        btnClearSelection.setOnClickListener(v -> { selectedDates.clear(); selectedRecurrence[0] = RECUR_ONCE; updateGrid.run(); });

        txtPatternSuggestion.setOnClickListener(v -> {
            if (selectedDates.size() < 2) return;
            List<Long> sorted = new ArrayList<>(selectedDates);
            Collections.sort(sorted);
            Calendar cur = Calendar.getInstance();
            cur.setTimeInMillis(sorted.get(0));
            int yearLimit = cur.get(Calendar.YEAR) + 1;
            while (cur.get(Calendar.YEAR) <= yearLimit) {
                long key = cur.getTimeInMillis();
                if (!selectedDates.contains(key)) selectedDates.add(key);
                cur.add(Calendar.DAY_OF_MONTH, patternGap[0]);
            }
            selectedRecurrence[0] = getString(R.string.recur_every_days, patternGap[0]);
            updateGrid.run();
        });

        btnPrevMonth.setOnClickListener(v -> { viewCalendar.add(Calendar.MONTH, -1); updateGrid.run(); });
        btnNextMonth.setOnClickListener(v -> { viewCalendar.add(Calendar.MONTH,  1); updateGrid.run(); });
        updateGrid.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_new_task))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.schedule), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = editTaskTitle.getText().toString().trim();
            String category = spinnerCategory.getSelectedItem().toString();
            if (title.isEmpty()) { Toast.makeText(this, getString(R.string.task_title_empty), Toast.LENGTH_SHORT).show(); return; }
            if (selectedDates.isEmpty()) { Toast.makeText(this, getString(R.string.please_select_date), Toast.LENGTH_SHORT).show(); return; }

            View previewView = LayoutInflater.from(this).inflate(R.layout.dialog_schedule_preview, null);
            ((TextView) previewView.findViewById(R.id.previewTitle)).setText(title);
            ((TextView) previewView.findViewById(R.id.previewCategory)).setText(category);
            ((TextView) previewView.findViewById(R.id.previewTime)).setText(selectedTime[0]);
            ((TextView) previewView.findViewById(R.id.previewTotalDates)).setText(selectedDates.size() + " " + getString(R.string.days_unit) + " (" + selectedRecurrence[0] + ")");

            new AlertDialog.Builder(this)
                    .setView(previewView)
                    .setPositiveButton(getString(R.string.confirm_and_save), (dConfirm, wConfirm) -> {
                        String groupId = UUID.randomUUID().toString();

                        AlertDialog progress = new AlertDialog.Builder(this)
                                .setMessage(getString(R.string.scheduling_tasks))
                                .setCancelable(false)
                                .show();

                        int batchSize = 400;
                        int totalTasks = selectedDates.size();
                        final int[] completedBatches = {0};
                        int numBatches = (totalTasks + batchSize - 1) / batchSize;

                        int notificationLimit = 50;
                        int notificationsCreated = 0;

                        for (int i = 0; i < totalTasks; i += batchSize) {
                            com.google.firebase.firestore.WriteBatch batch = db.batch();
                            int end = Math.min(i + batchSize, totalTasks);

                            for (int j = i; j < end; j++) {
                                Long time = selectedDates.get(j);
                                Calendar cal = Calendar.getInstance();
                                cal.setTimeInMillis(time);
                                Task t = new Task(null, title, category, selectedTime[0], "Pending",
                                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
                                        selectedRecurrence[0], groupId);

                                DocumentReference ref = db.collection("farm_data").document("shared")
                                        .collection("tasks").document();
                                batch.set(ref, buildTaskMap(t));

                                if (notificationsCreated < notificationLimit) {
                                    scheduleNotification(t, selHour[0], selMinute[0]);
                                    notificationsCreated++;
                                }
                            }

                            batch.commit().addOnCompleteListener(taskResult -> {
                                completedBatches[0]++;
                                if (completedBatches[0] >= numBatches) {
                                    progress.dismiss();
                                    Toast.makeText(this, getString(R.string.tasks_scheduled, totalTasks), Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                }
                            }).addOnFailureListener(e -> {
                                progress.dismiss();
                                Toast.makeText(this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                            });
                        }
                    })
                    .setNegativeButton(getString(R.string.back), null)
                    .show();
        });
    }

    private void showDeleteOptions(Task task) {
        boolean isRecurring = task.recurrenceGroupId != null && !RECUR_ONCE.equals(task.recurrence);
        if (!isRecurring) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.delete_task_title)).setMessage(getString(R.string.delete_task_msg)).setPositiveButton(getString(R.string.delete), (d, w) -> deleteTaskFromFirestore(task)).setNegativeButton(getString(R.string.cancel), null).show();
        } else {
            new AlertDialog.Builder(this).setTitle(getString(R.string.delete_recurring_title)).setItems(new String[]{getString(R.string.delete_this_only), getString(R.string.delete_all_series)}, (d, which) -> {
                if (which == 0) deleteTaskFromFirestore(task);
                else deleteRecurringSeriesFromFirestore(task);
            }).setNegativeButton(getString(R.string.cancel), null).show();
        }
    }

    private void showBulkDeleteDialog() {
        if (taskList.isEmpty()) { Toast.makeText(this, getString(R.string.no_tasks_to_delete), Toast.LENGTH_SHORT).show(); return; }

        Map<String, List<Task>> groups = new LinkedHashMap<>();
        for (Task task : taskList) {
            String key = task.recurrenceGroupId;
            if (key == null || key.isEmpty()) key = "SINGLE_" + task.firestoreId;
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(task);
        }

        List<String> groupKeys = new ArrayList<>(groups.keySet());
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);

        Map<String, CheckBox> checkBoxes = new HashMap<>();

        for (String key : groupKeys) {
            List<Task> groupTasks = groups.get(key);
            Task first = groupTasks.get(0);

            View itemView = getLayoutInflater().inflate(R.layout.item_bulk_delete, container, false);
            CheckBox cb = itemView.findViewById(R.id.checkDelete);
            TextView title = itemView.findViewById(R.id.bulkTaskTitle);
            TextView info = itemView.findViewById(R.id.bulkTaskInfo);
            TextView time = itemView.findViewById(R.id.bulkTaskTime);
            View indicator = itemView.findViewById(R.id.bulkStatusIndicator);

            title.setText(first.title);
            String infoText = first.category + " | " + (groupTasks.size() > 1 ? groupTasks.size() + " " + getString(R.string.days_unit) : first.day + " " + monthNames[first.month]);
            info.setText(infoText);
            time.setText(first.time);

            if (getString(R.string.status_done).equals(first.status)) indicator.setBackgroundResource(R.drawable.bg_status_done);
            else if (getString(R.string.status_ongoing).equals(first.status)) indicator.setBackgroundResource(R.drawable.bg_status_ongoing);
            else indicator.setBackgroundResource(R.drawable.bg_status_pending);

            checkBoxes.put(key, cb);
            container.addView(itemView);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(container);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_schedules_delete))
                .setView(scrollView)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    List<Task> toDelete = new ArrayList<>();
                    for (Map.Entry<String, CheckBox> entry : checkBoxes.entrySet()) {
                        if (entry.getValue().isChecked()) {
                            toDelete.addAll(groups.get(entry.getKey()));
                        }
                    }
                    if (!toDelete.isEmpty()) bulkDeleteFromFirestore(toDelete);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showAllTaskDetails() {
        if (taskList.isEmpty()) { Toast.makeText(this, getString(R.string.no_tasks_assigned), Toast.LENGTH_SHORT).show(); return; }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_all_task_details, null);
        LinearLayout container = dialogView.findViewById(R.id.allTasksContainer);

        Map<String, List<Task>> groups = new LinkedHashMap<>();
        for (Task task : taskList) {
            String key = task.recurrenceGroupId;
            if (key == null || key.isEmpty()) {
                key = "SINGLE_" + task.firestoreId;
            }
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
            }
            groups.get(key).add(task);
        }

        int idCounter = 1;
        for (Map.Entry<String, List<Task>> entry : groups.entrySet()) {
            List<Task> groupTasks = entry.getValue();
            if (groupTasks.isEmpty()) continue;

            Task first = groupTasks.get(0);
            View rowView = getLayoutInflater().inflate(R.layout.item_task_all_details, container, false);

            TextView idTv = rowView.findViewById(R.id.rowId);
            View statusIndicator = rowView.findViewById(R.id.rowStatusIndicator);
            TextView titleTv = rowView.findViewById(R.id.rowTitle);
            TextView categoryTv = rowView.findViewById(R.id.rowCategory);
            TextView dateInfoTv = rowView.findViewById(R.id.rowDateInfo);
            TextView timeTv = rowView.findViewById(R.id.rowTime);
            TextView statusTextTv = rowView.findViewById(R.id.rowStatusText);

            idTv.setText(String.valueOf(idCounter++));
            idTv.setOnClickListener(v -> showTaskGroupDetailsDialog(groupTasks));

            titleTv.setText(first.title);
            categoryTv.setText(first.category);
            timeTv.setText(first.time);
            statusTextTv.setText(first.status);

            if (groupTasks.size() > 1) {
                dateInfoTv.setText(first.recurrence + " " + first.category + " (" + groupTasks.size() + " " + getString(R.string.days_unit) + ")");
            } else {
                dateInfoTv.setText(first.day + " " + monthNames[first.month] + " " + first.year + " (" + first.category + ")");
            }

            if (getString(R.string.status_done).equals(first.status)) statusIndicator.setBackgroundResource(R.drawable.bg_status_done);
            else if (getString(R.string.status_ongoing).equals(first.status)) statusIndicator.setBackgroundResource(R.drawable.bg_status_ongoing);
            else statusIndicator.setBackgroundResource(R.drawable.bg_status_pending);

            container.addView(rowView);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.all_assigned_tasks))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    private void showTaskGroupDetailsDialog(List<Task> tasks) {
        Collections.sort(tasks, (a, b) -> {
            if (a.year != b.year) return a.year - b.year;
            if (a.month != b.month) return a.month - b.month;
            return a.day - b.day;
        });

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_task_group_details, null);
        TextView titleTv = view.findViewById(R.id.detailTaskTitle);
        TextView categoryTv = view.findViewById(R.id.detailTaskCategory);
        TextView timeTv = view.findViewById(R.id.detailTaskTime);
        LinearLayout datesContainer = view.findViewById(R.id.datesContainer);

        Task first = tasks.get(0);
        titleTv.setText(first.title);
        categoryTv.setText(getString(R.string.task_category_label, first.category));
        timeTv.setText(getString(R.string.task_time_label, first.time));

        for (Task t : tasks) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 12, 0, 12);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView dateTv = new TextView(this);
            dateTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            dateTv.setText(t.day + " " + monthNames[t.month] + " " + t.year);
            dateTv.setTextColor(Color.BLACK);
            dateTv.setTextSize(15);

            TextView statusTv = new TextView(this);
            statusTv.setText(t.status);
            statusTv.setPadding(12, 4, 12, 4);
            statusTv.setTextSize(12);
            statusTv.setTypeface(null, Typeface.BOLD);
            statusTv.setTextColor(Color.WHITE);

            if (getString(R.string.status_done).equals(t.status)) statusTv.setBackgroundResource(R.drawable.bg_status_done);
            else if (getString(R.string.status_ongoing).equals(t.status)) statusTv.setBackgroundResource(R.drawable.bg_status_ongoing);
            else statusTv.setBackgroundResource(R.drawable.bg_status_pending);

            row.addView(dateTv);
            row.addView(statusTv);
            datesContainer.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.parseColor("#F3F4F6"));
            datesContainer.addView(divider);
        }

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    private void updateTasksUI() {
        if (tasksContainer == null) return;
        tasksContainer.removeAllViews();
        int done = 0, ongoing = 0, pending = 0;
        int selYear  = selectedDate.get(Calendar.YEAR);
        int selMonth = selectedDate.get(Calendar.MONTH);
        int selDay   = selectedDate.get(Calendar.DAY_OF_MONTH);

        Map<String, List<Task>> categoryGroups = new TreeMap<>();
        for (Task task : taskList) {
            if (task.year != selYear || task.month != selMonth || task.day != selDay) continue;

            if (!categoryGroups.containsKey(task.category)) {
                categoryGroups.put(task.category, new ArrayList<>());
            }
            categoryGroups.get(task.category).add(task);

            if (getString(R.string.status_done).equals(task.status)) done++;
            else if (getString(R.string.status_ongoing).equals(task.status)) ongoing++;
            else pending++;
        }

        Calendar now = Calendar.getInstance();
        boolean isPastDate = selectedDate.before(now) && !isSameDay(selectedDate, now);

        for (Map.Entry<String, List<Task>> entry : categoryGroups.entrySet()) {
            addCategoryHeader(entry.getKey());
            for (Task task : entry.getValue()) {
                View taskView = getLayoutInflater().inflate(R.layout.item_schedule_task, tasksContainer, false);
                TextView titleTv = taskView.findViewById(R.id.taskTitle);
                TextView categoryTv = taskView.findViewById(R.id.taskCategory);
                TextView timeTv = taskView.findViewById(R.id.taskTime);
                TextView recurrenceTv = taskView.findViewById(R.id.taskRecurrence);
                View statusIndicator = taskView.findViewById(R.id.statusIndicator);
                ImageButton deleteBtn = taskView.findViewById(R.id.deleteTaskBtn);

                titleTv.setText(task.title);
                categoryTv.setText(task.category);
                timeTv.setText(task.time);
                if (recurrenceTv != null) recurrenceTv.setText(task.recurrence != null ? task.recurrence : RECUR_ONCE);

                boolean isMissed = isPastDate && (getString(R.string.status_pending).equals(task.status) || getString(R.string.status_ongoing).equals(task.status));

                if (isMissed) {
                    taskView.setAlpha(0.7f);
                    taskView.setClickable(false);
                    statusIndicator.setBackgroundColor(Color.parseColor("#DC2626")); // Red for missed
                    titleTv.setText(task.title + " (MISSED)");
                } else {
                    taskView.setOnClickListener(v -> {
                        String[] statuses = {getString(R.string.status_pending), getString(R.string.status_ongoing), getString(R.string.status_done)};
                        new AlertDialog.Builder(this).setTitle(getString(R.string.update_status)).setItems(statuses, (dialog, which) -> {
                            task.status = statuses[which];
                            updateTaskStatus(task);
                        }).show();
                    });
                }

                deleteBtn.setVisibility(roleManager.canDeleteTask() ? View.VISIBLE : View.GONE);
                if (roleManager.canDeleteTask()) {
                    deleteBtn.setOnClickListener(v -> showDeleteOptions(task));
                }

                if (!isMissed) {
                    if (getString(R.string.status_done).equals(task.status)) statusIndicator.setBackgroundResource(R.drawable.bg_status_done);
                    else if (getString(R.string.status_ongoing).equals(task.status)) statusIndicator.setBackgroundResource(R.drawable.bg_status_ongoing);
                    else statusIndicator.setBackgroundResource(R.drawable.bg_status_pending);
                }

                tasksContainer.addView(taskView);
            }
        }

        if (doneCount != null) doneCount.setText(String.valueOf(done));
        if (ongoingCount != null) ongoingCount.setText(String.valueOf(ongoing));
        if (pendingCount != null) pendingCount.setText(String.valueOf(pending));
        View placeholder = findViewById(R.id.noTasksPlaceholder);
        if (placeholder != null) placeholder.setVisibility(tasksContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void addCategoryHeader(String category) {
        TextView header = new TextView(this);
        header.setText(category);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(Color.parseColor("#374151"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 32, 0, 16);
        header.setLayoutParams(params);
        tasksContainer.addView(header);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("task_reminder_channel", "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for farm tasks");
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
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
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1);
    }

    private void showFullCalendar() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedDate.set(year, month, dayOfMonth);
            currentWeekCalendar = (Calendar) selectedDate.clone();
            alignCalendarToMonday(currentWeekCalendar);
            updateCalendarUI();
            updateTasksUI();
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateCalendarUI() {
        if (isSameDay(selectedDate, today)) monthText.setText(new SimpleDateFormat(getString(R.string.today_format), Locale.getDefault()).format(today.getTime()));
        else monthText.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(selectedDate.getTime()));
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
        findViewById(R.id.main).setOnTouchListener((v, event) -> { gestureDetector.onTouchEvent(event); return true; });
        findViewById(R.id.scrollView).setOnTouchListener((v, event) -> { gestureDetector.onTouchEvent(event); return false; });
    }

    private boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private void scheduleNotification(Task task, int hour, int minute) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar calendar = Calendar.getInstance();
        calendar.set(task.year, task.month, task.day, hour, minute, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.before(Calendar.getInstance())) return;
        
        Intent intent = new Intent(this, TaskAlarmReceiver.class);
        intent.putExtra("taskTitle", task.title);
        intent.putExtra("taskCategory", task.category);
        
        int rc = (task.title + task.year + task.month + task.day + hour + minute).hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(this, rc, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
        else am.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
    }

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

    private static class Task {
        String firestoreId;
        String title, category, time, status;
        int    year, month, day;
        String recurrence;
        String recurrenceGroupId;

        Task(String firestoreId, String title, String category, String time, String status, int year, int month, int day, String recurrence, String recurrenceGroupId) {
            this.firestoreId = firestoreId;
            this.title = title;
            this.category = category;
            this.time = time;
            this.status = status;
            this.year = year;
            this.month = month;
            this.day = day;
            this.recurrence = recurrence;
            this.recurrenceGroupId = recurrenceGroupId;
        }
    }

    public static class TaskAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String title = intent.getStringExtra("taskTitle");
            String category = intent.getStringExtra("taskCategory");
            createChannel(context);
            
            String timestamp = new SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()).format(new Date());
            try {
                AccountManager accountManager = new AccountManager(context);
                if (!accountManager.isScheduleEnabled()) return;
                if (accountManager.isGlobalDataEnabled()) {
                    GlobalData.INSTANCE.addAlert(context.getString(R.string.task_reminder_title, title) + " (" + category + ")", timestamp, "System");
                }
            } catch (Exception e) { e.printStackTrace(); }
            
            Intent alertIntent = new Intent(context, AlertsActivity.class);
            alertIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 0, alertIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "task_reminder_channel")
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle(context.getString(R.string.task_reminder_title, title))
                .setContentText(context.getString(R.string.task_reminder_msg, category))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                
            try {
                NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                // Use hash of title and category as ID to prevent multiple notification icons for the same task
                int notificationId = (title + category).hashCode();
                nm.notify(notificationId, builder.build());
            } catch (SecurityException e) { e.printStackTrace(); }
        }
        
        private static void createChannel(Context context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel("task_reminder_channel", "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifications for farm tasks");
                channel.enableLights(true); 
                channel.enableVibration(true);
                channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                NotificationManager nm = context.getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
            }
        }
    }
}