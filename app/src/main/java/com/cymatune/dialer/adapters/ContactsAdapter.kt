package com.cymatune.dialer.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cymatune.R
import com.cymatune.dialer.models.ContactItem

/**
 * Adapter for the ContactsFragment in Phase 2.4.
 * Uses DiffUtil for efficient list updates and smooth scrolling.
 */
class ContactsAdapter(
    private val onContactClick: (String) -> Unit
) : ListAdapter<ContactItem, ContactsAdapter.ViewHolder>(ContactDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onContactClick)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val initial: TextView = view.findViewById(R.id.contactInitial)
        private val name: TextView = view.findViewById(R.id.contactName)
        private val phone: TextView = view.findViewById(R.id.contactPhone)
        private val btnDial: ImageButton = view.findViewById(R.id.btnDial)

        fun bind(item: ContactItem, onContactClick: (String) -> Unit) {
            initial.text = item.initial
            name.text = item.name
            phone.text = item.number

            btnDial.setOnClickListener { onContactClick(item.number) }
            itemView.setOnClickListener { onContactClick(item.number) }
        }
    }

    class ContactDiffCallback : DiffUtil.ItemCallback<ContactItem>() {
        override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem == newItem
        }
    }
}
