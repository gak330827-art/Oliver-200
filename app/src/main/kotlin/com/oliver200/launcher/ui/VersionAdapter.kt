/*
 * Oliver-200 · адаптер списка версий. Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oliver200.launcher.R
import com.oliver200.launcher.core.mojang.VersionSummary

data class VersionRow(val summary: VersionSummary, val installed: Boolean, val selected: Boolean)

class VersionAdapter(
    private val onAction: (VersionRow) -> Unit,
) : ListAdapter<VersionRow, VersionAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_version, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val id: TextView = view.findViewById(R.id.version_id)
        private val meta: TextView = view.findViewById(R.id.version_meta)
        private val action: MaterialButton = view.findViewById(R.id.action)

        fun bind(row: VersionRow) {
            val ctx = itemView.context
            id.text = row.summary.id
            meta.text = ctx.getString(
                R.string.version_subtitle,
                row.summary.channel.ruLabel,
                row.summary.year,
            )
            action.setText(
                when {
                    row.selected -> R.string.versions_installed
                    row.installed -> R.string.launch_button
                    else -> R.string.versions_install
                },
            )
            action.isEnabled = !row.selected
            action.setOnClickListener { onAction(row) }
            itemView.setOnClickListener { onAction(row) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VersionRow>() {
            override fun areItemsTheSame(a: VersionRow, b: VersionRow) =
                a.summary.id == b.summary.id

            override fun areContentsTheSame(a: VersionRow, b: VersionRow) = a == b
        }
    }
}
