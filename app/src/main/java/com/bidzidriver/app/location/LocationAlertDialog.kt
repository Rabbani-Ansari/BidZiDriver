package com.bidzidriver.app.location

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.bidzidriver.app.R
/**
 * Beautiful in-app alert dialogs for location issues
 */
object LocationAlertDialog {

    private var currentDialog: Dialog? = null

    /**
     * CRITICAL: Location completely disabled while online
     */
    fun showLocationDisabledAlert(context: Context, onGoOffline: (() -> Unit)? = null) {
        dismissCurrent()

        val dialog = createCustomDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_location_alert, null)

        view.findViewById<ImageView>(R.id.ivAlertIcon).apply {
            setImageResource(R.drawable.ic_offline)
            setColorFilter(context.getColor(R.color.red))
        }

        view.findViewById<TextView>(R.id.tvAlertTitle).text = "🚨 Location Disabled"

        view.findViewById<TextView>(R.id.tvAlertMessage).text =
            "Your location services are completely turned off.\n\n" +
                    "⚠️ You're marked as ONLINE but passengers CANNOT find you!\n\n" +
                    "You must enable location services to receive ride requests."

        view.findViewById<Button>(R.id.btnPrimary).apply {
            text = "Enable Location Now"
            setBackgroundColor(context.getColor(R.color.driver_green))
            setOnClickListener {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                dialog.dismiss()
            }
        }

        view.findViewById<Button>(R.id.btnSecondary).apply {
            text = "Go Offline"
            setOnClickListener {
                DriverPreferences.setOnlineStatus(false)
                dialog.dismiss()
                onGoOffline?.invoke()
            }
        }

