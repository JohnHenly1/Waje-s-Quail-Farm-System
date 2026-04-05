package com.example.exp1;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CameraHelper {

    private final AppCompatActivity activity;
    private Uri photoUri;

    private final ActivityResultLauncher<Uri> takePictureLauncher;
    private final ActivityResultLauncher<String> requestPermissionLauncher;

    //  interface
    public interface OnPhotoDetected {
        void onResults(Uri photoUri, java.util.List<DetectionResult> results);
    }

    private OnPhotoDetected detectionCallback;
    private YoloDetector yoloDetector;

    //  Single constructor
    public CameraHelper(AppCompatActivity activity, OnPhotoDetected callback) {
        this.activity = activity;
        this.detectionCallback = callback;
        this.yoloDetector = new YoloDetector(activity);

        takePictureLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && photoUri != null) {
                        try {
                            android.graphics.Bitmap bitmap =
                                    MediaStore.Images.Media.getBitmap(activity.getContentResolver(), photoUri);

                            java.util.List<DetectionResult> results = yoloDetector.detect(bitmap);

                            if (detectionCallback != null) {
                                detectionCallback.onResults(photoUri, results);
                            }

                        } catch (Exception e) {
                            Toast.makeText(activity,
                                    "Detection failed: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        requestPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) openCamera();
                    else Toast.makeText(activity, "Camera permission required", Toast.LENGTH_SHORT).show();
                }
        );
    }

    public void launch() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    public Uri getPhotoUri() {
        return photoUri;
    }

    private void openCamera() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File imageFile = File.createTempFile("PHOTO_" + timestamp + "_", ".jpg", activity.getCacheDir());

            photoUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    imageFile
            );

            takePictureLauncher.launch(photoUri);

        } catch (Exception e) {
            Toast.makeText(activity, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}