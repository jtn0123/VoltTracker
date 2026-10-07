package com.volttracker.obdpoc.engine

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The guided car test's voice: Android text-to-speech, played as navigation guidance so it ducks
 * music and reaches the car's speakers like a sat-nav prompt. Lines said before the engine is ready
 * wait for it. A speech engine that fails to start, or fails a line, marks the voice [failed], so the
 * test can stop instead of giving instructions nobody hears. The engine lives on the main thread;
 * [say], [interrupt] and [close] may come from any thread.
 */
internal class AndroidVoice(
    context: Context,
) : GuidedCarTest.Voice {
    private val main = Handler(Looper.getMainLooper())

    /** Lines said and not yet spoken (or failed), by utterance id: what [speaking] answers from. */
    private val outstanding: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val nextId = AtomicInteger(0)

    @Volatile private var closing = false

    @Volatile private var failed = false

    // Main thread only.
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayDeque<Pair<String, String>>()

    init {
        val app = context.applicationContext
        main.post {
            tts =
                try {
                    TextToSpeech(app, ::onInit)
                } catch (ex: RuntimeException) {
                    failed = true
                    null
                }
        }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            failed = true
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

                override fun onDone(utteranceId: String?) = spoken(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = failedLine(utteranceId)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = failedLine(utteranceId)

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = spoken(utteranceId)
            },
        )
        ready = true
        while (waiting.isNotEmpty()) waiting.removeFirst().let { (id, text) -> speak(id, text) }
        if (closing && outstanding.isEmpty()) shutdown()
    }

    override fun say(text: String) {
        if (text.isEmpty() || closing || failed) return
        val id = "guided-${nextId.getAndIncrement()}"
        outstanding += id
        main.post {
            when {
                ready -> speak(id, text)
                tts != null -> waiting.addLast(id to text)
                else -> outstanding -= id
            }
        }
    }

    override fun speaking(): Boolean = outstanding.isNotEmpty()

    override fun failed(): Boolean = failed

    override fun interrupt() {
        outstanding.clear()
        main.post {
            waiting.clear()
            tts?.stop()
        }
    }

    override fun close() {
        closing = true
        main.post { if (outstanding.isEmpty()) shutdown() }
        // However the engine behaves, it is released.
        main.postDelayed(::shutdown, CLOSE_TIMEOUT_MS)
    }

    private fun speak(
        id: String,
        text: String,
    ) {
        // An interrupted line never starts.
        if (id !in outstanding) return
        val engine = tts
        if (engine == null || engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
            failedLine(id)
        }
    }

    /** One line finished or was stopped; called on a TTS thread. */
    private fun spoken(id: String?) {
        if (id != null) outstanding -= id
        if (outstanding.isEmpty() && closing) main.post(::shutdown)
    }

    private fun failedLine(id: String?) {
        failed = true
        spoken(id)
    }

    private fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        waiting.clear()
        outstanding.clear()
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 20_000L
    }
}
