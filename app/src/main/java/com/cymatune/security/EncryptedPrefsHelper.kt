package com.cymatune.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedPrefsHelper(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cymatune_encrypted_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    
    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()

    fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    operator fun contains(key: String): Boolean = prefs.contains(key)
    
    fun migrateFromLegacy(name: String, context: Context) {
        // Migrate data from legacy SharedPreferences to encrypted storage
        val legacyPrefs = context.getSharedPreferences("cymatune_prefs", Context.MODE_PRIVATE)
        val allEntries = legacyPrefs.all
        
        for ((key, value) in allEntries) {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is Long -> putLong(key, value)
            }
        }
        
        // Clear legacy preferences after migration
        legacyPrefs.edit().clear().apply()
    }
}