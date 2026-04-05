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
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class EnterCodeActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var editInviteCode: EditText
    private var enteredCode: String = ""

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
                Toast.makeText(this, "Please enter the 6-digit code from your email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Force account picker
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, 102)
            }
        }

        // Back button logic
        findViewById<android.view.View>(R.id.backButton)?.setOnClickListener {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 102) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java) ?: return
                val email = account.email?.lowercase() ?: ""

                val db = FirebaseFirestore.getInstance()
                db.collection("user_access").document(email).get()
                    .addOnSuccessListener { doc ->
                        if (doc.exists()) {
                            val savedCode = doc.getString("verificationCode")
                            val status = doc.getString("status") ?: ""

                            if (status == "approved" && savedCode == enteredCode) {
                                // Success!
                                val intent = Intent(this, SetupAccountActivity::class.java)
                                intent.putExtra("email", email)
                                intent.putExtra("name", account.displayName ?: "")
                                intent.putExtra("photoUrl", account.photoUrl?.toString() ?: "")
                                intent.putExtra("role", doc.getString("role") ?: "staff")
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(this, "Verification failed. Check your code.", Toast.LENGTH_LONG).show()
                                googleSignInClient.signOut()
                            }
                        } else {
                            showNotRegisteredDialog()
                            googleSignInClient.signOut()
                        }
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "Google Sign-In Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNotRegisteredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Account Not Found")
            .setMessage("This Google account is not registered. Please request access first.")
            .setPositiveButton("OK", null)
            .show()
    }
}
