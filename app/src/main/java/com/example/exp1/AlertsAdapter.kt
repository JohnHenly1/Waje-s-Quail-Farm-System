package com.example.exp1

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Wraps a GlobalData.AlertItem with a pre-computed relative-time string and a
 * stable id for DiffUtil. Computing relativeTime once here (in updateAlertsList())
 * instead of inside onBindViewHolder means we don't re-parse/re-format timestamps
 * every time a row scrolls back into view.
 */
data class DisplayAlert(
    val alert: GlobalData.AlertItem,
    val relativeTime: String,
    val stableId: String
)

private const val VIEW_TYPE_ALERT = 0
private const val VIEW_TYPE_EMPTY = 1

class AlertsAdapter : ListAdapter<DisplayAlert, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    class AlertViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.alertIcon)
        val stripe: View = view.findViewById(R.id.alertAccentStripe)
        val categoryTv: TextView = view.findViewById(R.id.alertCategory)
        val timeTv: TextView = view.findViewById(R.id.alertTime)
        val titleTv: TextView = view.findViewById(R.id.alertTitle)
        val msgTv: TextView = view.findViewById(R.id.alertMessage)
        val pillTv: TextView = view.findViewById(R.id.alertStatusPill)
    }

    class EmptyViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).stableId == EMPTY_ID) VIEW_TYPE_EMPTY else VIEW_TYPE_ALERT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_EMPTY) {
            val tv = TextView(parent.context).apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(32, 64, 32, 32)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            EmptyViewHolder(tv)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
            AlertViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)

        if (holder is EmptyViewHolder) {
            holder.textView.text = item.alert.message
            return
        }

        holder as AlertViewHolder
        val alert = item.alert

        holder.msgTv.text = alert.message
        holder.timeTv.text = item.relativeTime

        when {
            alert.type == "Egg Count" || alert.type == "System" || alert.message.contains("Egg", true) -> {
                setupAlertUI(holder, "EGG PRODUCTION", "PRODUCTION", "#4CAF50", R.drawable.lc_egg)
                holder.titleTv.text = if (alert.message.contains("summary")) "Daily Production Summary" else "Egg Collection Alert"
            }
            alert.type == "Inventory" || alert.message.contains("Inventory", true) || (alert.type == "Critical" && alert.message.contains("Inventory", true)) -> {
                val isDepleted = alert.message.contains("EMPTY", true) || alert.message.contains("DEPLETED", true)
                val isCritical = isDepleted || alert.message.contains("LOW", true)
                val status = when {
                    isDepleted -> "STOCK DEPLETED"
                    alert.message.contains("LOW", true) -> "CRITICALLY LOW"
                    else -> "REORDER SOON"
                }
                setupAlertUI(holder, "INVENTORY", status, if (isCritical) "#F44336" else "#FF9800", R.drawable.ic_shopping_bag)
                holder.titleTv.text = if (isCritical) "Critical Stock Warning" else "Inventory Update"
            }
            alert.type == "Schedule" || alert.message.contains("Missed", true) || (alert.type == "Critical" && alert.message.contains("Missed", true)) -> {
                val isMissed = alert.message.contains("Missed", true)
                setupAlertUI(holder, "SCHEDULE", if (isMissed) "OVERDUE" else "DUE TODAY", if (isMissed) "#B71C1C" else "#2196F3", R.drawable.ic_calendar)
                holder.titleTv.text = if (isMissed) "Task Overdue Notice" else "Upcoming Task"
            }
            else -> {
                setupAlertUI(holder, "FARM ALERT", "INFO", "#9E9E9E", R.drawable.ic_info)
                holder.titleTv.text = "Notification"
            }
        }

        holder.itemView.alpha = if (alert.isRead) 0.6f else 1.0f

        // Deliberately NOT starting a slide-up Animation here. RecyclerView recycles
        // and re-binds views constantly as you scroll, so animating on every bind
        // (like the old code did on every refresh) would fire repeatedly and burn
        // CPU for no visual benefit. If a one-time entrance animation is wanted,
        // it should be driven by a scroll/adapter-position check, not onBindViewHolder.
    }

    private fun setupAlertUI(holder: AlertViewHolder, catName: String, statusText: String, colorHex: String, iconRes: Int) {
        val color = Color.parseColor(colorHex)
        holder.stripe.setBackgroundColor(color)
        holder.icon.setImageResource(iconRes)
        holder.icon.setColorFilter(color)
        holder.icon.backgroundTintList = ColorStateList.valueOf(color).withAlpha(30)
        holder.pillTv.text = statusText
        holder.pillTv.backgroundTintList = ColorStateList.valueOf(color)
        holder.categoryTv.text = catName
        holder.categoryTv.setTextColor(color)
    }

    companion object {
        const val EMPTY_ID = "__EMPTY__"

        fun emptyPlaceholder(message: String) = DisplayAlert(
            alert = GlobalData.AlertItem(message = message, timestamp = "", type = "", isRead = true),
            relativeTime = "",
            stableId = EMPTY_ID
        )

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DisplayAlert>() {
            override fun areItemsTheSame(oldItem: DisplayAlert, newItem: DisplayAlert): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: DisplayAlert, newItem: DisplayAlert): Boolean =
                oldItem == newItem
        }
    }
}