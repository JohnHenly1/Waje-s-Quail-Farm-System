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

    private DocumentReference statsDoc() {
        return db.collection("farm_data").document("stats");
    }

    // The user_access doc holds the display name for this user
    private DocumentReference userAccessDoc() {
        return db.collection("user_access").document(userEmail);
    }

    // Load all farm data ---------------------------------------------------------------------------
    // Loads shared farm stats (birds/cages/start date) from farm_data/stats,
    // and the user's display name from user_access/{email}.
    public void loadFarmData(OnLoadListener listener) {
        // Load the shared stats doc
        statsDoc().get().addOnSuccessListener(statsSnap -> {
            int birds = 0;
            int cages = 0;
            long daysRunning = 0;

            if (statsSnap.exists()) {
                Long b = statsSnap.getLong("totalBirds");
                Long c = statsSnap.getLong("activeCages");
                Timestamp start = statsSnap.getTimestamp("farmStartDate");

                birds = b != null ? b.intValue() : 0;
                cages = c != null ? c.intValue() : 0;

                if (start != null) {
                    long diffMs = System.currentTimeMillis() - start.toDate().getTime();
                    daysRunning = TimeUnit.MILLISECONDS.toDays(diffMs) + 1;
                }
            }

            final int finalBirds = birds;
            final int finalCages = cages;
            final long finalDays = daysRunning;

            // Load user's display name from their user_access doc
            userAccessDoc().get().addOnSuccessListener(userSnap -> {
                String name = "";
                if (userSnap.exists()) {
                    String n = userSnap.getString("name");
                    if (n != null) name = n;
                }
                listener.onLoaded(name, finalBirds, finalCages, finalDays);
            }).addOnFailureListener(e -> {
                // Name load failed but stats are fine — still call onLoaded with empty name
                listener.onLoaded("", finalBirds, finalCages, finalDays);
            });

        }).addOnFailureListener(listener::onError);
    }

    // Save display name to user_access/{email} (not stats) ----------------------------------------
    public void saveName(String name, OnSaveListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        userAccessDoc().set(data, SetOptions.merge())
                .addOnSuccessListener(v -> listener.onSuccess())
                .addOnFailureListener(listener::onError);
    }

    // Save farm stats (birds and cages) to farm_data/stats ----------------------------------------
    // Sets farmStartDate only if not already set.
    public void saveFarmStats(int totalBirds, int activeCages, OnSaveListener listener) {
        statsDoc().get().addOnSuccessListener(doc -> {
            Map<String, Object> data = new HashMap<>();
            data.put("totalBirds", totalBirds);
            data.put("activeCages", activeCages);

            if (!doc.exists() || doc.getTimestamp("farmStartDate") == null) {
                data.put("farmStartDate", Timestamp.now());
            }

            statsDoc().set(data, SetOptions.merge())
                    .addOnSuccessListener(v -> listener.onSuccess())
                    .addOnFailureListener(listener::onError);

        }).addOnFailureListener(listener::onError);
    }

    // Restart days running: reset farmStartDate to now in farm_data/stats -------------------------
    public void restartDaysRunning(OnSaveListener listener) {
        Map<String, Object> data = new HashMap<>();
        data.put("farmStartDate", Timestamp.now());
        statsDoc().set(data, SetOptions.merge())
                .addOnSuccessListener(v -> listener.onSuccess())
                .addOnFailureListener(listener::onError);
    }
}