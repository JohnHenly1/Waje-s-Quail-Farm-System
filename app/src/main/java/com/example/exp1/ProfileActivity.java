package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class ProfileActivity extends AppCompatActivity {

    private AccountManager accountManager;
    private TextView totalBirdsValue;
    private TextView activeCagesValue;

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

        // Get user data from intent or session
        String username = getIntent().getStringExtra("username");
        
        if (username == null || username.isEmpty()) {
            username = accountManager.getCurrentUsername();
        }

        if (username != null && !username.isEmpty()) {
            TextView userNameTv = findViewById(R.id.userName);
            TextView profileInitialTv = findViewById(R.id.profileInitial);
            userNameTv.setText(username);
            profileInitialTv.setText(String.valueOf(username.charAt(0)).toUpperCase());

            String email = accountManager.getEmail(username);
            if (email != null) {
                TextView userEmailTv = findViewById(R.id.userEmail);
                userEmailTv.setText(email);
            }
        }

        // Display current farm stats
        updateFarmStatsDisplay();

        // Setup Farm Recalibration via the new list button
        View recalibrationButton = findViewById(R.id.farmRecalibrationButton);
        if (recalibrationButton != null) {
            recalibrationButton.setOnClickListener(v -> showRecalibrationDialog());
        }

        // Setup Back Button to Dashboard
        ImageButton backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            final String finalUsername = username;
            backButton.setOnClickListener(v -> {
                Intent intent = new Intent(ProfileActivity.this, DashboardActivity.class);
                intent.putExtra("username", finalUsername);
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

    private void updateFarmStatsDisplay() {
        totalBirdsValue.setText(String.valueOf(accountManager.getTotalBirds()));
        activeCagesValue.setText(String.valueOf(accountManager.getActiveCages()));
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
