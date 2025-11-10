package com.bidzidriver.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.bidzidriver.app.databinding.ActivityMainBinding

import com.bidzidriver.app.location.LocationTrackingService

import com.google.android.material.snackbar.Snackbar


import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler

import android.os.Looper

import android.provider.Settings
import androidx.activity.viewModels


import com.google.android.material.dialog.MaterialAlertDialogBuilder

import androidx.lifecycle.Lifecycle

import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bidzidriver.app.location.DriverPreferences
import com.bidzidriver.app.location.LocationAlertDialog
import com.bidzidriver.app.location.LocationMonitor
import com.bidzidriver.app.supabase.RideBooking
import com.bidzidriver.app.supabase.RideRequest

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext





import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import com.bidzidriver.app.helper.RideQueueManager
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// ========== ViewModel Required Imports ==========

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.channels.BufferOverflow
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    lateinit var locationMonitor: LocationMonitor
        private set

    private val rideRequestViewModel: RideRequestViewModel by viewModels()

    // ✅ NEW: Centralized queue manager
    private lateinit var rideQueueManager: RideQueueManager

    private var hasShownGPSWarning = false
    private var hasShownRoamingInfo = false
    private var networkGracePeriodJob: Job? = null

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "✅ Notification permission granted")
        } else {
            Log.w(TAG, "⚠️ Notification permission denied")
            showNotificationPermissionDialog()
        }
    }

    private val requestLocationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "✅ Location permissions granted")
            onLocationPermissionsGranted()
        } else {
            Log.w(TAG, "⚠️ Location permissions denied")
            handleLocationPermissionDenied(permissions)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val NETWORK_GRACE_PERIOD_MS = 30000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Log.d(TAG, "🚀 onCreate called")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()
        initializeRideQueueManager()
        initializeLocationMonitor()
        setupNavigation()
        observeLocationStates()
        observeRideRequestViewModel()
        requestNotificationPermission()
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
    }

    /**
     * ✅ NEW: Initialize centralized queue manager
     */
    private fun initializeRideQueueManager() {
        rideQueueManager = RideQueueManager(
            activity = this,
            onAccept = { ride -> rideRequestViewModel.acceptRide(ride) },
            onReject = { ride -> rideRequestViewModel.rejectRide(ride) },
            onCounterOffer = { ride, amount, msg ->
                rideRequestViewModel.submitCounterOffer(ride, amount, msg)
            }
        )
        Log.d(TAG, "✅ Queue manager initialized")
    }

    private fun initializeLocationMonitor() {
        locationMonitor = LocationMonitor(this)
        lifecycle.addObserver(locationMonitor)
        locationMonitor.createNotificationChannels()
        Log.d(TAG, "📍 Location monitor initialized")
    }

    // ========== RIDE REQUEST OBSERVERS ==========

    private fun observeRideRequestViewModel() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // UI state changes
                launch {
                    rideRequestViewModel.uiState.collect { state ->
                        handleRideRequestUiState(state)
                    }
                }

                // One-time events
                launch {
                    rideRequestViewModel.events.collect { event ->
                        handleRideRequestEvent(event)
                    }
                }

                // ✅ SIMPLIFIED: Just pass rides to queue manager
                launch {
                    rideRequestViewModel.activeRideRequests
                        // ✅ REMOVED: distinctUntilChanged() - StateFlow already has it built-in
                        .collect { requests ->
                            Log.d(TAG, "📋 ViewModel: ${requests.size} active rides")
                            rideQueueManager.updateQueue(requests)
                        }
                }

            }
        }
    }

    private fun handleRideRequestUiState(state: RideRequestUiState) {
        when (state) {
            is RideRequestUiState.AcceptingRide -> {
                Log.d(TAG, "🔄 Accepting ride...")
            }
            is RideRequestUiState.SubmittingCounterOffer -> {
                Log.d(TAG, "🔄 Submitting counter offer...")
            }
            is RideRequestUiState.CounterOfferSubmitted -> {
                showSnackbar("💰 Counter offer sent! Waiting for response...")
            }
            is RideRequestUiState.Error -> {
                showSnackbar(state.message, isError = true)
            }
            is RideRequestUiState.WaitingForConfirmation -> {
                showSnackbar("⏳ Response sent! Waiting for confirmation...")
            }
            is RideRequestUiState.Idle -> {
                // Normal state
            }
            is RideRequestUiState.Loading -> {
                Log.d(TAG, "🔄 Loading rides...")
            }
            is RideRequestUiState.RideAccepted -> {
                // Handled in events
            }
        }
    }

    private fun handleRideRequestEvent(event: RideRequestEvent) {
        when (event) {
            is RideRequestEvent.NewRideAvailable -> {
                Log.d(TAG, "🆕 Event: New ride available: ${event.rideRequest.rideId}")
                // Queue manager already notified via updateQueue
            }

            is RideRequestEvent.RideAcceptedSuccess -> {
                showSnackbar("✅ Ride accepted! Navigating...")
                navigateToActiveRide(event.booking)
            }

            is RideRequestEvent.RideCancelled -> {
                showSnackbar("❌ Ride cancelled by rider")
            }

            is RideRequestEvent.RideTakenByOther -> {
                showSnackbar("⚠️ Ride accepted by another driver")
            }

            is RideRequestEvent.RideRejected -> {
                // Already handled by queue manager
            }

            is RideRequestEvent.CounterOfferRejected -> {
                showSnackbar("❌ Counter offer rejected", isError = true)
            }

            is RideRequestEvent.CounterOfferExpired -> {
                showSnackbar("⏱️ Counter offer expired")
            }

            is RideRequestEvent.CounterOfferAccepted -> {
                showSnackbar("💰 Counter offer accepted! Waiting for confirmation...")
            }

            is RideRequestEvent.CounterOfferAcceptedAndConfirmed -> {
                showSnackbar("🎉 Counter offer accepted! Navigating...")
                navigateToActiveRide(event.booking)
            }

            is RideRequestEvent.RideConfirmedForDriver -> {
                showSnackbar("✅ Ride confirmed! Navigating...")
                navigateToActiveRide(event.booking)
            }

            is RideRequestEvent.ConnectionLost -> {
                showSnackbar("⚠️ Connection lost. Reconnecting...", isError = true)
            }

            else -> {
                // Other events
            }
        }
    }

    /**
     * ✅ SIMPLIFIED: Clear queue when going offline
     */
    fun clearRideQueue() {
        rideQueueManager.clearQueue()
        Log.d(TAG, "🧹 Cleared ride queue")
    }

    private fun navigateToActiveRide(booking: RideBooking) {
        Log.d(TAG, "🚗 Navigating to active ride: ${booking.id}")
        showSnackbar("🚗 Active ride feature coming soon!")
        // TODO: Implement navigation to active ride screen
    }

    // ========== LOCATION & NETWORK MONITORING ==========

    private fun observeLocationStates() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    locationMonitor.locationState.collect { state ->
                        handleGlobalLocationState(state)
                    }
                }
                launch {
                    locationMonitor.networkState.collect { state ->
                        handleGlobalNetworkState(state)
                    }
                }
            }
        }
    }

    private fun handleGlobalLocationState(state: LocationMonitor.LocationState) {
        val isOnline = DriverPreferences.getOnlineStatus()
        Log.d(TAG, "📍 Location: $state (Online: $isOnline)")

        when (state) {
            LocationMonitor.LocationState.DISABLED_WHILE_ONLINE -> {
                Log.e(TAG, "🚨 Location disabled while online!")
                forceDriverOffline("Location services disabled")
                if (isAppInForeground()) {
                    LocationAlertDialog.showLocationDisabledAlert(this) {}
                }
            }
            LocationMonitor.LocationState.GPS_ONLY_DISABLED -> {
                if (isOnline && isAppInForeground() && !hasShownGPSWarning) {
                    hasShownGPSWarning = true
                    LocationAlertDialog.showGPSDisabledAlert(this)
                }
            }
            LocationMonitor.LocationState.DISABLED -> {
                hasShownGPSWarning = false
            }
            LocationMonitor.LocationState.ENABLED -> {
                hasShownGPSWarning = false
            }
        }
    }

    private fun handleGlobalNetworkState(state: LocationMonitor.NetworkState) {
        val isOnline = DriverPreferences.getOnlineStatus()
        Log.d(TAG, "🌐 Network: $state (Online: $isOnline)")

        when (state) {
            LocationMonitor.NetworkState.DISCONNECTED,
            LocationMonitor.NetworkState.NO_INTERNET -> {
                if (isOnline) {
                    if (isAppInForeground()) {
                        networkGracePeriodJob?.cancel()
                        LocationAlertDialog.showNetworkDisabledAlert(this) {
                            forceDriverOffline("No internet")
                        }
                    } else {
                        if (networkGracePeriodJob?.isActive != true) {
                            startNetworkGracePeriod()
                        }
                    }
                }
            }
            LocationMonitor.NetworkState.LIMITED -> {
                Log.w(TAG, "⚠️ Limited connectivity")
            }
            LocationMonitor.NetworkState.ROAMING -> {
                if (isOnline && !hasShownRoamingInfo) {
                    hasShownRoamingInfo = true
                    showSnackbar("📱 Roaming - data charges may apply")
                }
            }
            LocationMonitor.NetworkState.CONNECTED -> {
                hasShownRoamingInfo = false
                networkGracePeriodJob?.cancel()
                networkGracePeriodJob = null
            }
        }
    }

    private fun startNetworkGracePeriod() {
        networkGracePeriodJob = lifecycleScope.launch {
            Log.d(TAG, "⏳ Network grace period started")
            delay(NETWORK_GRACE_PERIOD_MS)

            val stillDisconnected = locationMonitor.networkState.value in listOf(
                LocationMonitor.NetworkState.DISCONNECTED,
                LocationMonitor.NetworkState.NO_INTERNET
            )

            if (stillDisconnected && DriverPreferences.getOnlineStatus()) {
                forceDriverOffline("Extended network outage")
            }
        }
    }

    private fun forceDriverOffline(reason: String) {
        Log.w(TAG, "🛑 Forcing offline: $reason")

        DriverPreferences.setOnlineStatus(false)

        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        startService(intent)

        sendBroadcast(Intent("com.bidzidriver.app.DRIVER_FORCED_OFFLINE").apply {
            putExtra("reason", reason)
        })

        clearRideQueue()
        showSnackbar("⚠️ Taken offline: $reason", isError = true)
    }

    // ========== PERMISSIONS ==========

    fun requestLocationPermissionsFromFragment() {
        if (hasLocationPermissions()) {
            onLocationPermissionsGranted()
            return
        }
        requestLocationPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun onLocationPermissionsGranted() {
        sendBroadcast(Intent("com.bidzidriver.app.LOCATION_PERMISSION_GRANTED"))
    }

    private fun handleLocationPermissionDenied(permissions: Map<String, Boolean>) {
        val shouldShowRationale = shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (!shouldShowRationale) {
            LocationAlertDialog.showPermissionRevokedAlert(this) {
                forceDriverOffline("Location permission denied")
            }
        } else {
            showPermissionRationale()
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this, R.style.ModernAlertDialog)
            .setTitle("📍 Location Permission Required")
            .setMessage("Location access is essential for receiving rides and navigation.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestLocationPermissionsFromFragment()
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showNotificationPermissionDialog() {
        MaterialAlertDialogBuilder(this, R.style.ModernAlertDialog)
            .setTitle("🔔 Notification Permission")
            .setMessage("Enable notifications for ride alerts and system warnings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ========== NAVIGATION & UI ==========

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        if (navHostFragment == null) {
            Log.e(TAG, "❌ NavHostFragment not found")
            return
        }
        navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    fun showSnackbar(message: String, isError: Boolean = false, isNewRide: Boolean = false) {
        if (isFinishing || isDestroyed) return

        val duration = if (isNewRide) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT

        Snackbar.make(binding.root, message, duration).apply {
            setBackgroundTint(
                ContextCompat.getColor(
                    this@MainActivity,
                    when {
                        isError -> R.color.red
                        isNewRide -> R.color.accent_orange
                        else -> R.color.driver_green
                    }
                )
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            animationMode = Snackbar.ANIMATION_MODE_SLIDE

            if (isNewRide) {
                setAction("View") {
                    dismiss()
                }
                setActionTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            }

            show()
        }
    }

    private fun isAppInForeground(): Boolean {
        return try {
            val appProcesses = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses ?: return false
            appProcesses.any {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.processName == packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 onResume - Queue: ${rideQueueManager.getQueueStatus()}")
        networkGracePeriodJob?.cancel()
        lifecycleScope.launch {
            delay(500)
            locationMonitor.checkSystemHealth()
        }
    }

    override fun onPause() {
        super.onPause()
        hasShownGPSWarning = false
        hasShownRoamingInfo = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 Activity destroyed")

        // Cleanup
        networkGracePeriodJob?.cancel()
        rideQueueManager.cleanup()
    }
}

