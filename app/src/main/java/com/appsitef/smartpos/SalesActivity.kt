package com.appsitef.smartpos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.appsitef.smartpos.sales.AbastecimentoIntentSerializer
import com.appsitef.smartpos.sales.SaleAmountRules
import com.appsitef.smartpos.sales.SaleContextIntentSerializer
import com.appsitef.smartpos.sales.SalesPagerAdapter
import com.appsitef.smartpos.sales.SalesViewModel
import com.appsitef.smartpos.tef.TefTerminalTotalsStore
import com.appsitef.smartpos.tef.TefTransactionResult
import com.appsitef.smartpos.sales.network.AbastecimentoRemoteRepository
import com.appsitef.smartpos.tef.CliSiTefAssetInstaller
import com.appsitef.smartpos.tef.CliSiTefIHolder
import com.appsitef.smartpos.tef.GertecPinpadBootstrap
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.ui.MoneyInputMask
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.appsitef.smartpos.ui.WaitDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SalesActivity : AppCompatActivity() {

    private val salesViewModel: SalesViewModel by viewModels()
    private val abastecimentoRepository by lazy { AbastecimentoRemoteRepository(this) }
    private lateinit var viewPager: ViewPager2
    private lateinit var cardToolbarTotal: MaterialCardView
    private lateinit var tvToolbarCount: TextView
    private lateinit var tvToolbarAmount: TextView
    private lateinit var tvToolbarGrossAmount: TextView
    private lateinit var tvToolbarDiscount: TextView
    private lateinit var btnCancelarVenda: MaterialButton

    private val tefSaleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val tefResultJson = result.data?.getStringExtra(TefTransactionActivity.EXTRA_TEF_RESULT_JSON)
        salesViewModel.setLastTefTransactionResult(TefTransactionResult.fromJson(tefResultJson))
        recordApprovedSaleInTerminalTotals()
        salesViewModel.clearTransactionData()
        goToTab(0)
        Toast.makeText(this, R.string.sale_completed_reset, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarSales)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val header = LayoutInflater.from(this).inflate(R.layout.toolbar_sales_header, toolbar, false)
        toolbar.addView(header)
        cardToolbarTotal = header.findViewById(R.id.cardToolbarTotal)
        tvToolbarCount = header.findViewById(R.id.tvToolbarTotalCount)
        tvToolbarAmount = header.findViewById(R.id.tvToolbarTotalAmount)
        tvToolbarGrossAmount = header.findViewById(R.id.tvToolbarGrossAmount)
        tvToolbarDiscount = header.findViewById(R.id.tvToolbarDiscount)
        btnCancelarVenda = header.findViewById(R.id.btnToolbarCancelarVenda)

        val tabLayout = findViewById<TabLayout>(R.id.tabSales)
        viewPager = findViewById(R.id.vpSales)
        viewPager.isUserInputEnabled = false
        viewPager.adapter = SalesPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { _, _ -> }.attach()

        salesViewModel.selectedAbastecimentos.observe(this) {
            salesViewModel.refreshSaleAmountLimit()
            updateToolbarTotal()
        }
        salesViewModel.saleAbastecimentos.observe(this) {
            salesViewModel.refreshSaleAmountLimit()
            updateToolbarTotal()
        }
        salesViewModel.abastecimentos.observe(this) { updateToolbarTotal() }
        salesViewModel.totalDesconto.observe(this) {
            salesViewModel.refreshSaleAmountLimit()
            updateToolbarTotal()
        }

        btnCancelarVenda.setOnClickListener { confirmCancelSale() }
        salesViewModel.refreshSaleAmountLimit()
        updateToolbarTotal()

        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        CliSiTefAssetInstaller.ensureInstalled(this)
        GertecPinpadBootstrap.ensureGediReady(this)
        CliSiTefIHolder.warmUp(this)
    }

    fun launchTefSale(amount: String, operator: String, functionId: Int): Boolean {
        val limitCents = salesViewModel.getSaleAmountLimitCents()
        val amountCents = MoneyInputMask.parseToCents(amount)

        if (SaleAmountRules.exceedsLimitCents(amountCents, limitCents)) {
            Toast.makeText(
                this,
                getString(
                    R.string.error_sale_value_exceeds_total,
                    MoneyInputMask.formatFromCents(limitCents),
                ),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }

        val saleAbastecimentos = salesViewModel.saleAbastecimentos.value.orEmpty()
        tefSaleLauncher.launch(
            TefTransactionActivity.intent(
                context = this,
                amount = amount,
                operator = operator,
                functionId = functionId,
                saleAbastecimentosJson = AbastecimentoIntentSerializer.toJson(saleAbastecimentos),
                saleContextJson = SaleContextIntentSerializer.toJson(
                    salesViewModel.buildSaleContext(),
                ),
            )
        )
        return true
    }

    fun goToTab(index: Int) {
        if (index in 0..2) {
            viewPager.setCurrentItem(index, false)
        }
    }

    fun goToPreviousTab() {
        if (viewPager.currentItem > 0) {
            viewPager.setCurrentItem(viewPager.currentItem - 1, false)
        }
    }

    fun getCurrentTab(): Int = viewPager.currentItem

    private fun updateToolbarTotal() {
        val selected = salesViewModel.getToolbarAbastecimentos()
        val count = selected.size
        val discount = salesViewModel.getToolbarDiscountTotal()
        val netTotal = salesViewModel.getToolbarNetTotal()
        val hasSelection = count > 0

        val primary = ContextCompat.getColor(this, R.color.brand_primary)
        val primaryContainer = ContextCompat.getColor(this, R.color.brand_primary_container)
        val outline = ContextCompat.getColor(this, R.color.brand_outline)
        val mutedAmount = ContextCompat.getColor(this, R.color.brand_outline)

        cardToolbarTotal.strokeWidth = if (hasSelection) 2 else 1
        cardToolbarTotal.strokeColor = if (hasSelection) primary else outline
        cardToolbarTotal.setCardBackgroundColor(primaryContainer)

        if (hasSelection) {
            tvToolbarCount.text = if (count == 1) {
                getString(R.string.sales_total_count_one)
            } else {
                getString(R.string.sales_total_count_many, count)
            }
            if (discount > 0.0) {
                val grossTotal = salesViewModel.getToolbarGrossTotal()
                tvToolbarGrossAmount.visibility = View.VISIBLE
                tvToolbarGrossAmount.text = getString(
                    R.string.sales_total_amount,
                    formatMoney(grossTotal)
                )
                tvToolbarDiscount.visibility = View.VISIBLE
                tvToolbarDiscount.text = getString(
                    R.string.sales_discount_amount,
                    formatMoney(discount)
                )
                tvToolbarAmount.text = getString(
                    R.string.sales_net_total_amount,
                    formatMoney(netTotal)
                )
            } else {
                tvToolbarGrossAmount.visibility = View.GONE
                tvToolbarDiscount.visibility = View.GONE
                tvToolbarAmount.text = getString(
                    R.string.sales_total_amount,
                    formatMoney(salesViewModel.getToolbarGrossTotal())
                )
            }
            tvToolbarAmount.setTextColor(primary)
            tvToolbarAmount.textSize = 20f
            btnCancelarVenda.visibility = View.VISIBLE
        } else {
            tvToolbarCount.text = getString(R.string.sales_total_empty)
            tvToolbarGrossAmount.visibility = View.GONE
            tvToolbarDiscount.visibility = View.GONE
            tvToolbarAmount.text = getString(R.string.sales_total_empty_amount)
            tvToolbarAmount.setTextColor(mutedAmount)
            tvToolbarAmount.textSize = 16f
            btnCancelarVenda.visibility = View.GONE
        }
    }

    private fun formatMoney(value: Double): String {
        return String.format(Locale("pt", "BR"), "%.2f", value)
    }

    private fun recordApprovedSaleInTerminalTotals() {
        val operator = salesViewModel.saleOperator.value.orEmpty()
        var amount = MoneyInputMask.parseToDouble(salesViewModel.saleValue.value.orEmpty())
        if (amount <= 0.0) {
            amount = salesViewModel.getSaleNetTotalAbatot()
        }
        TefTerminalTotalsStore.recordApprovedSale(this, amount, operator)
    }

    private fun confirmCancelSale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cancel_sale)
            .setMessage(R.string.cancel_sale_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                liberarAbastecimentosECancelarVenda()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        handleBackNavigation()
        return true
    }

    private fun handleBackNavigation() {
        if (viewPager.currentItem > 0) {
            goToPreviousTab()
            return
        }
        if (hasSelectedAbastecimentos()) {
            Toast.makeText(this, getString(R.string.sale_in_progress), Toast.LENGTH_LONG).show()
            return
        }
        finish()
    }

    private fun hasSelectedAbastecimentos(): Boolean {
        return salesViewModel.selectedAbastecimentos.value.orEmpty().isNotEmpty()
    }

    private fun liberarAbastecimentosECancelarVenda() {
        val abastecimentos = salesViewModel.getAbastecimentosParaLiberar()
        if (abastecimentos.isEmpty()) {
            salesViewModel.cancelSale()
            goToTab(0)
            return
        }

        lifecycleScope.launch {
            btnCancelarVenda.isEnabled = false
            val waitDialog = WaitDialog.show(this@SalesActivity, R.string.wait_message_releasing_fueling)
            try {
                withContext(Dispatchers.IO) {
                    abastecimentoRepository.liberarAbastecimentos(abastecimentos)
                }
                salesViewModel.cancelSale()
                goToTab(0)
            } catch (error: Exception) {
                Toast.makeText(
                    this@SalesActivity,
                    "Erro ao liberar abastecimentos: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                waitDialog.dismiss()
                btnCancelarVenda.isEnabled = true
            }
        }
    }
}
