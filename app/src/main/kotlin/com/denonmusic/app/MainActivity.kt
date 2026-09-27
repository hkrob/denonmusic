package com.denonmusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.denonmusic.app.nav.MainScreen
import com.denonmusic.app.player.PlayerViewModel
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

    // Same activity-scoped Hilt instance MainScreen hoists via hiltViewModel() - both resolve
    // against this activity's ViewModelStore, so this doesn't create a second, unsynced copy.
    private val playerViewModel: PlayerViewModel by viewModels()

    private var lastInteractionAtMillis = SystemClock.elapsedRealtime()
    private var isDimmed = false

    /**
     * From Android 13 a notification needs the user's say-so, and without it the now-playing
     * controls simply never appear - silently, which is the worst way for a feature to be missing.
     * Asked for once on first launch; a refusal is final and left alone, since the app works
     * perfectly well without it and nagging would be worse than the gap.
     *
     * Nothing to verify here on the phone this was developed against (Android 9, where the
     * permission does not exist and the notification just appears).
     */
    private val requestNotificationPermission = registerForActivityResult(RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        setContent {
            WinampTheme {
                Surface(color = Winamp.Background) {
                    MainScreen()
                }
            }
        }
        observeScreenSettings()
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Any touch counts as activity, and immediately un-dims - the settings-driven idle loop below
     * only ever dims, so this is the one place brightness gets restored. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        registerInteraction()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The invariant is that the phone is never in the audio path (see CLAUDE.md) - there is no local
     * media stream for these keys to usefully control, so send them to the receiver's own volume
     * (HEOS `set_volume`) instead of letting Android's default handling adjust one anyway.
     *
     * `dispatchKeyEvent` would be the more usual place to intercept a key before any default
     * handling, but overriding it on `ComponentActivity` trips a `@RestrictedApi` lint error in this
     * project's androidx version (it's reserved for the same library group). `onKeyDown`/`onKeyUp`
     * are the ordinary, unrestricted override points and still run before the system's own volume
     * handling, since that's reached through this same callback chain.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = volumeKeyStep(keyCode) ?: return super.onKeyDown(keyCode, event)
        registerInteraction()
        playerViewModel.adjustVolume(step)
        return true
    }

    /** Consumed only so the system volume overlay doesn't appear on release; a held key's repeated
     * ACTION_DOWNs (at the OS's own key-repeat rate) are the only ones that should step the volume,
     * so this itself does nothing further. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        volumeKeyStep(keyCode) ?: return super.onKeyUp(keyCode, event)
        return true
    }

    private fun volumeKeyStep(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_KEY_STEP
        KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_KEY_STEP
        else -> null
    }

    private fun registerInteraction() {
        lastInteractionAtMillis = SystemClock.elapsedRealtime()
        if (isDimmed) restoreBrightness()
    }

    /** Coming back to the app is activity: without this the idle clock has been running the whole
     * time the app was away, so a resume with auto-dim on dimmed the screen instantly. */
    override fun onResume() {
        super.onResume()
        lastInteractionAtMillis = SystemClock.elapsedRealtime()
        restoreBrightness()
    }

    /**
     * Restarts its inner idle-check loop (via `collectLatest`) whenever settings change - cheap,
     * since the loop itself is just a 1s poll against a plain field, and simpler than diffing which
     * particular setting changed.
     *
     * Scoped to STARTED, not the activity's whole lifetime: a plain `lifecycleScope.launch` kept the
     * 1s poll ticking for as long as the process lived, so enabling auto-dim signed the user up for a
     * once-a-second wakeup while the app sat in the background doing nothing. There is nothing to dim
     * when the activity isn't on screen anyway.
     */
    private fun observeScreenSettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.settingsState.collectLatest { settings ->
                    applyKeepScreenOn(settings.keepScreenOn)
                    runAutoDimLoop(settings)
                }
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

        /** HEOS volume is 0..100, not the device media stream's usual 0..15, so a single key press
         * needs to be a visibly bigger step than the OS default to feel like it's doing anything. */
        const val VOLUME_KEY_STEP = 2
    }
}
