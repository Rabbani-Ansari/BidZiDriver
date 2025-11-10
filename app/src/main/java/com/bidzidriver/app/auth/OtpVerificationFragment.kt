package com.bidzidriver.app.auth

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup



import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build

import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent

import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope

import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs

import com.bidzidriver.app.MainActivity
import com.bidzidriver.app.R


import com.google.android.material.snackbar.Snackbar
import com.google.firebase.BuildConfig
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.messaging.FirebaseMessaging

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class OtpVerificationFragment : Fragment() {

    private lateinit var ivBack: ImageView
    private lateinit var tvPhoneNumber: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvResendCode: TextView
    private lateinit var tvContactSupport: TextView
    private lateinit var btnVerify: Button
    private lateinit var loadingIndicator: ProgressBar

    private lateinit var etOtp1: EditText
    private lateinit var etOtp2: EditText
    private lateinit var etOtp3: EditText
    private lateinit var etOtp4: EditText
    private lateinit var etOtp5: EditText
    private lateinit var etOtp6: EditText

    private lateinit var auth: FirebaseAuth
    private var verificationId: String? = null
    private var phone: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var countDownTimer: CountDownTimer? = null

    private val TAG = "OtpVerificationFragment"
    private val RESEND_TIMEOUT = 60_000L
    private var timeLeftInSeconds = 60
    private var isResending = false
    private var lastResendTime: Long = 0
    private val resendCooldownMs = 30_000L
    private var forceRecaptcha = false

    private lateinit var otpInputs: List<EditText>
    private val args: OtpVerificationFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_otp_verification, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        forceRecaptcha = needsForceRecaptcha()

        if (forceRecaptcha) {
            Log.d(TAG, "Device requires reCAPTCHA: ${Build.MANUFACTURER} ${Build.MODEL}")
        }

        verificationId = args.verificationId
        phone = args.phone
        resendToken = args.resendToken

        initializeViews(view)
        setupOtpInputs()
        setupListeners()
        startCountdown()
        setupOtpPaste()

        tvPhoneNumber.text = phone ?: ""
    }

    private fun initializeViews(view: View) {
        ivBack = view.findViewById(R.id.ivBack)
        tvPhoneNumber = view.findViewById(R.id.tvPhoneNumber)
        tvTimer = view.findViewById(R.id.tvTimer)
        tvResendCode = view.findViewById(R.id.tvResendCode)
        tvContactSupport = view.findViewById(R.id.tvContactSupport)
        btnVerify = view.findViewById(R.id.btnVerify)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)

        etOtp1 = view.findViewById(R.id.etOtp1)
        etOtp2 = view.findViewById(R.id.etOtp2)
        etOtp3 = view.findViewById(R.id.etOtp3)
        etOtp4 = view.findViewById(R.id.etOtp4)
        etOtp5 = view.findViewById(R.id.etOtp5)
        etOtp6 = view.findViewById(R.id.etOtp6)

        otpInputs = listOf(etOtp1, etOtp2, etOtp3, etOtp4, etOtp5, etOtp6)
    }

    private fun setupOtpInputs() {
        otpInputs.forEachIndexed { index, editText ->
            editText.isEnabled = true
            editText.filters = emptyArray()

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s.toString()

                    if (text.length > 1) {
                        handlePastedOtp(text)
                        return
                    }

                    if (editText.filters.isEmpty()) {
                        editText.filters = arrayOf(InputFilter.LengthFilter(1))
                    }

                    if (text.length == 1 && index < otpInputs.size - 1) {
                        otpInputs[index + 1].requestFocus()
                    } else if (text.isEmpty() && index > 0 && !isResending) {
                        otpInputs[index - 1].requestFocus()
                    }

                    validateOtp()
                }
            })

            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && index > 0) {
                        otpInputs[index - 1].requestFocus()
                        otpInputs[index - 1].setText("")
                    } else {
                        editText.setText("")
                    }
                    true
                } else {
                    false
                }
            }
        }

        otpInputs[0].requestFocus()
        showKeyboard(otpInputs[0])
    }

    private fun setupOtpPaste() {
        otpInputs.forEach { editText ->
            editText.setOnLongClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip

                if (clipData != null && clipData.itemCount > 0) {
                    val pastedText = clipData.getItemAt(0).text.toString()
                    handlePastedOtp(pastedText)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun handlePastedOtp(pastedText: String) {
        val digits = pastedText.filter { it.isDigit() }

        if (digits.length >= 6) {
            otpInputs.forEach {
                it.setText("")
                it.filters = emptyArray()
            }

            digits.take(6).forEachIndexed { index, digit ->
                otpInputs[index].setText(digit.toString())
            }

            otpInputs.forEach {
                it.filters = arrayOf(InputFilter.LengthFilter(1))
            }

            otpInputs[5].requestFocus()
            hideKeyboard()
            showSnackbar("OTP pasted successfully")
        } else if (digits.isNotEmpty()) {
            showSnackbar("Invalid OTP format. Please enter 6 digits.")
        }
    }

    private fun setupListeners() {
        ivBack.setOnClickListener {
            navigateBack()
        }

        btnVerify.setOnClickListener {
            val otp = getOtpCode()
            if (otp.length == 6 && verificationId != null) {
                hideKeyboard()
                showLoading(true)
                val credential = PhoneAuthProvider.getCredential(verificationId!!, otp)
                signInWithPhoneAuthCredential(credential)
            } else {
                showSnackbar("Please enter a valid 6-digit OTP")
            }
        }

        tvResendCode.setOnClickListener {
            if (isResending || resendToken == null) return@setOnClickListener

            val currentTime = System.currentTimeMillis()
            val remainingCooldown = resendCooldownMs - (currentTime - lastResendTime)
            if (remainingCooldown > 0) {
                showSnackbar("Please wait ${remainingCooldown / 1000} seconds")
                return@setOnClickListener
            }

            resendOtp()
        }

        tvContactSupport.setOnClickListener {
            showSnackbar("Opening support...")
            // TODO: Open support screen or contact options
        }
    }

    private fun validateOtp(): Boolean {
        val allFilled = otpInputs.all { it.text.length == 1 }
        btnVerify.isEnabled = allFilled && !isResending
        btnVerify.alpha = if (allFilled && !isResending) 1.0f else 0.5f
        return allFilled
    }

    private fun getOtpCode(): String {
        return otpInputs.joinToString("") { it.text.toString() }
    }

    private fun resendOtp() {
        if (resendToken == null || phone == null) {
            showSnackbar("Cannot resend code")
            return
        }

        isResending = true
        lastResendTime = System.currentTimeMillis()
        clearOtpFields()
        showLoading(true)

        Log.d(TAG, "Resending OTP: ${phone?.take(4)}***, Force reCAPTCHA: $forceRecaptcha")

        val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone!!)
            .setTimeout(120L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    Log.d(TAG, "onVerificationCompleted ignored in resend")
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    showLoading(false)

                    val errorMsg = when (e) {
                        is FirebaseAuthInvalidCredentialsException -> "Invalid phone number"
                        is FirebaseTooManyRequestsException -> "Too many attempts. Please wait."
                        is FirebaseAuthMissingActivityForRecaptchaException -> "reCAPTCHA required"
                        else -> "Resend failed: ${e.message ?: "Unknown error"}"
                    }

                    showSnackbar(errorMsg)
                    isResending = false
                    Log.e(TAG, "Resend failed: ${e.message}", e)
                }

                override fun onCodeSent(
                    newVerificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    showLoading(false)
                    verificationId = newVerificationId
                    resendToken = token

                    val message = if (BuildConfig.DEBUG) {
                        "Enter test OTP: 123456"
                    } else if (forceRecaptcha) {
                        "Code resent via web verification"
                    } else {
                        "Code resent successfully"
                    }
                    showSnackbar(message)
                    startCountdown()
                    isResending = false
                }
            })
            .setForceResendingToken(resendToken!!)

        if (forceRecaptcha) {
            optionsBuilder.requireSmsValidation(false)
        }

        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
    }

    private fun clearOtpFields() {
        otpInputs.forEach {
            it.setText("")
            it.isEnabled = true
        }
        otpInputs[0].requestFocus()
        showKeyboard(otpInputs[0])
    }

    private fun signInWithPhoneAuthCredential(credential: PhoneAuthCredential) {
        showLoading(true)

        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        Log.d(TAG, "Phone verification successful for UID: ${user.uid}")
                        lifecycleScope.launch {
                            handleSuccessfulSignIn(user)
                        }
                    } else {
                        showSnackbar("Verification failed: No user data")
                        showLoading(false)
                    }
                } else {
                    showLoading(false)
                    Log.e(TAG, "Verification failed: ${task.exception?.message}", task.exception)

                    if (task.exception?.message?.contains("sms code has expired") == true && !BuildConfig.DEBUG) {
                        showSnackbar("OTP expired. Resending a new code...")
                        resendOtp()
                    } else {
                        showSnackbar("Verification failed: ${task.exception?.message ?: "Unknown error"}")
                    }
                }
            }
    }

    private suspend fun handleSuccessfulSignIn(firebaseUser: FirebaseUser) {
        try {
            // Get FCM token
            val fcmToken = getFcmTokenWithRetry()
            Log.d(TAG, "FCM Token obtained: ${fcmToken.take(10)}...")

            // TODO: Save user data to your database
            // For now, just navigate to success screen

            showSnackbar("Sign-in successful!")
            navigateToHome()

        } catch (e: Exception) {
            Log.e(TAG, "Sign-in error: ${e.message}", e)
            showSnackbar("Error: ${e.message}")
            showLoading(false)
        }
    }

    private suspend fun getFcmTokenWithRetry(maxRetries: Int = 3): String {
        repeat(maxRetries) { attempt ->
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                if (!token.isNullOrEmpty()) {
                    Log.d(TAG, "FCM token retrieved on attempt ${attempt + 1}")
                    return token
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM token fetch attempt ${attempt + 1} failed", e)
                if (attempt < maxRetries - 1) {
                    delay(1000)
                }
            }
        }

        Log.w(TAG, "Failed to get FCM token after $maxRetries attempts")
        showSnackbar("Unable to get notification token. Some features may be limited.")
        return ""
    }

    private fun navigateToHome() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        requireActivity().finish()
    }


    private fun navigateBack() {
        try {
            findNavController().navigateUp()
        } catch (e: Exception) {
            Log.e(TAG, "Navigation back failed: ${e.message}", e)
        }
    }

    private fun startCountdown() {
        countDownTimer?.cancel()
        timeLeftInSeconds = 60

        countDownTimer = object : CountDownTimer(RESEND_TIMEOUT, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeftInSeconds = (millisUntilFinished / 1000).toInt()
                updateTimerDisplay()
            }

            override fun onFinish() {
                timeLeftInSeconds = 0
                updateTimerDisplay()
                enableResend()
            }
        }.start()
    }

    private fun updateTimerDisplay() {
        tvTimer.text = "Code expires in ${timeLeftInSeconds}s"
        tvResendCode.text = "Resend in ${timeLeftInSeconds}s"
        tvResendCode.isEnabled = false
    }

    private fun enableResend() {
        tvResendCode.text = "Resend now"
        tvResendCode.isEnabled = true
        isResending = false
    }

    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        btnVerify.isEnabled = !show && validateOtp()
        btnVerify.text = if (show) {
            if (isResending) "Sending OTP..." else "Verifying..."
        } else {
            "Verify & Continue"
        }
        tvResendCode.isEnabled = !show && !isResending && timeLeftInSeconds == 0
        otpInputs.forEach { it.isEnabled = !show && !isResending }
    }

    private fun showKeyboard(view: View) {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show keyboard: ${e.message}", e)
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            view?.let {
                imm.hideSoftInputFromWindow(it.windowToken, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide keyboard: ${e.message}", e)
        }
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

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        countDownTimer?.cancel()
        super.onDestroyView()
    }

    companion object {
        fun newInstance(phoneNumber: String) = OtpVerificationFragment()
    }
}