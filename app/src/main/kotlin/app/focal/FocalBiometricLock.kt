package app.focal

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Optional biometric / device-credential gate for Private Aperture (`◎`).
 *
 * Uses Android's built-in framework [BiometricPrompt] (API 29+) with zero external
 * dependencies and falls back gracefully when a device has no screen lock enrolled.
 */
object FocalBiometricLock {

    fun isDeviceAuthenticationAvailable(context: Context): Boolean {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val status = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            status == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    fun authenticatePrivateAperture(
        activity: Activity,
        onAuthenticated: () -> Unit,
        onCancelledOrFailed: () -> Unit,
    ) {
        if (!isDeviceAuthenticationAvailable(activity)) {
            onAuthenticated()
            return
        }

        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Private Aperture")
            .setSubtitle("Verify your identity to access private tabs")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }

        val prompt = builder.build()
        val cancellationSignal = CancellationSignal()
        prompt.authenticate(
            cancellationSignal,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onCancelledOrFailed()
                }

                override fun onAuthenticationFailed() {
                    // System prompt remains visible for retries until user cancels or succeeds
                }
            },
        )
    }
}
