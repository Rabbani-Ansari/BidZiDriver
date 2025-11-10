package com.bidzidriver.app
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import com.bidzidriver.app.location.DriverPreferences
import com.bidzidriver.app.supabase.CounterOfferResponse
import com.bidzidriver.app.supabase.RideBooking
import com.bidzidriver.app.supabase.RideRequest
import com.bidzidriver.app.supabase.RideRequestData
import com.bidzidriver.app.supabase.UserProfile
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

// ============================================
// RIDE REQUEST VIEWMODEL - FIXED & OPTIMIZED
// ============================================


class RideRequestViewModel(
    private val repository: DriverRideRepository = DriverRideRepository()
) : ViewModel() {

    companion object {
        private const val TAG = "RideRequestViewModel"
        private const val COUNTER_OFFER_MIN_PERCENTAGE = 0.8
        private const val COUNTER_OFFER_MAX_PERCENTAGE = 1.5
        private const val RETRY_DELAY_MS = 5000L
        private const val DEBOUNCE_MS = 300L
    }

    private val driverId: String
        get() = DriverPreferences.getDriverId()

    // ========== STATE MANAGEMENT ==========

    private val _uiState = MutableStateFlow<RideRequestUiState>(RideRequestUiState.Idle)
    val uiState: StateFlow<RideRequestUiState> = _uiState.asStateFlow()

    // ✅ OPTIMIZED: Use ConflatedBroadcastChannel pattern for better performance
    private val _activeRideRequests = MutableStateFlow<List<RideRequest>>(emptyList())
    val activeRideRequests: StateFlow<List<RideRequest>> = _activeRideRequests
        .debounce(DEBOUNCE_MS) // Prevent rapid updates
        .distinctUntilChanged() // Only emit when list actually changes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _events = MutableSharedFlow<RideRequestEvent>(
        replay = 0,
        extraBufferCapacity = 20, // Increased buffer
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<RideRequestEvent> = _events.asSharedFlow()

    // ✅ OPTIMIZED: Thread-safe processing state
    private val processingRides = ConcurrentHashMap.newKeySet<String>()
    private val rideRequestsCache = ConcurrentHashMap<String, RideRequest>()

    private var rideRequestsJob: Job? = null
    private var counterOffersJob: Job? = null

    init {
        loadPendingRideRequests()
        startRealtimeListeners()
    }

    // ========== INITIAL LOAD ==========

    private fun loadPendingRideRequests() {
        viewModelScope.launch {
            _uiState.value = RideRequestUiState.Loading

            repository.getPendingRideRequests(driverId)
                .onSuccess { requests ->
                    Log.d(TAG, "📋 Loaded ${requests.size} pending rides")

                    // Process all rides in parallel
                    requests.forEach { rideRequestData ->
                        launch {
                            processRideRequestData(rideRequestData, isNewRide = false)
                        }
                    }

                    _uiState.value = RideRequestUiState.Idle
                }
                .onFailure { error ->
                    Log.e(TAG, "❌ Error loading: ${error.message}")
                    _uiState.value = RideRequestUiState.Error(
                        "Failed to load ride requests"
                    )
                    delay(3000)
                    _uiState.value = RideRequestUiState.Idle
                }
        }
    }

    // ========== REALTIME LISTENERS - OPTIMIZED ==========

    private fun startRealtimeListeners() {
        // Cancel existing jobs
        rideRequestsJob?.cancel()
        counterOffersJob?.cancel()

        // ✅ OPTIMIZED: Better error handling and reconnection
        rideRequestsJob = viewModelScope.launch {
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries && isActive) {
                try {
                    repository.observeRideRequests(driverId)
                        .retryWhen { cause, attempt ->
                            if (attempt < 3) {
                                Log.e(TAG, "🔄 Retry ride_requests (#${attempt + 1}): ${cause.message}")
                                delay(RETRY_DELAY_MS * (attempt + 1))
                                true
                            } else {
                                false
                            }
                        }
                        .catch { e ->
                            Log.e(TAG, "❌ Ride requests failed: ${e.message}", e)
                            retryCount++
                            if (retryCount < maxRetries) {
                                delay(RETRY_DELAY_MS * retryCount)
                            }
                        }
                        .collect { action ->
                            handleRideRequestEvent(action)
                        }

                    // If we get here, connection was successful
                    retryCount = 0
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ride requests error: ${e.message}", e)
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(RETRY_DELAY_MS * retryCount)
                    }
                }
            }

            if (retryCount >= maxRetries) {
                _events.emit(RideRequestEvent.ConnectionLost)
                Log.e(TAG, "❌ Max retries reached for ride_requests")
            }
        }

        // ✅ OPTIMIZED: Counter offers listener
        counterOffersJob = viewModelScope.launch {
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries && isActive) {
                try {
                    repository.observeCounterOffers(driverId)
                        .retryWhen { cause, attempt ->
                            if (attempt < 3) {
                                Log.e(TAG, "🔄 Retry counter_offers (#${attempt + 1}): ${cause.message}")
                                delay(RETRY_DELAY_MS * (attempt + 1))
                                true
                            } else {
                                false
                            }
                        }
                        .catch { e ->
                            Log.e(TAG, "❌ Counter offers failed: ${e.message}", e)
                            retryCount++
                            if (retryCount < maxRetries) {
                                delay(RETRY_DELAY_MS * retryCount)
                            }
                        }
                        .collect { action ->
                            handleCounterOfferEvent(action)
                        }

                    retryCount = 0
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Counter offers error: ${e.message}", e)
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(RETRY_DELAY_MS * retryCount)
                    }
                }
            }

            if (retryCount >= maxRetries) {
                Log.e(TAG, "❌ Max retries reached for counter_offers")
            }
        }
    }

    // ========== EVENT HANDLERS ==========

    private suspend fun handleRideRequestEvent(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                try {
                    val data = action.decodeRecord<RideRequestData>()
                    Log.d(TAG, "🆕 INSERT: ${data.ride_id}")
                    processRideRequestData(data, isNewRide = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding INSERT: ${e.message}", e)
                }
            }

            is PostgresAction.Update -> {
                try {
                    val data = action.decodeRecord<RideRequestData>()
                    Log.d(TAG, "🔄 UPDATE: ${data.ride_id}, status=${data.status}")
                    handleRideRequestUpdate(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding UPDATE: ${e.message}", e)
                }
            }

            is PostgresAction.Delete -> {
                try {
                    val oldData = action.decodeOldRecord<RideRequestData>()
                    Log.d(TAG, "🗑️ DELETE: ${oldData.ride_id}")
                    removeRideRequest(oldData.ride_id)
                    _events.emit(RideRequestEvent.RideCancelled(oldData.ride_id))
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding DELETE: ${e.message}", e)
                }
            }

            else -> {
                Log.d(TAG, "⚠️ Unhandled action: ${action::class.simpleName}")
            }
        }
    }

    private suspend fun handleRideRequestUpdate(data: RideRequestData) {
        when (data.status) {
            "pending" -> {
                // ✅ CRITICAL: Always process pending status
                // This ensures UI updates even if ride exists
                Log.d(TAG, "🔄 Pending status: ${data.ride_id}")
                processRideRequestData(data, isNewRide = false)
            }
            "accepted" -> {
                Log.d(TAG, "✅ Accepted status: ${data.ride_id}")
                checkIfRideConfirmedForDriver(data.ride_id)
            }
            "rejected", "expired", "cancelled" -> {
                Log.d(TAG, "❌ Terminal status (${data.status}): ${data.ride_id}")
                removeRideRequest(data.ride_id)
            }
            "counter_offered" -> {
                Log.d(TAG, "💰 Counter offered: ${data.ride_id}")
                // Keep in queue, waiting for response
            }
            else -> {
                Log.d(TAG, "⚠️ Unknown status: ${data.status} for ${data.ride_id}")
            }
        }
    }

    private suspend fun handleCounterOfferEvent(action: PostgresAction) {
        if (action !is PostgresAction.Update) return

        try {
            val counterOffer = action.decodeRecord<CounterOfferResponse>()

            if (counterOffer.driverId != driverId || counterOffer.offeredBy != "driver") {
                return
            }

            Log.d(TAG, "💰 Counter offer event: ${counterOffer.status} for ${counterOffer.rideId}")

            when (counterOffer.status) {
                "accepted" -> handleCounterOfferAccepted(counterOffer)
                "rejected" -> {
                    removeRideRequest(counterOffer.rideId)
                    _events.emit(RideRequestEvent.CounterOfferRejected)
                }
                "expired" -> {
                    removeRideRequest(counterOffer.rideId)
                    _events.emit(RideRequestEvent.CounterOfferExpired)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling counter offer: ${e.message}", e)
        }
    }

    private suspend fun handleCounterOfferAccepted(counterOffer: CounterOfferResponse) {
        delay(500) // Database consistency delay

        repository.isRideConfirmedForDriver(counterOffer.rideId, driverId)
            .onSuccess { isConfirmed ->
                if (isConfirmed) {
                    repository.getRideBooking(counterOffer.rideId)
                        .onSuccess { booking ->
                            removeRideRequest(counterOffer.rideId)
                            _events.emit(
                                RideRequestEvent.CounterOfferAcceptedAndConfirmed(booking)
                            )
                        }
                } else {
                    _events.emit(RideRequestEvent.CounterOfferAccepted)
                }
            }
    }

    // ========== PROCESS RIDE REQUESTS - OPTIMIZED ==========

    private suspend fun processRideRequestData(
        rideRequestData: RideRequestData,
        isNewRide: Boolean
    ) {
        val rideId = rideRequestData.ride_id

        // ✅ OPTIMIZED: Allow reprocessing but prevent duplicate parallel processing
        if (!processingRides.add(rideId)) {
            Log.d(TAG, "⏸️ Already processing: $rideId")
            return
        }

        try {
            val requestId = rideRequestData.id
            if (requestId == null) {
                Log.e(TAG, "❌ No request ID for: $rideId")
                return
            }

            // Fetch booking data
            val booking = repository.getRideBooking(rideId).getOrNull()
            if (booking == null) {
                Log.e(TAG, "❌ No booking found: $rideId")
                return
            }

            // Fetch user profile (optional)
            val userProfile = repository.getUserProfile(booking.userId).getOrNull()

            // Create ride request object
            val rideRequest = createRideRequest(rideRequestData, booking, userProfile)

            // Cache it
            rideRequestsCache[rideId] = rideRequest

            // Add to active list
            addOrUpdateRideRequest(rideRequest, isNewRide)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing ride $rideId: ${e.message}", e)
        } finally {
            processingRides.remove(rideId)
        }
    }

    private fun createRideRequest(
        rideRequestData: RideRequestData,
        booking: RideBooking,
        userProfile: UserProfile?
    ): RideRequest {
        val driverLocation = DriverPreferences.getCurrentLocation()
        val distanceToPickup = calculateDistance(
            driverLocation.latitude, driverLocation.longitude,
            rideRequestData.pickup_latitude, rideRequestData.pickup_longitude
        )

        val sentAtMillis = try {
            rideRequestData.sent_at?.let {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(it)?.time
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing timestamp: ${e.message}")
            System.currentTimeMillis()
        }

        return RideRequest(
            rideRequestId = rideRequestData.id ?: 0L,
            rideId = booking.id,
            userId = booking.userId,
            userName = userProfile?.name ?: "User",
            userInitial = userProfile?.profileInitial ?: "U",
            userRating = userProfile?.rating ?: 0.0,
            totalRides = userProfile?.totalRides ?: 0,
            pickupAddress = booking.pickupAddress,
            pickupLat = booking.pickupLat,
            pickupLng = booking.pickupLng,
            dropAddress = booking.dropAddress,
            dropLat = booking.dropLat,
            dropLng = booking.dropLng,
            vehicleType = booking.vehicleType,
            distanceKm = booking.distanceKm,
            distanceToPickup = distanceToPickup,
            bidAmount = rideRequestData.bid_price,
            note = booking.note,
            sentAt = sentAtMillis
        )
    }

    /**
     * ✅ OPTIMIZED: Smart list update that triggers observers properly
     */
    private suspend fun addOrUpdateRideRequest(rideRequest: RideRequest, isNewRide: Boolean) {
        val currentList = _activeRideRequests.value

        // Check if ride already exists
        val existingIndex = currentList.indexOfFirst { it.rideId == rideRequest.rideId }

        val newList = if (existingIndex >= 0) {
            // Update existing ride
            Log.d(TAG, "🔄 Updating ride at index $existingIndex: ${rideRequest.rideId}")
            currentList.toMutableList().apply {
                set(existingIndex, rideRequest)
            }
        } else {
            // Add new ride
            Log.d(TAG, "➕ Adding new ride: ${rideRequest.rideId}")
            currentList + rideRequest
        }

        // Sort by distance (closest first)
        val sortedList = newList.sortedBy { it.distanceToPickup }

        // ✅ CRITICAL: Always assign new list reference to trigger StateFlow
        _activeRideRequests.value = sortedList

        Log.d(TAG, "📊 Queue updated: ${sortedList.size} rides, isNew=$isNewRide")

        // Emit event only for truly new rides from realtime
        if (isNewRide && existingIndex < 0) {
            _events.emit(RideRequestEvent.NewRideAvailable(rideRequest))
            Log.d(TAG, "📢 Emitted NewRideAvailable event")
        }
    }

    // ========== USER ACTIONS ==========

    fun acceptRide(rideRequest: RideRequest) {
        viewModelScope.launch {
            _uiState.value = RideRequestUiState.AcceptingRide(rideRequest.rideId)

            val eta = calculateETA(rideRequest.distanceToPickup)

            repository.acceptRide(
                rideRequestId = rideRequest.rideRequestId,
                rideId = rideRequest.rideId,
                driverId = driverId,
                bidAmount = rideRequest.bidAmount,
                estimatedEta = eta
            ).fold(
                onSuccess = {
                    Log.d(TAG, "✅ Accept API success: ${rideRequest.rideId}")
                    delay(500)
                    checkRideConfirmation(rideRequest)
                },
                onFailure = { error ->
                    Log.e(TAG, "❌ Accept failed: ${error.message}")
                    _uiState.value = RideRequestUiState.Error(
                        "Failed to accept ride: ${error.message ?: "Unknown error"}"
                    )
                    delay(2000)
                    _uiState.value = RideRequestUiState.Idle
                }
            )
        }
    }

    fun rejectRide(rideRequest: RideRequest) {
        viewModelScope.launch {
            Log.d(TAG, "❌ Rejecting: ${rideRequest.rideId}")

            repository.rejectRide(
                rideRequestId = rideRequest.rideRequestId,
                rideId = rideRequest.rideId,
                driverId = driverId
            ).fold(
                onSuccess = {
                    Log.d(TAG, "✅ Reject API success")
                    removeRideRequest(rideRequest.rideId)
                    _events.emit(RideRequestEvent.RideRejected(rideRequest.rideId))
                },
                onFailure = { error ->
                    Log.e(TAG, "⚠️ Reject API failed: ${error.message}")
                    // Remove from queue anyway
                    removeRideRequest(rideRequest.rideId)
                }
            )
        }
    }

    fun submitCounterOffer(
        rideRequest: RideRequest,
        newAmount: Double,
        message: String? = null
    ) {
        viewModelScope.launch {
            if (!isValidCounterOffer(newAmount, rideRequest.bidAmount.toDouble())) {
                _uiState.value = RideRequestUiState.Error(
                    "Counter offer must be 80-150% of original amount"
                )
                delay(2000)
                _uiState.value = RideRequestUiState.Idle
                return@launch
            }

            _uiState.value = RideRequestUiState.SubmittingCounterOffer

            repository.submitCounterOffer(
                rideRequestId = rideRequest.rideRequestId,
                rideId = rideRequest.rideId,
                driverId = driverId,
                userId = rideRequest.userId,
                newAmount = newAmount,
                originalAmount = rideRequest.bidAmount.toDouble(),
                message = message
            ).fold(
                onSuccess = {
                    Log.d(TAG, "✅ Counter offer submitted")
                    _uiState.value = RideRequestUiState.CounterOfferSubmitted
                    _events.emit(RideRequestEvent.CounterOfferSent)
                    delay(1500)
                    _uiState.value = RideRequestUiState.Idle
                },
                onFailure = { error ->
                    Log.e(TAG, "❌ Counter offer failed: ${error.message}")
                    _uiState.value = RideRequestUiState.Error(
                        "Failed to submit counter offer: ${error.message}"
                    )
                    delay(2000)
                    _uiState.value = RideRequestUiState.Idle
                }
            )
        }
    }

    // ========== HELPER FUNCTIONS ==========

    private suspend fun checkRideConfirmation(rideRequest: RideRequest) {
        repository.isRideConfirmedForDriver(rideRequest.rideId, driverId)
            .onSuccess { isConfirmed ->
                if (isConfirmed) {
                    repository.getRideBooking(rideRequest.rideId)
                        .onSuccess { booking ->
                            Log.d(TAG, "🎉 Ride confirmed: ${booking.id}")
                            _uiState.value = RideRequestUiState.RideAccepted(booking)
                            removeRideRequest(rideRequest.rideId)
                            _events.emit(RideRequestEvent.RideAcceptedSuccess(booking))
                        }
                        .onFailure {
                            Log.w(TAG, "⏳ Confirmed but booking fetch failed")
                            _uiState.value = RideRequestUiState.WaitingForConfirmation
                            _events.emit(RideRequestEvent.ResponseSentWaitingConfirmation)
                        }
                } else {
                    Log.d(TAG, "⏳ Not yet confirmed")
                    _uiState.value = RideRequestUiState.WaitingForConfirmation
                    _events.emit(RideRequestEvent.ResponseSentWaitingConfirmation)
                }
            }
            .onFailure { error ->
                Log.e(TAG, "❌ Check confirmation failed: ${error.message}")
                _uiState.value = RideRequestUiState.WaitingForConfirmation
                _events.emit(RideRequestEvent.ResponseSentWaitingConfirmation)
            }
    }

    private suspend fun checkIfRideConfirmedForDriver(rideId: String) {
        repository.isRideConfirmedForDriver(rideId, driverId)
            .onSuccess { isConfirmed ->
                if (isConfirmed) {
                    repository.getRideBooking(rideId)
                        .onSuccess { booking ->
                            Log.d(TAG, "✅ Confirmed for driver: $rideId")
                            removeRideRequest(rideId)
                            _events.emit(RideRequestEvent.RideConfirmedForDriver(booking))
                        }
                } else {
                    Log.d(TAG, "❌ Taken by other: $rideId")
                    removeRideRequest(rideId)
                    _events.emit(RideRequestEvent.RideTakenByOther(rideId))
                }
            }
            .onFailure { error ->
                Log.e(TAG, "Error checking confirmation: ${error.message}")
                // Assume taken by other
                removeRideRequest(rideId)
            }
    }

    private fun removeRideRequest(rideId: String) {
        Log.d(TAG, "🗑️ Removing: $rideId")

        // Remove from cache
        rideRequestsCache.remove(rideId)
        processingRides.remove(rideId)

        // Remove from active list
        _activeRideRequests.update { current ->
            current.filter { it.rideId != rideId }
        }
    }

    private fun isValidCounterOffer(newAmount: Double, originalAmount: Double): Boolean {
        return newAmount >= originalAmount * COUNTER_OFFER_MIN_PERCENTAGE &&
                newAmount <= originalAmount * COUNTER_OFFER_MAX_PERCENTAGE
    }

    private fun calculateETA(distanceKm: Double): Int {
        val averageSpeedKmh = 30.0
        val hours = distanceKm / averageSpeedKmh
        return maxOf(5, (hours * 60).toInt())
    }

    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    fun resetUiState() {
        _uiState.value = RideRequestUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "💀 ViewModel cleared")
        rideRequestsJob?.cancel()
        counterOffersJob?.cancel()
        rideRequestsCache.clear()
        processingRides.clear()
    }
}

// ========== UI STATE & EVENTS ==========

sealed class RideRequestUiState {
    object Idle : RideRequestUiState()
    object Loading : RideRequestUiState()
    data class AcceptingRide(val rideId: String) : RideRequestUiState()
    object WaitingForConfirmation : RideRequestUiState()
    data class RideAccepted(val booking: RideBooking) : RideRequestUiState()
    object SubmittingCounterOffer : RideRequestUiState()
    object CounterOfferSubmitted : RideRequestUiState()
    data class Error(val message: String) : RideRequestUiState()
}

sealed class RideRequestEvent {
    data class NewRideAvailable(val rideRequest: RideRequest) : RideRequestEvent()
    object ResponseSentWaitingConfirmation : RideRequestEvent()
    data class RideAcceptedSuccess(val booking: RideBooking) : RideRequestEvent()
    data class RideConfirmedForDriver(val booking: RideBooking) : RideRequestEvent()
    data class RideCancelled(val rideId: String) : RideRequestEvent()
    data class RideTakenByOther(val rideId: String) : RideRequestEvent()
    data class RideRejected(val rideId: String) : RideRequestEvent()
    object CounterOfferSent : RideRequestEvent()
    object CounterOfferAccepted : RideRequestEvent()
    object CounterOfferRejected : RideRequestEvent()
    object CounterOfferExpired : RideRequestEvent()
    data class CounterOfferAcceptedAndConfirmed(val booking: RideBooking) : RideRequestEvent()
    object ConnectionLost : RideRequestEvent()
}