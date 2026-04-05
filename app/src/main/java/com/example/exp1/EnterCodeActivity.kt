package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.firestore.FirebaseFirestore

class EnterCodeActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var editInviteCode: EditText
    private var enteredCode: String = ""
    private var detectedEmail: String = ""
    private var detectedRole: String = "staff"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_enter_code)

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
                Toast.makeText(this, "Please enter the 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. Detect if code is Email-bound or Generic
            verifyAndDetectAccount()
        }

        findViewById<android.view.View>(R.id.backButton)?.setOnClickListener {
            finish()
        }
    }

    private fun verifyAndDetectAccount() {
        val db = FirebaseFirestore.getInstance()
        
        // Step A: Check user_access (Codes assigned to specific emails)
        db.collection("user_access")
            .whereEqualTo("verificationCode", enteredCode)
            .get()
            .addOnSuccessListener { snapshots ->
                if (!snapshots.isEmpty) {
                    val doc = snapshots.documents[0]
                    detectedEmail = doc.id
                    detectedRole = doc.getString("role") ?: "staff"
                    
                    attemptAutoSignIn()
                } else {
                    // Step B: Check invite_codes (Generic numeric codes)
                    db.collection("invite_codes").document(enteredCode).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                val expiresAt = doc.getLong("expiresAt") ?: 0L
                                if (expiresAt != 0L && System.currentTimeMillis() > expiresAt) {
                                    Toast.makeText(this, "This code has expired.", Toast.LENGTH_LONG).show()
                                } else {
                                    detectedEmail = "" // Signal generic code
                                    detectedRole = doc.getString("role") ?: "staff"
                                    triggerManualSignIn()
                                }
                            } else {
                                Toast.makeText(this, "Invalid Code. Please check your email.", Toast.LENGTH_LONG).show()
                            }
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Connection Error", Toast.LENGTH_SHORT).show()
            }
    }

    private fun attemptAutoSignIn() {
        googleSignInClient.silentSignIn().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val account = task.result
                if (account?.email?.lowercase() == detectedEmail) {
                    proceedToSetup(account)
                } else {
                    triggerManualSignIn()
                }
            } else {
                triggerManualSignIn()
            }
        }
    }

    private fun triggerManualSignIn() {
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, 102)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 102) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java) ?: return
                val email = account.email?.lowercase() ?: ""

                if (detectedEmail.isEmpty() || email == detectedEmail) {
                    proceedToSetup(account)
                } else {
                    Toast.makeText(this, "This code was issued to $detectedEmail.", Toast.LENGTH_LONG).show()
                    googleSignInClient.signOut()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Sign-In Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun proceedToSetup(account: GoogleSignInAccount) {
        val intent = Intent(this, SetupAccountActivity::class.java)
        intent.putExtra("email", account.email?.lowercase())
        intent.putExtra("name", account.displayName ?: "")
        intent.putExtra("photoUrl", account.photoUrl?.toString() ?: "")
        intent.putExtra("role", detectedRole)
        startActivity(intent)
        finish()
    }
}
