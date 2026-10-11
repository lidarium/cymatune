package com.cymatune.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.cymatune.R
import com.cymatune.dialer.adapters.DialerPagerAdapter
import com.cymatune.ui.WarningBannerView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class DialerFragment : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var warningBanner: WarningBannerView

    private val threatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.cymatune.THREAT_DETECTED" -> {
                    val threatMessage = intent.getStringExtra("threat_message") ?: "Threat Detected"
                    val explanation = intent.getStringExtra("explanation") ?: "Suspicious cell tower activity detected."
                    val towerId = intent.getLongExtra("tower_id", -1L).takeIf { it != -1L }

                    warningBanner.setBannerDetails(
                        message = threatMessage,
                        explanation = explanation,
                        towerId = towerId,
                        expand = false
                    )
                }
                "com.cymatune.SERVICE_READY" -> {
                    updateBannerStatus()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_dialer, container, false)

        warningBanner = view.findViewById(R.id.warningBanner)
        tabLayout = view.findViewById(R.id.dialerTabLayout)
        viewPager = view.findViewById(R.id.dialerViewPager)

        setupBanner()
        setupPager()

        return view
    }

    override fun onResume() {
        super.onResume()
        updateBannerStatus()
        registerThreatReceiver()
        checkPendingDialNumber()
    }

    override fun onPause() {
        super.onPause()
        unregisterThreatReceiver()
    }

    private fun setupBanner() {
        warningBanner.setOnActionListener {
            if (warningBanner.isExpanded()) {
                warningBanner.collapse()
            } else if (warningBanner.getCurrentTowerId() != null) {
                warningBanner.setBannerDetails(
                    message = "Threat Detected",
                    explanation = "Suspicious cell tower activity detected.",
                    towerId = warningBanner.getCurrentTowerId(),
                    expand = false
                )
            } else {
                (requireActivity() as? com.cymatune.MainActivity)?.requestDialerRole()
            }
        }
    }

    private fun updateBannerStatus() {
        val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val isDefault = requireContext().packageName == telecomManager.defaultDialerPackage

        if (isDefault) {
            warningBanner.setBannerState(WarningBannerView.BannerState.ACTIVE)
        } else {
            warningBanner.setBannerState(WarningBannerView.BannerState.INACTIVE)
        }
    }

    private fun registerThreatReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.cymatune.THREAT_DETECTED")
            addAction("com.cymatune.SERVICE_READY")
        }
        ContextCompat.registerReceiver(requireContext(), threatReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterThreatReceiver() {
        try {
            requireContext().unregisterReceiver(threatReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    private fun setupPager() {
        viewPager.adapter = DialerPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Dial"
                1 -> "Recent"
                2 -> "Contacts"
                else -> null
            }
        }.attach()

        viewPager.offscreenPageLimit = 2
    }

    private fun checkPendingDialNumber() {
        val activity = requireActivity()
        val intent = activity.intent
        val pendingNumber = intent.getStringExtra("pending_dial_number")
        if (!pendingNumber.isNullOrBlank()) {
            intent.removeExtra("pending_dial_number")
            navigateToDialerWithNumber(pendingNumber)
        }
    }

    private fun navigateToDialerWithNumber(number: String) {
        viewPager.currentItem = 0
        val dialIntent = android.content.Intent("com.cymatune.POPULATE_DIALER").apply {
            putExtra("phone_number", number)
        }
        activity?.sendBroadcast(dialIntent)
    }
}
