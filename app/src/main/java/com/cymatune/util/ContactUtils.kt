package com.cymatune.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class for resolving contact names from phone numbers.
 * Performs direct ContactsContract lookups using READ_CONTACTS permission.
 *
 * This fixes the "Missing Contact Names" issue (B05) where system-cached names
 * weren't available, resulting in raw numbers being displayed.
 */
object ContactUtils {

    private const val TAG = "ContactUtils"

    /**
     * Normalize a phone number for consistent comparison.
     * Removes spaces, dashes, and country code variations.
     */
    fun normalizePhoneNumber(number: String?): String {
        if (number.isNullOrBlank()) return ""
        return number.replace(Regex("[^\\d]"), "")
    }

    /**
     * Resolve contact name from a phone number.
     * Returns the contact's display name if found, null otherwise.
     *
     * @param context Application context
     * @param phoneNumber The phone number to look up
     * @return Contact display name or null if not found/no permission
     */
    suspend fun resolveContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                // Check if we have READ_CONTACTS permission
                if (!hasReadContactsPermission(context)) {
                    Log.d(TAG, "No READ_CONTACTS permission, skipping contact lookup")
                    return@withContext null
                }

                // Try exact match first
                val normalizedInput = normalizePhoneNumber(phoneNumber)

                // Query using PhoneLookup URI (most efficient for phone number lookups)
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )

                val projection = arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.NUMBER
                )

                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            Log.d(TAG, "Found contact: $name for number: $phoneNumber")
                            return@withContext name
                        }
                    }
                }

                // Fallback: Search all contacts with normalized number comparison
                val allContacts = queryAllContacts(context)
                for ((contactNumber, contactName) in allContacts) {
                    val normalizedContact = normalizePhoneNumber(contactNumber)
                    if (normalizedContact == normalizedInput ||
                        normalizedInput.contains(normalizedContact) ||
                        normalizedContact.contains(normalizedInput)) {
                        Log.d(TAG, "Found contact via fallback: $contactName")
                        return@withContext contactName
                    }
                }

                Log.d(TAG, "No contact found for number: $phoneNumber")
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException during contact lookup", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving contact name", e)
                null
            }
        }
    }

    /**
     * Batch resolve multiple phone numbers to contact names.
     * More efficient for resolving multiple contacts at once.
     *
     * @param context Application context
     * @param phoneNumbers List of phone numbers to resolve
     * @return Map of phone number to contact name (null if not found)
     */
    suspend fun resolveContactNamesBatch(
        context: Context,
        phoneNumbers: List<String>
    ): Map<String, String?> {
        if (phoneNumbers.isEmpty()) return emptyMap()

        return withContext(Dispatchers.IO) {
            try {
                if (!hasReadContactsPermission(context)) {
                    return@withContext phoneNumbers.associateWith { null }
                }

                // Build a map of normalized numbers to original numbers
                val normalizedMap = phoneNumbers.associateBy { normalizePhoneNumber(it) }
                val results = mutableMapOf<String, String?>()

                // Query all contacts once
                val allContacts = queryAllContacts(context)

                // Match each input number
                for (phoneNumber in phoneNumbers) {
                    val normalizedInput = normalizePhoneNumber(phoneNumber)

                    // Try PhoneLookup first
                    val uri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(phoneNumber)
                    )

                    var found = false
                    context.contentResolver.query(
                        uri,
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val name = cursor.getString(0)
                            results[phoneNumber] = name
                            found = true
                        }
                    }

                    if (!found) {
                        // Fallback to normalized comparison
                        for ((contactNumber, contactName) in allContacts) {
                            val normalizedContact = normalizePhoneNumber(contactNumber)
                            if (normalizedContact == normalizedInput ||
                                normalizedInput.contains(normalizedContact) ||
                                normalizedContact.contains(normalizedInput)) {
                                results[phoneNumber] = contactName
                                found = true
                                break
                            }
                        }
                    }

                    if (!found) {
                        results[phoneNumber] = null
                    }
                }

                results
            } catch (e: Exception) {
                Log.e(TAG, "Error in batch contact resolution", e)
                phoneNumbers.associateWith { null }
            }
        }
    }

    /**
     * Query all contacts and return a map of phone numbers to names.
     * Used for fallback matching.
     */
    private fun queryAllContacts(context: Context): Map<String, String> {
        val contacts = mutableMapOf<String, String>()

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    if (numberIndex != -1 && nameIndex != -1) {
                        val number = cursor.getString(numberIndex)
                        val name = cursor.getString(nameIndex)
                        if (!number.isNullOrBlank() && !name.isNullOrBlank()) {
                            contacts[number] = name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying all contacts", e)
        }

        return contacts
    }

    /**
     * Check if we have READ_CONTACTS permission.
     */
    private fun hasReadContactsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Format a phone number with contact name for display.
     * Returns "Contact Name" if found, otherwise returns the formatted number.
     */
    suspend fun formatCallerDisplay(
        context: Context,
        phoneNumber: String?,
        fallbackName: String? = null
    ): String {
        if (phoneNumber.isNullOrBlank()) return fallbackName ?: "Unknown"

        val contactName = resolveContactName(context, phoneNumber)
        return contactName ?: fallbackName ?: formatPhoneNumber(phoneNumber)
    }

    /**
     * Basic phone number formatting (e.g., +91 12345 67890).
     */
    fun formatPhoneNumber(number: String?): String {
        if (number.isNullOrBlank()) return ""

        val normalized = normalizePhoneNumber(number)

        return when {
            // Indian format: +91 XXXXX XXXXX
            normalized.length == 12 && normalized.startsWith("91") -> {
                "+91 ${normalized.substring(2, 7)} ${normalized.substring(7)}"
            }
            // Indian format without country code: XXXXX XXXXX
            normalized.length == 10 -> {
                "${normalized.substring(0, 5)} ${normalized.substring(5)}"
            }
            // International format
            normalized.length > 10 -> {
                "+${normalized.substring(0, normalized.length - 10)} ${normalized.takeLast(10)}"
            }
            else -> number
        }
    }
}
