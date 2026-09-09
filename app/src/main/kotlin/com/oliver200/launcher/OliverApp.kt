/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : OliverApp.kt
 *  Назначение : точка сборки зависимостей и общие для приложения объекты.
 *  Безопасность: в debug-сборке включается StrictMode — он ловит утечки
 *               закрытых ресурсов и незашифрованный трафик до того, как
 *               это увидит пользователь. В release StrictMode выключен:
 *               его отчёты сами по себе — источник информации об
 *               устройстве приложения.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import com.oliver200.launcher.auth.AccountStore
import com.oliver200.launcher.core.mojang.LaunchEnvironment
import com.oliver200.launcher.data.GamePaths
import com.oliver200.launcher.data.Settings
import com.oliver200.launcher.data.ShaderRepository
import com.oliver200.launcher.data.SkinRepository
import com.oliver200.launcher.data.VersionRepository
import com.oliver200.launcher.security.Vault
import com.oliver200.launcher.security.VaultException
import com.oliver200.launcher.security.VaultFactory

class OliverApp : Application() {

    val paths: GamePaths by lazy { GamePaths(this) }
    val settings: Settings by lazy { Settings(this) }
    val versionRepository: VersionRepository by lazy { VersionRepository(paths) }
    val skinRepository: SkinRepository by lazy { SkinRepository() }
    val shaderRepository: ShaderRepository by lazy { ShaderRepository(this, paths) }

    /** Платформа для вычисления правил version.json. */
    val launchEnvironment: LaunchEnvironment by lazy {
        val arch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val is64 = arch.contains("arm64") || arch.contains("x86_64")
        if (is64) {
            LaunchEnvironment.android64(Build.VERSION.RELEASE ?: "0")
        } else {
            LaunchEnvironment.android32(Build.VERSION.RELEASE ?: "0")
        }
    }

    /**
     * Заставка уже играла в этом процессе.
     *
     * Живёт в объекте приложения, а не в настройках: ролик нужен как
     * представление при холодном старте, а не как «подождите» на каждый
     * возврат к лаунчеру. Процесс убили — представление будет снова.
     */
    @Volatile
    var introPlayed: Boolean = false

    /** null, если Keystore недоступен: тогда вход в аккаунт невозможен. */
    var vaultError: String? = null
        private set

    private val vaultOrNull: Vault? by lazy {
        try {
            VaultFactory.get(this, BuildConfig.DEBUG)
        } catch (e: VaultException) {
            vaultError = e.message
            null
        }
    }

    fun accountStore(): AccountStore? = vaultOrNull?.let { AccountStore(it) }

    override fun onCreate() {
        super.onCreate()
        paths.ensureDirs()
        if (BuildConfig.DEBUG) enableStrictMode()
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vm.detectCleartextNetwork()
        }
        StrictMode.setVmPolicy(vm.build())
    }

    companion object {
        fun from(context: Context): OliverApp = context.applicationContext as OliverApp
    }
}
