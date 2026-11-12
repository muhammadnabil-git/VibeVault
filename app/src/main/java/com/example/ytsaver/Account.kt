package com.example.ytsaver

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Accounts are Firebase Authentication accounts (Gmail address + password, or "Sign in with Google").
 * Passwords are only ever sent to Firebase: nothing about the password is stored on the phone.
 * The signed-in user is kept by the Firebase SDK itself, which also refreshes the session.
 * What is synced for the account lives in [CloudSync].
 */
object Account {
    /** Email of the signed-in user, or null. */
    val email = MutableStateFlow<String?>(null)
    /** Firebase user id of the signed-in user, or null. Changes drive [CloudSync]. */
    val uid = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    private val GMAIL = Regex("^[A-Za-z0-9._%+-]+@gmail\\.com$", RegexOption.IGNORE_CASE)
    private var listening = false

    /** Call once at start-up: restores the signed-in user and follows sign-in / sign-out from now on. */
    @Synchronized
    fun load(ctx: Context) {
        removeLegacyData(ctx)
        try {
            val auth = FirebaseAuth.getInstance()
            apply(auth.currentUser)
            if (!listening) {
                listening = true
                auth.addAuthStateListener { apply(it.currentUser) }
            }
        } catch (_: Exception) {
            // Firebase isn't configured in this build: the app still works, just without an account
        }
    }

    private fun apply(u: FirebaseUser?) {
        email.value = u?.email
        uid.value = u?.uid
    }

    /** Versions before 6.5 kept local password hashes and Supabase settings. None of that exists any more. */
    private fun removeLegacyData(ctx: Context) {
        for (k in listOf(
            "acct_local", "acct_email", "acct_access", "acct_refresh", "acct_uid",
            "acct_last_synced_settings", "cloud_url", "cloud_key",
        )) {
            if (Prefs.str(ctx, k) != null) Prefs.putStr(ctx, k, null)
        }
    }

    fun lastSyncMillis(ctx: Context) = Prefs.long(ctx, "acct_last_sync")

    /** True when this build carries the Google web client id (google-services.json lists the Google sign-in client). */
    fun googleAvailable(ctx: Context): Boolean =
        ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName) != 0

    private fun validate(mail: String, pass: String): String? = when {
        !GMAIL.matches(mail) -> L.t("Use a Gmail address (name@gmail.com).", "استخدم عنوان Gmail (name@gmail.com).")
        pass.length < 6 -> L.t("The password needs at least 6 characters.", "كلمة المرور يجب أن تكون 6 أحرف على الأقل.")
        else -> null
    }

    /** Turns a Firebase error into a short sentence a normal user can act on. */
    private fun friendly(e: Exception): String = when {
        e is FirebaseAuthUserCollisionException ->
            L.t("This email already has an account. Sign in instead.", "هذا البريد لديه حساب بالفعل. سجّل الدخول بدلًا من ذلك.")
        e is FirebaseAuthWeakPasswordException ->
            L.t("That password is too weak. Use at least 6 characters.", "كلمة المرور ضعيفة. استخدم 6 أحرف على الأقل.")
        e is FirebaseAuthInvalidUserException ->
            L.t("No account with this email. Create one first.", "لا يوجد حساب بهذا البريد. أنشئ حسابًا أولًا.")
        e is FirebaseAuthInvalidCredentialsException ->
            L.t("Wrong email or password.", "البريد أو كلمة المرور غير صحيحة.")
        e is FirebaseTooManyRequestsException ->
            L.t("Too many attempts. Wait a few minutes and try again.", "محاولات كثيرة. انتظر بضع دقائق ثم حاول مجددًا.")
        e is FirebaseNetworkException ->
            L.t("Couldn't reach the server. Check your connection.", "تعذّر الوصول إلى الخادم. تحقق من اتصالك.")
        e is FirebaseAuthException && e.errorCode == "ERROR_OPERATION_NOT_ALLOWED" ->
            L.t("Email sign-in isn't switched on for this app yet.", "تسجيل الدخول بالبريد غير مفعّل لهذا التطبيق بعد.")
        else -> L.t("Something went wrong: ", "حدث خطأ: ") + (e.message ?: e.javaClass.simpleName)
    }

    /** Creates a Firebase account and signs in. @return an error message, or null when it worked. */
    suspend fun signUp(mail0: String, pass: String): String? {
        val mail = mail0.trim().lowercase()
        validate(mail, pass)?.let { return it }
        return try {
            val user = FirebaseAuth.getInstance().createUserWithEmailAndPassword(mail, pass).await().user
            // ownership check by email; it does not block anything, sync works right away
            try { user?.sendEmailVerification()?.await() } catch (_: Exception) { }
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    /** @return an error message, or null when it worked. */
    suspend fun signIn(mail0: String, pass: String): String? {
        val mail = mail0.trim().lowercase()
        validate(mail, pass)?.let { return it }
        return try {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(mail, pass).await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    /** Sends Firebase's password-reset email. @return an error message, or null when it was sent. */
    suspend fun resetPassword(mail0: String): String? {
        val mail = mail0.trim().lowercase()
        if (!GMAIL.matches(mail)) return L.t("Type your Gmail address first.", "اكتب عنوان Gmail أولًا.")
        return try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(mail).await()
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }

    /**
     * Opens the Google account chooser and signs in through Firebase. Only Gmail accounts are accepted.
     * [activityCtx] must be the Activity (the chooser is shown over it).
     * @return an error message, or null when it worked.
     */
    suspend fun googleSignIn(activityCtx: Context): String? {
        val webId = activityCtx.resources.getIdentifier("default_web_client_id", "string", activityCtx.packageName)
        if (webId == 0) {
            return L.t("Google sign-in isn't available in this build. Use your email and password.",
                "تسجيل الدخول بحساب Google غير متاح في هذا الإصدار. استخدم البريد وكلمة المرور.")
        }
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(activityCtx.getString(webId))
                .build()
            val result = CredentialManager.create(activityCtx)
                .getCredential(activityCtx, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val cred = result.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return L.t("Google sign-in returned something unexpected.", "أعاد تسجيل الدخول بـ Google نتيجة غير متوقعة.")
            }
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            val user = FirebaseAuth.getInstance()
                .signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
            if (!GMAIL.matches(user?.email.orEmpty())) {
                FirebaseAuth.getInstance().signOut()
                return L.t("Only Gmail accounts can sign in.", "يمكن تسجيل الدخول بحسابات Gmail فقط.")
            }
            null
        } catch (_: GetCredentialCancellationException) {
            L.t("Sign-in cancelled.", "تم إلغاء تسجيل الدخول.")
        } catch (_: NoCredentialException) {
            L.t("No Google account found on this phone. Add one in Android settings.", "لا يوجد حساب Google على هذا الهاتف. أضف حسابًا من إعدادات أندرويد.")
        } catch (e: Exception) {
            L.t("Google sign-in failed: ", "فشل تسجيل الدخول بـ Google: ") + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Signs out. Playlists and downloaded files stay on this phone; [CloudSync] stops by itself. */
    fun signOut(ctx: Context) {
        try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) { }
        Prefs.putStr(ctx, "acct_last_sync", null)
        apply(null)
        status.value = ""
    }
}
