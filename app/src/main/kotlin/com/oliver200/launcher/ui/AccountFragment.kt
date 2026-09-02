/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/AccountFragment.kt
 *  Назначение : два способа представиться игре — аккаунт Microsoft и
 *               офлайн-профиль по нику — плюс передача команды запуска
 *               внешней среде выполнения Java.
 *
 *  Про офлайн-профиль. Офлайн — штатный режим самой игры: одиночный мир,
 *  локальная сеть, сервер с online-mode=false, а также случай, когда
 *  серверы Mojang недоступны. UUID вычисляется из ника тем же алгоритмом,
 *  что применяет сервер, поэтому игрок не теряет свой мир и инвентарь.
 *
 *  Одно ограничение: САМ ЗАПУСК в офлайне открывается после первого
 *  успешного входа через Microsoft — так лаунчер один раз убеждается, что
 *  игра у вас куплена. Это тот же порядок, что принят в Prism Launcher и
 *  других открытых лаунчерах: офлайн для владельца, у которого пропала
 *  сеть, а не способ играть без покупки. Просмотр скинов, управление
 *  версиями и паками работают без всяких условий.
 *
 *  Безопасность:
 *      · окно с кодом устройства помечается FLAG_SECURE;
 *      · токен доступа передаётся стороннему рантайму только после явного
 *        подтверждения с показом имени пакета-получателя;
 *      · в офлайне передавать нечего: уходит --accessToken 0.
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
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.oliver200.launcher.BuildConfig
import com.oliver200.launcher.OliverApp
import com.oliver200.launcher.R
import com.oliver200.launcher.auth.MicrosoftAuth
import com.oliver200.launcher.core.auth.OfflineProfile
import com.oliver200.launcher.core.mojang.LaunchContext
import com.oliver200.launcher.core.mojang.LaunchPlanBuilder
import com.oliver200.launcher.core.skin.MinecraftUuid
import com.oliver200.launcher.data.ProfileMode
import com.oliver200.launcher.mc.RuntimeBridge
import com.oliver200.launcher.mc.RuntimeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class AccountFragment : Fragment(R.layout.fragment_account) {

    private lateinit var accountState: TextView
    private lateinit var launchState: TextView
    private lateinit var codeCard: MaterialCardView
    private lateinit var codeText: TextView
    private lateinit var signInButton: MaterialButton
    private lateinit var signOutButton: MaterialButton

    private lateinit var offlineNickLayout: TextInputLayout
    private lateinit var offlineNickInput: TextInputEditText
    private lateinit var offlineUuid: TextView
    private lateinit var offlineGate: TextView
    private lateinit var profileToggle: MaterialButtonToggleGroup

    private var authJob: Job? = null
    private var pendingUserCode: String? = null

    /** Гасит реакцию на программное переключение тумблера. */
    private var suppressToggleCallback = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = OliverApp.from(requireContext())

        accountState = view.findViewById(R.id.account_state)
        launchState = view.findViewById(R.id.launch_state)
        codeCard = view.findViewById(R.id.code_card)
        codeText = view.findViewById(R.id.code_text)
        signInButton = view.findViewById(R.id.sign_in_button)
        signOutButton = view.findViewById(R.id.sign_out_button)

        offlineNickLayout = view.findViewById(R.id.offline_nick_layout)
        offlineNickInput = view.findViewById(R.id.offline_nick_input)
        offlineUuid = view.findViewById(R.id.offline_uuid)
        offlineGate = view.findViewById(R.id.offline_gate)
        profileToggle = view.findViewById(R.id.profile_toggle)

        signInButton.setOnClickListener { startSignIn() }
        signOutButton.setOnClickListener { signOut() }
        view.findViewById<MaterialButton>(R.id.copy_button).setOnClickListener { copyCode() }
        view.findViewById<MaterialButton>(R.id.launch_button).setOnClickListener { launch() }

        /* ── Офлайн-профиль ── */
        app.settings.offlineNick?.let { offlineNickInput.setText(it) }
        renderOfflineUuid(offlineNickInput.text?.toString())

        offlineNickInput.addTextChangedListener { text ->
            // UUID пересчитывается прямо при вводе: видно, что он меняется
            // от каждой буквы, и почему ник потом нельзя менять беспечно.
            renderOfflineUuid(text?.toString())
        }
        view.findViewById<MaterialButton>(R.id.offline_save_button).setOnClickListener {
            saveOfflineNick()
        }

        /* ── Переключатель активного профиля ── */
        profileToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressToggleCallback) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.profile_offline) {
                ProfileMode.OFFLINE
            } else {
                ProfileMode.MICROSOFT
            }
            app.settings.profileMode = mode
            snack(
                getString(
                    if (mode == ProfileMode.OFFLINE) {
                        R.string.profile_switched_offline
                    } else {
                        R.string.profile_switched_online
                    },
                ),
            )
            renderLaunchState()
        }

        renderAccount()
        renderProfileToggle()
        renderLaunchState()
    }

    override fun onResume() {
        super.onResume()
        if (pendingUserCode != null) setSecure(true)
        renderProfileToggle()
        renderLaunchState()
        renderGate()
    }

    override fun onPause() {
        super.onPause()
        setSecure(false)
    }

    private fun setSecure(secure: Boolean) {
        val window = activity?.window ?: return
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /* ─────────────────────────── Офлайн-профиль ─────────────────────────── */

    private fun renderOfflineUuid(raw: String?) {
        val profile = OfflineProfile.of(raw)
        offlineUuid.text = if (profile == null) {
            getString(R.string.offline_not_set)
        } else {
            getString(R.string.offline_uuid, profile.uuid)
        }
        offlineNickLayout.error = when {
            raw.isNullOrBlank() -> null
            profile == null -> getString(R.string.offline_invalid)
            else -> null
        }
    }

    private fun saveOfflineNick() {
        val app = OliverApp.from(requireContext())
        val raw = offlineNickInput.text?.toString()
        val profile = OfflineProfile.of(raw)
        if (profile == null) {
            offlineNickLayout.error = getString(R.string.offline_invalid)
            return
        }
        offlineNickLayout.error = null
        app.settings.offlineNick = profile.name
        snack(getString(R.string.offline_saved, profile.name))
        renderLaunchState()
    }

    /** Показывает, открыт ли офлайн-запуск и почему. */
    private fun renderGate() {
        val store = OliverApp.from(requireContext()).accountStore()
        val proof = store?.licenceVerification()
        offlineGate.text = if (proof == null) {
            getString(R.string.offline_gate_blocked)
        } else {
            val (at, name) = proof
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at * 1000))
            getString(R.string.offline_gate_ok, "$name ($date)")
        }
    }

    private fun renderProfileToggle() {
        val app = OliverApp.from(requireContext())
        val target = if (app.settings.profileMode == ProfileMode.OFFLINE) {
            R.id.profile_offline
        } else {
            R.id.profile_microsoft
        }
        if (profileToggle.checkedButtonId != target) {
            suppressToggleCallback = true
            profileToggle.check(target)
            suppressToggleCallback = false
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
            renderGate()
            return
        }

        val account = store.load()
        if (account == null) {
            accountState.text = buildString {
                append("Вход не выполнен")
                append("\nХранилище: ").append(store.storageDescription)
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
        renderGate()
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

                // Лицензия проверена запросом entitlements внутри
                // toMinecraftSession — только теперь открываем офлайн-запуск.
                store.markLicenceVerified(session.name)

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
        // clear() снимает токены, но сохраняет отметку о лицензии:
        // выход ради смены аккаунта не должен отбирать офлайн-игру.
        OliverApp.from(requireContext()).accountStore()?.clear()
        snack(getString(R.string.account_signed_out))
        renderAccount()
        renderLaunchState()
    }

    private fun copyCode() {
        val code = pendingUserCode ?: return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Oliver-200", code))
        snack(getString(R.string.account_code_copied))
    }

    /* ─────────────────────────── Запуск ─────────────────────────── */

    private fun renderLaunchState() {
        val app = OliverApp.from(requireContext())
        val version = app.settings.selectedVersion
        val runtimes = RuntimeBridge.findRuntimes(requireContext().packageManager)
        val mode = app.settings.profileMode

        launchState.text = buildString {
            append("Профиль: ")
            when (mode) {
                ProfileMode.OFFLINE -> {
                    val offline = app.settings.offlineProfile
                    append("офлайн, ").append(offline?.name ?: "ник не задан")
                }
                ProfileMode.MICROSOFT -> {
                    val account = app.accountStore()?.load()
                    append("Microsoft, ").append(account?.name ?: "вход не выполнен")
                }
            }
            append("\nВерсия: ").append(version ?: "не выбрана")
            append("\nСреда выполнения: ")
            append(if (runtimes.isEmpty()) "не найдена" else runtimes.joinToString { it.label })
            append("\nШейдеры: ").append(app.shaderRepository.currentSelection() ?: "выключены")
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
        val runtime = runtimes.first()

        when (app.settings.profileMode) {
            ProfileMode.OFFLINE -> launchOffline(runtime, versionId)
            ProfileMode.MICROSOFT -> launchOnline(runtime, versionId)
        }
    }

    private fun launchOffline(runtime: RuntimeInfo, versionId: String) {
        val app = OliverApp.from(requireContext())
        val offline = app.settings.offlineProfile
        if (offline == null) {
            snack(getString(R.string.launch_offline_no_nick))
            offlineNickLayout.error = getString(R.string.offline_invalid)
            return
        }
        val store = app.accountStore()
        if (store == null || !store.licenceVerified) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.offline_title)
                .setMessage(R.string.offline_gate_blocked)
                .setPositiveButton(R.string.common_close, null)
                .show()
            return
        }
        handOff(
            runtime = runtime,
            versionId = versionId,
            playerName = offline.name,
            playerUuid = offline.uuid,
            accessToken = OfflineProfile.ACCESS_TOKEN,
            userType = OfflineProfile.USER_TYPE,
            xuid = "",
            includeToken = false, // передавать нечего: токен и есть "0"
        )
    }

    private fun launchOnline(runtime: RuntimeInfo, versionId: String) {
        val app = OliverApp.from(requireContext())
        val account = app.accountStore()?.load()
        if (account == null) {
            snack(getString(R.string.launch_no_account))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.launch_title)
            .setMessage(getString(R.string.launch_token_warning, runtime.packageName))
            .setNegativeButton(R.string.launch_token_deny) { _, _ ->
                sendAccount(runtime, versionId, account.name, account.uuid, account, false)
            }
            .setPositiveButton(R.string.launch_token_allow) { _, _ ->
                sendAccount(runtime, versionId, account.name, account.uuid, account, true)
            }
            .show()
    }

    private fun sendAccount(
        runtime: RuntimeInfo,
        versionId: String,
        name: String,
        uuid: String,
        account: com.oliver200.launcher.auth.Account,
        includeToken: Boolean,
    ) = handOff(
        runtime = runtime,
        versionId = versionId,
        playerName = name,
        playerUuid = uuid,
        accessToken = account.accessToken,
        userType = "msa",
        xuid = account.xuid,
        includeToken = includeToken,
    )

    private fun handOff(
        runtime: RuntimeInfo,
        versionId: String,
        playerName: String,
        playerUuid: String,
        accessToken: String,
        userType: String,
        xuid: String,
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
                    playerName = playerName,
                    playerUuid = MinecraftUuid.dashed(playerUuid) ?: playerUuid,
                    accessToken = accessToken,
                    userType = userType,
                    xuid = xuid,
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
                    accessToken = accessToken,
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
