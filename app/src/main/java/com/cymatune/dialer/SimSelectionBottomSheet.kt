package com.cymatune.dialer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cymatune.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Phase 4: Multi-SIM Selection Bottom Sheet
 *
 * Displays available SIM cards for Dual/Triple SIM devices.
 * Uses SubscriptionManager to detect active SIMs and allows
 * user to select which SIM to use for outgoing calls.
 *
 * Reference: TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE
 */
class SimSelectionBottomSheet : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SimAdapter
    private var onSimSelectedListener: ((PhoneAccountHandle) -> Unit)? = null

    companion object {
        private const val TAG = "SimSelectionBottomSheet"

        fun newInstance(): SimSelectionBottomSheet {
            return SimSelectionBottomSheet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_sim_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.simListRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val sims = getAvailableSims()

        adapter = SimAdapter(sims) { simInfo ->
            getPhoneAccountHandle(simInfo)?.let { handle ->
                onSimSelectedListener?.invoke(handle)
                dismiss()
            }
        }
        recyclerView.adapter = adapter

        // Handle empty state
        if (sims.isEmpty()) {
            view.findViewById<TextView>(R.id.emptyStateText)?.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }
    }

    /**
     * Set callback for when a SIM is selected
     */
    fun setOnSimSelectedListener(listener: (PhoneAccountHandle) -> Unit) {
        onSimSelectedListener = listener
    }

    /**
     * Get list of available/active SIMs using SubscriptionManager
     */
    private fun getAvailableSims(): List<SubscriptionInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return emptyList()
        }

        // Check for READ_PHONE_STATE permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val subscriptionManager = requireContext().getSystemService(SubscriptionManager::class.java)
            ?: return emptyList()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Use getActiveSubscriptionInfoList for active SIMs
                subscriptionManager.activeSubscriptionInfoList?.filter {
                    it.simSlotIndex >= 0 // Valid slot index
                } ?: emptyList()
            } else {
                // Legacy: Get all subscription info
                subscriptionManager.activeSubscriptionInfoList?.filter {
                    it.simSlotIndex >= 0
                } ?: emptyList()
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Permission denied accessing SubscriptionManager", e)
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting SIM list", e)
            emptyList()
        }
    }

    /**
     * Convert SubscriptionInfo to PhoneAccountHandle for TelecomManager
     */
    private fun getPhoneAccountHandle(subscriptionInfo: SubscriptionInfo): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }

        val telecomManager = requireContext().getSystemService(TelecomManager::class.java)
            ?: return null

        return try {
            // Get all phone accounts and find the one matching this subscription
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            phoneAccounts.find { handle ->
                // Match by subscription ID encoded in the handle ID
                handle.id.contains(subscriptionInfo.subscriptionId.toString()) ||
                handle.id.contains(subscriptionInfo.simSlotIndex.toString())
            } ?: phoneAccounts.getOrNull(subscriptionInfo.simSlotIndex)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting PhoneAccountHandle", e)
            null
        }
    }

    /**
     * RecyclerView Adapter for SIM cards
     */
    private inner class SimAdapter(
        private val sims: List<SubscriptionInfo>,
        private val onSimClick: (SubscriptionInfo) -> Unit
    ) : RecyclerView.Adapter<SimAdapter.SimViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sim_card, parent, false)
            return SimViewHolder(view)
        }

        override fun onBindViewHolder(holder: SimViewHolder, position: Int) {
            holder.bind(sims[position], position + 1)
        }

        override fun getItemCount(): Int = sims.size

        inner class SimViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val simIcon: ImageView = itemView.findViewById(R.id.simIcon)
            private val simName: TextView = itemView.findViewById(R.id.simName)
            private val simNumber: TextView = itemView.findViewById(R.id.simNumber)
            private val simCarrier: TextView = itemView.findViewById(R.id.simCarrier)

            fun bind(simInfo: SubscriptionInfo, displayNumber: Int) {
                // Set SIM icon based on slot
                simIcon.setImageResource(
                    when (simInfo.simSlotIndex) {
                        0 -> R.drawable.ic_sim_1
                        1 -> R.drawable.ic_sim_2
                        else -> R.drawable.ic_sim_card
                    }
                )

                // Set SIM name
                val displayName = simInfo.displayName?.toString()
                    ?: simInfo.carrierName?.toString()
                    ?: "SIM $displayNumber"
                simName.text = displayName

                // Set phone number if available
                val number = simInfo.number
                simNumber.text = if (!number.isNullOrBlank()) {
                    number
                } else {
                    "Slot ${simInfo.simSlotIndex + 1}"
                }
                simNumber.visibility = if (!number.isNullOrBlank()) View.VISIBLE else View.GONE

                // Set carrier name
                simCarrier.text = simInfo.carrierName?.toString() ?: ""
                simCarrier.visibility = if (simInfo.carrierName.isNullOrBlank())
                    View.GONE else View.VISIBLE

                // Handle click
                itemView.setOnClickListener {
                    onSimClick(simInfo)
                }
            }
        }
    }

    override fun getTheme(): Int {
        return R.style.BottomSheetDialogTheme
    }
}

/**
 * Helper object for SIM selection
 */
object SimSelectionHelper {

    private const val PREFS_NAME = "sim_preferences"
    private const val KEY_DEFAULT_SIM_SLOT = "default_sim_slot"

    /**
     * Check if device has multiple active SIMs
     */
    fun hasMultipleSims(context: android.content.Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return false
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return false

        return try {
            val activeSims = subscriptionManager.activeSubscriptionInfoList
            (activeSims?.size ?: 0) > 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the number of active SIMs
     */
    fun getSimCount(context: android.content.Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return 1
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            return 1
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return 1

        return try {
            subscriptionManager.activeSubscriptionInfoList?.size ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Save default SIM preference
     */
    fun setDefaultSimSlot(context: android.content.Context, slotIndex: Int) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DEFAULT_SIM_SLOT, slotIndex)
            .apply()
    }

    /**
     * Get default SIM slot preference
     */
    fun getDefaultSimSlot(context: android.content.Context): Int {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_SIM_SLOT, 0)
    }

    /**
     * Show SIM selection dialog if multiple SIMs available
     * Returns true if dialog shown, false otherwise
     */
    fun showIfNeeded(
        fragmentManager: androidx.fragment.app.FragmentManager,
        onSimSelected: (PhoneAccountHandle) -> Unit
    ): Boolean {
        // Check if we have multiple SIMs
        // Note: This needs to be checked from a Context, which should be passed
        // For now, we'll show the dialog and it will handle empty state

        val bottomSheet = SimSelectionBottomSheet.newInstance()
        bottomSheet.setOnSimSelectedListener(onSimSelected)
        bottomSheet.show(fragmentManager, SimSelectionBottomSheet::class.java.simpleName)
        return true
    }
}
