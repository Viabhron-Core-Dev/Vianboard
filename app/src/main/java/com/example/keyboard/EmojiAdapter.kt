package com.example.keyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R

class EmojiAdapter(
    private var emojis: List<String>,
    private val onClick: (String) -> Unit,
    private val onLongClick: ((String, View) -> Unit)? = null
) : RecyclerView.Adapter<EmojiAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_emoji, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = emojis[position]
        holder.textView.text = emoji
        holder.itemView.setOnClickListener { onClick(emoji) }
        holder.itemView.setOnLongClickListener { v ->
            if (onLongClick != null) {
                onLongClick.invoke(emoji, v)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount() = emojis.size
    
    fun updateData(newEmojis: List<String>) {
        emojis = newEmojis
        notifyDataSetChanged()
    }
}
