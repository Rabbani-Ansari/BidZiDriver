package com.bidzidriver.app

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup


import androidx.core.content.ContextCompat
import com.bidzidriver.app.databinding.FragmentHomeBinding
import com.bidzidriver.app.location.LocationTrackingService
import com.bidzidriver.app.viewmodel.HomeViewModel

import com.google.android.material.snackbar.Snackbar


import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri

import android.os.IBinder
import android.provider.Settings

import androidx.activity.result.contract.ActivityResultContracts

import androidx.fragment.app.viewModels
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.bidzidriver.app.R



import android.util.Log
import androidx.lifecycle.Lifecycle

import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bidzidriver.app.location.DriverPreferences
import com.bidzidriver.app.location.LocationAlertDialog
import com.bidzidriver.app.location.LocationMonitor
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.coroutines.Job

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
class HomeFragment : Fragment(), OnMapReadyCallback {

    private val TAG = "HomeFragment"

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Get shared location monitor from MainActivity
    private val locationMonitor: LocationMonitor?
        get() = (activity as? MainActivity)?.locationMonitor

    private var googleMap: GoogleMap? = null
    private var driverMarker: Marker? = null
    private var accuracyCircle: Circle? = null

    private var locationService: LocationTrackingService? = null
    private var isServiceBound = false
    private var serviceCollectionJob: Job? = null

    // Debouncing
    private var lastClickTime = 0L
    private val DEBOUNCE_DELAY = 2000L

    // Map optimization
    private var lastMapUpdateLocation: LatLng? = null
    private val MAP_UPDATE_THRESHOLD = 0.00005
    private var markerAnimationJob: Job? = null

    // Alert tracking
    private var lastAccuracyAlertTime = 0L
    private val ACCURACY_ALERT_INTERVAL = 300000L // 5 minutes

