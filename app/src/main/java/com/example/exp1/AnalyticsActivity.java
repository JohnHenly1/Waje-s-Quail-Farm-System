package com.example.exp1;

import android.app.DatePickerDialog;
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
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;

// Realtime Database — egg_collections
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import kotlin.Unit;

public class AnalyticsActivity extends AppCompatActivity {

    private DatabaseReference eggCollectionsRef;
    private ValueEventListener eggCollectionsListener;
    private Map<String, DailyEggData> allData = new TreeMap<>();

    // UI components
    private TextView totalEggsText, dailyAverageText, gradeAPercentText;
    private PieChart gradePieChart;
    private ProgressBar gradeAProgress, gradeBProgress, gradeCProgress;
    private TextView gradeACount, gradeBCount, gradeCCount;
    private TextView bestGradeText, productionRateText, totalProductionLabel;
    private TextView serverTimeLabel;
    private Spinner filterSpinner;
    private CardView filterChoiceCard;
    private LinearLayout filterChoiceButton;
    private TextView filterChoiceText;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static final SimpleDateFormat DATE_KEY_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final String[] FILTERS = {"All Time", "Today", "Weekly", "Monthly", "Yearly", "Custom"};

    private String currentFilter = "All Time";
    // Prevents the network callback AND the initial onCreate check from both
    // trying to attach the Firebase listener / toggle loading UI, which was
    // causing the loading overlay to show/hide/recreate() in a tight loop.
    private boolean listenerAttached = false;

    // Selected period state for filters that need a user choice
    private int selectedMonth = Calendar.getInstance().get(Calendar.MONTH);       // 0-based
    private int selectedMonthYear = Calendar.getInstance().get(Calendar.YEAR);
    private int selectedYear = Calendar.getInstance().get(Calendar.YEAR);
    private String customStartDate = null; // yyyy-MM-dd
    private String customEndDate = null;   // yyyy-MM-dd

    // Fixed brand colors — keep these in sync with the legend swatches in activity_analytics.xml
    private static final int COLOR_GRADE_A = Color.parseColor("#355E1A");
    private static final int COLOR_GRADE_B = Color.parseColor("#7C3AED");
    private static final int COLOR_GRADE_C = Color.parseColor("#F4B400");
    private static final int COLOR_NO_DATA = Color.parseColor("#D1D5DB");

    // Helper class to hold multi-grade data
    static class DailyEggData {
        int total;
        int gradeA;
        int gradeB;
        int gradeC;

        DailyEggData(int total, int gradeA, int gradeB, int gradeC) {
            this.total = total;
            this.gradeA = gradeA;
            this.gradeB = gradeB;
            this.gradeC = gradeC;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_analytics);

        // Firebase reference for egg collections
        eggCollectionsRef = FirebaseDatabase.getInstance().getReference("egg_collections");

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupFilterSpinner();

        // Internet check + attach listeners
        if (!NavigationHelper.INSTANCE.isInternetActuallyWorking(this)) {
            NavigationHelper.INSTANCE.showNoInternetOverlay(this);
        } else {
            NavigationHelper.INSTANCE.showGlobalLoading(this, "Analyzing Farm Yield...", () -> {
                attachRealtimeListener();
                return Unit.INSTANCE;
            });
        }
        // Registered AFTER the initial check above. Note: ConnectivityManager fires
        // onAvailable() immediately on registration if the device already has a
        // validated connection — attachRealtimeListener() below is guarded against
        // running twice for exactly that reason.
        startLiveInternetSensor();

        // Back button navigation
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

        startTimeUpdate();

        // Generate Report button
        Button generateReportBtn = findViewById(R.id.generateReportButton);
        if (generateReportBtn != null) {
            generateReportBtn.setOnClickListener(v -> showReportDialog());
        }
    }

