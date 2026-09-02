/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/SkinFragment.kt
 *  Назначение : экран «Скин по нику» — поиск игрока и предпросмотр скина.
 *  Безопасность: ник проверяется маской ещё до запроса. Ошибка «неверный
 *               ник» показывается локально и в сеть вообще не уходит —
 *               меньше поводов дёргать API и ноль шансов подставить
 *               в путь URL что-то постороннее.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.oliver200.launcher.OliverApp
import com.oliver200.launcher.R
import com.oliver200.launcher.core.skin.MinecraftName
import com.oliver200.launcher.data.SkinLookup
import com.oliver200.launcher.skin.SkinRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SkinFragment : Fragment(R.layout.fragment_skin) {

    private lateinit var nickLayout: TextInputLayout
    private lateinit var nickInput: TextInputEditText
    private lateinit var preview: ImageView
    private lateinit var nameLabel: TextView
    private lateinit var metaLabel: TextView
    private lateinit var applyButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private var lookupJob: Job? = null
    private var lastFound: SkinLookup.Found? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = OliverApp.from(requireContext())

        nickLayout = view.findViewById(R.id.nick_layout)
        nickInput = view.findViewById(R.id.nick_input)
        preview = view.findViewById(R.id.skin_preview)
        nameLabel = view.findViewById(R.id.skin_name)
        metaLabel = view.findViewById(R.id.skin_meta)
        applyButton = view.findViewById(R.id.apply_button)
        progress = view.findViewById(R.id.progress)

        app.settings.offlineNick?.let { nickInput.setText(it) }

        view.findViewById<MaterialButton>(R.id.find_button).setOnClickListener { search() }
        nickInput.setOnEditorActionListener { _, _, _ ->
            search()
            true
        }

        applyButton.setOnClickListener {
            val found = lastFound ?: return@setOnClickListener
            app.settings.offlineNick = found.name
            snack(getString(R.string.skin_applied))
        }
    }

    private fun search() {
        if (lookupJob?.isActive == true) return
        val raw = nickInput.text?.toString().orEmpty().trim()

        // Локальная проверка: до сети дело не доходит вовсе.
        if (!MinecraftName.isValid(raw)) {
            nickLayout.error = getString(R.string.skin_invalid_nick)
            return
        }
        nickLayout.error = null

        val app = OliverApp.from(requireContext())
        progress.visibility = View.VISIBLE
        applyButton.isEnabled = false

        lookupJob = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.skinRepository.lookup(raw)) {
                is SkinLookup.Found -> showFound(result)
                is SkinLookup.NotFound -> {
                    clear()
                    metaLabel.setText(R.string.skin_not_found)
                }
                is SkinLookup.Invalid -> {
                    clear()
                    nickLayout.error = getString(R.string.skin_invalid_nick)
                }
                is SkinLookup.Error -> {
                    clear()
                    metaLabel.text = getString(R.string.skin_error, result.reason)
                }
            }
            progress.visibility = View.GONE
        }
    }

    private fun showFound(found: SkinLookup.Found) {
        lastFound = found
        nameLabel.text = found.name

        val bitmap = found.skin
        if (bitmap == null) {
            preview.setImageDrawable(null)
            metaLabel.setText(R.string.skin_no_skin)
        } else {
            val figure = SkinRenderer.renderFront(bitmap, found.model, zoom = 10)
            preview.setImageBitmap(figure)
            metaLabel.text = buildString {
                append(getString(R.string.skin_model, found.model.ruLabel))
                append("\n").append(found.uuid)
                if (found.cape != null) append("\n").append("Есть плащ")
            }
        }
        applyButton.isEnabled = true
        snack(getString(R.string.skin_loaded, found.name))
    }

    private fun clear() {
        lastFound = null
        preview.setImageDrawable(null)
        nameLabel.text = ""
        applyButton.isEnabled = false
    }

    private fun snack(text: String) {
        view?.let { Snackbar.make(it, text, Snackbar.LENGTH_SHORT).show() }
    }
}
