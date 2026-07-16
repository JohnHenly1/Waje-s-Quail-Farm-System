package com.example.exp1

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.*

class SetupAccountActivity : AppCompatActivity() {

    private lateinit var editName: EditText
    private lateinit var editBirthday: EditText
    private lateinit var editAddressStreet: EditText
    private lateinit var editAddressCity: EditText
    private lateinit var editAddressState: EditText
    private lateinit var editAddressPostal: EditText
    private lateinit var editPassword: EditText
    private lateinit var editPasswordLayout: TextInputLayout
    private lateinit var editPasswordConfirm: EditText
    private lateinit var editPasswordConfirmLayout: TextInputLayout
    private lateinit var imgProfile: ImageView
    private lateinit var btnComplete: Button
    private var prefilledInfoText: TextView? = null

    private var email: String = ""
    private var role: String = ""
    private var photoUrl: String = ""
    private val calendar = Calendar.getInstance()

    // True once we've confirmed an owner-created record already exists for
    // this email (status == "invited"). In that case name/birthday/address
    // came from the owner and are locked — the user only sets a password.
    private var infoWasPrefilledByOwner: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_account)

        // Ensure email is lowercased and trimmed for consistency
        email = intent.getStringExtra("email")?.trim()?.lowercase() ?: ""
        role = intent.getStringExtra("role") ?: "staff"
        val googleName = intent.getStringExtra("name") ?: ""
        photoUrl = intent.getStringExtra("photoUrl") ?: ""

        if (email.isEmpty()) {
            Toast.makeText(this, "Critical Error: No email provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        editName = findViewById(R.id.editSetupName)
        editBirthday = findViewById(R.id.editSetupBirthday)
        editAddressStreet = findViewById(R.id.editAddressStreet)
        editAddressCity = findViewById(R.id.editAddressCity)
        editAddressState = findViewById(R.id.editAddressState)
        editAddressPostal = findViewById(R.id.editAddressPostal)
        editPassword = findViewById(R.id.editSetupPassword)
        editPasswordLayout = findViewById(R.id.setupPasswordInputLayout)
        editPasswordConfirm = findViewById(R.id.editSetupPasswordConfirm)
        editPasswordConfirmLayout = findViewById(R.id.setupPasswordConfirmInputLayout)
        imgProfile = findViewById(R.id.imgSetupProfile)
        btnComplete = findViewById(R.id.btnCompleteSetup)

        // Pre-fill from Google Account as a fallback; may be overwritten
        // below once we check for an owner-entered record.
        editName.setText(googleName)
        if (photoUrl.isNotEmpty()) {
            Glide.with(this).load(photoUrl).circleCrop().into(imgProfile)
        }

        // Calendar Picker for Birthday
        editBirthday.setOnClickListener {
            showDatePicker()
        }

        // Live validation: letters only, no numbers or stray symbols
        editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                editName.error = getNameValidationError(s.toString())
            }
        })

        // Auto-detect password format
        editPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, before: Int, start: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                if (pass.isNotEmpty()) {
                    val error = getPasswordStrengthError(pass)
                    editPasswordLayout.error = error
                } else {
                    editPasswordLayout.error = null
                }
                // if confirm has value, validate match live
                val conf = editPasswordConfirm.text.toString()
                if (conf.isNotEmpty()) {
                    if (pass != conf) {
                        editPasswordConfirmLayout.error = "Passwords do not match"
                    } else {
                        editPasswordConfirmLayout.error = null
                    }
                }
            }
        })

        // Live validation for confirm password
        editPasswordConfirm.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val conf = s.toString()
                val pass = editPassword.text.toString()
                if (conf.isNotEmpty()) {
                    if (pass != conf) {
                        editPasswordConfirmLayout.error = "Passwords do not match"
                    } else {
                        editPasswordConfirmLayout.error = null
                    }
                } else {
                    editPasswordConfirmLayout.error = null
                }
            }
        })

        btnComplete.setOnClickListener {
            val name = editName.text.toString().trim()
            val bday = editBirthday.text.toString().trim()
            val street = editAddressStreet.text.toString().trim()
            val city = editAddressCity.text.toString().trim()
            val state = editAddressState.text.toString().trim()
            val postal = editAddressPostal.text.toString().trim()
            val pass = editPassword.text.toString().trim()
            val passConfirm = editPasswordConfirm.text.toString().trim()

            if (name.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Name and Password are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Full name must contain letters only (no digits or stray symbols).
            // Skipped when the owner already supplied a validated name, since
            // the field is locked and can't have been edited.
            if (!infoWasPrefilledByOwner) {
                val nameError = getNameValidationError(name)
                if (nameError != null) {
                    editName.error = nameError
                    Toast.makeText(this, nameError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Safety net: enforce 18+ even if the birthday text was set some
            // other way than the date picker (the picker itself already
            // blocks selecting a date that fails this).
            if (bday.isNotEmpty() && !isAtLeast18(bday)) {
                Toast.makeText(this, "You must be at least 18 years old", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate address if any component is provided (only relevant
            // when the user is filling it in themselves; owner-provided
            // addresses were already validated at Add User time).
            if (!infoWasPrefilledByOwner &&
                (street.isNotEmpty() || city.isNotEmpty() || state.isNotEmpty() || postal.isNotEmpty())
            ) {
                if (street.isEmpty() || city.isEmpty() || state.isEmpty() || postal.isEmpty()) {
                    Toast.makeText(this, "Please fill all address fields or leave them all empty", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val addressError = validateAddress(street, city, state, postal)
                if (addressError != null) {
                    Toast.makeText(this, addressError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Strong Password Restriction — always required, this is the
            // one thing the user always sets themselves.
            val passwordError = getPasswordStrengthError(pass)
            if (passwordError != null) {
                editPasswordLayout.error = passwordError
                Toast.makeText(this, passwordError, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Confirm password matches
            if (pass != passConfirm) {
                editPasswordConfirmLayout.error = "Passwords do not match"
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val addressMap = if (street.isNotEmpty()) {
                mapOf(
                    "street" to street,
                    "city" to city,
                    "state" to state,
                    "postalCode" to postal
                )
            } else {
                null
            }

            val userData = mutableMapOf<String, Any>(
                "name" to name,
                "email" to email,
                "birthday" to bday,
                "password" to pass,
                "role" to role,
                "profilePic" to photoUrl,
                "status" to "approved",
                "setupCompleted" to true,
                "verifiedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            if (addressMap != null) {
                userData["address"] = addressMap
            }

            btnComplete.isEnabled = false
            btnComplete.text = "Saving..."

            // ── Auth guard ──────────────────────────────────────────────
            // signInAnonymously() in WajeApplication is async. If this screen
            // is reached before it completes, request.auth is still null and
            // Firestore rejects the write with PERMISSION_DENIED. Re-trigger
            // sign-in here and only run the write once auth is confirmed
            // (same pattern as ScheduleActivity.ensureAuthThenRun).
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser != null) {
                saveUserAccess(email, userData)
            } else {
                auth.signInAnonymously()
                    .addOnSuccessListener { saveUserAccess(email, userData) }
                    .addOnFailureListener { e ->
                        btnComplete.isEnabled = true
                        btnComplete.text = "Complete Setup"
                        Toast.makeText(this, "Auth error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }

        loadOwnerPrefilledInfoIfAny()
    }

    /**
     * Checks whether the owner already created a record for this email via
     * "Add User" (status == "invited"). If so, pulls the name/birthday/address
     * they entered, fills the fields in, and locks them so the invited person
     * can't change what the owner set — they only pick a password.
     */
    private fun loadOwnerPrefilledInfoIfAny() {
        FirebaseFirestore.getInstance().collection("user_access").document(email).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                val status = doc.getString("status")
                if (status != "invited") return@addOnSuccessListener // already approved, or unknown state — leave fields editable

                val ownerName = doc.getString("name")
                val ownerBirthday = doc.getString("birthday")
                @Suppress("UNCHECKED_CAST")
                val ownerAddress = doc.get("address") as? Map<String, Any>

                if (!ownerName.isNullOrEmpty()) {
                    editName.setText(ownerName)
                    editName.isEnabled = false
                }
                if (!ownerBirthday.isNullOrEmpty()) {
                    editBirthday.setText(ownerBirthday)
                    editBirthday.isEnabled = false
                    editBirthday.isClickable = false
                }
                if (ownerAddress != null) {
                    editAddressStreet.setText(ownerAddress["street"]?.toString() ?: "")
                    editAddressCity.setText(ownerAddress["city"]?.toString() ?: "")
                    editAddressState.setText(ownerAddress["state"]?.toString() ?: "")
                    editAddressPostal.setText(ownerAddress["postalCode"]?.toString() ?: "")
                    editAddressStreet.isEnabled = false
                    editAddressCity.isEnabled = false
                    editAddressState.isEnabled = false
                    editAddressPostal.isEnabled = false
                }

                infoWasPrefilledByOwner = !ownerName.isNullOrEmpty()
                if (infoWasPrefilledByOwner) {
                    Toast.makeText(
                        this,
                        "Your info was added by your farm owner. Just set a password to finish.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener {
                // Non-fatal: fall back to the fully manual flow the person can still fill in themselves.
            }
    }

    /**
     * Performs the actual user_access write. Extracted so it can run either
     * immediately (already authed) or after signInAnonymously() succeeds.
     */
    private fun saveUserAccess(email: String, userData: Map<String, Any>) {
        val db = FirebaseFirestore.getInstance()
        db.collection("user_access").document(email).set(userData, SetOptions.merge())
            .addOnSuccessListener {
                val accountManager = AccountManager(this)
                // Use email as the key to remain consistent with MainActivity
                accountManager.registerAccount(email, email, userData["password"] as String, role)
                accountManager.saveCurrentSession(email)

                Toast.makeText(this, "Profile Setup Complete!", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, DashboardActivity::class.java)
                intent.putExtra("username", email)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                btnComplete.isEnabled = true
                btnComplete.text = "Complete Setup"
                Toast.makeText(this, "Error saving data: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Full names may contain letters, spaces, commas, periods, underscores,
     * hyphens, and apostrophes (covers names like "Mary-Jane", "O'Brien",
     * "J. Smith", "Smith, Jr.", or "first_last"). Digits and other symbols are not allowed.
     * Returns null when the name is valid or empty.
     */
    private fun getNameValidationError(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        // allow letters, spaces, comma (,), period (.), underscore (_), hyphen (-), apostrophe (')
        val nameRegex = Regex("^[A-Za-z._,\\s'-]+$")
        if (!nameRegex.matches(trimmed)) {
            return "Name may contain letters, spaces, commas, periods, underscores, hyphens and apostrophes (no digits or other symbols)"
        }
        return null
    }

    private fun validateAddress(street: String, city: String, state: String, postal: String): String? {
        if (street.length < 5) return "Street address must be at least 5 characters"
        if (city.length < 2) return "City must be at least 2 characters"
        if (state.length < 2) return "State/Province must be at least 2 characters"
        if (postal.length < 3) return "Postal code must be at least 3 characters"

        val invalidChars = "!@#$%^&*()=[]{}|;':\",<>?"
        if (street.any { it in invalidChars } || city.any { it in invalidChars } ||
            state.any { it in invalidChars } || postal.any { it in invalidChars }) {
            return "Address contains invalid characters"
        }

        return null
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

    private fun showDatePicker() {
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            val format = "MM/dd/yyyy"
            val sdf = SimpleDateFormat(format, Locale.US)
            editBirthday.setText(sdf.format(calendar.time))
        }

        // Birthday must yield an age of at least 18, so cap the picker at
        // "today minus 18 years" and open it there by default — no later
        // date can be selected.
        val maxBirthday = Calendar.getInstance().apply { add(Calendar.YEAR, -18) }

        val dialog = DatePickerDialog(
            this,
            dateSetListener,
            maxBirthday.get(Calendar.YEAR),
            maxBirthday.get(Calendar.MONTH),
            maxBirthday.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = maxBirthday.timeInMillis
        dialog.show()
    }

    /**
     * Safety net in case the birthday field is ever edited some other way
     * than the date picker (e.g. direct text input). Returns true only
     * when the parsed date makes the person 18 or older today.
     */
    private fun isAtLeast18(bday: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            sdf.isLenient = false
            val birthDate = sdf.parse(bday) ?: return false

            val birthCal = Calendar.getInstance().apply { time = birthDate }
            val today = Calendar.getInstance()

            var age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
            if (today.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) {
                age--
            }
            age >= 18
        } catch (e: Exception) {
            false
        }
    }
}