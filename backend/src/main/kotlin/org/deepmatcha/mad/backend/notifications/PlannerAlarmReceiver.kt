package org.deepmatcha.mad.backend.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import org.deepmatcha.mad.backend.school.*

/** One inexact wake-up for the earliest pending reminder across account-specific stores. */
class PlannerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(WAKE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) reconcile(context)
    }

    companion object {
        private const val WAKE = "org.deepmatcha.mad.PLANNER_ALARM"
        private const val CHANNEL = "planner"
        private val gson = Gson()
        private data class Delivery(val alert: PlannerAlert, val shown: Boolean = false)

        @Synchronized internal fun reconcile(context: Context, namespace: String? = null, plan: List<PlannerAlert> = emptyList()) {
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences("planner-notifications-v1", Context.MODE_PRIVATE)
            val manager = context.getSystemService(NotificationManager::class.java)
            val alarm = context.getSystemService(AlarmManager::class.java)
            val wake = PendingIntent.getBroadcast(context, 0, Intent(context, PlannerAlarmReceiver::class.java).setAction(WAKE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val records = prefs.all.mapValues { gson.fromJson(it.value as String, Delivery::class.java) }.toMutableMap()
            val edit = prefs.edit()
            if (namespace != null) {
                val desired = plan.filter { it.expires > now }.associateBy { "$namespace/${it.key}" }
                for (key in records.keys.filter { it.startsWith("$namespace/") && it !in desired }) {
                    manager.cancel(key, 1)
                    records.remove(key)
                    edit.remove(key)
                }
                for ((key, alert) in desired) {
                    val current = records[key]
                    if (current?.alert != alert) {
                        val next = Delivery(alert, current?.shown == true)
                        records[key] = next
                        edit.putString(key, gson.toJson(next))
                    }
                }
            }
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "일정 및 과제", NotificationManager.IMPORTANCE_DEFAULT))
            val permitted = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
                (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            var earliest = Long.MAX_VALUE
            for ((key, delivery) in records) {
                val alert = delivery.alert
                when {
                    alert.expires <= now -> {
                        manager.cancel(key, 1)
                        edit.remove(key)
                    }
                    delivery.shown -> Unit
                    alert.at > now -> earliest = minOf(earliest, alert.at)
                    permitted -> {
                        val label = when (alert.kind) {
                            AlertKind.DUE -> "과제 마감 알림"
                            AlertKind.AVAILABLE -> "과제 이용 가능"
                            AlertKind.NEW -> "새 과제"
                            AlertKind.CLASS_CHANGE -> "수업 방식 변경"
                            AlertKind.APPROVAL -> "날짜 변경 승인 요청"
                        }
                        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(label).setContentText(alert.title).setAutoCancel(true).setOnlyAlertOnce(true)
                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                            builder.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE))
                        }
                        try {
                            manager.notify(key, 1, builder.build())
                            edit.putString(key, gson.toJson(delivery.copy(shown = true)))
                        } catch (_: SecurityException) { /* Permission can change during delivery. */ }
                    }
                }
            }
            edit.commit()
            if (earliest == Long.MAX_VALUE) alarm.cancel(wake)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, wake)
        }
    }
}
