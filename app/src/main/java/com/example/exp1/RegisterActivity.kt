package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class RegisterActivity : AppCompatActivity() {

    private lateinit var btnEnterCode: Button
    private lateinit var btnFarmSetup: Button
    private lateinit var accountManager: AccountManager

    private lateinit var registrationUI: View
    private lateinit var pendingLayout: View
    private lateinit var pendingEmailTv: TextView
    private lateinit var pendingQuail: ImageView
    private var statusListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        accountManager = AccountManager(this)
        registrationUI = findViewById(R.id.registrationUI)
        pendingLayout = findViewById(R.id.pendingLayout)
        pendingEmailTv = findViewById(R.id.pendingEmailTv)
        pendingQuail = findViewById(R.id.pendingQuail)

        btnEnterCode = findViewById(R.id.btnEnterCode)
        btnFarmSetup = findViewById(R.id.btnFarmSetup)

        val db = FirebaseFirestore.getInstance()

        // Check if resuming pending
        val pendingEmail = intent.getStringExtra("pendingEmail") ?: accountManager.getCurrentUsername()
        if (pendingEmail != null) {
            // Only resume if it's actually a pending request in Firestore
            db.collection("user_access").document(pendingEmail).get().addOnSuccessListener { doc ->
                if (doc.exists() && doc.getString("status") == "pending") {
                    startPendingVerification(pendingEmail)
                }
            }
        }

        btnEnterCode.setOnClickListener {
            val intent = Intent(this, EnterCodeActivity::class.java)
            startActivity(intent)
        }

        checkOwnerExists(db)

        btnFarmSetup.setOnClickListener {
            val intent = Intent(this, FarmSetupActivity::class.java)
            startActivity(intent)
        }

        findViewById<ImageButton>(R.id.backButton)?.setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnCancelPending)?.setOnClickListener {
            cancelPendingRequest()
        }
    }

    private fun startPendingVerification(email: String) {
        registrationUI.visibility = View.GONE
        pendingLayout.visibility = View.VISIBLE
        pendingEmailTv.text = email

        val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
        pendingQuail.startAnimation(jump)

        // Listen for approval
        statusListener?.remove() // Ensure no double listeners
        statusListener = FirebaseFirestore.getInstance().collection("user_access")
            .document(email)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Toast.makeText(this, "Connection Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status") ?: ""
                    if (status == "approved") {
                        // Success! Cleanup UI but DON'T delete the document
                        statusListener?.remove()
                        pendingQuail.clearAnimation()

                        // Proceed to Verification Code screen automatically
                        val intent = Intent(this, EnterCodeActivity::class.java)
                        intent.putExtra("email", email)
                        startActivity(intent)
                        finish()
                    }
                } else if (snapshot != null && !snapshot.exists()) {
                    // Document was deleted (maybe rejected or cancelled elsewhere)
                    Toast.makeText(this, "Request was removed.", Toast.LENGTH_SHORT).show()
                    stopPendingUI()
                }
            }
    }

    private fun cancelPendingRequest() {
        val email = pendingEmailTv.text.toString()
        if (email.isNotEmpty()) {
            FirebaseFirestore.getInstance().collection("user_access").document(email).delete()
                .addOnSuccessListener {
                    Toast.makeText(this, "Request cancelled", Toast.LENGTH_SHORT).show()
                    accountManager.clearSession()
                    stopPendingUI()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error cancelling request: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            stopPendingUI()
        }
    }

    private fun stopPendingUI() {
        statusListener?.remove()
        pendingQuail.clearAnimation()
        pendingLayout.visibility = View.GONE
        registrationUI.visibility = View.VISIBLE
    }

    private fun checkOwnerExists(db: FirebaseFirestore) {
        db.collection("user_access")
            .whereEqualTo("role", "owner")
            .get()
            .addOnSuccessListener { documents ->
                btnFarmSetup.visibility = if (documents.isEmpty) View.VISIBLE else View.GONE
            }
    }

    override fun onDestroy() {
        statusListener?.remove()
        super.onDestroy()
    }
}