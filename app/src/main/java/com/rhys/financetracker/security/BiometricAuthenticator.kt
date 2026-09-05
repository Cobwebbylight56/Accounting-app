package com.rhys.financetracker.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.AuthenticationResult
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over [BiometricPrompt].
 *
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is requested so that fingerprint, face
 * unlock and the device PIN/pattern all work — face unlock is only available on
 * hardware that supports it, and this is how the platform reports that without
 * the app having to guess.
 */
object BiometricAuthenticator {

    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether this device can prompt for a biometric or device credential now. */
    fun availability(activity: FragmentActivity): BiometricAvailability =
        when (BiometricManager.from(activity).canAuthenticate(ALLOWED_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> BiometricAvailability.NO_HARDWARE
            else -> BiometricAvailability.UNAVAILABLE
        }

    /**
     * Shows the system prompt.
     *
     * @param onSuccess called on the main thread once the user is recognised.
     * @param onFailure called with a message when authentication cannot proceed;
     *   a plain "not recognised" is handled by the prompt itself and does not
     *   reach this callback.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Finance Tracker",
        subtitle: String = "Confirm it is you to see your finances",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // The user cancelling is not an error worth reporting.
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        return
                    }
                    onFailure(errString.toString())
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        runCatching { prompt.authenticate(info) }
            .onFailure { onFailure(it.message ?: "Could not start the unlock prompt") }
    }
}

enum class BiometricAvailability(val message: String) {
    AVAILABLE("Ready to use"),
    NOT_ENROLLED("Set up a fingerprint or face unlock in your phone's settings first"),
    NO_HARDWARE("This device does not support fingerprint or face unlock"),
    UNAVAILABLE("Fingerprint or face unlock is not available at the moment"),
    ;

    val isUsable: Boolean get() = this == AVAILABLE
}
