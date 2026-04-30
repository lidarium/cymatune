package com.cymatune.util

import android.telephony.CellIdentityNr
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.os.Build

/**
 * 5G NR (New Radio) support utilities for Cymatune LT
 * Inspired by concepts from CellInfo repository (CC0 1.0 licensed)
 *
 * Provides comprehensive 5G NR band mapping and signal processing
 */
object NR5GSupport {

    /**
     * Extract tower information from 5G NR cell info
     */
    fun extractNrTowerInfo(cellInfo: CellInfoNr, subscriptionId: Int): TowerConnectionInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        
        val cellIdentity = cellInfo.cellIdentity as? CellIdentityNr ?: return null
        val cellSignalStrength = cellInfo.cellSignalStrength as? CellSignalStrengthNr

        val cellId = cellIdentity.nci.toString()
        val tac = cellIdentity.tac
        val pci = cellIdentity.pci

        // Extract MCC and MNC with proper fallback handling
        val mcc = cellIdentity.mccString?.toIntOrNull() ?: 0
        val mnc = cellIdentity.mncString?.toIntOrNull() ?: 0

        // Skip invalid MCC/MNC values
        if (mcc == 65535 || mcc == Int.MAX_VALUE || mcc == 0) return null
        if (mnc == 65535 || mnc == Int.MAX_VALUE || mnc == 0) return null

        val signal = cellSignalStrength?.dbm ?: Int.MIN_VALUE
        
        // Extract timing advance for NR if available (not available in CellSignalStrengthNr)
        val timingAdvance = Int.MIN_VALUE
        
        // Extract 5G NR specific signal parameters
        val ssRsrp = cellSignalStrength?.ssRsrp
        val ssRsrq = cellSignalStrength?.ssRsrq
        val ssSinr = cellSignalStrength?.ssSinr
        
        // Extract ARFCN and band information
        val arfcn = cellIdentity.nrarfcn
        val band = getNrBandFromArfcn(arfcn)

        return TowerConnectionInfo(
            subscriptionId = subscriptionId,
            slotIndex = 0,
            mcc = mcc,
            mnc = mnc,
            lac = tac,
            cid = cellId.toIntOrNull() ?: 0,
            signalStrength = signal,
            timingAdvance = timingAdvance,
            pci = pci,
            arfcn = arfcn,
            band = band,
            ssRsrp = ssRsrp,
            ssRsrq = ssRsrq,
            ssSinr = ssSinr,
            isRegistered = cellInfo.isRegistered,
            cellInfo = cellInfo,
            networkType = "5G NR",
            additionalInfo = mapOf(
                "band_name" to getNrBandName(band)
            )
        )
    }

    /**
     * Map NR ARFCN to band number
     * Comprehensive band mapping inspired by CellInfo implementation
     */
    fun getNrBandFromArfcn(arfcn: Int): Int {
        return when (arfcn) {
            in 422000..434000 -> 1
            in 386000..398000 -> 2
            in 361000..376000 -> 3
            in 173800..178800 -> 5
            in 524000..538000 -> 7
            in 185000..192000 -> 8
            in 145800..149200 -> 12
            in 149200..151200 -> 13
            in 151600..153600 -> 14
            in 172000..175000 -> 18
            in 158200..164200 -> 20
            in 305000..311800 -> 24
            in 386000..399000 -> 25
            in 171800..178800 -> 26
            in 151600..160600 -> 28
            in 143400..145600 -> 29
            in 470000..472000 -> 30
            in 92500..93500 -> 31
            in 402000..405000 -> 34
            in 514000..524000 -> 38
            in 376000..384000 -> 39
            in 460000..480000 -> 40
            in 499200..537999 -> 41
            in 743334..795000 -> 46
            in 790334..795000 -> 47
            in 636667..646666 -> 48
            in 286400..303400 -> 50
            in 285400..286400 -> 51
            in 496700..499000 -> 53
            in 334000..335000 -> 54
            in 422000..440000 -> 65
            in 422000..440000 -> 66
            in 147600..151600 -> 67
            in 399000..404000 -> 70
            in 123400..130400 -> 71
            in 92200..93200 -> 72
            in 295000..303600 -> 74
            in 286400..303400 -> 75
            in 285400..286400 -> 76
            in 620000..680000 -> 77
            in 620000..653333 -> 78
            in 693334..733333 -> 79
            in 342000..357000 -> 80
            in 176000..183000 -> 81
            in 166400..172400 -> 82
            in 140600..149600 -> 83
            in 384000..396000 -> 84
            in 145600..149200 -> 85
            in 342000..356000 -> 86
            in 164800..169800 -> 89
            in 499200..538000 -> 90
            in 285400..286400 -> 91
            in 286400..303400 -> 92
            in 285400..286400 -> 93
            in 286400..303400 -> 94
            in 402000..405000 -> 95
            in 795000..875000 -> 96
            in 460000..480000 -> 97
            in 376000..384000 -> 98
            in 325300..332100 -> 99
            in 183880..185000 -> 100
            in 380000..382000 -> 101
            in 795000..828333 -> 102
            in 828334..875000 -> 104
            in 122400..130400 -> 105
            in 187000..188000 -> 106
            in 286400..303400 -> 109
            in 434000..440000 -> 256
            in 305000..311800 -> 255
            in 496700..500000 -> 254
            in 434000..440000 -> 256
            in 2054166..2104165 -> 257
            in 2016667..2070832 -> 258
            in 2270833..2337499 -> 259
            in 2229166..2279165 -> 260
            in 2070833..2084999 -> 261
            in 2399166..2415832 -> 262
            in 2564083..2794243 -> 262
            else -> -1 // Unknown or unsupported band
        }
    }

    /**
     * Get band name from band number
     */
    fun getNrBandName(bandNumber: Int): String {
        return when (bandNumber) {
            -1 -> "Unknown"
            else -> "n$bandNumber"
        }
    }

    /**
     * Check if device supports 5G NR
     */
    fun isNrSupported(): Boolean {
        return try {
            Class.forName("android.telephony.CellInfoNr")
            Class.forName("android.telephony.CellIdentityNr")
            Class.forName("android.telephony.CellSignalStrengthNr")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}