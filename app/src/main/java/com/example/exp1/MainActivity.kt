package com.example.exp1

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var accountManager: AccountManager
    private lateinit var googleSignInClient: GoogleSignInClient

    private var firestoreListener: ListenerRegistration? = null
    private lateinit var cm: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var loginUIContainer: View
    private lateinit var loadingLayout: View
    private lateinit var progressBar: ProgressBar
    private lateinit var percentageText: TextView
    private lateinit var statusText: TextView
    private lateinit var loadingIcon: View
    private lateinit var noInternetSection: View
    private lateinit var btnOffline: Button

    private val GOOGLE_SIGN_IN_REQUEST = 9001

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        accountManager = AccountManager(this)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val currentEmail = accountManager.getCurrentUsername()

        setContentView(R.layout.activity_login)

        // GOOGLE ACCOUNT PICKER SETUP
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("441160922275-03k03v695eo31hscn5dic9eh1o16hhka.apps.googleusercontent.com")
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val editLoginEmail = findViewById<EditText>(R.id.editLoginEmail)

        editLoginEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val email = s.toString().trim()
                if (email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    editLoginEmail.error = "Invalid email format"
                } else {
                    editLoginEmail.error = null
                }
            }
        })

        loginUIContainer = findViewById(R.id.loginUIContainer)
        loadingLayout = findViewById(R.id.loadingLayout)
        progressBar = findViewById(R.id.loadingProgressBar)
        percentageText = findViewById(R.id.loadingPercentageText)
        statusText = findViewById(R.id.loadingStatusText)
        loadingIcon = findViewById(R.id.loadingIcon)
        noInternetSection = findViewById(R.id.noInternetSection)
        btnOffline = findViewById(R.id.btnOfflineMode)

        loginUIContainer.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        noInternetSection.visibility = View.GONE

        loadingIcon.startAnimation(AnimationUtils.loadAnimation(this, R.anim.quail_jump))

        findViewById<View>(R.id.btnRetryConnection).setOnClickListener {
            if (isInternetActuallyWorking()) {
                noInternetSection.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                percentageText.visibility = View.VISIBLE
                startEntrySequence(currentEmail)
            } else {
                Toast.makeText(this, "Still no connection...", Toast.LENGTH_SHORT).show()
            }
        }

        btnOffline.setOnClickListener {
            if (currentEmail != null) {
                val role = accountManager.getRole(currentEmail)
                enterApp("User", currentEmail, role, "cached", false)
            }
        }

        registerNetworkSensor(currentEmail)

        // GOOGLE LOGIN BUTTON WITH MULTI-ACCOUNT PICKER
        findViewById<View>(R.id.Btn).setOnClickListener {
            // Force account picker by signing out first
            googleSignInClient.signOut().addOnCompleteListener {
                val intent = googleSignInClient.signInIntent
                startActivityForResult(intent, GOOGLE_SIGN_IN_REQUEST)
            }
        }

        findViewById<View>(R.id.btnManualLogin).setOnClickListener { handleManualLogin() }
        findViewById<View>(R.id.btnRegister).setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        findViewById<View>(R.id.btnForgotPassword).setOnClickListener { showForgotPasswordDialog() }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Google Login Result Handler
    // ─────────────────────────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == GOOGLE_SIGN_IN_REQUEST) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                val email = account.email ?: return

                val credential = com.google.firebase.auth.GoogleAuthProvider
                    .getCredential(account.idToken, null)

                com.google.firebase.auth.FirebaseAuth.getInstance()
                    .signInWithCredential(credential)
                    .addOnSuccessListener { checkFirestoreAccessWithPassword(email) }
                    .addOnFailureListener { checkFirestoreAccessWithPassword(email) }

            } catch (e: ApiException) {
                Toast.makeText(this, "Google sign-in cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Shared password check (Google + manual login both flow through here)
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkFirestoreAccessWithPassword(email: String) {
        loadingLayout.visibility = View.VISIBLE

        FirebaseFirestore.getInstance()
            .collection("user_access")
            .document(email)
            .get()
            .addOnSuccessListener { doc ->
                loadingLayout.visibility = View.GONE

                if (!doc.exists()) {
                    showNotRegisteredDialog()
                    return@addOnSuccessListener
                }

                val password = doc.getString("password") ?: ""
                val name = doc.getString("name") ?: email
                val role = doc.getString("role") ?: "staff"

                showPasswordDialog(email, password, name, role)
            }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Forgot Password — Step 1: ask for email
    // ─────────────────────────────────────────────────────────────────────────

    private fun showForgotPasswordDialog() {
        val currentEmailInput = findViewById<EditText>(R.id.editLoginEmail).text.toString().trim()

        val input = EditText(this)
        input.hint = getString(R.string.enter_email_hint)
        input.setText(currentEmailInput)
        input.setPadding(50, 40, 50, 40)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.forgot_password_label))
            .setMessage("Enter your registered email. We'll send you a 6-digit verification code.")
            .setView(input)
            .setPositiveButton("Send Code") { _, _ ->
                val email = input.text.toString().trim().lowercase()
                if (email.isNotEmpty()) {
                    showResetCodeConfirmationDialog(email)
                } else {
                    Toast.makeText(this, "Email cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forgot Password — Step 1b: confirm before sending the code
    // ─────────────────────────────────────────────────────────────────────────

    private fun showResetCodeConfirmationDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle("Send Verification Code?")
            .setMessage("A 6-digit reset code will be sent to:\n\n$email\n\nDo you want to proceed?")
            .setPositiveButton("Proceed") { _, _ -> checkEmailThenSendResetCode(email) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forgot Password — Step 2: verify email exists, generate & email the code
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkEmailThenSendResetCode(email: String) {
        loadingLayout.visibility = View.VISIBLE
        statusText.text = "Sending verification code..."

        FirebaseFirestore.getInstance()
            .collection("user_access")
            .document(email)
            .get()
            .addOnSuccessListener { doc ->
                loadingLayout.visibility = View.GONE

                if (!doc.exists()) {
                    Toast.makeText(this, getString(R.string.email_not_found), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // 6-digit code, valid for 15 minutes
                val code = "%06d".format(Random.nextInt(1000000))
                val expiresAt = System.currentTimeMillis() + (15 * 60 * 1000)

                sendResetCodeViaAppsScript(email, code)
                showVerificationCodeDialog(email, code, expiresAt)
            }
            .addOnFailureListener {
                loadingLayout.visibility = View.GONE
                Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forgot Password — send reset code email via Apps Script (background thread)
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendResetCodeViaAppsScript(email: String, code: String) {
        val scriptUrl = "https://script.google.com/macros/s/AKfycbxd3Jv_ysFbqaH0Rf5Qw8_Zxv6g2Sy2muDSkISnmPjxk2KMENJF7RA8ybXdQ5GYyMHF/exec"
        val secret    = "Red0455"

        Thread {
            try {
                val url  = URL(scriptUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput       = true
                conn.connectTimeout = 10000
                conn.readTimeout    = 10000

                val payload = JSONObject().apply {
                    put("secret", secret)
                    put("email",  email)
                    put("code",   code)
                    put("type",   "reset") // tells Apps Script to use the reset email template
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                val responseText = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                Handler(Looper.getMainLooper()).post {
                    if (responseCode != 200 || !responseText.contains("\"success\":true")) {
                        Toast.makeText(
                            this,
                            "Failed to send reset code. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forgot Password — Step 3: user enters the code they received
    // ─────────────────────────────────────────────────────────────────────────

    private fun showVerificationCodeDialog(email: String, expectedCode: String, expiresAt: Long) {
        val codeInput = EditText(this)
        codeInput.hint = "Enter 6-digit code"
        codeInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        codeInput.setPadding(50, 40, 50, 40)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Verify Your Email")
            .setMessage("A 6-digit code was sent to $email.\nIt expires in 15 minutes.")
            .setView(codeInput)
            .setPositiveButton("Verify", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = codeInput.text.toString().trim()

            if (System.currentTimeMillis() > expiresAt) {
                Toast.makeText(this, "Code has expired. Please request a new one.", Toast.LENGTH_LONG).show()
                dialog.dismiss()
                return@setOnClickListener
            }

            if (entered == expectedCode) {
                dialog.dismiss()
                showResetPasswordDialog(email)
            } else {
                codeInput.error = "Incorrect code"
                Toast.makeText(this, "Incorrect code. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forgot Password — Step 4: set the new password
    // ─────────────────────────────────────────────────────────────────────────

    private fun showResetPasswordDialog(email: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_entry, null)
        val editPassword = dialogView.findViewById<EditText>(R.id.editLoginPassword)
        val passwordInputLayout = dialogView.findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val tvEmail = dialogView.findViewById<TextView>(R.id.tvPasswordEmail)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPasswordDialogTitle)

        tvTitle?.text = getString(R.string.reset_password)
        tvEmail?.text = email
        editPassword.hint = getString(R.string.new_password_hint)

        editPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                passwordInputLayout.error = if (pass.isNotEmpty()) getPasswordStrengthError(pass) else null
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.update_password), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newPass = editPassword.text.toString().trim()
            val error = getPasswordStrengthError(newPass)
            if (error != null) {
                passwordInputLayout.error = error
                return@setOnClickListener
            }
            updatePasswordInFirestore(email, newPass, dialog)
        }
    }

    private fun updatePasswordInFirestore(email: String, newPass: String, dialog: AlertDialog) {
        loadingLayout.visibility = View.VISIBLE
        FirebaseFirestore.getInstance()
            .collection("user_access")
            .document(email)
            .update("password", newPass)
            .addOnSuccessListener {
                loadingLayout.visibility = View.GONE
                Toast.makeText(this, getString(R.string.password_updated_success), Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .addOnFailureListener {
                loadingLayout.visibility = View.GONE
                Toast.makeText(this, "Failed to update password: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Network
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerNetworkSensor(email: String?) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (noInternetSection.visibility == View.VISIBLE) {
                        noInternetSection.visibility = View.GONE
                        progressBar.visibility = View.VISIBLE
                        percentageText.visibility = View.VISIBLE
                        startEntrySequence(email)
                    }
                }
            }
            override fun onLost(network: Network) {
                runOnUiThread { showNoInternetUI(email != null) }
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)

        if (!isInternetActuallyWorking()) {
            showNoInternetUI(email != null)
        } else {
            startEntrySequence(email)
        }
    }

    private fun handleManualLogin() {
        if (!isInternetActuallyWorking()) {
            showNoInternetUI(false)
            return
        }

        val email = findViewById<EditText>(R.id.editLoginEmail).text.toString().trim().lowercase()
        if (email.isEmpty()) {
            Toast.makeText(this, "Enter valid email", Toast.LENGTH_SHORT).show()
            return
        }
        checkFirestoreAccessWithPassword(email)
    }

    private fun showNotRegisteredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Account Not Found")
            .setMessage("This email is not registered. Request access?")
            .setPositiveButton("Request Access") { _, _ -> startActivity(Intent(this, RegisterActivity::class.java)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasswordDialog(email: String, correctPass: String, name: String, role: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_entry, null)
        val editPassword = dialogView.findViewById<EditText>(R.id.editLoginPassword)
        val passwordInputLayout = dialogView.findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val tvEmail = dialogView.findViewById<TextView>(R.id.tvPasswordEmail)
        tvEmail?.text = email

        editPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                passwordInputLayout.error = if (pass.isNotEmpty()) getPasswordStrengthError(pass) else null
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Verify Identity")
            .setView(dialogView)
            .setPositiveButton("Verify & Login", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = editPassword.text.toString().trim()
            val formatError = getPasswordStrengthError(entered)

            if (formatError != null) {
                passwordInputLayout.error = formatError
                Toast.makeText(this, formatError, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (entered == correctPass) {
                enterApp(name, email, role, entered, true)
                dialog.dismiss()
            } else {
                passwordInputLayout.error = "Incorrect Password"
                Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun isInternetActuallyWorking(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showNoInternetUI(canGoOffline: Boolean) {
        handler.removeCallbacksAndMessages(null)
        loadingIcon.clearAnimation()
        statusText.text = "Connection Required"
        progressBar.visibility = View.GONE
        percentageText.visibility = View.GONE
        noInternetSection.visibility = View.VISIBLE
        btnOffline.visibility = if (canGoOffline) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun startEntrySequence(email: String?) {
        var progress = 0
        val progressHandler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (progress <= 100) {
                    progressBar.progress = progress
                    percentageText.text = "$progress%"

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

    @SuppressLint("SetTextI18n")
    private fun startLivePendingCheck(email: String) {
        firestoreListener?.remove()

        firestoreListener = FirebaseFirestore.getInstance()
            .collection("user_access")
            .document(email)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val status   = snapshot.getString("status") ?: ""
                    val role     = snapshot.getString("role") ?: "staff"
                    val name     = snapshot.getString("name") ?: email
                    val password = snapshot.getString("password") ?: ""

                    if (status == "approved") {
                        val setupDone = snapshot.getBoolean("setupCompleted") ?: false

                        if (setupDone) {
                            statusText.text = "Welcome, $name!"
                            handler.postDelayed({ enterApp(name, email, role, password, true) }, 600)
                        } else {
                            loadingLayout.visibility = View.GONE
                            loadingIcon.clearAnimation()
                            startActivity(Intent(this, EnterCodeActivity::class.java))
                            finish()
                        }
                    } else if (status == "pending") {
                        statusText.text = "Waiting for Farm Owner Approval..."
                    } else {
                        handleAccessDenied()
                    }
                } else {
                    handleAccessDenied()
                }
            }
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

    private fun enterApp(name: String, email: String, role: String, password: String, showToast: Boolean) {
        if (networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback!!) } catch (_: Exception) {}
        }

        firestoreListener?.remove()

        accountManager.registerAccount(email, email, password, role)
        accountManager.saveCurrentSession(email)

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            FirebaseFirestore.getInstance()
                .collection("sessions")
                .document(uid)
                .set(mapOf("email" to email, "role" to role, "status" to "approved"))
        }

        startActivity(Intent(this, DashboardActivity::class.java).putExtra("username", email))
        finish()
    }

    override fun onDestroy() {
        if (networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback!!) } catch (_: Exception) {}
        }
        firestoreListener?.remove()
        super.onDestroy()
    }
}