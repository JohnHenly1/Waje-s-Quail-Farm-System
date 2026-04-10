package com.example.exp1;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
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

// Realtime Database — egg_collections
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

// Firestore — feed inventory value + centralised pricing
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import kotlin.Unit;

public class AnalyticsActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;

    // ── Realtime Database — egg_collections ───────────────────────────────────
    private DatabaseReference eggCollectionsRef;
    private ValueEventListener eggCollectionsListener;
    private Map<String, Integer> allData = new TreeMap<>();
    private int totalEggs;

    // ── Firestore — feed inventory value + centralised pricing ────────────────
    private FirebaseFirestore db;
    private ListenerRegistration feedInventoryListener;
    private ListenerRegistration pricingListener;
    private double totalInventoryValue = 0.0;
    /**
     * Price per egg read from farm_data/shared.pricePerEgg — same document
     * EggCountActivity writes to, ensuring a single source of truth.
     * Falls back to 3.50 until Firestore delivers the value.
     */
    private double pricePerEgg = 3.50;

    // ── Views ──────────────────────────────────────────────────────────────────
    private TextView revenueValue, inventoryValueTv, profitValue,
            recommendationText, performanceTitle, performanceDesc;
    private TextView weeklyMonthLabel, monthlyYearLabel, serverTimeLabel;
    private LineChart weeklyChart;
    private BarChart monthlyChart;
    private ConnectivityManager.NetworkCallback networkCallback;

    private int selectedWeeklyYear;
    private int selectedWeeklyMonth;
    private int selectedMonthlyYear;

    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(new Locale("en", "PH"));

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        eggCollectionsRef = FirebaseDatabase.getInstance().getReference("egg_collections");
        db = FirebaseFirestore.getInstance();

        cameraHelper = new CameraHelper(this, (uri, results) -> {
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

        // Bind views
        weeklyChart        = findViewById(R.id.weeklyChart);
        monthlyChart       = findViewById(R.id.monthlyChart);
        recommendationText = findViewById(R.id.recommendationText);
        performanceTitle   = findViewById(R.id.performanceTitle);
        performanceDesc    = findViewById(R.id.performanceDesc);
        revenueValue       = findViewById(R.id.revenueValue);
        inventoryValueTv   = findViewById(R.id.feedCostValue);   // same view ID; label changed to "Inventory Value" in layout
        profitValue        = findViewById(R.id.profitValue);
        weeklyMonthLabel   = findViewById(R.id.weeklyMonthLabel);
        monthlyYearLabel   = findViewById(R.id.monthlyYearLabel);
        serverTimeLabel    = findViewById(R.id.serverTimeLabel);

        // Internet check + attach listeners
        if (!NavigationHelper.INSTANCE.isInternetActuallyWorking(this)) {
            NavigationHelper.INSTANCE.showNoInternetOverlay(this);
        } else {
            NavigationHelper.INSTANCE.showGlobalLoading(this, "Analyzing Farm Yield...", () -> {
                attachRealtimeListener();
                attachFeedInventoryListener();
                attachPricingListener();
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

        // Init selected periods
        Calendar cal = Calendar.getInstance();
        selectedWeeklyYear  = cal.get(Calendar.YEAR);
        selectedWeeklyMonth = cal.get(Calendar.MONTH);
        selectedMonthlyYear = cal.get(Calendar.YEAR);

        startTimeUpdate();

        // Period navigation
        ImageButton previousWeekButton  = findViewById(R.id.previousWeekButton);
        ImageButton nextWeekButton      = findViewById(R.id.nextWeekButton);
        ImageButton previousMonthButton = findViewById(R.id.previousMonthButton);
        ImageButton nextMonthButton     = findViewById(R.id.nextMonthButton);

        if (previousWeekButton != null) previousWeekButton.setOnClickListener(v -> { selectedWeeklyMonth--; if (selectedWeeklyMonth < 0) { selectedWeeklyMonth = 11; selectedWeeklyYear--; } updateDashboard(); });
        if (nextWeekButton     != null) nextWeekButton.setOnClickListener(v -> { selectedWeeklyMonth++; if (selectedWeeklyMonth > 11) { selectedWeeklyMonth = 0; selectedWeeklyYear++; } updateDashboard(); });
        if (previousMonthButton != null) previousMonthButton.setOnClickListener(v -> { selectedMonthlyYear--; updateDashboard(); });
        if (nextMonthButton     != null) nextMonthButton.setOnClickListener(v -> { selectedMonthlyYear++; updateDashboard(); });

        // Camera button
        LinearLayout cameraButton = findViewById(R.id.CameraButton);
        if (cameraButton != null) {
            cameraButton.setClickable(true);
            cameraButton.setFocusable(true);
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }

        // Generate Report button
        Button generateReportBtn = findViewById(R.id.generateReportButton);
        if (generateReportBtn != null) {
            generateReportBtn.setOnClickListener(v -> showReportDialog());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Realtime Database — egg collections
    // ─────────────────────────────────────────────────────────────────────────

    private void attachRealtimeListener() {
        eggCollectionsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                allData = new TreeMap<>();
                totalEggs = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    String dateKey = child.getKey();
                    Long total = child.child("total").getValue(Long.class);
                    if (dateKey != null && total != null) {
                        allData.put(dateKey, total.intValue());
                        totalEggs += total.intValue();
                    }
                }
                runOnUiThread(() -> updateDashboard());
            }
            @Override public void onCancelled(DatabaseError error) {
                allData = new TreeMap<>();
                totalEggs = 0;
                runOnUiThread(() -> updateDashboard());
            }
        };
        eggCollectionsRef.addValueEventListener(eggCollectionsListener);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firestore — live feed inventory value
    //  farm_data/shared/feed collection (same as FeedInventoryActivity)
    //  totalInventoryValue = Σ (quantity × unitPrice)
    // ─────────────────────────────────────────────────────────────────────────

    private void attachFeedInventoryListener() {
        feedInventoryListener = db.collection("farm_data")
                .document("shared")
                .collection("feed")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;
                    double sum = 0.0;
                    for (QueryDocumentSnapshot doc : snapshots) {
                        Long qty = doc.getLong("quantity");
                        Double price = doc.getDouble("unitPrice");
                        if (qty != null && price != null) sum += qty * price;
                    }
                    totalInventoryValue = sum;
                    runOnUiThread(() -> updateDashboard());
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Firestore — centralised egg pricing from farm_data/shared
    //  Reads pricePerEgg written by EggCountActivity's showPriceDialog()
    //  so both modules always use the same price without hardcoding.
    // ─────────────────────────────────────────────────────────────────────────

    private void attachPricingListener() {
        pricingListener = db.collection("farm_data")
                .document("shared")
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null || snapshot == null || !snapshot.exists()) return;
                    Double p = snapshot.getDouble("pricePerEgg");
                    if (p != null) pricePerEgg = p;
                    runOnUiThread(() -> updateDashboard());
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internet sensor
    // ─────────────────────────────────────────────────────────────────────────

    private void startLiveInternetSensor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    View ll = findViewById(R.id.loadingLayout);
                    if (ll != null && ll.getVisibility() == View.VISIBLE) {
                        View nn = findViewById(R.id.noInternetSection);
                        if (nn != null && nn.getVisibility() == View.VISIBLE) {
                            ll.setVisibility(View.GONE);
                            recreate();
                        }
                    }
                });
            }
            @Override public void onLost(Network network) {
                runOnUiThread(() -> NavigationHelper.INSTANCE.showNoInternetOverlay(AnalyticsActivity.this));
            }
        };
        cm.registerNetworkCallback(request, networkCallback);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (networkCallback != null)
            ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                    .unregisterNetworkCallback(networkCallback);
        if (eggCollectionsListener != null)
            eggCollectionsRef.removeEventListener(eggCollectionsListener);
        if (feedInventoryListener != null) feedInventoryListener.remove();
        if (pricingListener != null)       pricingListener.remove();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (eggCollectionsListener != null && NavigationHelper.INSTANCE.isInternetActuallyWorking(this)) {
            eggCollectionsRef.removeEventListener(eggCollectionsListener);
            eggCollectionsRef.addValueEventListener(eggCollectionsListener);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Clock
    // ─────────────────────────────────────────────────────────────────────────

    private void startTimeUpdate() {
        SimpleDateFormat timeFormat = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault());
        Handler handler = new Handler();
        Runnable updateTime = new Runnable() {
            @Override public void run() {
                if (serverTimeLabel != null)
                    serverTimeLabel.setText(timeFormat.format(Calendar.getInstance().getTime()));
                handler.postDelayed(this, 60000);
            }
        };
        handler.post(updateTime);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dashboard rendering
    //  Revenue          = totalEggs × pricePerEgg  (from Firestore)
    //  Inventory Value  = live sum from FeedInventory (Firestore)
    //  Net Profit       = Revenue − Inventory Value
    // ─────────────────────────────────────────────────────────────────────────

    private void updateDashboard() {
        double revenue = totalEggs * pricePerEgg;
        double profit  = revenue - totalInventoryValue;

        if (revenueValue     != null) revenueValue.setText(String.format(Locale.getDefault(), "₱%.2f", revenue));
        if (inventoryValueTv != null) inventoryValueTv.setText(currencyFormat.format(totalInventoryValue));
        if (profitValue      != null) {
            profitValue.setText(String.format(Locale.getDefault(), "₱%.2f", profit));
            profitValue.setTextColor(profit < 0 ? Color.parseColor("#FFCDD2") : Color.WHITE);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

        // Weekly chart
        Map<Integer, Integer> weekTotals = new HashMap<>();
        for (Map.Entry<String, Integer> entry : allData.entrySet()) {
            try {
                Date date = sdf.parse(entry.getKey());
                Calendar ec = Calendar.getInstance(); ec.setTime(date);
                int yr = ec.get(Calendar.YEAR), mo = ec.get(Calendar.MONTH), wk = ec.get(Calendar.WEEK_OF_MONTH);
                if (yr == selectedWeeklyYear && mo == selectedWeeklyMonth)
                    weekTotals.put(wk, weekTotals.getOrDefault(wk, 0) + entry.getValue());
            } catch (Exception ignored) {}
        }
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

        // Monthly chart
        Map<Integer, Integer> monthTotals = new HashMap<>();
        for (Map.Entry<String, Integer> entry : allData.entrySet()) {
            try {
                Date date = sdf.parse(entry.getKey());
                Calendar ec = Calendar.getInstance(); ec.setTime(date);
                int yr = ec.get(Calendar.YEAR), mo = ec.get(Calendar.MONTH);
                if (yr == selectedMonthlyYear)
                    monthTotals.put(mo, monthTotals.getOrDefault(mo, 0) + entry.getValue());
            } catch (Exception ignored) {}
        }
        List<BarEntry> barEntries = new ArrayList<>();
        List<String> monthlyLabels = new ArrayList<>();
        String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
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

        // Period labels
        String monthYear = String.format(Locale.getDefault(), "%02d/%d",
                selectedWeeklyMonth + 1, selectedWeeklyYear);
        if (weeklyMonthLabel != null) weeklyMonthLabel.setText(monthYear);
        if (monthlyYearLabel  != null) monthlyYearLabel.setText(String.valueOf(selectedMonthlyYear));

        // Performance & smart tips
        if (!allData.isEmpty()) {
            List<Integer> values = new ArrayList<>(allData.values());
            int    latest  = values.get(values.size() - 1);
            double average = totalEggs / (double) allData.size();
            calculatePerformance(latest, average);
            generateSmartTip(latest, values.size() > 1 ? values.get(values.size() - 2) : latest, profit);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Performance & smart tip logic
    // ─────────────────────────────────────────────────────────────────────────

    private void calculatePerformance(int latest, double average) {
        if (average == 0) return;
        double d = ((latest - average) / average) * 100;
        if (performanceTitle != null)
            performanceTitle.setText(d >= 10 ? "Excellent Performance!" : d >= -5 ? "Stable Production" : "Production Alert");
        if (performanceDesc != null)
            performanceDesc.setText(String.format(Locale.getDefault(),
                    "You're %.1f%% %s average production this month",
                    Math.abs(d), d >= 0 ? "above" : "below"));
    }

    private void generateSmartTip(int latest, int prev, double profit) {
        String tip;
        if (latest < prev * 0.8)
            tip = "Urgent: Production dropped significantly (>20%). Check for diseases or extreme environmental stress immediately.";
        else if (latest < prev * 0.9)
            tip = "Critical Tip: Production dropped by 10%. Check for sudden temperature changes or stressors in the cages.";
        else if (latest > prev * 1.2)
            tip = "Outstanding! Yield is up by 20%. Analyze if recent changes in feed or lighting contributed to this boost.";
        else if (latest > prev * 1.1)
            tip = "Great Job! Yield is up. Ensure consistent feed supply to maintain this peak production rate.";
        else if (profit < 0)
            tip = "Financial Alert: Inventory value is currently exceeding egg revenue. Review feed efficiency and waste management.";
        else if (latest > 0 && latest < 10)
            tip = "Startup Tip: Low volume detected. Ensure your quails have reached laying age (usually 6-7 weeks).";
        else {
            String[] stableTips = {
                    "Maintenance Tip: Production is stable. Remember to maintain 14-16 hours of daily light exposure for quails.",
                    "Efficiency Tip: Stable output. This is a good time to audit your bio-security measures.",
                    "Quality Tip: Ensure you are collecting eggs twice daily to prevent breakage during stable production.",
                    "Nutrition Tip: Maintain high-protein layer crumbles to sustain this steady production rate."
            };
            tip = stableTips[new Random().nextInt(stableTips.length)];
        }
        if (recommendationText != null) recommendationText.setText(tip);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Generate Report dialog
    // ─────────────────────────────────────────────────────────────────────────

    private void showReportDialog() {
        double revenue = totalEggs * pricePerEgg;
        double profit  = revenue - totalInventoryValue;
        String tip       = (recommendationText != null) ? recommendationText.getText().toString() : "N/A";
        String perfTitle = (performanceTitle   != null) ? performanceTitle.getText().toString()   : "N/A";
        String perfDesc  = (performanceDesc    != null) ? performanceDesc.getText().toString()    : "N/A";
        String ts        = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());

        String summary =
                "📋  FARM ANALYTICS REPORT\n" +
                        "Generated: " + ts + "\n\n" +
                        "🥚  EGG PRODUCTION\n" +
                        "   Total Eggs Collected : " + totalEggs + " eggs\n\n" +
                        "💰  REVENUE\n" +
                        "   Price per Egg        : " + currencyFormat.format(pricePerEgg) + "\n" +
                        "   Egg Sales Revenue    : " + currencyFormat.format(revenue) + "\n\n" +
                        "📦  INVENTORY VALUE\n" +
                        "   Total Inventory Value: " + currencyFormat.format(totalInventoryValue) + "\n\n" +
                        "📈  NET PROFIT\n" +
                        "   Net Profit           : " + currencyFormat.format(profit) +
                        (profit < 0 ? "  ⚠ Loss" : "  ✔ Profit") + "\n\n" +
                        "🏆  PERFORMANCE\n" +
                        "   " + perfTitle + "\n" +
                        "   " + perfDesc + "\n\n" +
                        "💡  AI RECOMMENDATION\n" +
                        "   " + tip;

        new AlertDialog.Builder(this)
                .setTitle("Farm Analytics Report")
                .setMessage(summary)
                .setPositiveButton("Export as PDF", (dialog, which) -> generatePdfReport())
                .setNegativeButton("Close", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PDF export — uses Android built-in PdfDocument (no extra dependency)
    // ─────────────────────────────────────────────────────────────────────────

    private void generatePdfReport() {
        try {
            double revenue = totalEggs * pricePerEgg;
            double profit  = revenue - totalInventoryValue;
            String ts        = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());
            String tip       = (recommendationText != null) ? recommendationText.getText().toString() : "N/A";
            String perfTitle = (performanceTitle   != null) ? performanceTitle.getText().toString()   : "N/A";
            String perfDesc  = (performanceDesc    != null) ? performanceDesc.getText().toString()    : "N/A";

            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            // Background
            Paint bg = new Paint(); bg.setColor(Color.WHITE);
            canvas.drawRect(0, 0, 595, 842, bg);

            // Header bar
            Paint hdr = new Paint(); hdr.setColor(Color.parseColor("#466d1d"));
            canvas.drawRect(0, 0, 595, 75, hdr);
            Paint ht = new Paint(); ht.setColor(Color.WHITE); ht.setTextSize(20f); ht.setFakeBoldText(true);
            canvas.drawText("Waje's Quail Farm — Analytics Report", 18, 34, ht);
            Paint hs = new Paint(); hs.setColor(Color.WHITE); hs.setTextSize(11f);
            canvas.drawText("Generated: " + ts, 18, 56, hs);

            int y = 100;
            Paint divider = new Paint(); divider.setColor(Color.LTGRAY); divider.setStrokeWidth(1f);

            // Financial Summary
            drawSectionTitle(canvas, "Financial Summary", y); y += 28;
            drawRow(canvas, "Price per Egg",        currencyFormat.format(pricePerEgg),        y, Color.parseColor("#555555")); y += 24;
            drawRow(canvas, "Egg Sales Revenue",    currencyFormat.format(revenue),            y, Color.parseColor("#2E7D32")); y += 24;
            drawRow(canvas, "Total Inventory Value",currencyFormat.format(totalInventoryValue),y, Color.parseColor("#E65100")); y += 24;
            drawRow(canvas, "Net Profit",           currencyFormat.format(profit),             y,
                    profit < 0 ? Color.parseColor("#C62828") : Color.parseColor("#1565C0")); y += 14;
            canvas.drawLine(20, y, 575, y, divider); y += 20;

            // Egg Production
            drawSectionTitle(canvas, "Egg Production", y); y += 28;
            drawRow(canvas, "Total Eggs Collected", totalEggs + " eggs", y, Color.BLACK); y += 14;
            canvas.drawLine(20, y, 575, y, divider); y += 20;

            // Performance
            drawSectionTitle(canvas, "Performance", y); y += 28;
            Paint bold = new Paint(); bold.setTextSize(13f); bold.setFakeBoldText(true); bold.setColor(Color.parseColor("#333333"));
            canvas.drawText(perfTitle, 24, y, bold); y += 18;
            Paint body = new Paint(); body.setTextSize(12f); body.setColor(Color.parseColor("#555555"));
            y = drawWrappedText(canvas, perfDesc, 24, y, 550, body); y += 6;
            canvas.drawLine(20, y, 575, y, divider); y += 20;

            // AI Recommendation
            drawSectionTitle(canvas, "AI Recommendation", y); y += 28;
            y = drawWrappedText(canvas, tip, 24, y, 550, body);
            canvas.drawLine(20, y + 6, 575, y + 6, divider);

            // Footer
            Paint footer = new Paint(); footer.setTextSize(10f); footer.setColor(Color.GRAY);
            canvas.drawText("Waje's Quail Farm Management System  •  Auto-generated report", 18, 820, footer);

            document.finishPage(page);

            // Save to app's Documents/WajeReports/
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WajeReports");
            if (!dir.exists()) dir.mkdirs();
            String filename = "FarmReport_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".pdf";
            File pdfFile = new File(dir, filename);
            document.writeTo(new FileOutputStream(pdfFile));
            document.close();

            // Open via FileProvider
            android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open PDF Report"));
            Toast.makeText(this, "✅ PDF saved: " + filename, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "PDF generation failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── PDF drawing helpers ───────────────────────────────────────────────────

    private void drawSectionTitle(Canvas canvas, String title, int y) {
        Paint p = new Paint();
        p.setTextSize(15f); p.setFakeBoldText(true); p.setColor(Color.parseColor("#466d1d"));
        canvas.drawText(title, 20, y, p);
    }

    private void drawRow(Canvas canvas, String label, String value, int y, int valueColor) {
        Paint lp = new Paint(); lp.setTextSize(13f); lp.setColor(Color.parseColor("#555555"));
        Paint vp = new Paint(); vp.setTextSize(13f); vp.setFakeBoldText(true); vp.setColor(valueColor);
        canvas.drawText(label, 24, y, lp);
        canvas.drawText(value, 390, y, vp);
    }

    /** Wraps text at maxWidth, returns updated y position. */
    private int drawWrappedText(Canvas canvas, String text, int x, int y, int maxWidth, Paint paint) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(test) > (maxWidth - x)) {
                canvas.drawText(line.toString(), x, y, paint);
                y += (int)(paint.getTextSize() * 1.4f);
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            canvas.drawText(line.toString(), x, y, paint);
            y += (int)(paint.getTextSize() * 1.4f);
        }
        return y;
    }
}