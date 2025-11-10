package com.bidzidriver.app.location

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bidzidriver.app.R

/**
 * Monitors location settings and provides alerts
 * Supports Android 8+ with proper notification channels
 * Compatible with API 24+
 */
/**
 * Monitors location settings and provides alerts
 * Supports Android 8+ with proper notification channels
 * Compatible with API 24+
 */
class LocationMonitor(private val context: Context) : DefaultLifecycleObserver {

    private val TAG = "LocationMonitor"

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Handler for delayed notification checks
    private val handler = Handler(Looper.getMainLooper())
    private var pendingNotificationCheck: Runnable? = null

    // State management
    private val _locationState = MutableStateFlow<LocationState>(LocationState.ENABLED)
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private val _networkState = MutableStateFlow<NetworkState>(NetworkState.CONNECTED)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var lastNotificationState: NotificationState? = null
    private var lastNetworkCheckTime = 0L
    private val NETWORK_CHECK_INTERVAL = 3000L // Check every 3 seconds when online

    // Track when issues first appeared (for grace periods)
    private var locationDisabledSince: Long? = null
    private var networkDisabledSince: Long? = null
    private val GRACE_PERIOD = 3000L // 3 seconds before showing notification

    // Notification IDs
    companion object {
        private const val ALERT_CHANNEL_ID = "location_alerts"
        private const val WARNING_CHANNEL_ID = "location_warnings"
        private const val INFO_CHANNEL_ID = "location_info"

        private const val NOTIFICATION_LOCATION_DISABLED = 2001
        private const val NOTIFICATION_GPS_DISABLED = 2002
        private const val NOTIFICATION_NETWORK_DISABLED = 2003
        private const val NOTIFICATION_POOR_ACCURACY = 2004
        private const val NOTIFICATION_BACKGROUND_RESTRICTION = 2005
        private const val NOTIFICATION_BATTERY_OPTIMIZATION = 2006
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    Log.d(TAG, "📍 Location provider changed")
                    checkLocationStatus()

                    // If driver is online, schedule immediate check + grace period check
                    if (DriverPreferences.getOnlineStatus()) {
                        updateNotifications()
                        scheduleGracePeriodCheck()
                    }
                }
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    Log.d(TAG, "🌐 Network connectivity changed")
                    checkNetworkStatus()

                    if (DriverPreferences.getOnlineStatus()) {
                        updateNotifications()
                        scheduleGracePeriodCheck()
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "🌐 Network available")
            networkDisabledSince = null // Reset grace period
            _networkState.value = NetworkState.CONNECTED

            if (DriverPreferences.getOnlineStatus()) {
                checkSystemHealth()
                dismissNetworkNotifications() // Auto-dismiss when recovered
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "🌐 Network lost")

