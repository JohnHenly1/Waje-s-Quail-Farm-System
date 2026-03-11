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

class RegisterActivity : AppCompatActivity() {
    private lateinit var accountManager: AccountManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_register)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        accountManager = AccountManager(this)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        // Sign Up Button Click Listener - saves account and returns to login
        try {
            val signUpButton = findViewById<Button>(R.id.Login)
            signUpButton?.setOnClickListener {
                val usernameField = findViewById<EditText>(R.id.editTextUsername)
                val emailField = findViewById<EditText>(R.id.editTextTextEmailAddress)
                val passwordField = findViewById<EditText>(R.id.createpassword)
                val confirmPasswordField = findViewById<EditText>(R.id.confirmpassword)
                
                val username = usernameField?.text.toString().trim()
                val email = emailField?.text.toString().trim()
                val password = passwordField?.text.toString().trim()
                val confirmPassword = confirmPasswordField?.text.toString().trim()
                
                when {
                    username.isEmpty() -> {
                        usernameField?.error = "Please enter a username"
                        Toast.makeText(this, "Username is required", Toast.LENGTH_SHORT).show()
                    }
                    email.isEmpty() -> {
                        emailField?.error = "Please enter an email"
                        Toast.makeText(this, "Email is required", Toast.LENGTH_SHORT).show()
                    }
                    password.isEmpty() -> {
                        passwordField?.error = "Please enter a password"
                        Toast.makeText(this, "Password is required", Toast.LENGTH_SHORT).show()
                    }
                    password != confirmPassword -> {
                        confirmPasswordField?.error = "Passwords do not match"
                        Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show()
                    }
                    accountManager.accountExists(username) -> {
                        usernameField?.error = "Username already exists"
                        Toast.makeText(this, "Username already taken!", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Register the account
                        if (accountManager.registerAccount(username, email, password)) {
                            Toast.makeText(this, "Account created successfully! Please log in.", Toast.LENGTH_SHORT).show()
                            // Return to login
                            val intent = Intent(this, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            Toast.makeText(this, "Failed to create account", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Return to login Button Click Listener
        try {
            val returnButton = findViewById<Button>(R.id.user_return_login)
            returnButton?.setOnClickListener {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

