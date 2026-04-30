package com.cymatune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.cymatune.dialer.DialerFragment
import com.cymatune.ui.SettingsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.forEach

class MainActivity : AppCompatActivity() {
    // Required permissions (blocking - app cannot function without these)
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            add(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }.toTypedArray()

    // Notification permission (required for Android 13+ to show threat alerts)
    private val notificationPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

// SMS permissions are always available in this version
private val optionalSMSPermissions = arrayOf(
Manifest.permission.RECEIVE_SMS,
Manifest.permission.READ_SMS
)
    
    // Track navigation history for proper back gesture behavior
    private val navigationHistory = mutableListOf<Int>()
    private var useModernBackNavigation = true // Toggle between modern and fallback behavior

    // Launcher for required permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startFakeTowerDetectionService()
            setupNavigationUI(null)
            // After required permissions granted, offer optional SMS permissions
            showSMSPermissionExplanation()
        } else {
            // Check if we should show rationale or if user permanently denied
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)) {
                showPermissionRationaleDialog()
            } else {
                showPermissionSettingsDialog()
            }
        }
    }
    
    // Launcher for optional SMS permissions
    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            android.util.Log.i("MainActivity", "SMS permissions granted - Silent SMS detection enabled")
        } else {
            android.util.Log.i("MainActivity", "SMS permissions denied - Continuing with network-only detection")
        }
        // App continues regardless of SMS permission decision
        requestDialerRole()
    }

    // Role request launcher for Default Dialer
    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            android.util.Log.i("MainActivity", "Default Dialer role granted")
        } else {
            android.util.Log.w("MainActivity", "Default Dialer role denied")
        }
    }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // SECURITY CHECK: Block Rooted Devices
        if (com.cymatune.security.RootDetectionUtil.isDeviceRooted()) {
            MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
                .setTitle("Security Violation")
                .setMessage("Root access detected. For security reasons, Cymatune cannot run on rooted devices.")
                .setPositiveButton("Exit") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }
        
        // Enable edge-to-edge display for better back gesture support
        enableEdgeToEdge()
        
        // Set up back gesture handling
        setupBackGestureHandling()
        
        // Check if required permissions are already granted
        val allRequiredPermissions = requiredPermissions + notificationPermission
        val hasRequiredPermissions = allRequiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        
        if (hasRequiredPermissions) {
            startFakeTowerDetectionService()
            setupNavigationUI(savedInstanceState)
            // Check if we should offer SMS permissions
            checkAndOfferSMSPermissions()
            // Check for Dialer Role
            requestDialerRole()
            // Handle dial intent data (tel:xxx from external apps)
            handleDialIntent()
        } else {
            // Request required permissions (including notifications for Android 13+)
            permissionLauncher.launch(allRequiredPermissions)
        }
    }

    /**
     * Handle dial intents from external apps (tel:xxx)
     * Extracts the phone number and populates the dialer
     */
    private fun handleDialIntent() {
        val dataUri = intent.data
        if (dataUri != null && dataUri.scheme == "tel") {
            val phoneNumber = dataUri.schemeSpecificPart
            if (!phoneNumber.isNullOrBlank()) {
                // Store the number for the DialerFragment to pick up
                intent.putExtra("pending_dial_number", phoneNumber)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Update intent for hot starts
        setIntent(intent)
        // Handle dial intent when app is already running
        handleDialIntent()
    }
    
    fun requestDialerRole() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(android.app.role.RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
                roleRequestLauncher.launch(intent)
            }
        } else {
            val telecomManager = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            if (packageName != telecomManager.defaultDialerPackage) {
                val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                startActivity(intent)
            }
        }
    }
    
    private fun startFakeTowerDetectionService() {
        val intent = android.content.Intent(this, com.cymatune.service.FakeTowerDetectionService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun setupNavigationUI(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        
// Load Dialer fragment by default
if (savedInstanceState == null) {
loadFragment(DialerFragment())
// Initialize navigation history with Dialer tab (index 0)
navigationHistory.add(0)
}
        
        // Set up bottom navigation listener
        setupBottomNavigationListener()
        
        // Force initial tab highlight
        updateBottomNavigationHighlight()
    }
    
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null) // Add to back stack for proper back navigation
            .commit()
    }
    
    private fun loadFragmentWithoutBackstack(fragment: Fragment) {
        // Fallback method that doesn't use back stack (original behavior)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

fun switchToTab(tabIndex: Int, filter: String?) {
val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)

// Only two tabs: 0 = Dialer, 1 = Settings
val fragment = when (tabIndex) {
    1 -> SettingsFragment()
    else -> DialerFragment()
}
        
        // Use appropriate loading method based on back navigation mode
        if (useModernBackNavigation) {
            loadFragment(fragment)
        } else {
            loadFragmentWithoutBackstack(fragment)
        }
        
        // Force update bottom navigation to match the fragment
        updateBottomNavigationHighlightForTab(tabIndex)
        
        // Add additional verification after fragment transaction completes
        bottomNav.postDelayed({
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            val currentTab = getCurrentBottomNavigationTab()
            val expectedTab = tabIndex
            
            android.util.Log.d("MainActivity", "VERIFICATION: Expected: $expectedTab, Current: $currentTab, Fragment: ${currentFragment?.javaClass?.simpleName}")
            
            if (currentTab != expectedTab) {
                android.util.Log.w("MainActivity", "VERIFICATION: Tab mismatch after switchToTab, forcing correction")
                updateBottomNavigationHighlightWithRetry(2)
            }
        }, 100)
        
        // Update navigation history for modern behavior
        if (useModernBackNavigation) {
            if (navigationHistory.isEmpty() || navigationHistory.last() != tabIndex) {
                navigationHistory.add(tabIndex)
                android.util.Log.d("MainActivity", "switchToTab added to history: $tabIndex, history: $navigationHistory")
            }
        }
        
        // Debug log to validate navigation flow
        android.util.Log.d("MainActivity", "Navigation: tabIndex=$tabIndex, filter=$filter, mode=${if (useModernBackNavigation) "modern" else "fallback"}")
    }
    
