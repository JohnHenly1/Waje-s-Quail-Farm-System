package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class RegisterActivity : AppCompatActivity() {

    private lateinit var btnRequestCode: Button
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
        val pendingEmail = intent.getStringExtra("pendingEmail")
        if (pendingEmail != null) {
            startPendingVerification(pendingEmail)
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
            stopPendingListener()
        }
    }

    private fun startPendingVerification(email: String) {
        registrationUI.visibility = View.GONE
        pendingLayout.visibility = View.VISIBLE
        pendingEmailTv.text = email

        val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
        pendingQuail.startAnimation(jump)

        // Listen for approval
        statusListener = FirebaseFirestore.getInstance().collection("user_access")
            .document(email)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener

                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status") ?: ""
                    if (status == "approved") {
                        stopPendingListener()
                        // Proceed to Verification Code screen automatically
                        val intent = Intent(this, EnterCodeActivity::class.java)
                        intent.putExtra("email", email)
                        startActivity(intent)
                        finish()
                    }
                }
            }
    }

    private fun stopPendingListener() {
        statusListener?.remove()
        pendingQuail.clearAnimation()
        pendingLayout.visibility = View.GONE
        registrationUI.visibility = View.VISIBLE
        // Delete the pending request
        val email = pendingEmailTv.text.toString()
        if (!email.isEmpty()) {
            FirebaseFirestore.getInstance().collection("user_access").document(email).delete()
                .addOnSuccessListener {
                    Toast.makeText(this, "Request cancelled", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error cancelling request", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun updateAvailabilityInButtons(db: FirebaseFirestore, rbStaff: RadioButton, rbBackup: RadioButton) {
        db.collection("system_settings").document("role_limits").get()
            .addOnSuccessListener { limitDoc ->
                val staffLimit = limitDoc.getLong("staff_limit") ?: 5L
                val staffLimit2 = limitDoc.getLong("staff_limit") ?: 5L

                db.collection("user_access")
                    .whereEqualTo("role", "staff")
                    .whereEqualTo("status", "approved")
                    .get()
                    .addOnSuccessListener { docs ->
                        val available = (staffLimit - docs.size()).coerceAtLeast(0)
                        rbStaff.text = "Farm Staff ($available spots left)"
                        if (available <= 0) {
                            rbStaff.isEnabled = false
                            rbStaff.text = "Farm Staff (Full)"
                        }
                    }
            }
    }

    private fun checkRoleAvailability(db: FirebaseFirestore, role: String, callback: (Boolean) -> Unit) {
        db.collection("system_settings").document("role_limits").get()
            .addOnSuccessListener { limitDoc ->
                val limit = limitDoc.getLong("${role}_limit") ?: 5L
                db.collection("user_access")
                    .whereEqualTo("role", role)
                    .whereEqualTo("status", "approved")
                    .get()
                    .addOnSuccessListener { users ->
                        callback(users.size() < limit)
                    }
                    .addOnFailureListener { callback(true) }
            }
            .addOnFailureListener { callback(true) }
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