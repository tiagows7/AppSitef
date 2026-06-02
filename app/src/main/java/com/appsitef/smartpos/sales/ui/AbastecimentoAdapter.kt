package com.appsitef.smartpos.sales.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
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
        private val tvProduto: TextView = itemView.findViewById(R.id.tvProduto)
        private val tvHora: TextView = itemView.findViewById(R.id.tvHora)
        private val tvQuantidade: TextView = itemView.findViewById(R.id.tvQuantidade)
        private val tvValorUnitario: TextView = itemView.findViewById(R.id.tvValorUnitario)
        private val tvValorTotal: TextView = itemView.findViewById(R.id.tvValorTotal)
        private val tvOperador: TextView = itemView.findViewById(R.id.tvOperador)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(item: Abastecimento, isSelected: Boolean) {
            ivFuel.setImageResource(R.drawable.ic_fuel_pump)
            tvBico.text = "Bomba ${item.ababmb}"
            tvProduto.text = item.abaprodes
            tvHora.text = "Hora: ${item.abahoradia}"
            tvQuantidade.text = "Qtd: ${formatDecimal(item.abaqtd)} L"
            tvValorUnitario.text = "Unit: R$ ${formatDecimal(item.abavlruni)}"
            tvValorTotal.text = "R$ ${formatDecimal(item.abatot)}"
            tvOperador.text = "Operador: ${item.abaopedes}"
            itemView.isSelected = isSelected
            card.strokeWidth = if (isSelected) 3 else 1

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
