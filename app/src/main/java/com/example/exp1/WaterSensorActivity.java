package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WaterSensorActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;

    // UI refs
    private TextView waterPercentageText;
    private TextView waterStatusText;
    private TextView lastUpdatedText;
    private View waterFillView;
    private View liveIndicator;

    // Live clock handler
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    // Firebase Realtime Database
    private DatabaseReference waterLevelRef;
    private ValueEventListener waterLevelListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_water_level_detection);

        cameraHelper = new CameraHelper(this, (uri, results) -> {
            // Count detections
            int gradeA = 0, gradeB = 0, gradeC = 0;
            for (DetectionResult r : results) {
                switch (r.getLabel()) {
                    case "egg_grade_a": gradeA++; break;
                    case "egg_grade_b": gradeB++; break;
                    case "egg_grade_c": gradeC++; break;
                }
            }
            int total = gradeA + gradeB + gradeC;
            Toast.makeText(this, "Detected " + total + " eggs!", Toast.LENGTH_SHORT).show();
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);

            View header = findViewById(R.id.header);
            if (header != null) {
                header.setPadding(header.getPaddingLeft(), systemBars.top, header.getPaddingRight(), header.getPaddingBottom());
            }
            return insets;
        });

        bindViews();
        setupBackButton();
        setupBottomNav();
        startLiveClock();

        // Connect to Firebase Realtime Database and listen for live water level updates
        listenForWaterLevelUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clockRunnable != null) handler.removeCallbacks(clockRunnable);
        if (waterLevelRef != null && waterLevelListener != null) {
            waterLevelRef.removeEventListener(waterLevelListener);
        }
    }

    // ── Bind all views ────────────────────────────────────────────────────────
    private void bindViews() {
        waterPercentageText = findViewById(R.id.waterPercentageText);
        waterStatusText     = findViewById(R.id.waterStatusText);
        lastUpdatedText     = findViewById(R.id.lastUpdatedText);
        waterFillView       = findViewById(R.id.waterFillView);
        liveIndicator       = findViewById(R.id.liveIndicator);
    }

    // ── Firebase Realtime Database ────────────────────────────────────────────
    private void listenForWaterLevelUpdates() {
        waterLevelRef = FirebaseDatabase.getInstance().getReference("water_level");

        waterLevelListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                Long percentageValue = snapshot.child("percentage").getValue(Long.class);
                String status = snapshot.child("status").getValue(String.class);

                int percent = percentageValue != null ? percentageValue.intValue() : 0;
                displayWaterLevel(percent, status);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(WaterSensorActivity.this,
                        "Unable to load water level: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        };

        waterLevelRef.addValueEventListener(waterLevelListener);
    }

    // ── Display current water level ───────────────────────────────────────────
    public void displayWaterLevel(int percent, String sensorStatus) {
        percent = Math.max(0, Math.min(100, percent));

        waterPercentageText.setText(percent + "%");

        // Prefer the status reported by the sensor; fall back to a computed label
        String status = (sensorStatus != null && !sensorStatus.trim().isEmpty())
                ? formatStatus(sensorStatus)
                : computeStatusLabel(percent);
        waterStatusText.setText(status);

        // Update fill view height
        final int finalPercent = percent;
        waterFillView.post(() -> {
            View container = (View) waterFillView.getParent();
            int containerH = container.getHeight();
            if (containerH > 0) {
                int targetH = (int) (containerH * finalPercent / 100f);
                android.view.ViewGroup.LayoutParams lp = waterFillView.getLayoutParams();
                lp.height = targetH;
                waterFillView.setLayoutParams(lp);
            }
        });
    }

    // ── Status label helpers ──────────────────────────────────────────────────
    private String computeStatusLabel(int percent) {
        if (percent >= 75) {
            return "Optimal Supply";
        } else if (percent >= 40) {
            return "Normal Supply";
        } else if (percent >= 20) {
            return "Low Level — Monitor";
        } else {
            return "Critical — Action Required";
        }
    }

    private String formatStatus(String rawStatus) {
        String lower = rawStatus.trim().toLowerCase(Locale.getDefault());
        switch (lower) {
            case "empty": return "Empty — Action Required";
            case "low": return "Low Level — Monitor";
            case "normal": return "Normal Supply";
            case "full": return "Optimal Supply";
            default:
                return lower.substring(0, 1).toUpperCase(Locale.getDefault()) + lower.substring(1);
        }
    }

    // ── Live clock ────────────────────────────────────────────────────────────
    private void startLiveClock() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault());
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                lastUpdatedText.setText(sdf.format(new Date()));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(clockRunnable);
    }

    private void setupBackButton() {
        ImageButton backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                Intent intent = new Intent(WaterSensorActivity.this, DashboardActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                finish();
            });
        }
    }

    private void setupBottomNav() {
        NavigationHelper.INSTANCE.setupBottomNavigation(this);
    }
}
