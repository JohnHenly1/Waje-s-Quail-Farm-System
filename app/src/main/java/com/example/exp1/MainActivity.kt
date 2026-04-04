package com.example.exp1

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var accountManager: AccountManager
    private var firestoreListener: ListenerRegistration? = null

    private val handler = Handler(Looper.getMainLooper())
    private val RC_SIGN_IN = 100

    private lateinit var loginUIContainer: View
    private lateinit var loadingLayout: View
    private lateinit var progressBar: ProgressBar
    private lateinit var percentageText: TextView
    private lateinit var statusText: TextView
    private lateinit var loadingIcon: View
    private lateinit var noInternetSection: View
    private lateinit var btnRetry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        accountManager = AccountManager(this)
        val currentEmail = accountManager.getCurrentUsername() 

        setContentView(R.layout.activity_login)

        loginUIContainer = findViewById(R.id.loginUIContainer)
        loadingLayout = findViewById(R.id.loadingLayout)
        progressBar = findViewById(R.id.loadingProgressBar)
        percentageText = findViewById(R.id.loadingPercentageText)
        statusText = findViewById(R.id.loadingStatusText)
        loadingIcon = findViewById(R.id.loadingIcon)
        noInternetSection = findViewById(R.id.noInternetSection)
        btnRetry = findViewById(R.id.btnRetryConnection)

        loginUIContainer.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        noInternetSection.visibility = View.GONE
        
        val jump = AnimationUtils.loadAnimation(this, R.anim.quail_jump)
        loadingIcon.startAnimation(jump)

        btnRetry.setOnClickListener {
            checkConnectionAndProceed(currentEmail)
        }

        checkConnectionAndProceed(currentEmail)

        auth = FirebaseAuth.getInstance()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        findViewById<View>(R.id.Btn).setOnClickListener {
            if (!isNetworkAvailable()) {
                showNoInternetUI()
                return@setOnClickListener
            }
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, RC_SIGN_IN)
            }
        }

        findViewById<View>(R.id.btnManualLogin).setOnClickListener {
            handleManualLogin()
        }

        findViewById<View>(R.id.btnRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun checkConnectionAndProceed(email: String?) {
        if (isNetworkAvailable()) {
            noInternetSection.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            percentageText.visibility = View.VISIBLE
            
            if (email != null) {
                statusText.text = "Restoring Session..."
                startEntrySequence(email)
            } else {
                statusText.text = "Preparing Farm..."
                startEntrySequence(null)
            }
        } else {
            showNoInternetUI()
        }
    }

    private fun showNoInternetUI() {
        statusText.text = "Connection Required"
        progressBar.visibility = View.GONE
        percentageText.visibility = View.GONE
        noInternetSection.visibility = View.VISIBLE
        loginUIContainer.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
    }

    private fun startEntrySequence(email: String?) {
        var progress = 0
        val progressHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (progress <= 100) {
                    progressBar.progress = progress
                    percentageText.text = "${progress}%"
                    
                    if (progress == 40 && email != null) {
                        statusText.text = "Syncing with Cloud..."
                        startLivePendingCheck(email)
                    }
                    
                    progress += 4
                    progressHandler.postDelayed(this, 30)
                } else {
                    if (email == null) {
                        loadingLayout.visibility = View.GONE
                        loadingIcon.clearAnimation()
                        loginUIContainer.visibility = View.VISIBLE
                    }
                }
            }
        }
        progressHandler.post(runnable)
    }

    private fun startLivePendingCheck(email: String) {
        if (!isNetworkAvailable()) {
            showNoInternetUI()
            return
        }

        firestoreListener?.remove()
        firestoreListener = FirebaseFirestore.getInstance().collection("user_access")
            .document(email)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    showLoginUI()
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status") ?: ""
                    val role = snapshot.getString("role") ?: "staff"
                    val name = snapshot.getString("name") ?: email

                    if (status == "approved") {
                        val setupDone = snapshot.getBoolean("setupCompleted") ?: false
                        if (setupDone) {
                            statusText.text = "Welcome, $name!"
                            handler.postDelayed({ enterApp(name, email, role, false) }, 600)
                        } else {
                            loadingLayout.visibility = View.GONE
                            loadingIcon.clearAnimation()
                            startActivity(Intent(this, EnterCodeActivity::class.java))
                            finish()
                        }
                    } else if (status == "pending") {
                        statusText.text = "Waiting for Owner Approval..."
                    } else {
                        handleAccessDenied()
                    }
                } else {
                    showLoginUI()
                }
            }
    }

    private fun showLoginUI() {
        loadingLayout.visibility = View.GONE
        loadingIcon.clearAnimation()
        loginUIContainer.visibility = View.VISIBLE
    }

    private fun handleManualLogin() {
        if (!isNetworkAvailable()) {
            showNoInternetUI()
            return
        }

        val email = findViewById<EditText>(R.id.editLoginEmail).text.toString().trim().lowercase()
        if (email.isEmpty()) return
        
        statusText.text = "Finding Account..."
        loadingLayout.visibility = View.VISIBLE
        loadingIcon.startAnimation(AnimationUtils.loadAnimation(this, R.anim.quail_jump))

        FirebaseFirestore.getInstance().collection("user_access").document(email).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val savedPassword = doc.getString("password") ?: ""
                    loadingLayout.visibility = View.GONE
                    showPasswordDialog(email, savedPassword, doc.getString("name") ?: "User", doc.getString("role") ?: "staff")
                } else {
                    loadingLayout.visibility = View.GONE
                    showNotRegisteredDialog()
                }
            }
    }

    private fun showPasswordDialog(email: String, correctPass: String, name: String, role: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_entry, null)
        val tvEmail = dialogView.findViewById<TextView>(R.id.tvPasswordEmail)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.editLoginPassword)
        
        tvEmail.text = email

        AlertDialog.Builder(this)
            .setTitle("Verify Identity")
            .setMessage("Verify password for $email")
            .setView(dialogView)
            .setPositiveButton("Verify & Login") { _, _ ->
                val entered = editPassword.text.toString().trim()
                if (entered == correctPass) {
                    statusText.text = "Identity Verified!"
                    loadingLayout.visibility = View.VISIBLE
                    handler.postDelayed({ enterApp(name, email, role, true) }, 800)
                } else {
                    Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNotRegisteredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Account Not Found")
            .setMessage("This email is not registered. Would you like to request access?")
            .setPositiveButton("Request Access") { _, _ ->
                startActivity(Intent(this, RegisterActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false).show()
    }

    private fun handleAccessDenied() {
        Toast.makeText(this, "Access Denied or Account Removed.", Toast.LENGTH_LONG).show()
        stopCheckingAndClear()
    }

    private fun stopCheckingAndClear() {
        firestoreListener?.remove()
        accountManager.clearSession()
        recreate()
    }

    private fun enterApp(name: String, email: String, role: String, showToast: Boolean) {
        firestoreListener?.remove()
        accountManager.registerAccount(email, email, "verified", role)
        accountManager.saveCurrentSession(email)
        startActivity(Intent(this, DashboardActivity::class.java).putExtra("username", email))
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java) ?: return
                val email = account.email?.lowercase() ?: ""
                
                statusText.text = "Authenticating..."
                loadingLayout.visibility = View.VISIBLE

                FirebaseFirestore.getInstance().collection("user_access").document(email).get()
                    .addOnSuccessListener { doc ->
                        if (doc.exists()) {
                            val savedPassword = doc.getString("password") ?: ""
                            loadingLayout.visibility = View.GONE
                            showPasswordDialog(email, savedPassword, doc.getString("name") ?: "User", doc.getString("role") ?: "staff")
                        } else {
                            loadingLayout.visibility = View.GONE
                            showNotRegisteredDialog()
                            googleSignInClient.signOut()
                        }
                    }
            } catch (e: Exception) {
                Log.e("LOGIN_ERROR", e.message ?: "Error")
            }
        }
    }

    override fun onDestroy() {
        firestoreListener?.remove()
        super.onDestroy()
    }
}
