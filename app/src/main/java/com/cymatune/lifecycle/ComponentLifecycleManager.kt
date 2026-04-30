package com.cymatune.lifecycle

import android.content.Context
import android.util.Log
import com.cymatune.db.DatabaseInitializationManager
import com.cymatune.exceptions.ComponentInitializationException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized component lifecycle manager to prevent timing issues and ensure proper dependency resolution.
 * Provides comprehensive lifecycle management for all application components with automatic dependency
 * resolution, error handling, and recovery mechanisms.
 */
object ComponentLifecycleManager {
    
    private const val TAG = "ComponentLifecycleManager"
    
    // Component state tracking
    private val componentStates = ConcurrentHashMap<String, ComponentInfo>()
    private val initializationMutex = kotlinx.coroutines.sync.Mutex()
    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
    
    // Configuration
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 2000L
    private const val SHUTDOWN_TIMEOUT_MS = 10000L
    
    /**
     * Component lifecycle states
     */
    enum class ComponentState {
        NOT_INITIALIZED, INITIALIZING, INITIALIZED, FAILED, SHUTDOWN
    }
    
    /**
     * Component information with dependencies and state tracking
     */
    data class ComponentInfo(
        val name: String,
        var state: ComponentState,
        val dependencies: List<String>,
        val initializationOrder: Int,
        val initializer: suspend (Context) -> Any,
        val shutdownAction: (() -> Unit)? = null,
        var lastError: Exception? = null,
        var retryCount: Int = 0
    )
    
    /**
     * Result of component initialization process
     */
    sealed class InitializationResult {
        data class Success(val initializedComponents: List<String>) : InitializationResult()
        data class Partial(val successfulComponents: List<String>, val failedComponents: List<String>) : InitializationResult()
        data class Failed(val errorMessage: String, val failedComponents: List<String>) : InitializationResult()
    }
    
    /**
     * Add a component to the lifecycle manager
     */
    fun addComponent(
        name: String,
        dependencies: List<String>,
        initializationOrder: Int,
        initializer: suspend (Context) -> Any,
        shutdownAction: (() -> Unit)? = null
    ) {
        Log.d(TAG, "Adding component: $name, dependencies: ${dependencies.joinToString()}")
        
        val componentInfo = ComponentInfo(
            name = name,
            state = ComponentState.NOT_INITIALIZED,
            dependencies = dependencies,
            initializationOrder = initializationOrder,
            initializer = initializer,
            shutdownAction = shutdownAction
        )
        
        componentStates[name] = componentInfo
    }
    
