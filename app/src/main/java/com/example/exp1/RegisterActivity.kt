package com.example.exp1

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore


class RegisterActivity : AppCompatActivity() {

    lateinit var editTextEmail: EditText
    lateinit var btnInvite: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        editTextEmail = findViewById(R.id.user_emailAddress)
        btnInvite = findViewById(R.id.Registerbtn)

        val db = FirebaseFirestore.getInstance()

        btnInvite.setOnClickListener {

            val email = editTextEmail.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "Enter email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            //  These are the users that are invited to the app and saved by the holy fire of firestore
            val userMap = hashMapOf(
                "email" to email,
                "approved" to true
            )

            db.collection("invited_users")
                .document(email)
                .set(userMap)
                .addOnSuccessListener {

                    Toast.makeText(this, "User invited!", Toast.LENGTH_SHORT).show()

                    // Send Email Invite
                    sendInviteEmail(email)

                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to invite user", Toast.LENGTH_SHORT).show()
                }
        }
    }

    // Email inv func, but it's not auto need to manually sent
    private fun sendInviteEmail(email: String) {

        val subject = "You're invited to Quail Farm System"
        val message = """
            Hello,
            
            You have been invited to access the Quail Farm Monitoring System.
            
            Please download the app and sign in using your Google account:
            $email
            
            Thank you!
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:") // only email apps
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, message)
        }

        startActivity(Intent.createChooser(intent, "Send Invite Email"))
    }
}