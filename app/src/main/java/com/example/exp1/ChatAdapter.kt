package com.example.exp1

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_USER = 0
        const val TYPE_BOT = 1
    }

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) TYPE_USER else TYPE_BOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = if (viewType == TYPE_USER) R.layout.item_message_user else R.layout.item_message_bot
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val messageView = holder.itemView.findViewById<TextView>(R.id.messageText)
        val message = messages[position]

        messageView.text = if (message.isUser) {
            message.text
        } else {
            MarkdownFormatter.toSpannable(message.text)
        }

        messageView.setOnLongClickListener {
            onLongPress(holder.adapterPosition)
            true
        }
    }

    override fun getItemCount() = messages.size
}