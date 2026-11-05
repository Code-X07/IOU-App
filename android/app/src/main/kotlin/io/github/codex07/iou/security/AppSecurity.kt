package io.github.codex07.iou.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.TimedLockGate
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App lock and screen protection on the device (spec §9), for both flavors. `IouApplication` calls [install]
 * once. Then:
 * - the process lifecycle drives [lock]: start is a return to the foreground, stop is going to the background.
 *   `ProcessLifecycleOwner` reports stop 700 ms after the last activity stops, so a rotation never counts;
 * - every activity's window has `FLAG_SECURE` while screen protection is on, from before its `onCreate` and
 *   following later changes of the setting.
 */
class AppSecurity @Inject constructor(private val lock: TimedLockGate, private val device: DeviceSettingsStore) {

    fun install(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = lock.foregrounded()

            override fun onStop(owner: LifecycleOwner) = lock.backgrounded()
        })
        app.registerActivityLifecycleCallbacks(SecureWindows())
    }

    private inner class SecureWindows : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            protect(activity, device.settings.value.screenProtection)
            (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                device.settings.map { it.screenProtection }.distinctUntilChanged().collect { protect(activity, it) }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun protect(activity: Activity, enabled: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (enabled) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }
}
