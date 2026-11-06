package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import java.util.UUID

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * App Check initialization (originally for Firebase AI Logic; the Claude relay does not use it).
 * In debug builds, installs DebugAppCheckProviderFactory with a persistent debug token (created once,
 * or taken from the FIREBASE_APPCHECK_DEBUG_TOKEN intent extra).
 * In release builds, installs RecaptchaAppCheckProviderFactory using the site key resource.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            // Test builds always use the App Check debug provider with a token that stays the
            // same across updates. The token must be added once in the Firebase console
            // (App Check -> Apps -> Manage debug tokens); Settings shows it.
            // The reCAPTCHA provider is only for release builds: test builds sent tokens that
            // the server rejected ("Firebase App Check token is invalid").
            val fromIntent = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            val prefs = debugStore(context)
            if (!fromIntent.isNullOrBlank()) {
                prefs.edit().putString(DEBUG_SECRET_KEY, fromIntent).commit()
            } else if (prefs.getString(DEBUG_SECRET_KEY, null).isNullOrBlank()) {
                prefs.edit().putString(DEBUG_SECRET_KEY, UUID.randomUUID().toString()).commit()
            }
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun debugStore(context: Context) = context.getSharedPreferences(
    "$DEBUG_STORE_PREFIX${FirebaseApp.getInstance().persistenceKey}",
    Context.MODE_PRIVATE
)

/**
 * The App Check debug token this test build sends, so the user can register it in the Firebase
 * console. Null in release builds or before App Check is configured. Treat it like a password:
 * anyone with it can pass App Check for this Firebase project until it is removed in the console.
 */
fun appCheckDebugToken(context: Context): String? {
    if (!BuildConfig.DEBUG) return null
    return runCatching {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        debugStore(context).getString(DEBUG_SECRET_KEY, null)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
