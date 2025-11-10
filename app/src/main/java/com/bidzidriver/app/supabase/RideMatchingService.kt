package com.bidzidriver.app.supabase

import io.github.jan.supabase.SupabaseClient
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

// Serialization
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import kotlin.math.*
import kotlin.to

// ==================== UPDATE RideMatchingService.kt ====================

// ==================== UPDATE RideMatchingService.kt ====================

class RideMatchingService(private val supabase: SupabaseClient) {

    private val haversineRadius = 6371.0

    companion object {
        private const val SEARCH_RADIUS_KM = 5.0
        private const val TAG = "RideMatchingService"
    }

    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return haversineRadius * c
    }

    suspend fun getPendingRideRequests(driverId: String): List<RideRequestData> {
        return try {
            supabase.postgrest["ride_requests"]
                .select {
                    filter {
                        eq("driver_id", driverId)
                        eq("status", "pending")
                    }
                    order(column = "sent_at", order = Order.DESCENDING)
                }
                .decodeList<RideRequestData>()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateRideRequestStatus(
        rideRequestId: Long,
        status: String
    ): Boolean {
        return try {
            supabase.postgrest["ride_requests"]
                .update(
                    mapOf(
                        "status" to status,
                        "responded_at" to System.currentTimeMillis()
                    )
                ) {
                    filter {
                        eq("id", rideRequestId)
                    }
                }
            Log.d(TAG, "Updated to: $status")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            false
        }
    }

    suspend fun getUnreadNotifications(userId: String): List<NotificationData> {
        return try {
            supabase.postgrest["notifications"]
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("is_read", false)
                    }
                    order(column = "created_at", order = Order.ASCENDING)
                    limit(20)
                }
                .decodeList<NotificationData>()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun markNotificationAsRead(notificationId: Long): Boolean {
        return try {
            supabase.postgrest["notifications"]
                .update(mapOf("is_read" to true)) {
                    filter {
                        eq("id", notificationId)
                    }
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            false
        }
    }
}

// ==================== UPDATED DATA CLASSES ====================

@Serializable
data class DriverLocationData(
    @SerialName("driver_id")
    val driver_id: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("vehicle_type")
    val vehicle_type: String,
    @SerialName("is_online")
    val is_online: Boolean,
    @SerialName("last_updated")
    val last_updated: Long
)

@Serializable
data class DriverLocationUpdateData(
    @SerialName("driver_id")
    val driver_id: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("vehicle_type")
    val vehicle_type: String,
    @SerialName("is_online")
    val is_online: Boolean,
    @SerialName("last_updated")
    val last_updated: Long
)



@Serializable
data class RideRequestMetadata(
    @SerialName("pickup_lat")
    val pickup_lat: Double? = null,
    @SerialName("pickup_lon")
    val pickup_lon: Double? = null,
    @SerialName("distance_km")
    val distance_km: Double? = null
)

@Serializable
data class NotificationData(
    val id: Long? = null,
    @SerialName("user_id")
    val user_id: String,
    @SerialName("driver_id")
    val driver_id: String? = null,
    @SerialName("ride_id")
    val ride_id: String? = null,  // CHANGED: Long -> String (to match UUID)
    val type: String,
    val title: String,
    val message: String,
    @SerialName("is_read")
    val is_read: Boolean = false,
    @SerialName("created_at")
    val created_at: String? = null,
    @SerialName("read_at")
    val read_at: String? = null,
    val metadata: RideRequestMetadata? = null
)
