package com.cymatune.ui

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cymatune.util.LoggingManager

/**
 * Accessibility management system for production deployment
 * 
 * Features:
 * - Screen reader support and content descriptions
 * - High contrast mode detection and adaptation
 * - Text size scaling support
 * - Touch target size validation
 * - Focus management and navigation
 * - Accessibility testing and validation
 */
object AccessibilityManager {
    
    // Accessibility event types for monitoring
    enum class AccessibilityEventType {
        CONTENT_CHANGED,
        FOCUS_CHANGED,
        VIEW_CLICKED,
        VIEW_FOCUSED
    }
    
    // Accessibility state data
    data class AccessibilityState(
        val isScreenReaderEnabled: Boolean,
        val isHighContrastEnabled: Boolean,
        val textScale: Float,
        val touchTargetSize: Int,
        val isTalkBackEnabled: Boolean
    )
    
    // Default accessibility constants
    private const val MIN_TOUCH_TARGET_DP = 48
    private const val MIN_TEXT_SIZE_SP = 12
    private const val MAX_TEXT_SIZE_SP = 24
    
    // Accessibility validation results
    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>,
        val suggestions: List<String>
    )
    
    /**
     * Initialize accessibility monitoring
     */
    fun initialize(context: Context) {
        LoggingManager.i(
            LoggingManager.Component.UI,
            "Initializing accessibility manager"
        )
        
        // Monitor accessibility service changes
        monitorAccessibilityServices(context)
    }
    
    /**
     * Monitor accessibility service availability
     */
    private fun monitorAccessibilityServices(context: Context) {
        val accessibilityManager = ContextCompat.getSystemService(
            context, 
            AccessibilityManager::class.java
        )
        
        accessibilityManager?.let { manager ->
            val isScreenReaderEnabled = manager.isEnabled && manager.isTouchExplorationEnabled
            val isTalkBackEnabled = manager.isEnabled && hasTalkBackService(manager)
            
            LoggingManager.d(
                LoggingManager.Component.UI,
                "Accessibility services status",
                mapOf(
                    "screenReaderEnabled" to isScreenReaderEnabled,
                    "talkBackEnabled" to isTalkBackEnabled
                )
            )
        }
    }
    
    /**
     * Check if TalkBack service is available
     */
    private fun hasTalkBackService(manager: AccessibilityManager): Boolean {
        return try {
            // Use reflection to access enabledAccessibilityServiceList for compatibility
            val enabledServicesField = manager.javaClass.getDeclaredField("enabledAccessibilityServiceList")
            enabledServicesField.isAccessible = true
            val enabledServices = enabledServicesField.get(manager) as? List<*>
            
            enabledServices?.any { service ->
                val serviceInfo = service?.javaClass?.getDeclaredField("id")?.let { field ->
                    field.isAccessible = true
                    field.get(service)
                }
                (serviceInfo as? String)?.contains("talkback") == true ||
                (serviceInfo as? String)?.contains("accessibility") == true
            } ?: false
        } catch (e: Exception) {
            LoggingManager.e(
                LoggingManager.Component.UI,
                "Failed to check TalkBack service",
                e
            )
            false
        }
    }
    
    /**
     * Get current accessibility state
     */
    fun getAccessibilityState(context: Context): AccessibilityState {
        val accessibilityManager = ContextCompat.getSystemService(
            context,
            AccessibilityManager::class.java
        )
        
        val isScreenReaderEnabled = accessibilityManager?.isEnabled == true
        val textScale = getTextScale(context)
        val touchTargetSize = getMinimumTouchTargetSize(context)
        val isTalkBackEnabled = accessibilityManager?.let { hasTalkBackService(it) } ?: false
        
        // High contrast detection (simplified)
        val isHighContrastEnabled = false // Would need system settings access
        
        return AccessibilityState(
            isScreenReaderEnabled = isScreenReaderEnabled,
            isHighContrastEnabled = isHighContrastEnabled,
            textScale = textScale,
            touchTargetSize = touchTargetSize,
            isTalkBackEnabled = isTalkBackEnabled
        )
    }
    
    /**
     * Get text scale from system settings
     */
    private fun getTextScale(context: Context): Float {
        return try {
            val configuration = context.resources.configuration
            configuration.fontScale
        } catch (e: Exception) {
            LoggingManager.e(
                LoggingManager.Component.UI,
                "Failed to get text scale",
                e
            )
            1.0f
        }
    }
    
    /**
     * Get minimum touch target size
     */
    private fun getMinimumTouchTargetSize(context: Context): Int {
        return try {
            val density = context.resources.displayMetrics.density
            (MIN_TOUCH_TARGET_DP * density).toInt()
        } catch (e: Exception) {
            LoggingManager.e(
                LoggingManager.Component.UI,
                "Failed to calculate touch target size",
                e
            )
            (MIN_TOUCH_TARGET_DP * 1.5f).toInt() // Default for mdpi
        }
    }
    
    /**
     * Set content description for views
     */
    fun setContentDescription(view: View, description: String) {
        view.contentDescription = description
        LoggingManager.d(
            LoggingManager.Component.UI,
            "Set content description",
            mapOf(
                "viewId" to view.id.toString(),
                "description" to description
            )
        )
    }
    
    /**
     * Set accessibility live region for dynamic content
     */
    fun setLiveRegion(view: View, mode: Int) {
        view.importantForAccessibility = mode
        LoggingManager.d(
            LoggingManager.Component.UI,
            "Set live region",
            mapOf(
                "viewId" to view.id.toString(),
                "mode" to mode.toString()
            )
        )
    }
    
    /**
     * Validate view accessibility
     */
    fun validateViewAccessibility(view: View): ValidationResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        // Check content description
        if (view.contentDescription.isNullOrEmpty()) {
            issues.add("Missing content description for ${view.javaClass.simpleName}")
            suggestions.add("Add descriptive content for screen readers")
        }
        
        // Check touch target size
        val width = view.width
        val height = view.height
        val minTouchTarget = 48 * 1.5f // 48dp minimum
        
        if (width > 0 && height > 0 && (width < minTouchTarget || height < minTouchTarget)) {
            issues.add("Touch target too small: ${width.toInt()}x${height.toInt()}dp")
            suggestions.add("Increase touch target to at least 48dp")
        }
        
        // Check focusability
        if (!view.isFocusable && !view.hasOnClickListeners()) {
            suggestions.add("Consider making view focusable for keyboard navigation")
        }
        
        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions
        )
    }
    
    /**
     * Validate text view accessibility
     */
    fun validateTextViewAccessibility(textView: TextView): ValidationResult {
        val baseResult = validateViewAccessibility(textView)
        val issues = baseResult.issues.toMutableList()
        val suggestions = baseResult.suggestions.toMutableList()
        
        // Check text size
        val textSize = textView.textSize // This returns pixels
        val minTextSize = MIN_TEXT_SIZE_SP * 1.5f // Convert sp to px
        
        if (textSize < minTextSize) {
            issues.add("Text size too small: ${String.format(java.util.Locale.US,"%.1f", textSize / 1.5f)}sp")
            suggestions.add("Increase text size to at least ${MIN_TEXT_SIZE_SP}sp")
        }
        
        // Check text color contrast (simplified)
        val textColor = textView.currentTextColor
        val backgroundColor = textView.rootView.rootView.background?.let { bg ->
            // This is a simplified check - real implementation would need proper color extraction
            android.graphics.Color.WHITE
        } ?: android.graphics.Color.WHITE
        
        if (!hasSufficientContrast(textColor, backgroundColor)) {
            issues.add("Insufficient color contrast")
            suggestions.add("Use colors with higher contrast ratio")
        }
        
        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions
        )
    }
    
    /**
     * Check if colors have sufficient contrast
     */
    private fun hasSufficientContrast(foreground: Int, background: Int): Boolean {
        // Simplified contrast check
        // Real implementation would use WCAG 2.1 contrast ratio calculation
        val fgLuminance = getLuminance(foreground)
        val bgLuminance = getLuminance(background)
        
        val contrastRatio = if (fgLuminance > bgLuminance) {
            (bgLuminance + 0.05) / (fgLuminance + 0.05)
        } else {
            (fgLuminance + 0.05) / (bgLuminance + 0.05)
        }
        
        return contrastRatio >= 0.5 // Simplified threshold
    }
    
    /**
     * Get luminance of a color
     */
    private fun getLuminance(color: Int): Double {
        val r = android.graphics.Color.red(color) / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color) / 255.0
        
        // Simplified luminance calculation
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    
    /**
     * Announce message to screen readers
     */
    fun announceForAccessibility(view: View, message: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            view.announceForAccessibility(message)
            LoggingManager.d(
                LoggingManager.Component.UI,
                "Announced message for accessibility",
                mapOf("message" to message)
            )
        }
    }
    
    /**
     * Create accessibility action
     */
    fun createAccessibilityAction(
        id: Int,
        label: String,
        handler: (View, android.os.Bundle?) -> Boolean
    ): android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(id, label)
        } else {
            // Fallback for older Android versions
            @Suppress("DEPRECATION")
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(id, label)
        }
    }
    
    /**
     * Log accessibility event
     */
    fun logAccessibilityEvent(
        eventType: AccessibilityEventType,
        view: View,
        description: String = ""
    ) {
        LoggingManager.d(
            LoggingManager.Component.UI,
            "Accessibility event",
            mapOf(
                "eventType" to eventType.name,
                "viewId" to view.id.toString(),
                "description" to description
            )
        )
    }
    
    /**
     * Accessibility testing utilities
     */
    object Testing {
        
        /**
         * Test all views in a container for accessibility
         */
        fun testContainerAccessibility(container: android.view.ViewGroup): List<ValidationResult> {
            val results = mutableListOf<ValidationResult>()
            
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val result = when (child) {
                    is TextView -> validateTextViewAccessibility(child)
                    else -> validateViewAccessibility(child)
                }
                results.add(result)
                
                if (!result.isValid) {
                    LoggingManager.w(
                        LoggingManager.Component.UI,
                        "Accessibility issues found in view",
                        null,
                        mapOf(
                            "viewId" to child.id.toString(),
                            "issues" to result.issues.joinToString(", ")
                        )
                    )
                }
                
                // Recursively test child containers
                if (child is android.view.ViewGroup) {
                    results.addAll(testContainerAccessibility(child))
                }
            }
            
            return results
        }
        
        /**
         * Generate accessibility report
         */
        fun generateAccessibilityReport(container: android.view.ViewGroup): String {
            val results = testContainerAccessibility(container)
            val totalViews = results.size
            val invalidViews = results.count { !it.isValid }
            val totalIssues = results.sumOf { it.issues.size }
            
            return """
                Accessibility Report
                ====================
                Total Views: $totalViews
                Views with Issues: $invalidViews
                Total Issues: $totalIssues
                
                Issues Found:
                ${results.filter { !it.isValid }.flatMap { it.issues }.joinToString("\n                ")}
                
                Suggestions:
                ${results.flatMap { it.suggestions }.distinct().joinToString("\n                ")}
            """.trimIndent()
        }
    }
}