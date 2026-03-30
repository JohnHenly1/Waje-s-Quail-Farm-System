package com.example.exp1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button

    private val RC_SIGN_IN = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        btnLogin = findViewById(R.id.Btn)
        btnRegister = findViewById(R.id.btnRegister)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        btnLogin.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }

        btnRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {

            val task = GoogleSignIn.getSignedInAccountFromIntent(data)

            try {
                val account = task.getResult(ApiException::class.java)

                if (account == null) {
                    Toast.makeText(this, "Account is null", Toast.LENGTH_SHORT).show()
                    return
                }

                val email = account.email?.trim()?.lowercase() ?: ""

                Log.d("LOGIN_DEBUG", "Email: $email")

                if (email.isEmpty()) {
                    Toast.makeText(this, "No email found", Toast.LENGTH_SHORT).show()
                    return
                }

                val db = FirebaseFirestore.getInstance()

                // Check Firestore for user existence
                db.collection("invited_users")
                    .document(email)
                    .get()
                    .addOnSuccessListener { document ->

                        Log.d("LOGIN_DEBUG", "Firestore exists: ${document.exists()}")

                        if (document.exists()) {

                            val credential = GoogleAuthProvider.getCredential(account.idToken, null)

                            auth.signInWithCredential(credential)
                                .addOnCompleteListener { authTask ->
                                    if (authTask.isSuccessful) {

                                        Toast.makeText(this, "Login Success!", Toast.LENGTH_SHORT).show()

                                        val intent = Intent(this, DashboardActivity::class.java)
                                        intent.putExtra("username", email)
                                        startActivity(intent)
                                        finish()

                                    } else {
                                        Toast.makeText(this, "Auth Failed: ${authTask.exception?.message}", Toast.LENGTH_LONG).show()
                                        Log.e("LOGIN_DEBUG", "Auth error", authTask.exception)
                                    }
                                }

                        } else {
                            Toast.makeText(this, "Access Denied. Not invited.", Toast.LENGTH_LONG).show()
                            googleSignInClient.signOut()
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Firestore Error: ${e.message}", Toast.LENGTH_LONG).show()
                        Log.e("LOGIN_DEBUG", "Firestore error", e)
                    }

            } catch (e: ApiException) {
                Toast.makeText(this, "Google Sign-In Failed: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("LOGIN_DEBUG", "Google error", e)
            }
        }
    }
}