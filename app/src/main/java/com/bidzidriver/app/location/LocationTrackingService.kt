package com.bidzidriver.app.location


import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bidzidriver.app.MainActivity
import com.google.android.gms.location.*
import com.bidzidriver.app.R

import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private val TAG = "LocationTrackingService"

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val _locationFlow = MutableSharedFlow<Location>(
        replay = 1,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val locationFlow: SharedFlow<Location> = _locationFlow.asSharedFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Stats for notification
    private var updateCount = 0
    private var syncedCount = 0
    private var lastAccuracy = 0f
    private var lastUpdateTime = 0L

    // Adaptive accuracy
    private var currentMaxAccuracy = 100f
    private val absoluteMaxAccuracy = 300f
    private var noLocationCount = 0

    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand - Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "🛑 Stopping service")
                stopLocationUpdates()
                DriverPreferences.setOnlineStatus(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.d(TAG, "▶️ Starting service")
                DriverPreferences.setOnlineStatus(true)
                startForegroundService()
                startLocationUpdates()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "✅ Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Foreground error: ${e.message}")
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    updateCount++
                    noLocationCount = 0
                    lastAccuracy = location.accuracy
                    lastUpdateTime = System.currentTimeMillis()

                    Log.d(
                        TAG,
                        "📍 Update #$updateCount: (${location.latitude}, ${location.longitude}) " +
                                "±${location.accuracy}m [Provider: ${location.provider}]"
                    )

                    // Quality check
                    if (location.accuracy > currentMaxAccuracy) {
                        Log.w(TAG, "⚠️ Poor accuracy: ${location.accuracy}m > ${currentMaxAccuracy}m")
                        return
                    }

                    // Reset threshold on good location
                    if (location.accuracy < 100f) {
                        currentMaxAccuracy = 100f
                    }

                    // Update notification with stats
                    updateNotification(location)

                    // Emit to Fragment
                    serviceScope.launch {
                        _locationFlow.emit(location)
                        Log.d(TAG, "✅ Location emitted to Flow")
                    }

                    // Save last location
                    DriverPreferences.saveLastLocation(
                        location.latitude,
                        location.longitude,
                        System.currentTimeMillis()
                    )

                    // Sync to database
                    syncToDatabase(location)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    noLocationCount++
                    adjustAccuracyThreshold()
                    Log.w(TAG, "🛰️ Location unavailable (count: $noLocationCount)")
                } else {
                    Log.d(TAG, "✅ Location available")
                }
            }
        }
    }

    private fun adjustAccuracyThreshold() {
        when {
            noLocationCount >= 5 -> {
                currentMaxAccuracy = absoluteMaxAccuracy
                Log.w(TAG, "🔴 Accepting up to ${absoluteMaxAccuracy}m")
            }
            noLocationCount >= 3 -> {
                currentMaxAccuracy = 200f
                Log.w(TAG, "🟡 Accepting up to 200m")
            }
        }
    }

    private fun syncToDatabase(location: Location) {
        serviceScope.launch {
            try {
                val result = LocationRepository.upsertDriverLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    isOnline = true
                )

                if (result.isSuccess) {
                    syncedCount++
                    Log.d(TAG, "☁️ Synced ($syncedCount total)")
                } else {
                    Log.e(TAG, "❌ Sync failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Sync exception: ${e.message}")
            }
        }
    }

    private fun startLocationUpdates() {
        if (!hasPermissions()) {
            Log.e(TAG, "❌ No permissions")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        ).apply {
            setMinUpdateIntervalMillis(3000L)
            setMinUpdateDistanceMeters(5f)
            setWaitForAccurateLocation(false)
            setMaxUpdateDelayMillis(10000L)
        }.build()

        Log.d(TAG, "📍 Config: 5s interval, 5m distance, adaptive accuracy")

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Updates started")
            getLastKnownLocation()

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Start failed: ${e.message}")
            stopSelf()
        }
    }

    private fun getLastKnownLocation() {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    location?.let {
                        Log.d(TAG, "📌 Last known: ±${it.accuracy}m")

                        if (it.accuracy <= currentMaxAccuracy) {
                            serviceScope.launch {
                                _locationFlow.emit(it)
                            }
                            syncToDatabase(it)
                        }
                    } ?: Log.w(TAG, "⚠️ No last location")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Last location failed: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        Log.d(TAG, "🛑 Stopping updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.coroutineContext.cancelChildren()

        Log.d(TAG, "📊 Final Stats:")
        Log.d(TAG, "   Updates: $updateCount")
        Log.d(TAG, "   Synced: $syncedCount")
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when your location is being shared with passengers"
                setShowBadge(false)
                enableLights(true)
                lightColor = 0xFF00FF00.toInt() // Green light
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚕 You're Online")
            .setContentText("Location sharing active • Waiting for rides...")
            .setSmallIcon(R.drawable.ic_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_offline, "Go Offline", stopPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your location is being shared with nearby passengers. You'll receive ride requests soon."))
            .build()
    }

    private fun updateNotification(location: Location) {
        // Update notification every 30 seconds to avoid spam
        val timeSinceLastUpdate = System.currentTimeMillis() - lastUpdateTime
        if (timeSinceLastUpdate < 30000) return

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val timeAgo = getTimeAgo(System.currentTimeMillis() - lastUpdateTime)
        val accuracyText = when {
            lastAccuracy < 20 -> "Excellent"
            lastAccuracy < 50 -> "Good"
            lastAccuracy < 100 -> "Fair"
            else -> "Poor"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚕 You're Online")
            .setContentText("Location: $accuracyText (±${lastAccuracy.toInt()}m) • $syncedCount syncs")
            .setSmallIcon(R.drawable.ic_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_offline, "Go Offline", stopPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Location shared with passengers\nAccuracy: $accuracyText (±${lastAccuracy.toInt()}m)\nSynced: $syncedCount times\nLast update: $timeAgo"))
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun getTimeAgo(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 120 -> "1 min ago"
            seconds < 3600 -> "${seconds / 60} mins ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "🔗 Service bound")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 Service destroyed")
        stopLocationUpdates()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "location_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP_SERVICE"
    }
}