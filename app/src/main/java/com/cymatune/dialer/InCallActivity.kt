package com.cymatune.dialer

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cymatune.R
import com.cymatune.util.ContactUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Phase 2.6: The actual ringing and active call UI.
 * Integrates Lockscreen overrides and answers/rejects calls via TelecomManager.
 * Now includes active call timer (MM:SS format).
 */
class InCallActivity : AppCompatActivity() {

    private lateinit var callerNameText: TextView
    private lateinit var callStatusText: TextView
    private lateinit var threatIndicator: ImageView
    private lateinit var btnAccept: FloatingActionButton
    private lateinit var btnReject: FloatingActionButton

    // Call timer state
    private var callStartTime: Long = 0L
    private var callStateLabel: String = ""
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            updateCallTimer()
            callTimerHandler.postDelayed(this, 1000L) // Update every second
        }
    }

    // B14: Proximity wake lock for screen off during calls
    private var proximityWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Critical: Allow Activity to show over lockscreen and turn on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_in_call)

        callerNameText = findViewById(R.id.callerNameText)
        callStatusText = findViewById(R.id.callStatusText)
        threatIndicator = findViewById(R.id.threatIndicator)
        btnAccept = findViewById(R.id.btnAccept)
        btnReject = findViewById(R.id.btnReject)

        setupListeners()
        observeCallState()

        // B14: Initialize proximity wake lock
        initProximityWakeLock()
    }

    /**
     * B14: Initialize proximity wake lock for screen off during calls
     */
    private fun initProximityWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // D05 FIX: Verify hardware support before attempting to create wake lock
            val isSupported = powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) ?: false
            if (isSupported) {
                proximityWakeLock = powerManager?.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "Cymatune:InCallProximity"
                )
                android.util.Log.d("InCallActivity", "Proximity wake lock initialized successfully")
            } else {
                android.util.Log.w("InCallActivity", "Proximity wake lock level NOT supported on this device")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // B14: Acquire proximity wake lock when call is active
        if (callStartTime > 0L) {
            acquireProximityWakeLock()
        }
    }

    override fun onResume() {
        super.onResume()
        // D05 FIX: Ensure proximity lock is held when returning to foreground during active call
        if (callStartTime > 0L) {
            android.util.Log.d("InCallActivity", "onResume: Active call detected, ensuring proximity lock")
            acquireProximityWakeLock()
        }
    }

    override fun onStop() {
        super.onStop()
        // B14: Do NOT release proximity wake lock here - it must remain active during the call
        // The wake lock is only released in onDestroy() when the call actually ends
        // Releasing in onStop() causes screen to turn back on when user switches apps
    }

    /**
     * B14: Acquire proximity wake lock to turn off screen when near ear
     */
    private fun acquireProximityWakeLock() {
        try {
            if (proximityWakeLock == null) {
                android.util.Log.v("InCallActivity", "proximityWakeLock is null, cannot acquire")
                return
            }
            if (!proximityWakeLock!!.isHeld) {
                android.util.Log.d("InCallActivity", "Acquiring proximity wake lock (10m timeout)")
                proximityWakeLock?.acquire(10*60*1000L) // 10 min timeout
            }
        } catch (e: Exception) {
            android.util.Log.e("InCallActivity", "Failed to acquire proximity wake lock", e)
        }
    }

    /**
     * B14: Release proximity wake lock
     */
    private fun releaseProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                android.util.Log.d("InCallActivity", "Releasing proximity wake lock")
                proximityWakeLock?.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("InCallActivity", "Failed to release proximity wake lock", e)
        }
    }

    private fun setupListeners() {
        btnAccept.setOnClickListener {
            CallManager.acceptCall()
        }

        btnReject.setOnClickListener {
            CallManager.rejectCall()
        }
    }

    private var hasObservedCall = false
    
    private fun observeCallState() {
        lifecycleScope.launch {
            CallManager.currentCall.collectLatest { call ->
                if (call == null) {
                    if (hasObservedCall) {
                        stopCallTimer()
                        finish() // Call has ended, close UI
                    } else {
                        // Allow some time for the call to be added to CallManager
                        kotlinx.coroutines.delay(2000L)
                        if (!hasObservedCall) {
                            finish()
                        }
                    }
                    return@collectLatest
                }

                hasObservedCall = true
                updateUI(call)
            }
        }
    }

    private fun updateUI(call: Call) {
        // Safe check for handle/number
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown Caller"

        // Try to resolve contact name from contacts
        lifecycleScope.launch {
            val resolvedName = ContactUtils.resolveContactName(this@InCallActivity, number)
            val displayName = resolvedName ?: call.details?.callerDisplayName ?: number
            callerNameText.text = displayName
        }

        // B15: Store call state label separately for timer template
        callStateLabel = when (call.state) {
            Call.STATE_RINGING -> {
                btnAccept.visibility = android.view.View.VISIBLE
                stopCallTimer() // Don't timer during ringing
                "Incoming call"
            }
            Call.STATE_ACTIVE -> {
                btnAccept.visibility = android.view.View.GONE
                startCallTimer()
                acquireProximityWakeLock() // B14: Enable proximity sensor during active call
                ""
            }
            Call.STATE_DIALING -> {
                btnAccept.visibility = android.view.View.GONE
                stopCallTimer()
                acquireProximityWakeLock() // Proximity on dialing
                "Calling"
            }
            Call.STATE_CONNECTING -> {
                btnAccept.visibility = android.view.View.GONE
                stopCallTimer()
                acquireProximityWakeLock() // Proximity on connecting
                "Calling"
            }
            Call.STATE_DISCONNECTED -> {
                btnAccept.visibility = android.view.View.GONE
                btnReject.visibility = android.view.View.GONE
                stopCallTimer()
                releaseProximityWakeLock() // B14: Release proximity lock
                "Call ended"
            }
            else -> {
                stopCallTimer()
                acquireProximityWakeLock()
                "Calling"
            }
        }

        // B15: Update status with template "%1$s | %2$s" (state | timer)
        updateStatusWithTimer()

        // Phase 2 Threat indicator color logic
        threatIndicator.setColorFilter(ContextCompat.getColor(this, R.color.primary_neon))
    }

    /**
     * B15: Update status text with state and timer using template format
     */
    private fun updateStatusWithTimer() {
        if (callStartTime > 0L) {
            // Timer will be updated by the runnable
            val elapsedMillis = System.currentTimeMillis() - callStartTime
            val elapsedSeconds = elapsedMillis / 1000
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            // Display only the timer when active
            callStatusText.text = String.format("%02d:%02d", minutes, seconds)
        } else {
            // No timer, just show state
            callStatusText.text = callStateLabel
        }
    }

    /**
     * B15: Start the call duration timer
     */
    private fun startCallTimer() {
        if (callStartTime == 0L) {
            callStartTime = System.currentTimeMillis()
            callTimerHandler.post(callTimerRunnable)
        }
    }

    /**
     * B15: Stop the call duration timer
     */
    private fun stopCallTimer() {
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callStartTime = 0L
    }

    /**
     * B15: Update the call timer display - always updates if timer is running
     * Uses template "%1$s | %2$s" to prevent timer from being wiped
     */
    private fun updateCallTimer() {
        // B15 FIX: Always update duration if callStartTime > 0, remove .startsWith() check
        if (callStartTime > 0L) {
            updateStatusWithTimer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallTimer()
        releaseProximityWakeLock() // B14: Ensure wake lock is released
        // If the activity is destroyed (e.g. back button), ensure we aren't leaking a ghost call state
        // The service will clean up the actual call.
    }
}