        dialog.setContentView(view)
        configureDialogWindow(dialog, context)
        dialog.show()
        currentDialog = dialog
    }

    /**
     * WARNING: GPS disabled, using network location only
     */
    fun showGPSDisabledAlert(context: Context) {
        // Don't show if already showing a critical alert
        if (currentDialog?.isShowing == true) return

        val dialog = createCustomDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_location_alert, null)

        view.findViewById<ImageView>(R.id.ivAlertIcon).apply {
            setImageResource(R.drawable.ic_offline)
            setColorFilter(context.getColor(R.color.orange))
        }

        view.findViewById<TextView>(R.id.tvAlertTitle).text = "⚠️ GPS Disabled"

        view.findViewById<TextView>(R.id.tvAlertMessage).text =
            "GPS is turned off. You're using network location only.\n\n" +
                    "This may cause:\n" +
                    "• Poor location accuracy (±50-500m)\n" +
                    "• Inaccurate pickup points\n" +
                    "• Difficulty finding nearby rides\n\n" +
                    "Enable GPS for best performance."

        view.findViewById<Button>(R.id.btnPrimary).apply {
            text = "Enable GPS"
            setOnClickListener {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                dialog.dismiss()
            }
        }

        view.findViewById<Button>(R.id.btnSecondary).apply {
            text = "Continue Anyway"
            setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.setContentView(view)
        configureDialogWindow(dialog, context)
        dialog.show()
        currentDialog = dialog
    }

    /**
     * CRITICAL: No internet connection
     */
    fun showNetworkDisabledAlert(context: Context, onGoOffline: (() -> Unit)? = null) {
        dismissCurrent()

        val dialog = createCustomDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_location_alert, null)

        view.findViewById<ImageView>(R.id.ivAlertIcon).apply {
            setImageResource(R.drawable.ic_offline)
            setColorFilter(context.getColor(R.color.red))
        }

        view.findViewById<TextView>(R.id.tvAlertTitle).text = "🚨 No Internet"

        view.findViewById<TextView>(R.id.tvAlertMessage).text =
            "No internet connection detected!\n\n" +
                    "⚠️ You're marked as ONLINE but:\n" +
                    "• Cannot receive ride requests\n" +
                    "• Cannot sync your location\n" +
                    "• Cannot communicate with passengers\n\n" +
                    "Enable WiFi or Mobile Data immediately."

        view.findViewById<Button>(R.id.btnPrimary).apply {
            text = "Open Network Settings"
            setBackgroundColor(context.getColor(R.color.driver_green))
            setOnClickListener {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                dialog.dismiss()
            }
        }

        view.findViewById<Button>(R.id.btnSecondary).apply {
            text = "Go Offline"
            setOnClickListener {
                DriverPreferences.setOnlineStatus(false)
                dialog.dismiss()
                onGoOffline?.invoke()
            }
        }

        dialog.setContentView(view)
        configureDialogWindow(dialog, context)
        dialog.show()
        currentDialog = dialog
    }

    /**
     * INFO: Poor location accuracy
     */
    fun showLocationAccuracyAlert(context: Context, accuracy: Float) {
        if (accuracy < 200f) return // Only show for very poor accuracy

        MaterialAlertDialogBuilder(context, R.style.ModernAlertDialog)
            .setTitle("📍 Poor Location Accuracy")
            .setMessage(
                "Your current location accuracy is ±${accuracy.toInt()}m.\n\n" +
                        "This is significantly poor and may affect:\n" +
                        "• Your visibility to nearby passengers\n" +
                        "• Accurate pickup locations\n" +
                        "• Navigation accuracy\n\n" +
                        "Try:\n" +
                        "• Moving outdoors or near windows\n" +
                        "• Enabling GPS (if disabled)\n" +
                        "• Waiting a moment for GPS to lock\n" +
                        "• Restarting your device if issue persists"
            )
            .setPositiveButton("Check Location Settings") { _, _ ->
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    /**
     * Permission revoked alert
     */
    fun showPermissionRevokedAlert(context: Context, onGoOffline: () -> Unit) {
        dismissCurrent()

        val dialog = createCustomDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_location_alert, null)

        view.findViewById<ImageView>(R.id.ivAlertIcon).apply {
            setImageResource(R.drawable.ic_offline)
            setColorFilter(context.getColor(R.color.red))
        }

        view.findViewById<TextView>(R.id.tvAlertTitle).text = "⚠️ Permission Revoked"

        view.findViewById<TextView>(R.id.tvAlertMessage).text =
            "Location permission was disabled or revoked.\n\n" +
                    "You've been automatically taken OFFLINE.\n\n" +
                    "Enable location permission to go online again."

        view.findViewById<Button>(R.id.btnPrimary).apply {
            text = "Open App Settings"
            setBackgroundColor(context.getColor(R.color.driver_green))
            setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
                dialog.dismiss()
            }
        }

        view.findViewById<Button>(R.id.btnSecondary).apply {
            text = "OK"
            setOnClickListener {
                dialog.dismiss()
                onGoOffline()
            }
        }

        dialog.setContentView(view)
        configureDialogWindow(dialog, context)
        dialog.show()
        currentDialog = dialog
    }

    /**
     * Background restriction warning
     */
    fun showBackgroundRestrictionAlert(context: Context) {
        MaterialAlertDialogBuilder(context, R.style.ModernAlertDialog)
            .setTitle("🔋 Background Restriction")
            .setMessage(
                "Your device is restricting background location access.\n\n" +
                        "⚠️ This means:\n" +
                        "• You may not receive ride requests when app is minimized\n" +
                        "• Location updates will stop in background\n" +
                        "• You may appear offline to passengers\n\n" +
                        "For best experience:\n" +
                        "1. Open Settings\n" +
                        "2. Find this app\n" +
                        "3. Set location access to 'Allow all the time'\n" +
                        "4. Disable battery optimization for this app"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Battery optimization warning
     */
    fun showBatteryOptimizationAlert(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return

        MaterialAlertDialogBuilder(context, R.style.ModernAlertDialog)
            .setTitle("🔋 Battery Optimization Active")
            .setMessage(
                "Battery optimization is enabled for this app.\n\n" +
                        "⚠️ This may cause:\n" +
                        "• Delayed location updates in background\n" +
                        "• Missed ride requests\n" +
                        "• App being killed in background\n\n" +
                        "Recommendation:\n" +
                        "Disable battery optimization for this app to ensure reliable location tracking and timely ride requests."
            )
            .setPositiveButton("Disable Optimization") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    /**
     * Data saver warning
     */
    fun showDataSaverAlert(context: Context) {
        MaterialAlertDialogBuilder(context, R.style.ModernAlertDialog)
            .setTitle("📵 Data Saver Active")
            .setMessage(
                "Data Saver mode is enabled on your device.\n\n" +
                        "⚠️ This restricts background data and may:\n" +
                        "• Prevent location syncing when app is in background\n" +
                        "• Block ride request notifications\n" +
                        "• Limit real-time updates\n\n" +
                        "For reliable operation:\n" +
                        "1. Open Settings > Network & Internet > Data Saver\n" +
                        "2. Add this app to 'Unrestricted data' list"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    context.startActivity(Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    /**
     * System health warning
     */
    fun showSystemHealthWarning(
        context: Context,
        issues: List<String>,
        onDismiss: (() -> Unit)? = null
    ) {
        val issuesText = issues.joinToString("\n") { "• $it" }

        MaterialAlertDialogBuilder(context, R.style.ModernAlertDialog)
            .setTitle("⚠️ System Issues Detected")
            .setMessage(
                "The following issues may affect your ability to receive ride requests:\n\n" +
                        "$issuesText\n\n" +
                        "Please resolve these issues for optimal performance."
            )
            .setPositiveButton("Review Settings") { _, _ ->
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            .setNegativeButton("Dismiss") { _, _ ->
                onDismiss?.invoke()
            }
            .show()
    }

    /**
     * Recovery suggestion after multiple failures
     */
    fun showRecoverySuggestionAlert(context: Context) {
        MaterialAlertDialogBuilder(context, R.style.ModernAlertDialog)
            .setTitle("🔧 Connection Issues")
            .setMessage(
                "We're experiencing repeated connection failures.\n\n" +
                        "Quick fixes to try:\n\n" +
                        "1. Toggle Airplane Mode on/off\n" +
                        "2. Restart your device\n" +
                        "3. Check if location services are enabled\n" +
                        "4. Ensure you have stable internet\n" +
                        "5. Make sure the app has all required permissions\n\n" +
                        "If issues persist, please contact support."
            )
            .setPositiveButton("Check Settings") { _, _ ->
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun createCustomDialog(context: Context): Dialog {
        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
        }
    }

    private fun configureDialogWindow(dialog: Dialog, context: Context) {
        dialog.window?.apply {
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.90).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
        }
    }

    private fun dismissCurrent() {
        currentDialog?.dismiss()
        currentDialog = null
    }
}


