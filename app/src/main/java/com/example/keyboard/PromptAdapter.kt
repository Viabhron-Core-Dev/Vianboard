package com.example.keyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class PromptAdapter(
    private val onItemClicked: (PersonalDictionaryItem) -> Unit,
    private val onItemLongClicked: (PersonalDictionaryItem) -> Unit
) : RecyclerView.Adapter<PromptAdapter.PromptViewHolder>() {

    private val items = mutableListOf<PersonalDictionaryItem>()

    fun setItems(newItems: List<PersonalDictionaryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromptViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt, parent, false)
        return PromptViewHolder(view)
    }

    override fun onBindViewHolder(holder: PromptViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class PromptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPromptText: TextView = itemView.findViewById(R.id.tv_prompt_text)
        private val tvPromptShortcut: TextView = itemView.findViewById(R.id.tv_prompt_shortcut)

        fun bind(item: PersonalDictionaryItem) {
            tvPromptText.text = item.word
            if (!item.shortcut.isNullOrEmpty()) {
                tvPromptShortcut.visibility = View.VISIBLE
                tvPromptShortcut.text = item.shortcut
            } else {
                tvPromptShortcut.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onItemClicked(item)
            }
            itemView.setOnLongClickListener {
                onItemLongClicked(item)
                true
            }
        }
    }
}
