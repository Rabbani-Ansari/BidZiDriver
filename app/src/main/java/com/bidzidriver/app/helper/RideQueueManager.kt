package com.bidzidriver.app.helper
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
import java.util.concurrent.ConcurrentLinkedQueue

import android.view.View
import com.bidzidriver.app.R
import com.bidzidriver.app.RideRequestBottomSheet

/**
 * ✅ ROBUST SOLUTION: Centralized Ride Request Queue Manager
 * Handles all queue logic in one place with proper state management
 */
class RideQueueManager(
    private val activity: AppCompatActivity,
    private val onAccept: (RideRequest) -> Unit,
    private val onReject: (RideRequest) -> Unit,
    private val onCounterOffer: (RideRequest, Double, String?) -> Unit
) {
    private val TAG = "RideQueueManager"

    // ✅ Thread-safe queue
    private val rideQueue = ConcurrentLinkedQueue<RideRequest>()

    // ✅ Processing state
    private val isProcessing = AtomicBoolean(false)
    private var currentBottomSheet: RideRequestBottomSheet? = null

    // ✅ Coroutine scope tied to activity lifecycle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ✅ Notification helpers
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var notificationSound: MediaPlayer? = null

    init {
        initNotificationSound()
    }

    private fun initNotificationSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            notificationSound = MediaPlayer.create(activity, uri)
            notificationSound?.setVolume(1.0f, 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init sound: ${e.message}")
        }
    }

    /**
     * ✅ Add new ride to queue
     */
    fun addRide(rideRequest: RideRequest, isNewRide: Boolean) {
        // Check if already in queue
        val exists = rideQueue.any { it.rideId == rideRequest.rideId }

        if (exists) {
            Log.d(TAG, "⏸️ Ride already in queue: ${rideRequest.rideId}")
            return
        }

        // Add to queue
        rideQueue.offer(rideRequest)
        Log.d(TAG, "➕ Added to queue: ${rideRequest.rideId} (Queue size: ${rideQueue.size})")

        // Notify if new ride
        if (isNewRide) {
            notifyNewRide(rideRequest)
        }

        // Process queue
        processQueue()
    }

    /**
     * ✅ Update existing ride in queue
     */
    fun updateRide(rideRequest: RideRequest) {
        val iterator = rideQueue.iterator()
        var found = false

        while (iterator.hasNext()) {
            val existing = iterator.next()
            if (existing.rideId == rideRequest.rideId) {
                iterator.remove()
                rideQueue.offer(rideRequest)
                found = true
                Log.d(TAG, "🔄 Updated ride: ${rideRequest.rideId}")
                break
            }
        }

        if (!found) {
            // Not in queue, add it
            addRide(rideRequest, isNewRide = false)
        }
    }

    /**
     * ✅ Remove ride from queue
     */
    fun removeRide(rideId: String) {
        val iterator = rideQueue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().rideId == rideId) {
                iterator.remove()
                Log.d(TAG, "🗑️ Removed from queue: $rideId (Queue size: ${rideQueue.size})")
                break
            }
        }

        // If current sheet is showing this ride, dismiss it
        if (currentBottomSheet?.getCurrentRideId() == rideId) {
            dismissCurrentSheet()
        }

        // Process next
        scheduleNextRide()
    }

    /**
     * ✅ Update entire queue (from ViewModel)
     */
    fun updateQueue(newRides: List<RideRequest>) {
        val currentRideId = currentBottomSheet?.getCurrentRideId()

        // Get IDs
        val newIds = newRides.map { it.rideId }.toSet()
        val oldIds = rideQueue.map { it.rideId }.toSet()

        // Find added rides
        val addedRides = newRides.filter { it.rideId !in oldIds }

        // Clear and rebuild queue
        rideQueue.clear()
        rideQueue.addAll(newRides)

        Log.d(TAG, "📋 Queue updated: ${rideQueue.size} rides, ${addedRides.size} new")

        // Notify for new rides
        addedRides.forEach { ride ->
            notifyNewRide(ride)
        }

        // If current ride was removed, dismiss sheet
        if (currentRideId != null && currentRideId !in newIds) {
            Log.d(TAG, "🗑️ Current ride removed: $currentRideId")
            dismissCurrentSheet()
        }

        // Process queue
        processQueue()
    }

    /**
     * ✅ Clear all rides (when going offline)
     */
    fun clearQueue() {
        rideQueue.clear()
        dismissCurrentSheet()
        isProcessing.set(false)
        Log.d(TAG, "🧹 Queue cleared")
    }

    /**
     * ✅ Notify driver of new ride
     */
    private fun notifyNewRide(ride: RideRequest) {
        // Vibrate
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }

        // Sound
        try {
            notificationSound?.apply {
                if (isPlaying) stop()
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sound error: ${e.message}")
        }

        // Snackbar
        val message = "🚕 New ride! ${String.format("%.1f", ride.distanceToPickup)}km away - ₹${ride.bidAmount}"
        showSnackbar(message, isNewRide = true)
    }

    /**
     * ✅ Process queue - show next ride if ready
     */
    private fun processQueue() {
        // Already processing or sheet visible
        if (isProcessing.get() || currentBottomSheet?.isVisible == true) {
            Log.d(TAG, "⏸️ Cannot process: processing=${isProcessing.get()}, visible=${currentBottomSheet?.isVisible}")
            return
        }

        // Queue empty
        val nextRide = rideQueue.peek()
        if (nextRide == null) {
            Log.d(TAG, "⏸️ Queue empty")
            return
        }

        // Activity check
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "⚠️ Activity not ready")
            return
        }

        // Set processing flag
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "⏸️ Already processing (race condition)")
            return
        }

        Log.d(TAG, "🎬 Processing ride: ${nextRide.rideId} (Queue: ${rideQueue.size})")

        // Show bottom sheet
        scope.launch {
            showBottomSheet(nextRide)
        }
    }

    /**
     * ✅ Show bottom sheet for ride
     */
    private suspend fun showBottomSheet(ride: RideRequest) {
        withContext(Dispatchers.Main) {
            try {
                val driverId = DriverPreferences.getDriverId()
                if (driverId.isEmpty()) {
                    Log.e(TAG, "❌ No driver ID")
                    isProcessing.set(false)
                    return@withContext
                }

                val sheet = RideRequestBottomSheet.newInstance(
                    rideRequest = ride,
                    driverId = driverId,
                    onAccept = { handleAccept(it) },
                    onReject = { handleReject(it) },
                    onCounterOffer = { r, amount, msg -> handleCounterOffer(r, amount, msg) },
                    onDismiss = { handleDismiss() }
                )

                sheet.show(activity.supportFragmentManager, "RideRequestBottomSheet_${ride.rideId}")
                currentBottomSheet = sheet

                Log.d(TAG, "✅ Bottom sheet shown: ${ride.rideId}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error showing sheet: ${e.message}", e)
                isProcessing.set(false)
                scheduleNextRide()
            }
        }
    }

    /**
     * ✅ Handle accept
     */
    private fun handleAccept(ride: RideRequest) {
        Log.d(TAG, "✅ Accept: ${ride.rideId}")
        onAccept(ride)
        // Don't remove from queue yet - ViewModel will handle via realtime update
    }

    /**
     * ✅ Handle reject
     */
    private fun handleReject(ride: RideRequest) {
        Log.d(TAG, "❌ Reject: ${ride.rideId}")
        removeRide(ride.rideId) // Remove immediately
        onReject(ride)
    }

    /**
     * ✅ Handle counter offer
     */
    private fun handleCounterOffer(ride: RideRequest, amount: Double, message: String?) {
        Log.d(TAG, "💰 Counter: ${ride.rideId} - ₹$amount")
        onCounterOffer(ride, amount, message)
        // Don't remove - wait for response
    }

    /**
     * ✅ Handle dismiss (critical for showing next ride)
     */
    private fun handleDismiss() {
        Log.d(TAG, "📱 Sheet dismissed")
        currentBottomSheet = null
        isProcessing.set(false)

        // Schedule next ride
        scheduleNextRide()
    }

    /**
     * ✅ Dismiss current sheet
     */
    private fun dismissCurrentSheet() {
        currentBottomSheet?.let { sheet ->
            try {
                if (sheet.isAdded && !sheet.isRemoving && sheet.isVisible) {
                    sheet.dismiss()
                    Log.d(TAG, "✅ Sheet dismissed programmatically")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error dismissing sheet: ${e.message}")
            }
        }
        currentBottomSheet = null
    }

    /**
     * ✅ Schedule next ride with delay
     */
    private fun scheduleNextRide() {
        scope.launch {
            delay(500) // Brief delay for smooth transition

            if (!activity.isFinishing && !activity.isDestroyed) {
                processQueue()
            }
        }
    }

    /**
     * ✅ Show snackbar
     */
    private fun showSnackbar(message: String, isNewRide: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return

        val rootView = activity.findViewById<View>(android.R.id.content)
        val duration = if (isNewRide) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT

        Snackbar.make(rootView, message, duration).apply {
            setBackgroundTint(
                ContextCompat.getColor(
                    activity,
                    if (isNewRide) R.color.accent_orange else R.color.driver_green
                )
            )
            setTextColor(ContextCompat.getColor(activity, R.color.white))
            animationMode = Snackbar.ANIMATION_MODE_SLIDE

            if (isNewRide) {
                setAction("View") { dismiss() }
                setActionTextColor(ContextCompat.getColor(activity, R.color.white))
            }

            show()
        }
    }

    /**
     * ✅ Get queue status
     */
    fun getQueueStatus(): String {
        return "Queue: ${rideQueue.size}, Processing: ${isProcessing.get()}, Sheet: ${currentBottomSheet?.isVisible}"
    }

    /**
     * ✅ Cleanup
     */
    fun cleanup() {
        scope.cancel()
        clearQueue()
        notificationSound?.release()
        notificationSound = null
        Log.d(TAG, "💀 Queue manager cleaned up")
    }
}