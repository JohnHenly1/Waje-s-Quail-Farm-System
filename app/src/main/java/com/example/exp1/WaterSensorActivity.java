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

    // ── Sample history data (replace with real sensor data / Firestore) ───────
    // Each entry: { dayLabel, levelPercent }
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

        cameraHelper = new CameraHelper(this);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bindViews();
        setupBackButton();
        setupBottomNav();
        startLiveClock();
        displayWaterLevel(75); // default display — replace with real sensor value
        displayHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clockRunnable != null) handler.removeCallbacks(clockRunnable);
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

    // ── Display current water level ───────────────────────────────────────────
    /**
     * Call this with any value 0–100 to update the UI.
     * Plug in your real sensor read here.
     */
    public void displayWaterLevel(int percent) {
        percent = Math.max(0, Math.min(100, percent));

        waterPercentageText.setText(percent + "%");

        // Status label
        String status;
        if (percent >= 70) {
            status = "Optimal";
        } else if (percent >= 40) {
            status = "Normal";
        } else if (percent >= 20) {
            status = "Low — Consider refilling";
        } else {
            status = "Critical — Refill now!";
        }
        waterStatusText.setText(status);

        // Animate the fill bar height to match percent
        final int finalPercent = percent;
        waterFillView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        waterFillView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        View container = (View) waterFillView.getParent();
                        int containerH = container.getHeight();
                        int targetH = (int) (containerH * finalPercent / 100f);

                        android.view.ViewGroup.LayoutParams lp = waterFillView.getLayoutParams();
                        lp.height = targetH;
                        waterFillView.setLayoutParams(lp);
                    }
                }
        );
    }

    // ── Display history bars ──────────────────────────────────────────────────
    private void displayHistory() {
        // Wait for layout so we can measure parent width
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

        // Set day labels
        int[] dayLabels = {
                R.id.historyDay1, R.id.historyDay2, R.id.historyDay3,
                R.id.historyDay4, R.id.historyDay5
        };
        for (int i = 0; i < dayLabels.length && i < historyData.length; i++) {
            TextView dayTv = findViewById(dayLabels[i]);
            if (dayTv != null) dayTv.setText((String) historyData[i][0]);
        }
    }

    // ── Live clock updating lastUpdatedText ───────────────────────────────────
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

    // ── Back button ───────────────────────────────────────────────────────────
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

    // ── Bottom navigation ─────────────────────────────────────────────────────
    private void setupBottomNav() {
        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        LinearLayout cameraButton = findViewById(R.id.CameraButton);
        if (cameraButton != null) {
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }
    }
}