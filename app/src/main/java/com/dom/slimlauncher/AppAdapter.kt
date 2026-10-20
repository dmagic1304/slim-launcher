package com.dom.slimlauncher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid adapter. A null entry in the list is the built-in "All apps" tile.
 */
class AppAdapter(
    private val icons: IconLoader,
    private val labelOf: (AppEntry) -> String,
    private val onClick: (AppEntry?) -> Unit,
    private val onLongClick: (AppEntry, Int) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private val list = ArrayList<AppEntry?>()

    class VH(val root: LinearLayout, val icon: ImageView, val label: TextView) :
        RecyclerView.ViewHolder(root)

    fun submit(items: List<AppEntry?>) {
        list.clear()
        list.addAll(items)
        notifyDataSetChanged()
    }

    fun refresh(pkg: String) {
        val i = list.indexOfFirst { it?.pkg == pkg }
        if (i >= 0) notifyItemChanged(i)
    }

    fun insertAt(i: Int, e: AppEntry) {
        list.add(i, e)
        notifyItemInserted(i)
    }

    fun removeAt(i: Int) {
        list.removeAt(i)
        notifyItemRemoved(i)
    }

    fun move(from: Int, to: Int) {
        list.add(to, list.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = list.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(10))
            isFocusable = true
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)))

        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        root.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val focusBg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(51, 255, 255, 255))
            setStroke(dp(2), Color.argb(204, 255, 255, 255))
        }
        root.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) focusBg else null
            val s = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(100).start()
            v.translationZ = if (hasFocus) 10f else 0f
        }
        return VH(root, icon, label)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val entry = list[position]
        if (entry == null) {
            h.label.text = "All apps"
            h.icon.setImageDrawable(h.root.context.getDrawable(R.drawable.ic_all_apps))
        } else {
            h.label.text = labelOf(entry)
            h.icon.setImageDrawable(icons.get(entry))
        }
        h.root.setOnClickListener { onClick(list.getOrNull(h.bindingAdapterPosition)) }
        h.root.setOnLongClickListener {
            val e = list.getOrNull(h.bindingAdapterPosition)
            if (e != null) onLongClick(e, h.bindingAdapterPosition)
            true
        }
    }
}
