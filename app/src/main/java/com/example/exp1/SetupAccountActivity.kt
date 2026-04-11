package com.example.exp1

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class SetupAccountActivity : AppCompatActivity() {

    private lateinit var editName: EditText
    private lateinit var editBirthday: EditText
    private lateinit var editAddressStreet: EditText
    private lateinit var editAddressCity: EditText
    private lateinit var editAddressState: EditText
    private lateinit var editAddressPostal: EditText
    private lateinit var editPassword: EditText
    private lateinit var editPasswordLayout: TextInputLayout
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
        editAddressStreet = findViewById(R.id.editAddressStreet)
        editAddressCity = findViewById(R.id.editAddressCity)
        editAddressState = findViewById(R.id.editAddressState)
        editAddressPostal = findViewById(R.id.editAddressPostal)
        editPassword = findViewById(R.id.editSetupPassword)
        editPasswordLayout = findViewById(R.id.setupPasswordInputLayout)
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

        // Auto-detect password format
        editPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                if (pass.isNotEmpty()) {
                    val error = getPasswordStrengthError(pass)
                    editPasswordLayout.error = error
                } else {
                    editPasswordLayout.error = null
                }
            }
        })

        btnComplete.setOnClickListener {
            val name = editName.text.toString().trim()
            val bday = editBirthday.text.toString().trim()
            val street = editAddressStreet.text.toString().trim()
            val city = editAddressCity.text.toString().trim()
            val state = editAddressState.text.toString().trim()
            val postal = editAddressPostal.text.toString().trim()
            val pass = editPassword.text.toString().trim()

            if (name.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Name and Password are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate address if any component is provided
            if (street.isNotEmpty() || city.isNotEmpty() || state.isNotEmpty() || postal.isNotEmpty()) {
                if (street.isEmpty() || city.isEmpty() || state.isEmpty() || postal.isEmpty()) {
                    Toast.makeText(this, "Please fill all address fields or leave them all empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val addressError = validateAddress(street, city, state, postal)
                if (addressError != null) {
                    Toast.makeText(this, addressError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Strong Password Restriction
            val passwordError = getPasswordStrengthError(pass)
            if (passwordError != null) {
                editPasswordLayout.error = passwordError
                Toast.makeText(this, passwordError, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val db = FirebaseFirestore.getInstance()
            // Check if email is already registered
            db.collection("user_access").document(email).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        Toast.makeText(this, "This email is already registered.", Toast.LENGTH_SHORT).show()
                    } else {
                        val addressMap = if (street.isNotEmpty()) {
                            mapOf(
                                "street" to street,
                                "city" to city,
                                "state" to state,
                                "postalCode" to postal
                            )
                        } else {
                            mapOf<String, String>()
                        }

                        val userData = hashMapOf(
                            "name" to name,
                            "email" to email,
                            "birthday" to bday,
                            "address" to addressMap,
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
                .addOnFailureListener {
                    Toast.makeText(this, "Error checking email", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun validateAddress(street: String, city: String, state: String, postal: String): String? {
        if (street.length < 5) return "Street address must be at least 5 characters"
        if (city.length < 2) return "City must be at least 2 characters"
        if (state.length < 2) return "State/Province must be at least 2 characters"
        if (postal.length < 3) return "Postal code must be at least 3 characters"
        
        // Check for suspicious characters
        val invalidChars = "!@#$%^&*()=[]{}|;':\",<>?"
        if (street.any { it in invalidChars } || city.any { it in invalidChars } || 
            state.any { it in invalidChars } || postal.any { it in invalidChars }) {
            return "Address contains invalid characters"
        }
        
        return null
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
