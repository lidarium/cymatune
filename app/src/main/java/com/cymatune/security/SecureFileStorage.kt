package com.cymatune.security

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureFileStorage(private val context: Context) {
    companion object {
        private const val TAG = "SecureFileStorage"
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        private const val KEY_ALIAS = "secure_file_key"
    }

    private val keyStoreHelper = KeystoreHelper(context)

    private fun getOrCreateKey(): SecretKey {
        val keyString = keyStoreHelper.getData(KEY_ALIAS)
        return if (keyString != null) {
            val decodedKey = android.util.Base64.decode(keyString, android.util.Base64.DEFAULT)
            SecretKeySpec(decodedKey, ALGORITHM)
        } else {
            val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
            keyGenerator.init(KEY_SIZE)
            val key = keyGenerator.generateKey()
            val encodedKey = android.util.Base64.encodeToString(key.encoded, android.util.Base64.DEFAULT)
            keyStoreHelper.saveData(KEY_ALIAS, encodedKey)
            key
        }
    }

    fun encryptAndSaveFile(filename: String, data: String): Boolean {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            
            val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            
            // Combine IV and encrypted data
            val combinedData = iv + encryptedData
            
            val file = File(context.filesDir, filename)
            FileOutputStream(file).use { fos ->
                fos.write(combinedData)
            }
            
            Log.d(TAG, "File encrypted and saved successfully: $filename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting and saving file: $filename", e)
            false
        }
    }

    fun decryptFile(filename: String): String? {
        return try {
            val file = File(context.filesDir, filename)
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: $filename")
                return null
            }
            
            val combinedData = FileInputStream(file).use { fis ->
                fis.readBytes()
            }
            
            if (combinedData.size < GCM_IV_LENGTH) {
                Log.e(TAG, "Invalid encrypted file format")
                return null
            }
            
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            // Extract IV and encrypted data
            val iv = combinedData.copyOfRange(0, GCM_IV_LENGTH)
            val encryptedData = combinedData.copyOfRange(GCM_IV_LENGTH, combinedData.size)
            
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            
            val decryptedData = cipher.doFinal(encryptedData)
            String(decryptedData, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting file: $filename", e)
            null
        }
    }

    fun deleteFile(filename: String): Boolean {
        return try {
            val file = File(context.filesDir, filename)
            val deleted = file.delete()
            Log.d(TAG, "File deleted: $filename, success: $deleted")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: $filename", e)
            false
        }
    }

    fun fileExists(filename: String): Boolean {
        val file = File(context.filesDir, filename)
        return file.exists()
    }
}
