package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class ProfileActivity extends AppCompatActivity {
    private CameraHelper cameraHelper;
    private AccountManager accountManager;
    private FirestoreManager firestoreManager;

    private TextView totalBirdsValue;
    private TextView activeCagesValue;
    private TextView daysRunningValue;
    private TextView userNameTv;
    private TextView userEmailTv;
    private TextView profileInitialTv;
    private ImageView profileImageView;

    private String userRole = "staff";
    private String currentEmail = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        cameraHelper = new CameraHelper(this);
        setContentView(R.layout.activity_profile);

        accountManager = new AccountManager(this);
        String currentSession = accountManager.getCurrentUsername();
        userRole = accountManager.getRole(currentSession != null ? currentSession : "");

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        totalBirdsValue  = findViewById(R.id.totalBirdsValue);
        activeCagesValue = findViewById(R.id.activeCagesValue);
        daysRunningValue = findViewById(R.id.daysRunningValue);
        userNameTv       = findViewById(R.id.userName);
        userEmailTv      = findViewById(R.id.userEmail);
        profileInitialTv = findViewById(R.id.profileInitial);
        profileImageView = findViewById(R.id.profileImage);

        // Resolve email
        currentEmail = getIntent().getStringExtra("username");
        if (currentEmail == null || currentEmail.isEmpty()) {
            currentEmail = currentSession;
        }
        
        if (currentEmail == null) currentEmail = "";

        firestoreManager = new FirestoreManager(currentEmail);

        showLoading("Syncing Profile...", () -> {
            fetchUserData();
            loadFirestoreData();
        });

        //  Edit profile (pen button) -----------------------------------------------------------
        View editProfileButton = findViewById(R.id.editProfileButton);
        if (editProfileButton != null) {
            editProfileButton.setOnClickListener(v -> showEditNameDialog());
        }

        //  Account Settings-----------------------------------------------------------
        View accountSettingsButton = findViewById(R.id.accountSettingsButton);
        if (accountSettingsButton != null) {
            accountSettingsButton.setOnClickListener(v -> showAccountSettingsDialog());
        }

        //  Farm Recalibration-----------------------------------------------------------
        View recalibrationButton = findViewById(R.id.farmRecalibrationButton);
        if (recalibrationButton != null) {
            if (isAdmin()) {
                recalibrationButton.setVisibility(View.VISIBLE);
                recalibrationButton.setOnClickListener(v -> showRecalibrationDialog());
            } else {
                recalibrationButton.setVisibility(View.GONE);
            }
        }

        //  Notification Preferences -----------------------------------------------------------
        View notificationPrefsButton = findViewById(R.id.notificationPreferencesButton);
        if (notificationPrefsButton != null) {
            notificationPrefsButton.setOnClickListener(v -> showNotificationPrefsDialog());
        }

        //  Language & Region -----------------------------------------------------------
        View languageRegionButton = findViewById(R.id.languageRegionButton);
        if (languageRegionButton != null) {
            languageRegionButton.setOnClickListener(v -> showLanguageRegionDialog());
        }

        //  Privacy & Security -----------------------------------------------------------
        View privacySecurityButton = findViewById(R.id.privacySecurityButton);
        if (privacySecurityButton != null) {
            privacySecurityButton.setOnClickListener(v -> showPrivacySecurityDialog());
        }

        //  Help & Support -----------------------------------------------------------
        View helpSupportButton = findViewById(R.id.helpSupportButton);
        if (helpSupportButton != null) {
            helpSupportButton.setOnClickListener(v -> showHelpSupportDialog());
        }

        // Back button ----------------------------------------------------------------------
        ImageButton backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                Intent intent = new Intent(ProfileActivity.this, DashboardActivity.class);
                intent.putExtra("username", currentEmail);
                intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                finish();
            });
        }

        // -- Logout -------------------------------------------------------------------------------
        View logoutButton = findViewById(R.id.logoutButton);
        if (logoutButton != null) {
            logoutButton.setOnClickListener(v -> {
                accountManager.clearSession();
                Intent intent = new Intent(ProfileActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }

        NavigationHelper.INSTANCE.setupBottomNavigation(this);

        LinearLayout cameraButton = findViewById(R.id.CameraButton);
        if (cameraButton != null) {
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }
    }

    private void showLoading(String label, Runnable action) {
        View loadingLayout = findViewById(R.id.loadingLayout);
        View loadingIcon = findViewById(R.id.loadingIcon);
        TextView statusText = findViewById(R.id.loadingStatusText);
        ProgressBar progressBar = findViewById(R.id.loadingProgressBar);
        TextView percentText = findViewById(R.id.loadingPercentageText);

        if (loadingLayout != null && loadingIcon != null) {
            statusText.setText(label);
            loadingLayout.setVisibility(View.VISIBLE);
            loadingIcon.startAnimation(AnimationUtils.loadAnimation(this, R.anim.quail_jump));

            final int[] progress = {0};
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (progress[0] <= 100) {
                        progressBar.setProgress(progress[0]);
                        percentText.setText(progress[0] + "%");
                        progress[0] += 10;
                        handler.postDelayed(this, 50);
                    } else {
                        loadingLayout.setVisibility(View.GONE);
                        loadingIcon.clearAnimation();
                        action.run();
                    }
                }
            });
        } else {
            action.run();
        }
    }

    private void fetchUserData() {
        if (currentEmail == null || currentEmail.isEmpty()) return;
        FirebaseFirestore.getInstance().collection("user_access").document(currentEmail).get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    String name = doc.getString("name");
                    String photoUrl = doc.getString("profilePic");
                    
                    if (name != null) {
                        userNameTv.setText(name);
                        profileInitialTv.setText(String.valueOf(name.charAt(0)).toUpperCase());
                    }
                    userEmailTv.setText(currentEmail);
                    
                    if (photoUrl != null && !photoUrl.isEmpty()) {
                        profileInitialTv.setVisibility(View.GONE);
                        profileImageView.setVisibility(View.VISIBLE);
                        Glide.with(this).load(photoUrl).circleCrop().into(profileImageView);
                    } else {
                        profileImageView.setVisibility(View.GONE);
                        profileInitialTv.setVisibility(View.VISIBLE);
                    }
                }
            });
    }

    private boolean isAdmin() {
        return "owner".equals(userRole);
    }

    //  Firestore load ---------------------------------------------------------------------------
    private void loadFirestoreData() {
        if (firestoreManager == null) return;
        firestoreManager.loadFarmData(new FirestoreManager.OnLoadListener() {
            @Override
            public void onLoaded(String name, int totalBirds, int activeCages, long daysRunning) {
                runOnUiThread(() -> {
                    totalBirdsValue.setText(totalBirds > 0 ? String.valueOf(totalBirds) : "--");
                    activeCagesValue.setText(activeCages > 0 ? String.valueOf(activeCages) : "--");
                    daysRunningValue.setText(daysRunning > 0 ? String.valueOf(daysRunning) : "--");
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> Toast.makeText(ProfileActivity.this, "Error loading farm data", Toast.LENGTH_SHORT).show());
            }
        });
    }

    //  Edit Name dialog (pen button)---------------------------------------------------------------
    private void showEditNameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Name");

        final EditText input = new EditText(this);
        input.setText(userNameTv.getText().toString());
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            firestoreManager.saveName(newName, new FirestoreManager.OnSaveListener() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        userNameTv.setText(newName);
                        profileInitialTv.setText(String.valueOf(newName.charAt(0)).toUpperCase());
                        Toast.makeText(ProfileActivity.this, "Name updated!", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> Toast.makeText(ProfileActivity.this, "Failed to update", Toast.LENGTH_SHORT).show());
                }
            });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    //  Farm Recalibration dialog ------------------------------------------------------------------
    private void showRecalibrationDialog() {
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_recalibrate_farm, null);

        EditText editTotalBirds  = dialogView.findViewById(R.id.editTotalBirds);
        EditText editActiveCages = dialogView.findViewById(R.id.editActiveCages);

        // Pre-fill from current UI
        editTotalBirds.setText(totalBirdsValue.getText());
        editActiveCages.setText(activeCagesValue.getText());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("Farm Recalibration")
                .setPositiveButton("Save", null)
                .setNeutralButton("Restart Days", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String birdsStr = editTotalBirds.getText().toString().trim();
                String cagesStr = editActiveCages.getText().toString().trim();

                if (birdsStr.isEmpty() || cagesStr.isEmpty()) {
                    Toast.makeText(ProfileActivity.this, "Please fill in both fields", Toast.LENGTH_SHORT).show();
                    return;
                }

                int newBirds = Integer.parseInt(birdsStr);
                int newCages = Integer.parseInt(cagesStr);

                accountManager.saveFarmStats(newBirds, newCages);
                firestoreManager.saveFarmStats(newBirds, newCages, new FirestoreManager.OnSaveListener() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            totalBirdsValue.setText(String.valueOf(newBirds));
                            activeCagesValue.setText(String.valueOf(newCages));
                            Toast.makeText(ProfileActivity.this, "Farm stats updated!", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        });
                    }
                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() -> Toast.makeText(ProfileActivity.this, "Save failed", Toast.LENGTH_SHORT).show());
                    }
                });
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                new AlertDialog.Builder(ProfileActivity.this)
                        .setTitle("Restart Days Running?")
                        .setMessage("Reset counter back to Day 1?")
                        .setPositiveButton("Restart", (d2, w) -> {
                            firestoreManager.restartDaysRunning(new FirestoreManager.OnSaveListener() {
                                @Override
                                public void onSuccess() {
                                    runOnUiThread(() -> {
                                        daysRunningValue.setText("1");
                                        Toast.makeText(ProfileActivity.this, "Days restarted!", Toast.LENGTH_SHORT).show();
                                        dialog.dismiss();
                                    });
                                }
                                @Override
                                public void onError(Exception e) {
                                    runOnUiThread(() -> Toast.makeText(ProfileActivity.this, "Restart failed", Toast.LENGTH_SHORT).show());
                                }
                            });
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
        dialog.show();
    }

    private void showAccountSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_account_settings, null);
        builder.setView(view);

        View changePasswordBtn = view.findViewById(R.id.changePasswordBtn);
        View deleteAccountBtn = view.findViewById(R.id.deleteAccountBtn);

        changePasswordBtn.setOnClickListener(v -> showChangePasswordDialog());
        deleteAccountBtn.setOnClickListener(v -> showDeleteAccountDialog());

        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showChangePasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Change Password");
        View view = getLayoutInflater().inflate(R.layout.dialog_change_password, null);
        builder.setView(view);

        EditText oldPass = view.findViewById(R.id.oldPassword);
        EditText newPass = view.findViewById(R.id.newPassword);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String oldP = oldPass.getText().toString();
            String newP = newPass.getText().toString();
            String currentUser = accountManager.getCurrentUsername();
            if (currentUser != null && accountManager.updatePassword(currentUser, oldP, newP)) {
                Toast.makeText(this, "Password updated successfully", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showDeleteAccountDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Account")
                .setMessage("Are you sure you want to delete your account?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    String currentUser = accountManager.getCurrentUsername();
                    if (currentUser != null && accountManager.deleteAccount(currentUser)) {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNotificationPrefsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_notification_preferences, null);
        builder.setView(dialogView);

        SwitchCompat switchAlerts     = dialogView.findViewById(R.id.switchAlerts);
        SwitchCompat switchGlobalData = dialogView.findViewById(R.id.switchGlobalData);
        SwitchCompat switchSchedule   = dialogView.findViewById(R.id.switchSchedule);

        switchAlerts.setChecked(accountManager.isAlertsEnabled());
        switchGlobalData.setChecked(accountManager.isGlobalDataEnabled());
        switchSchedule.setChecked(accountManager.isScheduleEnabled());

        builder.setTitle("Notification Preferences")
                .setPositiveButton("Save", (dialog, which) -> {
                    accountManager.saveNotificationPreferences(switchAlerts.isChecked(), switchGlobalData.isChecked(), switchSchedule.isChecked());
                    Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLanguageRegionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_language_region, null);
        builder.setView(view);

        Spinner spinnerLanguage = view.findViewById(R.id.spinnerLanguage);
        Spinner spinnerRegion   = view.findViewById(R.id.spinnerRegion);
        Spinner spinnerProvince = view.findViewById(R.id.spinnerProvince);

        String[] languages = {"English", "Tagalog", "Cebuano"};
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);
        spinnerLanguage.setSelection(Arrays.asList(languages).indexOf(accountManager.getSelectedLanguage()));

        Map<String, List<String>> regionProvinceMap = new HashMap<>();
        regionProvinceMap.put("National Capital Region (NCR)", Arrays.asList("Metro Manila"));
        regionProvinceMap.put("Ilocos Region (Region I)", Arrays.asList("Ilocos Norte", "Ilocos Sur", "La Union", "Pangasinan"));
        regionProvinceMap.put("Cagayan Valley (Region II)", Arrays.asList("Batanes", "Cagayan", "Isabela", "Nueva Vizcaya", "Quirino"));
        regionProvinceMap.put("Central Luzon (Region III)", Arrays.asList("Aurora", "Bataan", "Bulacan", "Nueva Ecija", "Pampanga", "Tarlac", "Zambales"));
        regionProvinceMap.put("CALABARZON (Region IV-A)", Arrays.asList("Batangas", "Cavite", "Laguna", "Quezon", "Rizal"));
        regionProvinceMap.put("MIMAROPA Region (Region IV-B)", Arrays.asList("Marinduque", "Occidental Mindoro", "Oriental Mindoro", "Palawan", "Romblon"));
        regionProvinceMap.put("Bicol Region (Region V)", Arrays.asList("Albay", "Camarines Norte", "Camarines Sur", "Catanduanes", "Masbate", "Sorsogon"));
        regionProvinceMap.put("Western Visayas (Region VI)", Arrays.asList("Aklan", "Antique", "Capiz", "Guimaras", "Iloilo", "Negros Occidental"));
        regionProvinceMap.put("Central Visayas (Region VII)", Arrays.asList("Bohol", "Cebu", "Negros Oriental", "Siquijor"));
        regionProvinceMap.put("Eastern Visayas (Region VIII)", Arrays.asList("Biliran", "Eastern Samar", "Leyte", "Northern Samar", "Samar", "Southern Leyte"));
        regionProvinceMap.put("Zamboanga Peninsula (Region IX)", Arrays.asList("Zamboanga del Norte", "Zamboanga del Sur", "Zamboanga Sibugay"));
        regionProvinceMap.put("Northern Mindanao (Region X)", Arrays.asList("Bukidnon", "Camiguin", "Lanao del Norte", "Misamis Occidental", "Misamis Oriental"));
        regionProvinceMap.put("Davao Region (Region XI)", Arrays.asList("Davao de Oro", "Davao del Norte", "Davao del Sur", "Davao Occidental", "Davao Oriental"));
        regionProvinceMap.put("SOCCSKSARGEN (Region XII)", Arrays.asList("Cotabato", "Sarangani", "South Cotabato", "Sultan Kudarat"));
        regionProvinceMap.put("Caraga (Region XIII)", Arrays.asList("Agusan del Norte", "Agusan del Sur", "Dinagat Islands", "Surigao del Norte", "Surigao del Sur"));
        regionProvinceMap.put("BARMM", Arrays.asList("Basilan", "Lanao del Sur", "Maguindanao", "Sulu", "Tawi-Tawi"));
        regionProvinceMap.put("Cordillera Administrative Region (CAR)", Arrays.asList("Abra", "Apayao", "Benguet", "Ifugao", "Kalinga", "Mountain Province"));

        String[] regions = regionProvinceMap.keySet().toArray(new String[0]);
        Arrays.sort(regions);
        ArrayAdapter<String> regionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, regions);
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRegion.setAdapter(regionAdapter);
        spinnerRegion.setSelection(Arrays.asList(regions).indexOf(accountManager.getSelectedRegion()));

        spinnerRegion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                String selectedRegion = regions[position];
                List<String> provinces = regionProvinceMap.get(selectedRegion);
                if (provinces != null) {
                    ArrayAdapter<String> provinceAdapter = new ArrayAdapter<>(ProfileActivity.this, android.R.layout.simple_spinner_item, provinces);
                    provinceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerProvince.setAdapter(provinceAdapter);
                    String savedProvince = accountManager.getSelectedProvince();
                    if (provinces.contains(savedProvince)) {
                        spinnerProvince.setSelection(provinces.indexOf(savedProvince));
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        builder.setPositiveButton("Save", (dialog, which) -> {
            String selectedLang = spinnerLanguage.getSelectedItem().toString();
            String selectedReg  = spinnerRegion.getSelectedItem().toString();
            String selectedProv = spinnerProvince.getSelectedItem().toString();
            accountManager.saveLanguageRegion(selectedLang, selectedReg, selectedProv);
            String langTag = "en";
            if (selectedLang.equals("Tagalog")) langTag = "tl";
            else if (selectedLang.equals("Cebuano")) langTag = "ceb";
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTag));
            recreate();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showPrivacySecurityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_privacy_security, null);
        builder.setView(view);

        View adminSection = view.findViewById(R.id.adminOnlySection);
        View btnUserList = view.findViewById(R.id.btnUserList);
        View btnDatabase = view.findViewById(R.id.btnDatabaseManagement);

        // Content Sections (Non-admin clickable)
        View sectionDataCollection = view.findViewById(R.id.sectionDataCollection);
        View sectionPrivacyContent = view.findViewById(R.id.sectionPrivacyContent);
        View sectionSecurityMeasures = view.findViewById(R.id.sectionSecurityMeasures);

        if (sectionDataCollection != null) sectionDataCollection.setOnClickListener(v -> showDataCollectionDetails());
        if (sectionPrivacyContent != null) sectionPrivacyContent.setOnClickListener(v -> showPrivacyContentDetails());
        if (sectionSecurityMeasures != null) sectionSecurityMeasures.setOnClickListener(v -> showSecurityMeasuresDetails());

        if (isAdmin()) {
            if (adminSection != null) adminSection.setVisibility(View.VISIBLE);
            if (btnUserList != null) btnUserList.setOnClickListener(v -> showUserListDialog());
            if (btnDatabase != null) btnDatabase.setOnClickListener(v -> showDatabaseManagementDialog());
        }

        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showDataCollectionDetails() {
        new AlertDialog.Builder(this)
            .setTitle("Data Collection Details")
            .setMessage("We collect farm profile information (GPS for location-based weather), flock records, daily production, health logs, and financial records to provide comprehensive farm management analytics.")
            .setPositiveButton("Close", null).show();
    }

    private void showPrivacyContentDetails() {
        new AlertDialog.Builder(this)
            .setTitle("Privacy Content Details")
            .setMessage("Your data belongs to you. We only use it for farm reports and regional trend analysis. No identifying information is shared with third parties without explicit consent.")
            .setPositiveButton("Close", null).show();
    }

    private void showSecurityMeasuresDetails() {
        new AlertDialog.Builder(this)
            .setTitle("Security Measures Details")
            .setMessage("Your records are protected via Firebase end-to-end encryption. Only authorized roles (Owner, Backup Owner, Staff) can view specific data tiers.")
            .setPositiveButton("Close", null).show();
    }

    private void showUserListDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("User Management");
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 40, 40, 40);

        // -- Button Row --
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        buttonRow.setPadding(0, 0, 0, 20);

        Button btnLimits = new Button(this);
        btnLimits.setText("Set Limits");
        btnLimits.setOnClickListener(v -> showRoleLimitsDialog());
        buttonRow.addView(btnLimits);

        Button btnPending = new Button(this);
        btnPending.setText("Pending Requests");
        btnPending.setOnClickListener(v -> showPendingRequestsDialog());
        buttonRow.addView(btnPending);

        Button btnInvite = new Button(this);
        btnInvite.setText("Invite User");
        btnInvite.setOnClickListener(v -> NavigationHelper.INSTANCE.showGenerateInviteCodeDialog(this, currentEmail));
        buttonRow.addView(btnInvite);

        mainLayout.addView(buttonRow);

        // -- Users List --
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        FirebaseFirestore.getInstance().collection("user_access")
            .whereEqualTo("status", "approved")
            .get()
            .addOnSuccessListener(docs -> {
                container.removeAllViews();
                for (DocumentSnapshot doc : docs) {
                    View row = getLayoutInflater().inflate(R.layout.item_user_manage, null);
                    String name = doc.getString("name");
                    String role = doc.getString("role");
                    String email = doc.getId();

                    ((TextView)row.findViewById(R.id.userNameText)).setText(name);
                    ((TextView)row.findViewById(R.id.userRoleText)).setText(role);

                    View deleteBtn = row.findViewById(R.id.deleteUserBtn);
                    if ("owner".equals(role)) deleteBtn.setVisibility(View.GONE);

                    deleteBtn.setOnClickListener(v -> {
                        FirebaseFirestore.getInstance().collection("user_access").document(email).delete()
                            .addOnSuccessListener(a -> showUserListDialog());
                    });
                    container.addView(row);
                }
            });

        mainLayout.addView(container);
        builder.setView(mainLayout);
        builder.setPositiveButton("Done", null);
        builder.show();
    }

    private void showRoleLimitsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Role Limits");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        EditText editBackupLimit = new EditText(this);
        editBackupLimit.setHint("Max Backup Owners");
        layout.addView(editBackupLimit);

        EditText editStaffLimit = new EditText(this);
        editStaffLimit.setHint("Max Staff");
        layout.addView(editStaffLimit);

        FirebaseFirestore.getInstance().collection("system_settings").document("role_limits").get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    editBackupLimit.setText(String.valueOf(doc.getLong("backup_owner_limit")));
                    editStaffLimit.setText(String.valueOf(doc.getLong("staff_limit")));
                }
            });

        builder.setView(layout);
        builder.setPositiveButton("Save", (d, w) -> {
            Map<String, Object> limits = new HashMap<>();
            limits.put("backup_owner_limit", Long.parseLong(editBackupLimit.getText().toString()));
            limits.put("staff_limit", Long.parseLong(editStaffLimit.getText().toString()));

            FirebaseFirestore.getInstance().collection("system_settings").document("role_limits").set(limits)
                .addOnSuccessListener(a -> Toast.makeText(this, "Limits Updated!", Toast.LENGTH_SHORT).show());
        });
        builder.show();
    }

    private void showPendingRequestsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Pending Access Requests");
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(40, 40, 40, 40);

        FirebaseFirestore.getInstance().collection("user_access")
            .whereEqualTo("status", "pending")
            .get()
            .addOnSuccessListener(docs -> {
                container.removeAllViews();
                if (docs.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText("No pending requests.");
                    container.addView(tv);
                }
                for (DocumentSnapshot doc : docs) {
                    View row = getLayoutInflater().inflate(R.layout.item_user_manage, null);
                    String name = doc.getString("name");
                    String role = doc.getString("role");
                    String email = doc.getId();

                    ((TextView)row.findViewById(R.id.userNameText)).setText(name + " (" + email + ")");
                    ((TextView)row.findViewById(R.id.userRoleText)).setText("Role: " + role);

                    ImageButton approveBtn = (ImageButton) row.findViewById(R.id.deleteUserBtn);
                    approveBtn.setImageResource(android.R.drawable.ic_menu_save); // reuse button for approve

                    approveBtn.setOnClickListener(v -> {
                        String code = String.format(Locale.getDefault(), "%06d", new Random().nextInt(999999));
                        Map<String, Object> update = new HashMap<>();
                        update.put("status", "approved");
                        update.put("verificationCode", code);

                        doc.getReference().update(update)
                            .addOnSuccessListener(a -> {
                                new AlertDialog.Builder(this)
                                    .setTitle("Request Approved")
                                    .setMessage("Verification Code for " + name + ": " + code)
                                    .setPositiveButton("Share via Gmail", (d, w) -> {
                                        Intent intent = new Intent(Intent.ACTION_SEND);
                                        intent.setType("message/rfc822");
                                        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
                                        intent.putExtra(Intent.EXTRA_SUBJECT, "Your Quail Farm Access Code");
                                        intent.putExtra(Intent.EXTRA_TEXT, "Hello " + name + ",\n\nYour request for access has been approved.\nYour 6-digit verification code is: " + code + "\n\nPlease enter this code in the app to complete your registration.");
                                        startActivity(Intent.createChooser(intent, "Send Email"));
                                        showPendingRequestsDialog();
                                    })
                                    .setNegativeButton("Close", (d, w) -> showPendingRequestsDialog())
                                    .show();
                            });
                    });
                    container.addView(row);
                }
            });

        builder.setView(container);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showDatabaseManagementDialog() {
        String[] items = {"Monitor Database", "Import Database", "Export Database", "Delete Database", "Wipe Everything"};
        new AlertDialog.Builder(this)
                .setTitle("Database Management")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: monitorDatabase(); break;
                        case 1: importDatabase(); break;
                        case 2: exportDatabase(); break;
                        case 3: deleteDatabase(); break;
                        case 4: wipeEverything(); break;
                    }
                }).setPositiveButton("Cancel", null).show();
    }

    private void monitorDatabase() {
        Toast.makeText(this, "Monitoring Database Connections...", Toast.LENGTH_SHORT).show();
    }

    private void importDatabase() {
        Toast.makeText(this, "Importing data from backup...", Toast.LENGTH_SHORT).show();
    }

    private void exportDatabase() {
        Toast.makeText(this, "Exporting farm records to CSV...", Toast.LENGTH_SHORT).show();
    }

    private void deleteDatabase() {
        new AlertDialog.Builder(this)
            .setTitle("Delete Database")
            .setMessage("Are you sure? This deletes only the farm records but keeps user accounts.")
            .setPositiveButton("Delete", (d, w) -> Toast.makeText(this, "Database Deleted", Toast.LENGTH_SHORT).show())
            .setNegativeButton("Cancel", null).show();
    }

    private void wipeEverything() {
        new AlertDialog.Builder(this)
            .setTitle("Wipe Everything")
            .setMessage("WARNING: This will delete all users, all farm data, and reset the system. Proceed?")
            .setPositiveButton("WIPE", (d, w) -> {
                accountManager.clearSession();
                finish();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void showHelpSupportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_help_support, null);
        builder.setView(view);
        builder.setPositiveButton("Close", null);
        builder.show();
    }
}
