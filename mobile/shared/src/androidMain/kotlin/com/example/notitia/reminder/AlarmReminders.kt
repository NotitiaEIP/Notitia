package com.example.notitia.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.example.notitia.shared.R
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The keys of the actions a touched notification opens.
const val EXTRA_REMINDER_ACTIONS = "com.example.notitia.extra.REMINDER_ACTIONS"

private const val CHANNEL = "reminders"
private const val SCHEME = "notitia-reminder"
private const val NOTIFICATION_ID = 1

@Serializable
private class Alarm(val at: Long, val message: String, val actions: List<String>)

// AlarmManager rather than WorkManager: a reminder belongs to a time of day, and an inexact alarm allowed in Doze rings
// within minutes of it without asking for exact alarms. Android forgets alarms on a reboot or an update, so the ones
// scheduled are kept in preferences too, which hold no word of the meetings.
class AlarmReminderScheduler(context: Context) : ReminderScheduler {
    private val context = context.applicationContext
    private val alarms = alarms(this.context)

    // A rung alarm leaves the preferences, but its notification may still be up.
    override suspend fun pending(): Set<String> {
        val shown = context.getSystemService<NotificationManager>()?.activeNotifications.orEmpty()
            .filter { it.id == NOTIFICATION_ID }
            .mapNotNull { it.tag }
        return alarms.all.keys + shown
    }

    override fun schedule(reminder: Reminder, message: String) {
        val alarm = Alarm(reminder.at.toEpochMilliseconds(), message, reminder.actions.map { it.key })
        alarms.edit { putString(reminder.key, Json.encodeToString(alarm)) }
        ring(context, reminder.key, alarm.at)
    }

    override fun cancel(key: String) {
        alarms.edit { remove(key) }
        context.getSystemService<AlarmManager>()?.cancel(ringIntent(context, key))
        NotificationManagerCompat.from(context).cancel(key, NOTIFICATION_ID)
    }
}

// A reminder taken back after its alarm was set is no longer in the preferences: it then shows nothing.
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.data?.schemeSpecificPart ?: return
        val alarms = alarms(context)
        val alarm = alarms.getString(key, null)?.let(::decode) ?: return
        alarms.edit { remove(key) }
        notify(context, key, alarm)
    }
}

private val RESCHEDULE_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIMEZONE_CHANGED,
)

// Android forgets alarms on a reboot or an update, and keeps them at the same instant when the time zone changes: each
// one is set again at 9 in the phone's zone now. A reminder whose time passed meanwhile rings at once, as a calendar does.
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val alarms = alarms(context)
        val zone = TimeZone.currentSystemDefault()
        alarms.all.forEach { (key, value) ->
            val alarm = (value as? String)?.let(::decode) ?: return@forEach
            val at = reminderAt(key, zone)?.toEpochMilliseconds() ?: alarm.at
            if (at != alarm.at) alarms.edit { putString(key, Json.encodeToString(Alarm(at, alarm.message, alarm.actions))) }
            ring(context, key, at)
        }
    }
}

private fun alarms(context: Context): SharedPreferences = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

private fun decode(value: String): Alarm? = runCatching { Json.decodeFromString<Alarm>(value) }.getOrNull()

private fun ring(context: Context, key: String, at: Long) {
    context.getSystemService<AlarmManager>()?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(context, key))
}

// The key in the data, not in an extra: Android tells two pending intents apart by their data, never by their extras.
private fun ringIntent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, ReminderReceiver::class.java).setData(Uri.fromParts(SCHEME, key, null)),
    PendingIntent.FLAG_IMMUTABLE,
)

// Only the count, never the action's words: the notification shows on the lock screen and on a watch.
private fun notify(context: Context, key: String, alarm: Alarm) {
    val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    val manager = NotificationManagerCompat.from(context)
    manager.createNotificationChannel(
        NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.reminder_channel))
            .setDescription(context.getString(R.string.reminder_channel_description))
            .build(),
    )
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.notification_icon)
        .setColor(context.getColor(R.color.accent))
        .setContentTitle(alarm.message)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .setContentIntent(openIntent(context, key, alarm.actions))
        .build()
    manager.notify(key, NOTIFICATION_ID, notification)
}

private fun openIntent(context: Context, key: String, actions: List<String>): PendingIntent? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    intent.putExtra(EXTRA_REMINDER_ACTIONS, actions.toTypedArray())
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
        context,
        key.hashCode(),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
