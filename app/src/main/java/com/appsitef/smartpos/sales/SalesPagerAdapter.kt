package com.appsitef.smartpos.sales

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.appsitef.smartpos.sales.ui.CustomerDataFragment
import com.appsitef.smartpos.sales.ui.FuelingSearchFragment
import com.appsitef.smartpos.sales.ui.SaleSummaryFragment

class SalesPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> FuelingSearchFragment()
            1 -> CustomerDataFragment()
            else -> SaleSummaryFragment()
        }
    }
}
