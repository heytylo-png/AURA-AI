package com.example.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.TodoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Settings for reminders, kept on the phone. */
class ReminderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aura_reminders", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var hour: Int
        get() = prefs.getInt("hour", ReminderPlan.DEFAULT_HOUR).coerceIn(ReminderPlan.HOURS)
        set(v) = prefs.edit().putInt("hour", v.coerceIn(ReminderPlan.HOURS)).apply()

    /** The notification channel reminders use now: the default one, or one with the user's own sound. */
    var channelId: String
        get() = prefs.getString("channel_id", Reminders.CHANNEL_ID) ?: Reminders.CHANNEL_ID
        set(v) = prefs.edit().putString("channel_id", v).apply()

    /** The user's own reminder sound (a notification sound Aura added), or null for the phone's default. */
    var customSound: String?
        get() = prefs.getString("custom_sound", null)
        set(v) = prefs.edit().putString("custom_sound", v).apply()

    /** Reminders at or before this time have been handled (shown, or skipped while off). */
    var lastHandledAt: Long
        get() = prefs.getLong("last_handled_at", 0L)
        set(v) = prefs.edit().putLong("last_handled_at", v).apply()
}

object Reminders {
    const val CHANNEL_ID = "todo_reminders"
    const val EXTRA_OPEN_TAB = "aura_open_tab"
    const val TASKS_TAB = 3
    private const val ACTION_FIRE = "com.example.reminders.FIRE"
    private const val TAG = "AuraReminders"

    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Sets (or clears) the single alarm for the next reminder. Called whenever to-dos or the
     * reminder settings change, after a restart, and after each alarm.
     */
    fun sync(context: Context, openTodos: List<TodoEntity>, now: Long = System.currentTimeMillis()) {
        val prefs = ReminderPrefs(context)
        // First run, or reminders were off: nothing before now is owed.
        if (prefs.lastHandledAt == 0L || !prefs.enabled) prefs.lastHandledAt = now
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = firePendingIntent(context)
        val next = if (prefs.enabled) ReminderPlan.nextAt(openTodos, prefs.hour, now) else null
        if (next == null) {
            alarms.cancel(pending)
        } else {
            // Inexact on purpose: no "exact alarm" permission needed; Android may delay it a few minutes.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }

    /** Shows reminders that came due since the last check, then schedules the next one. */
    suspend fun handleDue(context: Context, now: Long = System.currentTimeMillis()) {
        val open = AppDatabase.getInstance(context).todoDao().getOpen()
        val prefs = ReminderPrefs(context)
        if (prefs.enabled && prefs.lastHandledAt != 0L) {
            val due = ReminderPlan.due(open, prefs.hour, prefs.lastHandledAt, now)
            if (due.isNotEmpty()) notify(context, due)
        }
        prefs.lastHandledAt = now
        sync(context, open, now)
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Creates the channel reminders use now (with the user's own sound, if they chose one). Returns its ID. */
    fun ensureChannel(context: Context): String {
        val prefs = ReminderPrefs(context)
        val id = prefs.channelId
        if (Build.VERSION.SDK_INT < 26) return id
        val manager = context.getSystemService(NotificationManager::class.java) ?: return id
        val sound = prefs.customSound?.let { android.net.Uri.parse(it) }
        manager.createNotificationChannel(
            NotificationChannel(id, "To-do reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder on the day a to-do is due."
                if (id != CHANNEL_ID && sound != null) {
                    setSound(
                        sound,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            }
        )
        return id
    }

    /**
     * Switches reminders to [sound] (null: the phone's default sound). A channel's sound can't change
     * once made, so each change gets a new channel and the old one is removed.
     */
    fun useSound(context: Context, sound: android.net.Uri?) {
        val prefs = ReminderPrefs(context)
        val old = prefs.channelId
        prefs.customSound = sound?.toString()
        prefs.channelId = if (sound == null) CHANNEL_ID else "${CHANNEL_ID}_sound_${System.currentTimeMillis()}"
        if (Build.VERSION.SDK_INT >= 26 && old != prefs.channelId) {
            context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(old)
        }
        ensureChannel(context)
    }

    private fun notify(context: Context, due: List<TodoEntity>) {
        if (!notificationsAllowed(context)) {
            Log.i(TAG, "${due.size} reminder(s) due, but notifications aren't allowed")
            return
        }
        val channel = ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TAB, TASKS_TAB)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // On the lock screen, show only that something is due, not the title.
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(if (due.size == 1) "A to-do is due today" else "${due.size} to-dos are due today")
            .build()
        val manager = NotificationManagerCompat.from(context)
        for (todo in due) {
            // A to-do's picture shows in its reminder (only when the phone is unlocked; see publicVersion).
            val picture = todo.imageName?.let { name ->
                com.example.data.model.MediaLibrary.file(context, name)?.let { com.example.data.model.ChatImages.thumbnail(it, 1024) }
            }
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_todo)
                .setContentTitle("Due today")
                .setContentText(todo.title)
                .apply {
                    if (picture != null) {
                        setLargeIcon(picture)
                        setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(todo.title))
                    }
                }
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
            try {
                manager.notify(notificationId(todo.id), notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission was removed", e)
                return
            }
        }
    }

    fun notificationId(todoId: Long): Int = 40_000 + (todoId % 1_000_000).toInt()

    internal fun runAsync(receiver: BroadcastReceiver, context: Context) {
        val pending = receiver.goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleDue(app)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't handle reminders", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The app's own alarm. Not exported: only Aura can trigger it. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.runAsync(this, context)
}

/** Restores the alarm after a restart, an app update, or a clock/time-zone change. */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        Reminders.runAsync(this, context)
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
