package com.example.ytsaver

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecoveryLinkPurpose { SETUP, RESET }

/** Uses Firebase Auth's built-in email links; no SMTP credentials or callable backend are needed. */
object VaultRecovery {
    private const val APP_NAME = "vaultRecoveryAuth"
    private const val EMAIL_KEY = "vault_recovery_email"
    private const val UID_KEY = "vault_recovery_uid"
    private const val PENDING_EMAIL_KEY = "vault_recovery_pending_email"
    private const val PENDING_FLOW_KEY = "vault_recovery_pending_flow"
    private val appLock = Any()
    private val authLock = Mutex()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun app(ctx: Context): FirebaseApp = synchronized(appLock) {
        val context = ctx.applicationContext
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, APP_NAME)
            ?: error("Unable to initialize the vault recovery Firebase app.")
    }

    private suspend fun auth(ctx: Context): FirebaseAuth {
        val firebaseAuth = FirebaseAuth.getInstance(app(ctx))
        authLock.lock()
        try {
            if (firebaseAuth.currentUser == null) firebaseAuth.signInAnonymously().await()
            checkNotNull(firebaseAuth.currentUser) { "Vault recovery sign-in did not complete." }
        } finally {
            authLock.unlock()
        }
        return firebaseAuth
    }

    fun emailHint(ctx: Context): String? = recoveryEmail(ctx)?.let(::maskEmail)

    fun recoveryEmail(ctx: Context): String? = Prefs.str(ctx.applicationContext, EMAIL_KEY)

    fun isRecoveryEmail(ctx: Context, email: String): Boolean =
        recoveryEmail(ctx)?.equals(email.trim(), ignoreCase = true) == true

    suspend fun beginSetup(ctx: Context, email: String) {
        sendLink(ctx, email, RecoveryLinkPurpose.SETUP)
    }

    suspend fun requestReset(ctx: Context, email: String) {
        val normalized = email.trim()
        check(isRecoveryEmail(ctx, normalized)) {
            "That email does not match this vault's recovery email."
        }
        check(Prefs.str(ctx, UID_KEY) != null) { "No verified recovery email is configured for this vault." }
        sendLink(ctx, normalized, RecoveryLinkPurpose.RESET)
    }

    private suspend fun sendLink(ctx: Context, email: String, purpose: RecoveryLinkPurpose) {
        val normalized = email.trim()
        require(EMAIL_REGEX.matches(normalized)) { "Enter a valid recovery email." }
        val firebaseAuth = auth(ctx)
        val projectId = checkNotNull(FirebaseApp.getInstance().options.projectId) {
            "Firebase project ID is missing."
        }
        val settings = ActionCodeSettings.newBuilder()
            .setUrl("https://$projectId.firebaseapp.com")
            .setHandleCodeInApp(true)
            .setAndroidPackageName(ctx.packageName, false, null)
            .build()

        Prefs.putStr(ctx, PENDING_EMAIL_KEY, normalized)
        Prefs.putStr(ctx, PENDING_FLOW_KEY, purpose.name)
        try {
            firebaseAuth.sendSignInLinkToEmail(normalized, settings).await()
        } catch (e: Exception) {
            clearPending(ctx)
            throw e
        }
    }

    /** Completes a link delivered through the verified Firebase Hosting App Link. */
    suspend fun completeEmailLink(ctx: Context, link: String): RecoveryLinkPurpose? {
        val firebaseAuth = FirebaseAuth.getInstance(app(ctx))
        if (!firebaseAuth.isSignInWithEmailLink(link)) return null

        val email = checkNotNull(Prefs.str(ctx, PENDING_EMAIL_KEY)) {
            "Start recovery on this device before opening the email link."
        }
        val purpose = checkNotNull(Prefs.str(ctx, PENDING_FLOW_KEY)?.let {
            runCatching { RecoveryLinkPurpose.valueOf(it) }.getOrNull()
        }) { "This email link has no matching recovery request." }
        val result = firebaseAuth.signInWithEmailLink(email, link).await()
        val user = checkNotNull(result.user) { "Firebase did not return a recovery account." }

        if (purpose == RecoveryLinkPurpose.RESET) {
            val expectedUid = Prefs.str(ctx, UID_KEY)
            if (expectedUid == null || user.uid != expectedUid || !isRecoveryEmail(ctx, email)) {
                firebaseAuth.signOut()
                clearPending(ctx)
                error("This email link does not belong to this vault.")
            }
        } else {
            Prefs.putStr(ctx, EMAIL_KEY, email)
            Prefs.putStr(ctx, UID_KEY, user.uid)
        }

        clearPending(ctx)
        _revision.update { it + 1 }
        return purpose
    }

    private fun clearPending(ctx: Context) {
        Prefs.putStr(ctx, PENDING_EMAIL_KEY, null)
        Prefs.putStr(ctx, PENDING_FLOW_KEY, null)
    }

    private fun maskEmail(email: String): String {
        val at = email.lastIndexOf('@')
        if (at <= 0) return "*****"
        return email.take(minOf(2, at)) + "*****" + email.substring(at)
    }

    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}
