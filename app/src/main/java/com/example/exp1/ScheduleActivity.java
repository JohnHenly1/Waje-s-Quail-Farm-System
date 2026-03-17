package com.example.exp1;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class ScheduleActivity extends AppCompatActivity {

    private Calendar currentWeekCalendar;
    private TextView monthText;
    private TextView[] dayTextViews;
    private TextView[] dayLabelViews;
    private View[] dayContainers;
    private Calendar today;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_schedule);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        today = Calendar.getInstance();
        currentWeekCalendar = (Calendar) today.clone();
        
        // Align to Monday of the current week
        alignCalendarToMonday(currentWeekCalendar);

        monthText = findViewById(R.id.month);
        
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

        ImageButton backBtn = findViewById(R.id.imageButton);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                Intent intent = new Intent(ScheduleActivity.this, DashboardActivity.class);
                intent.putExtra("username", getIntent().getStringExtra("username"));
                intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                finish();
            });
        }

        TextView seeCalendarBtn = findViewById(R.id.seeCalendarBtn);
        if (seeCalendarBtn != null) {
            seeCalendarBtn.setOnClickListener(v -> showFullCalendar());
        }

        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        updateCalendarUI();
        setupSwipeGestures();
    }

    private void alignCalendarToMonday(Calendar cal) {
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        int daysToSubtract = (dayOfWeek - Calendar.MONDAY + 7) % 7;
        cal.add(Calendar.DAY_OF_MONTH, -daysToSubtract);
    }

    private void showFullCalendar() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    currentWeekCalendar.set(year, month, dayOfMonth);
                    alignCalendarToMonday(currentWeekCalendar);
                    updateCalendarUI();
                },
                currentWeekCalendar.get(Calendar.YEAR),
                currentWeekCalendar.get(Calendar.MONTH),
                currentWeekCalendar.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void updateCalendarUI() {
        // Update header text to show current month/context
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        if (isSameWeek(currentWeekCalendar, today)) {
            SimpleDateFormat todayFormat = new SimpleDateFormat("'Today, 'MMMM d", Locale.getDefault());
            monthText.setText(todayFormat.format(today.getTime()));
        } else {
            monthText.setText(monthFormat.format(currentWeekCalendar.getTime()));
        }

        Calendar tempCal = (Calendar) currentWeekCalendar.clone();
        for (int i = 0; i < 7; i++) {
            int dayNumber = tempCal.get(Calendar.DAY_OF_MONTH);
            dayTextViews[i].setText(String.valueOf(dayNumber));

            if (isSameDay(tempCal, today)) {
                // Today highlighted with white text
                dayContainers[i].setBackgroundResource(R.drawable.bg_dayselected);
                dayTextViews[i].setTextColor(Color.WHITE);
                dayLabelViews[i].setTextColor(Color.parseColor("#E5E7EB"));
            } else {
                // Other days with default dark text
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
                        // Swipe left -> Next week
                        currentWeekCalendar.add(Calendar.DAY_OF_MONTH, 7);
                    } else {
                        // Swipe right -> Previous week
                        currentWeekCalendar.add(Calendar.DAY_OF_MONTH, -7);
                    }
                    updateCalendarUI();
                    return true;
                }
                return false;
            }
        });

        // Apply swipe listener to the whole main view to make it intuitive
        View mainView = findViewById(R.id.main);
        mainView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true; 
        });
        
        // Also apply to the scroll view content so it doesn't swallow horizontal swipes
        findViewById(R.id.scrollView).setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false; // Return false so vertical scroll still works
        });
    }

    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    private boolean isSameWeek(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.WEEK_OF_YEAR) == cal2.get(Calendar.WEEK_OF_YEAR);
    }
}
