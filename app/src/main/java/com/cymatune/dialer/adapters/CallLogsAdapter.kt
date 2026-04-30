package com.cymatune.dialer.adapters

import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cymatune.R
import com.cymatune.dialer.models.CallLogItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for the CallLogsFragment in Phase 2.3.
 * Uses DiffUtil for high-performance 60fps scrolling.
 * Now supports filtering via CallLogsFragment's search functionality.
 */
class CallLogsAdapter(
    private val onCallClick: (String) -> Unit
) : ListAdapter<CallLogItem, CallLogsAdapter.ViewHolder>(CallLogDiffCallback()) {

    // Original unfiltered list
    private var originalList = listOf<CallLogItem>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onCallClick)
    }

    /**
     * Submit the full list and store original for filtering
     */
    fun submitFullList(list: List<CallLogItem>) {
        originalList = list
        submitList(list)
    }

    /**
     * Filter the list based on a query string
     * Searches in both contact name and phone number
     */
    fun filter(query: String) {
        val normalizedQuery = query.lowercase().trim()
        val filtered = if (normalizedQuery.isEmpty()) {
            originalList
        } else {
            originalList.filter { item ->
                val name = item.name?.lowercase() ?: ""
                val number = item.number.lowercase()
                name.contains(normalizedQuery) || number.contains(normalizedQuery)
            }
        }
        submitList(filtered)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.callTypeIcon)
        private val name: TextView = view.findViewById(R.id.contactName)
        private val details: TextView = view.findViewById(R.id.callDetails)
        private val btnDialBack: ImageButton = view.findViewById(R.id.btnDialBack)

        fun bind(item: CallLogItem, onCallClick: (String) -> Unit) {
            name.text = item.name ?: item.number

            val durationMin = item.duration / 60
            val durationText = if (durationMin > 0) " (${durationMin} min)" else ""
            details.text = "${item.label ?: "Mobile"} • ${item.formattedDate}$durationText"

            val iconRes = when (item.type) {
                CallLog.Calls.INCOMING_TYPE -> android.R.drawable.sym_call_incoming
                CallLog.Calls.OUTGOING_TYPE -> android.R.drawable.sym_call_outgoing
                CallLog.Calls.MISSED_TYPE -> android.R.drawable.sym_call_missed
                else -> android.R.drawable.sym_call_incoming
            }
            icon.setImageResource(iconRes)

            val colorRes = if (item.type == CallLog.Calls.MISSED_TYPE) R.color.warning_red else R.color.primary_neon
            icon.setColorFilter(ContextCompat.getColor(itemView.context, colorRes))

            btnDialBack.setOnClickListener { onCallClick(item.number) }
            itemView.setOnClickListener { onCallClick(item.number) }
        }
    }

    class CallLogDiffCallback : DiffUtil.ItemCallback<CallLogItem>() {
        override fun areItemsTheSame(oldItem: CallLogItem, newItem: CallLogItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CallLogItem, newItem: CallLogItem): Boolean {
            return oldItem == newItem
        }
    }
}
