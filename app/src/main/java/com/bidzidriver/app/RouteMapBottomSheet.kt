package com.bidzidriver.app

import com.bidzidriver.app.databinding.BottomSheetRouteMapBinding

import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.bidzidriver.app.location.DriverPreferences
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class RouteMapBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRouteMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mapView: MapView
    private lateinit var pickupAddress: String
    private lateinit var dropAddress: String
    private lateinit var pickupLatLng: GeoPoint
    private lateinit var dropLatLng: GeoPoint
    private lateinit var driverLatLng: GeoPoint

    private var pickupDistance: Double = 0.0
    private var tripDistance: Double = 0.0

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "RouteMapBottomSheet"

        fun newInstance(
            pickupAddress: String,
            dropAddress: String,
            pickupLat: Double,
            pickupLng: Double,
            dropLat: Double,
            dropLng: Double,
            pickupDistance: Double,
            tripDistance: Double
        ): RouteMapBottomSheet {
            return RouteMapBottomSheet().apply {
                this.pickupAddress = pickupAddress
                this.dropAddress = dropAddress
                this.pickupLatLng = GeoPoint(pickupLat, pickupLng)
                this.dropLatLng = GeoPoint(dropLat, dropLng)

                // ✅ Get driver location from DriverPreferences (single source of truth)
                val driverLocation = DriverPreferences.getCurrentLocation()
                this.driverLatLng = GeoPoint(driverLocation.latitude, driverLocation.longitude)

                this.pickupDistance = pickupDistance
                this.tripDistance = tripDistance

                Log.d(TAG, "📍 Route sheet initialized - Driver: (${driverLocation.latitude}, ${driverLocation.longitude})")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenBottomSheetDialog)

        // Initialize OSMDroid configuration
        Configuration.getInstance().load(
            requireContext(),
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        )

        Log.d(TAG, "✅ OSMDroid configured")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val behavior = bottomSheetDialog.behavior  // ✅ FIXED: Use direct property

            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            behavior.isHideable = true

            val displayMetrics = resources.displayMetrics
            val height = (displayMetrics.heightPixels * 0.95).toInt()
            behavior.peekHeight = height

            Log.d(TAG, "✅ Bottom sheet configured and expanded")
        }

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRouteMapBinding.inflate(inflater, container, false)
        // Remove this line:
        // binding.root.minimumHeight = minHeight
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupListeners()

        view.post {
            setupMap()
        }
    }

    private fun setupUI() {
        binding.apply {
            tvPickupDistance.text = String.format("%.1f km to pickup", pickupDistance)
            tvTripDistance.text = String.format("%.1f km total trip", tripDistance)
            tvPickupLocation.text = pickupAddress
            tvDropLocation.text = dropAddress
        }

        Log.d(TAG, "📍 UI setup complete")
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            dismiss()
        }

        binding.btnCenterRoute?.setOnClickListener {
            centerMapOnRoute()
        }
    }

    private fun setupMap() {
        mapView = binding.mapView

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)

        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(driverLatLng)

        addDriverMarker()
        addPickupMarker()
        addDropMarker()

        drawTaxiRoute()

        Log.d(TAG, "🗺️ Map setup complete")
    }

    private fun addDriverMarker() {
        val driverMarker = Marker(mapView).apply {
            position = driverLatLng
            title = "Your Location"
            snippet = "Driver's current position"

            try {
                icon = resources.getDrawable(R.drawable.ic_car_marker, null)
            } catch (e: Exception) {
                Log.w(TAG, "Car icon not found")
            }

            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(driverMarker)
        Log.d(TAG, "🚗 Driver marker added")
    }

    private fun addPickupMarker() {
        val pickupMarker = Marker(mapView).apply {
            position = pickupLatLng
            title = "Pickup Location"
            snippet = pickupAddress

            try {
                icon = resources.getDrawable(R.drawable.ic_location_pin, null)
            } catch (e: Exception) {
                Log.w(TAG, "Pickup icon not found")
            }

            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(pickupMarker)
        Log.d(TAG, "📍 Pickup marker added")
    }

    private fun addDropMarker() {
        val dropMarker = Marker(mapView).apply {
            position = dropLatLng
            title = "Drop Location"
            snippet = dropAddress

            try {
                icon = resources.getDrawable(R.drawable.my_location, null)
            } catch (e: Exception) {
                Log.w(TAG, "Drop icon not found")
            }

            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(dropMarker)
        Log.d(TAG, "📍 Drop marker added")
    }

    private fun drawTaxiRoute() {
        binding.progressBar?.visibility = View.VISIBLE

        coroutineScope.launch {
            try {
                val roadManager = OSRMRoadManager(requireContext(), "BidziDriver")

                // Segment 1: Driver to Pickup
                Log.d(TAG, "🔄 Fetching route: Driver → Pickup")
                val toPickupRoute = withContext(Dispatchers.IO) {
                    roadManager.getRoad(arrayListOf(driverLatLng, pickupLatLng))
                }

                if (toPickupRoute.mStatus == Road.STATUS_OK) {
                    drawRoutePolyline(
                        road = toPickupRoute,
                        color = Color.parseColor("#4285F4"),
                        width = 12f,
                        isDashed = true,
                        label = "To Pickup"
                    )
                    Log.d(TAG, "✅ Driver → Pickup route drawn")
                } else {
                    Log.w(TAG, "⚠️ Route failed: ${toPickupRoute.mStatus}")
                }

                // Segment 2: Pickup to Drop
                Log.d(TAG, "🔄 Fetching route: Pickup → Drop")
                val toDropRoute = withContext(Dispatchers.IO) {
                    roadManager.getRoad(arrayListOf(pickupLatLng, dropLatLng))
                }

                if (toDropRoute.mStatus == Road.STATUS_OK) {
                    drawRoutePolyline(
                        road = toDropRoute,
                        color = Color.parseColor("#34A853"),
                        width = 12f,
                        isDashed = false,
                        label = "Trip Route"
                    )
                    Log.d(TAG, "✅ Pickup → Drop route drawn")
                } else {
                    Log.w(TAG, "⚠️ Route failed: ${toDropRoute.mStatus}")
                }

                centerMapOnRoute()
                binding.progressBar?.visibility = View.GONE
                Log.d(TAG, "✅ Route complete")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}", e)
                binding.progressBar?.visibility = View.GONE
                drawFallbackRoute()

                Toast.makeText(
                    requireContext(),
                    "Using simplified route view",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun drawRoutePolyline(
        road: Road,
        color: Int,
        width: Float,
        isDashed: Boolean,
        label: String
    ) {
        val polyline = Polyline().apply {
            setPoints(road.mRouteHigh)
            outlinePaint.color = color
            outlinePaint.strokeWidth = width

            if (isDashed) {
                outlinePaint.pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(20f, 10f), 0f
                )
            }

            title = label
        }

        mapView.overlays.add(polyline)
        mapView.invalidate()
    }

    private fun drawFallbackRoute() {
        try {
            // Straight line fallback
            val toPickupLine = Polyline().apply {
                setPoints(listOf(driverLatLng, pickupLatLng))
                outlinePaint.color = Color.parseColor("#4285F4")
                outlinePaint.strokeWidth = 10f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
            }
            mapView.overlays.add(toPickupLine)

            val toDropLine = Polyline().apply {
                setPoints(listOf(pickupLatLng, dropLatLng))
                outlinePaint.color = Color.parseColor("#34A853")
                outlinePaint.strokeWidth = 10f
            }
            mapView.overlays.add(toDropLine)

            mapView.invalidate()
            centerMapOnRoute()
            Log.d(TAG, "✅ Fallback routes drawn")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback error: ${e.message}")
        }
    }

    private fun centerMapOnRoute() {
        try {
            val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(
                listOf(driverLatLng, pickupLatLng, dropLatLng)
            )

            mapView.post {
                mapView.zoomToBoundingBox(boundingBox, true, 100)
            }

            Log.d(TAG, "📍 Map centered on route")
        } catch (e: Exception) {
            Log.e(TAG, "Error centering: ${e.message}")
        }
    }
    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        coroutineScope.cancel()
        if (::mapView.isInitialized) {
            mapView.onDetach()
        }
        _binding = null
        Log.d(TAG, "💀 Route map destroyed")
    }
}

