/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : speech/Announcer.kt
 *  Назначение : голос диктора на заставке — живая запись, лежащая
 *               в приложении (res/raw/intro_voice.ogg).
 *
 *  ВАРИАНТ    : БЕЗ ШИФРОВАНИЯ (секретов не хранит и хранить не может).
 *
 *  Почему запись, а не синтезатор речи:
 *      · движок TTS — ЧУЖОЕ приложение, и часть движков синтезирует речь
 *        на своём сервере. Со своей записью наружу не уходит ничего,
 *        и в приложении не остаётся ни одного обращения к чужому коду;
 *      · на разных телефонах стоят разные движки и голоса, и заставка
 *        звучала бы у всех по-своему — а это часть знака сообщества;
 *      · длительность записи известна заранее, поэтому раскадровка
 *        (IntroScript.VOICE_MS) точно знает, когда экрану гаснуть.
 *
 *  Приличия остались прежними:
 *      · телефон в беззвучном режиме молчит. Лаунчер, который орёт
 *        на весь автобус, потому что «у нас красивая заставка», — это
 *        не фича;
 *      · фокус звука берётся временный, с приглушением чужой музыки,
 *        и сразу отдаётся. Потеряли фокус — замолчали;
 *      · ничего не логируется.
 *
 *  Живучесть: файл готовится асинхронно. Команда, пришедшая раньше
 *  готовности, ждёт в очереди из ОДНОГО элемента и выбрасывается, если
 *  опоздала больше чем на [LATE_LIMIT_MS] — лучше тишина, чем голос
 *  поверх уже другого кадра.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.oliver200.launcher.R

class Announcer(context: Context, enabled: Boolean) {

    private enum class State { PREPARING, READY, PLAYING, FAILED, CLOSED }

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

    private var player: MediaPlayer? = null
    private var state: State = State.PREPARING
    private var focusRequest: AudioFocusRequest? = null
    private var pendingAtMs = 0L
    private var pending = false

    init {
        if (!enabled) {
            state = State.FAILED
        } else {
            player = try {
                MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    appContext.resources.openRawResourceFd(R.raw.intro_voice).use { fd ->
                        setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    setOnPreparedListener { main.post { onPrepared() } }
                    setOnCompletionListener { main.post { abandonFocus() } }
                    setOnErrorListener { _, _, _ ->
                        main.post { fail() }
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                // Ресурса нет, кодек не открылся, память кончилась — заставка
                // молча идёт без голоса. Ронять из-за звука экран нельзя.
                state = State.FAILED
                null
            }
        }
    }

    /** Голоса не будет: выключен настройкой или не открылся файл. */
    val isSilent: Boolean get() = state == State.FAILED || state == State.CLOSED

    /** Включить запись. Вызывается один раз, на своей отметке ленты. */
    fun play() {
        when (state) {
            State.PREPARING -> {
                pending = true
                pendingAtMs = SystemClock.elapsedRealtime()
                return
            }
            State.READY -> Unit
            State.PLAYING, State.FAILED, State.CLOSED -> return
        }

        val media = player ?: return
        if (isMuted()) return
        if (!requestFocus()) return
        try {
            media.start()
            state = State.PLAYING
        } catch (e: IllegalStateException) {
            fail()
        }
    }

    /** Замолчать, но остаться живым: экран ушёл в фон. */
    fun stop() {
        val media = player
        try {
            if (state == State.PLAYING && media != null && media.isPlaying) media.pause()
        } catch (e: IllegalStateException) {
            // Плеер уже не в том состоянии — значит и останавливать нечего.
        }
        abandonFocus()
    }

    /** Отпустить плеер насовсем. Обязателен в onDestroy, иначе течёт кодек. */
    fun shutdown() {
        state = State.CLOSED
        pending = false
        main.removeCallbacksAndMessages(null)
        val media = player
        player = null
        try {
            media?.release()
        } catch (e: Exception) {
            // Закрываем как получится: падать на выходе нельзя.
        }
        abandonFocus()
    }

    /* ───────────────────────────── Внутреннее ───────────────────────────── */

    private fun onPrepared() {
        if (state == State.CLOSED || state == State.FAILED) return
        state = State.READY
        if (pending && SystemClock.elapsedRealtime() - pendingAtMs <= LATE_LIMIT_MS) {
            pending = false
            play()
        }
        pending = false
    }

    private fun fail() {
        if (state == State.CLOSED) return
        state = State.FAILED
        abandonFocus()
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
        /** Позже этого запись уже не в кадре — лучше промолчать. */
        const val LATE_LIMIT_MS = 2_500L
    }
}
