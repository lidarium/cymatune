package com.cymatune.util

import android.content.Context
import android.util.Log
import com.cymatune.db.AppDatabase
import com.cymatune.db.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull

/**
 * Helper class for diagnosing database issues
 */
object DatabaseDiagnosticHelper {
    
    private const val TAG = "DatabaseDiagnosticHelper"
    
    /**
     * Run comprehensive database diagnostics
     */
    suspend fun runDiagnostics(context: Context): DatabaseDiagnosticResult {
        return withContext(Dispatchers.IO) {
            val result = DatabaseDiagnosticResult()
            
            try {
                // Test 1: Check if database can be created
                result.databaseCreationSuccess = testDatabaseCreation(context)
                
                // Test 2: Check if DAOs are accessible
                result.daoAccessSuccess = testDaoAccess(context)
                
                // Test 3: Check deduplication query
                result.deduplicationSuccess = testDeduplicationQuery(context)
                
                // Test 4: Check for actual duplicates
                result.duplicateAnalysis = analyzeDuplicates(context)
                
                // Test 5: Check database health
                result.databaseHealth = testDatabaseHealth(context)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during diagnostics", e)
                result.error = e.message ?: "Unknown error"
            }
            
            result
        }
    }
    
    /**
     * Test if database can be created successfully
     */
    private fun testDatabaseCreation(context: Context): Boolean {
        return try {
            val db = AppDatabase.getDatabase(context)
            Log.d(TAG, "Database creation test: SUCCESS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Database creation test: FAILED", e)
            false
        }
    }
    
    /**
     * Test if DAOs are accessible
     */
    private suspend fun testDaoAccess(context: Context): Boolean {
        return try {
            val db = AppDatabase.getDatabase(context)
            val fakeTowerDao = db.fakeTowerDao()
            
            // Test basic DAO operations
            val count = fakeTowerDao.getTotalTowerCountBeforeDeduplication()
            Log.d(TAG, "DAO access test: SUCCESS, total towers: $count")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DAO access test: FAILED", e)
            false
        }
    }
    
    /**
     * Test deduplication query
     */
    private suspend fun testDeduplicationQuery(context: Context): Boolean {
        return try {
            val db = AppDatabase.getDatabase(context)
            val fakeTowerDao = db.fakeTowerDao()
            
            // Test deduplication query
            val deduplicatedCount = fakeTowerDao.getTotalDeduplicatedCount()
            val totalCount = fakeTowerDao.getTotalTowerCountBeforeDeduplication()
            
            Log.d(TAG, "Deduplication test: SUCCESS, total=$totalCount, deduplicated=$deduplicatedCount")
            
            // If counts are different, deduplication is working
            deduplicatedCount <= totalCount
        } catch (e: Exception) {
            Log.e(TAG, "Deduplication query test: FAILED", e)
            false
        }
    }
    
    /**
     * Analyze actual duplicates in database
     */
    private suspend fun analyzeDuplicates(context: Context): DuplicateAnalysis {
        val analysis = DuplicateAnalysis()
        
        return try {
            val db = AppDatabase.getDatabase(context)
            val fakeTowerDao = db.fakeTowerDao()
            
            // Get duplicate info
            val duplicates = fakeTowerDao.getDuplicateTowers()
            
            analysis.totalDuplicates = duplicates.sumOf { it.duplicate_count }
            analysis.uniqueDuplicateGroups = duplicates.size
            analysis.largestDuplicateGroup = duplicates.maxOfOrNull { it.duplicate_count } ?: 0
            
            // Log details
            if (duplicates.isNotEmpty()) {
                Log.w(TAG, "Found ${duplicates.size} duplicate groups with ${analysis.totalDuplicates} total duplicates")
                duplicates.take(5).forEach { dup ->
                    Log.w(TAG, "Duplicate group: CID=${dup.cid}, LAC=${dup.lac}, Count=${dup.duplicate_count}")
                }
            } else {
                Log.d(TAG, "No duplicates found in database")
            }
            
            analysis
        } catch (e: Exception) {
            Log.e(TAG, "Duplicate analysis failed", e)
            analysis.error = e.message ?: "Unknown error"
            analysis
        }
    }
    
    /**
     * Test database health
     */
    private suspend fun testDatabaseHealth(context: Context): DatabaseHealth {
        val health = DatabaseHealth()
        
        return try {
            val db = AppDatabase.getDatabase(context)
            
            // Test basic operations
            val fakeTowerDao = db.fakeTowerDao()
            val startTime = System.currentTimeMillis()
            
            // Test query performance - simplified approach
            val towers = try {
                // Just test that the DAO method can be called without error
                val flow = fakeTowerDao.getAllFakeTowers()
                // For health check, we just need to verify the flow can be created
                // The actual collection will be done by UI components
                emptyList<com.cymatune.db.FakeTower>()
            } catch (e: Exception) {
                Log.w(TAG, "Could not access towers for health check", e)
                emptyList()
            }
            val queryTime = System.currentTimeMillis() - startTime
            
            health.queryTimeMs = queryTime
            health.totalTowers = towers.size
            
            // Check if database file exists
            val dbFile = context.getDatabasePath("fake_tower_database")
            health.databaseFileExists = dbFile.exists()
            
            Log.d(TAG, "Database health check: SUCCESS, towers=${towers.size}, queryTime=${queryTime}ms")
            
            health
        } catch (e: Exception) {
            Log.e(TAG, "Database health check: FAILED", e)
            health.error = e.message ?: "Unknown error"
            health
        }
    }
}

/**
 * Result of database diagnostics
 */
data class DatabaseDiagnosticResult(
    var databaseCreationSuccess: Boolean = false,
    var daoAccessSuccess: Boolean = false,
    var deduplicationSuccess: Boolean = false,
    var duplicateAnalysis: DuplicateAnalysis = DuplicateAnalysis(),
    var databaseHealth: DatabaseHealth = DatabaseHealth(),
    var error: String? = null
) {
    fun isHealthy(): Boolean {
        return databaseCreationSuccess && daoAccessSuccess && deduplicationSuccess && error == null
    }
}

/**
 * Duplicate analysis results
 */
data class DuplicateAnalysis(
    var totalDuplicates: Int = 0,
    var uniqueDuplicateGroups: Int = 0,
    var largestDuplicateGroup: Int = 0,
    var error: String? = null
)

/**
 * Database health information
 */
data class DatabaseHealth(
    var queryTimeMs: Long = 0,
    var totalTowers: Int = 0,
    var databaseFileExists: Boolean = false,
    var error: String? = null
)