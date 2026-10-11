package com.cymatune.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cymatune.R
import com.cymatune.dialer.adapters.ContactsAdapter
import com.cymatune.dialer.models.ContactItem
import android.net.Uri
import android.content.Intent
import android.telecom.TelecomManager

/**
 * Fragment to display and search system contacts in Phase 2.4.
 * Queries the ContactsContract and handles search filtering.
 */
class ContactsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactsAdapter
    private lateinit var searchView: SearchView
    private lateinit var emptyState: TextView

    private var allContacts = mutableListOf<ContactItem>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadContacts()
        } else {
            showPermissionDeniedState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_contacts, container, false)
        
        recyclerView = view.findViewById(R.id.contactsRecyclerView)
        searchView = view.findViewById(R.id.contactsSearchView)
        emptyState = view.findViewById(R.id.emptyStateText)
        setupRecyclerView()
        setupSearchView()
        checkPermissionsAndLoad()

        return view
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter { number ->
            initiateCall(number)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterContacts(newText ?: "")
                return true
            }
        })
    }

    private fun checkPermissionsAndLoad() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) 
            == PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun loadContacts() {
        val contacts = mutableListOf<ContactItem>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )

        val cursor: Cursor? = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Unknown"
                val initial = if (name.isNotEmpty()) name.take(1).uppercase() else "?"
                
                contacts.add(
                    ContactItem(
                        id = it.getLong(idIdx),
                        name = name,
                        number = it.getString(numberIdx) ?: "",
                        initial = initial
                    )
                )
            }
        }

        allContacts = contacts
        adapter.submitList(allContacts)
        emptyState.visibility = if (allContacts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun filterContacts(query: String) {
        val filtered = if (query.isEmpty()) {
            allContacts
        } else {
            allContacts.filter { it.name.contains(query, ignoreCase = true) || it.number.contains(query) }
        }
        adapter.submitList(filtered)
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showPermissionDeniedState() {
        emptyState.text = "Contacts permission required to search and dial."
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
            android.util.Log.e("ContactsFragment", "Failed to initiate call", e)
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
            android.util.Log.e("ContactsFragment", "Failed to place call with SIM", e)
        }
    }
}