    private void initViews() {
        totalEggsText = findViewById(R.id.totalEggsText);
        dailyAverageText = findViewById(R.id.dailyAverageText);
        gradeAPercentText = findViewById(R.id.gradeAPercentText);
        gradePieChart = findViewById(R.id.gradePieChart);
        gradeAProgress = findViewById(R.id.gradeAProgress);
        gradeBProgress = findViewById(R.id.gradeBProgress);
        gradeCProgress = findViewById(R.id.gradeCProgress);
        gradeACount = findViewById(R.id.gradeACount);
        gradeBCount = findViewById(R.id.gradeBCount);
        gradeCCount = findViewById(R.id.gradeCCount);
        bestGradeText = findViewById(R.id.bestGradeText);
        productionRateText = findViewById(R.id.productionRateText);
        totalProductionLabel = findViewById(R.id.totalProductionLabel);
        serverTimeLabel = findViewById(R.id.serverTimeLabel);
        filterSpinner = findViewById(R.id.filterSpinner);
        filterChoiceCard = findViewById(R.id.filterChoiceCard);
        filterChoiceButton = findViewById(R.id.filterChoiceButton);
        filterChoiceText = findViewById(R.id.filterChoiceText);

        setupPieChart();
    }

    private void setupPieChart() {
        gradePieChart.setUsePercentValues(true);
        gradePieChart.getDescription().setEnabled(false);
        gradePieChart.setExtraOffsets(5, 10, 5, 5);
        gradePieChart.setDragDecelerationFrictionCoef(0.95f);
        gradePieChart.setDrawHoleEnabled(true);
        gradePieChart.setHoleColor(Color.WHITE);
        gradePieChart.setTransparentCircleRadius(61f);
        gradePieChart.setEntryLabelColor(Color.BLACK);
        gradePieChart.setEntryLabelTextSize(12f);
        // Your XML already has a custom Grade A/B/C legend row below the chart,
        // so the built-in legend AND the on-slice text labels are redundant/cluttered.
        gradePieChart.getLegend().setEnabled(false);
        gradePieChart.setDrawEntryLabels(false);
    }

    private void setupFilterSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, FILTERS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(adapter);
        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentFilter = FILTERS[position];
                updateFilterChoiceVisibility();

                // First time a choice-based filter is picked, immediately prompt for
                // the period instead of silently filtering on stale/default values.
                if (currentFilter.equals("Custom") && (customStartDate == null || customEndDate == null)) {
                    showCustomRangePicker();
                } else {
                    updateDashboard();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        filterChoiceButton.setOnClickListener(v -> {
            switch (currentFilter) {
                case "Monthly": showMonthYearPicker(); break;
                case "Yearly": showYearPicker(); break;
                case "Custom": showCustomRangePicker(); break;
            }
        });

        updateFilterChoiceVisibility();
    }

    /** Shows/hides the period-choice row and keeps its label in sync with the current filter. */
    private void updateFilterChoiceVisibility() {
        switch (currentFilter) {
            case "Monthly":
                filterChoiceCard.setVisibility(View.VISIBLE);
                filterChoiceText.setText(monthYearLabel(selectedMonth, selectedMonthYear));
                break;
            case "Yearly":
                filterChoiceCard.setVisibility(View.VISIBLE);
                filterChoiceText.setText(String.valueOf(selectedYear));
                break;
            case "Custom":
                filterChoiceCard.setVisibility(View.VISIBLE);
                if (customStartDate != null && customEndDate != null) {
                    filterChoiceText.setText(displayDate(customStartDate) + "  —  " + displayDate(customEndDate));
                } else {
                    filterChoiceText.setText("Select date range");
                }
                break;
            default:
                filterChoiceCard.setVisibility(View.GONE);
                break;
        }
    }

    private String monthYearLabel(int month, int year) {
        String[] monthNames = new DateFormatSymbols(Locale.getDefault()).getMonths();
        return monthNames[month] + " " + year;
    }

