package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class EnterCodeActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var accountManager: AccountManager
    private val RC_SIGN_IN = 102

    private lateinit var editInviteCode: EditText
    private var enteredCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_enter_code)

        auth = FirebaseAuth.getInstance()
        accountManager = AccountManager(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.enterCodeTitle)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        editInviteCode = findViewById(R.id.editInviteCode)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        findViewById<android.view.View>(R.id.btnVerifyCode).setOnClickListener {
            enteredCode = editInviteCode.text.toString().trim()

            if (enteredCode.length != 6) {
                Toast.makeText(this, "Please enter a valid 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Start Google Sign In
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account == null) return

                val email = account.email?.trim()?.lowercase() ?: ""
                val displayName = account.displayName ?: "User"

                val db = FirebaseFirestore.getInstance()

                // Check if the code is valid in 'invite_codes' collection
                db.collection("invite_codes")
                    .document(enteredCode)
                    .get()
                    .addOnSuccessListener { document ->
                        if (document.exists()) {
                            val role = document.getString("role") ?: "staff"
                            
                            // Proceed to Auth
                            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                            auth.signInWithCredential(credential)
                                .addOnCompleteListener { authTask ->
                                    if (authTask.isSuccessful) {
                                        // Auto-approve user
                                        val userAccess = hashMapOf(
                                            "name" to displayName,
                                            "email" to email,
                                            "status" to "approved",
                                            "role" to role
                                        )

                                        db.collection("user_access").document(email).set(userAccess)
                                            .addOnSuccessListener {
                                                accountManager.registerAccount(displayName, email, "google_auth", role)
                                                accountManager.saveCurrentSession(displayName)

                                                Toast.makeText(this, "Code Verified! Welcome.", Toast.LENGTH_SHORT).show()
                                                val intent = Intent(this, DashboardActivity::class.java)
                                                intent.putExtra("username", displayName)
                                                startActivity(intent)
                                                finish()
                                            }
                                    }
                                }
                        } else {
                            Toast.makeText(this, "Invalid or Expired Code", Toast.LENGTH_LONG).show()
                            googleSignInClient.signOut()
                        }
                    }
            } catch (e: ApiException) {
                Toast.makeText(this, "Verification Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}