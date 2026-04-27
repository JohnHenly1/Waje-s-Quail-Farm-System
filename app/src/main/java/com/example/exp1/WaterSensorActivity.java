package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
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

    // History bar views
    private View[] historyBars;
    private TextView[] historyPcts;

    // Live clock handler
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;
    
    // Simulation logic fields
    private Runnable simulationRunnable;
    private int simulatedLevel = 75;

    // ── Sample history data need to be replaced by a sensor

    private final Object[][] historyData = {
            {"Mon", 80},
            {"Tue", 75},
            {"Wed", 60},
            {"Thu", 85},
            {"Fri", 75}
    };

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
        setupSimulationButton(); // Initialize the new refresh button
        startLiveClock();
        
        // Start simulation to show water level changes for the panel
        startSimulation();
        
        displayHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clockRunnable != null) handler.removeCallbacks(clockRunnable);
        if (simulationRunnable != null) handler.removeCallbacks(simulationRunnable);
    }

    // ── Bind all views ────────────────────────────────────────────────────────
    private void bindViews() {
        waterPercentageText = findViewById(R.id.waterPercentageText);
        waterStatusText     = findViewById(R.id.waterStatusText);
        lastUpdatedText     = findViewById(R.id.lastUpdatedText);
        waterFillView       = findViewById(R.id.waterFillView);
        liveIndicator       = findViewById(R.id.liveIndicator);

        historyBars = new View[]{
                findViewById(R.id.historyBar1),
                findViewById(R.id.historyBar2),
                findViewById(R.id.historyBar3),
                findViewById(R.id.historyBar4),
                findViewById(R.id.historyBar5)
        };

        historyPcts = new TextView[]{
                findViewById(R.id.historyPct1),
                findViewById(R.id.historyPct2),
                findViewById(R.id.historyPct3),
                findViewById(R.id.historyPct4),
                findViewById(R.id.historyPct5)
        };
    }

    // ── Simulation Setup ──────────────────────────────────────────────────────
    private void setupSimulationButton() {
        ImageButton refreshBtn = findViewById(R.id.refreshSimulationBtn);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                simulatedLevel = 100; // Reset to full for the demo
                displayWaterLevel(simulatedLevel);
                Toast.makeText(this, "Simulation Restarted: Tank Full", Toast.LENGTH_SHORT).show();
                startSimulation();
            });
        }
    }

    private void startSimulation() {
        // Prevent multiple simultaneous simulation loops
        if (simulationRunnable != null) {
            handler.removeCallbacks(simulationRunnable);
        }

        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                // Simulate water usage by dropping level slightly (0-2%)
                simulatedLevel -= (int)(Math.random() * 3);
                
                // If water is critically low, simulate a refill
                if (simulatedLevel < 10) {
                    simulatedLevel = 98;
                    Toast.makeText(WaterSensorActivity.this, "Simulation: Tank Refilled", Toast.LENGTH_SHORT).show();
                }
                
                displayWaterLevel(simulatedLevel);
                
                // Update every 3 seconds to show movement to the panel
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(simulationRunnable);
    }

    // ── Display current water level ───────────────────────────────────────────
    public void displayWaterLevel(int percent) {
        percent = Math.max(0, Math.min(100, percent));

        waterPercentageText.setText(percent + "%");

        // Status label logic
        String status;
        if (percent >= 75) {
            status = "Optimal Supply";
        } else if (percent >= 40) {
            status = "Normal Supply";
        } else if (percent >= 20) {
            status = "Low Level — Monitor";
        } else {
            status = "Critical — Action Required";
        }
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

    // ── Display history bars ──────────────────────────────────────────────────
    private void displayHistory() {
        if (historyBars.length > 0 && historyBars[0] != null) {
            historyBars[0].getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            historyBars[0].getViewTreeObserver().removeOnGlobalLayoutListener(this);

                            for (int i = 0; i < historyBars.length && i < historyData.length; i++) {
                                int pct = (int) historyData[i][1];
                                View bar = historyBars[i];
                                View container = (View) bar.getParent();
                                int containerW = container.getWidth();
                                int targetW = (int) (containerW * pct / 100f);

                                android.view.ViewGroup.LayoutParams lp = bar.getLayoutParams();
                                lp.width = targetW;
                                bar.setLayoutParams(lp);

                                historyPcts[i].setText(pct + "%");
                            }
                        }
                    }
            );
        }

        int[] dayLabels = {
                R.id.historyDay1, R.id.historyDay2, R.id.historyDay3,
                R.id.historyDay4, R.id.historyDay5
        };
        for (int i = 0; i < dayLabels.length && i < historyData.length; i++) {
            TextView dayTv = findViewById(dayLabels[i]);
            if (dayTv != null) dayTv.setText((String) historyData[i][0]);
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
        LinearLayout cameraButton = findViewById(R.id.CameraButton);
        if (cameraButton != null) {
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }
    }
}
