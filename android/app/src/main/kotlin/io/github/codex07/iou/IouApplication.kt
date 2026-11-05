package io.github.codex07.iou

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.codex07.iou.security.AppSecurity
import javax.inject.Inject

@HiltAndroidApp
class IouApplication : Application() {

    @Inject lateinit var security: AppSecurity

    override fun onCreate() {
        super.onCreate()
        security.install(this)
    }
}
