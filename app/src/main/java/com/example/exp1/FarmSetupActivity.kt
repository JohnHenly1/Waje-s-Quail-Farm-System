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

class FarmSetupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var accountManager: AccountManager
    private val RC_SIGN_IN = 101

    private lateinit var setupTotalBirds: EditText
    private lateinit var setupActiveCages: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_farm_setup)

        auth         = FirebaseAuth.getInstance()
        accountManager = AccountManager(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setupTitle)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        setupTotalBirds  = findViewById(R.id.setupTotalBirds)
        setupActiveCages = findViewById(R.id.setupActiveCages)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        findViewById<android.view.View>(R.id.btnCompleteSetup).setOnClickListener {
            val birds = setupTotalBirds.text.toString().trim()
            val cages = setupActiveCages.text.toString().trim()
            if (birds.isEmpty() || cages.isEmpty()) {
                Toast.makeText(this, "Please enter farm statistics", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_SIGN_IN) return

        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account     = task.getResult(ApiException::class.java) ?: return
            val email       = account.email?.trim()?.lowercase() ?: ""
            val displayName = account.displayName ?: "Owner"

            val db          = FirebaseFirestore.getInstance()
            val credential  = GoogleAuthProvider.getCredential(account.idToken, null)

            auth.signInWithCredential(credential).addOnCompleteListener { authTask ->
                if (!authTask.isSuccessful) {
                    Toast.makeText(this, "Sign-In Failed", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }

                val birds = setupTotalBirds.text.toString().toInt()
                val cages = setupActiveCages.text.toString().toInt()

                val userAccess = hashMapOf(
                    "name"   to displayName,
                    "email"  to email,
                    "status" to "approved",
                    "role"   to "owner"
                )

                // Write user_access doc (per-user role record)
                db.collection("user_access").document(email).set(userAccess)
                    .addOnSuccessListener {
                        // Write shared farm stats to farm_data/shared (not per-user)
                        FarmRepository.saveFarmStats(birds, cages) { err ->
                            if (err != null) {
                                Toast.makeText(this, "Stats save failed: ${err.message}", Toast.LENGTH_SHORT).show()
                                return@saveFarmStats
                            }
                            FarmRepository.saveFarmStartDateIfAbsent()

                            accountManager.registerAccount(displayName, email, "google_auth", "owner")
                            accountManager.updateCachedRole(displayName, "owner")
                            accountManager.saveCurrentSession(displayName)

                            Toast.makeText(this, "Farm Setup Complete!", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, DashboardActivity::class.java)
                                .putExtra("username", displayName))
                            finish()
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