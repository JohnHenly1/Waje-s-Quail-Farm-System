package com.example.exp1

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

class ConversationHistoryAdapter(
    private val conversations: MutableList<SavedConversation>,
    private val onOpen: (SavedConversation) -> Unit,
    private val onDelete: (SavedConversation) -> Unit
) : RecyclerView.Adapter<ConversationHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.conversationItemTitle)
        val date: TextView = view.findViewById(R.id.conversationItemDate)
        val deleteBtn: ImageButton = view.findViewById(R.id.deleteConversationButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val convo = conversations[position]
        val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        holder.title.text = convo.title.ifBlank { "New conversation" }
        holder.date.text = "${sdf.format(convo.timestamp)} · ${convo.messages.size} messages"

        holder.itemView.setOnClickListener { onOpen(convo) }
        holder.deleteBtn.setOnClickListener { onDelete(convo) }
    }

    override fun getItemCount() = conversations.size
}