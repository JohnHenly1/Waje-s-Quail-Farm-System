package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
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
    private lateinit var btnManualLogin: Button
    private lateinit var editLoginEmail: EditText
    private lateinit var accountManager: AccountManager

    private val handler = Handler(Looper.getMainLooper())
    private val RC_SIGN_IN = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        accountManager = AccountManager(this)
        val currentSession = accountManager.getCurrentUsername()
        if (currentSession != null) {
            val intent = Intent(this, DashboardActivity::class.java)
            intent.putExtra("username", currentSession)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        
        btnLogin = findViewById(R.id.Btn)
        btnRegister = findViewById(R.id.btnRegister)
        btnManualLogin = findViewById(R.id.btnManualLogin)
        editLoginEmail = findViewById(R.id.editLoginEmail)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Google Login
        btnLogin.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }

        // Manual Email Login (Bypasses Google SHA-1 check)
        btnManualLogin.setOnClickListener {
            handleManualLogin()
        }

        btnRegister.setOnClickListener {
            showLoading {
                val intent = Intent(this, RegisterActivity::class.java)
                startActivity(intent)
            }
        }
    }

    fun showLoading(action: () -> Unit) {
        val loadingLayout = findViewById<View>(R.id.loadingLayout)
        val loadingIcon   = findViewById<View>(R.id.loadingIcon)

        if (loadingLayout != null && loadingIcon != null) {
            loadingLayout.visibility = View.VISIBLE
            val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
            loadingIcon.startAnimation(jump)

            handler.postDelayed({
                loadingLayout.visibility = View.GONE
                loadingIcon.clearAnimation()
                action()
            }, 1200)
        } else {
            action()
        }
    }

    private fun handleManualLogin() {
        val email = editLoginEmail.text.toString().trim().lowercase()
        if (email.isEmpty()) {
            Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
            return
        }

        val db = FirebaseFirestore.getInstance()
        
        // Check new user_access first
        db.collection("user_access")
            .document(email)
            .get()
            .addOnSuccessListener { accessDoc ->
                val status = accessDoc.getString("status") ?: ""
                val role = accessDoc.getString("role") ?: "staff"
                val name = accessDoc.getString("name") ?: "User"

                if (status == "approved") {
                    enterApp(name, email, role)
                } else if (status == "pending") {
                    Toast.makeText(this, "Your request is still pending approval.", Toast.LENGTH_LONG).show()
                } else {
                    // Fallback to teammate's old invited_users
                    db.collection("invited_users")
                        .document(email)
                        .get()
                        .addOnSuccessListener { invitedDoc ->
                            if (invitedDoc.exists()) {
                                enterApp("Invited User", email, "staff")
                            } else {
                                Toast.makeText(this, "Access Denied. Please register first.", Toast.LENGTH_LONG).show()
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Connection Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun enterApp(name: String, email: String, role: String) {
        // Save to local session
        accountManager.registerAccount(name, email, "manual_login", role)
        accountManager.saveCurrentSession(name)

        Toast.makeText(this, "Login Success as $role", Toast.LENGTH_SHORT).show()
        showLoading {
            val intent = Intent(this, DashboardActivity::class.java)
            intent.putExtra("username", name)
            startActivity(intent)
            finish()
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

                db.collection("user_access")
                    .document(email)
                    .get()
                    .addOnSuccessListener { accessDoc ->
                        val status = accessDoc.getString("status") ?: ""
                        val role = accessDoc.getString("role") ?: "staff"

                        if (status == "approved") {
                            completeGoogleSignIn(account, email, displayName, role)
                        } else if (status == "pending") {
                            Toast.makeText(this, "Your request is still pending approval.", Toast.LENGTH_LONG).show()
                            googleSignInClient.signOut()
                        } else {
                            db.collection("invited_users")
                                .document(email)
                                .get()
                                .addOnSuccessListener { invitedDoc ->
                                    if (invitedDoc.exists()) {
                                        completeGoogleSignIn(account, email, displayName, "staff")
                                    } else {
                                        Toast.makeText(this, "Access Denied. Please register first.", Toast.LENGTH_LONG).show()
                                        googleSignInClient.signOut()
                                    }
                                }
                        }
                    }
            } catch (e: ApiException) {
                Log.e("LOGIN_DEBUG", "Error: ${e.statusCode}")
                Toast.makeText(this, "Sign-In Failed (Code: ${e.statusCode}). Try Manual Login above.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun completeGoogleSignIn(account: GoogleSignInAccount, email: String, name: String, role: String) {
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { authTask ->
                if (authTask.isSuccessful) {
                    enterApp(name, email, role)
                } else {
                    Toast.makeText(this, "Firebase Auth Failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }
}
