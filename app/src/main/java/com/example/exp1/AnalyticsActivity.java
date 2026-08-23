package com.example.exp1;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
    // Sunday (start) of the chosen week for the "Weekly" filter. Null = default to
    // the trailing 7-day window (today - 6 .. today), same as the old behavior.
    private String selectedWeekStartDate = null; // yyyy-MM-dd

    // Fixed brand colors — keep these in sync with the legend swatches in activity_analytics.xml
    private static final int COLOR_GRADE_A = Color.parseColor("#355E1A");
    private static final int COLOR_GRADE_B = Color.parseColor("#7C3AED");
    private static final int COLOR_GRADE_C = Color.parseColor("#F4B400");
    private static final int COLOR_NO_DATA = Color.parseColor("#D1D5DB");

    // Size (in px) of the pie chart bitmap embedded in PDF/PNG/JPEG report exports.
    // Bumped up from the old 400x420 so the chart reads clearly on export/print,
    // and kept separate from the on-screen dashboard chart entirely (see
    // createPieChartBitmap — it's hand-drawn, not routed through a PieChart view).
    private static final int REPORT_CHART_WIDTH = 480;
    private static final int REPORT_CHART_HEIGHT = 560;

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
        gradePieChart.setExtraOffsets(10, 10, 10, 10);
        gradePieChart.setDragDecelerationFrictionCoef(0.95f);
        gradePieChart.setDrawHoleEnabled(true);
        gradePieChart.setHoleColor(Color.WHITE);
        gradePieChart.setTransparentCircleRadius(61f);
        gradePieChart.setEntryLabelColor(Color.BLACK);
        gradePieChart.setEntryLabelTextSize(12f);
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
                case "Weekly": showWeekPicker(); break;
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
            case "Weekly":
                filterChoiceCard.setVisibility(View.VISIBLE);
                if (selectedWeekStartDate != null) {
                    String weekEnd = weekEndDate(selectedWeekStartDate);
                    filterChoiceText.setText(displayDate(selectedWeekStartDate) + "  —  " + displayDate(weekEnd));
                } else {
                    filterChoiceText.setText("This Week (last 7 days)");
                }
                break;
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

    /** Given a week-start (yyyy-MM-dd, Sunday), returns the Saturday 6 days later. */
    private String weekEndDate(String weekStartYyyyMmDd) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DATE_KEY_FORMAT.parse(weekStartYyyyMmDd));
            c.add(Calendar.DAY_OF_YEAR, 6);
            return DATE_KEY_FORMAT.format(c.getTime());
        } catch (ParseException e) {
            return weekStartYyyyMmDd;
        }
    }

    /**
     * Week picker for the "Weekly" filter. User picks any day; we snap that
     * selection back to the Sunday that starts its calendar week, so the
     * filtered range is always a clean Sunday–Saturday 7-day window.
     */
    private void showWeekPicker() {
        Calendar cal = Calendar.getInstance();
        if (selectedWeekStartDate != null) {
            try { cal.setTime(DATE_KEY_FORMAT.parse(selectedWeekStartDate)); } catch (ParseException ignored) {}
        }

        DatePickerDialog dialog = new DatePickerDialog(this,
                (view, year, month, day) -> {
                    Calendar picked = Calendar.getInstance();
                    picked.set(year, month, day, 0, 0, 0);
                    // Snap back to the Sunday of the picked date's week
                    int dayOfWeek = picked.get(Calendar.DAY_OF_WEEK); // 1=Sunday ... 7=Saturday
                    picked.add(Calendar.DAY_OF_YEAR, -(dayOfWeek - 1));
                    selectedWeekStartDate = DATE_KEY_FORMAT.format(picked.getTime());
                    updateFilterChoiceVisibility();
                    updateDashboard();
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dialog.setTitle("Pick Any Day in the Week");
        dialog.show();
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

    /**
     * Returns the subset of allData matching the currently selected filter (Today / Weekly /
     * Monthly / Yearly / Custom / All Time). Shared by the dashboard cards+chart and by the
     * report generator so the report always reflects whichever filter is active on screen.
     */
    private Map<String, DailyEggData> getFilteredData() {
        Map<String, DailyEggData> filtered = new TreeMap<>();

        Calendar cal = Calendar.getInstance();
        String today = DATE_KEY_FORMAT.format(cal.getTime());

        Calendar cal7 = Calendar.getInstance();
        cal7.add(Calendar.DAY_OF_YEAR, -6); // today + previous 6 days = default 7-day window
        String sevenDaysAgo = DATE_KEY_FORMAT.format(cal7.getTime());

        // Resolve the active weekly range: user-picked Sunday-start week, or the
        // default trailing 7 days if they haven't picked one yet.
        String weekStart = selectedWeekStartDate != null ? selectedWeekStartDate : sevenDaysAgo;
        String weekEnd = selectedWeekStartDate != null ? weekEndDate(selectedWeekStartDate) : today;

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
                    if (entry.getKey().compareTo(weekStart) >= 0 && entry.getKey().compareTo(weekEnd) <= 0) {
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
            if (include) filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    /** Human-readable description of the currently active filter, for display on the report. */
    private String getReportPeriodLabel() {
        switch (currentFilter) {
            case "Today": {
                String today = DATE_KEY_FORMAT.format(Calendar.getInstance().getTime());
                return "Today — " + displayDate(today);
            }
            case "Weekly": {
                Calendar cal7 = Calendar.getInstance();
                cal7.add(Calendar.DAY_OF_YEAR, -6);
                String sevenDaysAgo = DATE_KEY_FORMAT.format(cal7.getTime());
                String today = DATE_KEY_FORMAT.format(Calendar.getInstance().getTime());
                String weekStart = selectedWeekStartDate != null ? selectedWeekStartDate : sevenDaysAgo;
                String weekEnd = selectedWeekStartDate != null ? weekEndDate(selectedWeekStartDate) : today;
                return "Weekly — " + displayDate(weekStart) + " to " + displayDate(weekEnd);
            }
            case "Monthly":
                return "Monthly — " + monthYearLabel(selectedMonth, selectedMonthYear);
            case "Yearly":
                return "Yearly — " + selectedYear;
            case "Custom":
                if (customStartDate != null && customEndDate != null) {
                    return "Custom — " + displayDate(customStartDate) + " to " + displayDate(customEndDate);
                }
                return "Custom Range";
            default:
                return "All Time";
        }
    }

    /** Short filename-safe tag for the current filter (e.g. "Weekly", "AllTime"), used in exported report filenames. */
    private String getReportFilterTag() {
        switch (currentFilter) {
            case "Today": return "Today";
            case "Weekly": return "Weekly";
            case "Monthly": return "Monthly_" + monthYearLabel(selectedMonth, selectedMonthYear).replace(" ", "");
            case "Yearly": return "Yearly_" + selectedYear;
            case "Custom": return "Custom";
            default: return "AllTime";
        }
    }

    private void updateDashboard() {
        int filteredTotal = 0;
        int filteredA = 0;
        int filteredB = 0;
        int filteredC = 0;
        int dayCount = 0;

        Map<String, DailyEggData> filteredData = getFilteredData();
        for (DailyEggData data : filteredData.values()) {
            filteredTotal += data.total;
            filteredA += data.gradeA;
            filteredB += data.gradeB;
            filteredC += data.gradeC;
            if (data.total > 0) dayCount++;
        }

        // Update Summary Cards
        totalEggsText.setText(String.valueOf(filteredTotal));
        double avg = dayCount > 0 ? (double) filteredTotal / dayCount : 0;
        dailyAverageText.setText(String.format(Locale.getDefault(), "%.1f", avg));

        int gradeAPct = filteredTotal > 0 ? (filteredA * 100 / filteredTotal) : 0;
        gradeAPercentText.setText(String.format(Locale.getDefault(), "%d%%", gradeAPct));

        // ---- Update Pie Chart (on-screen dashboard — still MPAndroidChart, unchanged) ----
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

        // --- Straight leader lines pointing at each slice ---
        dataSet.setValueLinePart1OffsetPercentage(80f);   // where the line starts (near slice edge)
        dataSet.setValueLinePart1Length(0.5f);            // radial line length
        dataSet.setValueLinePart2Length(0f);               // 0 = no horizontal kink, keeps it a straight line
        dataSet.setValueLineWidth(1.5f);
        dataSet.setUsingSliceColorAsValueLineColor(true);
        dataSet.setXValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE);
        dataSet.setYValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE);
        dataSet.setValueTextColor(Color.BLACK);
        dataSet.setValueTextSize(11f);

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
        gradePieChart.setData(pieData);

        // Force every slice to keep at least this much visual angle so that two
        // small/near-equal grades don't collapse into each other and squish their
        // percentage labels together. Purely visual — underlying % values shown
        // in the labels themselves are still the real numbers.
        gradePieChart.setMinAngleForSlices(18f);

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

        totalProductionLabel.setText(String.format(Locale.getDefault(), "Total No. of Eggs : %d", filteredTotal));

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
        for (DailyEggData d : getFilteredData().values()) {
            total += d.total; a += d.gradeA; b += d.gradeB; c += d.gradeC;
        }
        String periodLabel = getReportPeriodLabel();

        String ts = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());
        String summary = String.format(Locale.getDefault(),
                "     FARM ANALYTICS REPORT\n" +
                        "Generated: %s\n" +
                        "Period: %s\n\n" +
                        " EGG PRODUCTION\n" +
                        "Total No. of Eggs Collected: %d\n" +
                        "Grade A: %d\n" +
                        "Grade B: %d\n" +
                        "Grade C: %d", ts, periodLabel, total, a, b, c);

        int finalA = a;
        int finalTotal = total;
        int finalB = b;
        int finalC = c;
        new AlertDialog.Builder(this)
                .setTitle("Farm Analytics Report — " + currentFilter)
                .setMessage(summary)
                .setPositiveButton("Export as PDF", (dialog, which) -> generatePdfReport(finalTotal, finalA, finalB, finalC, periodLabel))
                .setNeutralButton("Export as Image", (dialog, which) -> showImageFormatChooser(finalTotal, finalA, finalB, finalC, periodLabel))
                .setNegativeButton("Close", null)
                .show();
    }

    /** Lets the user pick PNG (lossless) or JPEG (smaller file) before exporting the report as an image. */
    private void showImageFormatChooser(int total, int a, int b, int c, String periodLabel) {
        String[] formats = {"PNG (best quality)", "JPEG (smaller file)"};
        new AlertDialog.Builder(this)
                .setTitle("Choose Image Format")
                .setItems(formats, (dialog, which) -> {
                    if (which == 0) {
                        exportImageReport(total, a, b, c, periodLabel, Bitmap.CompressFormat.PNG, "png", "image/png");
                    } else {
                        exportImageReport(total, a, b, c, periodLabel, Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg");
                    }
                })
                .show();
    }

    /**
     * Renders the grade-distribution pie chart used in PDF/PNG/JPEG report exports.
     *
     * IMPORTANT: this is drawn by hand directly onto the Canvas (arcs + lines + text) instead
     * of instantiating a real MPAndroidChart PieChart view and calling draw() on it. The old
     * approach worked on most devices but was unreliable on others: an MPAndroidChart PieChart
     * that's never attached to a window depends on Android view-system internals (measure/layout
     * callbacks, the legend's own word-wrap logic, text-scale/density handling, Utils.init state)
     * that don't always behave the same off-screen across OEM skins, densities and chart-library
     * versions — which is exactly the kind of thing that shows up as "the pie chart just doesn't
     * render" on some devices while working fine on others. Drawing the slices ourselves removes
     * that dependency entirely: given the same a/b/c inputs, this always produces the same output,
     * on every device, every time. It's also easy to make bigger (see REPORT_CHART_WIDTH/HEIGHT).
     */
    private Bitmap createPieChartBitmap(int a, int b, int c, int widthPx, int heightPx) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            int total = a + b + c;

            // Slice list — falls back to a single gray "No Data" slice when there's nothing
            // to show, matching the on-screen dashboard's placeholder behavior.
            List<String> labels = new ArrayList<>();
            List<Integer> values = new ArrayList<>();
            List<Integer> sliceColors = new ArrayList<>();
            if (total > 0) {
                if (a > 0) { labels.add("Grade A"); values.add(a); sliceColors.add(COLOR_GRADE_A); }
                if (b > 0) { labels.add("Grade B"); values.add(b); sliceColors.add(COLOR_GRADE_B); }
                if (c > 0) { labels.add("Grade C"); values.add(c); sliceColors.add(COLOR_GRADE_C); }
            } else {
                labels.add("No Data"); values.add(1); sliceColors.add(COLOR_NO_DATA);
            }
            int sliceTotal = 0;
            for (int v : values) sliceTotal += v;

            // Reserve a legend band at the bottom; the pie itself occupies the area above it.
            float legendHeight = 74f;
            float chartAreaHeight = heightPx - legendHeight;
            float centerX = widthPx / 2f;
            float centerY = chartAreaHeight / 2f;
            // Generous margin (0.30 instead of ~0.45) so the outside % labels and their leader
            // lines always have room and never get clipped at the bitmap edge.
            float radius = Math.min(widthPx, chartAreaHeight) * 0.30f;

            RectF oval = new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

            Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(2f);
            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextSize(22f);
            labelPaint.setFakeBoldText(true);

            float startAngle = -90f; // 12 o'clock
            for (int i = 0; i < values.size(); i++) {
                float sweep = (values.get(i) / (float) sliceTotal) * 360f;

                slicePaint.setColor(sliceColors.get(i));
                canvas.drawArc(oval, startAngle, sweep, true, slicePaint);

                // Outside percentage label with a short radial leader line at the slice's
                // midpoint angle, so labels stay legible even for thin slices.
                float midAngleRad = (float) Math.toRadians(startAngle + sweep / 2f);
                float lineStartX = centerX + (float) Math.cos(midAngleRad) * radius;
                float lineStartY = centerY + (float) Math.sin(midAngleRad) * radius;
                float lineEndX = centerX + (float) Math.cos(midAngleRad) * (radius + 30f);
                float lineEndY = centerY + (float) Math.sin(midAngleRad) * (radius + 30f);

                linePaint.setColor(sliceColors.get(i));
                canvas.drawLine(lineStartX, lineStartY, lineEndX, lineEndY, linePaint);

                if (total > 0) {
                    int pct = Math.round((values.get(i) / (float) sliceTotal) * 100f);
                    String label = pct + "%";
                    float textWidth = labelPaint.measureText(label);
                    float textX = lineEndX >= centerX ? lineEndX + 6f : lineEndX - textWidth - 6f;
                    canvas.drawText(label, textX, lineEndY + 8f, labelPaint);
                }

                startAngle += sweep;
            }

            // White donut hole in the middle, matching the on-screen chart's style.
            Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            holePaint.setColor(Color.WHITE);
            canvas.drawCircle(centerX, centerY, radius * 0.55f, holePaint);

            // Centered legend row along the bottom band.
            Paint legendTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            legendTextPaint.setColor(Color.BLACK);
            legendTextPaint.setTextSize(20f);
            float boxSize = 18f;
            float gapAfterBox = 8f;
            float gapBetweenItems = 26f;

            float totalLegendWidth = 0f;
            for (String label : labels) {
                totalLegendWidth += boxSize + gapAfterBox + legendTextPaint.measureText(label) + gapBetweenItems;
            }
            totalLegendWidth -= gapBetweenItems;

            float legendX = centerX - totalLegendWidth / 2f;
            float legendBaselineY = chartAreaHeight + legendHeight / 2f + 7f;

            Paint boxPaint = new Paint();
            for (int i = 0; i < labels.size(); i++) {
                boxPaint.setColor(sliceColors.get(i));
                canvas.drawRect(legendX, legendBaselineY - boxSize, legendX + boxSize, legendBaselineY, boxPaint);
                legendX += boxSize + gapAfterBox;
                canvas.drawText(labels.get(i), legendX, legendBaselineY, legendTextPaint);
                legendX += legendTextPaint.measureText(labels.get(i)) + gapBetweenItems;
            }

            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void generatePdfReport(int total, int a, int b, int c, String periodLabel) {
        try {
            String ts = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            Paint bg = new Paint(); bg.setColor(Color.WHITE);
            canvas.drawRect(0, 0, 595, 842, bg);

            Paint hdr = new Paint(); hdr.setColor(Color.parseColor("#355E1A"));
            canvas.drawRect(0, 0, 595, 92, hdr);

            // --- App logo, top-left of the header band ---
            // Logo PNG lives at app/src/main/res/drawable/app_logo.png — rename here if yours differs.
            Bitmap logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.logo_quailfarm);
            if (logoBitmap != null) {
                Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, 50, 50, true);
                canvas.drawBitmap(scaledLogo, 18, 20, null);
            }

            Paint ht = new Paint(); ht.setColor(Color.WHITE); ht.setTextSize(20f); ht.setFakeBoldText(true);
            canvas.drawText("Waje's Quail Farm — Analytics Report", 78, 40, ht);

            Paint hs = new Paint(); hs.setColor(Color.WHITE); hs.setTextSize(11f);
            canvas.drawText("Generated: " + ts, 78, 58, hs);
            canvas.drawText("Period: " + periodLabel, 78, 76, hs);

            int y = 125;
            drawSection(canvas, "Production Summary (" + currentFilter + ")", y); y += 30;
            drawRow(canvas, "Total No. of Eggs Collected", String.valueOf(total), y); y += 20;
            drawRow(canvas, "Grade A (Normal)", String.valueOf(a), y); y += 20;
            drawRow(canvas, "Grade B (Cracked)", String.valueOf(b), y); y += 20;
            drawRow(canvas, "Grade C (Reject)", String.valueOf(c), y); y += 40;

            // --- Grade distribution pie chart ---
            drawSection(canvas, "Grade Distribution", y); y += 20;
            Bitmap chartBitmap = createPieChartBitmap(a, b, c, REPORT_CHART_WIDTH, REPORT_CHART_HEIGHT);
            float chartLeft = (595 - REPORT_CHART_WIDTH) / 2f;
            if (chartBitmap != null) {
                canvas.drawBitmap(chartBitmap, chartLeft, y, null);
            } else {
                // Should be effectively unreachable now that the chart is hand-drawn, but keep a
                // visible fallback instead of silently leaving a blank gap on the report.
                Paint fallback = new Paint(); fallback.setColor(Color.GRAY); fallback.setTextSize(14f);
                canvas.drawText("Chart unavailable", chartLeft, y + 30, fallback);
            }

            document.finishPage(page);
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WajeReports");
            if (!dir.exists()) dir.mkdirs();
            String filename = "FarmReport_" + getReportFilterTag() + "_" + System.currentTimeMillis() + ".pdf";
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

    /**
     * Renders the same header/logo + stats + pie chart layout used in the PDF report onto a
     * Bitmap and saves it as a PNG or JPEG, depending on what the user picked.
     */
    private void exportImageReport(int total, int a, int b, int c, String periodLabel, Bitmap.CompressFormat format, String ext, String mimeType) {
        try {
            // Height increased (800 -> 870) to comfortably fit the larger REPORT_CHART_HEIGHT
            // pie chart below the summary rows without clipping it.
            int width = 595, height = 870;
            Bitmap reportBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(reportBitmap);

            Paint bg = new Paint(); bg.setColor(Color.WHITE);
            canvas.drawRect(0, 0, width, height, bg);

            Paint hdr = new Paint(); hdr.setColor(Color.parseColor("#355E1A"));
            canvas.drawRect(0, 0, width, 92, hdr);

            Bitmap logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.logo_quailfarm);
            if (logoBitmap != null) {
                Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, 50, 50, true);
                canvas.drawBitmap(scaledLogo, 18, 20, null);
            }

            String ts = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(new Date());
            Paint ht = new Paint(); ht.setColor(Color.WHITE); ht.setTextSize(20f); ht.setFakeBoldText(true);
            canvas.drawText("Waje's Quail Farm — Analytics Report", 78, 40, ht);

            Paint hs = new Paint(); hs.setColor(Color.WHITE); hs.setTextSize(11f);
            canvas.drawText("Generated: " + ts, 78, 58, hs);
            canvas.drawText("Period: " + periodLabel, 78, 76, hs);

            int y = 125;
            drawSection(canvas, "Production Summary (" + currentFilter + ")", y); y += 30;
            drawRow(canvas, "Total No. of Eggs Collected", String.valueOf(total), y); y += 20;
            drawRow(canvas, "Grade A (Normal)", String.valueOf(a), y); y += 20;
            drawRow(canvas, "Grade B (Cracked)", String.valueOf(b), y); y += 20;
            drawRow(canvas, "Grade C (Reject)", String.valueOf(c), y); y += 40;

            drawSection(canvas, "Grade Distribution", y); y += 20;
            Bitmap chartBitmap = createPieChartBitmap(a, b, c, REPORT_CHART_WIDTH, REPORT_CHART_HEIGHT);
            float chartLeft = (width - REPORT_CHART_WIDTH) / 2f;
            if (chartBitmap != null) {
                canvas.drawBitmap(chartBitmap, chartLeft, y, null);
            } else {
                Paint fallback = new Paint(); fallback.setColor(Color.GRAY); fallback.setTextSize(14f);
                canvas.drawText("Chart unavailable", chartLeft, y + 30, fallback);
            }

            // JPEG has no alpha channel — flatten onto white before compressing so
            // transparent chart/logo edges don't turn black.
            // NOTE: uses the same DIRECTORY_DOCUMENTS root as the PDF export (not
            // DIRECTORY_PICTURES) because that's the root actually registered in
            // res/xml/file_paths.xml for the FileProvider. Pointing FileProvider at an
            // unregistered root is exactly what caused the "failed to find configured
            // root" crash.
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WajeReports");
            if (!dir.exists()) dir.mkdirs();
            String filename = "FarmReport_" + getReportFilterTag() + "_" + System.currentTimeMillis() + "." + ext;
            File imageFile = new File(dir, filename);
            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                reportBitmap.compress(format, 92, out);
            }

            android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open Image Report"));
            Toast.makeText(this, "Image saved: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to generate image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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