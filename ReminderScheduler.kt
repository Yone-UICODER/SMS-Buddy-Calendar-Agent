package com.example.calendarsms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ReminderScheduler {

    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_MESSAGE = "extra_message"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    const val EXTRA_USER_ID = "extra_user_id"
    const val EXTRA_EVENT_ID = "extra_event_id"

    const val EXTRA_RECURRING = "extra_recurring"
    const val EXTRA_REPEAT_TYPE = "extra_repeat_type"
    const val EXTRA_REPEAT_DAY = "extra_repeat_day"
    const val EXTRA_REPEAT_DURATION = "extra_repeat_duration"
    const val EXTRA_REPEAT_DURATION_UNIT = "extra_repeat_duration_unit"
    const val EXTRA_REPEAT_END_DATE = "extra_repeat_end_date"

    const val EXTRA_START_DATE = "extra_start_date"


    const val EXTRA_OCCURRENCE_DATE = "extra_occurrence_date"
    const val EXTRA_OCCURRENCE_HOUR = "extra_occurrence_hour"
    const val EXTRA_OCCURRENCE_MINUTE = "extra_occurrence_minute"

    private const val REQUEST_CODE_BASE = 5000

    private val PH_TIME_ZONE =
        TimeZone.getTimeZone("Asia/Manila")

    private val DATE_FORMAT =
        SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.ENGLISH
        ).apply {
            timeZone = PH_TIME_ZONE
            isLenient = false
        }


    fun scheduleReminder(
        context: Context,
        title: String,
        message: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        userId: String,
        eventId: String,
        notificationId: Int = createNotificationId(
            title,
            year,
            month,
            day,
            hour,
            minute
        )
    ): Boolean {

        val reminderTime =
            Calendar.getInstance(
                PH_TIME_ZONE
            ).apply {

                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)

                set(
                    Calendar.HOUR_OF_DAY,
                    hour
                )

                set(
                    Calendar.MINUTE,
                    minute
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )
            }

        val occurrenceDate =
            DATE_FORMAT.format(
                reminderTime.time
            )

        return scheduleAtMillis(
            context = context,
            title = title,
            message = message,
            triggerTimeMillis =
                reminderTime.timeInMillis,
            notificationId = notificationId,
            userId = userId,
            eventId = eventId,
            recurring = false,
            occurrenceDate = occurrenceDate,
            occurrenceHour = hour,
            occurrenceMinute = minute
        )
    }
    fun scheduleRecurringReminder(
        context: Context,
        title: String,
        message: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        userId: String,
        eventId: String,
        repeatType: String,
        repeatDay: String,
        repeatDuration: Int,
        repeatDurationUnit: String,
        repeatEndDate: String,
        startDate: String,
        notificationId: Int = createNotificationId(
            title,
            year,
            month,
            day,
            hour,
            minute
        )
    ): Boolean {

        val reminderTime =
            Calendar.getInstance(
                PH_TIME_ZONE
            ).apply {

                set(
                    Calendar.YEAR,
                    year
                )

                set(
                    Calendar.MONTH,
                    month - 1
                )

                set(
                    Calendar.DAY_OF_MONTH,
                    day
                )

                set(
                    Calendar.HOUR_OF_DAY,
                    hour
                )

                set(
                    Calendar.MINUTE,
                    minute
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )
            }

        val occurrenceDate =
            DATE_FORMAT.format(
                reminderTime.time
            )

        return scheduleAtMillis(
            context = context,
            title = title,
            message = message,
            triggerTimeMillis =
                reminderTime.timeInMillis,
            notificationId = notificationId,
            userId = userId,
            eventId = eventId,
            recurring = true,
            repeatType = repeatType,
            repeatDay = repeatDay,
            repeatDuration = repeatDuration,
            repeatDurationUnit =
                repeatDurationUnit,
            repeatEndDate = repeatEndDate,
            startDate = startDate,
            occurrenceDate = occurrenceDate,
            occurrenceHour = hour,
            occurrenceMinute = minute
        )
    }

    /**
     * Checks whether this app can schedule exact alarms.
     */
    fun canScheduleExactAlarms(
        context: Context
    ): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {
            return true
        }

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        return alarmManager.canScheduleExactAlarms()
    }

    fun openExactAlarmSettings(
        context: Context
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            try {

                val intent =
                    Intent(
                        android.provider.Settings
                            .ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    ).apply {

                        data =
                            android.net.Uri.parse(
                                "package:${context.packageName}"
                            )

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                context.startActivity(
                    intent
                )

            } catch (_: Exception) {

                val intent =
                    Intent(
                        android.provider.Settings
                            .ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                try {

                    context.startActivity(
                        intent
                    )

                } catch (_: Exception) {
                    // Ignore.
                }
            }
        }
    }


    private fun scheduleAtMillis(
        context: Context,
        title: String,
        message: String,
        triggerTimeMillis: Long,
        notificationId: Int,
        userId: String = "",
        eventId: String = "",
        recurring: Boolean = false,
        repeatType: String = "",
        repeatDay: String = "",
        repeatDuration: Int = 0,
        repeatDurationUnit: String = "",
        repeatEndDate: String = "",
        startDate: String = "",
        occurrenceDate: String = "",
        occurrenceHour: Int = -1,
        occurrenceMinute: Int = -1
    ): Boolean {


        if (
            triggerTimeMillis <=
            System.currentTimeMillis()
        ) {
            return false
        }

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            if (
                !alarmManager.canScheduleExactAlarms()
            ) {
                return false
            }
        }

        val intent =
            Intent(
                context,
                ReminderAlarmReceiver::class.java
            ).apply {

                putExtra(
                    EXTRA_TITLE,
                    title
                )

                putExtra(
                    EXTRA_MESSAGE,
                    message
                )

                putExtra(
                    EXTRA_NOTIFICATION_ID,
                    notificationId
                )

                putExtra(
                    EXTRA_USER_ID,
                    userId
                )

                putExtra(
                    EXTRA_EVENT_ID,
                    eventId
                )


                putExtra(
                    EXTRA_RECURRING,
                    recurring
                )

                putExtra(
                    EXTRA_REPEAT_TYPE,
                    repeatType
                )

                putExtra(
                    EXTRA_REPEAT_DAY,
                    repeatDay
                )

                putExtra(
                    EXTRA_REPEAT_DURATION,
                    repeatDuration
                )

                putExtra(
                    EXTRA_REPEAT_DURATION_UNIT,
                    repeatDurationUnit
                )

                putExtra(
                    EXTRA_REPEAT_END_DATE,
                    repeatEndDate
                )

                putExtra(
                    EXTRA_START_DATE,
                    startDate
                )


                putExtra(
                    EXTRA_OCCURRENCE_DATE,
                    occurrenceDate
                )

                putExtra(
                    EXTRA_OCCURRENCE_HOUR,
                    occurrenceHour
                )

                putExtra(
                    EXTRA_OCCURRENCE_MINUTE,
                    occurrenceMinute
                )
            }


        val requestCode =
            REQUEST_CODE_BASE +
                    notificationId
                        .and(0x7FFFFFFF)

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return try {

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMillis,
                pendingIntent
            )

            true

        } catch (_: SecurityException) {

            false
        }
    }


    fun scheduleTestReminder(
        context: Context,
        secondsFromNow: Int = 30
    ): Boolean {

        val triggerTime =
            System.currentTimeMillis() +
                    (
                            secondsFromNow *
                                    1000L
                            )

        val notificationId =
            (
                    "test-$triggerTime"
                        .hashCode()
                        .and(0x7FFFFFFF)
                    )

        return scheduleAtMillis(
            context = context,
            title = "CalendarSMS Test",
            message = "Your reminder is working!",
            triggerTimeMillis = triggerTime,
            notificationId = notificationId
        )
    }


    private fun createNotificationId(
        title: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Int {

        return (
                "$title-$year-$month-$day-$hour-$minute"
                    .hashCode()
                    .and(0x7FFFFFFF)
                )
    }
}