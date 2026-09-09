/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/SplashActivity.kt
 *  Назначение : заставка — появление знака Animal Company · RU Steam
 *               Community, подпись «Заботимся о вас» и голос диктора.
 *
 *  Как устроено время: ни одного «постучать через 300 мс». Кадры приходят
 *  от Choreographer — того же источника, по которому система рисует экран,
 *  а что показывать в этот миллисекунды решает :core (IntroScript).
 *  Поэтому ролик одинаково идёт и на 60, и на 120 Гц, а при уходе в фон
 *  замирает и продолжается с того же места, а не «доигрывает вслепую».
 *
 *  Безопасность:
 *      · голос — своя запись в res/raw, а не чужой движок TTS: наружу
 *        не уходит ничего и ни одного обращения к чужому коду нет;
 *      · fail-closed по бренду: не открылся шифрованный контейнер —
 *        заставки нет вообще, сразу главный экран. Показать «что-нибудь»
 *        вместо знака нельзя: подменённая заставка это фишинг;
 *      · это единственный exported-компонент приложения; MainActivity
 *        перестала быть точкой входа снаружи (см. AndroidManifest);
 *      · ролик один раз за запуск процесса. Он не средство «подождать»,
 *        и повторно на глаза не лезет;
 *      · тап в любом месте экрана обрывает всё, включая диктора;
 *      · при выключенных системных анимациях (спец. возможности,
 *        экономия батареи) заставка не показывается вовсе.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.Choreographer
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.oliver200.launcher.BuildConfig
import com.oliver200.launcher.OliverApp
import com.oliver200.launcher.R
import com.oliver200.launcher.core.intro.IntroBrand
import com.oliver200.launcher.core.intro.IntroBrandException
import com.oliver200.launcher.core.intro.IntroBrandSource
import com.oliver200.launcher.core.intro.IntroPlayback
import com.oliver200.launcher.speech.Announcer

class SplashActivity : AppCompatActivity(R.layout.activity_splash) {

    private lateinit var logo: IntroLogoView
    private lateinit var caption: TextView

    private val playback = IntroPlayback()
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val frameCallback = Choreographer.FrameCallback { nanos -> onFrame(nanos) }

    private var announcer: Announcer? = null
    private var brand: IntroBrand? = null

    private var clockStarted = false
    private var startNanos = 0L
    private var elapsedMs = 0L
    private var lastCueMs = -1L
    private var running = false
    private var navigated = false
    private var captionRisePx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = OliverApp.from(this)

        logo = findViewById(R.id.intro_logo)
        caption = findViewById(R.id.intro_caption)
        captionRisePx = resources.getDimension(R.dimen.intro_caption_rise)

        // Тексты знака живут в шифрованном контейнере (SealedIntroBrand).
        val loaded = try {
            IntroBrandSource.load(BuildConfig.DEBUG)
        } catch (e: IntroBrandException) {
            null
        }
        if (loaded == null) {
            // Fail-closed: чужой или битый контейнер — не показываем ничего.
            goToMain()
            return
        }
        brand = loaded
        logo.setBrand(loaded)
        logo.contentDescription = getString(R.string.intro_a11y)
        caption.text = loaded.care

        // Ролик уже играл в этом процессе, экран пересоздан системой или
        // анимации в системе выключены — заставку не показываем.
        if (app.introPlayed || savedInstanceState != null || animationsDisabled()) {
            goToMain()
            return
        }

        announcer = Announcer(this, app.settings.introVoice)

        // Пропуск — тап в любом месте экрана. Отдельной кнопки нет: она
        // отвлекала от знака, а обрывать ролик всё равно можно всегда.
        findViewById<View>(R.id.intro_root).setOnClickListener {
            playback.requestSkip(elapsedMs)
        }
    }

    override fun onResume() {
        super.onResume()
        if (navigated) return
        running = true
        // Точку отсчёта пересобираем от ближайшего кадра: пауза не должна
        // «проматывать» ролик на время, проведённое в фоне.
        clockStarted = false
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        running = false
        choreographer.removeFrameCallback(frameCallback)
        announcer?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        choreographer.removeFrameCallback(frameCallback)
        announcer?.shutdown()
        announcer = null
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running || navigated) return

        if (!clockStarted) {
            clockStarted = true
            startNanos = frameTimeNanos - elapsedMs * NANOS_PER_MS
        }
        elapsedMs = (frameTimeNanos - startNanos) / NANOS_PER_MS

        val f = playback.frameAt(elapsedMs)
        logo.setFrame(f)
        caption.alpha = clamp01(f.captionAlpha * f.master)
        caption.translationY = f.captionRise * captionRisePx

        // Единственное звуковое событие ленты: включить запись диктора.
        val voice = announcer
        if (voice != null && playback.cuesDue(lastCueMs, elapsedMs).isNotEmpty()) voice.play()
        lastCueMs = elapsedMs

        if (f.finished) {
            goToMain()
        } else {
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        running = false
        choreographer.removeFrameCallback(frameCallback)
        announcer?.stop()
        OliverApp.from(this).introPlayed = true

        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * Системные анимации выключены — значит человек попросил не показывать
     * ему движение. Заставка обязана это уважать.
     */
    private fun animationsDisabled(): Boolean {
        val scale = SystemSettings.Global.getFloat(
            contentResolver,
            SystemSettings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return scale == 0f
    }

    private fun clamp01(value: Float): Float = when {
        value.isNaN() -> 0f
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
