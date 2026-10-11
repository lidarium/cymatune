package com.cymatune.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cymatune.db.DatabaseManager
import com.cymatune.db.FakeTower
import com.cymatune.db.NeighborHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DataExportManager - Handles encrypted export and import of tower observation data
 * 
 * Features:
 * - Export all tower data as encrypted JSON
 * - Import from previously exported backups
 * - Uses AES-256-GCM encryption with device-derived key
 * - 3-5 year data retention with configurable cleanup
 */
object DataExportManager {
    private const val TAG = "DataExportManager"
    private const val EXPORT_VERSION = 1
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    
    // Default retention: 3 years (in milliseconds)
    const val DEFAULT_RETENTION_YEARS = 3
    private const val MILLIS_PER_YEAR = 365L * 24 * 60 * 60 * 1000
    
    /**
     * Export all tower data to an encrypted JSON file
     * @param context Application context
     * @param outputUri URI where to save the export file (via SAF)
     * @param encryptionKey 32-byte AES key (derived from license key)
     * @return ExportResult indicating success or failure
     */
    suspend fun exportData(
        context: Context,
        outputUri: Uri,
        encryptionKey: ByteArray
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting data export to $outputUri")
            
            // Collect all data
            val towerDao = DatabaseManager.towerDao
            val fakeTowerDao = DatabaseManager.fakeTowerDao
            val neighborHistoryDao = DatabaseManager.neighborHistoryDao
            
            val towerInfoList = towerDao.getAllTowerInfo().first()
            val fakeTowerList = fakeTowerDao.getAllFakeTowers().first()
            
            // Build JSON structure
            val exportJson = JSONObject().apply {
                put("version", EXPORT_VERSION)
                put("exportTime", System.currentTimeMillis())
                put("exportDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("appVersion", com.cymatune.BuildConfig.VERSION_NAME)
                
                // Tower Info
                put("towerInfo", JSONArray().apply {
                    towerInfoList.forEach { tower ->
                        put(JSONObject().apply {
                            put("cid", tower.cid)
                            put("lac", tower.lac)
                            put("mcc", tower.mcc)
                            put("mnc", tower.mnc)
                            put("firstSeen", tower.firstSeen)
                            put("lastSeen", tower.lastSeen)
                            put("lastConnectedTime", tower.lastConnectedTime)
                            put("disconnectionCount", tower.disconnectionCount)
                            tower.estimatedTowerLocation?.let {
                                put("estimatedLat", it.first)
                                put("estimatedLon", it.second)
                            }
                            put("suspicionReason", tower.suspicionReason ?: "")
                            put("suspicionConfidence", tower.suspicionConfidence ?: 0.0)
                            put("suspicionLevel", tower.suspicionLevel)
                        })
                    }
                })
                
                // Fake/Suspicious Towers
                put("fakeTowers", JSONArray().apply {
                    fakeTowerList.forEach { tower ->
                        put(JSONObject().apply {
                            put("cid", tower.cid)
                            put("lac", tower.lac)
                            put("mcc", tower.mcc)
                            put("mnc", tower.mnc)
                            put("latitude", tower.latitude)
                            put("longitude", tower.longitude)
                            put("detectionTime", tower.detectionTime)
                            put("accuracy", tower.accuracy)
                            put("reason", tower.reason)
                            put("signalStrength", tower.signalStrength)
                            put("networkType", tower.networkType)
                            put("observationCount", tower.observationCount)
                            put("precisionRadius", tower.precisionRadius)
                        })
                    }
                })
                
                // Statistics
                put("statistics", JSONObject().apply {
                    put("totalTowers", towerInfoList.size)
                    put("suspiciousTowers", fakeTowerList.size)
                    put("oldestRecord", towerInfoList.minOfOrNull { it.firstSeen } ?: 0)
                    put("newestRecord", towerInfoList.maxOfOrNull { it.lastSeen } ?: 0)
                })
            }
            
            val jsonBytes = exportJson.toString(2).toByteArray(Charsets.UTF_8)
            
            // Encrypt with AES-256-GCM
            val iv = ByteArray(GCM_IV_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            
            // Write to output
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                BufferedOutputStream(outputStream).use { buffered ->
                    // Write header: "CYMA" magic + version + IV
                    buffered.write("CYMA".toByteArray())
                    buffered.write(byteArrayOf(EXPORT_VERSION.toByte()))
                    buffered.write(iv)
                    
                    // Write encrypted data
                    CipherOutputStream(buffered, cipher).use { cipherStream ->
                        cipherStream.write(jsonBytes)
                    }
                }
            } ?: throw IOException("Failed to open output stream")
            
            Log.i(TAG, "Export completed: ${towerInfoList.size} towers, ${fakeTowerList.size} suspicious")
            
            ExportResult.Success(
                towersExported = towerInfoList.size,
                suspiciousTowersExported = fakeTowerList.size,
                fileSizeBytes = jsonBytes.size.toLong()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            ExportResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Import tower data from an encrypted backup file
     * @param context Application context
     * @param inputUri URI of the backup file
     * @param encryptionKey 32-byte AES key (same key used for export)
     * @param mergeMode How to handle existing data
     * @return ImportResult indicating success or failure
     */
    suspend fun importData(
        context: Context,
        inputUri: Uri,
        encryptionKey: ByteArray,
        mergeMode: MergeMode = MergeMode.SKIP_EXISTING
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting data import from $inputUri")
            
            val inputBytes = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                ?: throw IOException("Failed to open input stream")
            
            // Verify header
            if (inputBytes.size < 17) throw IOException("File too small")
            val magic = String(inputBytes.copyOfRange(0, 4))
            if (magic != "CYMA") throw IOException("Invalid file format")
            
            val version = inputBytes[4].toInt()
            if (version != EXPORT_VERSION) throw IOException("Unsupported version: $version")
            
            val iv = inputBytes.copyOfRange(5, 5 + GCM_IV_LENGTH)
            val encryptedData = inputBytes.copyOfRange(5 + GCM_IV_LENGTH, inputBytes.size)
            
            // Decrypt
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedData)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            val importJson = JSONObject(jsonString)
            
            // Parse and import
            val towerDao = DatabaseManager.towerDao
            val fakeTowerDao = DatabaseManager.fakeTowerDao
            
            var towersImported = 0
            var suspiciousImported = 0
            var skipped = 0
            
            // Import tower info
            val towerInfoArray = importJson.getJSONArray("towerInfo")
            for (i in 0 until towerInfoArray.length()) {
                val obj = towerInfoArray.getJSONObject(i)
                val existing = towerDao.getTowerInfo(
                    obj.getInt("cid"), obj.getInt("lac"),
                    obj.getInt("mcc"), obj.getInt("mnc")
                )
                
                when {
                    existing == null -> {
                        // Insert new
                        val towerInfo = TowerInfo(
                            cid = obj.getInt("cid"),
                            lac = obj.getInt("lac"),
                            mcc = obj.getInt("mcc"),
                            mnc = obj.getInt("mnc"),
                            firstSeen = obj.getLong("firstSeen"),
                            lastSeen = obj.getLong("lastSeen"),
                            lastConnectedTime = obj.optLong("lastConnectedTime", 0),
                            disconnectionCount = obj.optInt("disconnectionCount", 0),
                            estimatedTowerLocation = if (obj.has("estimatedLat") && obj.has("estimatedLon"))
                                Pair(obj.getDouble("estimatedLat"), obj.getDouble("estimatedLon")) else null,
                            suspicionReason = obj.optString("suspicionReason").takeIf { it.isNotEmpty() },
                            suspicionConfidence = obj.optDouble("suspicionConfidence", 0.0).takeIf { it > 0 },
                            suspicionLevel = obj.optString("suspicionLevel", "none")
                        )
                        towerDao.insertTowerInfo(towerInfo)
                        towersImported++
                    }
                    mergeMode == MergeMode.OVERWRITE -> {
                        // Update existing
                        val updated = existing.copy(
                            firstSeen = minOf(existing.firstSeen, obj.getLong("firstSeen")),
                            lastSeen = maxOf(existing.lastSeen, obj.getLong("lastSeen")),
                            lastConnectedTime = maxOf(existing.lastConnectedTime, obj.optLong("lastConnectedTime", 0))
                        )
                        towerDao.updateTowerInfo(updated)
                        towersImported++
                    }
                    else -> skipped++
                }
            }
            
            // Import fake towers
            val fakeTowersArray = importJson.getJSONArray("fakeTowers")
            for (i in 0 until fakeTowersArray.length()) {
                val obj = fakeTowersArray.getJSONObject(i)
                val existing = fakeTowerDao.getFakeTower(
                    obj.getInt("cid"), obj.getInt("lac"),
                    obj.getInt("mcc"), obj.getInt("mnc")
                )
                
                if (existing == null || mergeMode == MergeMode.OVERWRITE) {
                    val fakeTower = FakeTower(
                        cid = obj.getInt("cid"),
                        lac = obj.getInt("lac"),
                        mcc = obj.getInt("mcc"),
                        mnc = obj.getInt("mnc"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        detectionTime = obj.getLong("detectionTime"),
                        accuracy = obj.getDouble("accuracy"),
                        reason = obj.getString("reason"),
                        signalStrength = obj.getInt("signalStrength"),
                        networkType = obj.getString("networkType"),
                        observationCount = obj.optInt("observationCount", 1),
                        precisionRadius = obj.optDouble("precisionRadius", 5000.0)
                    )
                    fakeTowerDao.insertFakeTower(fakeTower)
                    suspiciousImported++
                }
            }
            
            Log.i(TAG, "Import completed: $towersImported towers, $suspiciousImported suspicious, $skipped skipped")
            
            ImportResult.Success(
                towersImported = towersImported,
                suspiciousImported = suspiciousImported,
                skipped = skipped
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult.Failure(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Cleanup old data beyond retention period
     * @param retentionYears Number of years to keep data (default 3)
     */
    suspend fun cleanupOldData(retentionYears: Int = DEFAULT_RETENTION_YEARS) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (retentionYears * MILLIS_PER_YEAR)
        Log.i(TAG, "Cleaning up data older than $retentionYears years (cutoff: $cutoffTime)")
        
        // Note: This requires adding cleanup queries to DAOs
        // For now, log the intent - actual cleanup will be added in a future migration
        Log.i(TAG, "Data retention cleanup scheduled for implementation")
    }
    
    sealed class ExportResult {
        data class Success(
            val towersExported: Int,
            val suspiciousTowersExported: Int,
            val fileSizeBytes: Long
        ) : ExportResult()
        
        data class Failure(val error: String) : ExportResult()
    }
    
    sealed class ImportResult {
        data class Success(
            val towersImported: Int,
            val suspiciousImported: Int,
            val skipped: Int
        ) : ImportResult()
        
        data class Failure(val error: String) : ImportResult()
    }
    
    enum class MergeMode {
        SKIP_EXISTING,  // Skip if tower already exists
        OVERWRITE,      // Overwrite existing with imported data
        MERGE           // Merge timestamps (keep oldest firstSeen, newest lastSeen)
    }
}
