package com.cymatune.dialer.adapters

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.cymatune.dialer.CallLogsFragment
import com.cymatune.dialer.ContactsFragment
import com.cymatune.dialer.DialpadFragment

/**
 * Pager adapter for the DialerFragment tabs in Phase 2.
 * Hosts Dialpad, Call Logs, and Contacts.
 */
class DialerPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> DialpadFragment()
            1 -> CallLogsFragment()
            2 -> ContactsFragment()
            else -> DialpadFragment()
        }
    }
}
