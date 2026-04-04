package com.example.exp1

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
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
    private lateinit var setupFarmLocation: EditText
    private lateinit var setupBirthday: EditText
    private lateinit var setupAddress: EditText
    private lateinit var setupPassword: EditText
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
        setupFarmLocation = findViewById(R.id.setupFarmLocation)
        setupBirthday = findViewById(R.id.setupBirthday)
        setupAddress = findViewById(R.id.setupAddress)
        setupPassword = findViewById(R.id.setupPassword)
        ownerProfilePic = findViewById(R.id.ownerProfilePic)

        // Setup Birthday Date Picker
        setupBirthday.setOnClickListener {
            showDatePicker()
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        findViewById<android.view.View>(R.id.btnCompleteSetup).setOnClickListener {
            val birds = setupTotalBirds.text.toString().trim()
            val cages = setupActiveCages.text.toString().trim()
            val farmLoc = setupFarmLocation.text.toString().trim()
            val bday = setupBirthday.text.toString().trim()
            val address = setupAddress.text.toString().trim()
            val pass = setupPassword.text.toString().trim()

            if (birds.isEmpty() || cages.isEmpty() || farmLoc.isEmpty() || 
                bday.isEmpty() || address.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Strong Password Restriction
            val passwordError = getPasswordStrengthError(pass)
            if (passwordError != null) {
                Toast.makeText(this, passwordError, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
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
            val farmLoc = setupFarmLocation.text.toString().trim()
            val bday = setupBirthday.text.toString().trim()
            val ownerAddress = setupAddress.text.toString().trim()
            val pass = setupPassword.text.toString().trim()

            val db = FirebaseFirestore.getInstance()
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)

            if (photoUrl.isNotEmpty()) {
                Glide.with(this).load(photoUrl).circleCrop().into(ownerProfilePic)
            }

            auth.signInWithCredential(credential).addOnCompleteListener { authTask ->
                if (!authTask.isSuccessful) {
                    Toast.makeText(this, "Sign-In Failed", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                val userAccess = hashMapOf(
                    "name" to displayName,
                    "email" to email,
                    "password" to pass,
                    "birthday" to bday,
                    "address" to ownerAddress,
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
                            "farmLocation" to farmLoc,
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
