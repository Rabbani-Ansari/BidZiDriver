package com.bidzidriver.app.auth

import android.content.Context.MODE_PRIVATE
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.bidzidriver.app.R


class AuthActivity : AppCompatActivity() {

    private val TAG = "AuthActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        // MUST call enableEdgeToEdge BEFORE super.onCreate()
        enableEdgeToEdge()
        Log.d(TAG, "enableEdgeToEdge called")

        super.onCreate(savedInstanceState)
        Log.d(TAG, "super.onCreate called")

        setContentView(R.layout.activity_auth)
        Log.d(TAG, "setContentView called")

        // Disable dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        Log.d(TAG, "Night mode disabled")
    }
}
