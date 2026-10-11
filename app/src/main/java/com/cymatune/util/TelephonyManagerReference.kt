package com.cymatune.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission

/**
 * Comprehensive reference of all TelephonyManager parameters, attributes, and settings
 * available for monitoring and configuring cellular network connections in Android.
 * 
 * This reference covers Android SDK 26+ (Android 8.0 Oreo) through current APIs.
 */
object TelephonyManagerReference {

    // ==================== PERMISSION REQUIREMENTS ====================

    /**
     * Required permissions for accessing telephony information
     */
    object Permissions {
        const val READ_PHONE_STATE = Manifest.permission.READ_PHONE_STATE
        const val ACCESS_FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION
        const val ACCESS_COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
        const val READ_PRECISE_PHONE_STATE = Manifest.permission.READ_PRECISE_PHONE_STATE
    }

    // ==================== TELEPHONY MANAGER CORE METHODS ====================

    /**
     * Core TelephonyManager methods for network information access
     */
    object CoreMethods {
        // Network operator information
        const val NETWORK_OPERATOR_NAME = "getNetworkOperatorName()"
        const val NETWORK_OPERATOR = "getNetworkOperator()" // Returns MCC+MNC as string
        const val SIM_OPERATOR_NAME = "getSimOperatorName()"
        const val SIM_OPERATOR = "getSimOperator()" // Returns MCC+MNC as string

        // Network type information
        const val NETWORK_TYPE = "getNetworkType()"
        const val DATA_NETWORK_TYPE = "getDataNetworkType()"
        const val VOICE_NETWORK_TYPE = "getVoiceNetworkType()"

        // Signal strength information (deprecated but still used)
        const val SIGNAL_STRENGTH = "getSignalStrength()" // Deprecated in API 31

        // Cell information (requires ACCESS_FINE_LOCATION)
        const val ALL_CELL_INFO = "getAllCellInfo()"
        const val CELL_LOCATION = "getCellLocation()" // Deprecated in API 29

        // Service state information
        const val SERVICE_STATE = "getServiceState()"

        // Device information
        const val DEVICE_ID = "getDeviceId()" // Requires READ_PHONE_STATE, deprecated in API 29
        const val IMEI = "getImei()" // Requires READ_PRECISE_PHONE_STATE
        const val MEID = "getMeid()" // Requires READ_PRECISE_PHONE_STATE
    }

    // ==================== CELL INFO CLASSES AND PROPERTIES ====================

    /**
     * CellInfo base class properties (common to all network types)
     */
    object CellInfoBase {
        const val TIME_STAMP = "getTimeStamp()" // Measurement timestamp in nanoseconds
        const val IS_REGISTERED = "isRegistered()" // Whether cell is currently serving
        const val CELL_CONNECTION_STATUS = "getCellConnectionStatus()" // API 29+
        const val CELL_SIGNAL_STRENGTH = "getCellSignalStrength()"
    }

    /**
     * CellInfoLte specific properties (4G LTE networks)
     */
    object CellInfoLteProperties {
        // CellIdentityLte properties
        const val CI = "getCi()" // Cell Identity (28-bit)
        const val PCI = "getPci()" // Physical Cell ID (0-503)
        const val TAC = "getTac()" // Tracking Area Code (16-bit)
        const val EARFCN = "getEarfcn()" // E-UTRA Absolute Radio Frequency Channel Number
        const val BANDWIDTH = "getBandwidth()" // Channel bandwidth in Hz
        const val MCC = "getMcc()" // Mobile Country Code
        const val MNC = "getMnc()" // Mobile Network Code
        const val MCC_STRING = "getMccString()" // MCC as string
        const val MNC_STRING = "getMncString()" // MNC as string
        const val OPERATOR_ALPHA_LONG = "getOperatorAlphaLong()"
        const val OPERATOR_ALPHA_SHORT = "getOperatorAlphaShort()"