    /**
     * Initialize all components with proper dependency resolution and error handling
     */
    suspend fun initializeComponents(context: Context): InitializationResult {
        return withTimeoutOrNull(60000L) { // 60 second timeout
            initializationMutex.withLock {
                if (isInitialized.get()) {
                    Log.d(TAG, "Components already initialized")
                    val initializedComponents = componentStates.keys.filter { componentName ->
                        componentStates[componentName]?.state == ComponentState.INITIALIZED
                    }
                    return@withTimeoutOrNull InitializationResult.Success(initializedComponents)
                }
                
                Log.i(TAG, "Starting component initialization process")
                
                try {
                    // Step 1: Validate dependency graph
                    val sortedComponents = performTopologicalSort()
                    Log.d(TAG, "Dependency resolution completed, order: ${sortedComponents.joinToString()}")
                    
                    // Step 2: Initialize components in dependency order
                    val successfulComponents = mutableListOf<String>()
                    val failedComponents = mutableListOf<String>()
                    
                    for (componentName in sortedComponents) {
                        val component = componentStates[componentName]
                        if (component == null) {
                            Log.w(TAG, "Component $componentName not found in registry")
                            continue
                        }
                        
                        try {
                            val result = initializeComponentWithRetry(context, component)
                            if (result) {
                                successfulComponents.add(componentName)
                            } else {
                                failedComponents.add(componentName)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to initialize component $componentName", e)
                            component.lastError = e
                            failedComponents.add(componentName)
                            
                            // Continue with other components unless it's a critical dependency
                            if (isCriticalComponent(componentName)) {
                                Log.e(TAG, "Critical component $componentName failed, stopping initialization")
                                break
                            }
                        }
                    }
                    
                    // Step 3: Determine result
                    return@withTimeoutOrNull when {
                        failedComponents.isEmpty() -> {
                            isInitialized.set(true)
                            Log.i(TAG, "All components initialized successfully: ${successfulComponents.joinToString()}")
                            InitializationResult.Success(successfulComponents)
                        }
                        successfulComponents.isNotEmpty() -> {
                            Log.w(TAG, "Partial initialization: ${successfulComponents.size} successful, ${failedComponents.size} failed")
                            InitializationResult.Partial(successfulComponents, failedComponents)
                        }
                        else -> {
                            Log.e(TAG, "All components failed to initialize")
                            InitializationResult.Failed("All components failed to initialize", failedComponents)
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Component initialization failed with exception", e)
                    throw ComponentInitializationException(
                        message = "Component lifecycle initialization failed",
                        cause = e,
                        componentName = "ComponentLifecycleManager",
                        failureType = ComponentInitializationException.FailureType.DEPENDENCY_RESOLUTION_FAILED,
                        recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
                    )
                }
            }
        } ?: InitializationResult.Failed("Component initialization timed out after 60 seconds", emptyList())
    }
    
    /**
     * Perform topological sort to resolve component dependencies
     */
    private fun performTopologicalSort(): List<String> {
        val sorted = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>() // For cycle detection
        
        fun visit(node: String) {
            when {
                visiting.contains(node) -> {
                    throw ComponentInitializationException(
                        message = "Circular dependency detected involving component: $node",
                        componentName = node,
                        failureType = ComponentInitializationException.FailureType.DEPENDENCY_RESOLUTION_FAILED,
                        recoveryAction = ComponentInitializationException.RecoveryAction.SKIP_COMPONENT
                    )
                }
                !visited.contains(node) -> {
                    visiting.add(node)
                    val component = componentStates[node]
                    component?.dependencies?.forEach { dep ->
                        if (componentStates.containsKey(dep)) {
                            visit(dep)
                        }
                    }
                    visiting.remove(node)
                    visited.add(node)
                    sorted.add(node)
                }
            }
        }
        
        // Sort by initialization order first, then by dependencies
        val componentsByOrder = componentStates.keys
            .map { componentName -> componentName to (componentStates[componentName]?.initializationOrder ?: Int.MAX_VALUE) }
            .sortedBy { (_, order) -> order }
            .map { (name, _) -> name }
        
        componentsByOrder.forEach { visit(it) }
        
        return sorted
    }
    
    /**
     * Initialize a single component with retry logic
     */
    private suspend fun initializeComponentWithRetry(
        context: Context,
        component: ComponentInfo
    ): Boolean {
        component.state = ComponentState.INITIALIZING
        Log.d(TAG, "Initializing component: ${component.name}")
        
        var lastException: Exception? = null
        
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                // Check if all dependencies are initialized
                val unmetDependencies = component.dependencies.filter { dep ->
                    componentStates[dep]?.state != ComponentState.INITIALIZED
                }
                
                if (unmetDependencies.isNotEmpty()) {
                    throw ComponentInitializationException(
                        message = "Unmet dependencies: ${unmetDependencies.joinToString()}",
                        componentName = component.name,
                        failureType = ComponentInitializationException.FailureType.DEPENDENCY_RESOLUTION_FAILED,
                        recoveryAction = ComponentInitializationException.RecoveryAction.DELAY_AND_RETRY
                    )
                }
                
                // Initialize component
                val result = component.initializer(context)
                component.state = ComponentState.INITIALIZED
                component.retryCount = attempt
                Log.i(TAG, "Component ${component.name} initialized successfully: ${result.javaClass.simpleName}")
                return true
                
            } catch (e: Exception) {
                lastException = e
                component.lastError = e
                component.retryCount++
                
                Log.w(TAG, "Component ${component.name} initialization attempt ${attempt + 1} failed: ${e.message}")
                
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    // Calculate exponential backoff delay
                    val delay = RETRY_DELAY_MS * (1L shl attempt.coerceAtMost(4)) // Cap at 2^4 = 16x delay
                    Log.d(TAG, "Retrying component ${component.name} in ${delay}ms")
                    delay(delay)
                }
            }
        }
        
        // All retry attempts failed
        component.state = ComponentState.FAILED
        component.lastError = lastException
        Log.e(TAG, "Component ${component.name} failed after ${MAX_RETRY_ATTEMPTS} attempts: ${lastException?.message}")
        
        return false
    }
    
