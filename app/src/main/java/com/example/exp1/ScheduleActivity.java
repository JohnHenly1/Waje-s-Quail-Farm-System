package com.example.exp1;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;

import com.example.exp1.FarmRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class ScheduleActivity extends AppCompatActivity {

    private CameraHelper cameraHelper;
    private String RECUR_ONCE;

    private Calendar currentWeekCalendar;
    private TextView monthText;
    private TextView weekRangeLabel;
    private TextView[] dayTextViews;
    private TextView[] dayLabelViews;
    private View[] dayContainers;
    private Calendar today;
    private Calendar selectedDate;
    private GestureDetector gestureDetector;

    private LinearLayout tasksContainer;
    private TextView doneCount, ongoingCount, pendingCount;
    private List<Task> taskList = new ArrayList<>();
    // Filter buttons
    private Button filterAssignedBtn, filterMissingBtn, filterDoneBtn;
    private static final String FILTER_ASSIGNED = "ASSIGNED"; // pending
    private static final String FILTER_MISSING = "MISSING";   // missed
    private static final String FILTER_DONE = "DONE";         // done
    private String activeFilter = FILTER_ASSIGNED;

    private FirebaseFirestore db;
    private String currentUserEmail;
    private ListenerRegistration tasksListener;
    private RoleManager roleManager;
    private AccountManager accountManager;

    // Caches email -> full name for the "Assigned To" identifier on each task
    // card. Populated lazily from the existing user_access collection (same
    // source already used by the assignee selector) — no user data is duplicated.
    private final Map<String, String> staffNameCache = new HashMap<>();

    // Tracks task IDs already logged as Missed this session, since Missed is
    // derived client-side on every render and would otherwise be re-logged
    // on every auto-refresh.
    private final java.util.Set<String> loggedMissedTaskIds = new java.util.HashSet<>();

    // ── Mark-as-Done proof (comment + photo) ────────────────────────────────
    // Marking a task Done now requires a written comment and a photo as proof
    // of completion. These hold the in-flight state for whichever task is
    // currently going through that flow.
    private ActivityResultLauncher<Uri> takePictureLauncher;
    // Requests CAMERA at runtime specifically for the proof-photo capture below.
    // Declaring android.permission.CAMERA in the manifest (needed for the egg
    // detection feature) means ANY camera capture intent — including this
    // implicit ACTION_IMAGE_CAPTURE one — requires the runtime grant first,
    // or it silently fails/crashes. This was missing, which is why "Take
    // Photo" in Mark as Done didn't work.
    private ActivityResultLauncher<String> proofCameraPermissionLauncher;
    private Uri pendingProofImageUri;
    // The actual on-disk File backing pendingProofImageUri. Uploads read from this
    // directly (see uploadProofImageAndFinalize) instead of re-opening the content://
    // Uri through the ContentResolver/FileProvider layer, which is what was producing
    // the "Object does not exist at location" Firebase Storage error even when the
    // file was genuinely present and non-empty.
    private File pendingProofImageFile;
    private ImageView proofImagePreview;
    private Task pendingDoneTask;
    // Proof photos are stored as Base64 directly on the task document (no Firebase
    // Storage bucket involved), so they're downscaled/compressed to stay well under
    // Firestore's 1 MiB per-document limit, leaving headroom for the rest of the fields.
    private static final int PROOF_IMAGE_MAX_DIMENSION = 1024; // px, longest side
    private static final int PROOF_IMAGE_MAX_BYTES = 600_000;  // raw JPEG bytes, pre-Base64

    private String[] monthNames;
    private Handler autoUpdateHandler = new Handler();
    private Runnable autoUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTasksUI();
            autoUpdateHandler.postDelayed(this, 60000); // Refresh every minute for status changes
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        EdgeToEdge.enable(this);

        RECUR_ONCE = getString(R.string.recur_once);
        monthNames = getResources().getStringArray(R.array.month_names);

        // Registered here (must happen before STARTED) so the "Take Photo" proof
        // step in the Mark-as-Done flow can launch the camera and receive the result.
        takePictureLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            // Some OEM camera apps (notably MIUI) report success=true via RESULT_OK
            // without actually having written any bytes to the target Uri. Trusting
            // "success" alone leaves pendingProofImageUri pointing at a file that
            // doesn't exist, which later surfaces as a cryptic Firebase Storage
            // "Object does not exist at location" error deep in the upload flow.
            // Verify the file really has content *here*, right after capture, so we
            // can fail fast with an actionable message instead.
            if (success && pendingProofImageUri != null && proofImagePreview != null
                    && proofFileHasContent(pendingProofImageUri)) {
                proofImagePreview.setImageURI(pendingProofImageUri);
                proofImagePreview.setVisibility(View.VISIBLE);
            } else {
                pendingProofImageUri = null;
                pendingProofImageFile = null;
                Toast.makeText(this, "Photo capture failed or was cancelled. Please try taking the photo again.", Toast.LENGTH_LONG).show();
            }
        });

        // Also registered here (must happen before STARTED). Only ever triggers the
        // camera launch on an explicit grant; a denial leaves the proof dialog open
        // with a clear message instead of silently doing nothing.
        proofCameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                launchProofCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to take the completion photo", Toast.LENGTH_SHORT).show();
            }
        });

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
            Toast.makeText(this, getString(R.string.detected_eggs, total), Toast.LENGTH_SHORT).show();
        });

        setContentView(R.layout.activity_schedule);
        createNotificationChannel();

        db = FirebaseFirestore.getInstance();
        currentUserEmail = getIntent().getStringExtra("username");
        if (currentUserEmail == null || currentUserEmail.isEmpty()) {
            currentUserEmail = "default_user";
        }

        accountManager = new AccountManager(this);
        roleManager = new RoleManager(accountManager.getCurrentRole());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        today = Calendar.getInstance();
        selectedDate = (Calendar) today.clone();
        currentWeekCalendar = (Calendar) today.clone();
        alignCalendarToMonday(currentWeekCalendar);

        monthText      = findViewById(R.id.month);
        weekRangeLabel = findViewById(R.id.weekRangeLabel);
        tasksContainer = findViewById(R.id.tasksContainer);
        doneCount      = findViewById(R.id.doneCount);
        ongoingCount   = findViewById(R.id.ongoingCount);
        pendingCount   = findViewById(R.id.pendingCount);

        dayTextViews = new TextView[]{
                findViewById(R.id.day1), findViewById(R.id.day2), findViewById(R.id.day3),
                findViewById(R.id.day4), findViewById(R.id.day5), findViewById(R.id.day6),
                findViewById(R.id.day7)
        };
        dayLabelViews = new TextView[]{
                findViewById(R.id.dayLabel1), findViewById(R.id.dayLabel2), findViewById(R.id.dayLabel3),
                findViewById(R.id.dayLabel4), findViewById(R.id.dayLabel5), findViewById(R.id.dayLabel6),
                findViewById(R.id.dayLabel7)
        };
        dayContainers = new View[]{
                findViewById(R.id.dayContainer1), findViewById(R.id.dayContainer2), findViewById(R.id.dayContainer3),
                findViewById(R.id.dayContainer4), findViewById(R.id.dayContainer5), findViewById(R.id.dayContainer6),
                findViewById(R.id.dayContainer7)
        };

        setupDayClickListeners();

        findViewById(R.id.imageButton).setOnClickListener(v -> {
            Intent intent = new Intent(ScheduleActivity.this, DashboardActivity.class);
            intent.putExtra("username", getIntent().getStringExtra("username"));
            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            finish();
        });


        ImageButton addBtn = findViewById(R.id.AddScheduleBtn);
        if (roleManager.canAddTask()) {
            addBtn.setVisibility(View.VISIBLE);
            addBtn.setOnClickListener(v -> showAddScheduleDialog());
        } else {
            addBtn.setVisibility(View.GONE);
        }

        findViewById(R.id.seeCalendarBtn).setOnClickListener(v -> showFullCalendar());
        findViewById(R.id.bulkDeleteBtn).setOnClickListener(v -> showBulkDeleteDialog());
        findViewById(R.id.taskDetailsBtn).setOnClickListener(v -> showAllTaskDetails());

        // Setup filter buttons (Assigned / Missing / Done)
        filterAssignedBtn = findViewById(R.id.filterAssignedBtn);
        filterMissingBtn = findViewById(R.id.filterMissingBtn);
        filterDoneBtn = findViewById(R.id.filterDoneBtn);

        View.OnClickListener filterClick = v -> {
            if (v.getId() == R.id.filterAssignedBtn) activeFilter = FILTER_ASSIGNED;
            else if (v.getId() == R.id.filterMissingBtn) activeFilter = FILTER_MISSING;
            else if (v.getId() == R.id.filterDoneBtn) activeFilter = FILTER_DONE;
            updateFilterButtonsUI();
            updateTasksUI();
        };
        if (filterAssignedBtn != null) filterAssignedBtn.setOnClickListener(filterClick);
        if (filterMissingBtn != null) filterMissingBtn.setOnClickListener(filterClick);
        if (filterDoneBtn != null) filterDoneBtn.setOnClickListener(filterClick);
        updateFilterButtonsUI();

        NavigationHelper.INSTANCE.setupBottomNavigation(this);


        updateCalendarUI();
        setupSwipeGestures();
        listenToTasks();
        cleanupOldProofPhotoCache();
        if (roleManager.isOwner()) {
            cleanupOldTaskHistory();
            cleanupOldProofPhotos();
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        autoUpdateHandler.post(autoUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoUpdateHandler.removeCallbacks(autoUpdateRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tasksListener != null) tasksListener.remove();
    }

    private void listenToTasks() {
        tasksListener = db.collection("farm_data")
                .document("shared")
                .collection("tasks")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        Toast.makeText(this, getString(R.string.failed_to_load_tasks, e.getMessage()), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    taskList.clear();
                    if (snapshots != null) {
                        for (QueryDocumentSnapshot doc : snapshots) {
                            Task task = new Task(
                                    doc.getId(),
                                    doc.getString("title"),
                                    doc.getString("category"),
                                    doc.getString("time"),
                                    doc.getString("status"),
                                    doc.getLong("year")  != null ? doc.getLong("year").intValue()  : 0,
                                    doc.getLong("month") != null ? doc.getLong("month").intValue() : 0,
                                    doc.getLong("day")   != null ? doc.getLong("day").intValue()   : 0,
                                    doc.getString("recurrence") != null ? doc.getString("recurrence") : RECUR_ONCE,
                                    doc.getString("recurrenceGroupId")
                            );
                            task.extensionMinutes = doc.getLong("extensionMinutes") != null ? doc.getLong("extensionMinutes").intValue() : 0;
                            task.workWindowMinutes = doc.getLong("workWindowMinutes") != null ? doc.getLong("workWindowMinutes").intValue() : 60;
                            task.assignedTo = parseAssignedTo(doc.get("assignedTo"));
                            task.assignedBy = doc.getString("assignedBy");
                            task.doneComment = doc.getString("doneComment");
                            task.doneImageUrl = doc.getString("doneImageUrl");
                            taskList.add(task);
                        }
                    }
                    updateTasksUI();
                });
    }

    /**
     * Reads the assignedTo field defensively: new docs store a List<String>
     * (multi-assign), older docs (pre multi-assign) stored a single String
     * email. Both are normalized into a List<String> so the rest of the
     * code only ever deals with one shape.
     */
    @SuppressWarnings("unchecked")
    private List<String> parseAssignedTo(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o != null && !o.toString().trim().isEmpty()) result.add(o.toString().trim());
            }
        } else if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private Map<String, Object> buildTaskMap(Task task) {
        Map<String, Object> data = new HashMap<>();
        data.put("title",              task.title);
        data.put("category",           task.category);
        data.put("time",               task.time);
        data.put("status",             task.status);
        data.put("year",               task.year);
        data.put("month",              task.month);
        data.put("day",                task.day);
        data.put("recurrence",         task.recurrence);
        data.put("recurrenceGroupId",  task.recurrenceGroupId);
        data.put("assignedTo",         task.assignedTo != null ? task.assignedTo : new ArrayList<String>());
        data.put("assignedBy",         task.assignedBy != null ? task.assignedBy : "");
        data.put("extensionMinutes",   task.extensionMinutes);
        data.put("workWindowMinutes",  task.workWindowMinutes);
        data.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        return data;
    }

    private void updateTaskStatus(Task task) {
        if (task.firestoreId == null) return;
        db.collection("farm_data").document("shared")
                .collection("tasks").document(task.firestoreId)
                .update("status", task.status, "extensionMinutes", task.extensionMinutes, "workWindowMinutes", task.workWindowMinutes)
                .addOnSuccessListener(unused -> logTaskHistory(task, task.status))
                .addOnFailureListener(e ->
                        Toast.makeText(this, getString(R.string.error_updating_status, e.getMessage()), Toast.LENGTH_SHORT).show());
    }

    // ── Auth guard ──────────────────────────────────────────────────────────
    // signInAnonymously() in WajeApplication is async. If the Activity opens
    // before it completes, currentUser is still null and every Firestore write
    // fails with PERMISSION_DENIED. This helper re-triggers sign-in right
    // before any write and only runs the action once auth is confirmed.
    private void ensureAuthThenRun(Runnable action) {
        com.google.firebase.auth.FirebaseAuth auth = com.google.firebase.auth.FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            action.run();
        } else {
            auth.signInAnonymously()
                    .addOnSuccessListener(result -> action.run())
                    .addOnFailureListener(e ->
                            Toast.makeText(this,
                                    "Auth error – please check your connection and try again.",
                                    Toast.LENGTH_LONG).show());
        }
    }
    // ───────────────────────────────────────────────────────────────────────

    // ───────────────────────────────────────────────────────────────────────
    // Activity Logs — Deleted / Schedules
    // Records every schedule deletion (single, recurring series, or bulk) into
    // the shared "activity_logs" collection so it appears under the "Deleted"
    // section's "Schedules" category in the web Activity Logs, alongside
    // Inventory and Staff deletions. Logging never blocks the deletion itself.
    // ───────────────────────────────────────────────────────────────────────
    private void logScheduleDeletion(String message, String details, java.util.Map<String, Object> metadata) {
        String actorName  = accountManager != null ? accountManager.getCurrentUsername() : null;
        if (actorName == null || actorName.isEmpty()) actorName = currentUserEmail;
        String actorEmail = accountManager != null && actorName != null ? accountManager.getEmail(actorName) : null;
        String actorRole  = accountManager != null && actorName != null ? accountManager.getRole(actorName) : "";

        FarmRepository.INSTANCE.logDeletion(
                "Schedules",
                message,
                actorName != null ? actorName : "User",
                actorEmail != null ? actorEmail : "",
                actorRole != null ? actorRole : "",
                details,
                metadata,
                null
        );
    }

    private void deleteTaskFromFirestore(Task task) {
        if (task.firestoreId == null) return;
        cancelNotification(task); // cancel alarm & dismiss any live notification
        ensureAuthThenRun(() ->
                db.collection("farm_data").document("shared")
                        .collection("tasks").document(task.firestoreId)
                        .delete()
                        .addOnSuccessListener(unused -> {
                            java.util.Map<String, Object> metadata = new HashMap<>();
                            metadata.put("taskId", task.firestoreId);
                            metadata.put("taskTitle", task.title);
                            String actor = accountManager != null ? accountManager.getCurrentUsername() : null;
                            logScheduleDeletion(
                                    (actor != null ? actor : "Someone") + " deleted scheduled task \"" + task.title + "\"",
                                    "Removed schedule: " + task.title,
                                    metadata
                            );
                        })
                        .addOnFailureListener(e ->
                                Toast.makeText(this, getString(R.string.error_deleting, e.getMessage()), Toast.LENGTH_SHORT).show())
        );
    }

    private void deleteRecurringSeriesFromFirestore(Task task) {
        if (task.recurrenceGroupId == null) { deleteTaskFromFirestore(task); return; }
        ensureAuthThenRun(() ->
                db.collection("farm_data").document("shared").collection("tasks")
                        .whereEqualTo("recurrenceGroupId", task.recurrenceGroupId)
                        .get()
                        .addOnSuccessListener(querySnapshot -> {
                            com.google.firebase.firestore.WriteBatch batch = db.batch();
                            int count = 0;
                            for (QueryDocumentSnapshot doc : querySnapshot) {
                                // Reconstruct a minimal Task so we can cancel each alarm.
                                try {
                                    Task t = new Task(
                                            doc.getId(),
                                            doc.getString("title"),
                                            doc.getString("category"),
                                            doc.getString("time"),
                                            doc.getString("status"),
                                            doc.getLong("year") != null  ? doc.getLong("year").intValue()  : 0,
                                            doc.getLong("month") != null ? doc.getLong("month").intValue() : 0,
                                            doc.getLong("day") != null   ? doc.getLong("day").intValue()   : 0,
                                            doc.getString("recurrence"),
                                            doc.getString("recurrenceGroupId")
                                    );
                                    cancelNotification(t);
                                } catch (Exception ignored) { }
                                batch.delete(doc.getReference());
                                count++;
                            }
                            final int deletedCount = count;
                            batch.commit()
                                    .addOnSuccessListener(unused -> {
                                        Toast.makeText(this, getString(R.string.all_recurring_deleted), Toast.LENGTH_SHORT).show();
                                        java.util.Map<String, Object> metadata = new HashMap<>();
                                        metadata.put("recurrenceGroupId", task.recurrenceGroupId);
                                        metadata.put("taskTitle", task.title);
                                        metadata.put("count", deletedCount);
                                        String actor = accountManager != null ? accountManager.getCurrentUsername() : null;
                                        logScheduleDeletion(
                                                (actor != null ? actor : "Someone") + " deleted the recurring series \"" + task.title + "\" (" + deletedCount + " tasks)",
                                                "Removed recurring schedule series: " + task.title,
                                                metadata
                                        );
                                    })
                                    .addOnFailureListener(e ->
                                            Toast.makeText(this, getString(R.string.error_deleting, e.getMessage()), Toast.LENGTH_SHORT).show());
                        })
        );
    }

    private void bulkDeleteFromFirestore(List<Task> tasksToDelete) {
        for (Task task : tasksToDelete) {
            cancelNotification(task); // cancel alarm & dismiss any live notification
        }
        ensureAuthThenRun(() -> {
            com.google.firebase.firestore.WriteBatch batch = db.batch();
            for (Task task : tasksToDelete) {
                if (task.firestoreId != null) {
                    batch.delete(db.collection("farm_data").document("shared")
                            .collection("tasks").document(task.firestoreId));
                }
            }
            batch.commit()
                    .addOnSuccessListener(unused -> {
                        Toast.makeText(this, getString(R.string.selected_schedules_deleted), Toast.LENGTH_SHORT).show();
                        java.util.Map<String, Object> metadata = new HashMap<>();
                        metadata.put("count", tasksToDelete.size());
                        String actor = accountManager != null ? accountManager.getCurrentUsername() : null;
                        logScheduleDeletion(
                                (actor != null ? actor : "Someone") + " deleted " + tasksToDelete.size() + " scheduled task(s)",
                                "Bulk-deleted schedules",
                                metadata
                        );
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, getString(R.string.error_deleting_tasks, e.getMessage()), Toast.LENGTH_SHORT).show());
        });
    }

    private void showAddScheduleDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_schedule, null);

        EditText    editTaskTitle        = dialogView.findViewById(R.id.editTaskTitle);
        Spinner     spinnerCategory      = dialogView.findViewById(R.id.spinnerCategory);
        TextView    textTime             = dialogView.findViewById(R.id.textTime);
        // Work window is now a Spinner (drop-down) offering fixed selections from 30 minutes to 2 hours
        Spinner     spinnerWorkWindow    = dialogView.findViewById(R.id.spinnerWorkWindow);
        TextView    txtCurrentMonth      = dialogView.findViewById(R.id.txtCurrentMonth);
        GridLayout  calendarGrid         = dialogView.findViewById(R.id.calendarGrid);
        ImageButton btnPrevMonth         = dialogView.findViewById(R.id.btnPrevMonth);
        ImageButton btnNextMonth         = dialogView.findViewById(R.id.btnNextMonth);
        Button      btnWeekdays          = dialogView.findViewById(R.id.btnWeekdays);
        Button      btnWeekends          = dialogView.findViewById(R.id.btnWeekends);
        Button      btnFullMonth         = dialogView.findViewById(R.id.btnFullMonth);
        Button      btnFullYear          = dialogView.findViewById(R.id.btnFullYear);
        Button      btnClearSelection    = dialogView.findViewById(R.id.btnClearSelection);
        TextView    txtSummary           = dialogView.findViewById(R.id.txtScheduleSummary);
        TextView    txtPatternSuggestion = dialogView.findViewById(R.id.txtPatternSuggestion);

        String[] categories = getResources().getStringArray(R.array.task_categories);
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(catAdapter);

        // Populate work window spinner with friendly labels and corresponding minute values
        final String[] workWindowLabels = new String[]{"30 minutes","45 minutes","60 minutes","75 minutes","90 minutes","105 minutes","120 minutes"};
        final int[] workWindowValues = new int[]{30,45,60,75,90,105,120};
        ArrayAdapter<String> wwAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, workWindowLabels);
        wwAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerWorkWindow.setAdapter(wwAdapter);
        // Set initial selection to match category default and update when category changes
        int defaultMinutes = getDefaultWorkWindow(spinnerCategory.getSelectedItem().toString());
        // find index
        int defaultIndex = 0;
        for (int i = 0; i < workWindowValues.length; i++) if (workWindowValues[i] == defaultMinutes) { defaultIndex = i; break; }
        spinnerWorkWindow.setSelection(defaultIndex);

        spinnerCategory.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String cat = parent.getItemAtPosition(position).toString();
                int def = getDefaultWorkWindow(cat);
                for (int k = 0; k < workWindowValues.length; k++) {
                    if (workWindowValues[k] == def) { spinnerWorkWindow.setSelection(k); break; }
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        final String[] selectedTime = {"08:00 AM"};
        final String[] selectedRecurrence = {RECUR_ONCE};
        final int[] selHour   = {8};
        final int[] selMinute = {0};
        textTime.setOnClickListener(v -> {
            // Create custom dialog with TimePicker to validate work hours (6 AM - 8 PM)
            android.widget.TimePicker timePicker = new android.widget.TimePicker(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                timePicker.setHour(selHour[0]);
                timePicker.setMinute(selMinute[0]);
            } else {
                timePicker.setCurrentHour(selHour[0]);
                timePicker.setCurrentMinute(selMinute[0]);
            }

            AlertDialog timeDialog = new AlertDialog.Builder(this)
                    .setTitle("Select Time (6 AM - 8 PM)")
                    .setView(timePicker)
                    .setPositiveButton("OK", null)  // Will override onClick
                    .setNegativeButton("Cancel", null)
                    .create();

            // Override positive button to validate work hours
            timeDialog.setOnShowListener(dialog -> {
                android.widget.Button okBtn = timeDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (okBtn != null) {
                    okBtn.setOnClickListener(btn -> {
                        int hour, minute;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            hour = timePicker.getHour();
                            minute = timePicker.getMinute();
                        } else {
                            hour = timePicker.getCurrentHour();
                            minute = timePicker.getCurrentMinute();
                        }

                        // Validate: work hours 6 AM (hour 6) to 8 PM (hour 20)
                        if (hour < 6 || hour > 20) {
                            Toast.makeText(this, "Please choose a time between 6:00 AM and 8:00 PM", Toast.LENGTH_SHORT).show();
                            return;  // Keep dialog open
                        }

                        // Accept selection
                        selHour[0] = hour;
                        selMinute[0] = minute;
                        String amPm = (hour < 12) ? "AM" : "PM";
                        int h = (hour > 12) ? hour - 12 : (hour == 0 ? 12 : hour);
                        selectedTime[0] = String.format(Locale.getDefault(), "%02d:%02d %s", h, minute, amPm);
                        textTime.setText(selectedTime[0]);
                        timeDialog.dismiss();
                    });
                }
            });

            timeDialog.show();
        });

        final List<Long> selectedDates = new ArrayList<>();
        final Calendar   viewCalendar  = Calendar.getInstance();
        viewCalendar.set(Calendar.DAY_OF_MONTH, 1);
        viewCalendar.set(Calendar.HOUR_OF_DAY, 0); viewCalendar.set(Calendar.MINUTE, 0);
        viewCalendar.set(Calendar.SECOND, 0); viewCalendar.set(Calendar.MILLISECOND, 0);

        final int[] patternGap = {0};

        Runnable updateGrid = new Runnable() {
            @Override
            public void run() {
                calendarGrid.removeAllViews();
                txtCurrentMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(viewCalendar.getTime()));
                txtSummary.setText(getString(R.string.total_dates_selected, selectedDates.size()));

                if (selectedDates.size() >= 2) {
                    List<Long> sorted = new ArrayList<>(selectedDates);
                    Collections.sort(sorted);
                    long diff = sorted.get(1) - sorted.get(0);
                    int days = (int) (diff / (1000 * 60 * 60 * 24));
                    // Only show pattern suggestion when gap is greater than 1 day.
                    // A gap of 1 day is equivalent to daily and the suggestion is redundant.
                    if (days > 1) {
                        patternGap[0] = days;
                        txtPatternSuggestion.setVisibility(View.VISIBLE);
                        txtPatternSuggestion.setText(getString(R.string.repeat_every_days_suggestion, days));
                    } else {
                        // hide suggestion for 1-day gap (and for non-positive gaps)
                        txtPatternSuggestion.setVisibility(View.GONE);
                        patternGap[0] = days;
                    }
                } else txtPatternSuggestion.setVisibility(View.GONE);

                String[] daysHeaders = {
                        getString(R.string.Sunday).substring(0, 1),
                        getString(R.string.Monday).substring(0, 1),
                        getString(R.string.Tuesday).substring(0, 1),
                        getString(R.string.Wednesday).substring(0, 1),
                        getString(R.string.Thursday).substring(0, 2),
                        getString(R.string.Friday).substring(0, 1),
                        getString(R.string.Saturday).substring(0, 1)
                };
                for (String d : daysHeaders)
                    calendarGrid.addView(makeHeaderCell(d));

                Calendar cal = (Calendar) viewCalendar.clone();
                int firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1;
                for (int i = 0; i < firstDow; i++) calendarGrid.addView(makeSpacer());

                int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                // compute today's midnight to prevent selecting previous dates
                Calendar todayMid = Calendar.getInstance();
                todayMid.set(Calendar.HOUR_OF_DAY, 0); todayMid.set(Calendar.MINUTE, 0); todayMid.set(Calendar.SECOND, 0); todayMid.set(Calendar.MILLISECOND, 0);
                long todayKey = todayMid.getTimeInMillis();

                for (int i = 1; i <= daysInMonth; i++) {
                    final int day = i;
                    final int month = cal.get(Calendar.MONTH);
                    final int year = cal.get(Calendar.YEAR);
                    Calendar dateCal = Calendar.getInstance();
                    dateCal.set(year, month, day, 0, 0, 0);
                    dateCal.set(Calendar.MILLISECOND, 0);
                    final long dateKey = dateCal.getTimeInMillis();

                    TextView tv = makeDayCell(String.valueOf(i));

                    // If date is in the past, disable selection and dim it
                    if (dateKey < todayKey) {
                        // Remove any previously selected past date
                        if (selectedDates.contains(dateKey)) selectedDates.remove(dateKey);
                        tv.setAlpha(0.35f);
                        tv.setEnabled(false);
                        tv.setTextColor(Color.parseColor("#9CA3AF"));
                    } else {
                        if (selectedDates.contains(dateKey)) {
                            tv.setBackgroundResource(R.drawable.bg_dayselected);
                            tv.setTextColor(Color.WHITE);
                        }
                        tv.setOnClickListener(v -> {
                            if (selectedDates.contains(dateKey)) {
                                selectedDates.remove(dateKey);
                                if (selectedDates.isEmpty()) selectedRecurrence[0] = RECUR_ONCE;
                                else if (selectedDates.size() == 1) selectedRecurrence[0] = RECUR_ONCE;
                                else selectedRecurrence[0] = getString(R.string.recur_custom);
                            } else {
                                selectedDates.add(dateKey);
                                selectedRecurrence[0] = selectedDates.size() > 1 ? getString(R.string.recur_custom) : RECUR_ONCE;
                            }
                            run();
                        });
                    }
                    calendarGrid.addView(tv);
                }
            }
        };

        btnWeekdays.setOnClickListener(v -> {
            selectedRecurrence[0] = getString(R.string.recur_weekdays);
            DatePickerDialog dp = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, 1, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= days; i++) {
                    cal.set(Calendar.DAY_OF_MONTH, i);
                    int dow = cal.get(Calendar.DAY_OF_WEEK);
                    if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                        long key = cal.getTimeInMillis();
                        if (!selectedDates.contains(key)) selectedDates.add(key);
                    }
                }
                updateGrid.run();
            }, viewCalendar.get(Calendar.YEAR), viewCalendar.get(Calendar.MONTH), 1);
            // Prevent selecting dates before today
            Calendar min = Calendar.getInstance();
            min.set(Calendar.HOUR_OF_DAY, 0); min.set(Calendar.MINUTE, 0); min.set(Calendar.SECOND, 0); min.set(Calendar.MILLISECOND, 0);
            dp.getDatePicker().setMinDate(min.getTimeInMillis());
            dp.show();
        });

        btnWeekends.setOnClickListener(v -> {
            selectedRecurrence[0] = getString(R.string.recur_weekends);
            DatePickerDialog dp = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, 1, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= days; i++) {
                    cal.set(Calendar.DAY_OF_MONTH, i);
                    int dow = cal.get(Calendar.DAY_OF_WEEK);
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                        long key = cal.getTimeInMillis();
                        if (!selectedDates.contains(key)) selectedDates.add(key);
                    }
                }
                updateGrid.run();
            }, viewCalendar.get(Calendar.YEAR), viewCalendar.get(Calendar.MONTH), 1);
            Calendar min2 = Calendar.getInstance();
            min2.set(Calendar.HOUR_OF_DAY, 0); min2.set(Calendar.MINUTE, 0); min2.set(Calendar.SECOND, 0); min2.set(Calendar.MILLISECOND, 0);
            dp.getDatePicker().setMinDate(min2.getTimeInMillis());
            dp.show();
        });

        btnFullMonth.setOnClickListener(v -> {
            selectedRecurrence[0] = getString(R.string.recur_monthly);
            DatePickerDialog dp = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, 1, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                for (int i = 1; i <= days; i++) {
                    cal.set(Calendar.DAY_OF_MONTH, i);
                    long key = cal.getTimeInMillis();
                    if (!selectedDates.contains(key)) selectedDates.add(key);
                }
                updateGrid.run();
            }, viewCalendar.get(Calendar.YEAR), viewCalendar.get(Calendar.MONTH), 1);
            Calendar min3 = Calendar.getInstance();
            min3.set(Calendar.HOUR_OF_DAY, 0); min3.set(Calendar.MINUTE, 0); min3.set(Calendar.SECOND, 0); min3.set(Calendar.MILLISECOND, 0);
            dp.getDatePicker().setMinDate(min3.getTimeInMillis());
            dp.show();
        });

        btnFullYear.setOnClickListener(v -> {
            View yearDialogView = LayoutInflater.from(this).inflate(R.layout.dialog_year_range, null);
            TextView textStartDate = yearDialogView.findViewById(R.id.textStartDate);
            TextView textEndDate = yearDialogView.findViewById(R.id.textEndDate);
            RadioGroup rgFilter = yearDialogView.findViewById(R.id.rgYearFilter);

            final Calendar startCal = Calendar.getInstance();
            final Calendar endCal = Calendar.getInstance();
            endCal.set(Calendar.MONTH, Calendar.DECEMBER);
            endCal.set(Calendar.DAY_OF_MONTH, 31);

            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            textStartDate.setText(df.format(startCal.getTime()));
            textEndDate.setText(df.format(endCal.getTime()));

            textStartDate.setOnClickListener(vStart -> {
                DatePickerDialog dpStart = new DatePickerDialog(this, (view, year, month, day) -> {
                    startCal.set(year, month, day);
                    textStartDate.setText(df.format(startCal.getTime()));
                }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH));
                Calendar minStart = Calendar.getInstance();
                minStart.set(Calendar.HOUR_OF_DAY, 0); minStart.set(Calendar.MINUTE, 0); minStart.set(Calendar.SECOND, 0); minStart.set(Calendar.MILLISECOND, 0);
                dpStart.getDatePicker().setMinDate(minStart.getTimeInMillis());
                dpStart.show();
            });

            textEndDate.setOnClickListener(vEnd -> {
                DatePickerDialog dpEnd = new DatePickerDialog(this, (view, year, month, day) -> {
                    endCal.set(year, month, day);
                    textEndDate.setText(df.format(endCal.getTime()));
                }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH));
                Calendar minEnd = Calendar.getInstance();
                minEnd.set(Calendar.HOUR_OF_DAY, 0); minEnd.set(Calendar.MINUTE, 0); minEnd.set(Calendar.SECOND, 0); minEnd.set(Calendar.MILLISECOND, 0);
                dpEnd.getDatePicker().setMinDate(minEnd.getTimeInMillis());
                dpEnd.show();
            });

            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.select_year_range))
                    .setView(yearDialogView)
                    .setPositiveButton(getString(R.string.confirm), (d, w) -> {
                        try {
                            if (startCal.after(endCal)) { Toast.makeText(this, getString(R.string.start_year_after_end), Toast.LENGTH_SHORT).show(); return; }

                            int checkedId = rgFilter.getCheckedRadioButtonId();
                            if (checkedId == R.id.rbYearlyWeekdays) selectedRecurrence[0] = getString(R.string.recur_yearly_weekdays);
                            else if (checkedId == R.id.rbYearlyWeekends) selectedRecurrence[0] = getString(R.string.recur_yearly_weekends);
                            else selectedRecurrence[0] = getString(R.string.recur_yearly);

                            Calendar cal = (Calendar) startCal.clone();
                            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);

                            while (!cal.after(endCal)) {
                                int dow = cal.get(Calendar.DAY_OF_WEEK);
                                boolean match = true;
                                if (checkedId == R.id.rbYearlyWeekdays) {
                                    match = (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY);
                                } else if (checkedId == R.id.rbYearlyWeekends) {
                                    match = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY);
                                }

                                if (match) {
                                    long key = cal.getTimeInMillis();
                                    if (!selectedDates.contains(key)) selectedDates.add(key);
                                }
                                cal.add(Calendar.DAY_OF_MONTH, 1);
                            }
                            updateGrid.run();
                        } catch (Exception e) { Toast.makeText(this, getString(R.string.invalid_year), Toast.LENGTH_SHORT).show(); }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        });

        btnClearSelection.setOnClickListener(v -> { selectedDates.clear(); selectedRecurrence[0] = RECUR_ONCE; updateGrid.run(); });

        txtPatternSuggestion.setOnClickListener(v -> {
            if (selectedDates.size() < 2) return;
            if (patternGap[0] <= 1) return; // ignore clicks when gap is 1 (daily) or invalid
            List<Long> sorted = new ArrayList<>(selectedDates);
            Collections.sort(sorted);
            Calendar cur = Calendar.getInstance();
            cur.setTimeInMillis(sorted.get(0));
            int yearLimit = cur.get(Calendar.YEAR) + 1;
            while (cur.get(Calendar.YEAR) <= yearLimit) {
                long key = cur.getTimeInMillis();
                if (!selectedDates.contains(key)) selectedDates.add(key);
                cur.add(Calendar.DAY_OF_MONTH, patternGap[0]);
            }
            selectedRecurrence[0] = getString(R.string.recur_every_days, patternGap[0]);
            updateGrid.run();
        });

        btnPrevMonth.setOnClickListener(v -> { viewCalendar.add(Calendar.MONTH, -1); updateGrid.run(); });
        btnNextMonth.setOnClickListener(v -> { viewCalendar.add(Calendar.MONTH,  1); updateGrid.run(); });
        updateGrid.run();

        // Assignee multi-select — populate with approved staff from user_access.
        // Tapping the field opens a checkbox list so more than one staff member
        // can be assigned to the same task (the old Spinner only ever allowed one).
        TextView assigneeSelector = dialogView.findViewById(R.id.assigneeSelector);
        final List<String> assigneeEmails = new ArrayList<>();   // all available staff emails (no placeholder row)
        final List<String> assigneeDisplay = new ArrayList<>();  // matching display names
        final List<String> selectedAssigneeEmails = new ArrayList<>(); // currently checked emails

        Runnable refreshAssigneeLabel = () -> {
            if (selectedAssigneeEmails.isEmpty()) {
                assigneeSelector.setText("(No specific staff)");
                assigneeSelector.setTextColor(Color.parseColor("#9CA3AF")); // gray placeholder, matches unset hint style
                return;
            }
            List<String> names = new ArrayList<>();
            for (String email : selectedAssigneeEmails) {
                int idx = assigneeEmails.indexOf(email);
                names.add((idx >= 0) ? assigneeDisplay.get(idx) : getString(R.string.unnamed_staff));
            }
            String summary = selectedAssigneeEmails.size() + " staff selected: " + android.text.TextUtils.join(", ", names);
            assigneeSelector.setText(summary);
            assigneeSelector.setTextColor(Color.parseColor("#111827")); // darker once filled in, matches other field text
        };
        refreshAssigneeLabel.run();

        assigneeSelector.setOnClickListener(v -> {
            if (assigneeDisplay.isEmpty()) {
                Toast.makeText(this, "No approved staff available", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence[] items = assigneeDisplay.toArray(new CharSequence[0]);
            boolean[] checked = new boolean[assigneeEmails.size()];
            for (int i = 0; i < assigneeEmails.size(); i++) {
                checked[i] = selectedAssigneeEmails.contains(assigneeEmails.get(i));
            }
            // Work on a temp copy so Cancel doesn't mutate the real selection
            final List<String> tempSelected = new ArrayList<>(selectedAssigneeEmails);

            new AlertDialog.Builder(this)
                    .setTitle("Assign Staff")
                    .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
                        String email = assigneeEmails.get(which);
                        if (isChecked) {
                            if (!tempSelected.contains(email)) tempSelected.add(email);
                        } else {
                            tempSelected.remove(email);
                        }
                    })
                    .setPositiveButton("Done", (dialog, which) -> {
                        selectedAssigneeEmails.clear();
                        selectedAssigneeEmails.addAll(tempSelected);
                        refreshAssigneeLabel.run();
                    })
                    .setNeutralButton("Clear", (dialog, which) -> {
                        selectedAssigneeEmails.clear();
                        refreshAssigneeLabel.run();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        db.collection("user_access")
                .whereEqualTo("role", "staff")
                .whereEqualTo("status", "approved")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    assigneeDisplay.clear();
                    assigneeEmails.clear();
                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        String email = doc.getId();
                        String name = doc.getString("name");
                        if (email == null) continue;
                        assigneeEmails.add(email);
                        assigneeDisplay.add((name != null && !name.isEmpty()) ? name : getString(R.string.unnamed_staff));
                    }
                    refreshAssigneeLabel.run();
                })
                .addOnFailureListener(e -> { /* ignore and keep default */ });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.add_new_task))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.schedule), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = editTaskTitle.getText().toString().trim();
            String category = spinnerCategory.getSelectedItem().toString();
            // Read selected work window minutes from spinner; fallback to category default if none selected
            int selPos = spinnerWorkWindow.getSelectedItemPosition();
            int window = (selPos >= 0 && selPos < workWindowValues.length) ? workWindowValues[selPos] : getDefaultWorkWindow(category);

            if (title.isEmpty()) { Toast.makeText(this, getString(R.string.task_title_empty), Toast.LENGTH_SHORT).show(); return; }
            if (selectedDates.isEmpty()) { Toast.makeText(this, getString(R.string.please_select_date), Toast.LENGTH_SHORT).show(); return; }

            View previewView = LayoutInflater.from(this).inflate(R.layout.dialog_schedule_preview, null);
            ((TextView) previewView.findViewById(R.id.previewTitle)).setText(title);
            ((TextView) previewView.findViewById(R.id.previewCategory)).setText(category);
            ((TextView) previewView.findViewById(R.id.previewTime)).setText(selectedTime[0]);
            ((TextView) previewView.findViewById(R.id.previewTotalDates)).setText(selectedDates.size() + " " + getString(R.string.days_unit) + " (" + selectedRecurrence[0] + ")");

            new AlertDialog.Builder(this)
                    .setView(previewView)
                    .setPositiveButton(getString(R.string.confirm_and_save), (dConfirm, wConfirm) -> {
                        String groupId = UUID.randomUUID().toString();

                        AlertDialog progress = new AlertDialog.Builder(this)
                                .setMessage(getString(R.string.scheduling_tasks))
                                .setCancelable(false)
                                .show();

                        int batchSize = 400;
                        int totalTasks = selectedDates.size();
                        final int[] completedBatches = {0};
                        int numBatches = (totalTasks + batchSize - 1) / batchSize;

                        ensureAuthThenRun(() -> {
                            for (int i = 0; i < totalTasks; i += batchSize) {
                                com.google.firebase.firestore.WriteBatch batch = db.batch();
                                int end = Math.min(i + batchSize, totalTasks);

                                for (int j = i; j < end; j++) {
                                    Long time = selectedDates.get(j);
                                    Calendar cal = Calendar.getInstance();
                                    cal.setTimeInMillis(time);

                                    DocumentReference ref = db.collection("farm_data").document("shared")
                                            .collection("tasks").document();

                                    Task t = new Task(ref.getId(), title, category, selectedTime[0], "Pending",
                                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
                                            selectedRecurrence[0], groupId);
                                    t.workWindowMinutes = window;
                                    t.assignedTo = new ArrayList<>(selectedAssigneeEmails);
                                    t.assignedBy = currentUserEmail;

                                    batch.set(ref, buildTaskMap(t));
                                    scheduleNotification(t, selHour[0], selMinute[0]);
                                }

                                batch.commit().addOnCompleteListener(taskResult -> {
                                    completedBatches[0]++;
                                    if (completedBatches[0] >= numBatches) {
                                        progress.dismiss();
                                        Toast.makeText(this, getString(R.string.tasks_scheduled, totalTasks), Toast.LENGTH_SHORT).show();
                                        dialog.dismiss();
                                    }
                                }).addOnFailureListener(e -> {
                                    progress.dismiss();
                                    Toast.makeText(this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                    })
                    .setNegativeButton(getString(R.string.back), null)
                    .show();
        });
    }

    private int getDefaultWorkWindow(String category) {
        switch (category) {
            case "Cleaning": return 120; // 2 hours
            case "Egg Collection": return 90; // 1.5 hours
            case "Health Check": return 90;
            case "Feeding": return 60;
            case "Watering": return 45;
            default: return 60;
        }
    }

    private void showDeleteOptions(Task task) {
        boolean isRecurring = task.recurrenceGroupId != null && !RECUR_ONCE.equals(task.recurrence);
        if (!isRecurring) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.delete_task_title)).setMessage(getString(R.string.delete_task_msg)).setPositiveButton(getString(R.string.delete), (d, w) -> deleteTaskFromFirestore(task)).setNegativeButton(getString(R.string.cancel), null).show();
        } else {
            new AlertDialog.Builder(this).setTitle(getString(R.string.delete_recurring_title)).setItems(new String[]{getString(R.string.delete_this_only), getString(R.string.delete_all_series)}, (d, which) -> {
                if (which == 0) deleteTaskFromFirestore(task);
                else deleteRecurringSeriesFromFirestore(task);
            }).setNegativeButton(getString(R.string.cancel), null).show();
        }
    }

    private void showBulkDeleteDialog() {
        if (taskList.isEmpty()) { Toast.makeText(this, getString(R.string.no_tasks_to_delete), Toast.LENGTH_SHORT).show(); return; }

        Map<String, List<Task>> groups = new LinkedHashMap<>();
        for (Task task : taskList) {
            String key = task.recurrenceGroupId;
            if (key == null || key.isEmpty()) key = "SINGLE_" + task.firestoreId;
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(task);
        }

        List<String> groupKeys = new ArrayList<>(groups.keySet());
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);

        Map<String, CheckBox> checkBoxes = new HashMap<>();

        for (String key : groupKeys) {
            List<Task> groupTasks = groups.get(key);
            Task first = groupTasks.get(0);

            View itemView = getLayoutInflater().inflate(R.layout.item_bulk_delete, container, false);
            CheckBox cb = itemView.findViewById(R.id.checkDelete);
            TextView title = itemView.findViewById(R.id.bulkTaskTitle);
            TextView info = itemView.findViewById(R.id.bulkTaskInfo);
            TextView time = itemView.findViewById(R.id.bulkTaskTime);
            View indicator = itemView.findViewById(R.id.bulkStatusIndicator);

            title.setText(first.title);
            String infoText = first.category + " | " + (groupTasks.size() > 1 ? groupTasks.size() + " " + getString(R.string.days_unit) : first.day + " " + monthNames[first.month]);
            info.setText(infoText);
            time.setText(first.time);

            if (getString(R.string.status_done).equals(first.status)) indicator.setBackgroundResource(R.drawable.bg_status_done);
            else if (getString(R.string.status_ongoing).equals(first.status)) indicator.setBackgroundResource(R.drawable.bg_status_ongoing);
            else indicator.setBackgroundResource(R.drawable.bg_status_pending);

            checkBoxes.put(key, cb);
            container.addView(itemView);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(container);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_schedules_delete))
                .setView(scrollView)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    List<Task> toDelete = new ArrayList<>();
                    for (Map.Entry<String, CheckBox> entry : checkBoxes.entrySet()) {
                        if (entry.getValue().isChecked()) {
                            toDelete.addAll(groups.get(entry.getKey()));
                        }
                    }
                    if (!toDelete.isEmpty()) bulkDeleteFromFirestore(toDelete);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showAllTaskDetails() {
        if (taskList.isEmpty()) { Toast.makeText(this, getString(R.string.no_tasks_assigned), Toast.LENGTH_SHORT).show(); return; }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_all_task_details, null);
        LinearLayout container = dialogView.findViewById(R.id.allTasksContainer);

        Map<String, List<Task>> groups = new LinkedHashMap<>();
        for (Task task : taskList) {
            String key = task.recurrenceGroupId;
            if (key == null || key.isEmpty()) {
                key = "SINGLE_" + task.firestoreId;
            }
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
            }
            groups.get(key).add(task);
        }

        int idCounter = 1;
        for (Map.Entry<String, List<Task>> entry : groups.entrySet()) {
            List<Task> groupTasks = entry.getValue();
            if (groupTasks.isEmpty()) continue;

            Task first = groupTasks.get(0);
            View rowView = getLayoutInflater().inflate(R.layout.item_task_all_details, container, false);

            TextView idTv = rowView.findViewById(R.id.rowId);
            View statusIndicator = rowView.findViewById(R.id.rowStatusIndicator);
            TextView titleTv = rowView.findViewById(R.id.rowTitle);
            TextView categoryTv = rowView.findViewById(R.id.rowCategory);
            TextView dateInfoTv = rowView.findViewById(R.id.rowDateInfo);
            TextView timeTv = rowView.findViewById(R.id.rowTime);
            TextView statusTextTv = rowView.findViewById(R.id.rowStatusText);

            idTv.setText(String.valueOf(idCounter++));
            idTv.setOnClickListener(v -> showTaskGroupDetailsDialog(groupTasks));

            titleTv.setText(first.title);
            categoryTv.setText(first.category);
            timeTv.setText(first.time);
            statusTextTv.setText(first.status);

            if (groupTasks.size() > 1) {
                dateInfoTv.setText(first.recurrence + " " + first.category + " (" + groupTasks.size() + " " + getString(R.string.days_unit) + ")");
            } else {
                dateInfoTv.setText(first.day + " " + monthNames[first.month] + " " + first.year + " (" + first.category + ")");
            }

            if (getString(R.string.status_done).equals(first.status)) statusIndicator.setBackgroundResource(R.drawable.bg_status_done);
            else if (getString(R.string.status_ongoing).equals(first.status)) statusIndicator.setBackgroundResource(R.drawable.bg_status_ongoing);
            else statusIndicator.setBackgroundResource(R.drawable.bg_status_pending);

            container.addView(rowView);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.all_assigned_tasks))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    private void showTaskGroupDetailsDialog(List<Task> tasks) {
        Collections.sort(tasks, (a, b) -> {
            if (a.year != b.year) return a.year - b.year;
            if (a.month != b.month) return a.month - b.month;
            return a.day - b.day;
        });

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_task_group_details, null);
        TextView titleTv = view.findViewById(R.id.detailTaskTitle);
        TextView categoryTv = view.findViewById(R.id.detailTaskCategory);
        TextView timeTv = view.findViewById(R.id.detailTaskTime);
        LinearLayout datesContainer = view.findViewById(R.id.datesContainer);

        Task first = tasks.get(0);
        titleTv.setText(first.title);
        categoryTv.setText(getString(R.string.task_category_label, first.category));
        timeTv.setText(getString(R.string.task_time_label, first.time));

        for (Task t : tasks) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 12, 0, 12);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView dateTv = new TextView(this);
            dateTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            dateTv.setText(t.day + " " + monthNames[t.month] + " " + t.year);
            dateTv.setTextColor(Color.BLACK);
            dateTv.setTextSize(15);

            TextView statusTv = new TextView(this);
            statusTv.setText(t.status);
            statusTv.setPadding(12, 4, 12, 4);
            statusTv.setTextSize(12);
            statusTv.setTypeface(null, Typeface.BOLD);
            statusTv.setTextColor(Color.WHITE);

            if (getString(R.string.status_done).equals(t.status)) statusTv.setBackgroundResource(R.drawable.bg_status_done);
            else if (getString(R.string.status_ongoing).equals(t.status)) statusTv.setBackgroundResource(R.drawable.bg_status_ongoing);
            else statusTv.setBackgroundResource(R.drawable.bg_status_pending);

            row.addView(dateTv);
            row.addView(statusTv);
            datesContainer.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(Color.parseColor("#F3F4F6"));
            datesContainer.addView(divider);
        }

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(getString(R.string.close), null)
                .show();
    }

    private void updateTasksUI() {
        if (tasksContainer == null) return;
        tasksContainer.removeAllViews();
        int done = 0, ongoing = 0, pending = 0;
        int selYear  = selectedDate.get(Calendar.YEAR);
        int selMonth = selectedDate.get(Calendar.MONTH);
        int selDay   = selectedDate.get(Calendar.DAY_OF_MONTH);

        Map<String, List<Task>> categoryGroups = new TreeMap<>();
        boolean isOwner = roleManager.isOwner();
        for (Task task : taskList) {
            if (task.year != selYear || task.month != selMonth || task.day != selDay) continue;
            // Visibility: owner sees ALL tasks; staff only sees tasks they are assigned to
            if (!isOwner) {
                boolean assignedToMe = false;
                if (task.assignedTo != null) {
                    for (String email : task.assignedTo) {
                        if (email != null && email.equalsIgnoreCase(currentUserEmail)) { assignedToMe = true; break; }
                    }
                }
                if (!assignedToMe) continue;
            }

            // Determine current status (auto-updated for non-Done tasks)
            String sDone = getString(R.string.status_done);
            String sOngoing = getString(R.string.status_ongoing);
            String sMissed = getString(R.string.status_missed);
            String sPending = getString(R.string.status_pending);

            if (!sDone.equals(task.status)) {
                String previousStatus = task.status;
                task.status = getAutoStatus(task);
                // Missed is derived client-side and never written back to the
                // task document, so log the transition once here (it would
                // otherwise never reach updateTaskStatus()).
                if (sMissed.equals(task.status) && !sMissed.equals(previousStatus)
                        && task.firestoreId != null && loggedMissedTaskIds.add(task.firestoreId)) {
                    logTaskHistory(task, sMissed);
                }
            }

            // Update counts (reflects all tasks for the day)
            if (sDone.equals(task.status)) done++;
            else if (sOngoing.equals(task.status)) ongoing++;
            else pending++;

            // Apply active filter: ASSIGNED -> pending or ongoing, MISSING -> missed, DONE -> done
            boolean includeByFilter = true;
            if (FILTER_ASSIGNED.equals(activeFilter)) includeByFilter = sPending.equals(task.status) || sOngoing.equals(task.status);
            else if (FILTER_MISSING.equals(activeFilter)) includeByFilter = sMissed.equals(task.status);
            else if (FILTER_DONE.equals(activeFilter)) includeByFilter = sDone.equals(task.status);
            if (!includeByFilter) continue;

            if (!categoryGroups.containsKey(task.category)) {
                categoryGroups.put(task.category, new ArrayList<>());
            }
            categoryGroups.get(task.category).add(task);
        }

        for (Map.Entry<String, List<Task>> entry : categoryGroups.entrySet()) {
            addCategoryHeader(entry.getKey());
            for (Task task : entry.getValue()) {
                View taskView = getLayoutInflater().inflate(R.layout.item_schedule_task, tasksContainer, false);
                TextView titleTv = taskView.findViewById(R.id.taskTitle);
                TextView categoryTv = taskView.findViewById(R.id.taskCategory);
                TextView timeTv = taskView.findViewById(R.id.taskTime);
                TextView deadlineTv = taskView.findViewById(R.id.taskDeadline);
                TextView recurrenceTv = taskView.findViewById(R.id.taskRecurrence);
                TextView assignedToTv = taskView.findViewById(R.id.taskAssignedTo);
                View statusIndicator = taskView.findViewById(R.id.statusIndicator);
                ImageButton deleteBtn = taskView.findViewById(R.id.deleteTaskBtn);
                View deleteBtnCard = taskView.findViewById(R.id.deleteBtnCard);
                ImageView iconView = taskView.findViewById(R.id.taskIcon);
                View iconContainer = taskView.findViewById(R.id.taskIconContainer);
                TextView statusPill = taskView.findViewById(R.id.statusPill);
                View assignedByRow = taskView.findViewById(R.id.assignedByRow);
                TextView assignedByTv = taskView.findViewById(R.id.taskAssignedBy);

                titleTv.setText(task.title);
                categoryTv.setText(task.category);
                timeTv.setText(task.time);
                if (recurrenceTv != null) recurrenceTv.setText(task.recurrence != null ? task.recurrence : RECUR_ONCE);

                // Assigned-to chip: green when assigned to specific staff, neutral gray
                // when the task is owner-only (no staff assigned).
                boolean hasAssignee = task.assignedTo != null && !task.assignedTo.isEmpty();
                if (assignedToTv != null) {
                    assignedToTv.setText(resolveAssignedToLabel(task));
                    assignedToTv.setBackgroundResource(hasAssignee ? R.drawable.bg_chip_staff : R.drawable.bg_chip_neutral);
                    assignedToTv.setTextColor(Color.parseColor(hasAssignee ? "#16A34A" : "#374151"));
                }

                // Assigned-by row: lets staff see which owner assigned them the task.
                if (assignedByRow != null && assignedByTv != null) {
                    if (!roleManager.isOwner() && task.assignedBy != null && !task.assignedBy.trim().isEmpty()) {
                        assignedByRow.setVisibility(View.VISIBLE);
                        assignedByTv.setText(getString(R.string.assigned_by_format, resolveAssignedByLabel(task)));
                    } else {
                        assignedByRow.setVisibility(View.GONE);
                    }
                }

                // Category icon + tint (purely cosmetic grouping, matches card redesign).
                if (iconView != null && iconContainer != null) {
                    int[] iconStyle = getCategoryIconStyle(task.category);
                    iconView.setImageResource(iconStyle[0]);
                    iconView.setColorFilter(iconStyle[1]);
                    android.graphics.drawable.GradientDrawable circleBg = new android.graphics.drawable.GradientDrawable();
                    circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    circleBg.setColor(iconStyle[2]);
                    iconContainer.setBackground(circleBg);
                }

                boolean isDone = getString(R.string.status_done).equals(task.status);
                boolean isOngoing = getString(R.string.status_ongoing).equals(task.status);
                boolean isMissed = getString(R.string.status_missed).equals(task.status);

                if (isOngoing || isMissed) {
                    deadlineTv.setVisibility(View.VISIBLE);
                    deadlineTv.setText("Deadline: " + calculateDeadlineTime(task));
                } else {
                    deadlineTv.setVisibility(View.GONE);
                }

                // Status pill: light background + matching darker text, mirrors the
                // reference design's "Done" pill instead of the old plain color bar.
                if (statusIndicator != null) statusIndicator.setVisibility(View.GONE);
                if (statusPill != null) {
                    String pillBg, pillText, pillLabel;
                    if (isMissed) {
                        pillBg = "#FEE2E2"; pillText = "#DC2626"; pillLabel = getString(R.string.status_missed);
                    } else if (isDone) {
                        pillBg = "#DCFCE7"; pillText = "#16A34A"; pillLabel = getString(R.string.status_done);
                    } else if (isOngoing) {
                        pillBg = "#DBEAFE"; pillText = "#2563EB"; pillLabel = getString(R.string.status_ongoing);
                    } else {
                        pillBg = "#FFEDD5"; pillText = "#EA580C"; pillLabel = getString(R.string.status_pending);
                    }
                    android.graphics.drawable.GradientDrawable pillBgDrawable = new android.graphics.drawable.GradientDrawable();
                    pillBgDrawable.setColor(Color.parseColor(pillBg));
                    pillBgDrawable.setCornerRadius(20f);
                    statusPill.setBackground(pillBgDrawable);
                    statusPill.setTextColor(Color.parseColor(pillText));
                    statusPill.setText(pillLabel);
                }

                if (isMissed) {
                    titleTv.setText(task.title + " (MISSED)");
                }

                taskView.setOnClickListener(v -> {
                    if (isDone) {
                        Toast.makeText(this, "Task already completed", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (isMissed) {
                        if (roleManager.canEditFarm()) {
                            showManagerOverrideDialog(task);
                        } else {
                            Toast.makeText(this, "Task was missed. Contact manager.", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    if (!isOngoing) {
                        // Manager Work Window dialog removed from here
                        Toast.makeText(this, "Can only update status when Ongoing (at " + task.time + ")", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    showStatusUpdateDialog(task);
                });

                boolean canDelete = roleManager.canDeleteTask();
                deleteBtn.setVisibility(canDelete ? View.VISIBLE : View.GONE);
                if (deleteBtnCard != null) deleteBtnCard.setVisibility(canDelete ? View.VISIBLE : View.GONE);
                if (canDelete) {
                    deleteBtn.setOnClickListener(v -> showDeleteOptions(task));
                }

                tasksContainer.addView(taskView);
            }
        }

        if (doneCount != null) doneCount.setText(String.valueOf(done));
        if (ongoingCount != null) ongoingCount.setText(String.valueOf(ongoing));
        if (pendingCount != null) pendingCount.setText(String.valueOf(pending));
        View placeholder = findViewById(R.id.noTasksPlaceholder);
        if (placeholder != null) placeholder.setVisibility(tasksContainer.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private String calculateDeadlineTime(Task task) {
        Calendar taskCal = Calendar.getInstance();
        try {
            Date date = new SimpleDateFormat("hh:mm a", Locale.getDefault()).parse(task.time);
            Calendar timePart = Calendar.getInstance();
            timePart.setTime(date);
            taskCal.set(task.year, task.month, task.day, timePart.get(Calendar.HOUR_OF_DAY), timePart.get(Calendar.MINUTE), 0);
            taskCal.set(Calendar.MILLISECOND, 0);

            taskCal.add(Calendar.MINUTE, task.workWindowMinutes + task.extensionMinutes);
            return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(taskCal.getTime());
        } catch (ParseException e) {
            return "N/A";
        }
    }

    private String getAutoStatus(Task task) {
        Calendar taskCal = Calendar.getInstance();
        try {
            Date date = new SimpleDateFormat("hh:mm a", Locale.getDefault()).parse(task.time);
            Calendar timePart = Calendar.getInstance();
            timePart.setTime(date);
            taskCal.set(task.year, task.month, task.day, timePart.get(Calendar.HOUR_OF_DAY), timePart.get(Calendar.MINUTE), 0);
            taskCal.set(Calendar.MILLISECOND, 0);
        } catch (ParseException e) {
            return getString(R.string.status_pending);
        }

        Calendar now = Calendar.getInstance();
        if (taskCal.after(now)) return getString(R.string.status_pending);

        int totalWindow = task.workWindowMinutes + task.extensionMinutes;
        Calendar expireCal = (Calendar) taskCal.clone();
        expireCal.add(Calendar.MINUTE, totalWindow);

        if (now.after(taskCal) && now.before(expireCal)) return getString(R.string.status_ongoing);
        return getString(R.string.status_missed);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Activity Logs — Updated / Tasks
    // Records a task deadline extension into the shared "activity_logs"
    // collection so it appears under the web Activity Logs "Updated → Tasks"
    // sub-filter. Covers BOTH extension paths in this Activity:
    //   - "Request 30min Extension" (showStatusUpdateDialog) — owner-only;
    //     staff only see "Mark as Done" for an Ongoing task.
    //   - "Manager Override → Reset to Ongoing" (showManagerOverrideDialog) —
    //     owner-only, only reachable once a task is already Missed.
    // The actor's actual role is recorded on every entry either way, so staff
    // vs. owner extensions are distinguishable in the audit trail even though
    // both funnel through this same logger. Logging never blocks the update.
    // ───────────────────────────────────────────────────────────────────────
    private void logTaskExtension(Task task, int minutesAdded, String reason) {
        String actorName = accountManager != null ? accountManager.getCurrentUsername() : null;
        if (actorName == null || actorName.isEmpty()) actorName = currentUserEmail;
        String actorEmail = accountManager != null && actorName != null ? accountManager.getEmail(actorName) : null;
        String actorRole = accountManager != null && actorName != null ? accountManager.getRole(actorName) : "staff";

        java.util.Map<String, Object> metadata = new HashMap<>();
        metadata.put("taskId", task.firestoreId);
        metadata.put("taskTitle", task.title);
        metadata.put("extensionMinutes", task.extensionMinutes);
        metadata.put("minutesAdded", minutesAdded);

        String durationLabel = minutesAdded >= 60
                ? (minutesAdded / 60) + " hour" + (minutesAdded / 60 == 1 ? "" : "s")
                : minutesAdded + " min";

        FarmRepository.INSTANCE.logTaskUpdated(
                actorName != null ? actorName : "Someone",
                actorEmail != null ? actorEmail : "",
                actorRole != null ? actorRole : "staff",
                (actorName != null ? actorName : "Someone") + " extended " + reason + " task \"" + task.title + "\" by " + durationLabel,
                "New total extension: " + task.extensionMinutes + " min",
                metadata,
                null
        );
    }

    private void showStatusUpdateDialog(Task task) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Update Task Status");

        // Extending the deadline is owner-only; staff only get "Mark as Done".
        boolean canExtend = roleManager.isOwner();
        String[] options = canExtend
                ? new String[]{"Mark as Done", "Request 30min Extension"}
                : new String[]{"Mark as Done"};
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                // Marking Done now requires proof (comment + photo) before anything
                // is written, followed by an explicit confirmation step.
                showMarkDoneProofDialog(task);
            } else if (canExtend) {
                task.extensionMinutes += 30;
                updateTaskStatus(task);
                logTaskExtension(task, 30, "ongoing");
                Toast.makeText(this, "Extension granted. New deadline updated.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
    }

    // ── Mark-as-Done proof flow ──────────────────────────────────────────────
    // Step 1: collect a required comment + required photo.
    // Step 2: explicit confirmation dialog.
    // Step 3: upload photo to Firebase Storage, then write status + proof to Firestore.
    // The task is NEVER marked Done if either the comment or the photo is missing.

    private void showMarkDoneProofDialog(Task task) {
        pendingDoneTask = task;
        pendingProofImageUri = null;
        pendingProofImageFile = null;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        container.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText("Completion proof required");
        label.setTypeface(null, Typeface.BOLD);
        label.setTextSize(16);
        label.setTextColor(Color.parseColor("#111827"));
        container.addView(label);

        TextView sub = new TextView(this);
        sub.setText("Add a comment and take a photo showing the task was completed. Both are required — this task cannot be marked Done without them.");
        sub.setTextColor(Color.parseColor("#6B7280"));
        sub.setTextSize(13);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.setMargins(0, dpToPx(4), 0, dpToPx(16));
        sub.setLayoutParams(subParams);
        container.addView(sub);

        final EditText commentInput = new EditText(this);
        commentInput.setHint("Describe what was done...");
        commentInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        commentInput.setMinLines(3);
        commentInput.setGravity(Gravity.TOP | Gravity.START);
        container.addView(commentInput);

        Button takePhotoBtn = new Button(this);
        takePhotoBtn.setText("Take Photo");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, dpToPx(16), 0, dpToPx(12));
        takePhotoBtn.setLayoutParams(btnParams);
        container.addView(takePhotoBtn);

        final ImageView preview = new ImageView(this);
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(200));
        preview.setLayoutParams(previewParams);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(Color.parseColor("#F3F4F6"));
        container.addView(preview);

        // Let the ActivityResultCallback registered in onCreate() write into this
        // specific preview / URI for the duration of this dialog.
        proofImagePreview = preview;

        takePhotoBtn.setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchProofCamera();
            } else {
                proofCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(container);

        AlertDialog proofDialog = new AlertDialog.Builder(this)
                .setTitle("Mark \"" + task.title + "\" as Done")
                .setView(scroll)
                .setPositiveButton("Continue", null) // overridden below so it can block on validation
                .setNegativeButton("Cancel", (d, w) -> {
                    pendingProofImageUri = null;
                    pendingProofImageFile = null;
                    proofImagePreview = null;
                })
                .create();

        proofDialog.show();
        proofDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String comment = commentInput.getText().toString().trim();
            if (comment.isEmpty()) {
                Toast.makeText(this, "A comment describing the completed work is required", Toast.LENGTH_SHORT).show();
                return; // keep dialog open
            }
            if (pendingProofImageUri == null) {
                Toast.makeText(this, "A photo is required as proof before this task can be marked Done", Toast.LENGTH_SHORT).show();
                return; // keep dialog open
            }
            Uri capturedUri = pendingProofImageUri;
            proofDialog.dismiss();
            showMarkDoneConfirmationDialog(task, comment, capturedUri);
        });
    }

    /** Explicit "are you sure" confirmation before the task is actually marked Done. */
    private void showMarkDoneConfirmationDialog(Task task, String comment, Uri imageUri) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm completion")
                .setMessage("Mark \"" + task.title + "\" as Done?\n\nYour comment and photo will be saved as proof of completion. This action cannot be undone.")
                .setPositiveButton("Confirm", (d, w) -> {
                    AlertDialog progress = new AlertDialog.Builder(this)
                            .setMessage("Saving proof and completing task...")
                            .setCancelable(false)
                            .show();
                    ensureAuthThenRun(() -> uploadProofImageAndFinalize(task, comment, imageUri, progress));
                })
                .setNegativeButton("Back", (d, w) -> showMarkDoneProofDialog(task)) // let them fix the comment/photo instead of losing the flow
                .setCancelable(false)
                .show();
    }

    /** Compresses the proof photo to a Bitmap and writes it (as Base64) plus status + proof fields to Firestore. */
    private void uploadProofImageAndFinalize(Task task, String comment, Uri imageUri, AlertDialog progress) {
        if (task.firestoreId == null) {
            progress.dismiss();
            Toast.makeText(this, "This task has no ID and cannot be updated", Toast.LENGTH_SHORT).show();
            return;
        }

        // Defense-in-depth: re-check right before encoding. The cache file can vanish
        // between capture and this point (low-storage cache eviction, the OS killing
        // the process while the camera app was in front, etc.).
        if (!proofFileHasContent(imageUri)) {
            progress.dismiss();
            Toast.makeText(this, "The proof photo is no longer available. Please retake the photo and try again.", Toast.LENGTH_LONG).show();
            pendingProofImageUri = null;
            pendingProofImageFile = null;
            showMarkDoneProofDialog(task);
            return;
        }

        final File proofFile = pendingProofImageFile;

        // Decoding + downscaling + JPEG compression can take real time on a full-size
        // camera photo, so do it off the main thread and hop back for the Firestore
        // write. This stores the photo directly as Base64 in the task document — no
        // Firebase Storage bucket required.
        Executors.newSingleThreadExecutor().execute(() -> {
            String base64Image;
            try {
                base64Image = encodeProofPhotoAsBase64(proofFile);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Failed to process proof photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }
            runOnUiThread(() -> markTaskDoneInFirestore(task, comment, base64Image, progress));
        });
    }

    /**
     * Downscales the proof photo (longest side capped at PROOF_IMAGE_MAX_DIMENSION),
     * corrects orientation from EXIF, and JPEG-compresses it, backing off quality until
     * the encoded Base64 string comfortably fits inside a single Firestore document
     * (1 MiB total document limit, so a wide margin is left for the other task fields).
     */
    private String encodeProofPhotoAsBase64(File file) throws java.io.IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int sampleSize = 1;
        while ((bounds.outWidth / sampleSize) > PROOF_IMAGE_MAX_DIMENSION
                || (bounds.outHeight / sampleSize) > PROOF_IMAGE_MAX_DIMENSION) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = sampleSize;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOptions);
        if (bitmap == null) throw new java.io.IOException("Could not decode the captured photo");

        int rotationDegrees = readExifRotationDegrees(file);
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            bitmap = rotated;
        }

        int quality = 85;
        byte[] jpegBytes;
        while (true) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            jpegBytes = out.toByteArray();
            if (jpegBytes.length <= PROOF_IMAGE_MAX_BYTES || quality <= 25) break;
            quality -= 15;
        }
        bitmap.recycle();

        if (jpegBytes.length > PROOF_IMAGE_MAX_BYTES) {
            throw new java.io.IOException("Photo is too large to save even after compression");
        }

        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
    }

    /** Reads the EXIF rotation on the captured file so the saved photo isn't sideways. */
    private int readExifRotationDegrees(File file) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Only reachable once a comment and an encoded photo both exist. */
    private void markTaskDoneInFirestore(Task task, String comment, String imageUrl, AlertDialog progress) {
        task.status = getString(R.string.status_done);
        task.doneComment = comment;
        task.doneImageUrl = imageUrl;

        cancelNotification(task); // cancel alarm & dismiss any live notification

        // Remove all related alerts from notification history (Firestore + local cache).
        FarmRepository.INSTANCE.deleteAlertByMessage(task.title, null);
        GlobalData.removeAlertsContaining(task.title);

        db.collection("farm_data").document("shared")
                .collection("tasks").document(task.firestoreId)
                .update("status", task.status,
                        "extensionMinutes", task.extensionMinutes,
                        "workWindowMinutes", task.workWindowMinutes,
                        "doneComment", comment,
                        "doneImageUrl", imageUrl,
                        "doneBy", currentUserEmail,
                        "doneAt", com.google.firebase.firestore.FieldValue.serverTimestamp())
                .addOnSuccessListener(unused -> {
                    progress.dismiss();
                    logTaskHistory(task, task.status);
                    Toast.makeText(this, "Task completed!", Toast.LENGTH_SHORT).show();
                    pendingDoneTask = null;
                    pendingProofImageUri = null;
                    pendingProofImageFile = null;
                    proofImagePreview = null;
                })
                .addOnFailureListener(e -> {
                    progress.dismiss();
                    Toast.makeText(this, getString(R.string.error_updating_status, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
    }

    /** Creates the proof image Uri and starts the camera capture. Only call once CAMERA permission is confirmed granted. */
    private void launchProofCamera() {
        File file = newProofImageFile();
        if (file == null) {
            Toast.makeText(this, "Could not prepare camera storage for the proof photo", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (Exception e) {
            Toast.makeText(this, "Could not prepare camera storage for the proof photo", Toast.LENGTH_SHORT).show();
            return;
        }
        // Some OEM camera apps (MIUI/Xiaomi in particular) don't reliably honor the
        // implicit grant that ActivityResultContracts.TakePicture() adds to the
        // capture Intent, and silently no-op the write to our content:// Uri while
        // still returning RESULT_OK. Explicitly granting write (and read, for the
        // preview) permission to every app that can actually handle the capture
        // intent is the standard workaround for that class of device bug.
        grantProofUriPermissionToCameraApps(uri);
        pendingProofImageUri = uri;
        pendingProofImageFile = file;
        takePictureLauncher.launch(uri);
    }

    /** Explicitly grants URI permissions to camera apps that can handle image capture. */
    private void grantProofUriPermissionToCameraApps(Uri uri) {
        Intent captureIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        java.util.List<android.content.pm.ResolveInfo> resolveInfoList =
                getPackageManager().queryIntentActivities(captureIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        for (android.content.pm.ResolveInfo resolveInfo : resolveInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            grantUriPermission(packageName, uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    /**
     * Returns true only if the content behind {@code uri} actually exists and has
     * at least one byte, i.e. the camera app really wrote a photo to it. Used to
     * catch the "RESULT_OK but nothing was written" failure mode some OEM camera
     * apps exhibit, before it turns into a confusing Firebase Storage error.
     */
    private boolean proofFileHasContent(Uri uri) {
        // Prefer checking the on-disk File directly when we have it — it's the same
        // path the upload itself now reads from, so this check and the actual upload
        // can never disagree about whether the photo is really there.
        if (pendingProofImageFile != null) {
            return pendingProofImageFile.exists() && pendingProofImageFile.length() > 0;
        }
        if (uri == null) return false;
        try (android.content.res.AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return afd != null && afd.getLength() != 0; // getLength() == 0 for genuinely empty files; UNKNOWN_LENGTH (-1) still counts as "has content"
        } catch (Exception e) {
            return false;
        }
    }

    /** Creates a fresh, empty cache file for the camera to write the proof photo into. */
    private File newProofImageFile() {
        try {
            File dir = new File(getCacheDir(), "proof_photos");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "proof_" + System.currentTimeMillis() + ".jpg");
        } catch (Exception e) {
            return null;
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density);
    }

    private void showManagerWorkWindowDialog(Task task) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(task.workWindowMinutes));
        input.setHint("Minutes for work window");

        new AlertDialog.Builder(this)
                .setTitle("Customize Work Window")
                .setMessage("Category: " + task.category + "\nSet how many minutes staff have to complete this task.")
                .setView(input)
                .setPositiveButton("Set Window", (d, w) -> {
                    String val = input.getText().toString();
                    if (!val.isEmpty()) {
                        task.workWindowMinutes = Integer.parseInt(val);
                        updateTaskStatus(task);
                        Toast.makeText(this, "Default work window updated for this task", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showManagerOverrideDialog(Task task) {
        new AlertDialog.Builder(this)
                .setTitle("Manager Override")
                .setMessage("Task '" + task.title + "' is MISSED. Reset it to Ongoing?")
                .setPositiveButton("Reset to Ongoing", (d, w) -> {
                    task.extensionMinutes += 60; // Add an hour to make it ongoing again
                    task.status = getString(R.string.status_ongoing);
                    updateTaskStatus(task);
                    logTaskExtension(task, 60, "missed");
                    Toast.makeText(this, "Task reset to Ongoing (1 hour added)", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addCategoryHeader(String category) {
        TextView header = new TextView(this);
        header.setText(category);
        header.setTextSize(16);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(Color.parseColor("#374151"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 32, 0, 16);
        header.setLayoutParams(params);
        tasksContainer.addView(header);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("task_reminder_channel", "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications for farm tasks");
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void setupDayClickListeners() {
        for (int i = 0; i < 7; i++) {
            final int index = i;
            dayContainers[i].setOnClickListener(v -> {
                selectedDate = (Calendar) currentWeekCalendar.clone();
                selectedDate.add(Calendar.DAY_OF_MONTH, index);
                updateCalendarUI();
                updateTasksUI();
            });
        }
    }

    private void alignCalendarToMonday(Calendar cal) {
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1);
    }

    /** Syncs the Assigned/Missing/Done chip selection with {@link #activeFilter}. */
    private void updateFilterButtonsUI() {
        if (filterAssignedBtn instanceof com.google.android.material.chip.Chip) {
            ((com.google.android.material.chip.Chip) filterAssignedBtn).setChecked(FILTER_ASSIGNED.equals(activeFilter));
        }
        if (filterMissingBtn instanceof com.google.android.material.chip.Chip) {
            ((com.google.android.material.chip.Chip) filterMissingBtn).setChecked(FILTER_MISSING.equals(activeFilter));
        }
        if (filterDoneBtn instanceof com.google.android.material.chip.Chip) {
            ((com.google.android.material.chip.Chip) filterDoneBtn).setChecked(FILTER_DONE.equals(activeFilter));
        }
    }

    /** Lets the user swipe left/right over the week calendar card to move between weeks. */
    private void setupSwipeGestures() {
        View swipeArea = findViewById(R.id.calendarCard);
        if (swipeArea == null) return;

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();

                if (Math.abs(diffX) > Math.abs(diffY)
                        && Math.abs(diffX) > SWIPE_THRESHOLD
                        && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX < 0) {
                        goToNextWeek();
                    } else {
                        goToPreviousWeek();
                    }
                    return true;
                }
                return false;
            }
        });

        swipeArea.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void goToNextWeek() {
        currentWeekCalendar.add(Calendar.DAY_OF_MONTH, 7);
        updateCalendarUI();
        updateTasksUI();
    }

    private void goToPreviousWeek() {
        currentWeekCalendar.add(Calendar.DAY_OF_MONTH, -7);
        updateCalendarUI();
        updateTasksUI();
    }

    // ── Assigned To identifier ────────────────────────────────────────────
    // Resolves task.assignedTo (a list of emails, empty for owner-only tasks)
    // to a display label using only the existing user_access collection —
    // the same source the assignee selector already reads from. No new user
    // records are created; staffNameCache just avoids re-querying Firestore
    // for the same email on every card render.
    private String resolveAssignedToLabel(Task task) {
        if (task.assignedTo == null || task.assignedTo.isEmpty()) return "Owner";

        List<String> labels = new ArrayList<>();
        for (String email : task.assignedTo) {
            if (email == null || email.trim().isEmpty()) continue;
            email = email.trim();
            String cached = staffNameCache.get(email);
            if (cached != null) {
                labels.add(cached);
            } else {
                labels.add(email); // show email until the async lookup below resolves
                final String emailFinal = email;
                db.collection("user_access").document(email).get()
                        .addOnSuccessListener(doc -> {
                            String name = doc.getString("name");
                            staffNameCache.put(emailFinal, (name != null && !name.isEmpty()) ? name : emailFinal);
                            updateTasksUI();
                        })
                        .addOnFailureListener(e -> staffNameCache.put(emailFinal, emailFinal));
            }
        }
        return labels.isEmpty() ? "Owner" : android.text.TextUtils.join(", ", labels);
    }

    // ── Assigned By identifier ──────────────────────────────────────────────
    // Resolves task.assignedBy (the email of the owner who created the task)
    // to a display name, using the same user_access lookup + cache as
    // resolveAssignedToLabel above. Lets staff see who assigned them a task.
    private String resolveAssignedByLabel(Task task) {
        if (task.assignedBy == null || task.assignedBy.trim().isEmpty()) return getString(R.string.status_owner_fallback);
        String email = task.assignedBy.trim();
        String cached = staffNameCache.get(email);
        if (cached != null) return cached;

        staffNameCache.put(email, email); // show email until the async lookup below resolves
        db.collection("user_access").document(email).get()
                .addOnSuccessListener(doc -> {
                    String name = doc.getString("name");
                    staffNameCache.put(email, (name != null && !name.isEmpty()) ? name : email);
                    updateTasksUI();
                })
                .addOnFailureListener(e -> staffNameCache.put(email, email));
        return email;
    }

    // ── Category icon styling ───────────────────────────────────────────────
    // Purely cosmetic: gives each task category its own icon + color accent on
    // the redesigned schedule card, matching the reference design's colored
    // icon circle. Returns {iconResId, iconTintColor, circleBackgroundColor}.
    private int[] getCategoryIconStyle(String category) {
        String c = category != null ? category.trim() : "";
        int icon;
        String tint, bg;
        if (c.equalsIgnoreCase("Watering")) {
            icon = R.drawable.ic_water_level;   tint = "#0284C7"; bg = "#E0F2FE";
        } else if (c.equalsIgnoreCase("Feeding")) {
            icon = R.drawable.ic_shopping_bag;  tint = "#EA580C"; bg = "#FFEDD5";
        } else if (c.equalsIgnoreCase("Cleaning")) {
            icon = R.drawable.ic_check_circle;  tint = "#059669"; bg = "#D1FAE5";
        } else if (c.equalsIgnoreCase("Egg Collection")) {
            icon = R.drawable.lc_egg;           tint = "#D97706"; bg = "#FEF3C7";
        } else if (c.equalsIgnoreCase("Lighting")) {
            icon = R.drawable.ic_alert_circle;  tint = "#7C3AED"; bg = "#EDE9FE";
        } else if (c.equalsIgnoreCase("Health Check")) {
            icon = R.drawable.ic_alert_triangle; tint = "#DC2626"; bg = "#FEE2E2";
        } else {
            icon = R.drawable.ic_calendar;      tint = "#6B7280"; bg = "#F3F4F6";
        }
        return new int[]{icon, Color.parseColor(tint), Color.parseColor(bg)};
    }

    // ── Owner task history ────────────────────────────────────────────────
    // Logs Done / Ongoing / Missed status changes for the owner's reference.
    // Mirrors the existing farm_data/shared/feed_history sibling structure —
    // no new top-level collection, no schema redesign.
    private void logTaskHistory(Task task, String status) {
        if (!roleManager.isOwner()) return; // history log is owner-only per spec

        Map<String, Object> entry = new HashMap<>();
        entry.put("taskId", task.firestoreId);
        entry.put("taskTitle", task.title);
        entry.put("assignedStaffName", resolveAssignedToLabel(task));
        entry.put("status", status);
        entry.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        if (getString(R.string.status_done).equals(status)) {
            entry.put("completedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        }
        // Completion proof, when this status change is the Mark-as-Done flow.
        if (task.doneComment != null && !task.doneComment.isEmpty()) entry.put("comment", task.doneComment);
        if (task.doneImageUrl != null && !task.doneImageUrl.isEmpty()) entry.put("proofImageUrl", task.doneImageUrl);
        entry.put("createdBy", currentUserEmail);
        entry.put("timestamp", com.google.firebase.firestore.FieldValue.serverTimestamp());

        ensureAuthThenRun(() ->
                db.collection("farm_data").document("shared")
                        .collection("task_history").document()
                        .set(entry)
                        .addOnFailureListener(e -> { /* non-critical: history log only */ }));
    }

    // ── Auto cleanup (max 1 month of history) ─────────────────────────────
    // Keeps only the current calendar month's history entries; anything
    // from a prior month is deleted automatically the next time Schedule
    // Activity opens. Active task documents (farm_data/shared/tasks) are
    // never touched here — only farm_data/shared/task_history records.
    private void cleanupOldTaskHistory() {
        Calendar monthStart = Calendar.getInstance();
        monthStart.set(Calendar.DAY_OF_MONTH, 1);
        monthStart.set(Calendar.HOUR_OF_DAY, 0);
        monthStart.set(Calendar.MINUTE, 0);
        monthStart.set(Calendar.SECOND, 0);
        monthStart.set(Calendar.MILLISECOND, 0);

        ensureAuthThenRun(() ->
                db.collection("farm_data").document("shared")
                        .collection("task_history")
                        .whereLessThan("timestamp", new com.google.firebase.Timestamp(monthStart.getTime()))
                        .get()
                        .addOnSuccessListener(snapshots -> {
                            if (snapshots.isEmpty()) return;
                            com.google.firebase.firestore.WriteBatch batch = db.batch();
                            for (QueryDocumentSnapshot doc : snapshots) batch.delete(doc.getReference());
                            batch.commit();
                        }));
    }

    // ── Local proof-photo cache cleanup (weekly) ────────────────────────────
    // Camera captures land in getCacheDir()/proof_photos before being Base64-
    // encoded into Firestore (see encodeProofPhotoAsBase64); nothing deleted
    // them afterward, so they'd otherwise accumulate on-device forever. Runs
    // on every launch and removes any capture older than a week — by then
    // it's already been uploaded (or the flow was abandoned), so the local
    // copy is no longer needed. Per-device cache, so no owner check needed.
    private void cleanupOldProofPhotoCache() {
        File dir = new File(getCacheDir(), "proof_photos");
        File[] files = dir.listFiles();
        if (files == null) return;
        long weekAgoMillis = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
        for (File f : files) {
            if (f.lastModified() < weekAgoMillis) f.delete();
        }
    }

    // ── Cloud proof-photo cleanup (weekly) ───────────────────────────────────
    // Proof photos are stored as Base64 directly on the task doc (and copied
    // into task_history — see logTaskHistory), which adds up fast. Once a
    // completed task's proof photo is more than a week old, this clears just
    // the image field — the comment, who/when it was done, and the task or
    // history entry itself are untouched. Runs on the same cadence and owner
    // gate as cleanupOldTaskHistory, and only ever removes the (by then no
    // longer useful) photo blob, never the record itself.
    private void cleanupOldProofPhotos() {
        Calendar weekAgo = Calendar.getInstance();
        weekAgo.add(Calendar.DAY_OF_YEAR, -7);
        com.google.firebase.Timestamp cutoff = new com.google.firebase.Timestamp(weekAgo.getTime());

        ensureAuthThenRun(() -> {
            db.collection("farm_data").document("shared")
                    .collection("tasks")
                    .whereLessThan("doneAt", cutoff)
                    .get()
                    .addOnSuccessListener(snapshots -> {
                        if (snapshots.isEmpty()) return;
                        com.google.firebase.firestore.WriteBatch batch = db.batch();
                        boolean[] hasWrites = {false};
                        for (QueryDocumentSnapshot doc : snapshots) {
                            String img = doc.getString("doneImageUrl");
                            if (img != null && !img.isEmpty()) {
                                batch.update(doc.getReference(), "doneImageUrl", com.google.firebase.firestore.FieldValue.delete());
                                hasWrites[0] = true;
                            }
                        }
                        if (hasWrites[0]) batch.commit();
                    });

            db.collection("farm_data").document("shared")
                    .collection("task_history")
                    .whereLessThan("timestamp", cutoff)
                    .get()
                    .addOnSuccessListener(snapshots -> {
                        if (snapshots.isEmpty()) return;
                        com.google.firebase.firestore.WriteBatch batch = db.batch();
                        boolean[] hasWrites = {false};
                        for (QueryDocumentSnapshot doc : snapshots) {
                            String img = doc.getString("proofImageUrl");
                            if (img != null && !img.isEmpty()) {
                                batch.update(doc.getReference(), "proofImageUrl", com.google.firebase.firestore.FieldValue.delete());
                                hasWrites[0] = true;
                            }
                        }
                        if (hasWrites[0]) batch.commit();
                    });
        });
    }

    private void showFullCalendar() {
        DatePickerDialog dpFull = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedDate.set(year, month, dayOfMonth);
            currentWeekCalendar = (Calendar) selectedDate.clone();
            alignCalendarToMonday(currentWeekCalendar);
            updateCalendarUI();
            updateTasksUI();
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH));
        Calendar minFull = Calendar.getInstance();
        minFull.set(Calendar.HOUR_OF_DAY, 0); minFull.set(Calendar.MINUTE, 0); minFull.set(Calendar.SECOND, 0); minFull.set(Calendar.MILLISECOND, 0);
        dpFull.getDatePicker().setMinDate(minFull.getTimeInMillis());
        dpFull.show();
    }

    private void updateCalendarUI() {
        if (isSameDay(selectedDate, today)) monthText.setText(new SimpleDateFormat(getString(R.string.today_format), Locale.getDefault()).format(today.getTime()));
        else monthText.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(selectedDate.getTime()));

        // Dynamic week label + current month
        if (weekRangeLabel != null) {
            Calendar todayWeekStart = (Calendar) today.clone();
            alignCalendarToMonday(todayWeekStart);
            long diffMillis = currentWeekCalendar.getTimeInMillis() - todayWeekStart.getTimeInMillis();
            long millisPerWeek = 7L * 24 * 60 * 60 * 1000;
            long weekOffset = diffMillis / millisPerWeek;
            String monthName = new SimpleDateFormat("MMMM", Locale.getDefault()).format(currentWeekCalendar.getTime());
            String weekLabel;
            if (weekOffset == 0)       weekLabel = "This Week";
            else if (weekOffset == 1)  weekLabel = "Next Week";
            else if (weekOffset == -1) weekLabel = "Last Week";
            else if (weekOffset > 1)   weekLabel = "Next " + weekOffset + " Weeks";
            else                       weekLabel = Math.abs(weekOffset) + " Weeks Ago";
            weekRangeLabel.setText(weekLabel + "  \u00b7  " + monthName);
        }

        Calendar tempCal = (Calendar) currentWeekCalendar.clone();
        for (int i = 0; i < 7; i++) {
            dayTextViews[i].setText(String.valueOf(tempCal.get(Calendar.DAY_OF_MONTH)));
            boolean isSelected = isSameDay(tempCal, selectedDate);
            boolean isToday    = isSameDay(tempCal, today);

            if (isSelected) {
                // Selected day: dark green fill (same whether or not it is also today)
                dayContainers[i].setBackgroundResource(R.drawable.bg_dayselected);
                dayTextViews[i].setTextColor(Color.WHITE);
                dayLabelViews[i].setTextColor(Color.parseColor("#E5E7EB"));
            } else if (isToday) {
                // Today (not selected): amber/orange outlined ring
                dayContainers[i].setBackgroundResource(R.drawable.bg_day_today);
                dayTextViews[i].setTextColor(Color.parseColor("#E65100"));
                dayLabelViews[i].setTextColor(Color.parseColor("#E65100"));
            } else {
                dayContainers[i].setBackgroundResource(R.drawable.bg_day);
                dayTextViews[i].setTextColor(Color.parseColor("#111827"));
                dayLabelViews[i].setTextColor(Color.parseColor("#4B5563"));
            }
            tempCal.add(Calendar.DAY_OF_MONTH, 1);
        }
    }
    private boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private void scheduleNotification(Task task, int hour, int minute) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar calendar = Calendar.getInstance();
        calendar.set(task.year, task.month, task.day, hour, minute, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.before(Calendar.getInstance())) return;

        Intent intent = new Intent(this, TaskAlarmReceiver.class);
        intent.putExtra("taskTitle", task.title);
        intent.putExtra("taskCategory", task.category);
        intent.putExtra("taskId", task.firestoreId);

        int rc = (task.title + task.year + task.month + task.day + hour + minute).hashCode();
        PendingIntent pi = PendingIntent.getBroadcast(this, rc, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
        else am.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
    }

    /**
     * Cancel the AlarmManager alarm and dismiss any posted notification for a task.
     * Must be called whenever a task is deleted or marked Done so the user never
     * receives a reminder for a task that no longer needs action.
     */
    private void cancelNotification(Task task) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // Re-derive the hour/minute from the stored time string so the request
        // code matches exactly what was used in scheduleNotification().
        int hour = 0, minute = 0;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
            java.util.Date d = sdf.parse(task.time.trim());
            if (d != null) {
                Calendar tmp = Calendar.getInstance();
                tmp.setTime(d);
                hour   = tmp.get(Calendar.HOUR_OF_DAY);
                minute = tmp.get(Calendar.MINUTE);
            }
        } catch (Exception ignored) { }

        int rc = (task.title + task.year + task.month + task.day + hour + minute).hashCode();
        Intent intent = new Intent(this, TaskAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, rc, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }

        // Also dismiss the notification if it is already showing.
        // IMPORTANT: notifId must match what TaskAlarmReceiver.showNotification() used.
        // TaskAlarmReceiver uses taskId.hashCode() when firestoreId is available,
        // and falls back to (title+category).hashCode() only for legacy alarms.
        // We cancel both IDs to cover both cases.
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            if (task.firestoreId != null && !task.firestoreId.isEmpty()) {
                nm.cancel(task.firestoreId.hashCode()); // matches TaskAlarmReceiver primary ID
            }
            nm.cancel((task.title + task.category).hashCode()); // legacy fallback
        }
    }

    private TextView makeHeaderCell(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 10, 0, 10);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.GRAY);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = GridLayout.LayoutParams.WRAP_CONTENT;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        tv.setLayoutParams(p);
        return tv;
    }

    private TextView makeDayCell(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 20, 0, 20);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setTextColor(Color.BLACK);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = GridLayout.LayoutParams.WRAP_CONTENT;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        tv.setLayoutParams(p);
        return tv;
    }

    private View makeSpacer() {
        View v = new View(this);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = 1;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        v.setLayoutParams(p);
        return v;
    }

    private static class Task {
        String firestoreId;
        String title, category, time, status;
        int    year, month, day;
        String recurrence;
        String recurrenceGroupId;
        List<String> assignedTo = new ArrayList<>(); // emails of assigned staff; empty = owner-only visible
        String assignedBy;    // email of the owner who created/assigned this task
        int extensionMinutes = 0;
        int workWindowMinutes = 60; // default
        String doneComment;   // required comment proof, set only when marked Done via the proof flow
        String doneImageUrl;  // Base64-encoded JPEG of the required proof photo (stored directly on the task doc, not Firebase Storage)

        Task(String firestoreId, String title, String category, String time, String status, int year, int month, int day, String recurrence, String recurrenceGroupId) {
            this.firestoreId = firestoreId;
            this.title = title;
            this.category = category;
            this.time = time;
            this.status = status;
            this.year = year;
            this.month = month;
            this.day = day;
            this.recurrence = recurrence;
            this.recurrenceGroupId = recurrenceGroupId;
        }
    }

    public static class TaskAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String taskId = intent.getStringExtra("taskId");
            if (taskId == null) {
                // Fallback for legacy alarms without ID
                showNotification(context, intent);
                return;
            }

            final PendingResult result = goAsync();
            // Ensure anonymous auth before Firestore read — the auth may not be ready
            // at alarm fire time (especially after device reboot).
            com.google.firebase.auth.FirebaseAuth auth =
                    com.google.firebase.auth.FirebaseAuth.getInstance();
            Runnable checkAndNotify = () ->
                    FirebaseFirestore.getInstance().collection("farm_data")
                            .document("shared").collection("tasks").document(taskId)
                            .get()
                            .addOnCompleteListener(task -> {
                                try {
                                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                                        String status = task.getResult().getString("status");
                                        if (!"Done".equals(status)) {
                                            showNotification(context, intent);
                                        }
                                    } else {
                                        // Task deleted or read failed — still show notification
                                        // so the user isn't silently skipped
                                        showNotification(context, intent);
                                    }
                                } finally {
                                    result.finish();
                                }
                            });
            if (auth.getCurrentUser() != null) {
                checkAndNotify.run();
            } else {
                auth.signInAnonymously()
                        .addOnSuccessListener(r -> checkAndNotify.run())
                        .addOnFailureListener(e -> {
                            // Auth failed — still show notification as fallback
                            showNotification(context, intent);
                            result.finish();
                        });
            }
        }

        private void showNotification(Context context, Intent intent) {
            String title = intent.getStringExtra("taskTitle");
            String category = intent.getStringExtra("taskCategory");
            String taskId  = intent.getStringExtra("taskId");
            createChannel(context);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            try {
                AccountManager accountManager = new AccountManager(context);
                if (!accountManager.isScheduleEnabled()) return;
                String alertMsg = context.getString(R.string.task_reminder_title, title) + " (" + category + ")";
                if (accountManager.isGlobalDataEnabled()) {
                    // Write to Firestore via FarmRepository so the alert is shared across
                    // all devices and deduplicated by deterministic document ID.
                    // Also update local GlobalData for immediate UI refresh.
                    FarmRepository.INSTANCE.addAlert(alertMsg, "Schedule", null);
                    GlobalData.addAlert(alertMsg, timestamp, "Schedule");
                }
            } catch (Exception e) { e.printStackTrace(); }

            Intent alertIntent = new Intent(context, MainActivity.class);
            alertIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 0, alertIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "task_reminder_channel")
                    .setSmallIcon(R.drawable.ic_notifications)
                    .setContentTitle(context.getString(R.string.task_reminder_title, title))
                    .setContentText(context.getString(R.string.task_reminder_msg, category))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            try {
                NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                // taskId already extracted at top of showNotification()
                int notificationId = (taskId != null && !taskId.isEmpty())
                        ? taskId.hashCode()
                        : (title + category).hashCode();
                nm.notify(notificationId, builder.build());
            } catch (SecurityException e) { e.printStackTrace(); }
        }

        private static void createChannel(Context context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel("task_reminder_channel", "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifications for farm tasks");
                channel.enableLights(true);
                channel.enableVibration(true);
                channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);
            }
        }
    }
}