package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private String currentEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        cameraHelper = new CameraHelper(this);
        setContentView(R.layout.activity_profile);

        accountManager = new AccountManager(this);

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

        // Resolve username + email
        String username = getIntent().getStringExtra("username");
        if (username == null || username.isEmpty()) {
            username = accountManager.getCurrentUsername();
        }
        currentEmail = accountManager.getEmail(username);
        if (currentEmail == null) currentEmail = username; // fallback

        firestoreManager = new FirestoreManager(currentEmail);

        updateUserInfoDisplay(username);
        loadFirestoreData();

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
            recalibrationButton.setOnClickListener(v -> showRecalibrationDialog());
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
                intent.putExtra("username", accountManager.getCurrentUsername());
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
            cameraButton.setClickable(true);
            cameraButton.setFocusable(true);
            cameraButton.setOnClickListener(v -> cameraHelper.launch());
        }
    }

    //  Firestore load ---------------------------------------------------------------------------
    private void loadFirestoreData() {
        firestoreManager.loadFarmData(new FirestoreManager.OnLoadListener() {
            @Override
            public void onLoaded(String name, int totalBirds, int activeCages, long daysRunning) {
                runOnUiThread(() -> {
                    if (!name.isEmpty()) {
                        userNameTv.setText(name);
                        profileInitialTv.setText(String.valueOf(name.charAt(0)).toUpperCase());
                    }
                    totalBirdsValue.setText(totalBirds > 0 ? String.valueOf(totalBirds) : "--");
                    activeCagesValue.setText(activeCages > 0 ? String.valueOf(activeCages) : "--");
                    daysRunningValue.setText(daysRunning > 0 ? String.valueOf(daysRunning) : "--");
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(ProfileActivity.this,
                                "Could not load data: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void updateUserInfoDisplay(String username) {
        if (username != null && !username.isEmpty()) {
            userNameTv.setText(username);
            profileInitialTv.setText(String.valueOf(username.charAt(0)).toUpperCase());
            String email = accountManager.getEmail(username);
            if (email != null) userEmailTv.setText(email);
        }
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
                    runOnUiThread(() ->
                            Toast.makeText(ProfileActivity.this,
                                    "Failed to update: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
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

        // Pre-fill from Firestore (load fresh before showing dialog)
        firestoreManager.loadFarmData(new FirestoreManager.OnLoadListener() {
            @Override
            public void onLoaded(String name, int totalBirds, int activeCages, long daysRunning) {
                runOnUiThread(() -> {
                    if (totalBirds > 0)  editTotalBirds.setText(String.valueOf(totalBirds));
                    if (activeCages > 0) editActiveCages.setText(String.valueOf(activeCages));
                });
            }
            @Override
            public void onError(Exception e) { /* fields stay empty, user can still type */ }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("Farm Recalibration")
                .setPositiveButton("Save", null)          // set null first to prevent auto-dismiss
                .setNeutralButton("Restart Days", null)   // same
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {

            //  Save button --------------------------------------------------------------------
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String birdsStr = editTotalBirds.getText().toString().trim();
                String cagesStr = editActiveCages.getText().toString().trim();

                if (birdsStr.isEmpty() || cagesStr.isEmpty()) {
                    Toast.makeText(ProfileActivity.this,
                            "Please fill in both fields", Toast.LENGTH_SHORT).show();
                    return; // keep dialog open
                }

                int newBirds = Integer.parseInt(birdsStr);
                int newCages = Integer.parseInt(cagesStr);

                // Save locally
                accountManager.saveFarmStats(newBirds, newCages);

                // Save to Firestore
                firestoreManager.saveFarmStats(newBirds, newCages, new FirestoreManager.OnSaveListener() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            totalBirdsValue.setText(String.valueOf(newBirds));
                            activeCagesValue.setText(String.valueOf(newCages));
                            Toast.makeText(ProfileActivity.this,
                                    "Farm stats updated!", Toast.LENGTH_SHORT).show();
                            loadFirestoreData(); // refresh daysRunning display too
                            dialog.dismiss();
                        });
                    }
                    @Override
                    public void onError(Exception e) {
                        runOnUiThread(() ->
                                Toast.makeText(ProfileActivity.this,
                                        "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                        );
                    }
                });
            });

            //  Restart Days button-----------------------------------------------------------------
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                new AlertDialog.Builder(ProfileActivity.this)
                        .setTitle("Restart Days Running?")
                        .setMessage("This will reset your Days Running counter back to Day 1. Are you sure?")
                        .setPositiveButton("Restart", (d2, w) -> {
                            firestoreManager.restartDaysRunning(new FirestoreManager.OnSaveListener() {
                                @Override
                                public void onSuccess() {
                                    runOnUiThread(() -> {
                                        daysRunningValue.setText("1");
                                        Toast.makeText(ProfileActivity.this,
                                                "Days Running restarted!", Toast.LENGTH_SHORT).show();
                                    });
                                }
                                @Override
                                public void onError(Exception e) {
                                    runOnUiThread(() ->
                                            Toast.makeText(ProfileActivity.this,
                                                    "Restart failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                                    );
                                }
                            });
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });

        dialog.show();
    }

    // -- Account Settings -------------------------------------------------------------------------
    private void showAccountSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_account_settings, null);
        builder.setView(view);

        EditText currentPassword    = view.findViewById(R.id.currentPassword);
        EditText newPassword        = view.findViewById(R.id.newPassword);
        EditText confirmNewPassword = view.findViewById(R.id.confirmNewPassword);

        builder.setPositiveButton("Update Password", (dialog, which) -> {
            String oldPass     = currentPassword.getText().toString();
            String newPass     = newPassword.getText().toString();
            String confirmPass = confirmNewPassword.getText().toString();

            if (oldPass.isEmpty()) {
                Toast.makeText(this, "Current password required", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newPass.equals(confirmPass)) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newPass.isEmpty()) {
                if (accountManager.updatePassword(accountManager.getCurrentUsername(), oldPass, newPass)) {
                    Toast.makeText(this, "Password updated successfully", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // -- Notification Preferences -----------------------------------------------------------------
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
                    accountManager.saveNotificationPreferences(
                            switchAlerts.isChecked(),
                            switchGlobalData.isChecked(),
                            switchSchedule.isChecked()
                    );
                    Toast.makeText(this, "Preferences saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -- Language & Region -----------------------------------------------------
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
                ArrayAdapter<String> provinceAdapter = new ArrayAdapter<>(ProfileActivity.this,
                        android.R.layout.simple_spinner_item, provinces);
                provinceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerProvince.setAdapter(provinceAdapter);
                String savedProvince = accountManager.getSelectedProvince();
                if (provinces.contains(savedProvince)) {
                    spinnerProvince.setSelection(provinces.indexOf(savedProvince));
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
            LocaleListCompat appLocales = LocaleListCompat.forLanguageTags(langTag);
            AppCompatDelegate.setApplicationLocales(appLocales);
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            recreate();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showPrivacySecurityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_privacy_security, null);
        builder.setView(view);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showHelpSupportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_help_support, null);
        builder.setView(view);
        builder.setPositiveButton("Close", null);
        builder.show();
    }
}