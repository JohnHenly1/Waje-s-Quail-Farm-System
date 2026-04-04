package com.example.exp1

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class SetupAccountActivity : AppCompatActivity() {

    private lateinit var editName: EditText
    private lateinit var editBirthday: EditText
    private lateinit var editLocation: EditText
    private lateinit var editPassword: EditText
    private lateinit var imgProfile: ImageView
    private lateinit var btnComplete: Button
    
    private var email: String = ""
    private var role: String = ""
    private var photoUrl: String = ""
    private val calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_account)

        email = intent.getStringExtra("email") ?: ""
        role = intent.getStringExtra("role") ?: "staff"
        val googleName = intent.getStringExtra("name") ?: ""
        photoUrl = intent.getStringExtra("photoUrl") ?: ""

        editName = findViewById(R.id.editSetupName)
        editBirthday = findViewById(R.id.editSetupBirthday)
        editLocation = findViewById(R.id.editSetupLocation)
        editPassword = findViewById(R.id.editSetupPassword)
        imgProfile = findViewById(R.id.imgSetupProfile)
        btnComplete = findViewById(R.id.btnCompleteSetup)

        // Pre-fill from Google Account
        editName.setText(googleName)
        if (photoUrl.isNotEmpty()) {
            Glide.with(this).load(photoUrl).circleCrop().into(imgProfile)
        }

        // Calendar Picker for Birthday
        editBirthday.setOnClickListener {
            showDatePicker()
        }

        btnComplete.setOnClickListener {
            val name = editName.text.toString().trim()
            val bday = editBirthday.text.toString().trim()
            val loc = editLocation.text.toString().trim()
            val pass = editPassword.text.toString().trim()

            if (name.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Name and Password are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Strong Password Restriction
            val passwordError = getPasswordStrengthError(pass)
            if (passwordError != null) {
                Toast.makeText(this, passwordError, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val db = FirebaseFirestore.getInstance()
            val userData = hashMapOf(
                "name" to name,
                "email" to email,
                "birthday" to bday,
                "location" to loc,
                "password" to pass,
                "role" to role,
                "profilePic" to photoUrl,
                "status" to "approved",
                "setupCompleted" to true,
                "verifiedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            db.collection("user_access").document(email).set(userData)
                .addOnSuccessListener {
                    val accountManager = AccountManager(this)
                    accountManager.registerAccount(name, email, pass, role)
                    accountManager.saveCurrentSession(email)

                    Toast.makeText(this, "Profile Setup Complete!", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, DashboardActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error saving data", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun getPasswordStrengthError(password: String): String? {
        if (password.length < 8) return "Password must be at least 8 characters long"
        if (!password.any { it.isUpperCase() }) return "Password must contain at least one uppercase letter"
        if (!password.any { it.isLowerCase() }) return "Password must contain at least one lowercase letter"
        if (!password.any { it.isDigit() }) return "Password must contain at least one digit"
        val symbols = "!@#$%^&*()_+-=[]{}|;':\",./<>?"
        if (!password.any { it in symbols }) return "Password must contain at least one special character"
        return null
    }

    private fun showDatePicker() {
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            val format = "MM/dd/yyyy"
            val sdf = SimpleDateFormat(format, Locale.US)
            editBirthday.setText(sdf.format(calendar.time))
        }

        DatePickerDialog(
            this,
            dateSetListener,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