            if (DriverPreferences.getOnlineStatus()) {
                if (networkDisabledSince == null) {
                    networkDisabledSince = System.currentTimeMillis()
                    Log.d(TAG, "⏰ Network grace period started")
                }
                _networkState.value = NetworkState.DISCONNECTED
                checkSystemHealth()
                scheduleGracePeriodCheck()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            val newState = when {
                !hasInternet -> NetworkState.NO_INTERNET
                !isValidated -> NetworkState.LIMITED
                else -> NetworkState.CONNECTED
            }

            // Reset grace period on good connection
            if (newState == NetworkState.CONNECTED) {
                networkDisabledSince = null
            } else if (networkDisabledSince == null && DriverPreferences.getOnlineStatus()) {
                networkDisabledSince = System.currentTimeMillis()
                Log.d(TAG, "⏰ Network grace period started (capabilities changed)")
            }

            _networkState.value = newState

            if (DriverPreferences.getOnlineStatus()) {
                checkSystemHealth()
                scheduleGracePeriodCheck()
            }
        }
    }

    /**
     * Schedule a check after grace period expires
     */
    private fun scheduleGracePeriodCheck() {
        // Cancel any pending check
        pendingNotificationCheck?.let { handler.removeCallbacks(it) }

        // Schedule new check after grace period + small buffer
        pendingNotificationCheck = Runnable {
            Log.d(TAG, "⏰ Grace period expired - rechecking notifications")
            updateNotifications()
        }

        handler.postDelayed(pendingNotificationCheck!!, GRACE_PERIOD + 500L)
        Log.d(TAG, "📅 Scheduled notification check in ${GRACE_PERIOD + 500L}ms")
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        createNotificationChannels()
        registerReceivers()
        checkSystemHealth()

        Log.d(TAG, "📍 LocationMonitor started")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)

        // Cancel pending checks
        pendingNotificationCheck?.let { handler.removeCallbacks(it) }

        // Don't unregister if driver is online - keep monitoring in background!
        if (!DriverPreferences.getOnlineStatus()) {
            unregisterReceivers()
        }
        Log.d(TAG, "📍 LocationMonitor stopped (monitoring: ${DriverPreferences.getOnlineStatus()})")
    }

    private fun registerReceivers() {
        try {
            // Location provider changes
            val locationFilter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Context.RECEIVER_EXPORTED
                } else {
                    Context.RECEIVER_NOT_EXPORTED
                }
                context.registerReceiver(locationReceiver, locationFilter, flags)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(locationReceiver, locationFilter)
            }

            // Network connectivity
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val networkFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(locationReceiver, networkFilter)
            }

            Log.d(TAG, "✅ Receivers registered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receivers already registered")
        }
    }

    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(locationReceiver)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            }
            Log.d(TAG, "✅ Receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }
    }

    /**
     * Comprehensive system health check
     */
    fun checkSystemHealth() {
        checkLocationStatus()
        checkNetworkStatus()
        checkBatteryOptimization()
        checkBackgroundRestrictions()
        updateNotifications()
    }

    fun checkLocationStatus() {
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isOnline = DriverPreferences.getOnlineStatus()

        Log.d(TAG, "📍 GPS: $isGpsEnabled, Network: $isNetworkEnabled, Online: $isOnline")

        val newState = when {
            !isGpsEnabled && !isNetworkEnabled && isOnline -> {
                if (locationDisabledSince == null) {
                    locationDisabledSince = System.currentTimeMillis()
                    Log.d(TAG, "⏰ Location grace period started")
                }
                LocationState.DISABLED_WHILE_ONLINE
            }
            !isGpsEnabled && !isNetworkEnabled -> {
                LocationState.DISABLED
            }
            !isGpsEnabled && isOnline -> {
                LocationState.GPS_ONLY_DISABLED
            }
            else -> {
                locationDisabledSince = null // Reset grace period
                LocationState.ENABLED
            }
        }

        _locationState.value = newState
    }

    private fun checkNetworkStatus() {
        val currentTime = System.currentTimeMillis()

        // Only debounce if not online (less frequent checks when offline)
        if (!DriverPreferences.getOnlineStatus()) {
            if (currentTime - lastNetworkCheckTime < NETWORK_CHECK_INTERVAL * 2) {
                return
            }
        }
        lastNetworkCheckTime = currentTime

        // Check using NetworkCallback approach for API 23+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            val newState = when {
                capabilities == null -> {
                    if (DriverPreferences.getOnlineStatus() && networkDisabledSince == null) {
                        networkDisabledSince = currentTime
                        Log.d(TAG, "⏰ Network grace period started (no capabilities)")
                    }
                    NetworkState.DISCONNECTED
                }
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> {
                    if (DriverPreferences.getOnlineStatus() && networkDisabledSince == null) {
                        networkDisabledSince = currentTime
                        Log.d(TAG, "⏰ Network grace period started (no internet)")
                    }
                    NetworkState.NO_INTERNET
                }
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> {
                    NetworkState.LIMITED
                }
                else -> {
                    networkDisabledSince = null
                    NetworkState.CONNECTED
                }
            }

            _networkState.value = newState
        } else {
            // Fallback for older APIs
            val networkInfo = connectivityManager.activeNetworkInfo
            val isConnected = networkInfo?.isConnectedOrConnecting == true

            _networkState.value = when {
                !isConnected -> {
                    if (DriverPreferences.getOnlineStatus() && networkDisabledSince == null) {
                        networkDisabledSince = currentTime
                        Log.d(TAG, "⏰ Network grace period started")
                    }
                    NetworkState.DISCONNECTED
                }
                networkInfo?.type == ConnectivityManager.TYPE_WIFI -> NetworkState.CONNECTED
                networkInfo?.type == ConnectivityManager.TYPE_MOBILE -> {
                    if (networkInfo.isRoaming) NetworkState.ROAMING else NetworkState.CONNECTED
                }
                else -> NetworkState.LIMITED
            }
        }

        Log.d(TAG, "🌐 Network: ${_networkState.value}")
    }

    private fun checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

            if (!isIgnoringOptimizations && DriverPreferences.getOnlineStatus()) {
                Log.w(TAG, "⚡ Battery optimization enabled - may affect background location")
            }
        }
    }

    private fun checkBackgroundRestrictions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val isRestricted = activityManager.isBackgroundRestricted

            if (isRestricted && DriverPreferences.getOnlineStatus()) {
                Log.w(TAG, "🚫 Background restrictions enabled")
            }
        }
    }

    /**
     * Smart notification management with grace periods
     */
    private fun updateNotifications() {
        val isOnline = DriverPreferences.getOnlineStatus()
        val isInBackground = !isAppInForeground()
        val locationState = _locationState.value
        val networkState = _networkState.value

        Log.d(TAG, "🔔 Checking notifications - Online: $isOnline, Background: $isInBackground, Location: $locationState, Network: $networkState")

        // Don't show notifications if driver is offline
        if (!isOnline) {
            dismissAllNotifications()
            return
        }

        // CRITICAL: Show notifications ONLY when in background AND driver is online
        if (!isInBackground) {
            // App is in foreground - MainActivity handles alerts
            dismissAllNotifications()
            return
        }

        // Check grace periods before showing notifications
        val currentTime = System.currentTimeMillis()

        val locationGracePassed = locationDisabledSince?.let {
            val elapsed = currentTime - it
            Log.d(TAG, "⏱️ Location disabled for ${elapsed}ms (grace: ${GRACE_PERIOD}ms)")
            elapsed >= GRACE_PERIOD
        } ?: false

        val networkGracePassed = networkDisabledSince?.let {
            val elapsed = currentTime - it
            Log.d(TAG, "⏱️ Network disabled for ${elapsed}ms (grace: ${GRACE_PERIOD}ms)")
            elapsed >= GRACE_PERIOD
        } ?: false

        // Priority 1: Location disabled (most critical)
        when (locationState) {
            LocationState.DISABLED_WHILE_ONLINE -> {
                Log.d(TAG, "🚨 Location disabled while online - Grace passed: $locationGracePassed")
                if (locationGracePassed) {
                    showCriticalLocationAlert()
                    return // Don't check other conditions
                } else {
                    Log.d(TAG, "⏳ Waiting for location grace period to expire...")
                    // Don't return - we still want to check network
                }
            }
            LocationState.GPS_ONLY_DISABLED -> {
                Log.d(TAG, "⚠️ GPS only disabled - Grace passed: $locationGracePassed")
                if (locationGracePassed) {
                    showGPSWarningNotification()
                    // Continue to check network
                }
            }
            else -> {
                dismissLocationNotifications()
            }
        }

        // Priority 2: Network issues (if location is OK or only GPS disabled)
        if (locationState == LocationState.ENABLED || locationState == LocationState.GPS_ONLY_DISABLED) {
            when (networkState) {
                NetworkState.DISCONNECTED, NetworkState.NO_INTERNET -> {
                    Log.d(TAG, "🚨 Network disconnected - Grace passed: $networkGracePassed")
                    if (networkGracePassed) {
                        showNetworkDisabledNotification()
                    } else {
                        Log.d(TAG, "⏳ Waiting for network grace period to expire...")
                    }
                }
                NetworkState.LIMITED -> {
                    showNetworkLimitedNotification()
                }
                NetworkState.ROAMING -> {
                    showRoamingNotification()
                }
                NetworkState.CONNECTED -> {
                    dismissNetworkNotifications()
                }
            }
        }
    }

    /**
     * CRITICAL: Location completely disabled while online
     */
    private fun showCriticalLocationAlert() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action to go offline
        val offlineIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        val offlinePendingIntent = PendingIntent.getService(
            context, 1, offlineIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_offline)
            .setContentTitle("🚨 Location Disabled!")
            .setContentText("You're online but location is OFF. Enable now or go offline.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("⚠️ CRITICAL: You're marked ONLINE but location services are OFF!\n\n" +
                        "Passengers cannot find you and you won't receive ride requests.\n\n" +
                        "✅ Tap to enable location\n" +
                        "🛑 Or go offline below"))
            .addAction(
                R.drawable.ic_online,
                "Enable Location",
                pendingIntent
            )
            .addAction(
                R.drawable.ic_offline,
                "Go Offline",
                offlinePendingIntent
            )
            .build()

        notificationManager?.notify(NOTIFICATION_LOCATION_DISABLED, notification)
        Log.d(TAG, "🔔 Showing CRITICAL location notification")
    }

    /**
     * WARNING: GPS disabled, using network only
     */
    private fun showGPSWarningNotification() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, WARNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_offline)
            .setContentTitle("⚠️ GPS Disabled")
            .setContentText("Location accuracy reduced. Tap to enable GPS.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("GPS is disabled. Using network location only.\n\n" +
                        "This may cause:\n" +
                        "• Inaccurate pickup locations\n" +
                        "• Poor navigation\n" +
                        "• Fewer ride requests\n\n" +
                        "Enable GPS for best performance."))
            .addAction(
                R.drawable.ic_online,
                "Enable GPS",
                pendingIntent
            )
            .build()

        notificationManager?.notify(NOTIFICATION_GPS_DISABLED, notification)
        Log.d(TAG, "🔔 Showing GPS warning notification")
    }

    /**
     * CRITICAL: No network connection
     */
    private fun showNetworkDisabledNotification() {
        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action to go offline
        val offlineIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        val offlinePendingIntent = PendingIntent.getService(
            context, 1, offlineIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_offline)
            .setContentTitle("🚨 No Internet Connection")
            .setContentText("You're online but have no network. Fix it or go offline.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("⚠️ CRITICAL: No internet connection!\n\n" +
                        "You're marked ONLINE but:\n" +
                        "• Cannot receive ride requests\n" +
                        "• Cannot sync location\n" +
                        "• Cannot update status\n\n" +
                        "✅ Enable WiFi/Mobile Data\n" +
                        "🛑 Or go offline"))
            .addAction(
                R.drawable.ic_online,
                "Open Settings",
                pendingIntent
            )
            .addAction(
                R.drawable.ic_offline,
                "Go Offline",
                offlinePendingIntent
            )
            .build()

        notificationManager?.notify(NOTIFICATION_NETWORK_DISABLED, notification)
        Log.d(TAG, "🔔 Showing CRITICAL network notification")
    }

    /**
     * INFO: Limited network connectivity
     */
    private fun showNetworkLimitedNotification() {
        val notification = NotificationCompat.Builder(context, INFO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_offline)
            .setContentTitle("📶 Weak Signal")
            .setContentText("Poor network connection detected.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(60000) // Auto-dismiss after 1 minute
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your network connection is weak.\n\n" +
                        "This may cause delayed ride requests or slow updates.\n\n" +
                        "Try moving to an area with better signal."))
            .build()

        notificationManager?.notify(NOTIFICATION_NETWORK_DISABLED, notification)
    }

    /**
     * INFO: Roaming detected
     */
    private fun showRoamingNotification() {
        val notification = NotificationCompat.Builder(context, INFO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_offline)
            .setContentTitle("🌍 Data Roaming Active")
            .setContentText("Additional charges may apply.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Only alert once per session
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("You're using mobile data roaming.\n\n" +
                        "Extra charges may apply. Consider using WiFi when available."))
            .build()

        notificationManager?.notify(NOTIFICATION_NETWORK_DISABLED, notification)
    }

    /**
     * Dismiss specific notification groups
     */
    private fun dismissLocationNotifications() {
        notificationManager?.apply {
            cancel(NOTIFICATION_LOCATION_DISABLED)
            cancel(NOTIFICATION_GPS_DISABLED)
        }
        Log.d(TAG, "✅ Location notifications dismissed")
    }

    private fun dismissNetworkNotifications() {
        notificationManager?.cancel(NOTIFICATION_NETWORK_DISABLED)
        Log.d(TAG, "✅ Network notifications dismissed")
    }

    /**
     * Dismiss all notifications
     */
    private fun dismissAllNotifications() {
        notificationManager?.apply {
            cancel(NOTIFICATION_LOCATION_DISABLED)
            cancel(NOTIFICATION_GPS_DISABLED)
            cancel(NOTIFICATION_NETWORK_DISABLED)
            cancel(NOTIFICATION_POOR_ACCURACY)
            cancel(NOTIFICATION_BACKGROUND_RESTRICTION)
            cancel(NOTIFICATION_BATTERY_OPTIMIZATION)
        }
    }

    /**
     * Check if app is in foreground
     */
    private fun isAppInForeground(): Boolean {
        return try {
            val appProcesses = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses ?: return false

            val packageName = context.packageName
            appProcesses.any {
                it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.processName == packageName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking foreground: ${e.message}")
            false
        }
    }

    /**
     * Create notification channels
     */
    fun createNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Critical alerts - max priority, with sound/vibration
            val alertChannel = android.app.NotificationChannel(
                ALERT_CHANNEL_ID,
                "Critical Location Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alerts about location and connectivity issues while online"
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            // Warnings - medium priority
            val warningChannel = android.app.NotificationChannel(
                WARNING_CHANNEL_ID,
                "Location Warnings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Important warnings about location accuracy"
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.YELLOW
            }

            // Info - low priority, no sound
            val infoChannel = android.app.NotificationChannel(
                INFO_CHANNEL_ID,
                "Location Information",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "General information about location status"
                setShowBadge(false)
                setSound(null, null)
            }

            notificationManager?.apply {
                createNotificationChannel(alertChannel)
                createNotificationChannel(warningChannel)
                createNotificationChannel(infoChannel)
            }

            Log.d(TAG, "✅ Notification channels created")
        }
    }

    // State classes
    sealed class LocationState {
        data object ENABLED : LocationState()
        data object DISABLED : LocationState()
        data object GPS_ONLY_DISABLED : LocationState()
        data object DISABLED_WHILE_ONLINE : LocationState()
    }

    sealed class NetworkState {
        data object CONNECTED : NetworkState()
        data object DISCONNECTED : NetworkState()
        data object NO_INTERNET : NetworkState()
        data object LIMITED : NetworkState()
        data object ROAMING : NetworkState()
    }

    data class NotificationState(
        val isOnline: Boolean,
        val isInBackground: Boolean,
        val locationState: LocationState,
        val networkState: NetworkState
    )
}