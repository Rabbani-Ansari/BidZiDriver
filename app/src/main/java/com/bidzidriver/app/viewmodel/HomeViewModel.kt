package com.bidzidriver.app.viewmodel


import android.app.Application
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bidzidriver.app.location.LocationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


import android.util.Log
import com.bidzidriver.app.location.DriverPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "HomeViewModel"
    private val context = application.applicationContext

    // Online status
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Current location
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    // System health status
    private val _systemHealth = MutableStateFlow<SystemHealth>(SystemHealth.HEALTHY)
    val systemHealth: StateFlow<SystemHealth> = _systemHealth.asStateFlow()

    // Location accuracy tracking
    private val _locationAccuracy = MutableStateFlow<LocationAccuracy>(LocationAccuracy.UNKNOWN)
    val locationAccuracy: StateFlow<LocationAccuracy> = _locationAccuracy.asStateFlow()

    // Network status
    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    // Error state
    private val _errorState = MutableStateFlow<ErrorState?>(null)
    val errorState: StateFlow<ErrorState?> = _errorState.asStateFlow()

    // Statistics
    private var locationUpdateCount = 0
    private var successfulSyncCount = 0
    private var failedSyncCount = 0
    private val sessionStartTime = System.currentTimeMillis()

    // Debouncing
    private var lastStatusUpdateTime = 0L
    private val STATUS_UPDATE_DEBOUNCE = 3000L

    init {
        Log.d(TAG, "🚀 ViewModel initialized")
        monitorSystemHealth()
    }

    /**
     * Monitor overall system health
     */
    private fun monitorSystemHealth() {
        viewModelScope.launch {
            while (isActive) {
                evaluateSystemHealth()
                delay(5000) // Check every 5 seconds
            }
        }
    }

    private fun evaluateSystemHealth() {
        val isOnline = _isOnline.value
        val hasLocation = _currentLocation.value != null
        val hasNetwork = _isNetworkAvailable.value
        val accuracy = _locationAccuracy.value

        _systemHealth.value = when {
            // Critical issues
            isOnline && !hasLocation -> SystemHealth.CRITICAL
            isOnline && !hasNetwork -> SystemHealth.CRITICAL

            // Warnings
            isOnline && accuracy == LocationAccuracy.POOR -> SystemHealth.WARNING
            isOnline && accuracy == LocationAccuracy.VERY_POOR -> SystemHealth.WARNING

            // All good
            isOnline && hasLocation && hasNetwork -> SystemHealth.HEALTHY

            // Offline (not applicable)
            else -> SystemHealth.OFFLINE
        }
    }

    /**
     * Set online/offline status with validation
     */
    fun setOnlineStatus(online: Boolean) {
        Log.d(TAG, "🔄 Status change requested: $online")

        if (online) {
            // Validate before going online
            if (!validateCanGoOnline()) {
                Log.w(TAG, "⚠️ Cannot go online - system not ready")
                _errorState.value = ErrorState.SYSTEM_NOT_READY
                return
            }
        }

        _isOnline.value = online

        if (!online) {
            _systemHealth.value = SystemHealth.OFFLINE
            clearErrors()
        }
    }

    private fun validateCanGoOnline(): Boolean {
        // Check location permissions
        if (!hasLocationPermissions()) {
            _errorState.value = ErrorState.NO_LOCATION_PERMISSION
            return false
        }

        // Check network
        if (!isNetworkConnected()) {
            _errorState.value = ErrorState.NO_NETWORK
            return false
        }

        return true
    }

    /**
     * Update location with comprehensive tracking
     */
    fun updateLocation(location: Location) {
        locationUpdateCount++

        val accuracy = calculateLocationAccuracy(location.accuracy)
        _locationAccuracy.value = accuracy

        Log.d(TAG, "📍 Location #$locationUpdateCount: " +
                "(${String.format("%.6f", location.latitude)}, " +
                "${String.format("%.6f", location.longitude)}) " +
                "±${location.accuracy.toInt()}m [$accuracy]")

        _currentLocation.value = location

        // Save last known location
        DriverPreferences.saveLastLocation(
            location.latitude,
            location.longitude,
            System.currentTimeMillis()
        )

        // Log accuracy issues
        if (accuracy == LocationAccuracy.VERY_POOR) {
            Log.w(TAG, "⚠️ Very poor location accuracy: ${location.accuracy}m")
        }

        // Clear location-related errors
        if (_errorState.value == ErrorState.NO_LOCATION) {
            clearErrors()
        }
    }

    private fun calculateLocationAccuracy(accuracy: Float): LocationAccuracy {
        return when {
            accuracy < 10 -> LocationAccuracy.EXCELLENT
            accuracy < 20 -> LocationAccuracy.VERY_GOOD
            accuracy < 50 -> LocationAccuracy.GOOD
            accuracy < 100 -> LocationAccuracy.FAIR
            accuracy < 200 -> LocationAccuracy.POOR
            else -> LocationAccuracy.VERY_POOR
        }
    }

    /**
     * Update driver status in database with retry logic
     */
    fun updateDriverStatus(isOnline: Boolean) {
        // Debounce status updates
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStatusUpdateTime < STATUS_UPDATE_DEBOUNCE) {
            Log.d(TAG, "⏱️ Status update debounced")
            return
        }
        lastStatusUpdateTime = currentTime

        viewModelScope.launch {
            try {
                Log.d(TAG, "🔄 Updating DB status: $isOnline")

                val result = LocationRepository.updateDriverOnlineStatus(isOnline)

                if (result.isSuccess) {
                    successfulSyncCount++
                    Log.d(TAG, "✅ Status updated (Success: $successfulSyncCount)")
                    clearErrors()
                } else {
                    failedSyncCount++
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "❌ Status update failed (Failures: $failedSyncCount): ${error?.message}")

                    _errorState.value = when {
                        error?.message?.contains("timeout", ignoreCase = true) == true ->
                            ErrorState.NETWORK_TIMEOUT
                        error?.message?.contains("network", ignoreCase = true) == true ->
                            ErrorState.NO_NETWORK
                        else -> ErrorState.DATABASE_ERROR
                    }
                }

            } catch (e: Exception) {
                failedSyncCount++
                Log.e(TAG, "❌ Exception (Failures: $failedSyncCount): ${e.message}")
                _errorState.value = ErrorState.UNKNOWN_ERROR
            }
        }
    }

    /**
     * Update network availability
     */
    fun updateNetworkAvailability(isAvailable: Boolean) {
        if (_isNetworkAvailable.value != isAvailable) {
            Log.d(TAG, "🌐 Network status changed: $isAvailable")
            _isNetworkAvailable.value = isAvailable

            if (!isAvailable && _isOnline.value) {
                _errorState.value = ErrorState.NO_NETWORK
            } else if (isAvailable) {
                // Clear network errors
                if (_errorState.value == ErrorState.NO_NETWORK ||
                    _errorState.value == ErrorState.NETWORK_TIMEOUT) {
                    clearErrors()
                }
            }
        }
    }

    /**
     * Handle location service errors
     */
    fun handleLocationError(error: String) {
        Log.e(TAG, "❌ Location error: $error")

        _errorState.value = when {
            error.contains("permission", ignoreCase = true) -> ErrorState.NO_LOCATION_PERMISSION
            error.contains("disabled", ignoreCase = true) -> ErrorState.LOCATION_DISABLED
            error.contains("timeout", ignoreCase = true) -> ErrorState.LOCATION_TIMEOUT
            else -> ErrorState.LOCATION_ERROR
        }
    }

    /**
     * Clear error state
     */
    fun clearErrors() {
        if (_errorState.value != null) {
            Log.d(TAG, "✅ Clearing errors")
            _errorState.value = null
        }
    }

    /**
     * Get session statistics
     */
    fun getSessionStats(): SessionStats {
        val sessionDuration = System.currentTimeMillis() - sessionStartTime
        val successRate = if (locationUpdateCount > 0) {
            (successfulSyncCount.toFloat() / locationUpdateCount * 100).toInt()
        } else 0

        return SessionStats(
            locationUpdates = locationUpdateCount,
            successfulSyncs = successfulSyncCount,
            failedSyncs = failedSyncCount,
            sessionDuration = sessionDuration,
            successRate = successRate
        )
    }

    /**
     * Force system health check
     */
    fun forceHealthCheck() {
        Log.d(TAG, "🔍 Force health check")
        evaluateSystemHealth()
    }

    private fun hasLocationPermissions(): Boolean {
        return context.checkSelfPermission(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onCleared() {
        super.onCleared()

        val stats = getSessionStats()
        Log.d(TAG, """
            💀 ViewModel cleared
            📊 Session Statistics:
               - Duration: ${formatDuration(stats.sessionDuration)}
               - Location Updates: ${stats.locationUpdates}
               - Successful Syncs: ${stats.successfulSyncs}
               - Failed Syncs: ${stats.failedSyncs}
               - Success Rate: ${stats.successRate}%
        """.trimIndent())
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    // Data classes
    enum class SystemHealth {
        HEALTHY,
        WARNING,
        CRITICAL,
        OFFLINE
    }

    enum class LocationAccuracy {
        EXCELLENT,   // < 10m
        VERY_GOOD,   // 10-20m
        GOOD,        // 20-50m
        FAIR,        // 50-100m
        POOR,        // 100-200m
        VERY_POOR,   // > 200m
        UNKNOWN
    }

    enum class ErrorState {
        NO_LOCATION_PERMISSION,
        LOCATION_DISABLED,
        NO_NETWORK,
        NETWORK_TIMEOUT,
        NO_LOCATION,
        LOCATION_TIMEOUT,
        LOCATION_ERROR,
        DATABASE_ERROR,
        SYSTEM_NOT_READY,
        UNKNOWN_ERROR
    }

    data class SessionStats(
        val locationUpdates: Int,
        val successfulSyncs: Int,
        val failedSyncs: Int,
        val sessionDuration: Long,
        val successRate: Int
    )
}