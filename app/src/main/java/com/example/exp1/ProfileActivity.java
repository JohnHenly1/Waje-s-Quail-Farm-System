package com.example.exp1;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class ProfileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Get user data from intent or session
        AccountManager accountManager = new AccountManager(this);
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
}
