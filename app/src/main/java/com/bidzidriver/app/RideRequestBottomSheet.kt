package com.bidzidriver.app

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.bidzidriver.app.R
import com.bidzidriver.app.databinding.RideRequestBottomSheetBinding
import com.bidzidriver.app.supabase.RideRequest
import kotlinx.coroutines.*
class RideRequestBottomSheet : BottomSheetDialogFragment() {

    private var _binding: RideRequestBottomSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var rideRequest: RideRequest
    private lateinit var driverId: String

    // ✅ Use coroutine instead of CountDownTimer
    private var countdownJob: Job? = null

    // ✅ Callbacks
    private var onAcceptCallback: ((RideRequest) -> Unit)? = null
    private var onRejectCallback: ((RideRequest) -> Unit)? = null
    private var onCounterOfferCallback: ((RideRequest, Double, String?) -> Unit)? = null
    private var onDismissCallback: (() -> Unit)? = null

    // ✅ Track user action to prevent duplicate calls
    private var actionTaken = false

    companion object {
        private const val TAG = "RideRequestBottomSheet"
        private const val ARG_RIDE_REQUEST = "ride_request"
        private const val ARG_DRIVER_ID = "driver_id"
        private const val TIMER_DURATION = 30000L

        fun newInstance(
            rideRequest: RideRequest,
            driverId: String,
            onAccept: (RideRequest) -> Unit,
            onReject: (RideRequest) -> Unit,
            onCounterOffer: (RideRequest, Double, String?) -> Unit,
            onDismiss: (() -> Unit)? = null
        ): RideRequestBottomSheet {
            return RideRequestBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_RIDE_REQUEST, rideRequest)
                    putString(ARG_DRIVER_ID, driverId)
                }
                this.onAcceptCallback = onAccept
                this.onRejectCallback = onReject
                this.onCounterOfferCallback = onCounterOffer
                this.onDismissCallback = onDismiss
            }
        }
    }

    /**
     * ✅ Get current ride ID (for MainActivity tracking)
     */
    fun getCurrentRideId(): String {
        return rideRequest.rideId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rideRequest = arguments?.getParcelable(ARG_RIDE_REQUEST)
            ?: throw IllegalStateException("RideRequest required")
        driverId = arguments?.getString(ARG_DRIVER_ID)
            ?: throw IllegalStateException("DriverId required")

        isCancelable = false
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        // ✅ CRITICAL: Configure behavior AFTER view is inflated
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog

            // ✅ CRITICAL: Find the bottom sheet view (FrameLayout with id: design_bottom_sheet)
            val bottomSheet = bottomSheetDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) as? FrameLayout

            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)

                // ✅ CRITICAL: Set height explicitly
                val displayMetrics = resources.displayMetrics
                val height = (displayMetrics.heightPixels * 0.95).toInt()

                val layoutParams = bottomSheet.layoutParams
                layoutParams.height = height
                bottomSheet.layoutParams = layoutParams

                // ✅ Configure behavior
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = height
                behavior.skipCollapsed = true
                behavior.isDraggable = false // Prevent dismissal by dragging
                behavior.isHideable = true

                // ✅ Prevent accidental dismissal
                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        when (newState) {
                            BottomSheetBehavior.STATE_DRAGGING,
                            BottomSheetBehavior.STATE_SETTLING,
                            BottomSheetBehavior.STATE_COLLAPSED,
                            BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                                // Force back to expanded
                                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                            }
                        }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // No-op
                    }
                })

                Log.d(TAG, "✅ Bottom sheet configured and expanded")
            } else {
                Log.e(TAG, "❌ Could not find bottom sheet view!")
            }
        }

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = RideRequestBottomSheetBinding.inflate(inflater, container, false)

        // ✅ CRITICAL: Set minimum height on root view
        val displayMetrics = resources.displayMetrics
        val minHeight = (displayMetrics.heightPixels * 0.9).toInt()
        binding.root.minimumHeight = minHeight

        // ✅ Set layout params to match parent
        binding.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minHeight
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupClickListeners()

        // ✅ Start timer after UI is ready
        view.post {
            startCountdownTimer()
        }

        Log.d(TAG, "🎬 Bottom sheet view created and configured")
    }

    private fun setupUI() {
        with(binding) {
            // Passenger info
            driverName.text = rideRequest.userName
            driverInitial.text = rideRequest.userInitial
            driverRating.text = String.format(
                "%.1f (%d rides)",
                rideRequest.userRating,
                rideRequest.totalRides
            )

            // Route preview
            tvRoutePreview.text = String.format(
                "%.1f km to pickup • %.1f km trip",
                rideRequest.distanceToPickup,
                rideRequest.distanceKm
            )

            // Location details
            pickupLocation.text = rideRequest.pickupAddress
            pickupDistance.text = String.format("%.1f km away", rideRequest.distanceToPickup)
            dropLocation.text = rideRequest.dropAddress
            tripDistance.text = String.format("Trip: %.1f km", rideRequest.distanceKm)

            // Bid amount
            fareAmount.text = "₹${rideRequest.bidAmount}"

            // Estimated earnings
            val estimatedEarnings = (rideRequest.bidAmount * 0.85).toInt()
            estimatedEarningsText?.text = "You'll earn: ~₹$estimatedEarnings"

            // Note from rider
            if (rideRequest.note.isNullOrBlank()) {
                rideNoteCard?.visibility = View.GONE
            } else {
                rideNoteCard?.visibility = View.VISIBLE
                rideNoteText?.text = rideRequest.note
            }

            // Vehicle type
            vehicleTypeText?.text = rideRequest.vehicleType
        }
    }

    /**
     * ✅ IMPROVED: Coroutine-based timer using lifecycleScope
     * Automatically canceled when fragment is destroyed
     */
    private fun startCountdownTimer() {
        countdownJob?.cancel()

        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            while (isActive && System.currentTimeMillis() - startTime < TIMER_DURATION) {
                if (!isAdded || _binding == null) {
                    cancel()
                    return@launch
                }

                val elapsedTime = System.currentTimeMillis() - startTime
                val millisRemaining = TIMER_DURATION - elapsedTime
                val seconds = (millisRemaining / 1000).toInt()

                // Update UI
                binding.timerText.text = String.format("%02d:%02d", seconds / 60, seconds % 60)

                val progress = ((millisRemaining.toFloat() / TIMER_DURATION) * 100).toInt()
                binding.timerProgress?.progress = progress

                // ✅ Visual warning at 10 seconds
                if (seconds <= 10) {
                    binding.timerText.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.red)
                    )
                    binding.timerProgress?.progressTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.red)

                    // ✅ Vibrate on last 3 seconds
                    if (seconds in 1..3) {
                        vibratePhone()
                    }
                }

                delay(100) // Update every 100ms for smooth animation
            }

            // Timer finished
            if (isAdded && _binding != null && !actionTaken) {
                autoRejectRide()
            }
        }

        Log.d(TAG, "⏱️ Countdown timer started")
    }

    /**
     * ✅ Vibrate with permission checks
     */
    private fun vibratePhone() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = requireContext().getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        with(binding) {
            routePreviewCard.setOnClickListener {
                openRouteMapBottomSheet()
            }

            acceptButton.setOnClickListener {
                acceptRide()
            }

            rejectButton.setOnClickListener {
                rejectRide()
            }

            counterOfferButton?.setOnClickListener {
                openCounterOfferBottomSheet()
            }

            closeButton?.setOnClickListener {
                rejectRide()
            }
        }
    }

    private fun openRouteMapBottomSheet() {
        if (!isAdded || !::rideRequest.isInitialized) return

        val routeMapDialog = RouteMapDialogFragment.newInstance(
            pickupAddress = rideRequest.pickupAddress,
            dropAddress = rideRequest.dropAddress,
            pickupLat = rideRequest.pickupLat,
            pickupLng = rideRequest.pickupLng,
            dropLat = rideRequest.dropLat,
            dropLng = rideRequest.dropLng,
            pickupDistance = rideRequest.distanceToPickup,
            tripDistance = rideRequest.distanceKm
        )

        routeMapDialog.show(parentFragmentManager, "RouteMapDialog")
    }

    private fun acceptRide() {
        if (!isAdded || _binding == null || actionTaken) return

        actionTaken = true
        disableButtons()
        countdownJob?.cancel()

        binding.acceptButton.text = "ACCEPTING..."

        // ✅ Immediate feedback
        vibrateSuccess()

        onAcceptCallback?.invoke(rideRequest)

        Log.d(TAG, "✅ Ride accepted: ${rideRequest.rideId}")
    }

    private fun openCounterOfferBottomSheet() {
        if (!isAdded || actionTaken) return

        val counterOfferSheet = DriverCounterOfferBottomSheet.newInstance(
            rideRequest = rideRequest,
            driverId = driverId,
            onCounterSent = { amount, message ->
                handleCounterOfferSent(amount, message)
            }
        )

        counterOfferSheet.show(parentFragmentManager, "DriverCounterOfferBottomSheet")
    }

    private fun handleCounterOfferSent(amount: Double, message: String?) {
        if (!isAdded || _binding == null || actionTaken) return

        actionTaken = true
        disableButtons()
        countdownJob?.cancel()

        binding.counterOfferButton?.text = "COUNTER SENT"

        vibrateSuccess()

        onCounterOfferCallback?.invoke(rideRequest, amount, message)

        // ✅ Auto-dismiss after showing confirmation
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1000)
            if (isAdded) {
                dismiss()
            }
        }

        Log.d(TAG, "💰 Counter offer sent: ${rideRequest.rideId}")
    }

    private fun rejectRide() {
        if (!isAdded || actionTaken) return

        actionTaken = true
        countdownJob?.cancel()

        onRejectCallback?.invoke(rideRequest)

        Toast.makeText(requireContext(), "Ride rejected", Toast.LENGTH_SHORT).show()
        dismiss()

        Log.d(TAG, "❌ Ride rejected: ${rideRequest.rideId}")
    }

    private fun autoRejectRide() {
        if (!isAdded || actionTaken) return

        actionTaken = true
        countdownJob?.cancel()

        onRejectCallback?.invoke(rideRequest)

        Toast.makeText(
            requireContext(),
            "⏱️ Time expired - Ride auto-rejected",
            Toast.LENGTH_LONG
        ).show()

        dismiss()

        Log.d(TAG, "⏱️ Auto-rejected (timeout): ${rideRequest.rideId}")
    }

    /**
     * ✅ Success vibration pattern
     */
    private fun vibrateSuccess() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = requireContext().getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 50, 50, 100),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error: ${e.message}")
        }
    }

    fun showLoading() {
        if (_binding == null || !isAdded) {
            Log.w(TAG, "Cannot show loading - view not available")
            return
        }

        disableButtons()
        binding.progressBar?.visibility = View.VISIBLE
    }

    fun hideLoading() {
        if (_binding == null || !isAdded) {
            Log.w(TAG, "Cannot hide loading - view not available")
            return
        }

        enableButtons()
        binding.progressBar?.visibility = View.GONE
    }

    private fun disableButtons() {
        if (_binding == null || !isAdded) return

        binding.acceptButton.isEnabled = false
        binding.rejectButton.isEnabled = false
        binding.counterOfferButton?.isEnabled = false
        binding.closeButton?.isEnabled = false
    }

    private fun enableButtons() {
        if (_binding == null || !isAdded) return

        binding.acceptButton.isEnabled = true
        binding.acceptButton.text = "ACCEPT"

        binding.rejectButton.isEnabled = true

        binding.counterOfferButton?.isEnabled = true
        binding.counterOfferButton?.text = "COUNTER OFFER"

        binding.closeButton?.isEnabled = true
    }

    fun showError(message: String) {
        if (_binding == null || !isAdded) {
            Log.w(TAG, "Cannot show error - view not available: $message")
            return
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.red))
            .setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            .show()
    }

    /**
     * ✅ CRITICAL: Override dismiss to trigger callback
     */
    override fun dismiss() {
        Log.d(TAG, "📱 Dismissing: ${rideRequest.rideId}")
        super.dismiss()
    }

    /**
     * ✅ CRITICAL: Call onDismiss callback when sheet is dismissed
     */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        // ✅ Trigger next ride processing
        onDismissCallback?.invoke()

        Log.d(TAG, "✅ Dismissed & callback triggered: ${rideRequest.rideId}")
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        countdownJob = null

        // ✅ Clear callbacks to prevent memory leaks
        onAcceptCallback = null
        onRejectCallback = null
        onCounterOfferCallback = null
        onDismissCallback = null

        super.onDestroyView()
        _binding = null

        Log.d(TAG, "💀 Bottom sheet view destroyed")
    }
}