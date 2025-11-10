package com.bidzidriver.app

import io.github.jan.supabase.SupabaseClient


import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import android.util.Log
import com.bidzidriver.app.supabase.CounterOffer
import com.bidzidriver.app.supabase.CounterOfferResponse
import com.bidzidriver.app.supabase.DriverRideResponse
import com.bidzidriver.app.supabase.RideBooking
import com.bidzidriver.app.supabase.RideBookingStatus
import com.bidzidriver.app.supabase.RideRequestData
import com.bidzidriver.app.supabase.UserProfile

import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.runBlocking

// ============================================
// DRIVER RIDE REPOSITORY - FIXED & OPTIMIZED
// ============================================

class DriverRideRepository(
    private val supabase: SupabaseClient = MyApplication.supabase
) {
    companion object {
        private const val TAG = "DriverRideRepository"
    }

    // ========== BASIC QUERIES ==========

    suspend fun getUserProfile(userId: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val profile = supabase.from("user_profiles")
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeSingle<UserProfile>()
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching user profile: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getRideBooking(rideId: String): Result<RideBooking> = withContext(Dispatchers.IO) {
        try {
            val booking = supabase.from("ride_bookings")
                .select {
                    filter { eq("id", rideId) }
                }
                .decodeSingle<RideBooking>()
            Result.success(booking)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching ride booking: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getPendingRideRequests(driverId: String): Result<List<RideRequestData>> =
        withContext(Dispatchers.IO) {
            try {
                val requests = supabase.from("ride_requests")
                    .select {
                        filter {
                            eq("driver_id", driverId)
                            eq("status", "pending")
                        }
                        order(column = "sent_at", order = Order.DESCENDING)
                    }
                    .decodeList<RideRequestData>()

                Log.d(TAG, "✅ Found ${requests.size} pending ride requests")
                Result.success(requests)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching pending ride requests: ${e.message}", e)
                Result.failure(e)
            }
        }

    // ========== REALTIME SUBSCRIPTIONS - CORRECTED ==========

    /**
     * ✅ FIXED: Proper realtime subscription with correct filter syntax
     *
     * NOTE: supabase-kt only supports ONE filter per subscription.
     * We filter by driver_id at database level, then filter by status client-side.
     */
    fun observeRideRequests(driverId: String): Flow<PostgresAction> = callbackFlow {
        val channelId = "ride_requests_$driverId"
        val channel = supabase.channel(channelId)

        Log.d(TAG, "🔄 Setting up ride_requests subscription for driver: $driverId")

        try {
            // ✅ STEP 1: Create the flow BEFORE subscribing
            // ✅ CORRECTED: Use filter() method, not property assignment
            val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "ride_requests"
                // ✅ Database-level filtering (only ONE filter supported)
                filter("driver_id", FilterOperator.EQ, driverId)
            }

            // ✅ STEP 2: Subscribe to the channel
            channel.subscribe()
            Log.d(TAG, "✅ Subscribed to $channelId")

            // ✅ STEP 3: Collect events
            changeFlow.collect { action ->
                when (action) {
                    is PostgresAction.Insert -> {
                        try {
                            val record = action.decodeRecord<RideRequestData>()
                            // ✅ Client-side filtering for status (since we can only have 1 DB filter)
                            if (record.driver_id == driverId && record.status == "pending") {
                                Log.d(TAG, "🆕 INSERT: Ride ${record.ride_id}")
                                trySend(action).isSuccess
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding INSERT: ${e.message}")
                        }
                    }
                    is PostgresAction.Update -> {
                        try {
                            val record = action.decodeRecord<RideRequestData>()
                            if (record.driver_id == driverId) {
                                Log.d(TAG, "🔄 UPDATE: Ride ${record.ride_id}, status=${record.status}")
                                trySend(action).isSuccess
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding UPDATE: ${e.message}")
                        }
                    }
                    is PostgresAction.Delete -> {
                        try {
                            val oldRecord = action.decodeOldRecord<RideRequestData>()
                            if (oldRecord.driver_id == driverId) {
                                Log.d(TAG, "🗑️ DELETE: Ride ${oldRecord.ride_id}")
                                trySend(action).isSuccess
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding DELETE: ${e.message}")
                        }
                    }
                    else -> {
                        Log.d(TAG, "⚠️ Unknown action type: ${action::class.simpleName}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Subscription error: ${e.message}", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "🔴 Closing $channelId subscription")
            runBlocking {
                try {
                    channel.unsubscribe()
                } catch (e: Exception) {
                    Log.e(TAG, "Error unsubscribing: ${e.message}")
                }
            }
        }
    }

    /**
     * ✅ FIXED: Counter offers realtime subscription with correct syntax
     */
    fun observeCounterOffers(driverId: String): Flow<PostgresAction> = callbackFlow {
        val channelId = "counter_offers_$driverId"
        val channel = supabase.channel(channelId)

        Log.d(TAG, "🔄 Setting up counter_offers subscription for driver: $driverId")

        try {
            // ✅ STEP 1: Create flow BEFORE subscribing
            val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "counter_offers"
                // ✅ Filter by driver_id at database level
                filter("driver_id", FilterOperator.EQ, driverId)
            }

            // ✅ STEP 2: Subscribe
            channel.subscribe()
            Log.d(TAG, "✅ Subscribed to $channelId")

            // ✅ STEP 3: Collect
            changeFlow.collect { action ->
                when (action) {
                    is PostgresAction.Insert, is PostgresAction.Update -> {
                        try {
                            val record = action.decodeRecord<CounterOfferResponse>()
                            if (record.driverId == driverId) {
                                Log.d(TAG, "💰 Counter offer change: ${record.rideId}, status=${record.status}")
                                trySend(action).isSuccess
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding counter offer: ${e.message}")
                        }
                    }
                    is PostgresAction.Delete -> {
                        try {
                            val oldRecord = action.decodeOldRecord<CounterOfferResponse>()
                            if (oldRecord.driverId == driverId) {
                                Log.d(TAG, "🗑️ Counter offer deleted: ${oldRecord.rideId}")
                                trySend(action).isSuccess
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error decoding DELETE: ${e.message}")
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Counter offers subscription error: ${e.message}", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "🔴 Closing $channelId subscription")
            runBlocking {
                try {
                    channel.unsubscribe()
                } catch (e: Exception) {
                    Log.e(TAG, "Error unsubscribing: ${e.message}")
                }
            }
        }
    }

    // ========== RIDE ACTIONS ==========

    suspend fun updateRideRequestStatus(
        rideRequestId: Long,
        status: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            supabase.from("ride_requests")
                .update(
                    mapOf("status" to status)
                ) {
                    filter { eq("id", rideRequestId) }
                }
            Log.d(TAG, "✅ Updated ride request $rideRequestId to: $status")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating ride request: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun acceptRide(
        rideRequestId: Long,
        rideId: String,
        driverId: String,
        bidAmount: Int,
        estimatedEta: Int
    ): Result<DriverRideResponse> = withContext(Dispatchers.IO) {
        try {
            updateRideRequestStatus(rideRequestId, "accepted")
                .onFailure { return@withContext Result.failure(it) }

            val response = DriverRideResponse(
                rideId = rideId,
                driverId = driverId,
                offeredPrice = bidAmount,
                estimatedEta = estimatedEta,
                offerType = "bid_accept",
                isConfirmed = false
            )
            supabase.from("driver_ride_responses").insert(response)

            Log.d(TAG, "✅ Ride accepted: $rideId")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting ride: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun rejectRide(
        rideRequestId: Long,
        rideId: String,
        driverId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            updateRideRequestStatus(rideRequestId, "rejected")

            val response = DriverRideResponse(
                rideId = rideId,
                driverId = driverId,
                offeredPrice = 0,
                estimatedEta = 0,
                offerType = "bid_rejected",
                isConfirmed = false
            )
            supabase.from("driver_ride_responses").insert(response)

            Log.d(TAG, "❌ Ride rejected: $rideId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting ride: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun submitCounterOffer(
        rideRequestId: Long,
        rideId: String,
        driverId: String,
        userId: String,
        newAmount: Double,
        originalAmount: Double,
        message: String? = null
    ): Result<CounterOffer> = withContext(Dispatchers.IO) {
        try {
            updateRideRequestStatus(rideRequestId, "counter_offered")
                .onFailure { return@withContext Result.failure(it) }

            val counterOffer = CounterOffer(
                rideId = rideId,
                driverId = driverId,
                userId = userId,
                originalAmount = originalAmount,
                counterAmount = newAmount,
                offeredBy = "driver",
                status = "pending",
                message = message
            )
            supabase.from("counter_offers").insert(counterOffer)

            Log.d(TAG, "💰 Counter offer submitted: $rideId - ₹$newAmount")
            Result.success(counterOffer)
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting counter offer: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun isRideConfirmedForDriver(
        rideId: String,
        driverId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val rideRequest = supabase.from("ride_requests")
                .select {
                    filter {
                        eq("ride_id", rideId)
                        eq("driver_id", driverId)
                        eq("status", "accepted")
                    }
                }
                .decodeSingleOrNull<RideRequestData>()

            val booking = supabase.from("ride_bookings")
                .select(columns = Columns.list("confirmed_driver_id", "status")) {
                    filter { eq("id", rideId) }
                }
                .decodeSingleOrNull<RideBookingStatus>()

            val isConfirmed = rideRequest != null &&
                    booking?.confirmedDriverId == driverId &&
                    booking.status == "confirmed"

            Result.success(isConfirmed)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking confirmation: ${e.message}")
            Result.failure(e)
        }
    }
}

/**
 * Get unread notifications for driver
 */
//suspend fun getUnreadNotifications(driverId: String): Result<List<NotificationData>> =
//    withContext(Dispatchers.IO) {
//        try {
//            val notifications = supabase.from("notifications")
//                .select {
//                    filter {
//                        eq("driver_id", driverId)
//                        eq("is_read", false)
//                    }
//                    order(column = "created_at", order = Order.DESCENDING)
//                    limit(20)
//                }
//                .decodeList<NotificationData>()
//
//            Result.success(notifications)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error fetching notifications: ${e.message}")
//            Result.failure(e)
//        }
//    }
//
///**
// * Mark notification as read
// */
//suspend fun markNotificationAsRead(notificationId: Long): Result<Boolean> =
//    withContext(Dispatchers.IO) {
//        try {
//            supabase.from("notifications")
//                .update(mapOf("is_read" to true)) {
//                    filter {
//                        eq("id", notificationId)
//                    }
//                }
//
//            Result.success(true)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error marking notification as read: ${e.message}")
//            Result.failure(e)
//        }
//    }