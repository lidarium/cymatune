package com.cymatune.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.cymatune.R
import com.cymatune.util.CommonUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.net.Uri
import android.os.Bundle as AndroidBundle

/**
 * Functional T9 dialpad for Phase 2.2.
 * Handles numeric input and triggers outgoing calls via TelecomManager.
 * Now supports external intent population (tel:xxx from other apps).
 */
class DialpadFragment : Fragment() {

    private lateinit var digitsDisplay: TextView
    private lateinit var dialpadGrid: android.widget.GridLayout
    private lateinit var btnCall: FloatingActionButton
    private lateinit var btnDelete: ImageButton
    private var currentNumber = ""

    // Broadcast receiver for external dial intents
    private val dialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.cymatune.POPULATE_DIALER") {
                val number = intent.getStringExtra("phone_number")
                if (!number.isNullOrBlank()) {
                    currentNumber = number
                    updateDisplay()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_dialpad, container, false)

        digitsDisplay = view.findViewById(R.id.digitsDisplay)
        dialpadGrid = view.findViewById(R.id.dialpadGrid)
        btnCall = view.findViewById(R.id.btnCall)
        btnDelete = view.findViewById(R.id.btnDelete)
        setupDialpad()
        setupActions()

        return view
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for external dial intents
        val filter = IntentFilter("com.cymatune.POPULATE_DIALER")
        ContextCompat.registerReceiver(requireContext(), dialReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        // Unregister receiver
        try {
            requireContext().unregisterReceiver(dialReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private fun setupDialpad() {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        val grid = dialpadGrid
        
        for (key in keys) {
            val button = LayoutInflater.from(requireContext()).inflate(R.layout.item_dialpad_key, grid, false)
            button.findViewById<TextView>(R.id.keyText).text = key
            button.setOnClickListener {
                appendDigit(key)
            }
            grid.addView(button)
        }
    }

    private fun setupActions() {
        btnDelete.setOnClickListener {
            if (currentNumber.isNotEmpty()) {
                currentNumber = currentNumber.dropLast(1)
                updateDisplay()
            }
        }
        
        btnDelete.setOnLongClickListener {
            currentNumber = ""
            updateDisplay()
            true
        }

        btnCall.setOnClickListener {
            if (currentNumber.isNotEmpty()) {
                initiateCall(currentNumber)
            }
        }
    }

    private fun appendDigit(digit: String) {
        if (currentNumber.length < 20) {
            currentNumber += digit
            updateDisplay()
        }
    }

    private fun updateDisplay() {
        digitsDisplay.text = currentNumber
        btnDelete.visibility = if (currentNumber.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Phase 4: Multi-SIM support
     * Shows SIM selection if multiple SIMs available, then places call
     */
    private fun initiateCall(number: String) {
        try {
            val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefault = requireContext().packageName == telecomManager.defaultDialerPackage

            if (!isDefault) {
                // Fallback to system dialer if not default
                val uri = Uri.fromParts("tel", number, null)
                val intent = Intent(Intent.ACTION_DIAL, uri)
                startActivity(intent)
                return
            }

            // Check if device has multiple SIMs
            if (SimSelectionHelper.hasMultipleSims(requireContext())) {
                // Show SIM selection bottom sheet
                val bottomSheet = SimSelectionBottomSheet.newInstance()
                bottomSheet.setOnSimSelectedListener { phoneAccountHandle ->
                    placeCallWithSim(number, phoneAccountHandle)
                }
                bottomSheet.show(parentFragmentManager, SimSelectionBottomSheet::class.java.simpleName)
            } else {
                // Single SIM - place call directly
                placeCallWithSim(number, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("DialpadFragment", "Failed to initiate call", e)
        }
    }

    /**
     * Place call with selected SIM (or default if phoneAccountHandle is null)
     */
    private fun placeCallWithSim(number: String, phoneAccountHandle: PhoneAccountHandle?) {
        try {
            val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", number, null)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && phoneAccountHandle != null) {
                // Place call with specific SIM
                val extras = AndroidBundle()
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                telecomManager.placeCall(uri, extras)
            } else {
                // Place call with default SIM
                telecomManager.placeCall(uri, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("DialpadFragment", "Failed to place call with SIM", e)
        }
    }
}
