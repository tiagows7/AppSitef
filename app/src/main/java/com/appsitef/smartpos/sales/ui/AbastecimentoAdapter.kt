package com.appsitef.smartpos.sales.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.appsitef.smartpos.R
import com.appsitef.smartpos.sales.model.Abastecimento
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class AbastecimentoAdapter(
    private val onSelectionChanged: (List<Abastecimento>) -> Unit,
    private val onItemToggled: ((Abastecimento, Boolean) -> Unit)? = null,
) : RecyclerView.Adapter<AbastecimentoAdapter.AbastecimentoViewHolder>() {

    private val items = mutableListOf<Abastecimento>()
    private val selectedIds = mutableSetOf<String>()
    private var interactionLocked = false

    fun updateItems(newItems: List<Abastecimento>, keepSelection: Boolean = false) {
        val previousSelected = selectedIds.toSet()
        items.clear()
        items.addAll(newItems)
        if (!keepSelection) {
            selectedIds.clear()
        } else {
            selectedIds.retainAll(newItems.map { it.id }.toSet())
        }
        notifyDataSetChanged()
        if (selectedIds != previousSelected) {
            notifySelectionChanged()
        }
    }

    fun setSelectedIds(ids: Collection<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<Abastecimento> = items.filter { selectedIds.contains(it.id) }

    fun setInteractionLocked(locked: Boolean) {
        if (interactionLocked == locked) return
        interactionLocked = locked
        notifyDataSetChanged()
    }

    fun deselectItem(itemId: String) {
        if (!selectedIds.remove(itemId)) return
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbastecimentoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_abastecimento, parent, false)
        return AbastecimentoViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: AbastecimentoViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, selectedIds.contains(item.id), interactionLocked) {
            val wasSelected = selectedIds.contains(item.id)
            if (wasSelected) {
                onItemToggled?.invoke(item, false) ?: run {
                    selectedIds.remove(item.id)
                    notifyItemChanged(position)
                    notifySelectionChanged()
                }
            } else {
                onItemToggled?.invoke(item, true) ?: run {
                    selectedIds.add(item.id)
                    notifyItemChanged(position)
                    notifySelectionChanged()
                }
            }
        }
    }

    fun confirmSelection(item: Abastecimento) {
        if (selectedIds.contains(item.id)) return
        selectedIds.add(item.id)
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun confirmDeselection(item: Abastecimento) {
        if (!selectedIds.remove(item.id)) return
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    private fun notifySelectionChanged() {
        onSelectionChanged(getSelectedItems())
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
        private val tvSelecionadoBadge: TextView = itemView.findViewById(R.id.tvSelecionadoBadge)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(item: Abastecimento, isSelected: Boolean, locked: Boolean, onToggle: () -> Unit) {
            ivFuel.setImageResource(R.drawable.ic_fuel_nozzle)
            tvBico.text = item.ababmb.ifBlank { "-" }
            tvProduto.text = item.abaprodes.ifBlank { "Produto" }
            tvHora.text = item.abahoradia.ifBlank { "--:--" }
            tvQuantidade.text = "${formatDecimal(item.abaqtd)} L"
            tvValorUnitario.text = "R$ ${formatDecimal(item.abavlruni)}/L"
            tvValorTotal.text = "R$ ${formatDecimal(item.abatot)}"
            tvOperador.text = item.abaopedes.ifBlank { "" }
            tvOperador.visibility = if (item.abaopedes.isBlank()) View.GONE else View.VISIBLE

            val outline = ContextCompat.getColor(itemView.context, R.color.brand_outline)
            val surface = ContextCompat.getColor(itemView.context, R.color.brand_surface)
            val onSurface = ContextCompat.getColor(itemView.context, R.color.brand_on_surface)
            val primary = ContextCompat.getColor(itemView.context, R.color.brand_primary)
            val selectedStroke = ContextCompat.getColor(itemView.context, R.color.fuel_selected_stroke)
            val selectedContainer = ContextCompat.getColor(itemView.context, R.color.fuel_selected_container)
            val selectedAccent = ContextCompat.getColor(itemView.context, R.color.fuel_selected_accent)

            card.strokeWidth = if (isSelected) 3 else 1
            card.setStrokeColor(if (isSelected) selectedStroke else outline)
            card.setCardBackgroundColor(if (isSelected) selectedContainer else surface)
            card.cardElevation = if (isSelected) 6f else 2f

            tvProduto.setTextColor(if (isSelected) selectedAccent else onSurface)
            tvValorTotal.setTextColor(if (isSelected) selectedAccent else primary)
            tvSelecionadoBadge.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.isEnabled = !locked
            itemView.alpha = if (locked) 0.6f else 1f
            itemView.setOnClickListener { if (!locked) onToggle() }
        }

        private fun formatDecimal(value: Double): String {
            return String.format(Locale("pt", "BR"), "%.2f", value)
        }
    }
}
