package com.example.exp1;

import android.content.Intent;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private TextView userRoleTv;
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
            Toast.makeText(this, getString(R.string.detected_eggs, total), Toast.LENGTH_SHORT).show();
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
        userRoleTv       = findViewById(R.id.userRole);
        profileInitialTv = findViewById(R.id.profileInitial);
        profileImageView = findViewById(R.id.profileImage);

        if (userRoleTv != null) {
            String roleDisplayName = RoleManager.Companion.displayName(userRole != null ? userRole : "staff");
            userRoleTv.setText(getString(R.string.role_label, roleDisplayName));
        }

        // Resolve email
        currentEmail = getIntent().getStringExtra("username");
        if (currentEmail == null || currentEmail.isEmpty()) {
            currentEmail = currentSession;
        }
        
        if (currentEmail == null) currentEmail = "";

        firestoreManager = new FirestoreManager(currentEmail);

        showLoading(getString(R.string.syncing_profile), () -> {
            fetchUserData() ;
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
            if (isAdmin()) {
                notificationPrefsButton.setVisibility(View.VISIBLE);
                notificationPrefsButton.setOnClickListener(v -> showNotificationPrefsDialog());
            } else {
                notificationPrefsButton.setVisibility(View.GONE);
            }
        }

        //  Admin Management Section -----------------------------------------------------------
        View adminGroupLabel = findViewById(R.id.adminGroupLabel);
        View adminCard = findViewById(R.id.adminCard);
        View userListButton = findViewById(R.id.userListButton);
        View databaseManagementButton = findViewById(R.id.databaseManagementButton);

        if (isAdmin()) {
            if (adminGroupLabel != null) adminGroupLabel.setVisibility(View.VISIBLE);
            if (adminCard != null) adminCard.setVisibility(View.VISIBLE);
            
            if (userListButton != null) {
                userListButton.setOnClickListener(v -> showUserListDialog());
            }
            if (databaseManagementButton != null) {
                databaseManagementButton.setOnClickListener(v -> showDatabaseManagementDialog());
            }

            // Also keep the existing ones if they exist in XML
            View generateInviteButton = findViewById(R.id.generateInviteButton);
            if (generateInviteButton != null) {
                generateInviteButton.setOnClickListener(v -> NavigationHelper.INSTANCE.showGenerateInviteCodeDialog(this, currentEmail));
            }
            View manageUsersButton = findViewById(R.id.manageUsersButton);
            if (manageUsersButton != null) {
                manageUsersButton.setOnClickListener(v -> showPendingRequestsDialog());
            }
        } else {
            if (adminGroupLabel != null) adminGroupLabel.setVisibility(View.GONE);
            if (adminCard != null) adminCard.setVisibility(View.GONE);
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
                        percentText.setText(getString(R.string.percent_placeholder, progress[0]));
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
                    String role = doc.getString("role");
                    
                    if (name != null) {
                        userNameTv.setText(name);
                        profileInitialTv.setText(String.valueOf(name.charAt(0)).toUpperCase());
                    }
                    userEmailTv.setText(currentEmail);

                    if (userRoleTv != null && role != null) {
                        userRoleTv.setText(getString(R.string.role_label, RoleManager.Companion.displayName(role)));
                    }
                    
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
        return "owner".equals(userRole) || "backup_owner".equals(userRole);
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
                runOnUiThread(() -> Toast.makeText(ProfileActivity.this, getString(R.string.error_loading_farm_data), Toast.LENGTH_SHORT).show());
            }
        });
    }

    //  Edit Name dialog (pen button)---------------------------------------------------------------
    private void showEditNameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.edit_name));

        final EditText input = new EditText(this);
        input.setText(userNameTv.getText().toString());
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(this, getString(R.string.name_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            firestoreManager.saveName(newName, new FirestoreManager.OnSaveListener() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        userNameTv.setText(newName);
                        profileInitialTv.setText(String.valueOf(newName.charAt(0)).toUpperCase());
                        Toast.makeText(ProfileActivity.this, getString(R.string.name_updated), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> Toast.makeText(ProfileActivity.this, getString(R.string.failed_to_update), Toast.LENGTH_SHORT).show());
                }
            });
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    //  Farm Recalibration dialog ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
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
                .setTitle(getString(R.string.farm_recalibration))
                .setPositiveButton(getString(R.string.save), null)
                .setNeutralButton(getString(R.string.restart_days), null)
                .setNegativeButton(getString(R.string.cancel), null)
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
                    Toast.makeText(ProfileActivity.this, getString(R.string.fill_birds_cages), Toast.LENGTH_SHORT).show();
                    return;
                }

                // Validate farm location if any field is provided
                if (!farmLocStreet.isEmpty() || !farmLocCity.isEmpty() || !farmLocState.isEmpty() || !farmLocPostal.isEmpty()) {
                    if (farmLocStreet.isEmpty() || farmLocCity.isEmpty() || farmLocState.isEmpty() || farmLocPostal.isEmpty()) {
                        Toast.makeText(ProfileActivity.this, getString(R.string.fill_all_location), Toast.LENGTH_SHORT).show();
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
                FirebaseFirestore db_ = FirebaseFirestore.getInstance();
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

                db_.collection("farm_data").document("stats").update(updates)
                    .addOnSuccessListener(a -> {
                        runOnUiThread(() -> {
                            totalBirdsValue.setText(String.valueOf(newBirds));
                            activeCagesValue.setText(String.valueOf(newCages));
                            Toast.makeText(ProfileActivity.this, getString(R.string.farm_stats_updated), Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        });
                    })
                    .addOnFailureListener(e -> runOnUiThread(() -> Toast.makeText(ProfileActivity.this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_SHORT).show()));
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(ProfileActivity.this)
                    .setTitle(getString(R.string.restart_days_title))
                    .setMessage(getString(R.string.restart_days_message))
                    .setPositiveButton(getString(R.string.restart), (d2, w) -> firestoreManager.restartDaysRunning(new FirestoreManager.OnSaveListener() {
                        @Override
                        public void onSuccess() {
                            runOnUiThread(() -> {
                                daysRunningValue.setText("1");
                                Toast.makeText(ProfileActivity.this, getString(R.string.days_restarted), Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            });
                        }
                        @Override
                        public void onError(Exception e) {
                            runOnUiThread(() -> Toast.makeText(ProfileActivity.this, getString(R.string.restart_failed), Toast.LENGTH_SHORT).show());
                        }
                    }))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show());
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
        builder.setTitle(getString(R.string.change_password));
        View view = getLayoutInflater().inflate(R.layout.dialog_change_password, null);
        builder.setView(view);

        EditText oldPass = view.findViewById(R.id.oldPassword);
        EditText newPass = view.findViewById(R.id.newPassword);
        TextInputLayout newPasswordLayout = view.findViewById(R.id.newPasswordLayout);

        // Auto-detect new password format
        newPass.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String pass = s.toString();
                if (!pass.isEmpty()) {
                    String error = getPasswordStrengthError(pass);
                    newPasswordLayout.setError(error);
                } else {
                    newPasswordLayout.setError(null);
                }
            }
        });

        builder.setPositiveButton(getString(R.string.update), null);
        builder.setNegativeButton(getString(R.string.cancel), null);
        
        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String oldP = oldPass.getText().toString().trim();
            String newP = newPass.getText().toString().trim();
            
            String error = getPasswordStrengthError(newP);
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                return;
            }

            if (currentEmail == null || currentEmail.isEmpty()) return;

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

            FirebaseFirestore.getInstance().collection("user_access").document(currentEmail).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String remotePass = doc.getString("password");
                        if (remotePass != null && remotePass.equals(oldP)) {
                            // Match! Update Remote
                            doc.getReference().update("password", newP)
                                .addOnSuccessListener(aVoid -> {
                                    // Update Local Cache
                                    accountManager.forceUpdatePassword(currentEmail, newP);
                                    Toast.makeText(ProfileActivity.this, getString(R.string.password_updated), Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(ProfileActivity.this, "Failed to update database", Toast.LENGTH_SHORT).show();
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                });
                        } else {
                            Toast.makeText(ProfileActivity.this, getString(R.string.incorrect_password), Toast.LENGTH_SHORT).show();
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        }
                    } else {
                        Toast.makeText(ProfileActivity.this, "User profile not found", Toast.LENGTH_SHORT).show();
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(ProfileActivity.this, "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                });
        });
    }

    private String getPasswordStrengthError(String password) {
        if (password.length() < 8) return "Password must be at least 8 characters long";
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSymbol = false;
        String symbols = "!@#$%^&*()_+-=[]{}|;':\",./<>?";
        
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else if (symbols.indexOf(c) >= 0) hasSymbol = true;
        }
        
        if (!hasUpper) return "Password must contain at least one uppercase letter";
        if (!hasLower) return "Password must contain at least one lowercase letter";
        if (!hasDigit) return "Password must contain at least one digit";
        if (!hasSymbol) return "Password must contain at least one special character";
        
        return null;
    }

    private void showDeleteAccountDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_account))
                .setMessage(getString(R.string.confirm_delete_account))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    String currentUser = accountManager.getCurrentUsername();
                    if (currentUser != null && accountManager.deleteAccount(currentUser)) {
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showNotificationPrefsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_notification_preferences, null);
        builder.setView(dialogView);

        SwitchCompat switchAlerts     = dialogView.findViewById(R.id.switchAlerts);
        SwitchCompat switchGlobalData = dialogView.findViewById(R.id.switchGlobalData);
        SwitchCompat switchSchedule   = dialogView.findViewById(R.id.switchSchedule);
        SwitchCompat switchEggCount   = dialogView.findViewById(R.id.switchEggCount);
        Button btnEggCountTime        = dialogView.findViewById(R.id.btnEggCountTime);

        switchAlerts.setChecked(accountManager.isAlertsEnabled());
        switchGlobalData.setChecked(accountManager.isGlobalDataEnabled());
        switchSchedule.setChecked(accountManager.isScheduleEnabled());
        switchEggCount.setChecked(accountManager.isEggCountEnabled());

        // Set time button text
        int hour = accountManager.getEggCountHour();
        int minute = accountManager.getEggCountMinute();
        btnEggCountTime.setText(String.format(Locale.getDefault(), "%02d:%02d", hour, minute));

        // Time picker for egg count
        btnEggCountTime.setOnClickListener(v -> {
            android.app.TimePickerDialog timePicker = new android.app.TimePickerDialog(this, (view, selectedHour, selectedMinute) -> btnEggCountTime.setText(String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute)), hour, minute, false);
            timePicker.show();
        });

        builder.setTitle(getString(R.string.notification_preferences))
                .setPositiveButton(getString(R.string.save), (dialog, which) -> {
                    String[] selectedTime = btnEggCountTime.getText().toString().split(":");
                    int eggHour = Integer.parseInt(selectedTime[0]);
                    int eggMinute = Integer.parseInt(selectedTime[1]);
                    accountManager.saveNotificationPreferences(switchAlerts.isChecked(), switchGlobalData.isChecked(), switchSchedule.isChecked(), switchEggCount.isChecked(), eggHour, eggMinute);
                    Toast.makeText(this, getString(R.string.preferences_saved), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null)
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
        regionProvinceMap.put("National Capital Region (NCR)", List.of("Metro Manila"));
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

        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String selectedLang = spinnerLanguage.getSelectedItem().toString();
            String selectedReg  = spinnerRegion.getSelectedItem().toString();
            String selectedProv = spinnerProvince.getSelectedItem().toString();
            
            // 1. Save locally
            accountManager.saveLanguageRegion(selectedLang, selectedReg, selectedProv);
            
            // 2. Map to language tag
            String langTag = "en";
            if (selectedLang.equals("Tagalog")) langTag = "fil";
            else if (selectedLang.equals("Cebuano")) langTag = "ceb";
            
            // 3. Set Locales
            LocaleListCompat appLocales = LocaleListCompat.forLanguageTags(langTag);
            AppCompatDelegate.setApplicationLocales(appLocales);
            
            // 4. INSTANT REFRESH: Force a clean restart of the activity stack
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent intent = getIntent();
                finish();
                overridePendingTransition(0, 0);
                startActivity(intent);
                overridePendingTransition(0, 0);
            }, 100);
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }

    private void showPrivacySecurityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_privacy_security, null);
        builder.setView(view);

        // Content Sections (Non-admin clickable)
        View sectionDataCollection = view.findViewById(R.id.sectionDataCollection);
        View sectionPrivacyContent = view.findViewById(R.id.sectionPrivacyContent);
        View sectionSecurityMeasures = view.findViewById(R.id.sectionSecurityMeasures);

        if (sectionDataCollection != null) sectionDataCollection.setOnClickListener(v -> showDataCollectionDetails());
        if (sectionPrivacyContent != null) sectionPrivacyContent.setOnClickListener(v -> showPrivacyContentDetails());
        if (sectionSecurityMeasures != null) sectionSecurityMeasures.setOnClickListener(v -> showSecurityMeasuresDetails());

        builder.setPositiveButton(getString(R.string.close), null);
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
        title.setText(R.string.data_collection_policy_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
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
            tvData.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvData.setPadding(0, 8, 0, 0);
            tvData.setLineSpacing(4, 1.0f);
            container.addView(tvData);
        }
        
        // Purpose
        TextView purposeTitle = new TextView(this);
        purposeTitle.setText(getString(R.string.data_usage_purpose_title));
        purposeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        purposeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        purposeTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        purposeTitle.setPadding(0, 16, 0, 8);
        container.addView(purposeTitle);
        
        TextView purposeText = new TextView(this);
        purposeText.setText("Your data is collected to:\n• Generate personalized farm reports\n• Provide analytics on farm performance\n• Calculate regional trends (anonymized)\n• Improve app features and accuracy");
        purposeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        purposeText.setLineSpacing(4, 1.0f);
        container.addView(purposeText);
        
        // Retention
        TextView retentionTitle = new TextView(this);
        retentionTitle.setText(getString(R.string.data_retention_title));
        retentionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        retentionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        retentionTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        retentionTitle.setPadding(0, 16, 0, 8);
        container.addView(retentionTitle);
        
        TextView retentionText = new TextView(this);
        retentionText.setText("Data is retained for:\n• 5 years for financial records\n• 2 years for production records\n• 1 year for temporary logs\n• Indefinitely for farm configuration");
        retentionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        retentionText.setLineSpacing(4, 1.0f);
        container.addView(retentionText);
        
        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton(getString(R.string.close), null);
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
        title.setText(R.string.privacy_policy_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);
        
        // Data Ownership
        TextView ownershipTitle = new TextView(this);
        ownershipTitle.setText(R.string.data_ownership_title);
        ownershipTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ownershipTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ownershipTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        ownershipTitle.setPadding(0, 0, 0, 8);
        container.addView(ownershipTitle);
        
        TextView ownershipText = new TextView(this);
        ownershipText.setText("✓ You own all your data\n✓ You control access to your farm data\n✓ You can request data deletion anytime");
        ownershipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ownershipText.setLineSpacing(4, 1.0f);
        container.addView(ownershipText);
        
        // Data Sharing
        TextView sharingTitle = new TextView(this);
        sharingTitle.setText(getString(R.string.data_sharing_title));
        sharingTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sharingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sharingTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        sharingTitle.setPadding(0, 16, 0, 8);
        container.addView(sharingTitle);
        
        TextView sharingText = new TextView(this);
        sharingText.setText("✗ We DO NOT share personal data with 3rd parties\n✓ We analyze anonymized farm data for regional trends\n✓ You can opt-out of regional analytics");
        sharingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sharingText.setLineSpacing(4, 1.0f);
        container.addView(sharingText);
        
        // Access Control
        TextView accessTitle = new TextView(this);
        accessTitle.setText(getString(R.string.access_control_title));
        accessTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        accessTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        accessTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        accessTitle.setPadding(0, 16, 0, 8);
        container.addView(accessTitle);
        
        TextView accessText = new TextView(this);
        accessText.setText("✓ Farm Owner: Full access to all farm data\n✓ Co Farm Owner: Access to reports\n✓ Farm Staff: Limited to assigned tasks");
        accessText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        accessText.setLineSpacing(4, 1.0f);
        container.addView(accessText);
        
        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton(getString(R.string.close), null);
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
        title.setText(R.string.security_implementation_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);
        
        // Encryption
        TextView encryptTitle = new TextView(this);
        encryptTitle.setText(R.string.encryption_title);
        encryptTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        encryptTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        encryptTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        encryptTitle.setPadding(0, 0, 0, 8);
        container.addView(encryptTitle);
        
        TextView encryptText = new TextView(this);
        encryptText.setText("✓ Data in transit: TLS 1.2+ encryption\n✓ Data at rest: Firebase automatic encryption\n✓ Sensitive fields: AES-256 encryption");
        encryptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        encryptText.setLineSpacing(4, 1.0f);
        container.addView(encryptText);
        
        // Authentication
        TextView authTitle = new TextView(this);
        authTitle.setText(getString(R.string.authentication_title));
        authTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        authTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        authTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        authTitle.setPadding(0, 16, 0, 8);
        container.addView(authTitle);
        
        TextView authText = new TextView(this);
        authText.setText("✓ Google Sign-In (OAuth 2.0)\n✓ Strong password requirements (8+ chars, mixed case, numbers, symbols)\n✓ Session timeout after inactivity");
        authText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        authText.setLineSpacing(4, 1.0f);
        container.addView(authText);
        
        // Access Security
        TextView accessSecTitle = new TextView(this);
        accessSecTitle.setText(getString(R.string.access_security_title));
        accessSecTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        accessSecTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        accessSecTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        accessSecTitle.setPadding(0, 16, 0, 8);
        container.addView(accessSecTitle);
        
        TextView accessSecText = new TextView(this);
        accessSecText.setText("✓ Role-based access control (RBAC)\n✓ Email verification for new users\n✓ Invite codes locked to specific emails\n✓ Admin approval required for access");
        accessSecText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        accessSecText.setLineSpacing(4, 1.0f);
        container.addView(accessSecText);
        
        // Monitoring
        TextView monitorTitle = new TextView(this);
        monitorTitle.setText(getString(R.string.monitoring_compliance_title));
        monitorTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        monitorTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        monitorTitle.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        monitorTitle.setPadding(0, 16, 0, 8);
        container.addView(monitorTitle);
        
        TextView monitorText = new TextView(this);
        monitorText.setText("✓ Real-time activity logs\n✓ Firestore security rules (test mode)\n✓ Regular security audits");
        monitorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        monitorText.setLineSpacing(4, 1.0f);
        container.addView(monitorText);
        
        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton(getString(R.string.close), null);
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
        view.findViewById(R.id.btnViewCodes).setOnClickListener(v -> showInviteCodesManagementDialog());

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
                            Toast.makeText(this, getString(R.string.user_removed), Toast.LENGTH_SHORT).show();
                            showUserListDialog(); // Refresh
                        })
                        .addOnFailureListener(e -> Toast.makeText(this, getString(R.string.failed_to_remove_user), Toast.LENGTH_SHORT).show());
                });
                rvUserList.setAdapter(adapter);
            })
            .addOnFailureListener(e -> {
                progressBar.setVisibility(View.GONE);
                errorTextView.setVisibility(View.VISIBLE);
                rvUserList.setVisibility(View.GONE);
            });

        builder.setPositiveButton(getString(R.string.close), null);
        builder.show();
    }

    private void showInviteCodesManagementDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Generated Invite Codes");
        
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(40, 40, 40, 40);
        
        FirebaseFirestore.getInstance().collection("invite_codes").get()
            .addOnSuccessListener(docs -> {
                container.removeAllViews();
                if (docs.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText(R.string.no_active_invite_codes);
                    container.addView(tv);
                }
                for (DocumentSnapshot doc : docs) {
                    View row = getLayoutInflater().inflate(R.layout.item_user_manage, container, false);
                    String code = doc.getId();
                    String invitedEmail = doc.getString("invitedEmail");
                    String role = doc.getString("role");

                    ((TextView)row.findViewById(R.id.userNameText)).setText(getString(R.string.invite_code_placeholder, code, invitedEmail));
                    ((TextView)row.findViewById(R.id.userRoleText)).setText(getString(R.string.role_display_placeholder, RoleManager.Companion.displayName(role != null ? role : "staff")));

                    ImageButton deleteBtn = row.findViewById(R.id.deleteUserBtn);
                    deleteBtn.setOnClickListener(v -> doc.getReference().delete().addOnSuccessListener(a -> {
                        Toast.makeText(this, "Invite code deleted", Toast.LENGTH_SHORT).show();
                        showInviteCodesManagementDialog(); // refresh
                    }));
                    container.addView(row);
                }
            });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(container);
        builder.setView(scroll);
        builder.setPositiveButton(getString(R.string.close), null);
        builder.show();
    }

    private void showRoleLimitsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.role_limits));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        EditText editBackupLimit = new EditText(this);
        editBackupLimit.setHint(R.string.max_backup_owners_hint);
        layout.addView(editBackupLimit);

        EditText editStaffLimit = new EditText(this);
        editStaffLimit.setHint(R.string.max_staff_hint);
        layout.addView(editStaffLimit);

        FirebaseFirestore.getInstance().collection("system_settings").document("role_limits").get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    editBackupLimit.setText(String.valueOf(doc.getLong("backup_owner_limit")));
                    editStaffLimit.setText(String.valueOf(doc.getLong("staff_limit")));
                }
            });

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.save), (d, w) -> {
            Map<String, Object> limits = new HashMap<>();
            limits.put("backup_owner_limit", Long.parseLong(editBackupLimit.getText().toString()));
            limits.put("staff_limit", Long.parseLong(editStaffLimit.getText().toString()));

            FirebaseFirestore.getInstance().collection("system_settings").document("role_limits").set(limits)
                .addOnSuccessListener(a -> Toast.makeText(this, getString(R.string.limits_updated), Toast.LENGTH_SHORT).show());
        });
        builder.show();
    }

    private void showPendingRequestsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.pending_access_requests));
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
                    tv.setText(getString(R.string.no_pending_requests));
                    container.addView(tv);
                }
                for (DocumentSnapshot doc : docs) {
                    View row = getLayoutInflater().inflate(R.layout.item_user_manage, container, false);
                    String name = doc.getString("name");
                    String role = doc.getString("role");
                    String email = doc.getId();

                    ((TextView)row.findViewById(R.id.userNameText)).setText(getString(R.string.user_email_placeholder, name, email));
                    ((TextView)row.findViewById(R.id.userRoleText)).setText(getString(R.string.role_display_placeholder, RoleManager.Companion.displayName(role != null ? role : "staff")));

                    ImageButton approveBtn = row.findViewById(R.id.deleteUserBtn);
                    approveBtn.setImageResource(android.R.drawable.ic_menu_save); // reuse button for approve
                    
                    ImageButton rejectBtn = row.findViewById(R.id.rejectBtn);
                    rejectBtn.setVisibility(View.VISIBLE);
                    
                    rejectBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.reject_request))
                        .setMessage(getString(R.string.confirm_reject_msg, name))
                        .setPositiveButton(getString(R.string.reject), (d, w) -> doc.getReference().delete().addOnSuccessListener(a -> {
                            Toast.makeText(this, getString(R.string.request_rejected), Toast.LENGTH_SHORT).show();
                            showPendingRequestsDialog(); // refresh
                        }))
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show());

                    approveBtn.setOnClickListener(v -> {
                        String code = String.format(Locale.getDefault(), "%06d", new Random().nextInt(999999));
                        Map<String, Object> update = new HashMap<>();
                        update.put("status", "approved");
                        update.put("verificationCode", code);
                        update.put("approvedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());

                        doc.getReference().update(update)
                            .addOnSuccessListener(a -> new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.request_approved))
                                .setMessage(getString(R.string.verification_code_msg, name, code))
                                .setPositiveButton(R.string.send_approval_email, (d, w) -> {
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
                                .setNegativeButton(R.string.close, (d, w) -> showPendingRequestsDialog())
                                .show());
                    });
                    container.addView(row);
                }
            });

        builder.setView(container);
        builder.setPositiveButton(getString(R.string.close), null);
        builder.show();
    }

    private void showDatabaseManagementDialog() {
        String[] items = {
            getString(R.string.monitor_db),
            getString(R.string.import_db),
            getString(R.string.export_db),
            getString(R.string.delete_db),
            getString(R.string.wipe_everything_label)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.database_management))
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: showMonitorDatabaseDialog(); break;
                        case 1: importDatabase(); break;
                        case 2: exportDatabase(); break;
                        case 3: deleteDatabase(); break;
                        case 4: wipeEverything(); break;
                    }
                }).setPositiveButton(getString(R.string.cancel), null).show();
    }

    private void showMonitorDatabaseDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.db_monitor_title);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView tvConnection = new TextView(this);
        tvConnection.setText(R.string.db_connection_checking);
        tvConnection.setTextSize(16);
        layout.addView(tvConnection);

        TextView tvUsers = new TextView(this);
        tvUsers.setText(R.string.db_users_loading);
        tvUsers.setTextSize(16);
        layout.addView(tvUsers);

        TextView tvPending = new TextView(this);
        tvPending.setText(R.string.db_pending_loading);
        tvPending.setTextSize(16);
        layout.addView(tvPending);

        TextView tvTasksCount = new TextView(this);
        tvTasksCount.setText(R.string.total_tasks_loading);
        tvTasksCount.setTextSize(16);
        layout.addView(tvTasksCount);

        TextView tvInviteCodes = new TextView(this);
        tvInviteCodes.setText(R.string.active_invite_codes_loading);
        tvInviteCodes.setTextSize(16);
        layout.addView(tvInviteCodes);

        TextView tvLastUpdate = new TextView(this);
        tvLastUpdate.setText(getString(R.string.db_last_update, new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date())));
        tvLastUpdate.setTextSize(14);
        tvLastUpdate.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        layout.addView(tvLastUpdate);

        // Add action buttons
        Button btnViewFarmStats = new Button(this);
        btnViewFarmStats.setText(R.string.view_farm_stats_btn);
        btnViewFarmStats.setOnClickListener(v -> showFarmStatsExplorer());
        layout.addView(btnViewFarmStats);

        Button btnManageCodes = new Button(this);
        btnManageCodes.setText(R.string.manage_invite_codes_btn);
        btnManageCodes.setOnClickListener(v -> showInviteCodesManagementDialog());
        layout.addView(btnManageCodes);

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.close), null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // Real-time listeners
        FirebaseFirestore db_ = FirebaseFirestore.getInstance();

        // Connection check
        db_.collection("user_access").limit(1).get().addOnSuccessListener(querySnapshot -> runOnUiThread(() -> tvConnection.setText(R.string.db_connection_online))).addOnFailureListener(e -> runOnUiThread(() -> tvConnection.setText(R.string.db_connection_offline)));

        // Approved users listener
        com.google.firebase.firestore.ListenerRegistration approvedListener = db_.collection("user_access")
            .whereEqualTo("status", "approved")
            .addSnapshotListener((querySnapshot, e) -> {
                if (e != null) {
                    runOnUiThread(() -> tvUsers.setText(R.string.approved_users_error));
                    return;
                }
                int count = querySnapshot != null ? querySnapshot.size() : 0;
                runOnUiThread(() -> {
                    tvUsers.setText(getString(R.string.db_users_count, count));
                    tvLastUpdate.setText(getString(R.string.db_last_update, new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date())));
                });
            });

        // Pending requests listener
        com.google.firebase.firestore.ListenerRegistration pendingListener = db_.collection("user_access")
            .whereEqualTo("status", "pending")
            .addSnapshotListener((querySnapshot, e) -> {
                if (e != null) {
                    runOnUiThread(() -> tvPending.setText(R.string.pending_requests_error));
                    return;
                }
                int count = querySnapshot != null ? querySnapshot.size() : 0;
                runOnUiThread(() -> tvPending.setText(getString(R.string.db_pending_count, count)));
            });

        // Tasks listener
        com.google.firebase.firestore.ListenerRegistration tasksListener = db_.collection("farm_data")
            .document("shared").collection("tasks")
            .addSnapshotListener((querySnapshot, e) -> {
                if (e != null) return;
                int count = querySnapshot != null ? querySnapshot.size() : 0;
                runOnUiThread(() -> tvTasksCount.setText(getString(R.string.total_tasks_count_placeholder, count)));
            });

        // Invite codes listener
        com.google.firebase.firestore.ListenerRegistration codesListener = db_.collection("invite_codes")
            .addSnapshotListener((querySnapshot, e) -> {
                if (e != null) return;
                int count = querySnapshot != null ? querySnapshot.size() : 0;
                runOnUiThread(() -> tvInviteCodes.setText(getString(R.string.active_invite_codes_count_placeholder, count)));
            });

        // Remove listeners on dismiss
        dialog.setOnDismissListener(dialogInterface -> {
            approvedListener.remove();
            pendingListener.remove();
            tasksListener.remove();
            codesListener.remove();
        });
    }

    private void showFarmStatsExplorer() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.farm_stats_explorer_title);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView tvData = new TextView(this);
        tvData.setText(R.string.fetching_stats_placeholder);
        layout.addView(tvData);

        FirebaseFirestore.getInstance().collection("farm_data").document("stats").get()
            .addOnSuccessListener(doc -> {
                if (doc.exists()) {
                    String data = getString(R.string.farm_stats_display_format,
                            doc.getLong("totalBirds"),
                            doc.getLong("activeCages"),
                            (doc.getTimestamp("farmStartDate") != null ? Objects.requireNonNull(doc.getTimestamp("farmStartDate")).toDate().toString() : "N/A"));
                    tvData.setText(data);
                    
                    Button btnReset = new Button(this);
                    btnReset.setText(R.string.reset_stats_btn);
                    btnReset.setOnClickListener(v -> {
                        Map<String, Object> reset = new HashMap<>();
                        reset.put("totalBirds", 0);
                        reset.put("activeCages", 0);
                        doc.getReference().update(reset).addOnSuccessListener(a -> {
                            Toast.makeText(this, "Stats reset", Toast.LENGTH_SHORT).show();
                            showFarmStatsExplorer(); // refresh
                        });
                    });
                    layout.addView(btnReset);
                } else {
                    tvData.setText(R.string.no_farm_stats_found);
                }
            });

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.close), null);
        builder.show();
    }

    private void importDatabase() {
        Toast.makeText(this, getString(R.string.db_importing), Toast.LENGTH_SHORT).show();
    }

    private void exportDatabase() {
        Toast.makeText(this, getString(R.string.db_exporting), Toast.LENGTH_SHORT).show();
    }

    private void deleteDatabase() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.delete_database_title)
            .setMessage(R.string.db_delete_confirm)
            .setPositiveButton(getString(R.string.delete), (d, w) -> Toast.makeText(this, getString(R.string.db_deleted), Toast.LENGTH_SHORT).show())
            .setNegativeButton(getString(R.string.cancel), null).show();
    }

    private void wipeEverything() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.wipe_everything))
            .setMessage(getString(R.string.confirm_wipe))
            .setPositiveButton(getString(R.string.wipe), (d, w) -> {
                accountManager.clearSession();
                finish();
            })
            .setNegativeButton(getString(R.string.cancel), null).show();
    }

    private void showHelpSupportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_help_support, null);
        builder.setView(view);
        builder.setPositiveButton(getString(R.string.close), null);
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
                .addOnSuccessListener(a -> Toast.makeText(this, getString(R.string.birthday_updated, newBirthday), Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, getString(R.string.birthday_update_failed), Toast.LENGTH_SHORT).show());
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
        
        // Create a layout for location input with 4 fields
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 24, 24, 24);

        TextView titleTv = new TextView(this);
        titleTv.setText(R.string.edit_address_title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        titleTv.setPadding(0, 0, 0, 16);
        container.addView(titleTv);

        EditText streetEt = new EditText(this);
        streetEt.setHint(R.string.street_address_hint);
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
        cityEt.setHint(R.string.city_hint);
        cityEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        cityEt.setBackgroundResource(R.drawable.rounded_corner);
        LinearLayout.LayoutParams cityParams = new LinearLayout.LayoutParams(0, 200);
        cityParams.weight = 1;
        cityParams.rightMargin = 8;
        cityEt.setLayoutParams(cityParams);
        cityEt.setPadding(16, 16, 16, 16);
        cityStateLayout.addView(cityEt);

        EditText stateEt = new EditText(this);
        stateEt.setHint(R.string.state_province_hint);
        stateEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        stateEt.setBackgroundResource(R.drawable.rounded_corner);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(0, 200);
        stateParams.weight = 1;
        stateEt.setLayoutParams(stateParams);
        stateEt.setPadding(16, 16, 16, 16);
        cityStateLayout.addView(stateEt);

        container.addView(cityStateLayout);

        EditText postalEt = new EditText(this);
        postalEt.setHint(R.string.postal_code_hint);
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
        builder.setPositiveButton(getString(R.string.save), (dialog, which) -> {
            String street = streetEt.getText().toString().trim();
            String city = cityEt.getText().toString().trim();
            String state = stateEt.getText().toString().trim();
            String postal = postalEt.getText().toString().trim();

            if (street.isEmpty() || city.isEmpty() || state.isEmpty() || postal.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_address, Toast.LENGTH_SHORT).show();
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
                .addOnSuccessListener(a -> Toast.makeText(this, getString(R.string.location_updated), Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, getString(R.string.location_update_failed), Toast.LENGTH_SHORT).show());
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }
}
