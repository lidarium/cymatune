package com.cymatune.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.CallLog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cymatune.R
import com.cymatune.dialer.adapters.CallLogsAdapter
import com.cymatune.dialer.models.CallLogItem
import com.cymatune.util.ContactUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.content.Intent
import android.telecom.TelecomManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fragment to display system call history in Phase 2.3.
 * Queries the CallLog content provider and handles permissions.
 * Now includes search/filter functionality (B04 fix).
 */
class CallLogsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CallLogsAdapter
    private lateinit var emptyState: TextView
    private lateinit var searchInput: EditText
    private var allCallLogs = listOf<CallLogItem>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadCallLogs()
        } else {
            showPermissionDeniedState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_call_logs, container, false)

        recyclerView = view.findViewById(R.id.callLogsRecyclerView)
        emptyState = view.findViewById(R.id.emptyStateText)
        searchInput = view.findViewById(R.id.searchInput)

        setupRecyclerView()
        setupSearch()
        checkPermissionsAndLoad()

        return view
    }

    private fun setupRecyclerView() {
        adapter = CallLogsAdapter { number ->
            initiateCall(number)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterLogs(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Clear button is handled natively by TextInputLayout's endIconMode="clear_text"
    }

    private fun filterLogs(query: String) {
        val filtered = if (query.isBlank()) {
            allCallLogs
        } else {
            val normalizedQuery = query.lowercase()
            allCallLogs.filter { log ->
                val name = log.name?.lowercase() ?: ""
                val number = log.number.lowercase()
                name.contains(normalizedQuery) || number.contains(normalizedQuery)
            }
        }
        adapter.submitList(filtered)
        emptyState.visibility = if (filtered.isEmpty() && allCallLogs.isNotEmpty()) {
            emptyState.text = "No calls matching \"$query\"."
            View.VISIBLE
        } else if (filtered.isEmpty()) {
            emptyState.text = "No call logs found."
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun checkPermissionsAndLoad() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALL_LOG)
            == PackageManager.PERMISSION_GRANTED) {
            loadCallLogs()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    private fun loadCallLogs() {
        lifecycleScope.launch {
            val callLogs = withContext(Dispatchers.IO) {
                val logs = mutableListOf<CallLogItem>()
                val projection = arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                )

                val cursor: Cursor? = requireContext().contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )

            cursor?.use { it ->
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                // B13 FIX: Use normalized number strings as keys for contact lookup
                val numbersToResolve = mutableSetOf<String>()
                val tempLogs = mutableListOf<Triple<Long, String, CallLogItem>>()

                while (it.moveToNext()) {
                    val number = it.getString(numberIdx) ?: "Unknown"
                    val cachedName = it.getString(nameIdx)
                    val dateLong = it.getLong(dateIdx)
                    val formattedDate = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(dateLong))

                    // B13 FIX: Store normalized number for lookup key
                    val normalizedNumber = ContactUtils.normalizePhoneNumber(number)

                    // If no cached name, add normalized number to resolution list
                    if (cachedName.isNullOrBlank() && number != "Unknown") {
                        numbersToResolve.add(normalizedNumber)
                    }

                    val logItem = CallLogItem(
                        id = it.getLong(idIdx),
                        number = number,
                        name = cachedName,
                        type = it.getInt(typeIdx),
                        date = dateLong,
                        duration = it.getLong(durationIdx),
                        formattedDate = formattedDate
                    )

                    // B13 FIX: Use normalized number as lookup key
                    tempLogs.add(Triple(it.getLong(idIdx), normalizedNumber, logItem))
                }

                // Batch resolve contact names using normalized numbers
                val resolvedNames = if (numbersToResolve.isNotEmpty()) {
                    ContactUtils.resolveContactNamesBatch(requireContext(), numbersToResolve.toList())
                } else {
                    emptyMap()
                }

                // B13 FIX: Use normalized number for map lookup
                tempLogs.forEach { (_, normalizedNumber, logItem) ->
                    val resolvedName = resolvedNames[normalizedNumber]
                    val finalName = logItem.name ?: resolvedName
                    logs.add(logItem.copy(name = finalName))
                }
            }

                logs
            }

            allCallLogs = callLogs
            adapter.submitList(callLogs)
            emptyState.visibility = if (callLogs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showPermissionDeniedState() {
        emptyState.text = "Call log permission required to view history."
        emptyState.visibility = View.VISIBLE
    }

    /**
     * Phase 4: Multi-SIM support
     * Shows SIM selection if multiple SIMs available, then places call
     */
    private fun initiateCall(number: String) {
        try {
            val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefault = requireContext().packageName == telecomManager.defaultDialerPackage

            if (!isDefault) {
                // Fallback to system dialer
                val uri = Uri.fromParts("tel", number, null)
                val intent = Intent(Intent.ACTION_DIAL, uri)
                startActivity(intent)
                return
            }

            // Check if device has multiple SIMs
            if (SimSelectionHelper.hasMultipleSims(requireContext())) {
                // Show SIM selection bottom sheet
                val bottomSheet = SimSelectionBottomSheet.newInstance()
                bottomSheet.setOnSimSelectedListener { phoneAccountHandle ->
                    placeCallWithSim(number, phoneAccountHandle)
                }
                bottomSheet.show(parentFragmentManager, SimSelectionBottomSheet::class.java.simpleName)
            } else {
                // Single SIM - place call directly
                placeCallWithSim(number, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("CallLogsFragment", "Failed to initiate call", e)
        }
    }

    /**
     * Place call with selected SIM (or default if phoneAccountHandle is null)
     */
    private fun placeCallWithSim(number: String, phoneAccountHandle: android.telecom.PhoneAccountHandle?) {
        try {
            val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", number, null)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && phoneAccountHandle != null) {
                // Place call with specific SIM
                val extras = android.os.Bundle()
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                telecomManager.placeCall(uri, extras)
            } else {
                // Place call with default SIM
                telecomManager.placeCall(uri, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("CallLogsFragment", "Failed to place call with SIM", e)
        }
    }
}
