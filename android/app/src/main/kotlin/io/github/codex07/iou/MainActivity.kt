package io.github.codex07.iou

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.codex07.iou.ui.IouRoot
import io.github.codex07.iou.ui.viewmodel.AuthPurpose
import io.github.codex07.iou.ui.viewmodel.AuthenticationRequest
import io.github.codex07.iou.ui.viewmodel.SecurityViewModel

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val security: SecurityViewModel by viewModels()
    private var authenticationId: Long? = null
    private lateinit var prompt: BiometricPrompt
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            }, launchAuthentication = ::authenticate, cancelAuthentication = { prompt.cancelAuthentication() })
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
}