    // Broadcast receiver for global events
    private val globalEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.bidzidriver.app.DRIVER_FORCED_OFFLINE" -> {
                    val reason = intent.getStringExtra("reason") ?: "Unknown"
                    Log.w(TAG, "⚠️ Forced offline: $reason")
                    handleForcedOffline()
                }
                "com.bidzidriver.app.LOCATION_PERMISSION_GRANTED" -> {
                    Log.d(TAG, "✅ Location permission granted")
                    checkGPSSettings()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "🔗 SERVICE CONNECTED")
            val binder = service as LocationTrackingService.LocalBinder
            locationService = binder.getService()
            isServiceBound = true

            // Start collecting only if view is available
            startLocationCollection()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "🔓 Service disconnected")
            stopLocationCollection()
            locationService = null
            isServiceBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "🚀 onViewCreated")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        setupMap()
        setupClickListeners()
        observeViewModel()
        observeLocationMonitor()
        registerGlobalEventReceiver()

        // Start collecting if service is already connected
        startLocationCollection()

        // Restore state
        restoreSavedState()
    }

    /**
     * Start collecting location updates from service
     * Only starts if both view and service are available
     */
    private fun startLocationCollection() {
        // Guard: Check if view exists and fragment is added
        if (_binding == null || !isAdded || view == null) {
            Log.d(TAG, "⏸️ View not ready, skipping collection")
            return
        }

        // Guard: Check if service is available
        if (locationService == null) {
            Log.d(TAG, "⏸️ Service not ready, skipping collection")
            return
        }

        // Cancel any existing collection
        serviceCollectionJob?.cancel()

        serviceCollectionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                locationService?.locationFlow?.collect { location ->
                    // Double-check view is still available
                    if (_binding == null || !isAdded) {
                        Log.w(TAG, "⚠️ View destroyed during collection")
                        return@collect
                    }

                    Log.d(TAG, "📍 Location: (${location.latitude}, ${location.longitude}) ±${location.accuracy}m")

                    viewModel.updateLocation(location)
                    updateMap(location)
                    checkLocationAccuracy(location)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error collecting location: ${e.message}")
            }
        }

        Log.d(TAG, "✅ Flow collection started")
    }

    /**
     * Stop collecting location updates
     */
    private fun stopLocationCollection() {
        serviceCollectionJob?.cancel()
        serviceCollectionJob = null
        Log.d(TAG, "🛑 Flow collection stopped")
    }

    /**
     * Register receiver for global events from MainActivity
     */
    private fun registerGlobalEventReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.bidzidriver.app.DRIVER_FORCED_OFFLINE")
            addAction("com.bidzidriver.app.LOCATION_PERMISSION_GRANTED")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                globalEventReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(globalEventReceiver, filter)
        }
    }

    /**
     * Observe location monitor state from MainActivity
     */
    private fun observeLocationMonitor() {
        val monitor = locationMonitor
        if (monitor == null) {
            Log.e(TAG, "❌ LocationMonitor not available")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    monitor.locationState.collect { state ->
                        handleFragmentLocationState(state)
                    }
                }

                launch {
                    monitor.networkState.collect { state ->
                        handleFragmentNetworkState(state)
                    }
                }
            }
        }
    }

    private fun handleFragmentLocationState(state: LocationMonitor.LocationState) {
        // MainActivity handles critical states globally
        // Fragment only needs to update UI
        if (!isAdded || _binding == null) return

        binding.apply {
            when (state) {
                LocationMonitor.LocationState.DISABLED_WHILE_ONLINE,
                LocationMonitor.LocationState.DISABLED -> {
                    tvStatusMessage.apply {
                        text = "⚠️ Location services disabled"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
                    }
                }
                LocationMonitor.LocationState.GPS_ONLY_DISABLED -> {
                    tvStatusMessage.apply {
                        text = "⚠️ GPS disabled - using network location"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.orange))
                    }
                }
                LocationMonitor.LocationState.ENABLED -> {
                    // Will be updated by location accuracy
                }
            }
        }
    }

    private fun handleFragmentNetworkState(state: LocationMonitor.NetworkState) {
        // Update UI based on network state
        when (state) {
            LocationMonitor.NetworkState.DISCONNECTED,
            LocationMonitor.NetworkState.NO_INTERNET -> {
                viewModel.updateNetworkAvailability(false)
            }
            LocationMonitor.NetworkState.LIMITED -> {
                showModernSnackbar("⚠️ Weak network signal")
            }
            else -> {
                viewModel.updateNetworkAvailability(true)
            }
        }
    }

    private fun handleForcedOffline() {
        // Update UI when forced offline by MainActivity
        viewModel.setOnlineStatus(false)
        stopLocationService()
    }

    private fun checkLocationAccuracy(location: Location) {
        if (location.accuracy > 200f) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAccuracyAlertTime > ACCURACY_ALERT_INTERVAL) {
                lastAccuracyAlertTime = currentTime

                // Guard: Check if fragment is still attached
                if (isAdded && context != null) {
                    LocationAlertDialog.showLocationAccuracyAlert(requireContext(), location.accuracy)
                }
            }
        }
    }

    private fun restoreSavedState() {
        val wasOnline = DriverPreferences.getOnlineStatus()

        if (wasOnline) {
            Log.d(TAG, "🔄 Restoring online state")
            viewModel.setOnlineStatus(true)

            lifecycleScope.launch {
                delay(500)
                showModernSnackbar("Welcome back! Resuming location sharing...", isSuccess = true)
            }

            if (hasLocationPermissions()) {
                locationMonitor?.checkLocationStatus()

                lifecycleScope.launch {
                    delay(1000)

                    if (locationMonitor?.locationState?.value == LocationMonitor.LocationState.ENABLED) {
                        reconnectLocationService()
                    } else {
                        Log.w(TAG, "⚠️ Can't reconnect - location disabled")
                        DriverPreferences.setOnlineStatus(false)
                        viewModel.setOnlineStatus(false)
                    }
                }
            } else {
                Log.w(TAG, "⚠️ Permissions revoked")
                DriverPreferences.setOnlineStatus(false)
                viewModel.setOnlineStatus(false)

                lifecycleScope.launch {
                    delay(1000)
                    if (isAdded && context != null) {
                        LocationAlertDialog.showPermissionRevokedAlert(requireContext()) {
                            // Already offline
                        }
                    }
                }
            }
        }

        // Load last location
        DriverPreferences.getLastLocation()?.let { (lat, lng, _) ->
            if (lat != null && lng != null) {
                Log.d(TAG, "📍 Last known: ($lat, $lng)")

                val location = Location("saved").apply {
                    latitude = lat
                    longitude = lng
                    accuracy = 50f
                }

                lifecycleScope.launch {
                    delay(500)
                    updateMap(location)
                }
            }
        }
    }

    private fun reconnectLocationService() {
        if (!isAdded || context == null) {
            Log.w(TAG, "⚠️ Fragment not attached, skipping reconnect")
            return
        }

        Log.d(TAG, "🔄 Reconnecting to location service")

        val intent = Intent(requireContext(), LocationTrackingService::class.java)

        try {
            ContextCompat.startForegroundService(requireContext(), intent)

            requireContext().bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )

            Log.d(TAG, "✅ Service reconnected")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to reconnect: ${e.message}")
            DriverPreferences.setOnlineStatus(false)
            viewModel.setOnlineStatus(false)
        }
    }

    private fun setupMap() {
        val mapFragment = SupportMapFragment.newInstance()
        childFragmentManager.beginTransaction()
            .replace(R.id.mapContainer, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        Log.d(TAG, "✅ Map ready")
        googleMap = map
        googleMap?.apply {
            uiSettings.apply {
                isZoomControlsEnabled = false
                isMyLocationButtonEnabled = false
                isRotateGesturesEnabled = true
                isCompassEnabled = true
            }

            val initialPos = LatLng(28.6139, 77.2090)
            moveCamera(CameraUpdateFactory.newLatLngZoom(initialPos, 14f))
        }

        getLastKnownLocation()
    }

    private fun getLastKnownLocation() {
        if (!hasLocationPermissions()) return

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    Log.d(TAG, "📌 Last location: ±${it.accuracy}m")
                    updateMap(it)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Last location error: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        binding.fabToggleStatus.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < DEBOUNCE_DELAY) {
                Log.d(TAG, "⏱️ Click debounced")
                return@setOnClickListener
            }
            lastClickTime = currentTime
            toggleOnlineStatus()
        }

        binding.btnViewRequest.setOnClickListener {
            val message = if (viewModel.isOnline.value) {
                "No pending requests"
            } else {
                "Go online to receive requests"
            }
            showModernSnackbar(message)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Online status
                launch {
                    viewModel.isOnline.collect { isOnline ->
                        updateUI(isOnline)
                    }
                }

                // Location accuracy
                launch {
                    viewModel.locationAccuracy.collect { accuracy ->
                        updateAccuracyUI(accuracy)
                    }
                }

                // System health
                launch {
                    viewModel.systemHealth.collect { health ->
                        handleSystemHealth(health)
                    }
                }

                // Error state
                launch {
                    viewModel.errorState.collect { error ->
                        error?.let { handleError(it) }
                    }
                }
            }
        }
    }

    private fun updateAccuracyUI(accuracy: HomeViewModel.LocationAccuracy) {
        if (!isAdded || _binding == null) return

        binding.tvStatusMessage.text = when (accuracy) {
            HomeViewModel.LocationAccuracy.EXCELLENT -> "📍 Excellent accuracy (±<10m)"
            HomeViewModel.LocationAccuracy.VERY_GOOD -> "📍 Very good accuracy (±10-20m)"
            HomeViewModel.LocationAccuracy.GOOD -> "📍 Good accuracy (±20-50m)"
            HomeViewModel.LocationAccuracy.FAIR -> "📍 Fair accuracy (±50-100m)"
            HomeViewModel.LocationAccuracy.POOR -> "⚠️ Poor accuracy (±100-200m)"
            HomeViewModel.LocationAccuracy.VERY_POOR -> "❌ Very poor accuracy (±>200m)"
            HomeViewModel.LocationAccuracy.UNKNOWN -> "📍 Calculating accuracy..."
        }

        binding.tvStatusMessage.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when (accuracy) {
                    HomeViewModel.LocationAccuracy.EXCELLENT,
                    HomeViewModel.LocationAccuracy.VERY_GOOD,
                    HomeViewModel.LocationAccuracy.GOOD -> R.color.driver_green
                    HomeViewModel.LocationAccuracy.FAIR,
                    HomeViewModel.LocationAccuracy.POOR -> R.color.orange
                    HomeViewModel.LocationAccuracy.VERY_POOR -> R.color.red
                    else -> R.color.text_medium
                }
            )
        )
    }

    private fun handleSystemHealth(health: HomeViewModel.SystemHealth) {
        if (!isAdded || _binding == null) return

        // Update UI based on overall system health
        when (health) {
            HomeViewModel.SystemHealth.CRITICAL -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.offline_indicator)
            }
            HomeViewModel.SystemHealth.WARNING -> {
                // Keep current indicator but show warning
            }
            HomeViewModel.SystemHealth.HEALTHY -> {
                if (viewModel.isOnline.value) {
                    binding.statusIndicator.setBackgroundResource(R.drawable.online_indicator)
                }
            }
            HomeViewModel.SystemHealth.OFFLINE -> {
                binding.statusIndicator.setBackgroundResource(R.drawable.offline_indicator)
            }
        }
    }

    private fun handleError(error: HomeViewModel.ErrorState) {
        if (!isAdded || context == null) return

        when (error) {
            HomeViewModel.ErrorState.NO_LOCATION_PERMISSION -> {
                showPermissionDialog()
            }
            HomeViewModel.ErrorState.LOCATION_DISABLED -> {
                // Handled by MainActivity globally
            }
            HomeViewModel.ErrorState.NO_NETWORK -> {
                showModernSnackbar("❌ No internet connection", isSuccess = false)
            }
            HomeViewModel.ErrorState.NETWORK_TIMEOUT -> {
                showModernSnackbar("⏰ Network timeout - retrying...", isSuccess = false)
            }
            HomeViewModel.ErrorState.DATABASE_ERROR -> {
                showModernSnackbar("❌ Database error - check connection", isSuccess = false)
            }
            else -> {
                showModernSnackbar("❌ Error: ${error.name}", isSuccess = false)
            }
        }
    }

    private fun toggleOnlineStatus() {
        if (viewModel.isOnline.value) {
            goOffline()
        } else {
            goOnline()
        }
    }

    private fun goOnline() {
        // Check permissions
        if (!hasLocationPermissions()) {
            showPermissionDialog()
            return
        }

        // Check location is enabled (MainActivity monitors this)
        locationMonitor?.checkLocationStatus()

        when (locationMonitor?.locationState?.value) {
            LocationMonitor.LocationState.DISABLED,
            LocationMonitor.LocationState.DISABLED_WHILE_ONLINE -> {
                if (isAdded && context != null) {
                    LocationAlertDialog.showLocationDisabledAlert(requireContext()) {
                        // Can't go online
                    }
                }
                return
            }
            else -> {
                checkGPSSettings()
            }
        }
    }

    private fun goOffline() {
        Log.d(TAG, "🛑 Going offline")

        stopLocationService()

        DriverPreferences.setOnlineStatus(false)
        viewModel.setOnlineStatus(false)
        viewModel.updateDriverStatus(false)

        showModernSnackbar("You're now offline", isSuccess = true)
    }

    private fun checkGPSSettings() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        ).build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        LocationServices.getSettingsClient(requireActivity())
            .checkLocationSettings(builder.build())
            .addOnSuccessListener {
                Log.d(TAG, "✅ GPS enabled")
                startLocationService()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        exception.startResolutionForResult(requireActivity(), GPS_REQUEST_CODE)
                    } catch (e: Exception) {
                        showGPSDialog()
                    }
                } else {
                    showGPSDialog()
                }
            }
    }

    private fun startLocationService() {
        if (!isAdded || context == null) {
            Log.w(TAG, "⚠️ Fragment not attached, skipping service start")
            return
        }

        Log.d(TAG, "🚀 Starting location service")

        val intent = Intent(requireContext(), LocationTrackingService::class.java)

        try {
            ContextCompat.startForegroundService(requireContext(), intent)

            val bindResult = requireContext().bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )

            Log.d(TAG, "🔗 Bind result: $bindResult")

            DriverPreferences.setOnlineStatus(true)
            viewModel.setOnlineStatus(true)
            viewModel.updateDriverStatus(true)

            showModernSnackbar("You're online! 🎉", isSuccess = true)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start: ${e.message}")
            showModernSnackbar("Failed to start: ${e.message}", isSuccess = false)
        }
    }

    private fun stopLocationService() {
        if (!isAdded || context == null) {
            Log.w(TAG, "⚠️ Fragment not attached, cleanup already done")
            return
        }

        stopLocationCollection()

        if (isServiceBound) {
            try {
                requireContext().unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error unbinding service: ${e.message}")
            }
        }

        val intent = Intent(requireContext(), LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        requireContext().startService(intent)

        Log.d(TAG, "✅ Service stopped")
    }

    private fun updateMap(location: Location) {
        val latLng = LatLng(location.latitude, location.longitude)

        // Optimize: Skip update if location hasn't changed significantly
        lastMapUpdateLocation?.let { last ->
            val latDiff = Math.abs(latLng.latitude - last.latitude)
            val lngDiff = Math.abs(latLng.longitude - last.longitude)

            if (latDiff < MAP_UPDATE_THRESHOLD && lngDiff < MAP_UPDATE_THRESHOLD) {
                return
            }
        }

        lastMapUpdateLocation = latLng
        Log.d(TAG, "🗺️ Updating map")

        googleMap?.let { map ->
            if (driverMarker == null) {
                driverMarker = map.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("You")
                        .icon(BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_BLUE
                        ))
                )
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            } else {
                animateMarkerSmooth(driverMarker!!, latLng)
            }

            // Only show accuracy circle when zoomed in
            if (map.cameraPosition.zoom >= 14f) {
                accuracyCircle?.remove()
                accuracyCircle = map.addCircle(
                    CircleOptions()
                        .center(latLng)
                        .radius(location.accuracy.toDouble())
                        .strokeColor(0x330000FF)
                        .fillColor(0x110000FF)
                        .strokeWidth(2f)
                )
            }
        }
    }

    private fun animateMarkerSmooth(marker: Marker, toPosition: LatLng) {
        // Cancel previous animation
        markerAnimationJob?.cancel()

        markerAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            val startPosition = marker.position
            val distance = calculateDistance(startPosition, toPosition)

            // Skip animation for large distances
            if (distance > 100) {
                marker.position = toPosition
                return@launch
            }

            val steps = 15
            val delay = 16L // ~60fps

            for (i in 0..steps) {
                // Check if still active
                if (!isActive || _binding == null) return@launch

                val fraction = i.toFloat() / steps
                val lat = startPosition.latitude +
                        (toPosition.latitude - startPosition.latitude) * fraction
                val lng = startPosition.longitude +
                        (toPosition.longitude - startPosition.longitude) * fraction

                marker.position = LatLng(lat, lng)
                delay(delay)
            }
        }
    }

    private fun calculateDistance(from: LatLng, to: LatLng): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            from.latitude, from.longitude,
            to.latitude, to.longitude,
            results
        )
        return results[0].toDouble()
    }

    private fun updateUI(isOnline: Boolean) {
        if (!isAdded || _binding == null) return

        binding.apply {
            if (isOnline) {
                statusIndicator.setBackgroundResource(R.drawable.online_indicator)
                tvStatus.text = "You're Online"
                tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_dark))

                fabToggleStatus.apply {
                    text = "Go Offline"
                    setIconResource(R.drawable.ic_offline)
                    backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.red)
                }
            } else {
                statusIndicator.setBackgroundResource(R.drawable.offline_indicator)
                tvStatus.text = "You're Offline"
                tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_medium))
                tvStatusMessage.text = "Go online to start receiving ride requests"

                fabToggleStatus.apply {
                    text = "Go Online"
                    setIconResource(R.drawable.ic_online)
                    backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.driver_green)
                }
            }
        }
    }

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDialog() {
        if (!isAdded || context == null) return

        MaterialAlertDialogBuilder(requireContext(), R.style.ModernAlertDialog)
            .setTitle("📍 Location Access")
            .setMessage("We need your location to connect you with nearby passengers")
            .setPositiveButton("Allow") { _, _ ->
                // Use MainActivity's permission request
                (activity as? MainActivity)?.requestLocationPermissionsFromFragment()
            }
            .setNegativeButton("Not Now", null)
            .setCancelable(false)
            .show()
    }

    private fun showGPSDialog() {
        if (!isAdded || context == null) return

        MaterialAlertDialogBuilder(requireContext(), R.style.ModernAlertDialog)
            .setTitle("🛰️ GPS Required")
            .setMessage("Please enable location services")
            .setPositiveButton("Enable") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModernSnackbar(message: String, isSuccess: Boolean = false) {
        // Use MainActivity's snackbar for consistency
        (activity as? MainActivity)?.showSnackbar(message, !isSuccess)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 onResume")

        // Force health check
        lifecycleScope.launch {
            delay(500)
            locationMonitor?.checkSystemHealth()
            viewModel.forceHealthCheck()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "📱 onPause")

        // Reset alert times to prevent stale alerts
        lastAccuracyAlertTime = 0L
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "💀 onDestroyView")

        // Cancel all animations and jobs
        markerAnimationJob?.cancel()
        markerAnimationJob = null
        stopLocationCollection()

        // Unbind service
        if (isServiceBound && context != null) {
            try {
                requireContext().unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error unbinding service: ${e.message}")
            }
        }

        // Unregister receiver
        try {
            requireContext().unregisterReceiver(globalEventReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error unregistering receiver: ${e.message}")
        }

        // Clear map references
        googleMap = null
        driverMarker = null
        accuracyCircle = null

        // Clear binding
        _binding = null
    }

    companion object {
        private const val GPS_REQUEST_CODE = 1001
    }
}