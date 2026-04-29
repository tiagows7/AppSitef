package com.appsitef.smartpos.sales.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.appsitef.smartpos.R
import com.appsitef.smartpos.sales.model.Abastecimento
import java.util.Locale

class AbastecimentoAdapter(
    private val onItemSelected: (Abastecimento) -> Unit
) : RecyclerView.Adapter<AbastecimentoAdapter.AbastecimentoViewHolder>() {

    private val items = mutableListOf<Abastecimento>()
    private var selectedId: String? = null

    fun updateItems(newItems: List<Abastecimento>) {
        items.clear()
        items.addAll(newItems)
        selectedId = null
        notifyDataSetChanged()
    }

    fun getSelectedItem(): Abastecimento? = items.firstOrNull { it.id == selectedId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbastecimentoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_abastecimento, parent, false)
        return AbastecimentoViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: AbastecimentoViewHolder, position: Int) {
        holder.bind(items[position], items[position].id == selectedId)
    }

    inner class AbastecimentoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFuel: ImageView = itemView.findViewById(R.id.ivFuelIcon)
        private val tvBico: TextView = itemView.findViewById(R.id.tvBico)
        private val tvQuantidade: TextView = itemView.findViewById(R.id.tvQuantidade)
        private val tvValorUnitario: TextView = itemView.findViewById(R.id.tvValorUnitario)
        private val tvValorTotal: TextView = itemView.findViewById(R.id.tvValorTotal)
        private val tvOperador: TextView = itemView.findViewById(R.id.tvOperador)

        fun bind(item: Abastecimento, isSelected: Boolean) {
            ivFuel.setImageResource(R.drawable.ic_fuel_pump)
            tvBico.text = "Bico ${item.numeroBico}"
            tvQuantidade.text = "Qtd: ${formatDecimal(item.quantidade)} L"
            tvValorUnitario.text = "Unit: R$ ${formatDecimal(item.valorUnitario)}"
            tvValorTotal.text = "Total: R$ ${formatDecimal(item.valorTotal)}"
            tvOperador.text = "Operador: ${item.operador}"
            itemView.isSelected = isSelected

            itemView.setOnClickListener {
                selectedId = item.id
                notifyDataSetChanged()
                onItemSelected(item)
            }
        }

        private fun formatDecimal(value: Double): String {
            return String.format(Locale("pt", "BR"), "%.2f", value)
        }
    }
}
