package com.bidzidriver.app.auth


import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment



import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

import android.provider.Settings

import android.util.Log

import android.widget.ProgressBar

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.bidzidriver.app.R

import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.Locale
import java.util.concurrent.TimeUnit


import com.google.firebase.BuildConfig


class DriverSignUpFragment : Fragment() {

    private lateinit var etMobileNumber: EditText
    private lateinit var btnGetOtp: Button
    private lateinit var tvTermsLink: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var auth: FirebaseAuth

    private val TAG = "DriverSignUpFragment"
    private var lastOtpRequestTime: Long = 0
    private val otpCooldownMs = 30_000L
    private var isSendingOtp = false
    private var forceRecaptcha = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            showSnackbar("SMS permissions are recommended for automatic OTP detection")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_driver_sign_up, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        auth = FirebaseAuth.getInstance()

        configureFirebaseAuth()
        requestSmsPermissions()

        forceRecaptcha = needsForceRecaptcha()
        if (forceRecaptcha) {
            Log.d(TAG, "Device requires reCAPTCHA: ${Build.MANUFACTURER} ${Build.MODEL}")
            showSnackbar("Using web-based verification for better compatibility")
        }

        setupListeners()
    }

    private fun initializeViews(view: View) {
        etMobileNumber = view.findViewById(R.id.etMobileNumber)
        btnGetOtp = view.findViewById(R.id.btnGetOtp)
        tvTermsLink = view.findViewById(R.id.tvTermsLink)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)
    }

    private fun configureFirebaseAuth() {
        auth.firebaseAuthSettings.setAppVerificationDisabledForTesting(false)
        auth.setLanguageCode(Locale.getDefault().language)
    }

    private fun requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.RECEIVE_SMS)
            }

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_SMS)
            }

            if (permissions.isNotEmpty()) {
                requestPermissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }

    private fun setupListeners() {
        // Mobile number text watcher
        etMobileNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateInput()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Get OTP button click
        btnGetOtp.setOnClickListener {
            if (isSendingOtp) {
                showSnackbar("OTP request in progress. Please wait.")
                return@setOnClickListener
            }

            val currentTime = System.currentTimeMillis()
            val remainingCooldown = otpCooldownMs - (currentTime - lastOtpRequestTime)
            if (remainingCooldown > 0) {
                showSnackbar("Please wait ${remainingCooldown / 1000} seconds before requesting another OTP.")
                return@setOnClickListener
            }

            handleGetOtp()
        }



        // Terms link click
        tvTermsLink.setOnClickListener {
            handleTermsClick()
        }
    }

    private fun validateInput(): Boolean {
        val phoneNumber = etMobileNumber.text.toString().trim()
        val isValid = phoneNumber.length == 10 && phoneNumber.all { it.isDigit() }

        btnGetOtp.isEnabled = isValid
        btnGetOtp.alpha = if (isValid) 1.0f else 0.5f

        return isValid
    }

    private fun handleGetOtp() {
        if (!validateInput()) {
            showSnackbar("Please enter a valid 10-digit mobile number")
            return
        }

        startOtpSendSequence()
    }

    private fun startOtpSendSequence() {
        val buttonText = if (BuildConfig.DEBUG) "Sending Test..."
        else if (forceRecaptcha) "Verifying..."
        else "Sending..."

        btnGetOtp.text = buttonText
        btnGetOtp.isEnabled = false
        loadingIndicator.visibility = View.VISIBLE
        isSendingOtp = true

        sendOtp()
    }

    private fun sendOtp() {
        val isDebugMode = BuildConfig.DEBUG
        val phoneNumber = if (isDebugMode) {
            "+919510522559"  // Test number for debug
        } else {
            val inputPhone = etMobileNumber.text.toString().trim()
            "+91$inputPhone"  // Indian country code
        }

        if (!isDebugMode && (phoneNumber.isEmpty() || phoneNumber.length < 6)) {
            etMobileNumber.error = "Enter a valid phone number"
            resetButtonState()
            return
        }

        lastOtpRequestTime = System.currentTimeMillis()
        startFirebasePhoneAuth(phoneNumber)
    }

    private fun startFirebasePhoneAuth(phoneNumber: String) {
        Log.d(
            TAG, """
            Phone Auth Started
            - Device: ${Build.MANUFACTURER} ${Build.MODEL}
            - Phone: ${phoneNumber.take(4)}***
            - Force reCAPTCHA: $forceRecaptcha
        """.trimIndent()
        )

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                Log.d(TAG, "onVerificationCompleted - ignored to enforce manual OTP")
            }

            override fun onVerificationFailed(e: FirebaseException) {
                loadingIndicator.visibility = View.GONE

                val errorText = when (e) {
                    is FirebaseTooManyRequestsException ->
                        "Too many attempts. Please wait and try again."

                    is FirebaseAuthInvalidCredentialsException ->
                        "Invalid phone number format. Please check and try again."

                    is FirebaseAuthMissingActivityForRecaptchaException ->
                        "reCAPTCHA required. Please update Google Play Services."

                    else -> {
                        Log.e(TAG, "Verification failed", e)
                        "Verification failed: ${e.message ?: "Unknown error"}"
                    }
                }

                showSnackbar(errorText)

                if (!forceRecaptcha && isProblematicDevice()) {
                    showRetryWithRecaptchaDialog(phoneNumber)
                } else {
                    resetButtonState()
                }
                isSendingOtp = false
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                loadingIndicator.visibility = View.GONE

                try {
                    // Navigate to OTP Verification Fragment
                    val action = DriverSignUpFragmentDirections
                        .actionCustomerSignUpToOtpVerification(
                            verificationId = verificationId,
                            phone = phoneNumber,
                            resendToken = token
                        )
                    findNavController().navigate(action)

                    val message = if (BuildConfig.DEBUG) {
                        "Enter test OTP: 123456"
                    } else if (forceRecaptcha) {
                        "OTP sent via web verification"
                    } else {
                        "OTP sent successfully"
                    }
                    showSnackbar(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Navigation failed", e)
                    showSnackbar("An error occurred. Please try again.")
                } finally {
                    resetButtonState()
                }
                isSendingOtp = false
            }
        }

        val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(120L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(callbacks)

        if (forceRecaptcha) {
            Log.d(TAG, "Using forceRecaptchaFlow")
            optionsBuilder.requireSmsValidation(false)
        }

        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
    }

    private fun showRetryWithRecaptchaDialog(phoneNumber: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Verification Issue")
            .setMessage("Would you like to retry with web-based verification?")
            .setPositiveButton("Retry") { _, _ ->
                forceRecaptcha = true
                startOtpSendSequence()
            }
            .setNeutralButton("Help") { _, _ ->
                showDeviceSettingsHelp()
            }
            .setNegativeButton("Cancel") { _, _ ->
                resetButtonState()
            }
            .setCancelable(false)
            .show()
    }

    private fun showDeviceSettingsHelp() {
        val helpMessage = """
            To receive OTP:
            
            1. Enable SMS permissions
            2. Disable battery optimization
            3. Update Google Play Services
            4. Update Android System WebView
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("Device Settings Guide")
            .setMessage(helpMessage)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", requireContext().packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    showSnackbar("Unable to open settings")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun resetButtonState() {
        val buttonText = when {
            BuildConfig.DEBUG -> "Send Test OTP"
            forceRecaptcha -> "Send OTP (Web)"
            else -> "Get OTP"
        }
        btnGetOtp.text = buttonText
        btnGetOtp.isEnabled = true
        loadingIndicator.visibility = View.GONE
        isSendingOtp = false
    }

    private fun handleCreateAccount() {
        showSnackbar("Navigate to Create Account")
        // TODO: Navigate to registration screen if needed
    }

    private fun handleTermsClick() {
        showSnackbar("Opening Terms & Conditions")
        // TODO: Open terms and conditions in WebView or browser
    }

    private fun needsForceRecaptcha(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        return manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco") ||
                model.contains("redmi") ||
                model.contains("poco")
    }

    private fun isProblematicDevice(): Boolean {
        return needsForceRecaptcha()
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_LONG).show()
        }
    }

    companion object {
        fun newInstance() = DriverSignUpFragment()
    }
}