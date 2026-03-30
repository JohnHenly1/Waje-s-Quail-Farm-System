package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    // These are the new buttons from your updated layout
    private lateinit var btnRequestCode: Button
    private lateinit var btnEnterCode: Button
    private lateinit var btnFarmSetup: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // 1. Initialize the new buttons
        btnRequestCode = findViewById(R.id.btnRequestCode)
        btnEnterCode = findViewById(R.id.btnEnterCode)
        btnFarmSetup = findViewById(R.id.btnFarmSetup)

        val db = FirebaseFirestore.getInstance()

        // 2. Logic for Requesting a Code (Shows a Dialog as we discussed)
        btnRequestCode.setOnClickListener {
            showRequestCodeDialog()
        }

        // 3. Logic for entering an existing code
        btnEnterCode.setOnClickListener {
            Toast.makeText(this, "Enter Code feature coming soon!", Toast.LENGTH_SHORT).show()
            // Here you will eventually navigate to a verification screen
        }

        // 4. Check if Farm Setup should be visible (Owner check)
        checkOwnerExists(db)

        btnFarmSetup.setOnClickListener {
            // Navigate to Farm Setup Activity
            Toast.makeText(this, "Opening Farm Setup...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRequestCodeDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null) // We can reuse or make a new one
        val builder = AlertDialog.Builder(this)
            .setTitle("Request Access Code")
            .setView(dialogView)

        // Note: You'll want to create a specific layout for this dialog
        // with an Email field and Role RadioButtons to match your plan.

        builder.setPositiveButton("Submit Request") { dialog, _ ->
            Toast.makeText(this, "Request Sent to Owner!", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun checkOwnerExists(db: FirebaseFirestore) {
        db.collection("users")
            .whereEqualTo("role", "owner")
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    btnFarmSetup.visibility = android.view.View.VISIBLE
                } else {
                    btnFarmSetup.visibility = android.view.View.GONE
                }
            }
    }
}