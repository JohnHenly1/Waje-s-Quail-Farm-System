package com.example.exp1;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

public class ScheduleActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);setContentView(R.layout.activity_schedule); // This links the UI


        ImageButton backBtn = findViewById(R.id.imageButton);
        backBtn.setOnClickListener(v -> finish());
    }
}


