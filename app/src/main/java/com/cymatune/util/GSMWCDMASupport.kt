package com.cymatune.util

object GSMWCDMASupport {

    fun getGsmBandFromArfcn(arfcn: Int): Int {
        return when (arfcn) {
            in 1..124 -> 900
            in 128..251 -> 900
            in 259..293 -> 900
            in 306..340 -> 900
            in 512..885 -> 1800
            in 975..1023 -> 1900
            in 0..0 -> 450
            else -> -1
        }
    }

    fun getWcdmaBandFromUarfcn(uarfcn: Int): Int {
        return when (uarfcn) {
            in 9612..9888 -> 1
            in 9262..9538 -> 2
            in 937..1287 -> 5
            in 1312..1513 -> 6
            in 2712..2863 -> 8
            in 2937..3088 -> 9
            in 3112..3388 -> 10
            in 3487..3563 -> 11
            in 3612..3612 -> 12
            in 3662..3662 -> 13
            in 3712..3712 -> 14
            in 3842..3842 -> 19
            in 3862..3862 -> 20
            in 3962..3962 -> 21
            in 4012..4012 -> 22
            in 4062..4062 -> 25
            in 4112..4112 -> 26
            in 4162..4162 -> 27
            in 4212..4212 -> 28
            in 4262..4262 -> 29
            in 4312..4312 -> 30
            in 4362..4362 -> 31
            in 4412..4412 -> 32
            in 4462..4462 -> 33
            in 4512..4512 -> 34
            in 4562..4562 -> 35
            in 4612..4612 -> 36
            in 4662..4662 -> 37
            in 4712..4712 -> 38
            in 4762..4762 -> 39
            in 4812..4812 -> 40
            else -> -1
        }
    }

    fun getGsmBandName(bandNumber: Int): String {
        return when (bandNumber) {
            900 -> "GSM 900"
            1800 -> "GSM 1800"
            1900 -> "GSM 1900"
            450 -> "GSM 450"
            else -> "Unknown GSM Band"
        }
    }

    fun getWcdmaBandName(bandNumber: Int): String {
        return when (bandNumber) {
            1 -> "Band I (2100 MHz)"
            2 -> "Band II (1900 MHz)"
            5 -> "Band V (850 MHz)"
            8 -> "Band VIII (900 MHz)"
            9 -> "Band IX (1700 MHz)"
            10 -> "Band X (1700 MHz)"
            11 -> "Band XI (1500 MHz)"
            12 -> "Band XII (700 MHz)"
            13 -> "Band XIII (700 MHz)"
            14 -> "Band XIV (700 MHz)"
            19 -> "Band XIX (800 MHz)"
            20 -> "Band XX (800 MHz)"
            21 -> "Band XXI (1500 MHz)"
            22 -> "Band XXII (3500 MHz)"
            25 -> "Band XXV (1900 MHz)"
            26 -> "Band XXVI (850 MHz)"
            27 -> "Band XXVII (800 MHz)"
            28 -> "Band XXVIII (700 MHz)"
            29 -> "Band XXIX (700 MHz)"
            30 -> "Band XXX (2300 MHz)"
            31 -> "Band XXXI (450 MHz)"
            32 -> "Band XXXII (1500 MHz)"
            33 -> "Band XXXIII (1900 MHz)"
            34 -> "Band XXXIV (2000 MHz)"
            35 -> "Band XXXV (1900 MHz)"
            36 -> "Band XXXVI (1900 MHz)"
            37 -> "Band XXXVII (1900 MHz)"
            38 -> "Band XXXVIII (2600 MHz)"
            39 -> "Band XXXIX (1900 MHz)"
            40 -> "Band XL (2300 MHz)"
            else -> "Unknown WCDMA Band"
        }
    }
}