        // CellSignalStrengthLte properties
        const val RSRP = "getRsrp()" // Reference Signal Received Power (dBm)
        const val RSRQ = "getRsrq()" // Reference Signal Received Quality (dB)
        const val RSSNR = "getRssnr()" // Reference Signal Signal-to-Noise Ratio (dB) - API 29+
        const val CQI = "getCqi()" // Channel Quality Indicator - API 29+
        const val TIMING_ADVANCE = "getTimingAdvance()" // Timing Advance in nanoseconds - API 29+
        const val DBM = "getDbm()" // Signal strength in dBm
        const val LEVEL = "getLevel()" // Signal level (0-4)
        const val ASU_LEVEL = "getAsuLevel()" // Arbitrary Strength Unit
    }

    /**
     * CellInfoNr specific properties (5G NR networks) - API 29+
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    object CellInfoNrProperties {
        // CellIdentityNr properties
        const val NCI = "getNci()" // NR Cell Identity (36-bit)
        const val PCI = "getPci()" // Physical Cell ID (0-1007)
        const val TAC = "getTac()" // Tracking Area Code (24-bit)
        const val NRARFCN = "getNrarfcn()" // NR Absolute Radio Frequency Channel Number
        const val SSB_ARFCN = "getSsbArfcn()" // SSB ARFCN - API 31+
        const val MCC_STRING = "getMccString()"
        const val MNC_STRING = "getMncString()"
        const val OPERATOR_ALPHA_LONG = "getOperatorAlphaLong()"
        const val OPERATOR_ALPHA_SHORT = "getOperatorAlphaShort()"
        const val BANDS = "getBands()" // Frequency bands array - API 31+

        // CellSignalStrengthNr properties
        const val SS_RSRP = "getSsRsrp()" // SS Reference Signal Received Power (dBm)
        const val SS_RSRQ = "getSsRsrq()" // SS Reference Signal Received Quality (dB)
        const val SS_SINR = "getSsSinr()" // SS Signal to Interference plus Noise Ratio (dB)
        const val CSI_RSRP = "getCsiRsrp()" // CSI Reference Signal Received Power (dBm) - API 31+
        const val CSI_RSRQ = "getCsiRsrq()" // CSI Reference Signal Received Quality (dB) - API 31+
        const val CSI_SINR = "getCsiSinr()" // CSI Signal to Interference plus Noise Ratio (dB) - API 31+
        const val DBM = "getDbm()" // Signal strength in dBm
        const val LEVEL = "getLevel()" // Signal level (0-4)
        const val NR_LEVEL = "getNrLevel()" // NR-specific signal level - API 31+
        const val SS_RSRP_THRESHOLDS = "getSsRsrpThresholds()" // API 31+
        const val CSI_RSRP_THRESHOLDS = "getCsiRsrpThresholds()" // API 31+
    }

    /**
     * CellInfoWcdma specific properties (3G WCDMA networks)
     */
    object CellInfoWcdmaProperties {
        // CellIdentityWcdma properties
        const val CID = "getCid()" // Cell Identity
        const val LAC = "getLac()" // Location Area Code
        const val PSC = "getPsc()" // Primary Scrambling Code
        const val UARFCN = "getUarfcn()" // UTRA Absolute Radio Frequency Channel Number
        const val MCC = "getMcc()"
        const val MNC = "getMnc()"
        const val MCC_STRING = "getMccString()"
        const val MNC_STRING = "getMncString()"

        // CellSignalStrengthWcdma properties
        const val DBM = "getDbm()"
        const val LEVEL = "getLevel()"
        const val BER = "getBer()" // Bit Error Rate - API 29+
        const val RSCP = "getRscp()" // Received Signal Code Power - API 29+
        const val ECNO = "getEcno()" // Energy per chip-to-noise ratio - API 29+
    }

    /**
     * CellInfoGsm specific properties (2G GSM networks)
     */
    object CellInfoGsmProperties {
        // CellIdentityGsm properties
        const val CID = "getCid()" // Cell Identity
        const val LAC = "getLac()" // Location Area Code
        const val ARFCN = "getArfcn()" // Absolute Radio Frequency Channel Number
        const val BSIC = "getBsic()" // Base Station Identity Code
        const val MCC = "getMcc()"
        const val MNC = "getMnc()"
        const val MCC_STRING = "getMccString()"
        const val MNC_STRING = "getMncString()"

        // CellSignalStrengthGsm properties
        const val DBM = "getDbm()"
        const val LEVEL = "getLevel()"
        const val BER = "getBer()" // Bit Error Rate - API 29+
        const val TIMING_ADVANCE = "getTimingAdvance()" // API 29+
    }

