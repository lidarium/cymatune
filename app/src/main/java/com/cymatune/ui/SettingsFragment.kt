package com.cymatune.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.cymatune.R
import com.cymatune.service.LogcatService

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set app version
        view.findViewById<TextView>(R.id.appVersion)?.text = com.cymatune.BuildConfig.VERSION_NAME
        
        // Enhanced Detection toggle
        setupEnhancedDetection(view)
        
        // Notification Settings
        setupNotificationSettings(view)
        
        // Strict Thresholds toggle
        setupStrictThresholds(view)
        
        // About button
        view.findViewById<Button>(R.id.btnAbout)?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("About Cymatune")
                .setMessage(
                    "Cymatune is a privacy-focused Android application that detects fake cell towers (IMSI catchers) using multiple detection methods including geographic anomaly detection, signal pattern analysis, tower behavior monitoring, LAC/CID validation, and real-time system log analysis.\n\n" +
                    "Detection accuracy improves over time as the app learns your normal tower environment. No security tool can guarantee 100% detection of all IMSI catchers.\n\n" +
                    "Version: ${com.cymatune.BuildConfig.VERSION_NAME}"
                )
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    // ===== ENHANCED DETECTION =====
    private fun setupEnhancedDetection(view: View) {
        val switchEnhanced = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchEnhancedDetection)
        val statusText = view.findViewById<TextView>(R.id.enhancedDetectionStatus)
        val btnCheckPermission = view.findViewById<Button>(R.id.btnCheckLogcatPermission)
        
        val hasPermission = hasReadLogsPermission()
        
        if (hasPermission) {
            switchEnhanced?.isChecked = true
            statusText?.text = "Enabled - System log analysis active"
            statusText?.setTextColor(requireContext().getColor(R.color.primary_neon))
            btnCheckPermission?.visibility = View.GONE
        } else {
            switchEnhanced?.isChecked = false
            statusText?.text = "Disabled - Tap to enable"
            statusText?.setTextColor(requireContext().getColor(R.color.text_secondary))
            btnCheckPermission?.visibility = View.VISIBLE
        }
        
        btnCheckPermission?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Enable Enhanced Detection")
                .setMessage(
                    "Enhanced Detection provides real-time system log analysis for improved fake tower detection.\n\n" +
                    "⚠️ This feature requires special permission that can only be granted via ADB.\n\n" +
                    "To enable:\n1. Connect device to computer with ADB\n" +
                    "2. Run: adb shell pm grant com.cymatune android.permission.READ_LOGS\n" +
                    "3. The toggle will activate automatically once permission is granted."
                )
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        }
        
        switchEnhanced?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPermission) {
                showPermissionDialog(switchEnhanced, statusText)
            } else if (isChecked) {
                statusText?.text = "Enabled - System log analysis active"
                statusText?.setTextColor(requireContext().getColor(R.color.primary_neon))
            } else {
                statusText?.text = "Disabled - Tap to enable"
                statusText?.setTextColor(requireContext().getColor(R.color.text_secondary))
            }
        }
    }

    private fun hasReadLogsPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.READ_LOGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDialog(switch: androidx.appcompat.widget.SwitchCompat?, statusText: TextView?) {
        val adbCommand = "adb shell pm grant com.cymatune android.permission.READ_LOGS"
        AlertDialog.Builder(requireContext())
            .setTitle("Permission Required")
            .setMessage(
                "Enhanced detection requires READ_LOGS permission.\n\n" +
                "Grant via ADB:\n$adbCommand\n\n" +
                "After running the command, return to the app and toggle again."
            )
            .setPositiveButton("Copy Command") { d, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ADB Command", adbCommand))
                android.widget.Toast.makeText(requireContext(), "Command copied", android.widget.Toast.LENGTH_SHORT).show()
                d.dismiss()
                switch?.isChecked = false
            }
            .setNegativeButton("Cancel") { d, _ ->
                d.dismiss()
                switch?.isChecked = false
            }
            .setOnCancelListener { switch?.isChecked = false }
            .show()
    }

    // ===== NOTIFICATION SETTINGS =====
    private fun setupNotificationSettings(view: View) {
        val prefs = requireContext().getSharedPreferences("cymatune_settings", android.content.Context.MODE_PRIVATE)
        
        val switchEnabled = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchNotificationsEnabled)
        val statusText = view.findViewById<TextView>(R.id.notificationsStatus)
        val txtLimit = view.findViewById<TextView>(R.id.txtDailyLimit)
        val btnDecrement = view.findViewById<Button>(R.id.btnDecrementLimit)
        val btnIncrement = view.findViewById<Button>(R.id.btnIncrementLimit)

        val isEnabled = prefs.getBoolean("notifications_enabled", false)
        val limit = prefs.getInt("daily_alert_limit", 0)

        switchEnabled?.isChecked = isEnabled
        txtLimit?.text = limit.toString()
        updateNotificationsStatus(statusText, isEnabled)

        switchEnabled?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notifications_enabled", checked).apply()
            updateNotificationsStatus(statusText, checked)
        }

        btnDecrement?.setOnClickListener {
            val current = txtLimit?.text.toString().toIntOrNull() ?: 0
            val newVal = maxOf(0, current - 1)
            txtLimit?.text = newVal.toString()
            prefs.edit().putInt("daily_alert_limit", newVal).apply()
        }

        btnIncrement?.setOnClickListener {
            val current = txtLimit?.text.toString().toIntOrNull() ?: 0
            val newVal = current + 1
            txtLimit?.text = newVal.toString()
            prefs.edit().putInt("daily_alert_limit", newVal).apply()
        }
    }

    private fun updateNotificationsStatus(statusText: TextView?, enabled: Boolean) {
        if (enabled) {
            statusText?.text = "Enabled - Silent alerts active"
            statusText?.setTextColor(requireContext().getColor(R.color.primary_neon))
        } else {
            statusText?.text = "Disabled (Intrusion-Free Mode)"
            statusText?.setTextColor(requireContext().getColor(R.color.text_secondary))
        }
    }

    // ===== STRICT THRESHOLDS =====
    private fun setupStrictThresholds(view: View) {
        val prefs = requireContext().getSharedPreferences("cymatune_settings", android.content.Context.MODE_PRIVATE)
        val switchStrict = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStrictThresholds)
        val statusText = view.findViewById<TextView>(R.id.strictThresholdsStatus)

        val isStrict = prefs.getBoolean("strict_thresholds_enabled", false)

        switchStrict?.isChecked = isStrict
        updateStrictThresholdsStatus(statusText, isStrict)

        com.cymatune.detection.DetectionThresholdManager.setStrictMode(isStrict)

        switchStrict?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("strict_thresholds_enabled", isChecked).apply()
            updateStrictThresholdsStatus(statusText, isChecked)
            com.cymatune.detection.DetectionThresholdManager.setStrictMode(isChecked)
            val msg = if (isChecked) "Strict thresholds enabled" else "Standard thresholds enabled"
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStrictThresholdsStatus(statusText: TextView?, enabled: Boolean) {
        if (enabled) {
            statusText?.text = "Enabled - High sensitivity mode"
            statusText?.setTextColor(requireContext().getColor(R.color.warning_red))
        } else {
            statusText?.text = "Disabled - Standard detection"
            statusText?.setTextColor(requireContext().getColor(R.color.text_secondary))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh enhanced detection status in case permission was granted
        view?.let { setupEnhancedDetection(it) }
    }
}