    private String displayDate(String yyyyMmDd) {
        try {
            Date d = DATE_KEY_FORMAT.parse(yyyyMmDd);
            return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d);
        } catch (ParseException e) {
            return yyyyMmDd;
        }
    }

    /** Month + year picker for the "Monthly" filter. */
    private void showMonthYearPicker() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        int pad = dpToPx(16);
        layout.setPadding(pad, pad, pad, pad);

        NumberPicker monthPicker = new NumberPicker(this);
        String[] monthNames = new DateFormatSymbols(Locale.getDefault()).getMonths();
        // getMonths() returns 13 entries (last one is empty for some calendars) — trim to 12
        String[] twelveMonths = new String[12];
        System.arraycopy(monthNames, 0, twelveMonths, 0, 12);
        monthPicker.setMinValue(0);
        monthPicker.setMaxValue(11);
        monthPicker.setDisplayedValues(twelveMonths);
        monthPicker.setValue(selectedMonth);
        monthPicker.setWrapSelectorWheel(true);

        NumberPicker yearPicker = new NumberPicker(this);
        int nowYear = Calendar.getInstance().get(Calendar.YEAR);
        yearPicker.setMinValue(nowYear - 10);
        yearPicker.setMaxValue(nowYear);
        yearPicker.setValue(selectedMonthYear);
        yearPicker.setWrapSelectorWheel(false);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        layout.addView(monthPicker, params);
        layout.addView(yearPicker, params);

        new AlertDialog.Builder(this)
                .setTitle("Select Month")
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    selectedMonth = monthPicker.getValue();
                    selectedMonthYear = yearPicker.getValue();
                    updateFilterChoiceVisibility();
                    updateDashboard();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Year picker for the "Yearly" filter. */
    private void showYearPicker() {
        NumberPicker yearPicker = new NumberPicker(this);
        int nowYear = Calendar.getInstance().get(Calendar.YEAR);
        yearPicker.setMinValue(nowYear - 15);
        yearPicker.setMaxValue(nowYear);
        yearPicker.setValue(selectedYear);
        yearPicker.setWrapSelectorWheel(false);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        fp.gravity = Gravity.CENTER;
        int pad = dpToPx(16);
        container.setPadding(pad, pad, pad, pad);
        container.addView(yearPicker, fp);

        new AlertDialog.Builder(this)
                .setTitle("Select Year")
                .setView(container)
                .setPositiveButton("OK", (dialog, which) -> {
                    selectedYear = yearPicker.getValue();
                    updateFilterChoiceVisibility();
                    updateDashboard();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Start + end date pickers for the "Custom" filter. */
    private void showCustomRangePicker() {
        Calendar startCal = Calendar.getInstance();
        if (customStartDate != null) {
            try { startCal.setTime(DATE_KEY_FORMAT.parse(customStartDate)); } catch (ParseException ignored) {}
        }

        DatePickerDialog startDialog = new DatePickerDialog(this,
                (view, year, month, day) -> {
                    Calendar chosenStart = Calendar.getInstance();
                    chosenStart.set(year, month, day, 0, 0, 0);
                    String start = DATE_KEY_FORMAT.format(chosenStart.getTime());

                    Calendar endCal = Calendar.getInstance();
                    if (customEndDate != null) {
                        try { endCal.setTime(DATE_KEY_FORMAT.parse(customEndDate)); } catch (ParseException ignored) {}
                    }
                    if (endCal.getTimeInMillis() < chosenStart.getTimeInMillis()) {
                        endCal = (Calendar) chosenStart.clone();
                    }

                    DatePickerDialog endDialog = new DatePickerDialog(this,
                            (view2, year2, month2, day2) -> {
                                Calendar chosenEnd = Calendar.getInstance();
                                chosenEnd.set(year2, month2, day2, 0, 0, 0);
                                String end = DATE_KEY_FORMAT.format(chosenEnd.getTime());

                                customStartDate = start;
                                customEndDate = end;
                                updateFilterChoiceVisibility();
                                updateDashboard();
                            },
                            endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH));
                    endDialog.setTitle("Select End Date");
                    endDialog.getDatePicker().setMinDate(chosenStart.getTimeInMillis());
                    endDialog.show();
                },
                startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH));
        startDialog.setTitle("Select Start Date");
        startDialog.show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void attachRealtimeListener() {
        if (listenerAttached) return; // already listening — avoid duplicate Firebase listeners
        listenerAttached = true;

        eggCollectionsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                allData = new TreeMap<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String dateKey = child.getKey();
                    Long total = child.child("total").getValue(Long.class);
                    Long gA = child.child("gradeA").getValue(Long.class);
                    Long gB = child.child("gradeB").getValue(Long.class);
                    Long gC = child.child("gradeC").getValue(Long.class);

                    if (dateKey != null) {
                        allData.put(dateKey, new DailyEggData(
                                total != null ? total.intValue() : 0,
                                gA != null ? gA.intValue() : 0,
                                gB != null ? gB.intValue() : 0,
                                gC != null ? gC.intValue() : 0
                        ));
                    }
                }
                updateDashboard();
            }
            @Override public void onCancelled(DatabaseError error) {
                Toast.makeText(AnalyticsActivity.this, "Database Connection Error", Toast.LENGTH_SHORT).show();
            }
        };
        eggCollectionsRef.addValueEventListener(eggCollectionsListener);
    }

    private void updateDashboard() {
        int filteredTotal = 0;
        int filteredA = 0;
        int filteredB = 0;
        int filteredC = 0;
        int dayCount = 0;

        Calendar cal = Calendar.getInstance();
        String today = DATE_KEY_FORMAT.format(cal.getTime());

        Calendar cal7 = Calendar.getInstance();
        cal7.add(Calendar.DAY_OF_YEAR, -6); // today + previous 6 days = 7-day window
        String sevenDaysAgo = DATE_KEY_FORMAT.format(cal7.getTime());

        // "yyyy-MM" prefix for the chosen month (dateKey format is yyyy-MM-dd)
        String monthlyPrefix = String.format(Locale.getDefault(), "%04d-%02d", selectedMonthYear, selectedMonth + 1);
        // "yyyy" prefix for the chosen year
        String yearlyPrefix = String.format(Locale.getDefault(), "%04d", selectedYear);

        for (Map.Entry<String, DailyEggData> entry : allData.entrySet()) {
            boolean include = false;
            switch (currentFilter) {
                case "Today":
                    if (entry.getKey().equals(today)) include = true;
                    break;
                case "Weekly":
                    if (entry.getKey().compareTo(sevenDaysAgo) >= 0 && entry.getKey().compareTo(today) <= 0) {
                        include = true;
                    }
                    break;
                case "Monthly":
                    if (entry.getKey().startsWith(monthlyPrefix)) include = true;
                    break;
                case "Yearly":
                    if (entry.getKey().startsWith(yearlyPrefix)) include = true;
                    break;
                case "Custom":
                    if (customStartDate != null && customEndDate != null
                            && entry.getKey().compareTo(customStartDate) >= 0
                            && entry.getKey().compareTo(customEndDate) <= 0) {
                        include = true;
                    }
                    break;
                default: // All Time
                    include = true;
                    break;
            }

            if (include) {
                DailyEggData data = entry.getValue();
                filteredTotal += data.total;
                filteredA += data.gradeA;
                filteredB += data.gradeB;
                filteredC += data.gradeC;
                if (data.total > 0) dayCount++;
            }
        }

        // Update Summary Cards
        totalEggsText.setText(String.valueOf(filteredTotal));
        double avg = dayCount > 0 ? (double) filteredTotal / dayCount : 0;
        dailyAverageText.setText(String.format(Locale.getDefault(), "%.1f", avg));

        int gradeAPct = filteredTotal > 0 ? (filteredA * 100 / filteredTotal) : 0;
        gradeAPercentText.setText(String.format(Locale.getDefault(), "%d%%", gradeAPct));

        // ---- Update Pie Chart ----
        // Build entries and colors TOGETHER so a color always stays attached to its grade,
        // even when one or more grades are zero and get skipped from the slice list.
        List<PieEntry> entries = new ArrayList<>();
        List<Integer> sliceColors = new ArrayList<>();

        if (filteredTotal > 0) {
            if (filteredA > 0) {
                entries.add(new PieEntry(filteredA, "Grade A"));
                sliceColors.add(COLOR_GRADE_A);
            }
            if (filteredB > 0) {
                entries.add(new PieEntry(filteredB, "Grade B"));
                sliceColors.add(COLOR_GRADE_B);
            }
            if (filteredC > 0) {
                entries.add(new PieEntry(filteredC, "Grade C"));
                sliceColors.add(COLOR_GRADE_C);
            }
        } else {
            entries.add(new PieEntry(1, "No Data"));
            sliceColors.add(COLOR_NO_DATA);
        }

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(sliceColors);
        dataSet.setSliceSpace(3f);
        dataSet.setSelectionShift(5f);

        PieData pieData = new PieData(dataSet);
        if (filteredTotal > 0) {
            pieData.setValueFormatter(new PercentFormatter(gradePieChart));
        } else {
            // Don't show "100%" on the placeholder "No Data" slice
            pieData.setValueFormatter(new ValueFormatter() {
                @Override
                public String getPieLabel(float value, PieEntry entry) {
                    return "";
                }
            });
        }
        pieData.setValueTextSize(11f);
        pieData.setValueTextColor(Color.WHITE);
        gradePieChart.setData(pieData);
        gradePieChart.invalidate();
        gradePieChart.animateY(1000);

        // Update Grade Breakdown ProgressBars, counts, and per-grade percentages
        int pctB = filteredTotal > 0 ? (filteredB * 100 / filteredTotal) : 0;
        int pctC = filteredTotal > 0 ? (filteredC * 100 / filteredTotal) : 0;
        // gradeAPct already computed above as gradeAPct

        gradeACount.setText(String.format(Locale.getDefault(), "%d (%d%%)", filteredA, gradeAPct));
        gradeBCount.setText(String.format(Locale.getDefault(), "%d (%d%%)", filteredB, pctB));
        gradeCCount.setText(String.format(Locale.getDefault(), "%d (%d%%)", filteredC, pctC));

        if (filteredTotal > 0) {
            gradeAProgress.setProgress(gradeAPct);
            gradeBProgress.setProgress(pctB);
            gradeCProgress.setProgress(pctC);
        } else {
            gradeAProgress.setProgress(0);
            gradeBProgress.setProgress(0);
            gradeCProgress.setProgress(0);
        }

        totalProductionLabel.setText(String.format(Locale.getDefault(), "Total Eggs : %d", filteredTotal));

        // Update Stats Labels
        if (filteredTotal > 0) {
            if (filteredA >= filteredB && filteredA >= filteredC) bestGradeText.setText("Grade A");
            else if (filteredB >= filteredA && filteredB >= filteredC) bestGradeText.setText("Grade B");
            else bestGradeText.setText("Grade C");
        } else {
            bestGradeText.setText("N/A");
        }

        productionRateText.setText(String.format(Locale.getDefault(), "%d%%", gradeAPct));
    }

    private void showReportDialog() {
        int total = 0, a = 0, b = 0, c = 0;
        for (DailyEggData d : allData.values()) {
            total += d.total; a += d.gradeA; b += d.gradeB; c += d.gradeC;
        }

        String ts = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());
        String summary = String.format(Locale.getDefault(),
                "     FARM ANALYTICS REPORT\n" +
                        "Generated: %s\n\n" +
                        " EGG PRODUCTION\n" +
                        "Total Eggs Collected: %d\n" +
                        "Grade A: %d\n" +
                        "Grade B: %d\n" +
                        "Grade C: %d", ts, total, a, b, c);

        int finalA = a;
        int finalTotal = total;
        int finalB = b;
        int finalC = c;
        new AlertDialog.Builder(this)
                .setTitle("Farm Analytics Report")
                .setMessage(summary)
                .setPositiveButton("Export as PDF", (dialog, which) -> generatePdfReport(finalTotal, finalA, finalB, finalC))
                .setNegativeButton("Close", null)
                .show();
    }

    private void generatePdfReport(int total, int a, int b, int c) {
        try {
            String ts = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            Paint bg = new Paint(); bg.setColor(Color.WHITE);
            canvas.drawRect(0, 0, 595, 842, bg);

            Paint hdr = new Paint(); hdr.setColor(Color.parseColor("#355E1A"));
            canvas.drawRect(0, 0, 595, 75, hdr);

            Paint ht = new Paint(); ht.setColor(Color.WHITE); ht.setTextSize(20f); ht.setFakeBoldText(true);
            canvas.drawText("Waje's Quail Farm — Analytics Report", 18, 34, ht);

            Paint hs = new Paint(); hs.setColor(Color.WHITE); hs.setTextSize(11f);
            canvas.drawText("Generated: " + ts, 18, 56, hs);

            int y = 120;
            drawSection(canvas, "Production Summary", y); y += 30;
            drawRow(canvas, "Total Eggs Collected", String.valueOf(total), y); y += 20;
            drawRow(canvas, "Grade A (Normal)", String.valueOf(a), y); y += 20;
            drawRow(canvas, "Grade B (Cracked)", String.valueOf(b), y); y += 20;
            drawRow(canvas, "Grade C (Reject)", String.valueOf(c), y); y += 40;

            document.finishPage(page);
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WajeReports");
            if (!dir.exists()) dir.mkdirs();
            String filename = "FarmReport_" + System.currentTimeMillis() + ".pdf";
            File pdfFile = new File(dir, filename);
            document.writeTo(new FileOutputStream(pdfFile));
            document.close();

            android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open PDF Report"));
            Toast.makeText(this, "PDF saved: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to generate PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void drawSection(Canvas canvas, String title, int y) {
        Paint p = new Paint(); p.setTextSize(16f); p.setFakeBoldText(true); p.setColor(Color.parseColor("#355E1A"));
        canvas.drawText(title, 20, y, p);
    }

    private void drawRow(Canvas canvas, String label, String value, int y) {
        Paint p = new Paint(); p.setTextSize(14f); p.setColor(Color.BLACK);
        canvas.drawText(label, 30, y, p);
        canvas.drawText(value, 400, y, p);
    }

    private void startLiveInternetSensor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    // onAvailable() fires immediately on registration if the network is
                    // already validated, so this can run right at activity startup — don't
                    // let it fight with the initial onCreate loading state or recreate()
                    // the whole activity (that was the cause of the flicker: recreate()
                    // re-registers this same callback, which fires onAvailable() again,
                    // which recreates again, and so on).
                    View ll = findViewById(R.id.loadingLayout);
                    if (ll != null) {
                        ll.setVisibility(View.GONE);
                    }
                    attachRealtimeListener(); // no-op if already attached
                });
            }
            @Override public void onLost(Network network) {
                runOnUiThread(() -> NavigationHelper.INSTANCE.showNoInternetOverlay(AnalyticsActivity.this));
            }
        };
        cm.registerNetworkCallback(request, networkCallback);
    }

    private void startTimeUpdate() {
        SimpleDateFormat timeFormat = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault());
        Handler handler = new Handler();
        handler.post(new Runnable() {
            @Override public void run() {
                if (serverTimeLabel != null) serverTimeLabel.setText(timeFormat.format(new Date()));
                handler.postDelayed(this, 60000);
            }
        });
    }

    @Override protected void onStop() {
        super.onStop();
        if (networkCallback != null)
            ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
        if (eggCollectionsListener != null)
            eggCollectionsRef.removeEventListener(eggCollectionsListener);
    }
}