    /**
     * CellInfoCdma specific properties (CDMA networks)
     */
    object CellInfoCdmaProperties {
        // CellIdentityCdma properties
        const val BASESTATION_ID = "getBasestationId()"
        const val LATITUDE = "getLatitude()"
        const val LONGITUDE = "getLongitude()"
        const val NETWORK_ID = "getNetworkId()"
        const val SYSTEM_ID = "getSystemId()"

        // CellSignalStrengthCdma properties
        const val DBM = "getDbm()"
        const val LEVEL = "getLevel()"
        const val CDMA_LEVEL = "getCdmaLevel()"
        const val EVDO_LEVEL = "getEvdoLevel()"
        const val CDMA_ECIO = "getCdmaEcio()" // CDMA Energy per chip-to-interference ratio
        const val EVDO_ECIO = "getEvdoEcio()" // EV-DO Energy per chip-to-interference ratio
        const val EVDO_SNR = "getEvdoSnr()" // EV-DO Signal-to-Noise Ratio
    }

    // ==================== NETWORK TYPES AND CONSTANTS ====================

    /**
     * Network type constants from TelephonyManager
     */
    object NetworkTypes {
        const val UNKNOWN = "NETWORK_TYPE_UNKNOWN"
        const val GSM = "NETWORK_TYPE_GSM"
        const val CDMA = "NETWORK_TYPE_CDMA"
        const val LTE = "NETWORK_TYPE_LTE"
        const val NR = "NETWORK_TYPE_NR"
        const val WCDMA = "NETWORK_TYPE_WCDMA"
        const val TDSCDMA = "NETWORK_TYPE_TDSCDMA"
        const val HSPA = "NETWORK_TYPE_HSPA"
        const val HSPAP = "NETWORK_TYPE_HSPAP"
        const val EDGE = "NETWORK_TYPE_EDGE"
        const val GPRS = "NETWORK_TYPE_GPRS"
        const val EHRPD = "NETWORK_TYPE_EHRPD"
        const val EVDO_0 = "NETWORK_TYPE_EVDO_0"
        const val EVDO_A = "NETWORK_TYPE_EVDO_A"
        const val EVDO_B = "NETWORK_TYPE_EVDO_B"
        const val IWLAN = "NETWORK_TYPE_IWLAN"
        const val LTE_CA = "NETWORK_TYPE_LTE_CA" // LTE Carrier Aggregation
    }

    // ==================== SERVICE STATE INFORMATION ====================

    /**
     * ServiceState properties for network registration status
     */
    object ServiceStateProperties {
        const val VOICE_REG_STATE = "getVoiceRegState()" // Voice registration state
        const val DATA_REG_STATE = "getDataRegState()" // Data registration state
        const val VOICE_OPERATOR_ALPHA_LONG = "getVoiceOperatorAlphaLong()"
        const val VOICE_OPERATOR_ALPHA_SHORT = "getVoiceOperatorAlphaShort()"
        const val VOICE_OPERATOR_NUMERIC = "getVoiceOperatorNumeric()"
        const val DATA_OPERATOR_ALPHA_LONG = "getDataOperatorAlphaLong()"
        const val DATA_OPERATOR_ALPHA_SHORT = "getDataOperatorAlphaShort()"
        const val DATA_OPERATOR_NUMERIC = "getDataOperatorNumeric()"
        const val IS_MANUAL_NETWORK_SELECTION = "isManualNetworkSelection()"
        const val CHANNEL_NUMBER = "getChannelNumber()" // API 30+
        const val CELL_BANDWIDTHS = "getCellBandwidths()" // API 30+
    }

    // ==================== ADVANCED NETWORK METRICS ====================

    /**
     * Advanced network metrics and performance indicators
     */
    object AdvancedMetrics {
        // Carrier aggregation information - API 28+
        const val CARRIER_AGGREGATION_STATUS = "getCarrierAggregationStatus()"
        const val ACTIVE_MODEM_INFO = "getActiveModemInfo()" // API 31+

        // Network slicing information - API 31+
        const val NETWORK_SLICING_CONFIGURATION = "getNetworkSlicingConfiguration()"

