package io.github.codex07.iou

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.codex07.iou.store.RecurringRunner
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The foreground triggers of auto-posting (spec §5.7), in both flavors: the runner's own triggers (start-up load,
 * restores, imports) start with the app, and every return to the foreground asks for a pass. Runs off the main thread.
 */
class RecurringTriggers @Inject constructor(private val runner: RecurringRunner) {

    fun install(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        runner.start(scope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = requestPass()
        })
    }

    fun requestPass() = runner.request()
}