private fun getCurrentBottomNavigationTab(): Int {
    val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
    return bottomNav.selectedItemId.let { itemId ->
        when (itemId) {
            R.id.navigation_dialer -> 0
            R.id.navigation_settings -> 1
            else -> 0
        }
    }
}
    
    private fun updateBottomNavigationHighlight() {
        updateBottomNavigationHighlightWithRetry(1)
    }
    
    private fun updateBottomNavigationHighlightWithRetry(maxRetries: Int) {
        var retryCount = 0
        
        fun attemptUpdate() {
            retryCount++
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            val currentTab = getCurrentBottomNavigationTab()
            
            android.util.Log.d("MainActivity", "UPDATE ATTEMPT $retryCount/$maxRetries - Fragment: ${currentFragment?.javaClass?.simpleName}, CurrentTab: $currentTab")
            
val targetItemId = when (currentFragment) {
is DialerFragment -> R.id.navigation_dialer
is SettingsFragment -> R.id.navigation_settings
else -> {
android.util.Log.w("MainActivity", "UPDATE: Unknown fragment type: ${currentFragment?.javaClass?.simpleName}, defaulting to Dialer")
R.id.navigation_dialer
}
}

val targetTab = when (targetItemId) {
R.id.navigation_dialer -> 0
R.id.navigation_settings -> 1
else -> 0
}
            
            android.util.Log.d("MainActivity", "UPDATE: Target tab: $targetTab ($targetItemId)")
            
            if (currentTab != targetTab) {
                android.util.Log.d("MainActivity", "UPDATE: Tab mismatch detected, forcing update")
                updateBottomNavigationHighlightForItemId(targetItemId)
                
                // If we haven't reached max retries and fragment still doesn't match, retry after delay
                if (retryCount < maxRetries) {
                    android.util.Log.d("MainActivity", "UPDATE: Scheduling retry in 100ms")
                    findViewById<BottomNavigationView>(R.id.bottomNavigation).postDelayed({ attemptUpdate() }, 100)
                } else {
                    android.util.Log.w("MainActivity", "UPDATE: Max retries reached, update may have failed")
                }
            } else {
                android.util.Log.d("MainActivity", "UPDATE: Tab already correct, no update needed")
            }
        }
        
        attemptUpdate()
    }
    
