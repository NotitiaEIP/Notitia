package com.example.notitia.reminder

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

private const val ACTIONS = "actions"
private const val THREAD = "reminders"

// iOS keeps what it schedules across a restart, so nothing is kept on the side as on Android.
class NotificationReminderScheduler : ReminderScheduler {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun pending(): Set<String> = suspendCoroutine { done ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            done.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>().map { it.identifier }.toSet())
        }
    }

    // The day and hour on the calendar rather than a countdown: a clock set forward or back still rings at 9.
    override fun schedule(reminder: Reminder, message: String) {
        val content = UNMutableNotificationContent()
        content.setBody(message)
        content.setSound(UNNotificationSound.defaultSound)
        content.setThreadIdentifier(THREAD)
        content.setUserInfo(mapOf<Any?, Any?>(ACTIONS to reminder.actions.map { it.key }))
        val at = reminder.at.toLocalDateTime(TimeZone.currentSystemDefault())
        val day = NSDateComponents().apply {
            year = at.year.toLong()
            month = at.month.number.toLong()
            this.day = at.day.toLong()
            hour = at.hour.toLong()
            minute = at.minute.toLong()
        }
        val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(day, repeats = false)
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(reminder.key, content, trigger),
            withCompletionHandler = null,
        )
    }

    override fun cancel(key: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(key))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(key))
    }
}

// The notification center keeps its delegate weakly: this one lives as long as the app.
private val center = ReminderCenter()

val reminderTaps: StateFlow<ReminderTap?> get() = center.taps

// Called by iOSApp before launch ends, so a touch that starts the app is not lost.
fun listenToReminders() {
    UNUserNotificationCenter.currentNotificationCenter().delegate = center
}

private class ReminderCenter : NSObject(), UNUserNotificationCenterDelegateProtocol {
    private val touched = MutableStateFlow<ReminderTap?>(null)
    val taps: StateFlow<ReminderTap?> = touched.asStateFlow()

    // Shown with the app open too, as a banner, as Reminders does.
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound,
        )
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val keys = didReceiveNotificationResponse.notification.request.content.userInfo[ACTIONS] as? List<*>
        keys?.filterIsInstance<String>()?.let { touched.value = ReminderTap(it) }
        withCompletionHandler()
    }
}
