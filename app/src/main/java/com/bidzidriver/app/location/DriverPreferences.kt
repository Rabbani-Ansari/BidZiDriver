package com.bidzidriver.app.location

import android.content.Context
import android.content.SharedPreferences
import com.bidzidriver.app.auth.SessionUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Manages driver's online/offline state persistence
 */
object DriverPreferences {

    private const val PREFS_NAME = "driver_prefs"
    private const val KEY_IS_ONLINE = "is_online"
    private const val KEY_LAST_LATITUDE = "last_latitude"
    private const val KEY_LAST_LONGITUDE = "last_longitude"
    private const val KEY_LAST_UPDATE_TIME = "last_update_time"

    private lateinit var prefs: SharedPreferences

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load saved state
        _isOnline.value = prefs.getBoolean(KEY_IS_ONLINE, false)
    }

    fun setOnlineStatus(isOnline: Boolean) {
        _isOnline.value = isOnline
        prefs.edit().putBoolean(KEY_IS_ONLINE, isOnline).apply()
    }

    fun getOnlineStatus(): Boolean {
        return prefs.getBoolean(KEY_IS_ONLINE, false)
    }

    fun saveLastLocation(latitude: Double, longitude: Double, currentTimeMillis: Long) {
        prefs.edit()
            .putString(KEY_LAST_LATITUDE, latitude.toString())
            .putString(KEY_LAST_LONGITUDE, longitude.toString())
            .putLong(KEY_LAST_UPDATE_TIME, currentTimeMillis)
            .apply()
    }

    fun getLastLocation(): Triple<Double?, Double?, Long?>? {
        val lat = prefs.getString(KEY_LAST_LATITUDE, null)?.toDoubleOrNull()
        val lng = prefs.getString(KEY_LAST_LONGITUDE, null)?.toDoubleOrNull()
        val time = prefs.getLong(KEY_LAST_UPDATE_TIME, 0L)

        return if (lat != null && lng != null) {
            Triple(lat, lng, time)
        } else null
    }

    /**
     * Get current location as LatLng (fallback to default if not available)
     */
    fun getCurrentLocation(): LatLng {
        val lastLocation = getLastLocation()
        return if (lastLocation?.first != null && lastLocation.second != null) {
            LatLng(lastLocation.first!!, lastLocation.second!!)
        } else {
            // Fallback to default location (e.g., Pune, India)
            LatLng(18.5204, 73.8567)
        }
    }

    /**
     * Get driver ID (from Firebase Auth via SessionUser)
     */
    fun getDriverId(): String {
        return SessionUser.currentUserId
    }

    /**
     * Check if driver is authenticated
     */
    fun isAuthenticated(): Boolean {
        return SessionUser.isUserLoggedIn
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        _isOnline.value = false
    }
}

/**
 * Simple data class for location
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double
)

