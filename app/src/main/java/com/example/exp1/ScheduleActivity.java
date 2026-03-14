package com.example.exp1;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.w3c.dom.Text;

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
            backBtn.setOnClickListener(v -> finish());
        }

        // Setup bottom navigation
        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        //calendar
        TextView monthText = findViewById(R.id.month);

        //Upd month and add year beside the month so it will be like March 2026
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());

        String currentMonth = monthFormat.format(calendar.getTime());
        monthText.setText(currentMonth);

       // array of days
        TextView[] days = {
                findViewById(R.id.day1),
                findViewById(R.id.day2),
                findViewById(R.id.day3),
                findViewById(R.id.day4),
                findViewById(R.id.day5),
                findViewById(R.id.day6),
                findViewById(R.id.day7)
        };


        // get today's date
        int today = calendar.get(Calendar.DAY_OF_MONTH);

        // fill the 7 days
        for(int i = 0; i < 7; i++){

            int dayNumber = calendar.get(Calendar.DAY_OF_MONTH);
            days[i].setText(String.valueOf(dayNumber));

            // highlight today
            if(dayNumber == today){
                days[i].setBackgroundResource(R.drawable.bg_dayselected);
            }

            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

    }
}
