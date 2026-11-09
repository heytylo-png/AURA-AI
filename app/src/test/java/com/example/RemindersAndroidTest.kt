package com.example

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.TodoEntity
import com.example.data.model.TodoRules
import com.example.reminders.ReminderPlan
import com.example.reminders.ReminderPrefs
import com.example.reminders.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The alarm follows the to-do list: set for the next due reminder, cleared when nothing is due or reminders are off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemindersAndroidTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val day = TodoRules.today() + 2

    @Test fun alarmSetForNextReminderAndClearedWhenNothingDue() {
        ReminderPrefs(app).enabled = true
        Reminders.sync(app, listOf(TodoEntity(id = 1, title = "Pay rent", dueEpochDay = day)))
        val next = alarms.nextScheduledAlarm
        assertNotNull(next)
        assertEquals(ReminderPlan.reminderAt(day, ReminderPrefs(app).hour), next!!.triggerAtTime)

        Reminders.sync(app, emptyList())
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test fun turningRemindersOffClearsAlarm() {
        val prefs = ReminderPrefs(app)
        prefs.enabled = false
        Reminders.sync(app, listOf(TodoEntity(id = 1, title = "Pay rent", dueEpochDay = day)))
        assertNull(alarms.nextScheduledAlarm)
        prefs.enabled = true
    }

    @Test fun channelExistsAndNotificationIdsAreStable() {
        Reminders.ensureChannel(app)
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel(Reminders.CHANNEL_ID))
        assertEquals(Reminders.notificationId(7), Reminders.notificationId(7))
    }
}
