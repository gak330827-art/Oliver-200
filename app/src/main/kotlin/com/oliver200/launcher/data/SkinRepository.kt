/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : data/SkinRepository.kt
 *  Назначение : поиск игрока по нику и загрузка его скина.
 *  Безопасность:
 *      · ник валидируется ДО построения URL (см. MinecraftName);
 *      · адрес текстуры берётся из ответа сервера, поэтому проверяется
 *        белым списком — подменённый sessionserver не заставит нас
 *        сходить на чужой хост;
 *      · PNG сначала читается ТОЛЬКО заголовком (inJustDecodeBounds).
 *        Без этого «скин» размером 30000×30000 выделит гигабайты и убьёт
 *        приложение — классическая decompression bomb.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.oliver200.launcher.core.net.UrlGuard
import com.oliver200.launcher.core.skin.MinecraftName
import com.oliver200.launcher.core.skin.MinecraftUuid
import com.oliver200.launcher.core.skin.SkinAtlas
import com.oliver200.launcher.core.skin.SkinEndpoints
import com.oliver200.launcher.core.skin.SkinModel
import com.oliver200.launcher.core.skin.SkinProfileParser
import com.oliver200.launcher.net.SecureHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed class SkinLookup {
    data class Found(
        val name: String,
        val uuid: String,
        val model: SkinModel,
        val skinUrl: String?,
        val skin: Bitmap?,
        val cape: Bitmap?,
    ) : SkinLookup()

    data class NotFound(val nick: String) : SkinLookup()
    data class Invalid(val nick: String) : SkinLookup()
    data class Error(val reason: String) : SkinLookup()
}

class SkinRepository {

    companion object {
        private const val MAX_TEXTURE_BYTES = 2L * 1024 * 1024
        private const val MAX_TEXTURE_SIDE = SkinAtlas.BASE_WIDTH * SkinAtlas.MAX_SCALE
        private const val MAX_API_BYTES = 128L * 1024
    }

    suspend fun lookup(rawNick: String): SkinLookup = withContext(Dispatchers.IO) {
        val nick = MinecraftName.normalize(rawNick)
            ?: return@withContext SkinLookup.Invalid(rawNick)
        try {
            val uuid = resolveUuid(nick) ?: return@withContext SkinLookup.NotFound(nick)

            val profileResp = SecureHttp.get(
                SkinEndpoints.profile(uuid),
                UrlGuard.PROFILE_API,
                maxBody = MAX_API_BYTES,
            )
            if (profileResp.code == 204 || profileResp.code == 404) {
                return@withContext SkinLookup.NotFound(nick)
            }
            if (profileResp.code !in 200..299) {
                return@withContext SkinLookup.Error("HTTP ${profileResp.code}")
            }
            val textures = SkinProfileParser.parseProfile(profileResp.text())
                ?: return@withContext SkinLookup.Error("Не удалось разобрать профиль")

            val skinTexture = textures.skin
            val bitmap = skinTexture?.let { loadTexture(it.url) }
            val cape = textures.cape?.let { loadTexture(it.url) }

            SkinLookup.Found(
                name = textures.profileName ?: nick,
                uuid = MinecraftUuid.dashed(uuid) ?: uuid,
                model = skinTexture?.model ?: SkinModel.CLASSIC,
                skinUrl = skinTexture?.url,
                skin = bitmap,
                cape = cape,
            )
        } catch (e: IOException) {
            SkinLookup.Error(e.message ?: "Сеть недоступна")
        } catch (e: SecurityException) {
            SkinLookup.Error(e.message ?: "Адрес заблокирован политикой безопасности")
        }
    }

    private suspend fun resolveUuid(nick: String): String? {
        val endpoints = listOf(SkinEndpoints.nameToUuid(nick), SkinEndpoints.nameToUuidFallback(nick))
        for (url in endpoints) {
            val resp = SecureHttp.get(url, UrlGuard.PROFILE_API, maxBody = MAX_API_BYTES)
            when (resp.code) {
                in 200..299 -> SkinProfileParser.parseNameLookup(resp.text())?.let { return it.uuid }
                204, 404 -> return null // ника не существует — второй эндпоинт не поможет
                else -> Unit // 429/5xx — пробуем следующий
            }
        }
        return null
    }

    /**
     * Загружает PNG скина. Два барьера: лимит на размер файла и проверка
     * заявленных размеров ДО выделения памяти под пиксели.
     */
    private suspend fun loadTexture(url: String): Bitmap? {
        val safeUrl = UrlGuard.upgradeTexturesUrl(url) ?: return null
        if (!UrlGuard.isAllowed(safeUrl, UrlGuard.TEXTURES)) return null

        val resp = SecureHttp.get(safeUrl, UrlGuard.TEXTURES, maxBody = MAX_TEXTURE_BYTES)
        if (resp.code !in 200..299) return null
        val bytes = resp.body

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_TEXTURE_SIDE || bounds.outHeight !in 1..MAX_TEXTURE_SIDE) {
            return null
        }
        if (SkinAtlas.layoutFor(bounds.outWidth, bounds.outHeight) == null) {
            return null // не скин: пропорции не совпадают ни с 64×64, ни с 64×32
        }

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false // пиксель-арт нельзя масштабировать под плотность экрана
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
