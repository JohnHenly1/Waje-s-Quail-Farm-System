package com.example.exp1

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.PieChart

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_USER = 0
        const val TYPE_BOT = 1
        const val TYPE_BOT_CHART = 2
    }

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        return when {
            msg.isUser -> TYPE_USER
            msg.chart != null -> TYPE_BOT_CHART
            else -> TYPE_BOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_BOT_CHART -> R.layout.item_message_bot_chart
            else -> R.layout.item_message_bot
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        if (message.chart != null) {
            val chartView = holder.itemView.findViewById<PieChart>(R.id.chartView)
            val titleView = holder.itemView.findViewById<TextView>(R.id.chartTitleText)
            titleView.text = message.chartTitle ?: "Grade Distribution"
            ChartHelper.configure(chartView)
            ChartHelper.populate(chartView, message.chart)

            holder.itemView.setOnLongClickListener {
                onLongPress(holder.bindingAdapterPosition)
                true
            }
            return
        }

        val messageView = holder.itemView.findViewById<TextView>(R.id.messageText)
        messageView.text = if (message.isUser) message.text else MarkdownFormatter.toSpannable(message.text)
        messageView.setOnLongClickListener {
            onLongPress(holder.bindingAdapterPosition)
            true
        }
    }

    override fun getItemCount() = messages.size
}