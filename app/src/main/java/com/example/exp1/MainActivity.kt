package com.example.exp1

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        accountManager = AccountManager(this)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        // Login Button Click Listener - goes to Dashboard
        try {
            val loginButton = findViewById<Button>(R.id.Login)
            loginButton?.setOnClickListener {
                val emailField = findViewById<EditText>(R.id.editTextTextEmailAddress)
                val passwordField = findViewById<EditText>(R.id.user_passwordInput)
                
                val email = emailField?.text.toString().trim()
                val password = passwordField?.text.toString().trim()
                
                when {
                    email.isEmpty() -> {
                        emailField?.error = "Please enter an email"
                        Toast.makeText(this, "Email is required", Toast.LENGTH_SHORT).show()
                    }
                    password.isEmpty() -> {
                        passwordField?.error = "Please enter a password"
                        Toast.makeText(this, "Password is required", Toast.LENGTH_SHORT).show()
                    }
                    !accountManager.accountExistsByEmail(email) -> {
                        Toast.makeText(this, "Account not found. Please register first!", Toast.LENGTH_SHORT).show()
                    }
                    !accountManager.validateLoginByEmail(email, password) -> {
                        Toast.makeText(this, "Invalid password!", Toast.LENGTH_SHORT).show()
                        passwordField?.text?.clear()
                    }
                    else -> {
                        // Login successful - get username associated with email
                        val username = accountManager.getUsernameByEmail(email) ?: "User"
                        val intent = Intent(this, DashboardActivity::class.java)
                        intent.putExtra("username", username)
                        startActivity(intent)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sign Up Button Click Listener - goes to Register
        try {
            val signUpButton = findViewById<Button>(R.id.user_return_login)
            signUpButton?.setOnClickListener {
                val intent = Intent(this, RegisterActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}