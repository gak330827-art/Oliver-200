/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/ShadersFragment.kt
 *  Назначение : экран паков шейдеров — импорт, включение, удаление.
 *  Безопасность: файл выбирается системным пикером (SAF), поэтому
 *               приложению не нужно ни одного разрешения на доступ
 *               к хранилищу. Отклонённый пак показывается с причиной —
 *               пользователь должен понимать, что именно не так, а не
 *               видеть молчаливый отказ.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.oliver200.launcher.OliverApp
import com.oliver200.launcher.R
import kotlinx.coroutines.launch

class ShadersFragment : Fragment(R.layout.fragment_shaders) {

    private lateinit var adapter: ShaderAdapter
    private lateinit var activeLabel: TextView
    private lateinit var emptyLabel: TextView
    private lateinit var progress: LinearProgressIndicator

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPack(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        activeLabel = view.findViewById(R.id.active_pack)
        emptyLabel = view.findViewById(R.id.empty)
        progress = view.findViewById(R.id.progress)

        adapter = ShaderAdapter(
            onSelect = { row -> select(row.pack.id) },
            onDelete = { row -> confirmDelete(row) },
        )
        view.findViewById<RecyclerView>(R.id.list).also {
            it.layoutManager = LinearLayoutManager(requireContext())
            it.adapter = adapter
        }

        view.findViewById<MaterialButton>(R.id.add_button).setOnClickListener {
            // Тип задаём широко: некоторые провайдеры отдают zip как
            // application/octet-stream, и по строгому типу файл не выберется.
            picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        view.findViewById<MaterialButton>(R.id.off_button).setOnClickListener { select(null) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val app = OliverApp.from(requireContext())
        progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val packs = app.shaderRepository.list()
            val active = app.shaderRepository.currentSelection()
            adapter.submitList(packs.map { ShaderRow(it, it.id == active) })
            emptyLabel.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
            activeLabel.text = if (active == null) {
                getString(R.string.shaders_none_active)
            } else {
                getString(R.string.shaders_active, active)
            }
            progress.visibility = View.GONE
        }
    }

    private fun importPack(uri: Uri) {
        val app = OliverApp.from(requireContext())
        progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val installed = app.shaderRepository.import(uri)
                snack(getString(R.string.shaders_installed, installed.info.displayName))
                if (installed.info.warnings.isNotEmpty()) showWarnings(installed.info.warnings)
                refresh()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                snack(getString(R.string.shaders_rejected, e.message ?: "неизвестная ошибка"))
            }
        }
    }

    private fun select(packId: String?) {
        val app = OliverApp.from(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.shaderRepository.select(packId)
                refresh()
            } catch (e: Exception) {
                snack(e.message ?: "Не удалось выбрать пак")
            }
        }
    }

    private fun confirmDelete(row: ShaderRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(row.pack.info.displayName)
            .setMessage(row.pack.id)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.shaders_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    OliverApp.from(requireContext()).shaderRepository.remove(row.pack.id)
                    snack(getString(R.string.shaders_removed))
                    refresh()
                }
            }
            .show()
    }

    private fun showWarnings(warnings: List<String>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.shaders_warning_title)
            .setMessage(warnings.joinToString("\n• ", prefix = "• "))
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    private fun snack(text: String) {
        view?.let { Snackbar.make(it, text, Snackbar.LENGTH_LONG).show() }
    }
}
