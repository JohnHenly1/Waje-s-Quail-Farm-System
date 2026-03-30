package com.example.exp1;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FirestoreManager {

    public interface OnLoadListener {
        void onLoaded(String name, int totalBirds, int activeCages, long daysRunning);
        void onError(Exception e);
    }

    public interface OnSaveListener {
        void onSuccess();
        void onError(Exception e);
    }

    private final FirebaseFirestore db;
    private final String userEmail;

    public FirestoreManager(String userEmail) {
        this.db = FirebaseFirestore.getInstance();
        this.userEmail = userEmail;
    }

    private DocumentReference userDoc() {
        return db.collection("users").document(userEmail);
    }

    // Load all farm data---------------------------------------------------------------------------
    public void loadFarmData(OnLoadListener listener) {
        userDoc().get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                String name        = doc.getString("name");
                Long birds         = doc.getLong("totalBirds");
                Long cages         = doc.getLong("activeCages");
                Timestamp start    = doc.getTimestamp("farmStartDate");

                long daysRunning = 0;
                if (start != null) {
                    long diffMs = System.currentTimeMillis() - start.toDate().getTime();
                    daysRunning = TimeUnit.MILLISECONDS.toDays(diffMs) + 1; // day 1 on start day
                }

                listener.onLoaded(
                        name  != null ? name  : "",
                        birds != null ? birds.intValue() : 0,
                        cages != null ? cages.intValue() : 0,
                        daysRunning
                );
            } else {
                listener.onLoaded("", 0, 0, 0);
            }
        }).addOnFailureListener(listener::onError);
    }

    // Save name only-------------------------------------------------------------------------------
    public void saveName(String name, OnSaveListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        userDoc().set(data, SetOptions.merge())
                .addOnSuccessListener(v -> listener.onSuccess())
                .addOnFailureListener(listener::onError);
    }

    // Save farm stats (birds and cages). Sets farmStartDate only if not set.-------------------------
    public void saveFarmStats(int totalBirds, int activeCages, OnSaveListener listener) {
        // First check if farmStartDate already exists
        userDoc().get().addOnSuccessListener(doc -> {
            Map<String, Object> data = new HashMap<>();
            data.put("totalBirds", totalBirds);
            data.put("activeCages", activeCages);

            // Only set farmStartDate if it hasn't been set yet
            if (!doc.exists() || doc.getTimestamp("farmStartDate") == null) {
                data.put("farmStartDate", Timestamp.now());
            }

            userDoc().set(data, SetOptions.merge())
                    .addOnSuccessListener(v -> listener.onSuccess())
                    .addOnFailureListener(listener::onError);

        }).addOnFailureListener(listener::onError);
    }

    // Restart days running: reset farmStartDate to now ------------------------------------------------------
    public void restartDaysRunning(OnSaveListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("farmStartDate", Timestamp.now());
        userDoc().set(data, SetOptions.merge())
                .addOnSuccessListener(v -> listener.onSuccess())
                .addOnFailureListener(listener::onError);
    }
}