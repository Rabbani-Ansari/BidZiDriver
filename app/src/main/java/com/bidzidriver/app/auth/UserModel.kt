package com.bidzidriver.app.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val totalSpent: Int = 0,

    @SerialName("created_at")
    val createdAt: String? = null,

    @SerialName("updated_at")
    val updatedAt: String? = null
)

// For update operations
@Serializable
data class UserProfileUpdate(
    @SerialName("name")
    val name: String,

    @SerialName("phone_number")
    val phoneNumber: String,

    @SerialName("profile_initial")
    val profileInitial: String,

    @SerialName("updated_at")
    val updatedAt: String? = null
)
