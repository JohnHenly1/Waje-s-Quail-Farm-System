package com.example.exp1;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import kotlin.Unit;

public class AnalyticsActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FirebaseFirestore db;

    private TextView revenueValue, feedCostValue, profitValue, recommendationText, performanceTitle, performanceDesc;
    private LineChart weeklyChart;
    private BarChart monthlyChart;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        db = FirebaseFirestore.getInstance();
        cameraHelper = new CameraHelper(this);

        setContentView(R.layout.activity_analytics);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize Views
        weeklyChart = findViewById(R.id.weeklyChart);
        monthlyChart = findViewById(R.id.monthlyChart);
        recommendationText = findViewById(R.id.recommendationText);
        performanceTitle = findViewById(R.id.performanceTitle);
        performanceDesc = findViewById(R.id.performanceDesc);
        revenueValue = findViewById(R.id.revenueValue);
        feedCostValue = findViewById(R.id.feedCostValue);
        profitValue = findViewById(R.id.profitValue);

        // ── LIVE INTERNET SENSOR ──
        // Check once at start, then register listener for real-time changes
        if (!NavigationHelper.INSTANCE.isInternetActuallyWorking(this)) {
            NavigationHelper.INSTANCE.showNoInternetOverlay(this);
        } else {
            // Loading Sequence from NavigationHelper
            NavigationHelper.INSTANCE.showGlobalLoading(this, "Analyzing Farm Yield...", () -> {
                fetchAnalyticsData();
                return Unit.INSTANCE;
            });
        }
        startLiveInternetSensor();

        // Back button
        ImageButton backButton = findViewById(R.id.imageButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                Intent intent = new Intent(AnalyticsActivity.this, DashboardActivity.class);
                intent.putExtra("username", getIntent().getStringExtra("username"));
                intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                finish();
            });
        }

        NavigationHelper.INSTANCE.setupBottomNavigation(this);
        NavigationHelper.INSTANCE.setupNotificationButton(this);

        // Camera button
        LinearLayout cameraButton = findViewById(R.id.CameraButton);
        if (cameraButton != null) {
            cameraButton.setClickable(true);
            cameraButton.setFocusable(true);
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }
    }

    private void startLiveInternetSensor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    View loadingLayout = findViewById(R.id.loadingLayout);
                    if (loadingLayout != null && loadingLayout.getVisibility() == View.VISIBLE) {
                        View noNet = findViewById(R.id.noInternetSection);
                        if (noNet != null && noNet.getVisibility() == View.VISIBLE) {
                            // Internet came back, hide error and refresh the activity
                            loadingLayout.setVisibility(View.GONE);
                            recreate(); 
                        }
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> NavigationHelper.INSTANCE.showNoInternetOverlay(AnalyticsActivity.this));
            }
        };
        cm.registerNetworkCallback(request, networkCallback);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(networkCallback);
        }
    }

    private void fetchAnalyticsData() {
        // Query real egg collection data
        db.collection("farm_data").document("shared").collection("egg_collections")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(30)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        generateSimulation();
                    } else {
                        processRealData(queryDocumentSnapshots);
                    }
                })
                .addOnFailureListener(e -> generateSimulation());
    }

    private void processRealData(com.google.firebase.firestore.QuerySnapshot snapshots) {
        Map<String, Integer> dailyTotals = new TreeMap<>();
        int totalEggs = 0;

        for (QueryDocumentSnapshot doc : snapshots) {
            String date = doc.getString("date");
            int count = doc.getLong("total") != null ? doc.getLong("total").intValue() : 0;
            if (date != null) dailyTotals.put(date, count);
            totalEggs += count;
        }

        updateDashboard(dailyTotals, totalEggs);
    }

    private void generateSimulation() {
        Map<String, Integer> simData = new TreeMap<>();
        simData.put("Mon", 150); simData.put("Tue", 165);
        simData.put("Wed", 140); simData.put("Thu", 180);
        simData.put("Fri", 175); simData.put("Sat", 190);
        simData.put("Sun", 205);
        updateDashboard(simData, 1205);
    }

    private void updateDashboard(Map<String, Integer> data, int total) {
        // 1. Update Financials (Simulated P5 revenue, P1.8 cost)
        double revenue = total * 5.0;
        double cost = total * 1.8;
        double profit = revenue - cost;

        if (revenueValue != null) revenueValue.setText(String.format(Locale.getDefault(), "₱%.0f", revenue));
        if (feedCostValue != null) feedCostValue.setText(String.format(Locale.getDefault(), "₱%.0f", cost));
        if (profitValue != null) profitValue.setText(String.format(Locale.getDefault(), "₱%.0f", profit));

        // 2. Line Chart Logic
        List<Entry> lineEntries = new ArrayList<>();
        int i = 0;
        double sum = 0;
        for (Integer val : data.values()) {
            lineEntries.add(new Entry(i++, val));
            sum += val;
        }
        double average = sum / (data.size() > 0 ? data.size() : 1);

        LineDataSet lineSet = new LineDataSet(lineEntries, "Egg Count");
        lineSet.setColor(Color.parseColor("#466d1d"));
        lineSet.setCircleColor(Color.parseColor("#466d1d"));
        lineSet.setLineWidth(3f);
        weeklyChart.setData(new LineData(lineSet));
        weeklyChart.animateX(800);
        weeklyChart.invalidate();

        // 3. Performance & Smart Tips
        if (!data.isEmpty()) {
            List<Integer> values = new ArrayList<>(data.values());
            int latest = values.get(values.size() - 1);
            calculatePerformance(latest, average);
            generateSmartTip(latest, values.size() > 1 ? values.get(values.size() - 2) : latest);
        }
    }

    private void calculatePerformance(int latest, double average) {
        if (average == 0) return;
        double diffPercent = ((latest - average) / average) * 100;

        if (performanceTitle != null) {
            if (diffPercent >= 10) performanceTitle.setText("Excellent Performance!");
            else if (diffPercent >= -5) performanceTitle.setText("Stable Production");
            else performanceTitle.setText("Production Alert");
        }

        if (performanceDesc != null) {
            String direction = diffPercent >= 0 ? "above" : "below";
            performanceDesc.setText(String.format(Locale.getDefault(), 
                "You're %.1f%% %s average production this month", Math.abs(diffPercent), direction));
        }
    }

    private void generateSmartTip(int latest, int prev) {
        String tip;
        if (latest < prev * 0.9) {
            tip = "Critical Tip: Production dropped by 10%. Check for sudden temperature changes or stressors in the cages.";
        } else if (latest > prev * 1.1) {
            tip = "Great Job! Yield is up. Ensure consistent feed supply to maintain this peak production rate.";
        } else {
            tip = "Maintenance Tip: Production is stable. Remember to maintain 14-16 hours of daily light exposure for quails.";
        }
        if (recommendationText != null) recommendationText.setText(tip);
    }
}
