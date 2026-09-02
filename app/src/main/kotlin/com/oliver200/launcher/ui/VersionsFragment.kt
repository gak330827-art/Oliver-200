/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/VersionsFragment.kt
 *  Назначение : экран «Выбор версии» — список, фильтры, установка.
 *  Безопасность: устанавливается только то, что прошло проверку в ядре.
 *               Любой файл с несовпавшим SHA-1 обрывает установку, а не
 *               «пропускается для скорости»: половина проверенных файлов
 *               и один подменённый jar — это уже исполнение чужого кода.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.oliver200.launcher.OliverApp
import com.oliver200.launcher.R
import com.oliver200.launcher.core.mojang.ReleaseChannel
import com.oliver200.launcher.core.mojang.VersionManifest
import com.oliver200.launcher.core.mojang.VersionSummary
import com.oliver200.launcher.data.GamePaths
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VersionsFragment : Fragment(R.layout.fragment_versions) {

    private lateinit var adapter: VersionAdapter
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout

    private var manifest: VersionManifest? = null
    private var query: String = ""
    private var installJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = OliverApp.from(requireContext())

        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        swipe = view.findViewById(R.id.swipe)

        adapter = VersionAdapter { row -> onRowAction(row) }
        view.findViewById<RecyclerView>(R.id.list).also {
            it.layoutManager = LinearLayoutManager(requireContext())
            it.adapter = adapter
            it.setHasFixedSize(true)
        }

        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_input)
            .addTextChangedListener { text ->
                query = text?.toString().orEmpty()
                render()
            }

        val chips = mapOf(
            view.findViewById<Chip>(R.id.chip_release) to setOf(ReleaseChannel.RELEASE),
            view.findViewById<Chip>(R.id.chip_snapshot) to setOf(ReleaseChannel.SNAPSHOT),
            view.findViewById<Chip>(R.id.chip_old) to
                setOf(ReleaseChannel.OLD_BETA, ReleaseChannel.OLD_ALPHA, ReleaseChannel.OTHER),
        )
        val active = app.settings.channels
        for ((chip, group) in chips) {
            chip.isChecked = group.any { it in active }
            chip.setOnCheckedChangeListener { _, _ ->
                val selected = chips.entries
                    .filter { it.key.isChecked }
                    .flatMap { it.value }
                    .toSet()
                app.settings.channels = selected
                render()
            }
        }

        swipe.setOnRefreshListener { load(force = true) }

        if (manifest == null) load(force = false) else render()
    }

    override fun onResume() {
        super.onResume()
        if (manifest != null) render() // список установленного мог измениться
    }

    private fun load(force: Boolean) {
        val app = OliverApp.from(requireContext())
        status.setText(R.string.versions_loading)
        swipe.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                manifest = app.versionRepository.loadManifest(force)
                render()
            } catch (e: Exception) {
                val cached = app.versionRepository.loadCachedManifest()
                if (cached != null) {
                    manifest = cached
                    render()
                    snack(getString(R.string.versions_error, e.message ?: ""))
                } else {
                    status.text = getString(R.string.versions_error, e.message ?: "")
                }
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun render() {
        val app = OliverApp.from(requireContext())
        val data = manifest
        if (data == null) {
            status.setText(R.string.versions_empty)
            adapter.submitList(emptyList())
            return
        }
        val installed = app.versionRepository.installedIds()
        val selected = app.settings.selectedVersion

        val rows = data.filter(app.settings.channels, query)
            .sortedByDescending { it.releaseTime }
            .map { VersionRow(it, it.id in installed, it.id == selected) }

        adapter.submitList(rows)
        status.text = buildString {
            append(rows.size).append(" версий · ")
            append(getString(R.string.storage_free, GamePaths.humanSize(app.paths.freeSpaceBytes())))
            if (data.rejected.isNotEmpty()) {
                append(" · ").append(getString(R.string.versions_rejected, data.rejected.size))
            }
        }
    }

    private fun onRowAction(row: VersionRow) {
        if (installJob?.isActive == true) return
        val app = OliverApp.from(requireContext())
        if (row.installed) {
            app.settings.selectedVersion = row.summary.id
            snack(getString(R.string.versions_selected, row.summary.id))
            render()
            return
        }
        install(row.summary)
    }

    private fun install(summary: VersionSummary) {
        val app = OliverApp.from(requireContext())
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.setText(R.string.install_preparing)

        installJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = app.versionRepository.loadDetail(summary)
                val plan = app.versionRepository.buildPlan(detail, app.launchEnvironment)

                progress.isIndeterminate = false
                progress.max = plan.count.coerceAtLeast(1)

                val report = app.versionRepository.install(plan) { done, total, item ->
                    progress.progress = done
                    status.text = getString(R.string.install_progress, done + 1, total, item.label)
                }

                if (!report.success) {
                    val first = report.failures.first()
                    status.text = getString(
                        R.string.install_failed,
                        "${first.item.label}: ${first.reason}",
                    )
                    return@launch
                }

                app.versionRepository.extractNatives(detail, app.launchEnvironment)
                app.settings.selectedVersion = summary.id
                status.text = getString(R.string.install_done, summary.id)
                snack(getString(R.string.install_done, summary.id))
                render()
            } catch (e: Exception) {
                status.text = getString(R.string.install_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun snack(text: String) {
        view?.let { Snackbar.make(it, text, Snackbar.LENGTH_LONG).show() }
    }
}
