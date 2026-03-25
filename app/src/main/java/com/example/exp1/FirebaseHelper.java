package com.example.exp1;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class FirebaseHelper extends AppCompatActivity {

    DatabaseReference db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login); // your UI

        // 🔥 Connect to Firebase
        db = FirebaseDatabase.getInstance().getReference();

        // 🔥 Create sample data
        Map<String, Object> user = new HashMap<>();
        user.put("name", "John");
        user.put("age", 25);

        // 🔥 Write to Firebase
        db.child("users").push().setValue(user)
                .addOnSuccessListener(aVoid -> {
                    Log.d("FIREBASE", "SUCCESS");
                })
                .addOnFailureListener(e -> {
                    Log.e("FIREBASE", "FAILED: " + e.getMessage());
                });
    }
}