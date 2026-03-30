package com.example.exp1;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Toast;

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

    public CameraHelper(AppCompatActivity activity) {
        this.activity = activity;

        // take picture
        takePictureLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && photoUri != null) {
                        Toast.makeText(activity, "Photo saved!", Toast.LENGTH_SHORT).show();
                        // Extend here: pass photoUri to an upload method, open preview, etc.
                    }
                }
        );

        // request CAMERA permission
        requestPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) openCamera();
                    else Toast.makeText(activity, "Camera permission is required", Toast.LENGTH_SHORT).show();
                }
        );
    }

    // Call this from any button click to open the camera
    public void launch() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    //Returns the URI of the last photo taken (may be null).
    public Uri getPhotoUri() {
        return photoUri;
    }

    //----------------------------------------------------------------------------------------------

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
            // Fallback: launch any installed camera app without a specific save URI
            Intent fallback = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (fallback.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(fallback);
            } else {
                Toast.makeText(activity, "No camera app found", Toast.LENGTH_SHORT).show();
            }
        }
    }
}