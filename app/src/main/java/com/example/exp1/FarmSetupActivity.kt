package com.example.exp1

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class FarmSetupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var accountManager: AccountManager
    private val RC_SIGN_IN = 101

    private lateinit var setupTotalBirds: EditText
    private lateinit var setupActiveCages: EditText
    private lateinit var setupFarmLocationStreet: EditText
    private lateinit var setupFarmLocationCity: EditText
    private lateinit var setupFarmLocationState: EditText
    private lateinit var setupFarmLocationPostal: EditText
    private lateinit var setupBirthday: EditText
    private lateinit var setupAddressStreet: EditText
    private lateinit var setupAddressCity: EditText
    private lateinit var setupAddressState: EditText
    private lateinit var setupAddressPostal: EditText
    private lateinit var setupPassword: EditText
    private lateinit var setupPasswordLayout: TextInputLayout
    private lateinit var ownerProfilePic: ImageView
    
    private val calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_farm_setup)

        auth = FirebaseAuth.getInstance()
        accountManager = AccountManager(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setupTitle)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Initialize Views
        setupTotalBirds = findViewById(R.id.setupTotalBirds)
        setupActiveCages = findViewById(R.id.setupActiveCages)
        setupFarmLocationStreet = findViewById(R.id.setupFarmLocationStreet)
        setupFarmLocationCity = findViewById(R.id.setupFarmLocationCity)
        setupFarmLocationState = findViewById(R.id.setupFarmLocationState)
        setupFarmLocationPostal = findViewById(R.id.setupFarmLocationPostal)
        setupBirthday = findViewById(R.id.setupBirthday)
        setupAddressStreet = findViewById(R.id.setupAddressStreet)
        setupAddressCity = findViewById(R.id.setupAddressCity)
        setupAddressState = findViewById(R.id.setupAddressState)
        setupAddressPostal = findViewById(R.id.setupAddressPostal)
        setupPassword = findViewById(R.id.setupPassword)
        setupPasswordLayout = setupPassword.parent.parent as TextInputLayout
        ownerProfilePic = findViewById(R.id.ownerProfilePic)

        // Setup Birthday Date Picker
        setupBirthday.setOnClickListener {
            showDatePicker()
        }

        // Auto-detect password format
        setupPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                if (pass.isNotEmpty()) {
                    val error = getPasswordStrengthError(pass)
                    setupPasswordLayout.error = error
                } else {
                    setupPasswordLayout.error = null
                }
            }
        })

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        findViewById<android.view.View>(R.id.btnCompleteSetup).setOnClickListener {
            val birds = setupTotalBirds.text.toString().trim()
            val cages = setupActiveCages.text.toString().trim()
            val farmLocStreet = setupFarmLocationStreet.text.toString().trim()
            val farmLocCity = setupFarmLocationCity.text.toString().trim()
            val farmLocState = setupFarmLocationState.text.toString().trim()
            val farmLocPostal = setupFarmLocationPostal.text.toString().trim()
            val bday = setupBirthday.text.toString().trim()
            val street = setupAddressStreet.text.toString().trim()
            val city = setupAddressCity.text.toString().trim()
            val state = setupAddressState.text.toString().trim()
            val postal = setupAddressPostal.text.toString().trim()
            val pass = setupPassword.text.toString().trim()

            if (birds.isEmpty() || cages.isEmpty() || farmLocStreet.isEmpty() || farmLocCity.isEmpty() || 
                farmLocState.isEmpty() || farmLocPostal.isEmpty() || bday.isEmpty() || street.isEmpty() || 
                city.isEmpty() || state.isEmpty() || postal.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate farm location
            val farmLocError = validateAddress(farmLocStreet, farmLocCity, farmLocState, farmLocPostal)
            if (farmLocError != null) {
                Toast.makeText(this, farmLocError, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate address components
            val addressError = validateAddress(street, city, state, postal)
            if (addressError != null) {
                Toast.makeText(this, addressError, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Strong Password Restriction
            val passwordError = getPasswordStrengthError(pass)
            if (passwordError != null) {
                setupPasswordLayout.error = passwordError
                Toast.makeText(this, passwordError, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Sign out to force account picker
            googleSignInClient.signOut().addOnCompleteListener {
                startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
            }
        }
    }

    private fun showDatePicker() {
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            val format = "MM/dd/yyyy"
            val sdf = SimpleDateFormat(format, Locale.US)
            setupBirthday.setText(sdf.format(calendar.time))
        }

        DatePickerDialog(
            this,
            dateSetListener,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_SIGN_IN) return

        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java) ?: return
            val email = account.email?.trim()?.lowercase() ?: ""
            val displayName = account.displayName ?: "Owner"
            val photoUrl = account.photoUrl?.toString() ?: ""
            
            val birdsCount = setupTotalBirds.text.toString().toInt()
            val cagesCount = setupActiveCages.text.toString().toInt()
            val farmLocStreet = setupFarmLocationStreet.text.toString().trim()
            val farmLocCity = setupFarmLocationCity.text.toString().trim()
            val farmLocState = setupFarmLocationState.text.toString().trim()
            val farmLocPostal = setupFarmLocationPostal.text.toString().trim()
            val bday = setupBirthday.text.toString().trim()
            val street = setupAddressStreet.text.toString().trim()
            val city = setupAddressCity.text.toString().trim()
            val state = setupAddressState.text.toString().trim()
            val postal = setupAddressPostal.text.toString().trim()
            val pass = setupPassword.text.toString().trim()

            val db = FirebaseFirestore.getInstance()
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)

            if (photoUrl.isNotEmpty()) {
                Glide.with(this).load(photoUrl).circleCrop().into(ownerProfilePic)
            }

            auth.signInWithCredential(credential).addOnCompleteListener { authTask ->
                if (!authTask.isSuccessful) {
                    val errorMsg = authTask.exception?.message ?: "Sign-In Failed"
                    Toast.makeText(this, "Sign-In Failed: $errorMsg", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }

                val userAccess = hashMapOf(
                    "name" to displayName,
                    "email" to email,
                    "password" to pass,
                    "birthday" to bday,
                    "address" to mapOf(
                        "street" to street,
                        "city" to city,
                        "state" to state,
                        "postalCode" to postal
                    ),
                    "profilePic" to photoUrl,
                    "status" to "approved",
                    "role" to "owner",
                    "setupCompleted" to true,
                    "verifiedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                // Write user_access doc
                db.collection("user_access").document(email).set(userAccess)
                    .addOnSuccessListener {
                        // Write shared farm stats including location
                        val farmStats = hashMapOf(
                            "totalBirds" to birdsCount,
                            "activeCages" to cagesCount,
                            "farmLocation" to mapOf(
                                "street" to farmLocStreet,
                                "city" to farmLocCity,
                                "state" to farmLocState,
                                "postalCode" to farmLocPostal
                            ),
                            "farmStartDate" to com.google.firebase.Timestamp.now()
                        )
                        
                        db.collection("farm_data").document("stats").set(farmStats)
                            .addOnSuccessListener {
                                accountManager.registerAccount(displayName, email, pass, "owner")
                                accountManager.updateCachedRole(displayName, "owner")
                                accountManager.saveCurrentSession(email)

                                Toast.makeText(this, "Farm Setup Complete!", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this, DashboardActivity::class.java)
                                    .putExtra("username", email))
                                finish()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Stats save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Setup failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        } catch (e: ApiException) {
            Toast.makeText(this, "Sign-In Failed", Toast.LENGTH_SHORT).show()
        }
    }
}
