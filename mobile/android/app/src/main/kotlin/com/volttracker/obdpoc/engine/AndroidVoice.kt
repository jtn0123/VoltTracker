package com.volttracker.obdpoc.engine

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * The guided car test's voice: Android text-to-speech, played as navigation guidance so it ducks
 * music and reaches the car's speakers like a sat-nav prompt. Lines said before the engine is ready
 * wait for it. The engine lives on the main thread; [say] and [close] may come from any thread.
 */
internal class AndroidVoice(
    context: Context,
) : GuidedCarTest.Voice {
    private val main = Handler(Looper.getMainLooper())

    /** Lines said and not yet spoken (or failed): what [speaking] answers from, on any thread. */
    private val pending = AtomicInteger(0)

    @Volatile private var closing = false

    // Main thread only.
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayDeque<String>()
    private var nextId = 0

    init {
        val app = context.applicationContext
        main.post { tts = TextToSpeech(app, ::onInit) }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            shutdown()
            return
        }
        engine.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = spoken()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = spoken()

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = spoken()

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = spoken()
            },
        )
        ready = true
        while (waiting.isNotEmpty()) speak(waiting.removeFirst())
        if (closing && pending.get() <= 0) shutdown()
    }

    override fun say(text: String) {
        if (text.isEmpty() || closing) return
        pending.incrementAndGet()
        main.post {
            when {
                ready -> speak(text)
                tts != null -> waiting.addLast(text)
                else -> pending.decrementAndGet()
            }
        }
    }

    override fun speaking(): Boolean = pending.get() > 0

    override fun close() {
        closing = true
        main.post { if (pending.get() <= 0) shutdown() }
        // However the engine behaves, it is released.
        main.postDelayed(::shutdown, CLOSE_TIMEOUT_MS)
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null ||
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "guided-${nextId++}") != TextToSpeech.SUCCESS
        ) {
            pending.decrementAndGet()
        }
    }

    /** One line finished (or failed); called on a TTS thread. */
    private fun spoken() {
        if (pending.decrementAndGet() <= 0 && closing) main.post(::shutdown)
    }

    private fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        waiting.clear()
        pending.set(0)
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 20_000L
    }
}
