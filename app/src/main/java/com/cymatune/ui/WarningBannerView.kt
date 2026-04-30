package com.cymatune.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cymatune.R

/**
 * Custom view for showing real-time signal analysis status and localized threat alerts.
 * Implements the expandable warning banner with details dropdown from Phase 2.5.
 */
class WarningBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val icon: ImageView
    private val messageText: TextView
    private val chevron: ImageView
    private val detailContainer: LinearLayout
    private val detailExplanation: TextView
    private val bannerHeader: View

    enum class BannerState {
        ACTIVE,     // Monitoring active, no threats
        INACTIVE,   // Signal analysis disabled
        THREAT      // THREAT DETECTED
    }

    private var currentState: BannerState = BannerState.INACTIVE
    private var isExpanded = false
    private var currentTowerId: Long? = null
    private var customAction: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_warning_banner, this, true)
        orientation = VERTICAL

        // Find views
        icon = findViewById(R.id.bannerIcon)
        messageText = findViewById(R.id.bannerMessage)
        chevron = findViewById(R.id.bannerChevron)
        detailContainer = findViewById(R.id.detailContainer)
        detailExplanation = findViewById(R.id.detailExplanation)
        bannerHeader = findViewById(R.id.bannerHeader)

        // Default state
        setBannerState(BannerState.INACTIVE)

        // Header click toggles expansion
        bannerHeader.setOnClickListener {
            toggleExpansion()
        }
    }

    private fun toggleExpansion() {
        isExpanded = !isExpanded

        if (isExpanded) {
            detailContainer.visibility = View.VISIBLE
            chevron.animate().rotation(180f).setDuration(200).start()
        } else {
            detailContainer.visibility = View.GONE
            chevron.animate().rotation(0f).setDuration(200).start()
        }
    }

    fun setBannerState(state: BannerState, customMessage: String? = null) {
        this.currentState = state

        when (state) {
            BannerState.ACTIVE -> {
                icon.setColorFilter(ContextCompat.getColor(context, R.color.primary_neon))
                messageText.text = customMessage ?: "Signal Analysis Active: Monitoring securely."
                detailContainer.visibility = View.GONE
                isExpanded = false
                chevron.rotation = 0f
            }
            BannerState.INACTIVE -> {
                icon.setColorFilter(ContextCompat.getColor(context, R.color.warning_yellow))
                messageText.text = customMessage ?: "Signal Analysis Inactive: Default role required."
                detailContainer.visibility = View.GONE
                isExpanded = false
                chevron.rotation = 0f
            }
            BannerState.THREAT -> {
                icon.setColorFilter(ContextCompat.getColor(context, R.color.warning_red))
                messageText.text = customMessage ?: "Threat Detected: Suspicious cell tower activity."
                // Keep detail container state - may already be showing
            }
        }
    }

    /**
     * Set banner with detailed information for threat alerts
     */
    fun setBannerDetails(
        message: String,
        explanation: String,
        towerId: Long? = null,
        expand: Boolean = false
    ) {
        this.currentTowerId = towerId
        this.currentState = BannerState.THREAT

        icon.setColorFilter(ContextCompat.getColor(context, R.color.warning_red))
        messageText.text = message
        detailExplanation.text = explanation

        if (expand && !isExpanded) {
            toggleExpansion()
        }
    }

    fun setOnActionListener(listener: () -> Unit) {
        customAction = listener
    }

    fun getCurrentTowerId(): Long? = currentTowerId

    fun isExpanded(): Boolean = isExpanded

    fun collapse() {
        if (isExpanded) {
            toggleExpansion()
        }
    }
}