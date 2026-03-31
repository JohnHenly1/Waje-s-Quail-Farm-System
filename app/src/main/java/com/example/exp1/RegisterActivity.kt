package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var btnRequestCode: Button
    private lateinit var btnEnterCode: Button
    private lateinit var btnFarmSetup: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        btnRequestCode = findViewById(R.id.btnRequestCode)
        btnEnterCode = findViewById(R.id.btnEnterCode)
        btnFarmSetup = findViewById(R.id.btnFarmSetup)

        val db = FirebaseFirestore.getInstance()

        btnRequestCode.setOnClickListener {
            showRequestCodeDialog(db)
        }

        btnEnterCode.setOnClickListener {
            val intent = Intent(this, EnterCodeActivity::class.java)
            startActivity(intent)
        }

        checkOwnerExists(db)

        btnFarmSetup.setOnClickListener {
            val intent = Intent(this, FarmSetupActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showRequestCodeDialog(db: FirebaseFirestore) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_request_access, null)
        val editEmail = dialogView.findViewById<EditText>(R.id.requestEmail)
        val editName = dialogView.findViewById<EditText>(R.id.requestName)
        val roleGroup = dialogView.findViewById<RadioGroup>(R.id.requestRoleGroup)
        
        val builder = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Submit Request", null)
            .setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val email = editEmail.text.toString().trim().lowercase()
            val name = editName.text.toString().trim()
            
            val selectedRoleId = roleGroup.checkedRadioButtonId
            val role = if (selectedRoleId == R.id.radioRequestBackup) "backup_owner" else "staff"

            if (email.isNotEmpty() && name.isNotEmpty()) {
                val requestMap = hashMapOf(
                    "name" to name,
                    "email" to email,
                    "status" to "pending",
                    "role" to role,
                    "requestedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                db.collection("user_access")
                    .document(email)
                    .set(requestMap)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Request submitted for $role. Please wait for owner approval.", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkOwnerExists(db: FirebaseFirestore) {
        db.collection("user_access")
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