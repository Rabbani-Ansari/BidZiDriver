package com.bidzidriver.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

import android.preference.PreferenceManager
import android.util.Log

import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.bidzidriver.app.databinding.FragmentRouteMapDialogBinding
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

class RouteMapDialogFragment : DialogFragment() {

    private var _binding: FragmentRouteMapDialogBinding? = null
    private val binding get() = _binding!!

    private var mapView: MapView? = null
    private lateinit var pickupAddress: String
    private lateinit var dropAddress: String
    private lateinit var pickupLatLng: GeoPoint
    private lateinit var dropLatLng: GeoPoint
    private lateinit var driverLatLng: GeoPoint

    private var pickupDistance: Double = 0.0
    private var tripDistance: Double = 0.0

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "RouteMapDialog"

        fun newInstance(
            pickupAddress: String,
            dropAddress: String,
            pickupLat: Double,
            pickupLng: Double,
            dropLat: Double,
            dropLng: Double,
            pickupDistance: Double,
            tripDistance: Double
        ): RouteMapDialogFragment {
            return RouteMapDialogFragment().apply {
                this.pickupAddress = pickupAddress
                this.dropAddress = dropAddress
                this.pickupLatLng = GeoPoint(pickupLat, pickupLng)
                this.dropLatLng = GeoPoint(dropLat, dropLng)

                val driverLocation = DriverPreferences.getCurrentLocation()
                this.driverLatLng = GeoPoint(driverLocation.latitude, driverLocation.longitude)

                this.pickupDistance = pickupDistance
                this.tripDistance = tripDistance

                Log.d(TAG, "📍 Route dialog initialized")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Full-screen dialog style
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogTheme)

        // Initialize OSMDroid
        Configuration.getInstance().load(
            requireContext(),
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        )

        Log.d(TAG, "✅ OSMDroid configured")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteMapDialogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupListeners()

        // Setup map after view is ready
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

        mapView?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)

            // ✅ Performance optimizations
            isTilesScaledToDpi = true
            setUseDataConnection(true)
            isVerticalMapRepetitionEnabled = false
            isHorizontalMapRepetitionEnabled = false

            // ✅ Set tile cache size (default is too small)
            val tileCacheSize = 512 * 1024 * 1024L // 512 MB
            Configuration.getInstance().tileFileSystemCacheMaxBytes = tileCacheSize
            Configuration.getInstance().tileFileSystemCacheTrimBytes = tileCacheSize * 0.9.toLong()

            controller.setZoom(13.0)
            controller.setCenter(driverLatLng)
        }

        addPickupMarker()
        addDropMarker()
        drawTaxiRoute()

        Log.d(TAG, "🗺️ Map setup complete")
    }

    private fun addPickupMarker() {
        val pickupMarker = Marker(mapView).apply {
            position = pickupLatLng
            title = "Pickup"
            snippet = pickupAddress

            // Create pickup icon programmatically
            icon = createPickupIcon()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView?.overlays?.add(pickupMarker)
        Log.d(TAG, "📍 Pickup marker added")
    }

    private fun addDropMarker() {
        val dropMarker = Marker(mapView).apply {
            position = dropLatLng
            title = "Drop"
            snippet = dropAddress

            // Create drop icon programmatically
            icon = createDropIcon()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView?.overlays?.add(dropMarker)
        Log.d(TAG, "📍 Drop marker added")
    }

    private fun createPickupIcon(): Drawable {
        val size = (32 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Draw blue circle with white border
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint)

        paint.color = Color.parseColor("#4285F4") // Google Blue
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6, paint)

        // Draw "P" text
        paint.color = Color.WHITE
        paint.textSize = size * 0.5f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.isFakeBoldText = true
        canvas.drawText("P", size / 2f, size / 2f + size * 0.18f, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun createDropIcon(): Drawable {
        val size = (32 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Draw red circle with white border
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint)

        paint.color = Color.parseColor("#EA4335") // Google Red
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6, paint)

        // Draw "D" text
        paint.color = Color.WHITE
        paint.textSize = size * 0.5f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.isFakeBoldText = true
        canvas.drawText("D", size / 2f, size / 2f + size * 0.18f, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun resizeDrawable(drawable: Drawable, widthDp: Int, heightDp: Int): Drawable {
        val density = resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return BitmapDrawable(resources, bitmap)
    }

    private fun drawTaxiRoute() {
        binding.progressBar?.visibility = View.VISIBLE

        coroutineScope.launch {
            try {
                val roadManager = OSRMRoadManager(requireContext(), "BidziDriver")

                // ✅ Fetch both routes in parallel for better performance
                val deferredPickupRoute = async(Dispatchers.IO) {
                    roadManager.getRoad(arrayListOf(driverLatLng, pickupLatLng))
                }

                val deferredDropRoute = async(Dispatchers.IO) {
                    roadManager.getRoad(arrayListOf(pickupLatLng, dropLatLng))
                }

                // Wait for both routes
                val toPickupRoute = deferredPickupRoute.await()
                val toDropRoute = deferredDropRoute.await()

                // Driver to Pickup - Purple dashed line
                if (toPickupRoute.mStatus == Road.STATUS_OK) {
                    drawRoutePolyline(
                        road = toPickupRoute,
                        color = Color.parseColor("#7B1FA2"), // Deep Purple
                        width = 12f,
                        isDashed = true,
                        label = "To Pickup"
                    )
                    Log.d(TAG, "✅ Driver → Pickup route drawn")
                }

                // Pickup to Drop - Dark Blue solid line
                if (toDropRoute.mStatus == Road.STATUS_OK) {
                    drawRoutePolyline(
                        road = toDropRoute,
                        color = Color.parseColor("#1976D2"), // Dark Blue
                        width = 14f,
                        isDashed = false,
                        label = "Trip Route"
                    )
                    Log.d(TAG, "✅ Pickup → Drop route drawn")
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

        mapView?.overlays?.add(polyline)
        mapView?.invalidate()
    }

    private fun drawFallbackRoute() {
        try {
            // Purple dashed line: Driver to Pickup
            val toPickupLine = Polyline().apply {
                setPoints(listOf(driverLatLng, pickupLatLng))
                outlinePaint.color = Color.parseColor("#7B1FA2") // Deep Purple
                outlinePaint.strokeWidth = 12f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
            }
            mapView?.overlays?.add(toPickupLine)

            // Dark Blue solid line: Pickup to Drop
            val toDropLine = Polyline().apply {
                setPoints(listOf(pickupLatLng, dropLatLng))
                outlinePaint.color = Color.parseColor("#1976D2") // Dark Blue
                outlinePaint.strokeWidth = 14f
            }
            mapView?.overlays?.add(toDropLine)

            mapView?.invalidate()
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

            mapView?.post {
                mapView?.zoomToBoundingBox(boundingBox, true, 100)
            }

            Log.d(TAG, "📍 Map centered on route")
        } catch (e: Exception) {
            Log.e(TAG, "Error centering: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        coroutineScope.cancel()
        mapView?.onDetach()
        mapView = null
        _binding = null
        Log.d(TAG, "💀 Route dialog destroyed")
    }
}