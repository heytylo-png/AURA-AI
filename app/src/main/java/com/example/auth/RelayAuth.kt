package com.example.auth

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies Firebase ID tokens that prove to the Claude relay which app install is calling. */
interface RelayTokenSource {
    /** The current user's ID ("access ID"), or null if not signed in yet. */
    fun accessId(): String?

    /** Signs in if needed and returns a fresh ID token. Throws with a readable message on failure. */
    suspend fun idToken(forceRefresh: Boolean = false): String
}

/**
 * Anonymous Firebase sign-in: no account or password. Each install gets a stable user ID,
 * which the relay's allowlist (AURA_ALLOWED_UIDS) must contain. Reinstalling or clearing app
 * data creates a new ID.
 */
class FirebaseRelayAuth : RelayTokenSource {

    private fun auth(): FirebaseAuth = try {
        FirebaseAuth.getInstance()
    } catch (e: IllegalStateException) {
        throw IllegalStateException("Firebase isn't set up in this build of Aura, so it can't sign in to the Claude relay.", e)
    }

    override fun accessId(): String? = runCatching { auth().currentUser?.uid }.getOrNull()

    override suspend fun idToken(forceRefresh: Boolean): String {
        val auth = auth()
        val user = auth.currentUser ?: try {
            auth.signInAnonymously().await().user
        } catch (e: Exception) {
            throw IllegalStateException(
                "Couldn't sign in to the Claude relay (${e.message ?: e.javaClass.simpleName}). " +
                    "Check that Anonymous sign-in is enabled in Firebase Authentication.",
                e
            )
        } ?: throw IllegalStateException("Couldn't sign in to the Claude relay.")
        val token = try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't get a sign-in token for the Claude relay (${e.message ?: e.javaClass.simpleName}).", e)
        }
        return token ?: throw IllegalStateException("Firebase returned an empty sign-in token.")
    }
}

/** Awaits a Play Services Task without adding the coroutines-play-services dependency. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
