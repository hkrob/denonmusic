package com.denonmusic.app

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import com.denonmusic.app.nav.MainScreen
import com.denonmusic.app.settings.SettingsViewModel
import com.denonmusic.app.ui.Winamp
import com.denonmusic.app.ui.WinampTheme
import com.denonmusic.data.settings.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // A Hilt view model rather than `@Inject lateinit var` field injection - every other injection
    // point in this app is constructor injection (view models, the bridge service); this keeps
    // MainActivity on the one pattern the project's Dagger/Kotlin toolchain pairing has actually been
    // exercised against.
    private val settingsViewModel: SettingsViewModel by viewModels()

    private var lastInteractionAtMillis = SystemClock.elapsedRealtime()
    private var isDimmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WinampTheme {
                Surface(color = Winamp.Background) {
                    MainScreen()
                }
            }
        }
        observeScreenSettings()
    }

    /** Any touch counts as activity, and immediately un-dims - the settings-driven idle loop below
     * only ever dims, so this is the one place brightness gets restored. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastInteractionAtMillis = SystemClock.elapsedRealtime()
        if (isDimmed) restoreBrightness()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Runs for the app's lifetime, restarting its inner idle-check loop (via `collectLatest`)
     * whenever settings change - cheap, since the loop itself is just a 1s poll against a plain
     * field, and simpler than diffing which particular setting changed.
     */
    private fun observeScreenSettings() {
        lifecycleScope.launch {
            settingsViewModel.settingsState.collectLatest { settings ->
                applyKeepScreenOn(settings.keepScreenOn)
                runAutoDimLoop(settings)
            }
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private suspend fun runAutoDimLoop(settings: AppSettings) {
        if (!settings.autoDimEnabled) {
            restoreBrightness()
            return
        }
        val delayMillis = settings.autoDimAfterSeconds * 1000L
        while (currentCoroutineContext().isActive) {
            val idleForMillis = SystemClock.elapsedRealtime() - lastInteractionAtMillis
            if (idleForMillis >= delayMillis && !isDimmed) {
                dimTo(settings.autoDimBrightnessPercent)
            }
            delay(AUTO_DIM_CHECK_INTERVAL_MS)
        }
    }

    private fun dimTo(percent: Int) {
        isDimmed = true
        val attributes = window.attributes
        attributes.screenBrightness = percent.coerceIn(1, 100) / 100f
        window.attributes = attributes
    }

    private fun restoreBrightness() {
        if (!isDimmed) return
        isDimmed = false
        val attributes = window.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    private companion object {
        const val AUTO_DIM_CHECK_INTERVAL_MS = 1_000L
    }
}
