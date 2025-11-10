package com.bidzidriver.app.auth

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity



import android.content.Intent

import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bidzidriver.app.MainActivity

import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private var isReady = false

    companion object {
        private const val TAG = "SplashActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()

        // Install splash screen BEFORE super.onCreate()
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        Log.d(TAG, "SplashActivity onCreate called")

        // Keep splash screen visible until auth state is determined
        splashScreen.setKeepOnScreenCondition { !isReady }

        // Observe authentication state and handle navigation
        observeAuthState()
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.authState.collect { state ->
                    Log.d(TAG, "Auth State: $state")
                    when (state) {
                        is AuthState.Loading -> {
                            Log.d(TAG, "Loading authentication...")
                        }
                        is AuthState.Authenticated -> {
                            Log.d(TAG, "User authenticated, navigating to MainActivity")
                            isReady = true
                            navigateToMain()
                        }
                        is AuthState.Unauthenticated -> {
                            Log.d(TAG, "User not authenticated, navigating to AuthActivity")
                            isReady = true
                            navigateToAuth()
                        }
                    }
                }
            }
        }
    }

    private fun navigateToMain() {
        Log.d(TAG, "Navigating to MainActivity")
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun navigateToAuth() {
        Log.d(TAG, "Navigating to AuthActivity")
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
