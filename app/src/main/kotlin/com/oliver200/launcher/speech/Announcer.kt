/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : speech/Announcer.kt
 *  Назначение : голос диктора на заставке. Две короткие реплики: название
 *               сообщества и «Заботимся о вас».
 *
 *  ВАРИАНТ    : БЕЗ ШИФРОВАНИЯ (секретов не хранит и хранить не может).
 *
 *  Безопасность и приличия — почему тут столько ограничений:
 *      · Движок TTS — ЧУЖОЕ приложение, и часть движков синтезирует речь
 *        на своём сервере. Значит всё, что сюда передано, может уйти
 *        в сеть. Поэтому на входе [IntroCue] и [IntroBrand], а не String:
 *        передать сюда ник, токен или ответ сервера физически нельзя —
 *        нет такого метода.
 *      · Телефон в беззвучном режиме молчит. Лаунчер, который орёт
 *        на весь автобус, потому что «у нас красивая заставка», — это
 *        не фича.
 *      · Фокус звука берётся временный, с приглушением чужой музыки,
 *        и сразу отдаётся. Потеряли фокус — замолчали.
 *      · Ничего не логируется: реплики не секрет, но и лишних записей
 *        о том, что и когда произносилось, в logcat не нужно.
 *
 *  Живучесть: движок инициализируется асинхронно и на части устройств
 *  отсутствует вовсе. Реплика, пришедшая до готовности, ждёт в очереди
 *  из ОДНОГО элемента и выбрасывается, если опоздала больше чем
 *  на [LATE_LIMIT_MS] — лучше тишина, чем голос поверх уже другого кадра.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.oliver200.launcher.core.intro.IntroBrand
import com.oliver200.launcher.core.intro.IntroCue
import java.util.Locale

class Announcer(context: Context, enabled: Boolean) {

    private enum class State { STARTING, READY, FAILED, CLOSED }

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stop()
        }
    }

    private var tts: TextToSpeech? = null
    private var state: State = State.STARTING
    private var russianVoice = false
    private var spokenCount = 0
    private var focusRequest: AudioFocusRequest? = null

    private var pendingCue: IntroCue? = null
    private var pendingBrand: IntroBrand? = null
    private var pendingAtMs = 0L

    init {
        if (!enabled) {
            state = State.FAILED
        } else {
            tts = try {
                // Слушатель может прийти на чужом потоке — уводим на главный.
                TextToSpeech(appContext) { status -> main.post { onInit(status) } }
            } catch (e: Exception) {
                state = State.FAILED
                null
            }
        }
    }

    /** Готов ли голос. До инициализации движка — ещё неизвестно. */
    val isSilent: Boolean get() = state == State.FAILED || state == State.CLOSED

    /**
     * Произнести реплику. Набор фраз закрыт: [cue] выбирает строку внутри
     * [brand], произвольный текст сюда не передаётся никогда.
     */
    fun say(cue: IntroCue, brand: IntroBrand) {
        when (state) {
            State.STARTING -> {
                // Очередь ровно на одну реплику: вторая вытеснит первую,
                // и это правильно — устаревшую всё равно нельзя произносить.
                pendingCue = cue
                pendingBrand = brand
                pendingAtMs = SystemClock.elapsedRealtime()
                return
            }
            State.READY -> Unit
            State.FAILED, State.CLOSED -> return
        }

        val engine = tts ?: return
        if (isMuted()) return

        val text = brand.voice(cue, russianVoice)
        if (text.isEmpty()) return
        if (!requestFocus()) return

        // Первая реплика начинает очередь, вторая встаёт следом и не рвёт её.
        val mode = if (spokenCount == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = UTTERANCE_PREFIX + cue.name
        if (engine.speak(text, mode, null, id) == TextToSpeech.SUCCESS) {
            spokenCount++
        }
    }

    /** Замолчать, но остаться живым: экран ушёл в фон. */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            // Движок мог умереть вместе со своим процессом — это не наша беда.
        }
        abandonFocus()
    }

    /** Отпустить движок насовсем. Обязателен в onDestroy, иначе течёт сервис. */
    fun shutdown() {
        state = State.CLOSED
        pendingCue = null
        pendingBrand = null
        main.removeCallbacksAndMessages(null)
        val engine = tts
        tts = null
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (e: Exception) {
            // См. выше: закрываем как получится, падать на выходе нельзя.
        }
        abandonFocus()
    }

    /* ───────────────────────────── Внутреннее ───────────────────────────── */

    private fun onInit(status: Int) {
        if (state == State.CLOSED) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            state = State.FAILED
            return
        }
        val locale = pickLocale(engine)
        if (locale == null) {
            state = State.FAILED
            return
        }
        russianVoice = locale.language == RUSSIAN
        engine.setPitch(PITCH)
        engine.setSpeechRate(RATE)
        engine.setAudioAttributes(attributes)
        state = State.READY

        val cue = pendingCue
        val brand = pendingBrand
        pendingCue = null
        pendingBrand = null
        if (cue != null && brand != null &&
            SystemClock.elapsedRealtime() - pendingAtMs <= LATE_LIMIT_MS
        ) {
            say(cue, brand)
        }
    }

    /** Русский голос — родной для этих реплик; дальше язык системы, дальше английский. */
    private fun pickLocale(engine: TextToSpeech): Locale? {
        val candidates = listOf(Locale("ru", "RU"), Locale.getDefault(), Locale.US)
        for (locale in candidates) {
            val result = try {
                engine.setLanguage(locale)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                return locale
            }
        }
        return null
    }

    /** Беззвучный режим и нулевая громкость медиа — повод молчать. */
    private fun isMuted(): Boolean {
        val manager = audio ?: return false
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    private fun requestFocus(): Boolean {
        val manager = audio ?: return true
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        val manager = audio ?: return
        val request = focusRequest ?: return
        focusRequest = null
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            // Фокус мог быть отобран системой — отпускать уже нечего.
        }
    }

    private companion object {
        const val RUSSIAN = "ru"
        /** Ниже и медленнее обычного: узнаваемая подача диктора. */
        const val PITCH = 0.86f
        const val RATE = 0.95f
        /** Позже этого реплика уже не в кадре — лучше промолчать. */
        const val LATE_LIMIT_MS = 2_500L
        const val UTTERANCE_PREFIX = "oliver-intro-"
    }
}
