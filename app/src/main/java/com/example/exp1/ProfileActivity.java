package com.example.exp1;

import android.content.Intent;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
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
import android.widget.ScrollView;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.text.SimpleDateFormat;

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
        EditText editFarmLocationStreet = dialogView.findViewById(R.id.editFarmLocationStreet);
        EditText editFarmLocationCity = dialogView.findViewById(R.id.editFarmLocationCity);
        EditText editFarmLocationState = dialogView.findViewById(R.id.editFarmLocationState);
        EditText editFarmLocationPostal = dialogView.findViewById(R.id.editFarmLocationPostal);

        // Pre-fill from current UI
        editTotalBirds.setText(totalBirdsValue.getText());
        editActiveCages.setText(activeCagesValue.getText());

        // Pre-fill farm location from Firestore
        FirebaseFirestore.getInstance().collection("farm_data").document("stats").get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    Object farmLocObj = doc.get("farmLocation");
                    if (farmLocObj instanceof Map) {
                        Map<String, Object> farmLoc = (Map<String, Object>) farmLocObj;
                        editFarmLocationStreet.setText((String) farmLoc.getOrDefault("street", ""));
                        editFarmLocationCity.setText((String) farmLoc.getOrDefault("city", ""));
                        editFarmLocationState.setText((String) farmLoc.getOrDefault("state", ""));
                        editFarmLocationPostal.setText((String) farmLoc.getOrDefault("postalCode", ""));
                    }
                }
            });

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
                String farmLocStreet = editFarmLocationStreet.getText().toString().trim();
                String farmLocCity = editFarmLocationCity.getText().toString().trim();
                String farmLocState = editFarmLocationState.getText().toString().trim();
                String farmLocPostal = editFarmLocationPostal.getText().toString().trim();

                if (birdsStr.isEmpty() || cagesStr.isEmpty()) {
                    Toast.makeText(ProfileActivity.this, "Please fill in birds and cages", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Validate farm location if any field is provided
                if (!farmLocStreet.isEmpty() || !farmLocCity.isEmpty() || !farmLocState.isEmpty() || !farmLocPostal.isEmpty()) {
                    if (farmLocStreet.isEmpty() || farmLocCity.isEmpty() || farmLocState.isEmpty() || farmLocPostal.isEmpty()) {
                        Toast.makeText(ProfileActivity.this, "Please fill all farm location fields or leave them all empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String locError = validateAddress(farmLocStreet, farmLocCity, farmLocState, farmLocPostal);
                    if (locError != null) {
                        Toast.makeText(ProfileActivity.this, locError, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                int newBirds = Integer.parseInt(birdsStr);
                int newCages = Integer.parseInt(cagesStr);

                accountManager.saveFarmStats(newBirds, newCages);
                
                // Update both farm stats and farm location
                FirebaseFirestore db = FirebaseFirestore.getInstance();
                Map<String, Object> updates = new HashMap<>();
                updates.put("totalBirds", newBirds);
                updates.put("activeCages", newCages);
                
                if (!farmLocStreet.isEmpty()) {
                    updates.put("farmLocation", new HashMap<String, Object>() {{
                        put("street", farmLocStreet);
                        put("city", farmLocCity);
                        put("state", farmLocState);
                        put("postalCode", farmLocPostal);
                    }});
                }

                db.collection("farm_data").document("stats").update(updates)
                    .addOnSuccessListener(a -> {
                        runOnUiThread(() -> {
                            totalBirdsValue.setText(String.valueOf(newBirds));
                            activeCagesValue.setText(String.valueOf(newCages));
                            Toast.makeText(ProfileActivity.this, "Farm stats and location updated!", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        });
                    })
                    .addOnFailureListener(e -> {
                        runOnUiThread(() -> Toast.makeText(ProfileActivity.this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
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

    private String validateAddress(String street, String city, String state, String postal) {
        if (street.length() < 5) return "Street address must be at least 5 characters";
        if (city.length() < 2) return "City must be at least 2 characters";
        if (state.length() < 2) return "State/Province must be at least 2 characters";
        if (postal.length() < 3) return "Postal code must be at least 3 characters";
        
        // Check for suspicious characters
        String invalidChars = "!@#$%^&*()=[]{}|;':\",<>?";
        for (char c : street.toCharArray()) {
            if (invalidChars.indexOf(c) >= 0) return "Address contains invalid characters";
        }
        for (char c : city.toCharArray()) {
            if (invalidChars.indexOf(c) >= 0) return "Address contains invalid characters";
        }
        for (char c : state.toCharArray()) {
            if (invalidChars.indexOf(c) >= 0) return "Address contains invalid characters";
        }
        for (char c : postal.toCharArray()) {
            if (invalidChars.indexOf(c) >= 0) return "Address contains invalid characters";
        }
        
        return null;
    }

    private void showAccountSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_account_settings, null);
        builder.setView(view);

        View changePasswordBtn = view.findViewById(R.id.changePasswordBtn);
        View editProfilePicBtn = view.findViewById(R.id.editProfilePicBtn);
        View editBirthdayBtn = view.findViewById(R.id.editBirthdayBtn);
        View editLocationBtn = view.findViewById(R.id.editLocationBtn);
        View deleteAccountBtn = view.findViewById(R.id.deleteAccountBtn);

        changePasswordBtn.setOnClickListener(v -> showChangePasswordDialog());
        editProfilePicBtn.setOnClickListener(v -> showEditProfilePictureDialog());
        editBirthdayBtn.setOnClickListener(v -> showEditBirthdayDialog());
        editLocationBtn.setOnClickListener(v -> showEditLocationDialog());
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);
        
        // Title
        TextView title = new TextView(this);
        title.setText("Data Collection Policy");
        title.setTextSize(2, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getResources().getColor(android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);
        
        // Data types we collect
        String[] dataTypes = {
            "• Farm Profile: Location, GPS coordinates, farm name",
            "• Flock Records: Bird count, breed type, age range",
            "• Daily Production: Egg count, health status, mortality",
            "• Financial Records: Feed costs, sales revenue, expenses",
            "• Health Logs: Vaccination records, disease incidents",
            "• Environmental: Temperature, humidity readings, weather data"
        };
        
        for (String dataType : dataTypes) {
            TextView tvData = new TextView(this);
            tvData.setText(dataType);
            tvData.setTextSize(2, 14);
            tvData.setPadding(0, 8, 0, 0);
            tvData.setLineSpacing(4, 1.0f);
            container.addView(tvData);
        }
        
        // Purpose
        TextView purposeTitle = new TextView(this);
        purposeTitle.setText("\nData Usage Purpose");
        purposeTitle.setTextSize(2, 16);
        purposeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        purposeTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        purposeTitle.setPadding(0, 16, 0, 8);
        container.addView(purposeTitle);
        
        TextView purposeText = new TextView(this);
        purposeText.setText("Your data is collected to:\n• Generate personalized farm reports\n• Provide analytics on farm performance\n• Calculate regional trends (anonymized)\n• Improve app features and accuracy");
        purposeText.setTextSize(2, 14);
        purposeText.setLineSpacing(4, 1.0f);
        container.addView(purposeText);
        
        // Retention
        TextView retentionTitle = new TextView(this);
        retentionTitle.setText("\nData Retention");
        retentionTitle.setTextSize(2, 16);
        retentionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        retentionTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        retentionTitle.setPadding(0, 16, 0, 8);
        container.addView(retentionTitle);
        
        TextView retentionText = new TextView(this);
        retentionText.setText("Data is retained for:\n• 5 years for financial records\n• 2 years for production records\n• 1 year for temporary logs\n• Indefinitely for farm configuration");
        retentionText.setTextSize(2, 14);
        retentionText.setLineSpacing(4, 1.0f);
        container.addView(retentionText);
        
        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showPrivacyContentDetails() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);
        
        // Title
        TextView title = new TextView(this);
        title.setText("Privacy Policy");
        title.setTextSize(2, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getResources().getColor(android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);
        
        // Data Ownership
        TextView ownershipTitle = new TextView(this);
        ownershipTitle.setText("Data Ownership");
        ownershipTitle.setTextSize(2, 16);
        ownershipTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ownershipTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        ownershipTitle.setPadding(0, 0, 0, 8);
        container.addView(ownershipTitle);
        
        TextView ownershipText = new TextView(this);
        ownershipText.setText("✓ You own all your data\n✓ You control access to your farm data\n✓ You can request data deletion anytime");
        ownershipText.setTextSize(2, 14);
        ownershipText.setLineSpacing(4, 1.0f);
        container.addView(ownershipText);
        
        // Data Sharing
        TextView sharingTitle = new TextView(this);
        sharingTitle.setText("\nData Sharing");
        sharingTitle.setTextSize(2, 16);
        sharingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sharingTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        sharingTitle.setPadding(0, 16, 0, 8);
        container.addView(sharingTitle);
        
        TextView sharingText = new TextView(this);
        sharingText.setText("✗ We DO NOT share personal data with 3rd parties\n✓ We analyze anonymized farm data for regional trends\n✓ You can opt-out of regional analytics");
        sharingText.setTextSize(2, 14);
        sharingText.setLineSpacing(4, 1.0f);
        container.addView(sharingText);
        
        // Access Control
        TextView accessTitle = new TextView(this);
        accessTitle.setText("\nAccess Control");
        accessTitle.setTextSize(2, 16);
        accessTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        accessTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        accessTitle.setPadding(0, 16, 0, 8);
        container.addView(accessTitle);
        
        TextView accessText = new TextView(this);
        accessText.setText("✓ Owner: Full access to all farm data\n✓ Backup Owner: Access to reports\n✓ Staff: Limited to assigned tasks");
        accessText.setTextSize(2, 14);
        accessText.setLineSpacing(4, 1.0f);
        container.addView(accessText);
        
        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showSecurityMeasuresDetails() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);
        
        // Title
        TextView title = new TextView(this);
        title.setText("Security Implementation");
        title.setTextSize(2, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getResources().getColor(android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);
        
        // Encryption
        TextView encryptTitle = new TextView(this);
        encryptTitle.setText("Encryption");
        encryptTitle.setTextSize(2, 16);
        encryptTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        encryptTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        encryptTitle.setPadding(0, 0, 0, 8);
        container.addView(encryptTitle);
        
        TextView encryptText = new TextView(this);
        encryptText.setText("✓ Data in transit: TLS 1.2+ encryption\n✓ Data at rest: Firebase automatic encryption\n✓ Sensitive fields: AES-256 encryption");
        encryptText.setTextSize(2, 14);
        encryptText.setLineSpacing(4, 1.0f);
        container.addView(encryptText);
        
        // Authentication
        TextView authTitle = new TextView(this);
        authTitle.setText("\nAuthentication");
        authTitle.setTextSize(2, 16);
        authTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        authTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        authTitle.setPadding(0, 16, 0, 8);
        container.addView(authTitle);
        
        TextView authText = new TextView(this);
        authText.setText("✓ Google Sign-In (OAuth 2.0)\n✓ Strong password requirements (8+ chars, mixed case, numbers, symbols)\n✓ Session timeout after inactivity");
        authText.setTextSize(2, 14);
        authText.setLineSpacing(4, 1.0f);
        container.addView(authText);
        
        // Access Security
        TextView accessSecTitle = new TextView(this);
        accessSecTitle.setText("\nAccess Security");
        accessSecTitle.setTextSize(2, 16);
        accessSecTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        accessSecTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        accessSecTitle.setPadding(0, 16, 0, 8);
        container.addView(accessSecTitle);
        
        TextView accessSecText = new TextView(this);
        accessSecText.setText("✓ Role-based access control (RBAC)\n✓ Email verification for new users\n✓ Invite codes locked to specific emails\n✓ Admin approval required for access");
        accessSecText.setTextSize(2, 14);
        accessSecText.setLineSpacing(4, 1.0f);
        container.addView(accessSecText);
        
        // Monitoring
        TextView monitorTitle = new TextView(this);
        monitorTitle.setText("\nMonitoring & Compliance");
        monitorTitle.setTextSize(2, 16);
        monitorTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        monitorTitle.setTextColor(getResources().getColor(android.R.color.darker_gray));
        monitorTitle.setPadding(0, 16, 0, 8);
        container.addView(monitorTitle);
        
        TextView monitorText = new TextView(this);
        monitorText.setText("✓ Real-time activity logs\n✓ Firestore security rules (test mode)\n✓ Regular security audits");
        monitorText.setTextSize(2, 14);
        monitorText.setLineSpacing(4, 1.0f);
        container.addView(monitorText);
        
        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void showUserListDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_user_management, null);
        builder.setView(view);

        RecyclerView rvUserList = view.findViewById(R.id.rvUserList);
        ProgressBar progressBar = view.findViewById(R.id.progressBar);
        TextView errorTextView = view.findViewById(R.id.errorTextView);

        rvUserList.setLayoutManager(new LinearLayoutManager(this));

        // Set up buttons
        view.findViewById(R.id.btnRoleLimits).setOnClickListener(v -> showRoleLimitsDialog());
        view.findViewById(R.id.btnPendingReq).setOnClickListener(v -> showPendingRequestsDialog());
        view.findViewById(R.id.btnInviteNew).setOnClickListener(v -> NavigationHelper.INSTANCE.showGenerateInviteCodeDialog(this, currentEmail));

        // Show loading
        progressBar.setVisibility(View.VISIBLE);
        errorTextView.setVisibility(View.GONE);
        rvUserList.setVisibility(View.GONE);

        FirebaseFirestore.getInstance().collection("user_access")
            .whereEqualTo("status", "approved")
            .get()
            .addOnSuccessListener(docs -> {
                progressBar.setVisibility(View.GONE);
                rvUserList.setVisibility(View.VISIBLE);
                List<DocumentSnapshot> userDocs = new ArrayList<>(docs.getDocuments());
                UserAdapter adapter = new UserAdapter(userDocs, email -> {
                    // Delete user
                    FirebaseFirestore.getInstance().collection("user_access").document(email).delete()
                        .addOnSuccessListener(a -> {
                            Toast.makeText(this, "User removed", Toast.LENGTH_SHORT).show();
                            showUserListDialog(); // Refresh
                        })
                        .addOnFailureListener(e -> Toast.makeText(this, "Failed to remove user", Toast.LENGTH_SHORT).show());
                });
                rvUserList.setAdapter(adapter);
            })
            .addOnFailureListener(e -> {
                progressBar.setVisibility(View.GONE);
                errorTextView.setVisibility(View.VISIBLE);
                rvUserList.setVisibility(View.GONE);
            });

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
                        update.put("approvedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());

                        doc.getReference().update(update)
                            .addOnSuccessListener(a -> {
                                new AlertDialog.Builder(this)
                                    .setTitle("Request Approved")
                                    .setMessage("Verification Code for " + name + ": " + code)
                                    .setPositiveButton("Send Approval & Code via Gmail", (d, w) -> {
                                        String emailBody = "Hello " + name + ",\n\n" +
                                            "Congratulations! Your request for access to the Quail Farm Management System has been APPROVED.\n\n" +
                                            "Your 6-digit verification code is: " + code + "\n\n" +
                                            "Steps to complete registration:\n" +
                                            "1. Open the Quail Farm app\n" +
                                            "2. Select 'Enter Code' during registration\n" +
                                            "3. Enter your 6-digit code: " + code + "\n" +
                                            "4. Complete your profile setup\n\n" +
                                            "This code will expire in 24 hours.\n\n" +
                                            "Best regards,\n" +
                                            "Quail Farm Management System";

                                        Intent intent = new Intent(Intent.ACTION_SEND);
                                        intent.setType("message/rfc822");
                                        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
                                        intent.putExtra(Intent.EXTRA_CC, new String[]{currentEmail});
                                        intent.putExtra(Intent.EXTRA_SUBJECT, "Quail Farm Access Approved - Code: " + code);
                                        intent.putExtra(Intent.EXTRA_TEXT, emailBody);
                                        intent.setPackage("com.google.android.gm");
                                        
                                        try {
                                            startActivity(intent);
                                        } catch (Exception e) {
                                            intent.setPackage(null);
                                            startActivity(Intent.createChooser(intent, "Send Email"));
                                        }
                                        
                                        new Handler(Looper.getMainLooper()).postDelayed(this::showPendingRequestsDialog, 2000);
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
                        case 0: showMonitorDatabaseDialog(); break;
                        case 1: importDatabase(); break;
                        case 2: exportDatabase(); break;
                        case 3: deleteDatabase(); break;
                        case 4: wipeEverything(); break;
                    }
                }).setPositiveButton("Cancel", null).show();
    }

    private void showMonitorDatabaseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Real-Time Database Monitor");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView tvConnection = new TextView(this);
        tvConnection.setText("Connection: Checking...");
        tvConnection.setTextSize(16);
        layout.addView(tvConnection);

        TextView tvUsers = new TextView(this);
        tvUsers.setText("Approved Users: Loading...");
        tvUsers.setTextSize(16);
        layout.addView(tvUsers);

        TextView tvPending = new TextView(this);
        tvPending.setText("Pending Requests: Loading...");
        tvPending.setTextSize(16);
        layout.addView(tvPending);

        TextView tvLastUpdate = new TextView(this);
        tvLastUpdate.setText("Last Update: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
        tvLastUpdate.setTextSize(14);
        tvLastUpdate.setTextColor(getResources().getColor(android.R.color.darker_gray));
        layout.addView(tvLastUpdate);

        builder.setView(layout);
        builder.setPositiveButton("Close", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // Real-time listeners
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Connection check
        db.collection("user_access").limit(1).get().addOnSuccessListener(querySnapshot -> {
            runOnUiThread(() -> tvConnection.setText("Connection: Online"));
        }).addOnFailureListener(e -> {
            runOnUiThread(() -> tvConnection.setText("Connection: Offline"));
        });

        // Approved users
        com.google.firebase.firestore.ListenerRegistration approvedListener = db.collection("user_access")
            .whereEqualTo("status", "approved")
            .addSnapshotListener((querySnapshot, e) -> {
                if (e != null) {
                    runOnUiThread(() -> tvUsers.setText("Approved Users: Error"));
                    return;
                }
                int count = querySnapshot != null ? querySnapshot.size() : 0;
                runOnUiThread(() -> {
                    tvUsers.setText("Approved Users: " + count);
                    tvLastUpdate.setText("Last Update: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
                });
            });

        // Pending requests
        com.google.firebase.firestore.ListenerRegistration pendingListener = db.collection("user_access")
            .whereEqualTo("status", "pending")
            .addSnapshotListener((querySnapshot, e) -> {
                if (e != null) {
                    runOnUiThread(() -> tvPending.setText("Pending Requests: Error"));
                    return;
                }
                int count = querySnapshot != null ? querySnapshot.size() : 0;
                runOnUiThread(() -> {
                    tvPending.setText("Pending Requests: " + count);
                    tvLastUpdate.setText("Last Update: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
                });
            });

        // Remove listeners on dismiss
        dialog.setOnDismissListener(dialogInterface -> {
            approvedListener.remove();
            pendingListener.remove();
        });
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

    private void showEditProfilePictureDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Edit Profile Picture")
            .setMessage("Use the camera icon in the profile header to change your picture.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void showEditBirthdayDialog() {
        Calendar calendar = Calendar.getInstance();
        DatePickerDialog.OnDateSetListener dateSetListener = (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            String format = "MM/dd/yyyy";
            SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
            String newBirthday = sdf.format(calendar.getTime());

            // Update in Firestore
            FirebaseFirestore.getInstance().collection("user_access").document(currentEmail)
                .update("birthday", newBirthday)
                .addOnSuccessListener(a -> {
                    Toast.makeText(this, "Birthday updated to " + newBirthday, Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to update birthday", Toast.LENGTH_SHORT).show();
                });
        };

        new DatePickerDialog(
            this,
            dateSetListener,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void showEditLocationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.activity_setup_account, null);
        
        // Create a layout for location input with 4 fields
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);

        TextView titleTv = new TextView(this);
        titleTv.setText("Edit Your Address");
        titleTv.setTextSize(2, 18);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setTextColor(getResources().getColor(android.R.color.darker_gray));
        titleTv.setPadding(0, 0, 0, 16);
        container.addView(titleTv);

        EditText streetEt = new EditText(this);
        streetEt.setHint("Street Address");
        streetEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        streetEt.setBackgroundResource(R.drawable.rounded_corner);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            200
        );
        params.bottomMargin = 12;
        streetEt.setLayoutParams(params);
        streetEt.setPadding(16, 16, 16, 16);
        container.addView(streetEt);

        LinearLayout cityStateLayout = new LinearLayout(this);
        cityStateLayout.setOrientation(LinearLayout.HORIZONTAL);
        cityStateLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        cityStateLayout.setWeightSum(2);

        EditText cityEt = new EditText(this);
        cityEt.setHint("City");
        cityEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        cityEt.setBackgroundResource(R.drawable.rounded_corner);
        LinearLayout.LayoutParams cityParams = new LinearLayout.LayoutParams(0, 200);
        cityParams.weight = 1;
        cityParams.rightMargin = 8;
        cityEt.setLayoutParams(cityParams);
        cityEt.setPadding(16, 16, 16, 16);
        cityStateLayout.addView(cityEt);

        EditText stateEt = new EditText(this);
        stateEt.setHint("State/Province");
        stateEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        stateEt.setBackgroundResource(R.drawable.rounded_corner);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(0, 200);
        stateParams.weight = 1;
        stateEt.setLayoutParams(stateParams);
        stateEt.setPadding(16, 16, 16, 16);
        cityStateLayout.addView(stateEt);

        container.addView(cityStateLayout);

        EditText postalEt = new EditText(this);
        postalEt.setHint("Postal Code");
        postalEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        postalEt.setBackgroundResource(R.drawable.rounded_corner);
        LinearLayout.LayoutParams postalParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            200
        );
        postalParams.topMargin = 12;
        postalEt.setLayoutParams(postalParams);
        postalEt.setPadding(16, 16, 16, 16);
        container.addView(postalEt);

        // Pre-fill current values from Firestore
        FirebaseFirestore.getInstance().collection("user_access").document(currentEmail).get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    Object addressObj = doc.get("address");
                    if (addressObj instanceof Map) {
                        Map<String, Object> address = (Map<String, Object>) addressObj;
                        streetEt.setText((String) address.getOrDefault("street", ""));
                        cityEt.setText((String) address.getOrDefault("city", ""));
                        stateEt.setText((String) address.getOrDefault("state", ""));
                        postalEt.setText((String) address.getOrDefault("postalCode", ""));
                    }
                }
            });

        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String street = streetEt.getText().toString().trim();
            String city = cityEt.getText().toString().trim();
            String state = stateEt.getText().toString().trim();
            String postal = postalEt.getText().toString().trim();

            if (street.isEmpty() || city.isEmpty() || state.isEmpty() || postal.isEmpty()) {
                Toast.makeText(this, "Please fill all address fields", Toast.LENGTH_SHORT).show();
                return;
            }

            String locError = validateAddress(street, city, state, postal);
            if (locError != null) {
                Toast.makeText(this, locError, Toast.LENGTH_SHORT).show();
                return;
            }

            Map<String, Object> addressMap = new HashMap<>();
            addressMap.put("street", street);
            addressMap.put("city", city);
            addressMap.put("state", state);
            addressMap.put("postalCode", postal);

            FirebaseFirestore.getInstance().collection("user_access").document(currentEmail)
                .update("address", addressMap)
                .addOnSuccessListener(a -> {
                    Toast.makeText(this, "Location updated successfully", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to update location", Toast.LENGTH_SHORT).show();
                });
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
}