        // MIMO (Multiple Input Multiple Output) information
        const val MIMO_INFO = "getMimoInfo()" // API 31+

        // Beamforming information for 5G NR - API 31+
        const val BEAMFORMING_INFO = "getBeamformingInfo()"

        // Quality of Service (QoS) metrics
        const val QOS_PARAMETERS = "getQosParameters()" // API 31+
    }

    // ==================== DEVICE CAPABILITIES ====================

    /**
     * Device capability information
     */
    object DeviceCapabilities {
        const val IS_NR_DUAL_CONNECTIVITY_SUPPORTED = "isNrDualConnectivitySupported()" // API 29+
        const val IS_DATA_ENABLED = "isDataEnabled()"
        const val IS_VOICE_CAPABLE = "isVoiceCapable()"
        const val IS_SMS_CAPABLE = "isSmsCapable()"
        const val IS_EMERGENCY_NUMBER = "isEmergencyNumber(String)" // Check if number is emergency
        const val SIM_COUNT = "getSimCount()"
        const val ACTIVE_MODEM_COUNT = "getActiveModemCount()"
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Utility methods for working with telephony data
     */
    object UtilityMethods {
        /**
         * Extract MCC from network operator string
         */
        fun extractMcc(networkOperator: String?): String {
            return networkOperator?.substring(0, 3) ?: "--"
        }

        /**
         * Extract MNC from network operator string
         */
        fun extractMnc(networkOperator: String?): String {
            return networkOperator?.substring(3) ?: "--"
        }

        /**
         * Check if required permissions are granted
         */
        @SuppressLint("InlinedApi")
        fun hasRequiredPermissions(context: Context): Boolean {
            return context.checkSelfPermission(Permissions.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                   context.checkSelfPermission(Permissions.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        /**
         * Get human-readable network type name
         */
        fun getNetworkTypeName(networkType: Int): String {
            return when (networkType) {
                TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
                TelephonyManager.NETWORK_TYPE_CDMA -> "2G CDMA"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "2G 1xRTT"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "3G EVDO_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G EVDO_A"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G EVDO_B"
                TelephonyManager.NETWORK_TYPE_EHRPD -> "3G EHRPD"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
                TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G TD-SCDMA"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                // NETWORK_TYPE_LTE_CA is not available in standard Android SDK
                // Use a fallback approach for LTE Carrier Aggregation
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                else -> {
                    if (networkType >= 19) { // LTE_CA is typically value 19
                        "4G LTE-CA"
                    } else {
                        "Unknown"
                    }
                }
            }
        }
    }

    // ==================== ANDROID VERSION COMPATIBILITY ====================

    /**
     * Android version compatibility information
     */
    object VersionCompatibility {
        const val CELL_INFO_NR_AVAILABLE = Build.VERSION_CODES.Q // API 29
        const val PRECISE_PHONE_STATE_AVAILABLE = Build.VERSION_CODES.R // API 30
        const val NETWORK_SLICING_AVAILABLE = Build.VERSION_CODES.S // API 31
        const val ADVANCED_NR_METRICS_AVAILABLE = Build.VERSION_CODES.S // API 31
        const val BEAMFORMING_INFO_AVAILABLE = Build.VERSION_CODES.S // API 31
    }

    // ==================== SIGNAL QUALITY THRESHOLDS ====================

    /**
     * Signal quality thresholds for different network types
     */
    object SignalQualityThresholds {
        // LTE signal quality thresholds (dBm)
        const val LTE_EXCELLENT = -85
        const val LTE_GOOD = -95
        const val LTE_FAIR = -105
        const val LTE_POOR = -115

        // 5G NR signal quality thresholds (dBm)
        const val NR_EXCELLENT = -80
        const val NR_GOOD = -90
        const val NR_FAIR = -100
        const val NR_POOR = -110

        // RSRQ thresholds (dB)
        const val RSRQ_EXCELLENT = -8
        const val RSRQ_GOOD = -12
        const val RSRQ_FAIR = -16
        const val RSRQ_POOR = -20

        // SINR thresholds (dB)
        const val SINR_EXCELLENT = 20
        const val SINR_GOOD = 13
        const val SINR_FAIR = 6
        const val SINR_POOR = 0
    }
}