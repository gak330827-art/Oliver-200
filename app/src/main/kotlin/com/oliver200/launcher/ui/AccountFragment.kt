/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/AccountFragment.kt
 *  Назначение : вход Microsoft (Device Code) и передача команды запуска
 *               внешней среде выполнения Java.
 *  Безопасность:
 *      · окно с кодом устройства помечается FLAG_SECURE — код не попадёт
 *        ни в скриншот, ни в запись экрана, ни в превью в списке задач;
 *      · токен доступа передаётся стороннему рантайму ТОЛЬКО после
 *        явного подтверждения, где показано имя пакета-получателя;
 *      · если хранилище ключей недоступно, вход блокируется целиком:
 *        отката на открытое хранение токена нет.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.oliver200.launcher.BuildConfig
import com.oliver200.launcher.OliverApp
import com.oliver200.launcher.R
import com.oliver200.launcher.auth.Account
import com.oliver200.launcher.auth.MicrosoftAuth
import com.oliver200.launcher.core.mojang.LaunchContext
import com.oliver200.launcher.core.mojang.LaunchPlanBuilder
import com.oliver200.launcher.core.skin.MinecraftUuid
import com.oliver200.launcher.mc.RuntimeBridge
import com.oliver200.launcher.mc.RuntimeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AccountFragment : Fragment(R.layout.fragment_account) {

    private lateinit var accountState: TextView
    private lateinit var launchState: TextView
    private lateinit var codeCard: MaterialCardView
    private lateinit var codeText: TextView
    private lateinit var signInButton: MaterialButton
    private lateinit var signOutButton: MaterialButton

    private var authJob: Job? = null
    private var pendingUserCode: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        accountState = view.findViewById(R.id.account_state)
        launchState = view.findViewById(R.id.launch_state)
        codeCard = view.findViewById(R.id.code_card)
        codeText = view.findViewById(R.id.code_text)
        signInButton = view.findViewById(R.id.sign_in_button)
        signOutButton = view.findViewById(R.id.sign_out_button)

        signInButton.setOnClickListener { startSignIn() }
        signOutButton.setOnClickListener { signOut() }
        view.findViewById<MaterialButton>(R.id.copy_button).setOnClickListener { copyCode() }
        view.findViewById<MaterialButton>(R.id.launch_button).setOnClickListener { launch() }

        renderAccount()
        renderLaunchState()
    }

    override fun onResume() {
        super.onResume()
        // Код устройства не должен попадать в скриншоты и превью задач.
        if (pendingUserCode != null) setSecure(true)
        renderLaunchState()
    }

    override fun onPause() {
        super.onPause()
        setSecure(false)
    }

    private fun setSecure(secure: Boolean) {
        val window = activity?.window ?: return
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /* ─────────────────────────── Аккаунт ─────────────────────────── */

    private fun renderAccount() {
        val app = OliverApp.from(requireContext())
        val store = app.accountStore()

        if (store == null) {
            accountState.text = app.vaultError
                ?: "Хранилище ключей недоступно — вход невозможен"
            signInButton.isEnabled = false
            signOutButton.visibility = View.GONE
            return
        }

        val account = store.load()
        if (account == null) {
            accountState.text = buildString {
                append(getString(R.string.account_offline))
                append("\n")
                append("Хранилище: ").append(store.storageDescription)
                if (!store.storageEncrypted) append(" ⚠")
            }
            signInButton.isEnabled = BuildConfig.MS_CLIENT_ID.isNotBlank()
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
            if (BuildConfig.MS_CLIENT_ID.isBlank()) {
                accountState.append("\n\n" + getString(R.string.account_not_configured))
            }
        } else {
            accountState.text = buildString {
                append(getString(R.string.account_signed_in, account.name))
                append("\n").append(account.uuid)
                append("\nХранилище: ").append(store.storageDescription)
            }
            signInButton.visibility = View.GONE
            signOutButton.visibility = View.VISIBLE
        }
    }

    private fun startSignIn() {
        if (authJob?.isActive == true) return
        val app = OliverApp.from(requireContext())
        val store = app.accountStore() ?: return
        val clientId = BuildConfig.MS_CLIENT_ID
        if (clientId.isBlank()) {
            snack(getString(R.string.account_not_configured))
            return
        }

        signInButton.isEnabled = false
        accountState.setText(R.string.account_waiting)

        authJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prompt = MicrosoftAuth.startDeviceCode(clientId)
                pendingUserCode = prompt.userCode
                setSecure(true)
                codeCard.visibility = View.VISIBLE
                codeText.text = getString(
                    R.string.account_device_code,
                    prompt.verificationUri,
                    prompt.userCode,
                )

                val tokens = MicrosoftAuth.awaitToken(clientId, prompt)
                val session = MicrosoftAuth.toMinecraftSession(tokens.accessToken)
                store.save(session, tokens.refreshToken)

                snack(getString(R.string.account_signed_in, session.name))
            } catch (e: Exception) {
                accountState.text = getString(R.string.account_error, e.message ?: "")
            } finally {
                pendingUserCode = null
                setSecure(false)
                codeCard.visibility = View.GONE
                codeText.text = ""
                signInButton.isEnabled = true
                renderAccount()
                renderLaunchState()
            }
        }
    }

    private fun signOut() {
        authJob?.cancel()
        OliverApp.from(requireContext()).accountStore()?.clear()
        snack(getString(R.string.account_signed_out))
        renderAccount()
        renderLaunchState()
    }

    private fun copyCode() {
        val code = pendingUserCode ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Oliver-200", code))
        snack(getString(R.string.account_code_copied))
    }

    /* ─────────────────────────── Запуск ─────────────────────────── */

    private fun renderLaunchState() {
        val app = OliverApp.from(requireContext())
        val version = app.settings.selectedVersion
        val runtimes = RuntimeBridge.findRuntimes(requireContext().packageManager)

        launchState.text = buildString {
            append("Версия: ").append(version ?: "не выбрана").append("\n")
            append("Среда выполнения: ")
            append(if (runtimes.isEmpty()) "не найдена" else runtimes.joinToString { it.label })
            val active = app.shaderRepository.currentSelection()
            append("\nШейдеры: ").append(active ?: "выключены")
        }
    }

    private fun launch() {
        val app = OliverApp.from(requireContext())
        val versionId = app.settings.selectedVersion
        if (versionId == null) {
            snack(getString(R.string.launch_no_version))
            return
        }
        val runtimes = RuntimeBridge.findRuntimes(requireContext().packageManager)
        if (runtimes.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.launch_title)
                .setMessage(R.string.launch_no_runtime)
                .setPositiveButton(R.string.common_close, null)
                .show()
            return
        }
        val account = app.accountStore()?.load()
        if (account == null) {
            snack(getString(R.string.launch_no_account))
            return
        }
        confirmTokenHandoff(runtimes.first(), versionId, account)
    }

    /**
     * Отдельное подтверждение перед передачей токена. Это не формальность:
     * получатель Intent — стороннее приложение, а токен открывает игровой
     * аккаунт целиком.
     */
    private fun confirmTokenHandoff(runtime: RuntimeInfo, versionId: String, account: Account) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.launch_title)
            .setMessage(getString(R.string.launch_token_warning, runtime.packageName))
            .setNegativeButton(R.string.launch_token_deny) { _, _ ->
                handOff(runtime, versionId, account, includeToken = false)
            }
            .setPositiveButton(R.string.launch_token_allow) { _, _ ->
                handOff(runtime, versionId, account, includeToken = true)
            }
            .show()
    }

    private fun handOff(
        runtime: RuntimeInfo,
        versionId: String,
        account: Account,
        includeToken: Boolean,
    ) {
        val app = OliverApp.from(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val manifest = app.versionRepository.loadCachedManifest()
                val summary = manifest?.byId(versionId)
                    ?: throw IllegalStateException("Нет описания версии $versionId")
                val detail = app.versionRepository.loadDetail(summary)

                val gameDir = app.paths.root.absolutePath
                val nativesDir = app.paths.nativesDir(versionId)?.absolutePath
                    ?: throw IllegalStateException("Нет каталога natives")
                val clientJar = app.paths.clientJar(versionId)?.absolutePath
                    ?: throw IllegalStateException("Клиент не установлен")

                val context = LaunchContext(
                    playerName = account.name,
                    playerUuid = MinecraftUuid.dashed(account.uuid) ?: account.uuid,
                    accessToken = account.accessToken,
                    xuid = account.xuid,
                    versionName = detail.id,
                    versionType = detail.type,
                    gameDir = gameDir,
                    assetsDir = app.paths.assetsDir.absolutePath,
                    assetsIndexName = detail.assetIndex?.id ?: detail.assetsId ?: "legacy",
                    nativesDir = nativesDir,
                    librariesDir = app.paths.librariesDir.absolutePath,
                )

                val plan = LaunchPlanBuilder.build(
                    detail = detail,
                    env = app.launchEnvironment,
                    context = context,
                    libraryPath = { lib ->
                        lib.artifact?.let { app.versionRepository.libraryPathFor(it.path) }
                    },
                    clientJarPath = clientJar,
                )

                val intent = RuntimeBridge.buildIntent(
                    runtime = runtime,
                    plan = plan,
                    gameDir = gameDir,
                    nativesDir = nativesDir,
                    javaMajorVersion = detail.javaMajorVersion,
                    accessToken = account.accessToken,
                    includeToken = includeToken,
                )
                startActivity(intent)
                snack(getString(R.string.launch_handoff))
            } catch (e: Exception) {
                snack(e.message ?: "Не удалось собрать команду запуска")
            }
        }
    }

    private fun snack(text: String) {
        view?.let { Snackbar.make(it, text, Snackbar.LENGTH_LONG).show() }
    }
}
