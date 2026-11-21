package io.github.codex07.iou

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.codex07.iou.ui.IouRoot
import io.github.codex07.iou.ui.navigation.RecurringNotificationRoute
import io.github.codex07.iou.ui.viewmodel.AuthPurpose
import io.github.codex07.iou.ui.viewmodel.AuthenticationRequest
import io.github.codex07.iou.ui.viewmodel.SecurityViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val security: SecurityViewModel by viewModels()
    @Inject lateinit var recurring: RecurringTriggers
    private var authenticationId: Long? = null
    private var dueRequest by mutableStateOf<RecurringNotificationRoute?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recurring.requestPass()
    }

    private lateinit var prompt: BiometricPrompt
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readDueIntent(intent)
        else savedInstanceState.getString(PENDING_DUE)?.let { dueRequest = RecurringNotificationRoute.fromExtras("due", it) }
        authenticationId = security.state.value.request?.id
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { authenticationId?.let { security.result(it, true) } }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticationId?.let { security.result(it, false, when (errorCode) {
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Authentication is temporarily unavailable. Use an available device credential or try again later."
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "Authentication cancelled. Unlock to view financial content."
                    else -> "System authentication is unavailable. Check Android security settings and try again."
                }) }
            }
            override fun onAuthenticationFailed() { security.failedAttempt() }
        })
        setContent {
            IouRoot(security, requestAuthentication = { purpose ->
                val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
                val available = BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
                security.begin(purpose, secure, available)
            }, launchAuthentication = ::authenticate, cancelAuthentication = { prompt.cancelAuthentication() },
                dueRequest = dueRequest, onDueOpened = { if (dueRequest == it) dueRequest = null },
                onScheduleSaved = ::requestNotificationPermission)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDueIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        dueRequest?.let { outState.putString(PENDING_DUE, it.schedule?.value.orEmpty()) }
        super.onSaveInstanceState(outState)
    }

    private fun readDueIntent(intent: Intent) {
        RecurringNotificationRoute.fromExtras(intent.getStringExtra("iou.open"), intent.getStringExtra("iou.schedule"))
            ?.let { dueRequest = it }
    }

    private fun requestNotificationPermission() {
        if (BuildConfig.FLAVOR != "real" || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val preferences = getSharedPreferences("recurring-notifications", MODE_PRIVATE)
        if (preferences.getBoolean("permission-offered", false)) return
        preferences.edit { putBoolean("permission-offered", true) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun authenticate(request: AuthenticationRequest) {
        if (request.launched) return
        security.launched(request.id); authenticationId = request.id
        try {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock IOU")
                .setSubtitle(if (request.purpose == AuthPurpose.ENABLE_LOCK) "Confirm your identity to enable app lock" else "Authenticate to view your financial records")
                .setAllowedAuthenticators(authenticators).build())
        } catch (_: Exception) { security.result(request.id, false, "System authentication could not start. Check your device lock settings.") }
    }
    override fun onStart() { super.onStart(); security.foreground() }
    override fun onPause() {
        if (!isChangingConfigurations) security.background()
        super.onPause()
    }
    override fun onResume() { super.onResume(); security.foreground() }
    override fun onDestroy() { if (isFinishing && ::prompt.isInitialized) prompt.cancelAuthentication(); super.onDestroy() }

    private companion object {
        const val PENDING_DUE = "pending-recurring-notification"
    }
}
