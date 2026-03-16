package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class ProfileActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTimeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);
        
        drawerLayout = findViewById(R.id.drawerLayout);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Setup Menu Button to open Drawer
        ImageButton menuButton = findViewById(R.id.imageButton);
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }

        // Setup Sign Out
        Button signOutButton = findViewById(R.id.signOutButton);
        if (signOutButton != null) {
            signOutButton.setOnClickListener(v -> {
                Intent intent = new Intent(ProfileActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }

        // Setup Server Time Update
        setupServerTime();
        
        // Setup bottom navigation
        NavigationHelper.INSTANCE.setupBottomNavigation(this);
        // Setup notification button
        NavigationHelper.INSTANCE.setupNotificationButton(this);
    }

    private void setupServerTime() {
        TextView serverTimeText = findViewById(R.id.serverTimeText);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault());
        
        updateTimeRunnable = new Runnable() {
            @Override
            public void run() {
                String currentTime = sdf.format(Calendar.getInstance().getTime());
                if (serverTimeText != null) {
                    serverTimeText.setText(currentTime);
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(updateTimeRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && updateTimeRunnable != null) {
            handler.removeCallbacks(updateTimeRunnable);
        }
    }
}
