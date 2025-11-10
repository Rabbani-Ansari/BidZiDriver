package com.bidzidriver.app
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bidzidriver.app.databinding.BottomSheetDriverCounterOfferBinding
import com.bidzidriver.app.supabase.RideRequest

import com.google.android.material.bottomsheet.BottomSheetDialogFragment


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DriverCounterOfferBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDriverCounterOfferBinding? = null
    private val binding get() = _binding!!

    private lateinit var rideRequest: RideRequest
    private lateinit var driverId: String

    private val repository by lazy { DriverRideRepository() }
    private var isSubmitting = false

    private var onCounterSent: ((Double, String?) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDriverCounterOfferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupData()
        setupListeners()
        setupChips()

        // Auto-focus keyboard
        binding.etCounterPrice.requestFocus()
        showKeyboard()
    }

    // ========================================
    // SETUP UI
    // ========================================

    private fun setupData() {
        binding.apply {
            tvPassengerInitials.text = rideRequest.userInitial
            tvPassengerName.text = rideRequest.userName
            tvPassengerRating.text = rideRequest.userRating.toString()
            tvTotalRides.text = "${rideRequest.totalRides} rides"
            tvOriginalBid.text = "₹${rideRequest.bidAmount}"

            // Set suggested price hint (10% higher)
            val suggestedPrice = (rideRequest.bidAmount * 1.1).toInt()
            etCounterPrice.hint = suggestedPrice.toString()

            // Calculate min/max
            val minCounter = (rideRequest.bidAmount * 0.8).toInt()
            val maxCounter = (rideRequest.bidAmount * 1.5).toInt()
            tvSubtitle.text = "Counter between ₹$minCounter - ₹$maxCounter"
        }
    }

    private fun setupListeners() {
        binding.apply {
            btnClose.setOnClickListener { dismiss() }
            btnCancel.setOnClickListener { dismiss() }

            btnSendCounter.setOnClickListener {
                if (!isSubmitting) {
                    submitCounterOffer()
                }
            }

            btnClearPrice.setOnClickListener {
                etCounterPrice.text?.clear()
                btnClearPrice.visibility = View.GONE
            }

            etCounterPrice.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    btnClearPrice.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    updateButtonState()
                    updateValidationMessage(s)
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }

    private fun setupChips() {
        val originalBid = rideRequest.bidAmount

        // Driver suggestions: slightly higher prices
        val suggestions = listOf(
            (originalBid * 1.05).toInt(),
            (originalBid * 1.10).toInt(),
            (originalBid * 1.15).toInt(),
            (originalBid * 1.20).toInt()
        )

        binding.apply {
            val chips = listOf(chipPrice1, chipPrice2, chipPrice3, chipPrice4)

            chips.forEachIndexed { index, chip ->
                val price = suggestions[index]
                val extra = price - originalBid
                chip.text = "₹$price (+₹$extra)"

                chip.setOnClickListener {
                    etCounterPrice.setText(price.toString())
                    etCounterPrice.setSelection(etCounterPrice.text?.length ?: 0)
                }
            }
        }
    }

    // ========================================
    // VALIDATION & UI FEEDBACK
    // ========================================

    private fun updateValidationMessage(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            binding.tvNote.apply {
                this.text = "💡 Reasonable counters have higher acceptance rates"
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.yellow_light))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }
            return
        }

        val price = text.toString().toIntOrNull() ?: return
        val originalBid = rideRequest.bidAmount
        val minCounter = (originalBid * 0.8).toInt()
        val maxCounter = (originalBid * 1.5).toInt()

        when {
            price < minCounter -> {
                binding.tvNote.apply {
                    this.text = "⚠️ Counter too low. Minimum: ₹$minCounter"
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_light))
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
                }
            }
            price > maxCounter -> {
                binding.tvNote.apply {
                    this.text = "⚠️ Counter too high. Maximum: ₹$maxCounter"
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_light))
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
                }
            }
            price <= originalBid -> {
                binding.tvNote.apply {
                    this.text = "⚠️ Consider accepting the original bid instead"
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.orange_light))
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.orange))
                }
            }
            else -> {
                val extra = price - originalBid
                val percentage = ((extra.toFloat() / originalBid) * 100).toInt()
                binding.tvNote.apply {
                    this.text = "✓ Good counter! +₹$extra ($percentage% more)"
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.green_light))
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.green))
                }
            }
        }
    }

    private fun updateButtonState() {
        val counterPriceText = binding.etCounterPrice.text.toString()
        val price = counterPriceText.toIntOrNull()
        val originalBid = rideRequest.bidAmount
        val minCounter = (originalBid * 0.8).toInt()
        val maxCounter = (originalBid * 1.5).toInt()

        val isValid = price != null && price >= minCounter && price <= maxCounter

        binding.btnSendCounter.apply {
            isEnabled = isValid && !isSubmitting
            alpha = if (isValid && !isSubmitting) 1.0f else 0.5f
            setCardBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isValid && !isSubmitting) R.color.driver_green else R.color.gray_medium
                )
            )
        }
    }

    // ========================================
    // SUBMIT COUNTER OFFER USING REPOSITORY
    // ========================================

    private fun submitCounterOffer() {
        val counterPriceText = binding.etCounterPrice.text.toString()
        val counterPrice = counterPriceText.toDoubleOrNull()
        val message = binding.etMessage.text.toString().trim().ifEmpty { null }

        // Validate input
        if (counterPrice == null || counterPrice <= 0) {
            Toast.makeText(requireContext(), "Enter valid price", Toast.LENGTH_SHORT).show()
            return
        }

        val originalBid = rideRequest.bidAmount
        val minCounter = originalBid * 0.8
        val maxCounter = originalBid * 1.5

        if (counterPrice < minCounter || counterPrice > maxCounter) {
            Toast.makeText(
                requireContext(),
                "Counter must be between ₹${minCounter.toInt()} - ₹${maxCounter.toInt()}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Show loading state
        isSubmitting = true
        updateButtonState()
        binding.tvSendCounterText.text = "Sending..."

        lifecycleScope.launch {
            try {
                // Use repository to submit counter offer
                val result = repository.submitCounterOffer(
                    rideRequestId = rideRequest.rideRequestId,
                    rideId = rideRequest.rideId,
                    driverId = driverId,
                    userId = rideRequest.userId,
                    newAmount = counterPrice,
                    originalAmount = originalBid.toDouble(),  // Convert Int to Double
                    message = message
                )

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { counterOffer ->
                            Log.d("DriverCounterOfferBS", "Counter offer created successfully")

                            Toast.makeText(
                                requireContext(),
                                "Counter offer sent to ${rideRequest.userName}",
                                Toast.LENGTH_SHORT
                            ).show()

                            onCounterSent?.invoke(counterPrice, message)
                            dismiss()
                        },
                        onFailure = { exception ->
                            Log.e("DriverCounterOfferBS", "Error submitting counter offer", exception)

                            isSubmitting = false
                            updateButtonState()
                            binding.tvSendCounterText.text = "Send Counter"

                            Toast.makeText(
                                requireContext(),
                                "Failed to send counter: ${exception.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

            } catch (e: Exception) {
                Log.e("DriverCounterOfferBS", "Unexpected error", e)

                withContext(Dispatchers.Main) {
                    isSubmitting = false
                    updateButtonState()
                    binding.tvSendCounterText.text = "Send Counter"

                    Toast.makeText(
                        requireContext(),
                        "Failed to send counter: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ========================================
    // HELPER FUNCTIONS
    // ========================================

    private fun showKeyboard() {
        binding.etCounterPrice.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etCounterPrice, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    // ========================================
    // LIFECYCLE
    // ========================================

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ========================================
    // COMPANION OBJECT
    // ========================================

    companion object {
        fun newInstance(
            rideRequest: RideRequest,
            driverId: String,
            onCounterSent: (Double, String?) -> Unit
        ): DriverCounterOfferBottomSheet {
            return DriverCounterOfferBottomSheet().apply {
                this.rideRequest = rideRequest
                this.driverId = driverId
                this.onCounterSent = onCounterSent
            }
        }
    }
}