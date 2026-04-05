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
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import kotlin.Unit;

public class AnalyticsActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;
    private FirebaseFirestore db;

    private TextView revenueValue, feedCostValue, profitValue, recommendationText, performanceTitle, performanceDesc;
    private TextView weeklyMonthLabel, monthlyYearLabel, serverTimeLabel;
    private LineChart weeklyChart;
    private BarChart monthlyChart;
    private ConnectivityManager.NetworkCallback networkCallback;
    private int selectedWeeklyYear;
    private int selectedWeeklyMonth;
    private int selectedMonthlyYear;
    private Map<String, Integer> allData;
    private int totalEggs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        db = FirebaseFirestore.getInstance();
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
        weeklyMonthLabel = findViewById(R.id.weeklyMonthLabel);
        monthlyYearLabel = findViewById(R.id.monthlyYearLabel);
        serverTimeLabel = findViewById(R.id.serverTimeLabel);

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

        // Initialize selected periods
        Calendar cal = Calendar.getInstance();
        selectedWeeklyYear = cal.get(Calendar.YEAR);
        selectedWeeklyMonth = cal.get(Calendar.MONTH);
        selectedMonthlyYear = cal.get(Calendar.YEAR);

        // Start real-time time update
        startTimeUpdate();

        // Set up button listeners for navigation
        ImageButton previousWeekButton = findViewById(R.id.previousWeekButton);
        ImageButton nextWeekButton = findViewById(R.id.nextWeekButton);
        ImageButton previousMonthButton = findViewById(R.id.previousMonthButton);
        ImageButton nextMonthButton = findViewById(R.id.nextMonthButton);

        if (previousWeekButton != null) {
            previousWeekButton.setOnClickListener(v -> {
                selectedWeeklyMonth--;
                if (selectedWeeklyMonth < 0) {
                    selectedWeeklyMonth = 11;
                    selectedWeeklyYear--;
                }
                updateDashboard();
            });
        }

        if (nextWeekButton != null) {
            nextWeekButton.setOnClickListener(v -> {
                selectedWeeklyMonth++;
                if (selectedWeeklyMonth > 11) {
                    selectedWeeklyMonth = 0;
                    selectedWeeklyYear++;
                }
                updateDashboard();
            });
        }

        if (previousMonthButton != null) {
            previousMonthButton.setOnClickListener(v -> {
                selectedMonthlyYear--;
                updateDashboard();
            });
        }

        if (nextMonthButton != null) {
            nextMonthButton.setOnClickListener(v -> {
                selectedMonthlyYear++;
                updateDashboard();
            });
        }

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

    private void startTimeUpdate() {
        SimpleDateFormat timeFormat = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault());
        Handler handler = new Handler();
        Runnable updateTime = new Runnable() {
            @Override
            public void run() {
                Calendar cal = Calendar.getInstance();
                String time = timeFormat.format(cal.getTime());
                if (serverTimeLabel != null) serverTimeLabel.setText(time);
                handler.postDelayed(this, 60000);
            }
        };
        handler.post(updateTime);
    }

    private void fetchAnalyticsData() {
        // Query real egg collection data without date filter
        db.collection("farm_data").document("shared").collection("egg_collections")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (queryDocumentSnapshots.isEmpty()) {
                        allData = new TreeMap<>();
                        totalEggs = 0;
                        updateDashboard();
                    } else {
                        processRealData(queryDocumentSnapshots);
                    }
                })
                .addOnFailureListener(e -> {
                    allData = new TreeMap<>();
                    totalEggs = 0;
                    updateDashboard();
                });
    }

    private void processRealData(com.google.firebase.firestore.QuerySnapshot snapshots) {
        allData = new TreeMap<>();
        totalEggs = 0;

        for (QueryDocumentSnapshot doc : snapshots) {
            String date = doc.getString("date");
            int count = doc.getLong("total") != null ? doc.getLong("total").intValue() : 0;
            if (date != null) allData.put(date, count);
            totalEggs += count;
        }

        updateDashboard();
    }

    private void updateDashboard() {
        // Update Financials
        double revenue = totalEggs * 5.0;
        double cost = totalEggs * 1.8;
        double profit = revenue - cost;

        if (revenueValue != null) revenueValue.setText(String.format(Locale.getDefault(), "₱%.0f", revenue));
        if (feedCostValue != null) feedCostValue.setText(String.format(Locale.getDefault(), "₱%.0f", cost));
        if (profitValue != null) profitValue.setText(String.format(Locale.getDefault(), "₱%.0f", profit));

        // Aggregate for weekly
        Map<Integer, Integer> weekTotals = new HashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        for (Map.Entry<String, Integer> entry : allData.entrySet()) {
            try {
                Date date = sdf.parse(entry.getKey());
                Calendar entryCal = Calendar.getInstance();
                entryCal.setTime(date);
                int year = entryCal.get(Calendar.YEAR);
                int month = entryCal.get(Calendar.MONTH);
                int week = entryCal.get(Calendar.WEEK_OF_MONTH);
                if (year == selectedWeeklyYear && month == selectedWeeklyMonth) {
                    weekTotals.put(week, weekTotals.getOrDefault(week, 0) + entry.getValue());
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // Weekly chart
        List<Entry> lineEntries = new ArrayList<>();
        List<String> weeklyLabels = new ArrayList<>();
        for (int w = 1; w <= 4; w++) {
            lineEntries.add(new Entry(w - 1, weekTotals.getOrDefault(w, 0)));
            weeklyLabels.add("Week " + w);
        }
        LineDataSet lineSet = new LineDataSet(lineEntries, "Egg Count");
        lineSet.setColor(Color.parseColor("#466d1d"));
        lineSet.setCircleColor(Color.parseColor("#466d1d"));
        lineSet.setLineWidth(3f);
        lineSet.setDrawValues(true);
        weeklyChart.setData(new LineData(lineSet));
        weeklyChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(weeklyLabels));
        weeklyChart.getXAxis().setLabelRotationAngle(-45f);
        weeklyChart.getXAxis().setGranularity(1f);
        weeklyChart.getXAxis().setLabelCount(weeklyLabels.size());
        weeklyChart.animateX(800);
        weeklyChart.invalidate();

        // Aggregate for monthly
        Map<Integer, Integer> monthTotals = new HashMap<>();
        for (Map.Entry<String, Integer> entry : allData.entrySet()) {
            try {
                Date date = sdf.parse(entry.getKey());
                Calendar entryCal = Calendar.getInstance();
                entryCal.setTime(date);
                int year = entryCal.get(Calendar.YEAR);
                int month = entryCal.get(Calendar.MONTH);
                if (year == selectedMonthlyYear) {
                    monthTotals.put(month, monthTotals.getOrDefault(month, 0) + entry.getValue());
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // Monthly chart
        List<BarEntry> barEntries = new ArrayList<>();
        List<String> monthlyLabels = new ArrayList<>();
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        for (int m = 0; m < 12; m++) {
            barEntries.add(new BarEntry(m, monthTotals.getOrDefault(m, 0)));
            monthlyLabels.add(months[m]);
        }
        BarDataSet barSet = new BarDataSet(barEntries, "Egg Count");
        barSet.setColor(Color.parseColor("#466d1d"));
        barSet.setDrawValues(true);
        monthlyChart.setData(new BarData(barSet));
        monthlyChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(monthlyLabels));
        monthlyChart.getXAxis().setLabelRotationAngle(-45f);
        monthlyChart.getXAxis().setGranularity(1f);
        monthlyChart.getXAxis().setLabelCount(monthlyLabels.size());
        monthlyChart.animateY(800);
        monthlyChart.invalidate();

        // Set labels
        String monthYear = String.format(Locale.getDefault(), "%02d/%d", selectedWeeklyMonth + 1, selectedWeeklyYear);
        if (weeklyMonthLabel != null) weeklyMonthLabel.setText(monthYear);
        if (monthlyYearLabel != null) monthlyYearLabel.setText(String.valueOf(selectedMonthlyYear));

        // Performance & Smart Tips
        if (!allData.isEmpty()) {
            List<Integer> values = new ArrayList<>(allData.values());
            int latest = values.get(values.size() - 1);
            double average = totalEggs / (double) allData.size();
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
