package com.cymatune.util

import android.content.Context
import com.cymatune.security.EncryptedPrefsHelper

object UnitConverter {
    private const val UNIT_KM = "km"
    private const val UNIT_MI = "mi"
    private const val KM_PER_MILE = 1.60934

    fun convertMetersToUnit(meters: Double, context: Context): Double {
        val encryptedPrefs = EncryptedPrefsHelper(context)
        val unit = encryptedPrefs.getString("distance_units", UNIT_KM)
        return when (unit) {
            UNIT_MI -> meters / (1000 * KM_PER_MILE)
            else -> meters / 1000
        }
    }

    fun getUnitAbbreviation(context: Context): String {
        val encryptedPrefs = EncryptedPrefsHelper(context)
        return encryptedPrefs.getString("distance_units", UNIT_KM)
    }

    fun convertMetersToKilometers(meters: Double): Double = meters / 1000
    fun convertKilometersToMiles(km: Double): Double = km / KM_PER_MILE
    fun convertMetersToMiles(meters: Double): Double = meters / (1000 * KM_PER_MILE)
}
