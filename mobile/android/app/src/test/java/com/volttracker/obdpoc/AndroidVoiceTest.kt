package com.volttracker.obdpoc

import android.content.Context
import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.volttracker.obdpoc.engine.AndroidVoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/** The guided test's text-to-speech voice: queued until the engine is ready, released once it has finished. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun engine() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    @After
    fun tearDown() = ShadowTextToSpeech.reset()

    @Test
    fun linesSaidBeforeTheEngineIsReadyWaitForIt() {
        val voice = AndroidVoice(context)
        voice.say("Lock the doors.")
        idle()
        assertTrue(voice.speaking())
        assertNull(engine().lastSpokenText)

        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals("Lock the doors.", engine().lastSpokenText)
        assertEquals(TextToSpeech.QUEUE_ADD, engine().queueMode)
        idle()

        assertFalse("spoken", voice.speaking())
    }

    @Test
    fun itSpeaksAsNavigationGuidance() {
        AndroidVoice(context)
        idle()
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)

        val usage = usageOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, usage)
    }

    @Test
    fun closeLetsTheLastLineFinishFirst() {
        val voice = AndroidVoice(context)
        idle()
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)

        voice.say("Test stopped.")
        voice.close()
        voice.say("Too late.")
        shadowOf(Looper.getMainLooper()).runOneTask()

        assertEquals("Test stopped.", engine().lastSpokenText)
        assertFalse("still speaking", engine().isShutdown)
        idle()
        assertTrue(engine().isShutdown)
        assertEquals(listOf("Test stopped."), engine().spokenTextList)
    }

    @Test
    fun anEngineThatFailsToStartGoesQuiet() {
        val voice = AndroidVoice(context)
        voice.say("Hello.")
        idle()

        engine().onInitListener.onInit(TextToSpeech.ERROR)
        voice.say("Anyone?")
        idle()

        assertFalse(voice.speaking())
        assertTrue(engine().isShutdown)
        assertTrue("the test can see it", voice.failed())
    }

    @Test
    fun aWorkingEngineHasNotFailed() {
        val voice = AndroidVoice(context)
        idle()
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        voice.say("Lock the doors.")
        idle()

        assertFalse(voice.failed())
    }

    @Test
    fun interruptDropsLinesStillWaiting() {
        val voice = AndroidVoice(context)
        voice.say("Open the hatch.")
        idle()

        voice.interrupt()
        assertFalse(voice.speaking())
        idle()
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        voice.say("Test stopped.")
        idle()

        assertEquals("only what came after the interrupt", listOf("Test stopped."), engine().spokenTextList)
    }

    /** The usage the engine was given, read back through its hidden field (the shadow keeps none). */
    private fun usageOf(tts: TextToSpeech): Int {
        val field = TextToSpeech::class.java.getDeclaredField("mParams")
        field.isAccessible = true
        val params = field.get(tts) as android.os.Bundle

        @Suppress("DEPRECATION")
        val attributes = params.getParcelable<AudioAttributes>("audioAttributes")
        return attributes?.usage ?: -1
    }
}
