package com.appsitef.smartpos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.appsitef.smartpos.sales.SalesPagerAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class SalesActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales)
        setSupportActionBar(findViewById(R.id.toolbarSales))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        tabLayout = findViewById(R.id.tabSales)
        viewPager = findViewById(R.id.vpSales)
        viewPager.adapter = SalesPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Abastecimento"
                1 -> "Cliente"
                else -> "Fechamento"
            }
        }.attach()
    }

    fun goToTab(index: Int) {
        if (index in 0..2) {
            viewPager.currentItem = index
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
