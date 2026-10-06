package com.volttracker.obdpoc.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.components.rememberSystemPrefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SystemPrefsResumeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsChangedWhileAwayRefreshWithoutRecreatingTheHost() {
        val resolver = compose.activity.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.System.putString(resolver, Settings.System.TIME_12_24, "12")
        lateinit var registry: LifecycleRegistry
        val owner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle get() = registry
            }
        registry = LifecycleRegistry(owner)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val prefs = rememberSystemPrefs(quietLiveData = true, demo = false)
                Text("motion=${prefs.reduceMotion};clock=${prefs.clock24h}")
            }
        }
        compose.onNodeWithText("motion=false;clock=false").assertExists()
        compose.runOnIdle {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            Settings.System.putString(resolver, Settings.System.TIME_12_24, "24")
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("motion=true;clock=true").assertExists()
    }
}