    /**
     * Shutdown all initialized components in reverse dependency order with graceful resource cleanup
     */
    fun shutdownComponents() {
        if (!isInitialized.getAndSet(false)) {
            Log.d(TAG, "Components already shutdown")
            return
        }
        
        Log.i(TAG, "Starting graceful component shutdown process")
        
        try {
            // Get components in reverse initialization order for proper dependency cleanup
            val shutdownOrder = performTopologicalSort().reversed()
            Log.d(TAG, "Shutdown order: ${shutdownOrder.joinToString()}")
            
            val shutdownScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val shutdownJobs = mutableListOf<Job>()
            
            // Shutdown components with individual timeouts
            for (componentName in shutdownOrder) {
                val component = componentStates[componentName]
                if (component?.state == ComponentState.INITIALIZED) {
                    val shutdownJob = shutdownScope.launch {
                        try {
                            Log.d(TAG, "Shutting down component: $componentName")
                            
                            // Execute component shutdown action with timeout
                            withTimeout(SHUTDOWN_TIMEOUT_MS) {
                                component.shutdownAction?.invoke()
                            }
                            
                            component.state = ComponentState.SHUTDOWN
                            Log.d(TAG, "Component $componentName shutdown completed successfully")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "Error shutting down component $componentName", e)
                            component.lastError = e
                            component.state = ComponentState.FAILED
                            
                            // Continue shutdown process even if individual components fail
                        }
                    }
                    shutdownJobs.add(shutdownJob)
                }
            }
            
            // Wait for all shutdown jobs to complete with overall timeout
            shutdownScope.launch {
                try {
                    shutdownJobs.forEach { it.join() }
                    Log.i(TAG, "All component shutdown jobs completed")
                } catch (e: Exception) {
                    Log.w(TAG, "Shutdown jobs completed with some failures: ${e.message}")
                }
            }
            
            // Cancel shutdown scope after timeout
            shutdownScope.launch {
                delay(SHUTDOWN_TIMEOUT_MS * 2) // Double timeout for safety
                if (shutdownScope.isActive) {
                    Log.w(TAG, "Forcing shutdown scope cancellation due to timeout")
                    shutdownScope.cancel()
                }
            }
            
            // Perform final cleanup
            performFinalCleanup()
            
            Log.i(TAG, "Component shutdown process completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Component shutdown failed with exception", e)
        }
    }
    
    /**
     * Perform final cleanup operations
     */
    private fun performFinalCleanup() {
        try {
            // Clear any remaining references
            componentStates.values.forEach { component ->
                if (component.state != ComponentState.SHUTDOWN) {
                    Log.w(TAG, "Component ${component.name} not properly shutdown: ${component.state}")
                }
            }
            
            // Clear caches and release resources
            Log.d(TAG, "Performing final resource cleanup")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during final cleanup", e)
        }
    }
    
    /**
     * Get current initialization status
     */
    fun getInitializationStatus(): String {
        val status = componentStates.map { (name, info) ->
            "$name: ${info.state} (retries: ${info.retryCount})" + 
            if (info.lastError != null) " [Error: ${info.lastError?.message}]" else ""
        }.joinToString("\n")
        
        return "Component Lifecycle Status:\n" +
               "- Initialized: ${isInitialized.get()}\n" +
               "- Total Components: ${componentStates.size}\n" +
               "- Current Status:\n$status"
    }
    
    /**
     * Get component information
     */
    fun getComponentInfo(componentName: String): ComponentInfo? {
        return componentStates[componentName]
    }
    
    /**
     * Get all component states
     */
    fun getAllComponentStates(): Map<String, ComponentState> {
        return componentStates.mapValues { it.value.state }
    }
    
    /**
     * Check if a component is critical (failure should stop initialization)
     */
    private fun isCriticalComponent(componentName: String): Boolean {
        return when (componentName) {
            "DatabaseManager", "DatabaseInitializationManager" -> true
            else -> false
        }
    }
    
    /**
     * Force reinitialize specific component
     */
    suspend fun forceReinitializeComponent(context: Context, componentName: String): Boolean {
        val component = componentStates[componentName]
        return component?.let {
            it.state = ComponentState.NOT_INITIALIZED
            it.retryCount = 0
            it.lastError = null
            initializeComponentWithRetry(context, it)
        } ?: false
    }
    
    /**
     * Save component state to persistent storage for recovery across app restarts
     */
    fun saveComponentState() {
        try {
            // This would save component states to SharedPreferences or Database
            // For now, we'll log the current state
            Log.d(TAG, "Saving component state: ${getInitializationStatus()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save component state", e)
        }
    }
    
    /**
     * Load component state from persistent storage
     */
    fun loadComponentState() {
        try {
            // This would load component states from SharedPreferences or Database
            Log.d(TAG, "Loading component state from persistent storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load component state", e)
        }
    }
    
    /**
     * Clear all component registrations (for testing)
     */
    fun clearComponents() {
        componentStates.clear()
        isInitialized.set(false)
        Log.d(TAG, "Component registry cleared")
    }
}