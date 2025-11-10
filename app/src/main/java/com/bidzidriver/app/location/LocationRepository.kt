package com.bidzidriver.app.location


import android.util.Log
import com.bidzidriver.app.MyApplication
import com.bidzidriver.app.auth.SessionUser
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


object LocationRepository {

    private const val TAG = "LocationRepository"
    private const val TIMEOUT_MS = 8000L

    // Optimized debouncing - 2 seconds
    private const val MIN_UPDATE_INTERVAL_MS = 2000L
    private var lastUpdateTime = 0L
    private var lastSyncedLocation: Pair<Double, Double>? = null
    private val minDistanceMeters = 10.0 // Only sync if moved 10+ meters

    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 1000L

    private val supabase = MyApplication.supabase

    /**
     * OPTIMIZED: Smart debouncing with distance + time checks
     */
    suspend fun upsertDriverLocation(
        latitude: Double,
        longitude: Double,
        isOnline: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()

            // Time-based debounce
            if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
                Log.d(TAG, "⏱️ Debounced (too soon)")
                return@withContext Result.success(Unit)
            }

            // Distance-based debounce
            lastSyncedLocation?.let { (lastLat, lastLng) ->
                val distance = calculateDistance(lastLat, lastLng, latitude, longitude)
                if (distance < minDistanceMeters) {
                    Log.d(TAG, "📏 Debounced (distance: ${String.format("%.1f", distance)}m)")
                    return@withContext Result.success(Unit)
                }
            }

            val driverId = SessionUser.currentUserId
            if (driverId.isEmpty() || driverId == "null") {
                Log.e(TAG, "❌ Invalid driver ID")
                return@withContext Result.failure(Exception("Invalid driver ID"))
            }

            Log.d(TAG, "📍 Syncing: (${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)})")

            retryWithExponentialBackoff(MAX_RETRIES) {
                withTimeout(TIMEOUT_MS) {
                    val location = DriverLocation(
                        driverId = driverId,
                        latitude = latitude,
                        longitude = longitude,
                        isOnline = isOnline,
                        lastUpdated = currentTime
                    )

                    supabase.from("driver_locations")
                        .upsert(location) {
                            onConflict = "driver_id"
                        }
                }
            }

            // Update sync tracking
            lastUpdateTime = currentTime
            lastSyncedLocation = Pair(latitude, longitude)

            Log.d(TAG, "✅ Synced successfully")
            Result.success(Unit)

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "⏰ Timeout after ${TIMEOUT_MS}ms")
            Result.failure(e)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Calculate distance between two coordinates in meters
     */
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    suspend fun updateDriverOnlineStatus(isOnline: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val driverId = SessionUser.currentUserId

                if (driverId.isEmpty() || driverId == "null") {
                    return@withContext Result.failure(Exception("Invalid driver ID"))
                }

                Log.d(TAG, "🔄 Status update: $isOnline")

                retryWithExponentialBackoff(MAX_RETRIES) {
                    withTimeout(TIMEOUT_MS) {
                        supabase.from("driver_locations")
                            .update({
                                set("is_online", isOnline)
                                set("last_updated", System.currentTimeMillis())
                            }) {
                                filter {
                                    eq("driver_id", driverId)
                                }
                            }
                    }
                }

                Log.d(TAG, "✅ Status updated")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Status failed: ${e.message}")
                Result.failure(e)
            }
        }

    private suspend fun <T> retryWithExponentialBackoff(
        maxRetries: Int,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val delayTime = RETRY_DELAY_MS * (1 shl attempt)
                    Log.w(TAG, "⚠️ Retry ${attempt + 1}/$maxRetries (delay: ${delayTime}ms)")
                    delay(delayTime)
                }
            }
        }

        throw lastException ?: Exception("Unknown error")
    }

    // Debug functions
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🧪 Testing connection...")

            supabase.from("driver_locations")
                .select()

            Log.d(TAG, "✅ Connection OK")
            Result.success("Connection successful")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection failed: ${e.message}")
            Result.failure(e)
        }
    }
}

@Serializable
data class DriverLocation(
    @SerialName("driver_id")
    val driverId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("is_online")
    val isOnline: Boolean,
    @SerialName("last_updated")
    val lastUpdated: Long
)