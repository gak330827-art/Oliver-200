/*
 * Oliver-200 · адаптер списка паков шейдеров. Подпись: OLIVER-200 · см. SIGNATURES.txt
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
import com.oliver200.launcher.core.shader.InstalledShaderPack
import com.oliver200.launcher.data.GamePaths

data class ShaderRow(val pack: InstalledShaderPack, val active: Boolean)

class ShaderAdapter(
    private val onSelect: (ShaderRow) -> Unit,
    private val onDelete: (ShaderRow) -> Unit,
) : ListAdapter<ShaderRow, ShaderAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shader, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.pack_name)
        private val meta: TextView = view.findViewById(R.id.pack_meta)
        private val requirement: TextView = view.findViewById(R.id.pack_requirement)
        private val select: MaterialButton = view.findViewById(R.id.select_button)
        private val delete: MaterialButton = view.findViewById(R.id.delete_button)

        fun bind(row: ShaderRow) {
            val ctx = itemView.context
            val info = row.pack.info
            name.text = info.displayName
            meta.text = ctx.getString(
                R.string.shader_subtitle,
                info.kind.ruLabel,
                GamePaths.humanSize(row.pack.sizeBytes),
                info.fileCount,
            )
            requirement.text = if (info.warnings.isEmpty()) {
                info.kind.requirement
            } else {
                info.kind.requirement + " · " + info.warnings.first()
            }
            select.isEnabled = !row.active
            select.setText(if (row.active) R.string.versions_installed else R.string.shaders_select)
            select.setOnClickListener { onSelect(row) }
            delete.setOnClickListener { onDelete(row) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ShaderRow>() {
            override fun areItemsTheSame(a: ShaderRow, b: ShaderRow) = a.pack.id == b.pack.id
            override fun areContentsTheSame(a: ShaderRow, b: ShaderRow) =
                a.active == b.active && a.pack.sha256 == b.pack.sha256
        }
    }
}
