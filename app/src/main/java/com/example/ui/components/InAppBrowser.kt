package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens web links in a browser view on top of Aura (a Custom Tab), so the user stays in the app and
 * returns to the chat with the back button. The page is shown by the phone's browser, which keeps its
 * own sign-ins and protections; Aura can't see or change the page. Phones without a browser that
 * supports this open the link in their browser app instead.
 */
object InAppBrowser {
    /** Only web addresses are opened; returns false if nothing could open the link. */
    fun open(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()
        if (context !is android.app.Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tab.launchUrl(context, uri)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
