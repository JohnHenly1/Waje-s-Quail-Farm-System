package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
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

        // Setup bottom navigation
        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        // Calendar setup
        TextView monthText = findViewById(R.id.month);
        Calendar today = Calendar.getInstance();
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        monthText.setText(monthFormat.format(today.getTime()));

        // Array of TextViews for day numbers (aligned with Monday-Sunday labels in layout)
        TextView[] dayTextViews = {
                findViewById(R.id.day1),
                findViewById(R.id.day2),
                findViewById(R.id.day3),
                findViewById(R.id.day4),
                findViewById(R.id.day5),
                findViewById(R.id.day6),
                findViewById(R.id.day7)
        };

        // Align calendar to the Monday of the current week
        Calendar calendar = (Calendar) today.clone();
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        // Calculate days to subtract to reach Monday (Monday is 2, Sunday is 1 in Calendar)
        int daysToSubtract = (dayOfWeek - Calendar.MONDAY + 7) % 7;
        calendar.add(Calendar.DAY_OF_MONTH, -daysToSubtract);

        // Fill the 7 days of the week and highlight the current day
        for (int i = 0; i < 7; i++) {
            int dayNumber = calendar.get(Calendar.DAY_OF_MONTH);
            dayTextViews[i].setText(String.valueOf(dayNumber));

            // Get the parent LinearLayout of the day TextView to apply the background
            View dayContainer = (View) dayTextViews[i].getParent();

            // Highlight the container if this calendar day is actually today
            if (isSameDay(calendar, today)) {
                dayContainer.setBackgroundResource(R.drawable.bg_dayselected);
            } else {
                dayContainer.setBackgroundResource(R.drawable.bg_day);
            }

            // Move to the next day
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    /**
     * Checks if two Calendar instances represent the same day.
     */
    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
}
