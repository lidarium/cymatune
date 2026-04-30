package com.cymatune.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cymatune.R
import com.cymatune.db.FakeTowerDao
import com.cymatune.util.TowerConnectionInfo
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.*
import android.net.Uri
import android.content.Intent

class TowerDetailsBottomSheet : BottomSheetDialogFragment() {

    private lateinit var fakeTowerDao: FakeTowerDao
    private var currentTowerInfo: TowerConnectionInfo? = null

    companion object {
        fun newInstance(towerInfo: TowerConnectionInfo?): TowerDetailsBottomSheet {
            val fragment = TowerDetailsBottomSheet()
            val args = Bundle()
            args.putParcelable("tower_info", towerInfo)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getParcelable<TowerConnectionInfo>("tower_info")?.let {
            currentTowerInfo = it
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_tower_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

viewLifecycleOwner.lifecycleScope.launch {
try {
val database = com.cymatune.db.DatabaseManager.getDatabase()
fakeTowerDao = database.fakeTowerDao()
setupTowerDetails(view)
} catch (e: Exception) {
view.findViewById<TextView>(R.id.tvTowerIdValue).text = "DB Error"
}
}
    }

    private fun setupTowerDetails(view: View) {
        var towerInfo = currentTowerInfo

        if (towerInfo == null) {
            towerInfo = runBlocking {
                val recentTowers = withContext(Dispatchers.IO) {
                    fakeTowerDao.getAllFakeTowers().first()
                }
                recentTowers.maxByOrNull { it.detectionTime }?.let { dbTower ->
                    TowerConnectionInfo(
                        subscriptionId = 1,
                        slotIndex = 0,
                        mcc = dbTower.mcc,
                        mnc = dbTower.mnc,
                        lac = dbTower.lac,
                        cid = dbTower.cid,
                        signalStrength = dbTower.signalStrength,
                        timingAdvance = dbTower.timingAdvance,
                        pci = dbTower.pci,
                        arfcn = dbTower.arfcn,
                        band = null,
                        ssRsrp = null,
                        ssRsrq = dbTower.rsrq,
                        ssSinr = dbTower.sinr,
                        isRegistered = true,
                        estimatedLocation = if (dbTower.latitude != 0.0 && dbTower.longitude != 0.0) {
                            Pair(dbTower.latitude, dbTower.longitude)
                        } else null,
                        networkType = dbTower.networkType,
                        additionalInfo = mapOf("accuracy" to dbTower.accuracy.toString())
                    )
                }
            }
        } else {
            val dbTower = runBlocking {
                withContext(Dispatchers.IO) {
                    fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                }
            }

            if (dbTower != null) {
                val enrichedInfo = towerInfo.additionalInfo?.toMutableMap() ?: mutableMapOf()
                enrichedInfo["accuracy"] = dbTower.accuracy.toString()

                towerInfo = towerInfo.copy(
                    additionalInfo = enrichedInfo,
                    estimatedLocation = if (towerInfo.estimatedLocation == null && dbTower.latitude != 0.0) {
                        Pair(dbTower.latitude, dbTower.longitude)
                    } else towerInfo.estimatedLocation
                )
            }
        }

        if (towerInfo != null) {
            populateTowerDetails(view, towerInfo)
            setupMapButton(view, towerInfo)
        } else {
            view.findViewById<TextView>(R.id.tvTowerIdValue).text = "No data"
            view.findViewById<TextView>(R.id.tvNetworkTypeValue).text = "No data"
            view.findViewById<TextView>(R.id.tvSignalValue).text = "No data"
            view.findViewById<TextView>(R.id.tvRsrpValue).text = "No data"
            view.findViewById<TextView>(R.id.tvRsrqValue).text = "No data"
            view.findViewById<TextView>(R.id.tvSinrValue).text = "No data"
            view.findViewById<TextView>(R.id.tvAccuracyValue).text = "No data"
            view.findViewById<TextView>(R.id.tvLocationValue).text = "No data"
            view.findViewById<TextView>(R.id.tvTimestampValue).text = "No data"
            view.findViewById<TextView>(R.id.tvTrustScoreValue).text = "No data"
        }
    }

    private fun populateTowerDetails(view: View, towerInfo: TowerConnectionInfo) {
        view.findViewById<TextView>(R.id.tvTowerIdValue).text =
            "${towerInfo.mcc}-${towerInfo.mnc}-${towerInfo.lac}-${towerInfo.cid}"

        val networkId = if (towerInfo.mcc != null && towerInfo.mnc != null && towerInfo.mcc != 0 && towerInfo.mnc != 0) {
            "${towerInfo.mcc}-${towerInfo.mnc}"
        } else {
            "N/A"
        }
        view.findViewById<TextView>(R.id.tvNetworkTypeValue).text = "${towerInfo.networkType ?: "Unknown"} ($networkId)"

        view.findViewById<TextView>(R.id.tvSignalValue).text = "${towerInfo.signalStrength} dBm"

        val pci = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.pci ?: towerInfo.pci
            }
        }
        view.findViewById<TextView>(R.id.tvPciValue).text = if (pci != null && pci != Int.MAX_VALUE) "$pci" else "N/A"

        val arfcn = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.arfcn ?: towerInfo.arfcn
            }
        }
        view.findViewById<TextView>(R.id.tvArfcnValue).text = if (arfcn != null && arfcn != Int.MAX_VALUE) "$arfcn" else "N/A"

        val ta = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.timingAdvance ?: towerInfo.timingAdvance
            }
        }
        view.findViewById<TextView>(R.id.tvTaValue).text = if (ta != null && ta != Int.MAX_VALUE) "$ta" else "N/A"

        val rsrp = towerInfo.ssRsrp ?: towerInfo.signalStrength
        view.findViewById<TextView>(R.id.tvRsrpValue).text = "$rsrp dBm"

        val rsrq = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.rsrq ?: towerInfo.ssRsrq ?: -1
            }
        }
        view.findViewById<TextView>(R.id.tvRsrqValue).text = if (rsrq != -1 && rsrq != Int.MAX_VALUE) "$rsrq" else "Unknown"

        val sinr = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.sinr ?: towerInfo.ssSinr ?: -1
            }
        }
        view.findViewById<TextView>(R.id.tvSinrValue).text = if (sinr != -1 && sinr != Int.MAX_VALUE) "$sinr" else "Unknown"

        val accuracy = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.accuracy ?: towerInfo.additionalInfo?.get("accuracy")?.toIntOrNull() ?: 100
            }
        }
        view.findViewById<TextView>(R.id.tvAccuracyValue).text = accuracy.toString()

        val trustScore = runBlocking {
            withContext(Dispatchers.IO) {
                val dbTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
                dbTower?.trustScore ?: 100
            }
        }
        view.findViewById<TextView>(R.id.tvTrustScoreValue).text = trustScore.toString()

        val location = towerInfo.estimatedLocation
        if (location != null && (location.first != 0.0 || location.second != 0.0)) {
            view.findViewById<TextView>(R.id.tvLocationValue).text =
                "${String.format(java.util.Locale.US, "%.6f", location.first)}, ${String.format(java.util.Locale.US, "%.6f", location.second)}"
        } else {
            val distText = if (towerInfo.timingAdvance != null && towerInfo.timingAdvance!! > 0) {
                "~${towerInfo.timingAdvance!! * 78}m"
            } else {
                "Unknown"
            }
            view.findViewById<TextView>(R.id.tvLocationValue).text = "Triangulating... (Dist: $distText)"
        }

        val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
        val currentTime = sdf.format(Date())
        view.findViewById<TextView>(R.id.tvTimestampValue).text = currentTime
    }

    private fun setupMapButton(view: View, towerInfo: TowerConnectionInfo) {
        val btnMap = view.findViewById<View>(R.id.btnLocateOnMap)

        runBlocking {
            val dbTower = withContext(Dispatchers.IO) {
                fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
            }

            if (dbTower != null && dbTower.latitude != 0.0 && dbTower.longitude != 0.0) {
                btnMap.setOnClickListener {
                    val uri = Uri.parse("geo:${dbTower.latitude},${dbTower.longitude}?q=${dbTower.latitude},${dbTower.longitude}(Tower ${towerInfo.cid})")
                    val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                    try {
                        startActivity(mapIntent)
                    } catch (e: Exception) {
                        // No map app
                    }
                }
                btnMap.isEnabled = true
            } else {
                btnMap.isEnabled = false
                if (btnMap is com.google.android.material.button.MaterialButton) {
                    btnMap.text = "Location Unknown"
                }
            }
        }
    }
}