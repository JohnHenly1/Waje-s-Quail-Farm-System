package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfileActivity extends AppCompatActivity {

    private AccountManager accountManager;
    private TextView totalBirdsValue;
    private TextView activeCagesValue;
    private TextView userNameTv;
    private TextView userEmailTv;
    private TextView profileInitialTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);
        
        accountManager = new AccountManager(this);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        totalBirdsValue = findViewById(R.id.totalBirdsValue);
        activeCagesValue = findViewById(R.id.activeCagesValue);
        userNameTv = findViewById(R.id.userName);
        userEmailTv = findViewById(R.id.userEmail);
        profileInitialTv = findViewById(R.id.profileInitial);

        // Get user data from intent or session
        String username = getIntent().getStringExtra("username");
        if (username == null || username.isEmpty()) {
            username = accountManager.getCurrentUsername();
        }

        updateUserInfoDisplay(username);

        // Display current farm stats
        updateFarmStatsDisplay();

        // Setup Edit Profile Button
        View editProfileButton = findViewById(R.id.editProfileButton);
        if (editProfileButton != null) {
            editProfileButton.setOnClickListener(v -> showEditProfileDialog());
        }

        // Setup Account Settings Button
        View accountSettingsButton = findViewById(R.id.accountSettingsButton);
        if (accountSettingsButton != null) {
            accountSettingsButton.setOnClickListener(v -> showAccountSettingsDialog());
        }

        // Setup Farm Recalibration
        View recalibrationButton = findViewById(R.id.farmRecalibrationButton);
        if (recalibrationButton != null) {
            recalibrationButton.setOnClickListener(v -> showRecalibrationDialog());
        }

        // Setup Notification Preferences
        View notificationPrefsButton = findViewById(R.id.notificationPreferencesButton);
        if (notificationPrefsButton != null) {
            notificationPrefsButton.setOnClickListener(v -> showNotificationPrefsDialog());
        }

        // Setup Language & Region
        View languageRegionButton = findViewById(R.id.languageRegionButton);
        if (languageRegionButton != null) {
            languageRegionButton.setOnClickListener(v -> showLanguageRegionDialog());
        }

        // Setup Privacy & Security
        View privacySecurityButton = findViewById(R.id.privacySecurityButton);
        if (privacySecurityButton != null) {
            privacySecurityButton.setOnClickListener(v -> showPrivacySecurityDialog());
        }

        // Setup Help & Support
        View helpSupportButton = findViewById(R.id.helpSupportButton);
        if (helpSupportButton != null) {
            helpSupportButton.setOnClickListener(v -> showHelpSupportDialog());
        }

        // Setup Back Button to Dashboard
        ImageButton backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            final String finalUsername = username;
            backButton.setOnClickListener(v -> {
                Intent intent = new Intent(ProfileActivity.this, DashboardActivity.class);
                intent.putExtra("username", accountManager.getCurrentUsername());
                intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                finish();
            });
        }

        // Setup Logout Button
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

        // Setup bottom navigation
        NavigationHelper.INSTANCE.setupBottomNavigation(this);
    }

    private void updateUserInfoDisplay(String username) {
        if (username != null && !username.isEmpty()) {
            userNameTv.setText(username);
            profileInitialTv.setText(String.valueOf(username.charAt(0)).toUpperCase());

            String email = accountManager.getEmail(username);
            if (email != null) {
                userEmailTv.setText(email);
            }
        }
    }

    private void updateFarmStatsDisplay() {
        totalBirdsValue.setText(String.valueOf(accountManager.getTotalBirds()));
        activeCagesValue.setText(String.valueOf(accountManager.getActiveCages()));
    }

    private void showEditProfileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_profile, null);
        builder.setView(view);

        EditText editUsername = view.findViewById(R.id.editUsername);
        EditText editEmail = view.findViewById(R.id.editEmail);

        String currentUsername = accountManager.getCurrentUsername();
        editUsername.setText(currentUsername);
        editEmail.setText(accountManager.getEmail(currentUsername));

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newUsername = editUsername.getText().toString();
            String newEmail = editEmail.getText().toString();

            if (!newUsername.isEmpty() && !newEmail.isEmpty()) {
                if (accountManager.updateProfile(currentUsername, newUsername, newEmail)) {
                    updateUserInfoDisplay(newUsername);
                    Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Username already exists", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showAccountSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_account_settings, null);
        builder.setView(view);

        EditText currentPassword = view.findViewById(R.id.currentPassword);
        EditText newPassword = view.findViewById(R.id.newPassword);
        EditText confirmNewPassword = view.findViewById(R.id.confirmNewPassword);

        builder.setPositiveButton("Update Password", (dialog, which) -> {
            String oldPass = currentPassword.getText().toString();
            String newPass = newPassword.getText().toString();
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

    private void showLanguageRegionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_language_region, null);
        builder.setView(view);

        Spinner spinnerLanguage = view.findViewById(R.id.spinnerLanguage);
        Spinner spinnerRegion = view.findViewById(R.id.spinnerRegion);
        Spinner spinnerProvince = view.findViewById(R.id.spinnerProvince);

        // Languages
        String[] languages = {"English", "Tagalog", "Cebuano"};
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);
        spinnerLanguage.setSelection(Arrays.asList(languages).indexOf(accountManager.getSelectedLanguage()));

        // Regions and Provinces data
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
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedRegion = regions[position];
                List<String> provinces = regionProvinceMap.get(selectedRegion);
                ArrayAdapter<String> provinceAdapter = new ArrayAdapter<>(ProfileActivity.this, android.R.layout.simple_spinner_item, provinces);
                provinceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerProvince.setAdapter(provinceAdapter);
                
                // Pre-select if it matches saved province
                String savedProvince = accountManager.getSelectedProvince();
                if (provinces.contains(savedProvince)) {
                    spinnerProvince.setSelection(provinces.indexOf(savedProvince));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        builder.setPositiveButton("Save", (dialog, which) -> {
            String selectedLang = spinnerLanguage.getSelectedItem().toString();
            String selectedReg = spinnerRegion.getSelectedItem().toString();
            String selectedProv = spinnerProvince.getSelectedItem().toString();
            
            accountManager.saveLanguageRegion(selectedLang, selectedReg, selectedProv);

            // Apply Locale
            String langTag = "en";
            if (selectedLang.equals("Tagalog")) langTag = "tl";
            else if (selectedLang.equals("Cebuano")) langTag = "ceb";

            LocaleListCompat appLocales = LocaleListCompat.forLanguageTags(langTag);
            AppCompatDelegate.setApplicationLocales(appLocales);

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            
            // Restart activity to reflect changes
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

    private void showNotificationPrefsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_notification_preferences, null);
        builder.setView(dialogView);

        SwitchCompat switchAlerts = dialogView.findViewById(R.id.switchAlerts);
        SwitchCompat switchGlobalData = dialogView.findViewById(R.id.switchGlobalData);
        SwitchCompat switchSchedule = dialogView.findViewById(R.id.switchSchedule);

        // Load current preferences
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

    private void showRecalibrationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_recalibrate_farm, null);
        builder.setView(dialogView);

        EditText editTotalBirds = dialogView.findViewById(R.id.editTotalBirds);
        EditText editActiveCages = dialogView.findViewById(R.id.editActiveCages);

        // Pre-fill with current values
        editTotalBirds.setText(String.valueOf(accountManager.getTotalBirds()));
        editActiveCages.setText(String.valueOf(accountManager.getActiveCages()));

        builder.setTitle("Farm Recalibration")
               .setPositiveButton("Save", (dialog, which) -> {
                   String birdsStr = editTotalBirds.getText().toString();
                   String cagesStr = editActiveCages.getText().toString();

                   if (!birdsStr.isEmpty() && !cagesStr.isEmpty()) {
                       int birds = Integer.parseInt(birdsStr);
                       int cages = Integer.parseInt(cagesStr);
                       accountManager.saveFarmStats(birds, cages);
                       updateFarmStatsDisplay();
                       Toast.makeText(this, "Farm stats updated!", Toast.LENGTH_SHORT).show();
                   } else {
                       Toast.makeText(this, "Please enter all values", Toast.LENGTH_SHORT).show();
                   }
               })
               .setNegativeButton("Cancel", null)
               .show();
    }
}
