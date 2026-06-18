package com.example.exp1;

import android.content.Intent;
import android.app.DatePickerDialog;
import android.os.Build;
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

import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.ListenerRegistration;

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


    private String userRole = "staff";
    private String currentEmail = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ListenerRegistration userProfileListener;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

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

        if (userRoleTv != null) {
            String roleDisplayName = RoleManager.Companion.displayName(userRole != null ? userRole : "staff");
            userRoleTv.setText(getString(R.string.role_label, roleDisplayName));
        }

        currentEmail = getIntent().getStringExtra("username");
        if (currentEmail == null || currentEmail.isEmpty()) {
            currentEmail = currentSession;
        }
        if (currentEmail == null) currentEmail = "";

        firestoreManager = new FirestoreManager(currentEmail);

        showLoading(getString(R.string.syncing_profile), () -> {
            fetchUserData();
            loadFirestoreData();
        });

        View accountSettingsButton = findViewById(R.id.accountSettingsButton);
        if (accountSettingsButton != null) {
            accountSettingsButton.setOnClickListener(v -> showAccountSettingsDialog());
        }

        View recalibrationButton = findViewById(R.id.farmRecalibrationButton);
        if (recalibrationButton != null) {
            if (isAdmin()) {
                recalibrationButton.setVisibility(View.VISIBLE);
                recalibrationButton.setOnClickListener(v -> showRecalibrationDialog());
            } else {
                recalibrationButton.setVisibility(View.GONE);
            }
        }

        View adminGroupLabel = findViewById(R.id.adminGroupLabel);
        View adminCard = findViewById(R.id.adminCard);
        View userListButton = findViewById(R.id.userListButton);

        if (isAdmin()) {
            if (adminGroupLabel != null) adminGroupLabel.setVisibility(View.VISIBLE);
            if (adminCard != null) adminCard.setVisibility(View.VISIBLE);

            if (userListButton != null) {
                userListButton.setOnClickListener(v -> showUserListDialog());
            }

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

        View languageRegionButton = findViewById(R.id.languageRegionButton);
        if (languageRegionButton != null) {
            languageRegionButton.setOnClickListener(v -> showLanguageRegionDialog());
        }

        View privacySecurityButton = findViewById(R.id.privacySecurityButton);
        if (privacySecurityButton != null) {
            privacySecurityButton.setOnClickListener(v -> showPrivacySecurityDialog());
        }

        View helpSupportButton = findViewById(R.id.helpSupportButton);
        if (helpSupportButton != null) {
            helpSupportButton.setOnClickListener(v -> showHelpSupportDialog());
        }

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

    // ── Helpers ────────────────────────────────────────────────────────────────

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

        // Use a real-time snapshot listener so the UI reflects any server-side
        // changes (role updates, name edits) immediately — no manual refresh needed.
        userProfileListener = FirebaseFirestore.getInstance()
                .collection("user_access").document(currentEmail)
                .addSnapshotListener((doc, error) -> {
                    if (error != null) {
                        Toast.makeText(this,
                                "Failed to load profile: " + error.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (doc != null && doc.exists()) {
                        String name = doc.getString("name");
                        String role = doc.getString("role");

                        if (name != null && !name.isEmpty()) {
                            userNameTv.setText(name);
                            profileInitialTv.setText(
                                    String.valueOf(name.charAt(0)).toUpperCase());
                        }
                        userEmailTv.setText(currentEmail);

                        if (userRoleTv != null && role != null) {
                            // Keep in-memory role in sync so isAdmin() stays accurate.
                            userRole = role;
                            userRoleTv.setText(getString(R.string.role_label,
                                    RoleManager.Companion.displayName(role)));
                        }
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Detach the listener when the activity is no longer visible to avoid
        // unnecessary Firestore reads and potential memory leaks.
        if (userProfileListener != null) {
            userProfileListener.remove();
            userProfileListener = null;
        }
    }

    private boolean isAdmin() {
        return new RoleManager(userRole).canViewAdminPanel();
    }

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
                runOnUiThread(() -> Toast.makeText(ProfileActivity.this,
                        getString(R.string.error_loading_farm_data), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── Farm Recalibration ─────────────────────────────────────────────────────

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

        editTotalBirds.setText(totalBirdsValue.getText());
        editActiveCages.setText(activeCagesValue.getText());

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
                        .addOnFailureListener(e -> runOnUiThread(() ->
                                Toast.makeText(ProfileActivity.this,
                                        getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_SHORT).show()));
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(ProfileActivity.this)
                            .setTitle(getString(R.string.restart_days_title))
                            .setMessage(getString(R.string.restart_days_message))
                            .setPositiveButton(getString(R.string.restart), (d2, w) ->
                                    firestoreManager.restartDaysRunning(new FirestoreManager.OnSaveListener() {
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

    // ── Account Settings ───────────────────────────────────────────────────────

    private void showAccountSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_account_settings, null);
        builder.setView(view);

        View changePasswordBtn = view.findViewById(R.id.changePasswordBtn);
        View editBirthdayBtn   = view.findViewById(R.id.editBirthdayBtn);

        changePasswordBtn.setOnClickListener(v -> showChangePasswordDialog());
        editBirthdayBtn.setOnClickListener(v -> showEditBirthdayDialog());

        builder.setPositiveButton("Close", null);
        builder.show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FIX 1 — Change Password
    //  • Guards currentEmail null/empty before Firestore call
    //  • Verifies current password against Firestore (source of truth)
    //  • Mirrors new password to local SharedPreferences via forceUpdatePassword
    //  • Correctly re-enables the button on every failure path
    //  • Uses separate error labels for every field
    // ══════════════════════════════════════════════════════════════════════════
    private void showChangePasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.change_password));
        View view = getLayoutInflater().inflate(R.layout.dialog_change_password, null);
        builder.setView(view);

        EditText oldPass     = view.findViewById(R.id.oldPassword);
        EditText newPass     = view.findViewById(R.id.newPassword);
        EditText confirmPass = view.findViewById(R.id.confirmPassword);
        TextInputLayout newPasswordLayout     = view.findViewById(R.id.newPasswordLayout);
        TextInputLayout confirmPasswordLayout = view.findViewById(R.id.confirmPasswordLayout);

        // Live strength feedback
        newPass.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String pass = s.toString();
                if (!pass.isEmpty()) {
                    newPasswordLayout.setError(getPasswordStrengthError(pass));
                } else {
                    newPasswordLayout.setError(null);
                }
                String confirm = confirmPass.getText().toString();
                if (!confirm.isEmpty()) {
                    confirmPasswordLayout.setError(
                            pass.equals(confirm) ? null : "Passwords do not match");
                }
            }
        });

        // Live match check
        confirmPass.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String confirm = s.toString();
                String newP    = newPass.getText().toString();
                if (!confirm.isEmpty()) {
                    confirmPasswordLayout.setError(newP.equals(confirm) ? null : "Passwords do not match");
                } else {
                    confirmPasswordLayout.setError(null);
                }
            }
        });

        builder.setPositiveButton(getString(R.string.update), null);
        builder.setNegativeButton(getString(R.string.cancel), null);

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String oldP     = oldPass.getText().toString().trim();
            String newP     = newPass.getText().toString().trim();
            String confirmP = confirmPass.getText().toString().trim();

            // ── Validate: current password field ──────────────────────────
            if (oldP.isEmpty()) {
                oldPass.setError("Please enter your current password");
                oldPass.requestFocus();
                return;
            }

            // ── Validate: new password strength ───────────────────────────
            String strengthError = getPasswordStrengthError(newP);
            if (strengthError != null) {
                newPasswordLayout.setError(strengthError);
                newPass.requestFocus();
                return;
            }
            newPasswordLayout.setError(null);

            // ── Validate: passwords match ─────────────────────────────────
            if (!newP.equals(confirmP)) {
                confirmPasswordLayout.setError("Passwords do not match");
                confirmPass.requestFocus();
                return;
            }
            confirmPasswordLayout.setError(null);

            // ── Validate: new != old ──────────────────────────────────────
            if (newP.equals(oldP)) {
                newPasswordLayout.setError("New password must be different from current password");
                newPass.requestFocus();
                return;
            }

            // ── Guard: email must be known ────────────────────────────────
            if (currentEmail == null || currentEmail.isEmpty()) {
                Toast.makeText(ProfileActivity.this,
                        "Cannot identify your account. Please log out and back in.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // ── Disable button while working ──────────────────────────────
            Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveBtn.setEnabled(false);
            positiveBtn.setText("Updating...");

            // ── Verify against Firestore (the authoritative password store) ─
            // Source.SERVER forces a live read instead of the local cache.
            // This ensures the same authenticated session is used for both the
            // .get() verification and the subsequent .update() write, preventing
            // the "Permission Denied" error that occurs when .get() is served from
            // cache (no auth needed) but .update() requires a live server write.
            FirebaseFirestore.getInstance()
                    .collection("user_access").document(currentEmail)
                    .get(Source.SERVER)
                    .addOnSuccessListener(doc -> {
                        if (!doc.exists()) {
                            runOnUiThread(() -> {
                                Toast.makeText(ProfileActivity.this,
                                        "User profile not found. Please contact your administrator.",
                                        Toast.LENGTH_LONG).show();
                                positiveBtn.setEnabled(true);
                                positiveBtn.setText(getString(R.string.update));
                            });
                            return;
                        }

                        String remotePass = doc.getString("password");
                        if (remotePass == null || !remotePass.equals(oldP)) {
                            // Wrong current password
                            runOnUiThread(() -> {
                                oldPass.setError(getString(R.string.incorrect_password));
                                oldPass.requestFocus();
                                Toast.makeText(ProfileActivity.this,
                                        getString(R.string.incorrect_password), Toast.LENGTH_SHORT).show();
                                positiveBtn.setEnabled(true);
                                positiveBtn.setText(getString(R.string.update));
                            });
                            return;
                        }

                        // ── Correct — save new password to Firestore ──────
                        doc.getReference().update("password", newP)
                                .addOnSuccessListener(aVoid -> {
                                    // Mirror to local SharedPreferences so offline logins keep working.
                                    String sessionUsername = accountManager.getCurrentUsername();
                                    String keyForPrefs = (sessionUsername != null && !sessionUsername.isEmpty())
                                            ? sessionUsername : currentEmail;
                                    accountManager.forceUpdatePassword(keyForPrefs, newP);
                                    runOnUiThread(() -> {
                                        Toast.makeText(ProfileActivity.this,
                                                getString(R.string.password_updated), Toast.LENGTH_SHORT).show();
                                        dialog.dismiss();
                                    });
                                })
                                .addOnFailureListener(e -> runOnUiThread(() -> {
                                    Toast.makeText(ProfileActivity.this,
                                            "Failed to save new password: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                    positiveBtn.setEnabled(true);
                                    positiveBtn.setText(getString(R.string.update));
                                }));
                    })
                    .addOnFailureListener(e -> runOnUiThread(() -> {
                        Toast.makeText(ProfileActivity.this,
                                "Network error — could not verify password: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        positiveBtn.setEnabled(true);
                        positiveBtn.setText(getString(R.string.update));
                    }));
        });
    }

    private String getPasswordStrengthError(String password) {
        if (password.length() < 8) return "Password must be at least 8 characters long";
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSymbol = false;
        String symbols = "!@#$%^&*()_+-=[]{}|;':\",./&lt;&gt;?";

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


    // ── Language & Region ──────────────────────────────────────────────────────

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
                    ArrayAdapter<String> provinceAdapter = new ArrayAdapter<>(ProfileActivity.this,
                            android.R.layout.simple_spinner_item, provinces);
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

            accountManager.saveLanguageRegion(selectedLang, selectedReg, selectedProv);

            String langTag = "en";
            if (selectedLang.equals("Tagalog")) langTag = "fil";
            else if (selectedLang.equals("Cebuano")) langTag = "ceb";

            LocaleListCompat appLocales = LocaleListCompat.forLanguageTags(langTag);
            AppCompatDelegate.setApplicationLocales(appLocales);

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

    // ── Privacy & Security ─────────────────────────────────────────────────────

    private void showPrivacySecurityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_privacy_security, null);
        builder.setView(view);

        View sectionDataCollection  = view.findViewById(R.id.sectionDataCollection);
        View sectionPrivacyContent  = view.findViewById(R.id.sectionPrivacyContent);
        View sectionSecurityMeasures = view.findViewById(R.id.sectionSecurityMeasures);

        if (sectionDataCollection != null)  sectionDataCollection.setOnClickListener(v -> showDataCollectionDetails());
        if (sectionPrivacyContent != null)  sectionPrivacyContent.setOnClickListener(v -> showPrivacyContentDetails());
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

        TextView title = new TextView(this);
        title.setText(R.string.data_collection_policy_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);

        String[] dataTypes = {
                "• Farm Profile: Location, GPS coordinates, farm name",
                "• Flock Records: Bird count, breed type, age range",
                "• Daily Production: Egg count, health status, mortality",
                "• Financial Records: Feed costs, sales revenue, expenses",
                "• Health Logs: Vaccination records, disease incidents",
                "• Environmental: Temperature, humidity readings, weather data"
        };
        for (String dataType : dataTypes) {
            TextView tv = new TextView(this);
            tv.setText(dataType);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setPadding(0, 8, 0, 0);
            tv.setLineSpacing(4, 1.0f);
            container.addView(tv);
        }

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

        TextView title = new TextView(this);
        title.setText(R.string.privacy_policy_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);

        TextView ownershipText = new TextView(this);
        ownershipText.setText("✓ You own all your data\n✓ You control access to your farm data\n✓ You can request data deletion anytime");
        ownershipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ownershipText.setLineSpacing(4, 1.0f);
        container.addView(ownershipText);

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

        TextView title = new TextView(this);
        title.setText(R.string.security_implementation_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        title.setPadding(0, 0, 0, 16);
        container.addView(title);

        TextView encryptText = new TextView(this);
        encryptText.setText("✓ Data in transit: TLS 1.2+ encryption\n✓ Data at rest: Firebase automatic encryption\n✓ Sensitive fields: AES-256 encryption");
        encryptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        encryptText.setLineSpacing(4, 1.0f);
        container.addView(encryptText);

        scrollView.addView(container);
        builder.setView(scrollView);
        builder.setPositiveButton(getString(R.string.close), null);
        builder.show();
    }

    // ── User List & Admin ──────────────────────────────────────────────────────

    private void showUserListDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_user_management, null);
        builder.setView(view);

        RecyclerView rvUserList    = view.findViewById(R.id.rvUserList);
        ProgressBar progressBar    = view.findViewById(R.id.progressBar);
        TextView errorTextView     = view.findViewById(R.id.errorTextView);

        rvUserList.setLayoutManager(new LinearLayoutManager(this));

        view.findViewById(R.id.btnRoleLimits).setOnClickListener(v -> showRoleLimitsDialog());
        view.findViewById(R.id.btnInviteNew).setOnClickListener(v -> NavigationHelper.INSTANCE.showGenerateInviteCodeDialog(this, currentEmail));
        view.findViewById(R.id.btnViewCodes).setOnClickListener(v -> showInviteCodesManagementDialog());

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
                        FirebaseFirestore.getInstance().collection("user_access").document(email).delete()
                                .addOnSuccessListener(a -> {
                                    Toast.makeText(this, getString(R.string.user_removed), Toast.LENGTH_SHORT).show();
                                    showUserListDialog();
                                })
                                .addOnFailureListener(e ->
                                        Toast.makeText(this, getString(R.string.failed_to_remove_user), Toast.LENGTH_SHORT).show());
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

                        ((TextView) row.findViewById(R.id.userNameText)).setText(
                                getString(R.string.invite_code_placeholder, code, invitedEmail));
                        ((TextView) row.findViewById(R.id.userRoleText)).setText(
                                getString(R.string.role_display_placeholder,
                                        RoleManager.Companion.displayName(role != null ? role : "staff")));

                        ImageButton deleteBtn = row.findViewById(R.id.deleteUserBtn);
                        deleteBtn.setOnClickListener(v -> doc.getReference().delete()
                                .addOnSuccessListener(a -> {
                                    Toast.makeText(this, "Invite code deleted", Toast.LENGTH_SHORT).show();
                                    showInviteCodesManagementDialog();
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
        // Role limits: staff_limit field in system_settings/role_limits
        layout.addView(editBackupLimit);

        EditText editStaffLimit = new EditText(this);
        editStaffLimit.setHint(R.string.max_staff_hint);
        layout.addView(editStaffLimit);

        FirebaseFirestore.getInstance().collection("system_settings").document("role_limits").get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        // staff limit loaded below
                        editStaffLimit.setText(String.valueOf(doc.getLong("staff_limit")));
                    }
                });

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.save), (d, w) -> {
            Map<String, Object> limits = new HashMap<>();
            // consolidated into single staff_limit below
            limits.put("staff_limit", Long.parseLong(editStaffLimit.getText().toString()));
            FirebaseFirestore.getInstance().collection("system_settings").document("role_limits").set(limits)
                    .addOnSuccessListener(a ->
                            Toast.makeText(this, getString(R.string.limits_updated), Toast.LENGTH_SHORT).show());
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
                        String name  = doc.getString("name");
                        String role  = doc.getString("role");
                        String email = doc.getId();

                        ((TextView) row.findViewById(R.id.userNameText)).setText(
                                getString(R.string.user_email_placeholder, name, email));
                        ((TextView) row.findViewById(R.id.userRoleText)).setText(
                                getString(R.string.role_display_placeholder,
                                        RoleManager.Companion.displayName(role != null ? role : "staff")));

                        ImageButton approveBtn = row.findViewById(R.id.deleteUserBtn);
                        approveBtn.setImageResource(android.R.drawable.ic_menu_save);

                        ImageButton rejectBtn = row.findViewById(R.id.rejectBtn);
                        rejectBtn.setVisibility(View.VISIBLE);

                        rejectBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.reject_request))
                                .setMessage(getString(R.string.confirm_reject_msg, name))
                                .setPositiveButton(getString(R.string.reject), (d, w) ->
                                        doc.getReference().delete().addOnSuccessListener(a -> {
                                            Toast.makeText(this, getString(R.string.request_rejected), Toast.LENGTH_SHORT).show();
                                            showPendingRequestsDialog();
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
                                                        "Congratulations! Your request for access has been APPROVED.\n\n" +
                                                        "Your 6-digit verification code is: " + code + "\n\n" +
                                                        "Best regards,\nQuail Farm Management System";

                                                Intent intent = new Intent(Intent.ACTION_SEND);
                                                intent.setType("message/rfc822");
                                                intent.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
                                                intent.putExtra(Intent.EXTRA_CC, new String[]{currentEmail});
                                                intent.putExtra(Intent.EXTRA_SUBJECT, "Quail Farm Access Approved - Code: " + code);
                                                intent.putExtra(Intent.EXTRA_TEXT, emailBody);
                                                intent.setPackage("com.google.android.gm");
                                                try {
                                                    startActivity(intent);
                                                } catch (Exception ex) {
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


    // ── Help & Support ─────────────────────────────────────────────────────────

    private void showHelpSupportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_help_support, null);
        builder.setView(view);
        builder.setPositiveButton(getString(R.string.close), null);
        builder.show();
    }

    // ── Birthday & Location ────────────────────────────────────────────────────

    private void showEditBirthdayDialog() {
        Calendar calendar = Calendar.getInstance();
        DatePickerDialog.OnDateSetListener dateSetListener = (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            String format = "MM/dd/yyyy";
            SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
            String newBirthday = sdf.format(calendar.getTime());
            FirebaseFirestore.getInstance().collection("user_access").document(currentEmail)
                    .update("birthday", newBirthday)
                    .addOnSuccessListener(a ->
                            Toast.makeText(this, getString(R.string.birthday_updated, newBirthday), Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                            Toast.makeText(this, getString(R.string.birthday_update_failed), Toast.LENGTH_SHORT).show());
        };
        new DatePickerDialog(this, dateSetListener,
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
                .show();
    }

    @SuppressWarnings("unchecked")
    private void showEditLocationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
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
                LinearLayout.LayoutParams.MATCH_PARENT, 200);
        params.bottomMargin = 12;
        streetEt.setLayoutParams(params);
        streetEt.setPadding(16, 16, 16, 16);
        container.addView(streetEt);

        LinearLayout cityStateLayout = new LinearLayout(this);
        cityStateLayout.setOrientation(LinearLayout.HORIZONTAL);
        cityStateLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
                LinearLayout.LayoutParams.MATCH_PARENT, 200);
        postalParams.topMargin = 12;
        postalEt.setLayoutParams(postalParams);
        postalEt.setPadding(16, 16, 16, 16);
        container.addView(postalEt);

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
            String city   = cityEt.getText().toString().trim();
            String state  = stateEt.getText().toString().trim();
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
                    .addOnSuccessListener(a ->
                            Toast.makeText(this, getString(R.string.location_updated), Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                            Toast.makeText(this, getString(R.string.location_update_failed), Toast.LENGTH_SHORT).show());
        });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.show();
    }
}