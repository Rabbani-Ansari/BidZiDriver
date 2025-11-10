package com.bidzidriver.app.supabase

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Counter offer for insertion (counter_offers table)
 */
@Serializable
data class CounterOffer(
    @SerialName("ride_id")
    val rideId: String,

    @SerialName("driver_id")
    val driverId: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("original_amount")
    val originalAmount: Double,

    @SerialName("counter_amount")
    val counterAmount: Double,

    @SerialName("offered_by")
    val offeredBy: String,

    @SerialName("status")
    val status: String,

    @SerialName("message")
    val message: String? = null
)

/**
 * Counter offer from database (counter_offers table)
 */
@Serializable
data class CounterOfferResponse(
    @SerialName("id")
    val id: String,

    @SerialName("ride_id")
    val rideId: String,

    @SerialName("driver_id")
    val driverId: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("original_amount")
    val originalAmount: Double,

    @SerialName("counter_amount")
    val counterAmount: Double,

    @SerialName("offered_by")
    val offeredBy: String,

    @SerialName("status")
    val status: String,

    @SerialName("message")
    val message: String? = null,

    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Driver location (not a database table)
 */
data class DriverLocation(
    val latitude: Double,
    val longitude: Double
)

/**
 * Driver ride response for insertion (driver_ride_responses table)
 */
@Serializable
data class DriverRideResponse(
    @SerialName("ride_id")
    val rideId: String,

    @SerialName("driver_id")
    val driverId: String,

    @SerialName("offered_price")
    val offeredPrice: Int,

    @SerialName("estimated_eta")
    val estimatedEta: Int,

    @SerialName("offer_type")
    val offerType: String,

    @SerialName("is_confirmed")
    val isConfirmed: Boolean = false
)

/**
 * Ride booking from database (ride_bookings table)
 */
@Serializable
data class RideBooking(
    @SerialName("id")
    val id: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("pickup_address")
    val pickupAddress: String,

    @SerialName("pickup_lat")
    val pickupLat: Double,

    @SerialName("pickup_lng")
    val pickupLng: Double,

    @SerialName("drop_address")
    val dropAddress: String,

    @SerialName("drop_lat")
    val dropLat: Double,

    @SerialName("drop_lng")
    val dropLng: Double,

    @SerialName("vehicle_type")
    val vehicleType: String,

    @SerialName("distance_km")
    val distanceKm: Double,

    @SerialName("bid")
    val bid: Int,

    @SerialName("note")
    val note: String? = null,

    @SerialName("status")
    val status: String,

    @SerialName("confirmed_driver_id")
    val confirmedDriverId: String? = null,

    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Ride booking status (partial model for status checks)
 */
@Serializable
data class RideBookingStatus(
    @SerialName("confirmed_driver_id")
    val confirmedDriverId: String? = null,

    @SerialName("status")
    val status: String
)

/**
 * Ride request data shown to driver
 * Not directly mapped to database - constructed from multiple tables
 */
// ============================================
// DATA MODELS - FIXED & OPTIMIZED
// ============================================


@Parcelize
data class RideRequest(
    val rideRequestId: Long, // Added - from ride_requests.id
    val rideId: String,
    val userId: String,
    val userName: String,
    val userInitial: String,
    val userRating: Double,
    val totalRides: Int,
    val pickupAddress: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropAddress: String,
    val dropLat: Double,
    val dropLng: Double,
    val vehicleType: String,
    val distanceKm: Double,
    val distanceToPickup: Double,
    val bidAmount: Int,
    val note: String? = null,
    val sentAt: Long // Added - for sorting/display
) : Parcelable

/**
 * Ride request data from database (ride_requests table)
 * This matches your database schema exactly
 */
@Serializable
data class RideRequestData(
    @SerialName("id")
    val id: Long,

    @SerialName("ride_id")
    val ride_id: String,

    @SerialName("driver_id")
    val driver_id: String,

    @SerialName("pickup_latitude")
    val pickup_latitude: Double,

    @SerialName("pickup_longitude")
    val pickup_longitude: Double,

    @SerialName("vehicle_type")
    val vehicle_type: String,

    @SerialName("bid_price")
    val bid_price: Int,

    @SerialName("status")
    val status: String = "pending",

    @SerialName("sent_at")
    val sent_at: String? = null, // Timestamp as ISO string

    @SerialName("responded_at")
    val responded_at: Long? = null, // bigint

    @SerialName("created_at")
    val created_at: String? = null,

    @SerialName("updated_at")
    val updated_at: String? = null
)

/**
 * User profile from database (user_profiles table)
 */
@Serializable
data class UserProfile(
    @SerialName("user_id")
    val userId: String,

    @SerialName("name")
    val name: String,

    @SerialName("phone_number")
    val phoneNumber: String,

    @SerialName("profile_initial")
    val profileInitial: String? = null,

    @SerialName("total_rides")
    val totalRides: Int = 0,

    @SerialName("rating")
    val rating: Double? = null,

    @SerialName("total_spent")
    val totalSpent: Int = 0
)