private fun updateBottomNavigationHighlightForTab(tabIndex: Int) {
val targetItemId = when (tabIndex) {
0 -> R.id.navigation_dialer
1 -> R.id.navigation_settings
else -> R.id.navigation_dialer
}
        
        updateBottomNavigationHighlightForItemId(targetItemId)
    }
    
    private fun updateBottomNavigationHighlightForItemId(targetItemId: Int) {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        val currentTab = bottomNav.selectedItemId
        
        android.util.Log.d("MainActivity", "updateBottomNavigationHighlight - Current: $currentTab, Target: $targetItemId")
        
        // Only update if different to avoid unnecessary updates
        if (currentTab != targetItemId) {
            // Temporarily remove the listener to avoid recursive calls
            bottomNav.setOnItemSelectedListener(null)
            
            // Set the correct tab as selected
            bottomNav.selectedItemId = targetItemId
            
            // Restore the listener
            setupBottomNavigationListener()
            
            android.util.Log.d("MainActivity", "Bottom navigation updated - now showing tab: $targetItemId")
        }
    }
    
    private fun setupBottomNavigationListener() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        
        // Handle bottom navigation item clicks
        bottomNav.setOnItemSelectedListener { item ->
val tabIndex = when (item.itemId) {
    R.id.navigation_dialer -> 0
    R.id.navigation_settings -> 1
    else -> 0
}
            
            // Track navigation history for modern back behavior
            if (useModernBackNavigation) {
                // Only add to history if navigating to a different tab
                if (navigationHistory.isEmpty() || navigationHistory.last() != tabIndex) {
                    navigationHistory.add(tabIndex)
                    android.util.Log.d("MainActivity", "Added to navigation history: $tabIndex, history: $navigationHistory")
                }
            }
            
            val fragment = when (item.itemId) {
    R.id.navigation_dialer -> DialerFragment()
    R.id.navigation_settings -> SettingsFragment()
    else -> DialerFragment()
}
            loadFragment(fragment)
            true
        }
    }

    private fun showPermissionRationaleDialog() {
        MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setTitle("Permissions Required")
            .setMessage("Cymatune needs Location and Phone State permissions to detect fake cell towers. Without these, the app cannot function.")
            .setPositiveButton("Grant") { _, _ ->
                permissionLauncher.launch(requiredPermissions)
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setTitle("Permissions Denied")
            .setMessage("You have permanently denied the required permissions. Please enable them in Settings to use Cymatune.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Check if SMS permissions should be offered to the user
     * Only show if not already granted and not previously denied
     */
    private fun checkAndOfferSMSPermissions() {
        val hasSMSPermissions = optionalSMSPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        
        if (!hasSMSPermissions) {
            // Check if user previously dismissed this
            val prefs = getSharedPreferences("cymatune_prefs", MODE_PRIVATE)
            val smsPermissionOffered = prefs.getBoolean("sms_permission_offered", false)
            
            if (!smsPermissionOffered) {
                // Show explanation dialog
                showSMSPermissionExplanation()
                // Mark as offered so we don't show it again
                prefs.edit().putBoolean("sms_permission_offered", true).apply()
            }
        }
    }
    
    /**
     * Show SMS permission explanation dialog
     * Explains the purpose of SMS permissions for silent SMS detection
     */
    private fun showSMSPermissionExplanation() {
        MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setTitle(R.string.sms_permission_title)
            .setMessage(R.string.sms_permission_message)
            .setPositiveButton(R.string.sms_permission_enable) { _, _ ->
                requestSMSPermissions()
            }
            .setNegativeButton(R.string.sms_permission_skip) { dialog, _ ->
                android.util.Log.i("MainActivity", "User skipped SMS permissions - Continuing with network-only detection")
                dialog.dismiss()
                requestDialerRole()
            }
            .setCancelable(true)
            .show()
    }
    
    /**
     * Request SMS permissions from user
     */
    private fun requestSMSPermissions() {
        val permissionsToRequest = optionalSMSPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            smsPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            requestDialerRole()
        }
    }

    /**
     * Enable edge-to-edge display for better back gesture support
     * This allows the system navigation bar to be hidden and enables proper back gestures
     */
    private fun enableEdgeToEdge() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // Make content appear behind navigation bar for edge-to-edge experience
        // Use modern approach to avoid deprecation warnings
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ - use WindowInsetsController
            window.setDecorFitsSystemWindows(false)
        } else {
            // Android 10 and below - use legacy approach
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    /**
     * Set up back gesture handling with proper navigation logic
     * Handles fragment back stack and app exit scenarios
     */
    private fun setupBackGestureHandling() {
        // Add back press callback
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }
        
        // Register the back press callback
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    /**
     * Handle back button/gesture navigation logic
     * Implements proper navigation hierarchy and exit confirmation
     */
    private fun handleBackNavigation() {
        try {
            val fragmentManager = supportFragmentManager
            val currentFragment = fragmentManager.findFragmentById(R.id.fragmentContainer)
            
            // Log back gesture for debugging
            android.util.Log.d("MainActivity", "Back gesture detected, current fragment: ${currentFragment?.javaClass?.simpleName}, mode=${if (useModernBackNavigation) "modern" else "fallback"}")
            
            if (useModernBackNavigation) {
                // Modern behavior: use fragment back stack and navigation history
                handleModernBackNavigation(currentFragment)
            } else {
                // Fallback behavior: original hardcoded navigation
                handleFallbackBackNavigation(currentFragment)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error handling back navigation", e)
            // Fallback: finish activity
            finish()
        }
    }
    
    /**
     * Handle modern back navigation with proper fragment stack and history
     */
    private fun handleModernBackNavigation(currentFragment: Fragment?) {
        val fragmentManager = supportFragmentManager
        
        // Try to pop from fragment back stack first
        if (fragmentManager.backStackEntryCount > 1) {
            android.util.Log.d("MainActivity", "Popping from fragment back stack (${fragmentManager.backStackEntryCount} entries)")
            
            // Get current state before popping
            val currentFragment = fragmentManager.findFragmentById(R.id.fragmentContainer)
            val beforeTab = getCurrentBottomNavigationTab()
            val beforeHistory = navigationHistory.toList()
            
            android.util.Log.d("MainActivity", "BEFORE POP - Fragment: ${currentFragment?.javaClass?.simpleName}, Tab: $beforeTab, History: $beforeHistory")
            
            fragmentManager.popBackStack()
            
            // Update navigation history
            if (navigationHistory.size > 1) {
                navigationHistory.removeAt(navigationHistory.size - 1)
                android.util.Log.d("MainActivity", "Updated navigation history: $navigationHistory")
            }
            
            // Get state after popping
            val afterFragment = fragmentManager.findFragmentById(R.id.fragmentContainer)
            val afterTab = getCurrentBottomNavigationTab()
            
            android.util.Log.d("MainActivity", "AFTER POP - Fragment: ${afterFragment?.javaClass?.simpleName}, Tab: $afterTab, History: $navigationHistory")
            
            // Force update bottom navigation to match the new fragment
            // Use post to ensure fragment transaction is committed
            findViewById<BottomNavigationView>(R.id.bottomNavigation).post {
                android.util.Log.d("MainActivity", "POST: Attempting to update bottom navigation after fragment pop")
                updateBottomNavigationHighlightWithRetry(3)
            }
            
            return
        }
        
        // If no back stack entries or on Dialer fragment, check navigation history
        val isOnHomeFragment = currentFragment is DialerFragment
        
        if (isOnHomeFragment) {
            // On main screen - show exit confirmation
            showExitConfirmation()
        } else {
            // Navigate back through history
            if (navigationHistory.size > 1) {
                navigationHistory.removeAt(navigationHistory.size - 1)
                val previousTab = navigationHistory.last()
                android.util.Log.d("MainActivity", "Navigating to previous tab: $previousTab, history: $navigationHistory")
                
                // Get current bottom navigation tab before switching
                val currentTab = getCurrentBottomNavigationTab()
                android.util.Log.d("MainActivity", "Before switchToTab - Current tab: $currentTab, Target tab: $previousTab")
                
                switchToTab(previousTab, null)
                
                // Add a small delay to ensure fragment transaction completes, then force tab update
                findViewById<BottomNavigationView>(R.id.bottomNavigation).postDelayed({
                    android.util.Log.d("MainActivity", "POST-DELAY: Forcing tab update after switchToTab")
                    updateBottomNavigationHighlightWithRetry(2)
                }, 50)
                
                // Get tab after switching
                val newTab = getCurrentBottomNavigationTab()
                android.util.Log.d("MainActivity", "After switchToTab - New tab: $newTab")
            } else {
                // No history, go to Dialer
                android.util.Log.d("MainActivity", "No navigation history, going to Dialer")
                switchToTab(0, null)
            }
        }
    }
    
    /**
     * Handle fallback back navigation (original behavior)
     */
    private fun handleFallbackBackNavigation(currentFragment: Fragment?) {
        // Check if we're on the Dialer fragment (main screen)
        val isOnDialerFragment = currentFragment is DialerFragment
        
        if (isOnDialerFragment) {
            // On main screen - show exit confirmation
            showExitConfirmation()
        } else {
            // Navigate back to Dialer fragment (original behavior)
            switchToTab(0, null) // 0 = Dialer tab
            android.util.Log.d("MainActivity", "Fallback: Navigating back to Dialer fragment")
        }
    }

    /**
     * Show exit confirmation dialog when user tries to leave the app
     */
    private fun showExitConfirmation() {
        MaterialAlertDialogBuilder(this, R.style.AlertDialogTheme)
            .setTitle("Exit Cymatune")
            .setMessage("Are you sure you want to exit? The fake tower detection service will continue running in the background.")
            .setPositiveButton("Exit") { _, _ ->
                android.util.Log.d("MainActivity", "User confirmed app exit")
                finish()
            }
            .setNegativeButton("Stay") { dialog, _ ->
                dialog.dismiss()
                android.util.Log.d("MainActivity", "User cancelled exit, staying in app")
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Override onKeyDown to handle hardware back button as well
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackNavigation()
            return true // Consume the event
        }
        return super.onKeyDown(keyCode, event)
    }
    
    /**
     * Toggle between modern and fallback back navigation behavior
     * Call this method to switch behavior modes for testing
     */
    fun toggleBackNavigationMode() {
        useModernBackNavigation = !useModernBackNavigation
        android.util.Log.d("MainActivity", "Toggled back navigation mode to: ${if (useModernBackNavigation) "modern" else "fallback"}")
        
        // Clear navigation history when switching modes
        if (!useModernBackNavigation) {
            navigationHistory.clear()
            android.util.Log.d("MainActivity", "Cleared navigation history")
}
}

/**
 * Get current back navigation mode for debugging
 */
fun isUsingModernBackNavigation(): Boolean = useModernBackNavigation
}
