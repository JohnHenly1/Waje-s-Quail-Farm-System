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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ScheduleActivity extends AppCompatActivity {

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

    private final String[] monthNames = {
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_schedule);

        createNotificationChannel();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        today = Calendar.getInstance();
        selectedDate = (Calendar) today.clone();
        currentWeekCalendar = (Calendar) today.clone();
        
        alignCalendarToMonday(currentWeekCalendar);

        monthText = findViewById(R.id.month);
        tasksContainer = findViewById(R.id.tasksContainer);
        doneCount = findViewById(R.id.doneCount);
        ongoingCount = findViewById(R.id.ongoingCount);
        pendingCount = findViewById(R.id.pendingCount);
        
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
        updateTasksUI();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Task Reminders";
            String description = "Notifications for farm tasks";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel("task_reminder_channel", name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
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
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
    }

    private void showFullCalendar() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedDate.set(year, month, dayOfMonth);
            currentWeekCalendar = (Calendar) selectedDate.clone();
            alignCalendarToMonday(currentWeekCalendar);
            updateCalendarUI();
            updateTasksUI();
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH));
        datePickerDialog.show();
    }

    private void showAddScheduleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_schedule, null);
        EditText editTaskTitle = dialogView.findViewById(R.id.editTaskTitle);
        Spinner spinnerCategory = dialogView.findViewById(R.id.spinnerCategory);
        TextView textTime = dialogView.findViewById(R.id.textTime);
        TextView txtCurrentMonth = dialogView.findViewById(R.id.txtCurrentMonth);
        GridLayout calendarGrid = dialogView.findViewById(R.id.calendarGrid);
        ImageButton btnPrevMonth = dialogView.findViewById(R.id.btnPrevMonth);
        ImageButton btnNextMonth = dialogView.findViewById(R.id.btnNextMonth);

        String[] categories = {"Feeding", "Watering", "Cleaning", "Egg Collection", "Lighting", "Health Check"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categories);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(adapter);

        final String[] selectedTime = {"08:00 AM"};
        final int[] selHour = {8};
        final int[] selMinute = {0};
        textTime.setOnClickListener(v -> {
            TimePickerDialog timePickerDialog = new TimePickerDialog(this, (view, hourOfDay, minute) -> {
                selHour[0] = hourOfDay;
                selMinute[0] = minute;
                String amPm = (hourOfDay < 12) ? "AM" : "PM";
                int displayHour = (hourOfDay > 12) ? (hourOfDay - 12) : (hourOfDay == 0 ? 12 : hourOfDay);
                selectedTime[0] = String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, minute, amPm);
                textTime.setText(selectedTime[0]);
            }, selHour[0], selMinute[0], false);
            timePickerDialog.show();
        });

        final Set<Long> selectedDates = new HashSet<>();
        final Calendar viewCalendar = Calendar.getInstance();
        viewCalendar.set(Calendar.DAY_OF_MONTH, 1);

        Runnable updateGrid = new Runnable() {
            @Override
            public void run() {
                calendarGrid.removeAllViews();
                txtCurrentMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(viewCalendar.getTime()));

                String[] weekDays = {"S", "M", "T", "W", "Th", "F", "S"};
                for (String day : weekDays) {
                    TextView tv = new TextView(ScheduleActivity.this);
                    tv.setText(day);
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(0, 10, 0, 10);
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setTextColor(Color.GRAY);
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = 0;
                    params.height = GridLayout.LayoutParams.WRAP_CONTENT;
                    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                    tv.setLayoutParams(params);
                    calendarGrid.addView(tv);
                }

                Calendar cal = (Calendar) viewCalendar.clone();
                int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1;
                for (int i = 0; i < firstDayOfWeek; i++) {
                    View spacer = new View(ScheduleActivity.this);
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = 0;
                    params.height = 1;
                    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                    spacer.setLayoutParams(params);
                    calendarGrid.addView(spacer);
                }

                int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= daysInMonth; i++) {
                    final int day = i;
                    final int month = cal.get(Calendar.MONTH);
                    final int year = cal.get(Calendar.YEAR);
                    
                    Calendar dateCal = Calendar.getInstance();
                    dateCal.set(year, month, day, 0, 0, 0);
                    dateCal.set(Calendar.MILLISECOND, 0);
                    final long dateKey = dateCal.getTimeInMillis();

                    TextView tv = new TextView(ScheduleActivity.this);
                    tv.setText(String.valueOf(i));
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(0, 20, 0, 20);
                    tv.setClickable(true);
                    tv.setFocusable(true);
                    
                    if (selectedDates.contains(dateKey)) {
                        tv.setBackgroundResource(R.drawable.bg_dayselected);
                        tv.setTextColor(Color.WHITE);
                    } else {
                        tv.setTextColor(Color.BLACK);
                    }

                    tv.setOnClickListener(v -> {
                        if (selectedDates.contains(dateKey)) {
                            selectedDates.remove(dateKey);
                            tv.setBackground(null);
                            tv.setTextColor(Color.BLACK);
                        } else {
                            selectedDates.add(dateKey);
                            tv.setBackgroundResource(R.drawable.bg_dayselected);
                            tv.setTextColor(Color.WHITE);
                        }
                    });

                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = 0;
                    params.height = GridLayout.LayoutParams.WRAP_CONTENT;
                    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                    tv.setLayoutParams(params);
                    calendarGrid.addView(tv);
                }
            }
        };

        btnPrevMonth.setOnClickListener(v -> {
            viewCalendar.add(Calendar.MONTH, -1);
            updateGrid.run();
        });

        btnNextMonth.setOnClickListener(v -> {
            viewCalendar.add(Calendar.MONTH, 1);
            updateGrid.run();
        });

        updateGrid.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add New Task")
               .setView(dialogView)
               .setPositiveButton("Schedule", (dialog, which) -> {
                   String title = editTaskTitle.getText().toString();
                   String category = spinnerCategory.getSelectedItem().toString();
                   
                   if (title.isEmpty()) {
                       Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show();
                       return;
                   }
                   
                   if (selectedDates.isEmpty()) {
                       Toast.makeText(this, "Please select at least one date", Toast.LENGTH_SHORT).show();
                       return;
                   }

                   for (long dateMillis : selectedDates) {
                       Calendar c = Calendar.getInstance();
                       c.setTimeInMillis(dateMillis);
                       Task newTask = new Task(title, category, selectedTime[0], "Pending", 
                           c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
                       addTask(newTask);
                       scheduleNotification(newTask, selHour[0], selMinute[0]);
                   }
                   Toast.makeText(this, "Tasks scheduled", Toast.LENGTH_SHORT).show();
               })
               .setNegativeButton("Cancel", null)
               .show();
    }

    private void showBulkDeleteDialog() {
        if (taskList.isEmpty()) {
            Toast.makeText(this, "No tasks to delete", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] taskTitles = new String[taskList.size()];
        boolean[] selectedTasks = new boolean[taskList.size()];
        List<Integer> tasksToRemove = new ArrayList<>();

        for (int i = 0; i < taskList.size(); i++) {
            taskTitles[i] = taskList.get(i).title + " (" + taskList.get(i).time + ")";
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.delete_selected_tasks)
            .setMultiChoiceItems(taskTitles, selectedTasks, (dialog, which, isChecked) -> {
                if (isChecked) {
                    tasksToRemove.add(which);
                } else {
                    tasksToRemove.remove(Integer.valueOf(which));
                }
            })
            .setPositiveButton("Delete", (dialog, which) -> {
                if (tasksToRemove.isEmpty()) {
                    Toast.makeText(this, R.string.no_tasks_selected, Toast.LENGTH_SHORT).show();
                    return;
                }

                tasksToRemove.sort((a, b) -> b.compareTo(a));
                for (int index : tasksToRemove) {
                    taskList.remove(index);
                }
                updateTasksUI();
                Toast.makeText(this, "Selected tasks deleted", Toast.LENGTH_SHORT).show();
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
            details.append("Date: ").append(task.day).append(" ").append(monthNames[task.month]).append(" ").append(task.year).append("\n");
            details.append("Status: ").append(task.status).append("\n\n");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("All Assigned Tasks")
            .setMessage(details.toString())
            .setPositiveButton("Close", null)
            .show();
    }

    private void addTask(Task task) {
        taskList.add(0, task);
        updateTasksUI();
    }

    private void scheduleNotification(Task task, int hour, int minute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                startActivity(intent);
                return;
            }
        }

        Calendar calendar = Calendar.getInstance();
        calendar.set(task.year, task.month, task.day, hour, minute, 0);

        if (calendar.before(Calendar.getInstance())) {
            return; // Don't schedule for the past
        }

        Intent intent = new Intent(this, TaskAlarmReceiver.class);
        intent.putExtra("taskTitle", task.title);
        intent.putExtra("taskCategory", task.category);
        
        int requestCode = (task.title + task.year + task.month + task.day + hour + minute).hashCode();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        }
    }

    private void updateTasksUI() {
        if (tasksContainer == null) return;
        tasksContainer.removeAllViews();
        
        int done = 0, ongoing = 0, pending = 0;
        int selYear = selectedDate.get(Calendar.YEAR);
        int selMonth = selectedDate.get(Calendar.MONTH);
        int selDay = selectedDate.get(Calendar.DAY_OF_MONTH);
        
        for (int i = 0; i < taskList.size(); i++) {
            Task task = taskList.get(i);
            if (task.year == selYear && task.month == selMonth && task.day == selDay) {
                View taskView = getLayoutInflater().inflate(R.layout.item_schedule_task, tasksContainer, false);
                TextView titleTv = taskView.findViewById(R.id.taskTitle);
                TextView categoryTv = taskView.findViewById(R.id.taskCategory);
                TextView timeTv = taskView.findViewById(R.id.taskTime);
                View statusIndicator = taskView.findViewById(R.id.statusIndicator);
                ImageButton deleteBtn = taskView.findViewById(R.id.deleteTaskBtn);
                
                titleTv.setText(task.title);
                categoryTv.setText(task.category);
                timeTv.setText(task.time);
                
                final int taskIndex = i;
                
                taskView.setOnClickListener(v -> {
                    String[] statuses = {"Pending", "Ongoing", "Done"};
                    new AlertDialog.Builder(this)
                        .setTitle("Update Status")
                        .setItems(statuses, (dialog, which) -> {
                            task.status = statuses[which];
                            updateTasksUI();
                        })
                        .show();
                });

                deleteBtn.setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Delete Task")
                        .setMessage("Are you sure you want to delete this task?")
                        .setPositiveButton("Delete", (dialog, which) -> {
                            taskList.remove(taskIndex);
                            updateTasksUI();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                });

                switch (task.status) {
                    case "Done":
                        done++;
                        statusIndicator.setBackgroundResource(R.drawable.bg_status_done);
                        break;
                    case "Ongoing":
                        ongoing++;
                        statusIndicator.setBackgroundResource(R.drawable.bg_status_ongoing);
                        break;
                    case "Pending":
                        pending++;
                        statusIndicator.setBackgroundResource(R.drawable.bg_status_pending);
                        break;
                }
                tasksContainer.addView(taskView);
            }
        }
        
        if (doneCount != null) doneCount.setText(String.valueOf(done));
        if (ongoingCount != null) ongoingCount.setText(String.valueOf(ongoing));
        if (pendingCount != null) pendingCount.setText(String.valueOf(pending));
        
        View placeholder = findViewById(R.id.noTasksPlaceholder);
        if (placeholder != null) {
            placeholder.setVisibility(tasksContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void updateCalendarUI() {
        if (isSameDay(selectedDate, today)) {
            SimpleDateFormat todayFormat = new SimpleDateFormat("'Today, 'MMMM d", Locale.getDefault());
            monthText.setText(todayFormat.format(today.getTime()));
        } else {
            SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
            monthText.setText(dayFormat.format(selectedDate.getTime()));
        }

        Calendar tempCal = (Calendar) currentWeekCalendar.clone();
        for (int i = 0; i < 7; i++) {
            int dayNumber = tempCal.get(Calendar.DAY_OF_MONTH);
            dayTextViews[i].setText(String.valueOf(dayNumber));

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
                float deltaX = e2.getX() - e1.getX();
                float deltaY = e2.getY() - e1.getY();
                
                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 100 && Math.abs(velocityX) > 100) {
                    if (deltaX < 0) {
                        currentWeekCalendar.add(Calendar.DAY_OF_MONTH, 7);
                    } else {
                        currentWeekCalendar.add(Calendar.DAY_OF_MONTH, -7);
                    }
                    updateCalendarUI();
                    return true;
                }
                return false;
            }
        });

        View mainView = findViewById(R.id.main);
        mainView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true; 
        });
        
        findViewById(R.id.scrollView).setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    private static class Task {
        String title, category, time, status;
        int year, month, day;
        Task(String title, String category, String time, String status, int year, int month, int day) {
            this.title = title;
            this.category = category;
            this.time = time;
            this.status = status;
            this.year = year;
            this.month = month;
            this.day = day;
        }
    }

    public static class TaskAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            AccountManager accountManager = new AccountManager(context);
            
            // Check if Schedule Notifications are enabled
            if (!accountManager.isScheduleEnabled()) {
                return;
            }

            String title = intent.getStringExtra("taskTitle");
            String category = intent.getStringExtra("taskCategory");
            
            String timestamp = new SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()).format(new Date());

            // Check if Global Data (History) is enabled
            if (accountManager.isGlobalDataEnabled()) {
                GlobalData.INSTANCE.addAlert("Task Reminder: " + title + " (" + category + ")", timestamp, "System");
            }

            // Check if System Alerts (Push Notifications) are enabled
            if (accountManager.isAlertsEnabled()) {
                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "task_reminder_channel")
                        .setSmallIcon(R.drawable.ic_notifications)
                        .setContentTitle("Task Reminder: " + title)
                        .setContentText("It's time for " + category)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);

                Intent alertIntent = new Intent(context, AlertsActivity.class);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, alertIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setContentIntent(pendingIntent);

                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                try {
                    notificationManager.notify((int) System.currentTimeMillis(), builder.build());
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
