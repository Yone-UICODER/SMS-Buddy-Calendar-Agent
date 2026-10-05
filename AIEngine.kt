package com.example.calendarsms.ai

import android.util.Log
import com.example.calendarsms.GeminiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern
import com.example.calendarsms.GeminiResult

data class AIResult(
    val intent: String = "UNKNOWN",
    val title: String = "",
    val date: String = "",
    val time: String = "",
    val durationMinutes: Int = 60,
    val priority: Int = 2,
    val recurring: Boolean = false,
    val repeatType: String = "",
    val repeatDay: String = "",
    val repeatDuration: Int = 0,
    val repeatDurationUnit: String = "",
    val repeatEndDate: String = "",
    val availabilityStart: String = "",
    val availabilityEnd: String = "",
    val response: String = ""
)

data class AIPendingContext(
    val intent: String = "",
    val title: String = "",
    val date: String = "",
    val time: String = "",
    val durationMinutes: Int = 60,
    val priority: Int = 2,
    val recurring: Boolean = false,
    val repeatType: String = "",
    val repeatDay: String = "",
    val repeatDuration: Int = 0,
    val repeatDurationUnit: String = "",
    val repeatEndDate: String = "",
    val awaitingConfirmation: Boolean = false,
    val conflictAction: String = "",
    val conflictEventId: String = "",
    val conflictEventTitle: String = "",
    val conflictEventTime: String = "",
    val conflictEventPriority: Int = 2
)

class AIEngine {

    companion object {

        private const val TAG = "AIEngine"

        const val CREATE_EVENT = "CREATE_EVENT"
        const val CREATE_RECURRING_EVENT = "CREATE_RECURRING_EVENT"
        const val LIST_EVENTS = "LIST_EVENTS"
        const val DELETE_EVENT = "DELETE_EVENT"
        const val UPDATE_EVENT = "UPDATE_EVENT"
        const val CANCEL_WORKFLOW = "CANCEL_WORKFLOW"
        const val FIND_FREE_TIME = "FIND_FREE_TIME"
        const val OPTIMIZE_SCHEDULE = "OPTIMIZE_SCHEDULE"
        const val CHAT = "CHAT"
        const val UNKNOWN = "UNKNOWN"

        private const val PH_TIMEZONE = "Asia/Manila"

        private val PH_TIME: TimeZone =
            TimeZone.getTimeZone(PH_TIMEZONE)

        private val CANCEL_WORDS = setOf(
            "stop",
            "cancel",
            "nevermind",
            "never mind",
            "exit",
            "abort",
            "quit",
            "clear"
        )

        private val CONFIRM_WORDS = setOf(
            "yes",
            "yes please",
            "yeah",
            "yep",
            "yup",
            "okay",
            "ok",
            "sure",
            "do it",
            "schedule it",
            "sounds good",
            "confirm",
            "go ahead"
        )

        private val REJECT_WORDS = setOf(
            "no",
            "nope",
            "nah",
            "don't",
            "do not",
            "cancel it",
            "never mind",
            "nevermind"
        )
    }

    private val geminiService = GeminiService()

    fun process(
        input: String,
        pendingContext: AIPendingContext? = null
    ): AIResult {

        if (input.isBlank()) {
            return AIResult(
                intent = UNKNOWN,
                response = "I didn't catch that. What would you like me to do?"
            )
        }

        val normalized = normalize(input)

        if (isExactCancel(normalized)) {
            return AIResult(
                intent = CANCEL_WORKFLOW,
                response = "Okay, I've cancelled that."
            )
        }

        if (pendingContext?.awaitingConfirmation == true) {

            if (isConfirmation(normalized)) {
                return buildConfirmationResult(pendingContext)
            }

            if (isRejection(normalized)) {
                return AIResult(
                    intent = CANCEL_WORKFLOW,
                    response = "No problem. I won't make that change."
                )
            }

            return AIResult(
                intent = UNKNOWN,
                response = ""
            )
        }

        if (pendingContext != null) {

            val followUp = processFollowUp(
                input = input,
                pendingContext = pendingContext
            )

            if (followUp != null) {
                return followUp
            }
        }

        return processLocal(
            input = input,
            pendingContext = pendingContext
        )
    }

    fun processAsync(
        input: String,
        pendingContext: AIPendingContext? = null,
        callback: (AIResult) -> Unit
    ) {

        CoroutineScope(Dispatchers.Default).launch {

            try {

                val localResult =
                    process(
                        input = input,
                        pendingContext = pendingContext
                    )

                if (localResult.intent != UNKNOWN) {

                    Log.d(
                        TAG,
                        "Local AI understood request: ${localResult.intent}"
                    )

                    withContext(Dispatchers.Main) {
                        callback(localResult)
                    }

                    return@launch
                }

                Log.d(
                    TAG,
                    "Local AI returned UNKNOWN. Sending to Gemini."
                )

                val geminiResult =
                    processWithGemini(
                        input = input,
                        pendingContext = pendingContext
                    )

                if (geminiResult != null) {

                    Log.d(
                        TAG,
                        "Gemini returned intent: ${geminiResult.intent}"
                    )

                    withContext(Dispatchers.Main) {
                        callback(geminiResult)
                    }

                    return@launch
                }

                Log.w(
                    TAG,
                    "Gemini returned null."
                )

                withContext(Dispatchers.Main) {

                    callback(
                        AIResult(
                            intent = UNKNOWN,
                            response =
                                "I'm temporarily unable to understand that. " +
                                        "You can still ask me to schedule, " +
                                        "change, delete, or check your events."
                        )
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "AI processing failed.",
                    e
                )

                withContext(Dispatchers.Main) {

                    callback(
                        AIResult(
                            intent = UNKNOWN,
                            response =
                                "Sorry, I couldn't process that right now. " +
                                        "You can still use the scheduling features."
                        )
                    )
                }
            }
        }
    }

    private suspend fun processWithGemini(
        input: String,
        pendingContext: AIPendingContext?
    ): AIResult? {

        val today = getToday()

        val pendingJson =
            if (pendingContext == null) {
                "{}"
            } else {
                """
            {
              "intent": "${escapeJson(pendingContext.intent)}",
              "title": "${escapeJson(pendingContext.title)}",
              "date": "${escapeJson(pendingContext.date)}",
              "time": "${escapeJson(pendingContext.time)}",
              "durationMinutes": ${pendingContext.durationMinutes},
              "priority": ${pendingContext.priority},
              "recurring": ${pendingContext.recurring},
              "repeatType": "${escapeJson(pendingContext.repeatType)}",
              "repeatDay": "${escapeJson(pendingContext.repeatDay)}",
              "repeatDuration": ${pendingContext.repeatDuration},
              "repeatDurationUnit": "${escapeJson(pendingContext.repeatDurationUnit)}",
              "repeatEndDate": "${escapeJson(pendingContext.repeatEndDate)}",
              "awaitingConfirmation": ${pendingContext.awaitingConfirmation},
              "conflictAction": "${escapeJson(pendingContext.conflictAction)}",
              "conflictEventId": "${escapeJson(pendingContext.conflictEventId)}",
              "conflictEventTitle": "${escapeJson(pendingContext.conflictEventTitle)}",
              "conflictEventTime": "${escapeJson(pendingContext.conflictEventTime)}",
              "conflictEventPriority": ${pendingContext.conflictEventPriority}
            }
            """.trimIndent()
            }

        val prompt = """
You are the natural-language intelligence of a calendar scheduling assistant.

Your job is to understand the user's message and convert it into a structured
calendar command when a calendar action is requested.

You are also responsible for NATURAL CONVERSATION.

IMPORTANT:

You do NOT have direct access to Firestore.

You do NOT save events.

You do NOT delete events.

You do NOT update events.

You do NOT schedule reminders.

The Android application performs those actions after you return the JSON.

CURRENT DATE:
$today

TIMEZONE:
Asia/Manila

USER MESSAGE:
${escapeJson(input)}

PENDING CONTEXT:
$pendingJson

ALLOWED INTENTS:

CREATE_EVENT
CREATE_RECURRING_EVENT
LIST_EVENTS
DELETE_EVENT
UPDATE_EVENT
FIND_FREE_TIME
OPTIMIZE_SCHEDULE
CANCEL_WORKFLOW
CHAT
UNKNOWN


DATE RULES


Resolve relative dates using the current date.

Recognize:

today
tomorrow
the day after tomorrow
Monday
Tuesday
Wednesday
Thursday
Friday
Saturday
Sunday
next Monday
next Tuesday
next Wednesday
next Thursday
next Friday
next Saturday
next Sunday
this Monday
this Tuesday
this Wednesday
this Thursday
this Friday
this Saturday
this Sunday

Return:

yyyy-MM-dd

If a weekday is given without "this" or "next",
use the next occurrence of that weekday.

Do not invent a date if the user did not provide or imply one.


MONTH/DAY RULE


If a month and day are given without a year, use the next occurrence.

For example, when today is 2026-10-04:

September 30 -> 2027-09-30
October 5 -> 2026-10-05
December 25 -> 2026-12-25
January 5 -> 2027-01-05

Explicit years must always be respected.


TIME RULES


Return HH:mm.

Examples:

9 AM -> 09:00
9:30 AM -> 09:30
2 PM -> 14:00
2:30 PM -> 14:30
noon -> 12:00
midnight -> 00:00

Never invent a time.


DURATION


Return durationMinutes.

30 minutes -> 30
45 minutes -> 45
1 hour -> 60
2 hours -> 120
1.5 hours -> 90
2 and a half hours -> 150

If unspecified, use 60.


PRIORITY


1 = Low
2 = Normal
3 = High
4 = Urgent

low priority -> 1
normal priority -> 2
important -> 3
high priority -> 3
urgent -> 4
emergency -> 4
critical -> 4

If unspecified, use 2.

Preserve pending priority when appropriate.


RECURRING


Recognize:

daily
every day
weekday
weekdays
every weekday
weekly
every week
every Monday
every Tuesday
every Wednesday
every Thursday
every Friday
every Saturday
every Sunday
monthly
every month

Use:

DAILY
WEEKDAYS
WEEKLY
MONTHLY

For a specific weekday, set repeatDay.


RECURRING DURATION


Recognize:

for 10 days
for 4 weeks
for 3 months
for 2 years

Return:

repeatDuration = number

repeatDurationUnit =
days
weeks
months
years

If an explicit ending date is given, return repeatEndDate as yyyy-MM-dd.


FREE TIME


Use FIND_FREE_TIME for requests such as:

am I free tomorrow?
are you free tomorrow?
when am I free?
when am I available?
do I have anything tomorrow?
what is free tomorrow afternoon?

Availability:

morning = 08:00-12:00
afternoon = 12:00-17:00
evening = 17:00-21:00


OPTIMIZE SCHEDULE


Use OPTIMIZE_SCHEDULE when the user wants the application
to find the best available time.

Examples:

find the best time for me tomorrow
when should I schedule my study session?
find the best time for a meeting
optimize my schedule tomorrow
fit a 2 hour study session into tomorrow
when is the best time for me to study?

Extract:

title
date
time if explicitly provided
duration
priority

If no date is provided:
date = ""

If no time is provided:
time = ""

Do not claim that anything has already been saved.


LIST


Use LIST_EVENTS for:

what am I doing tomorrow?
what do I have tomorrow?
show my schedule
what are my events?
what is on my calendar?
do I have anything Friday?


DELETE


Use DELETE_EVENT for:

delete my meeting tomorrow
remove the dentist appointment
cancel my project meeting

"cancel" by itself means CANCEL_WORKFLOW.


UPDATE


Use UPDATE_EVENT for:

move my meeting to 3 PM
change my meeting tomorrow to 4
make the meeting high priority
move the appointment to Friday
make it 2 hours

Preserve pending information.


FOLLOW-UP


If the previous request was:

Schedule a project meeting at 2 PM

and the user says:

tomorrow

preserve:

title = project meeting
time = 14:00

and add:

date = tomorrow.

If the user says:

actually make that 3 PM

preserve:

title
date
duration
priority
recurrence

and change:

time = 15:00.

If the user says:

make it high priority

change:

priority = 3

If the user says:

make it 2 hours

change:

durationMinutes = 120

Never erase known pending information unless explicitly changed.


CONVERSATION / NATURAL LANGUAGE


Natural conversation is YOUR responsibility.

If the user's message is ordinary conversation, a greeting,
a casual remark, a general question, a capability question,
an identity question, or a planning discussion that does not
contain a concrete calendar action, use CHAT.

Examples:

"Hey"
"Hi"
"Hello"
"Good morning"
"How are you?"
"Thanks!"
"You're helpful."
"Who are you?"
"What can you do?"
"What can you help me with?"
"Can you help me plan my day?"
"Can you help me organize my day?"
"I'm having a busy day."
"Tell me something interesting."

For these messages, provide a short, natural response
in the "response" field.

Do NOT turn ordinary conversation into:

CREATE_EVENT
CREATE_RECURRING_EVENT
LIST_EVENTS
DELETE_EVENT
UPDATE_EVENT
FIND_FREE_TIME
OPTIMIZE_SCHEDULE

unless the user actually requests a calendar or scheduling action.

IMPORTANT DISTINCTIONS:

"Can you help me plan my day?"
-> CHAT

"Can you help me plan a meeting tomorrow?"
-> CREATE_EVENT

"Hey, are you free tomorrow?"
-> FIND_FREE_TIME

"What do I have tomorrow?"
-> LIST_EVENTS

"Schedule a meeting tomorrow at 2 PM."
-> CREATE_EVENT

"Tell me something interesting."
-> CHAT

"What can you do?"
-> CHAT

If the user asks a general question unrelated to calendar
scheduling, use CHAT and answer naturally when possible.

Do not claim access to information that the application
has not provided to you.


RESPONSE


Keep response short and natural.

For CHAT responses, respond conversationally.

For calendar commands, describe what the application should do,
but do not claim that an action has already happened.

Never claim that an event was saved, deleted, or updated.

The Android application performs those actions.


OUTPUT


Return ONLY valid JSON.

Use exactly:

{
  "intent": "",
  "title": "",
  "date": "",
  "time": "",
  "durationMinutes": 60,
  "priority": 2,
  "recurring": false,
  "repeatType": "",
  "repeatDay": "",
  "repeatDuration": 0,
  "repeatDurationUnit": "",
  "repeatEndDate": "",
  "availabilityStart": "",
  "availabilityEnd": "",
  "response": ""
}
""".trimIndent()

        return try {

            val geminiResult =
                geminiService.askGemini(prompt)

            when (geminiResult) {

                is GeminiResult.Success -> {

                    Log.d(
                        TAG,
                        "Gemini processing succeeded."
                    )

                    parseGeminiResult(
                        input = input,
                        rawResponse = geminiResult.text,
                        pendingContext = pendingContext
                    )
                }

                is GeminiResult.QuotaExceeded -> {

                    val retryAfterMillis =
                        geminiResult.retryAfterMillis

                    val quotaMessage =
                        buildGeminiQuotaMessage(
                            retryAfterMillis
                        )

                    Log.w(
                        TAG,
                        "Gemini quota exceeded. " +
                                "Retry in ${formatQuotaDuration(retryAfterMillis)}"
                    )

                    AIResult(
                        intent = "CHAT",
                        response = quotaMessage
                    )
                }

                is GeminiResult.Error -> {

                    Log.e(
                        TAG,
                        "Gemini returned an error: " +
                                geminiResult.message
                    )

                    AIResult(
                        intent = "CHAT",
                        response =
                            "I'm temporarily unable to use my " +
                                    "natural conversation AI right now. " +
                                    "You can still use me for scheduling, " +
                                    "checking, updating, deleting, and " +
                                    "organizing your events."
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gemini request failed",
                e
            )

            AIResult(
                intent = "CHAT",
                response =
                    "I'm temporarily unable to use my " +
                            "natural conversation AI right now. " +
                            "You can still use me for scheduling, " +
                            "checking, updating, deleting, and " +
                            "organizing your events."
            )
        }
    }

    private fun buildGeminiQuotaMessage(
        retryAfterMillis: Long
    ): String {

        val duration = formatQuotaDuration(retryAfterMillis)

        return """
        Smart Buddy's advanced conversation feature is temporarily unavailable.

        It should be available again in about $duration.

        In the meantime, you can still use Smart Buddy to:
        • Schedule events
        • Create recurring events
        • Check your schedule
        • Find available time
        • Update or reschedule events
        • Delete events
        • Optimize your schedule

        Your calendar and scheduling features are still working normally.
    """.trimIndent()
    }

    private fun formatQuotaDuration(
        millis: Long
    ): String {

        val totalSeconds =
            (millis + 999L) / 1000L

        val hours =
            totalSeconds / 3600L

        val minutes =
            (totalSeconds % 3600L) / 60L

        val seconds =
            totalSeconds % 60L

        return when {

            hours > 0L -> {

                when {
                    minutes > 0L ->
                        "$hours hour${if (hours != 1L) "s" else ""} " +
                                "$minutes minute${if (minutes != 1L) "s" else ""}"

                    else ->
                        "$hours hour${if (hours != 1L) "s" else ""}"
                }
            }

            minutes > 0L ->
                "$minutes minute${if (minutes != 1L) "s" else ""}"

            else ->
                "$seconds second${if (seconds != 1L) "s" else ""}"
        }
    }

    private fun parseGeminiResult(
        input: String,
        rawResponse: String,
        pendingContext: AIPendingContext?
    ): AIResult? {

        return try {

            var cleaned = rawResponse.trim()

            cleaned = cleaned
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val start = cleaned.indexOf("{")
            val end = cleaned.lastIndexOf("}")

            if (start < 0 || end <= start) {

                Log.e(
                    TAG,
                    "Gemini response did not contain JSON."
                )

                return null
            }

            cleaned =
                cleaned.substring(
                    start,
                    end + 1
                )

            val json =
                JSONObject(cleaned)

            val rawIntent =
                json.optString(
                    "intent",
                    UNKNOWN
                ).uppercase(Locale.US)

            val intent =
                when (rawIntent) {

                    CREATE_EVENT ->
                        CREATE_EVENT

                    CREATE_RECURRING_EVENT ->
                        CREATE_RECURRING_EVENT

                    LIST_EVENTS ->
                        LIST_EVENTS

                    DELETE_EVENT ->
                        DELETE_EVENT

                    UPDATE_EVENT ->
                        UPDATE_EVENT

                    CANCEL_WORKFLOW ->
                        CANCEL_WORKFLOW

                    FIND_FREE_TIME ->
                        FIND_FREE_TIME

                    OPTIMIZE_SCHEDULE ->
                        OPTIMIZE_SCHEDULE

                    CHAT ->
                        CHAT

                    else ->
                        UNKNOWN
                }

            val explicitDate =
                extractDate(input)

            val explicitTime =
                extractTime(input)

            val explicitDuration =
                extractDurationMinutes(input)

            val explicitPriority =
                extractPriority(
                    normalize(input)
                )

            val geminiTitle =
                json.optString(
                    "title",
                    ""
                ).trim()

            val geminiDate =
                normalizeDate(
                    json.optString(
                        "date",
                        ""
                    )
                )

            val geminiTime =
                normalizeTime(
                    json.optString(
                        "time",
                        ""
                    )
                )

            val finalDate =
                explicitDate.ifBlank {
                    geminiDate
                }

            val finalTime =
                explicitTime.ifBlank {
                    geminiTime
                }

            val finalDuration =
                explicitDuration
                    ?: if (json.has("durationMinutes")) {
                        json.optInt(
                            "durationMinutes",
                            60
                        ).coerceIn(
                            15,
                            1440
                        )
                    } else {
                        pendingContext?.durationMinutes
                            ?: 60
                    }

            val finalPriority =
                explicitPriority
                    ?: if (json.has("priority")) {
                        json.optInt(
                            "priority",
                            pendingContext?.priority ?: 2
                        ).coerceIn(
                            1,
                            4
                        )
                    } else {
                        pendingContext?.priority ?: 2
                    }

            val repeatType =
                json.optString(
                    "repeatType",
                    ""
                ).uppercase(Locale.US)

            val repeatDay =
                json.optString(
                    "repeatDay",
                    ""
                ).trim()

            val repeatDuration =
                json.optInt(
                    "repeatDuration",
                    0
                ).coerceAtLeast(0)

            val repeatDurationUnit =
                json.optString(
                    "repeatDurationUnit",
                    ""
                ).lowercase(Locale.US)

            val repeatEndDate =
                normalizeDate(
                    json.optString(
                        "repeatEndDate",
                        ""
                    )
                )

            val availabilityStart =
                normalizeTime(
                    json.optString(
                        "availabilityStart",
                        ""
                    )
                )

            val availabilityEnd =
                normalizeTime(
                    json.optString(
                        "availabilityEnd",
                        ""
                    )
                )

            val result =
                AIResult(
                    intent = intent,
                    title = geminiTitle,
                    date = finalDate,
                    time = finalTime,
                    durationMinutes = finalDuration,
                    priority = finalPriority,
                    recurring =
                        json.optBoolean(
                            "recurring",
                            false
                        ) ||
                                intent ==
                                CREATE_RECURRING_EVENT,
                    repeatType = repeatType,
                    repeatDay = repeatDay,
                    repeatDuration = repeatDuration,
                    repeatDurationUnit =
                        repeatDurationUnit,
                    repeatEndDate = repeatEndDate,
                    availabilityStart = availabilityStart,
                    availabilityEnd = availabilityEnd,
                    response =
                        json.optString(
                            "response",
                            ""
                        ).trim()
                )

            mergeWithPending(
                result = result,
                pending = pendingContext,
                input = input
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to parse Gemini JSON.",
                e
            )

            null
        }
    }

    private fun mergeWithPending(
        result: AIResult,
        pending: AIPendingContext?,
        input: String = ""
    ): AIResult {

        if (pending == null) {
            return result
        }

        val schedulingIntent =
            result.intent == CREATE_EVENT ||
                    result.intent == CREATE_RECURRING_EVENT ||
                    result.intent == UPDATE_EVENT ||
                    result.intent == OPTIMIZE_SCHEDULE

        if (!schedulingIntent) {
            return result
        }

        val explicitDuration =
            if (input.isNotBlank()) {
                extractDurationMinutes(input)
            } else {
                null
            }

        val explicitPriority =
            if (input.isNotBlank()) {
                extractPriority(
                    normalize(input)
                )
            } else {
                null
            }

        val finalDuration =
            explicitDuration
                ?: if (result.durationMinutes != 60) {
                    result.durationMinutes
                } else {
                    pending.durationMinutes
                }

        val finalPriority =
            explicitPriority
                ?: if (
                    result.priority != 2 ||
                    pending.priority == 2
                ) {
                    result.priority
                } else {
                    pending.priority
                }

        return result.copy(

            title =
                result.title.ifBlank {
                    pending.title
                },

            date =
                result.date.ifBlank {
                    pending.date
                },

            time =
                result.time.ifBlank {
                    pending.time
                },

            durationMinutes =
                finalDuration,

            priority =
                finalPriority,

            recurring =
                result.recurring ||
                        pending.recurring,

            repeatType =
                result.repeatType.ifBlank {
                    pending.repeatType
                },

            repeatDay =
                result.repeatDay.ifBlank {
                    pending.repeatDay
                },

            repeatDuration =
                if (result.repeatDuration > 0) {
                    result.repeatDuration
                } else {
                    pending.repeatDuration
                },

            repeatDurationUnit =
                result.repeatDurationUnit.ifBlank {
                    pending.repeatDurationUnit
                },

            repeatEndDate =
                result.repeatEndDate.ifBlank {
                    pending.repeatEndDate
                }
        )
    }

    private fun processLocal(
        input: String,
        pendingContext: AIPendingContext? = null
    ): AIResult {

        val normalized =
            normalize(input)

        if (isExactCancel(normalized)) {

            return AIResult(
                intent = CANCEL_WORKFLOW,
                response = "Okay, I've cancelled that."
            )
        }

        if (
            pendingContext != null &&
            !pendingContext.awaitingConfirmation
        ) {

            val followUp =
                processFollowUp(
                    input = input,
                    pendingContext = pendingContext
                )

            if (followUp != null) {
                return followUp
            }
        }

        val priority =
            extractPriority(normalized)

        if (looksLikeOptimize(normalized)) {

            return parseOptimizeSchedule(
                input = input,
                priority = priority,
                pendingContext = pendingContext
            )
        }

        val recurringResult =
            parseRecurringEvent(
                input = input,
                normalized = normalized,
                priority = priority
            )

        if (recurringResult != null) {

            return mergeWithPending(
                result = recurringResult,
                pending = pendingContext,
                input = input
            )
        }

        if (looksLikeDelete(normalized)) {

            return parseDeleteEvent(input)
        }

        if (looksLikeUpdate(normalized)) {

            return parseUpdateEvent(
                input = input,
                pendingContext = pendingContext
            )
        }

        if (looksLikeFreeTime(normalized)) {

            return parseFreeTime(input)
        }

        if (looksLikeList(normalized)) {

            return parseListEvents(input)
        }

        if (looksLikeCreate(normalized)) {

            return parseCreateEvent(
                input = input,
                priority = priority
            )
        }

        return AIResult(
            intent = UNKNOWN,
            response = ""
        )
    }

    private fun parseOptimizeSchedule(
        input: String,
        priority: Int?,
        pendingContext: AIPendingContext?
    ): AIResult {

        val date =
            extractDate(input)
                .ifBlank {
                    pendingContext?.date ?: ""
                }

        val time =
            extractTime(input)
                .ifBlank {
                    pendingContext?.time ?: ""
                }

        val duration =
            extractDurationMinutes(input)
                ?: pendingContext?.durationMinutes
                ?: 60

        val title =
            extractOptimizationTitle(input)
                .ifBlank {
                    pendingContext?.title ?: ""
                }

        return AIResult(
            intent = OPTIMIZE_SCHEDULE,
            title = title,
            date = date,
            time = time,
            durationMinutes = duration,
            priority =
                priority
                    ?: pendingContext?.priority
                    ?: 2,
            recurring = false,
            response =
                if (title.isBlank()) {
                    "I can find the best time. What would you like to schedule?"
                } else {
                    "I'll find the best time for $title."
                }
        )
    }

    private fun processFollowUp(
        input: String,
        pendingContext: AIPendingContext
    ): AIResult? {

        val normalized =
            normalize(input)

        if (isExactCancel(normalized)) {

            return AIResult(
                intent = CANCEL_WORKFLOW,
                response = "Okay, I've cancelled that."
            )
        }

        var changed = false

        var title = pendingContext.title
        var date = pendingContext.date
        var time = pendingContext.time
        var durationMinutes =
            pendingContext.durationMinutes
        var priority =
            pendingContext.priority

        val extractedDate =
            extractDate(input)

        if (
            extractedDate.isNotBlank() &&
            extractedDate != pendingContext.date
        ) {

            date = extractedDate
            changed = true
        }

        val extractedTime =
            extractTime(input)

        if (
            extractedTime.isNotBlank() &&
            extractedTime != pendingContext.time
        ) {

            time = extractedTime
            changed = true
        }

        val extractedDuration =
            extractDurationMinutes(input)

        if (
            extractedDuration != null &&
            extractedDuration !=
            pendingContext.durationMinutes
        ) {

            durationMinutes =
                extractedDuration

            changed = true
        }

        val extractedPriority =
            extractPriority(normalized)

        if (
            extractedPriority != null &&
            extractedPriority !=
            pendingContext.priority
        ) {

            priority =
                extractedPriority

            changed = true
        }

        if (pendingContext.title.isBlank()) {

            val extractedTitle =
                when (pendingContext.intent) {

                    OPTIMIZE_SCHEDULE ->
                        extractOptimizationTitle(input)

                    CREATE_EVENT,
                    CREATE_RECURRING_EVENT ->
                        extractEventTitle(input)

                    UPDATE_EVENT ->
                        extractUpdateTitle(input)

                    else ->
                        ""
                }

            if (
                extractedTitle.isNotBlank() &&
                !isOnlySchedulingInformation(
                    input = input,
                    extractedTitle = extractedTitle
                )
            ) {

                title =
                    extractedTitle

                changed = true
            }
        }

        if (!changed) {
            return null
        }

        val intent =
            pendingContext.intent.ifBlank {
                CREATE_EVENT
            }

        val response =
            when (intent) {

                OPTIMIZE_SCHEDULE -> {

                    if (title.isNotBlank()) {

                        if (date.isNotBlank()) {
                            "Great. I'll find the best time for $title on ${formatFriendlyDateWithoutYear(date)}."
                        } else {
                            "Great. I'll find the best time for $title."
                        }

                    } else {

                        "I can find the best time. What would you like to schedule?"
                    }
                }

                else -> {

                    buildFollowUpResponse(
                        date = date,
                        time = time,
                        priority = priority,
                        durationMinutes =
                            durationMinutes
                    )
                }
            }

        return AIResult(
            intent = intent,
            title = title,
            date = date,
            time = time,
            durationMinutes =
                durationMinutes,
            priority = priority,
            recurring =
                pendingContext.recurring,
            repeatType =
                pendingContext.repeatType,
            repeatDay =
                pendingContext.repeatDay,
            repeatDuration =
                pendingContext.repeatDuration,
            repeatDurationUnit =
                pendingContext.repeatDurationUnit,
            repeatEndDate =
                pendingContext.repeatEndDate,
            response = response
        )
    }

    private fun isOnlySchedulingInformation(
        input: String,
        extractedTitle: String
    ): Boolean {

        if (extractedTitle.isBlank()) {
            return true
        }

        val normalized =
            normalize(input)

        val hasDate =
            extractDate(input).isNotBlank()

        val hasTime =
            extractTime(input).isNotBlank()

        val hasDuration =
            extractDurationMinutes(input) != null

        val hasPriority =
            extractPriority(normalized) != null

        val dateOnlyWords =
            setOf(
                "today",
                "tomorrow",
                "monday",
                "tuesday",
                "wednesday",
                "thursday",
                "friday",
                "saturday",
                "sunday"
            )

        if (
            dateOnlyWords.contains(normalized) &&
            hasDate
        ) {
            return true
        }

        if (
            normalized.matches(
                Regex(
                    """^\d{1,2}(?::\d{2})?\s*(am|pm)$"""
                )
            )
        ) {
            return true
        }

        if (
            hasDuration &&
            extractedTitle.equals(
                normalized,
                ignoreCase = true
            )
        ) {
            return true
        }

        return false
    }

    private fun buildFollowUpResponse(
        date: String,
        time: String,
        priority: Int,
        durationMinutes: Int
    ): String {

        return when {

            date.isNotBlank() &&
                    time.isNotBlank() -> {

                "Great. I'll use ${formatFriendlyDateWithoutYear(date)} at ${formatFriendlyTime(time)}."
            }

            date.isNotBlank() -> {

                "Got it. What time should I use?"
            }

            time.isNotBlank() -> {

                "Got it. What date should I use?"
            }

            else -> {

                "Got it."
            }
        }
    }

    private fun buildConfirmationResult(
        pendingContext: AIPendingContext
    ): AIResult {

        return AIResult(
            intent =
                pendingContext.intent.ifBlank {
                    CREATE_EVENT
                },
            title = pendingContext.title,
            date = pendingContext.date,
            time = pendingContext.time,
            durationMinutes =
                pendingContext.durationMinutes,
            priority =
                pendingContext.priority,
            recurring =
                pendingContext.recurring,
            repeatType =
                pendingContext.repeatType,
            repeatDay =
                pendingContext.repeatDay,
            repeatDuration =
                pendingContext.repeatDuration,
            repeatDurationUnit =
                pendingContext.repeatDurationUnit,
            repeatEndDate =
                pendingContext.repeatEndDate,
            response = "Okay, I'll go ahead."
        )
    }

    private fun parseCreateEvent(
        input: String,
        priority: Int?
    ): AIResult {

        val date =
            extractDate(input)

        val time =
            extractTime(input)

        val duration =
            extractDurationMinutes(input)
                ?: 60

        val title =
            extractEventTitle(input)

        return AIResult(
            intent = CREATE_EVENT,
            title = title,
            date = date,
            time = time,
            durationMinutes = duration,
            priority = priority ?: 2,
            recurring = false,
            response =
                when {

                    date.isBlank() &&
                            time.isBlank() ->
                        "Sure. What date and time should I use?"

                    date.isBlank() ->
                        "Sure. What date should I use?"

                    time.isBlank() ->
                        "Sure. What time should I use?"

                    else ->
                        "Sure, I can schedule that."
                }
        )
    }

    private fun parseRecurringEvent(
        input: String,
        normalized: String,
        priority: Int?
    ): AIResult? {

        val recurring =
            normalized.contains("every ") ||
                    normalized.contains("daily") ||
                    normalized.contains("weekday") ||
                    normalized.contains("weekly") ||
                    normalized.contains("monthly")

        if (!recurring) {
            return null
        }

        var repeatType = ""
        var repeatDay = ""

        when {

            normalized.contains("every monday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Monday"
            }

            normalized.contains("every tuesday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Tuesday"
            }

            normalized.contains("every wednesday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Wednesday"
            }

            normalized.contains("every thursday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Thursday"
            }

            normalized.contains("every friday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Friday"
            }

            normalized.contains("every saturday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Saturday"
            }

            normalized.contains("every sunday") -> {
                repeatType = "WEEKLY"
                repeatDay = "Sunday"
            }

            normalized.contains("weekday") -> {
                repeatType = "WEEKDAYS"
            }

            normalized.contains("daily") ||
                    normalized.contains("every day") -> {
                repeatType = "DAILY"
            }

            normalized.contains("monthly") ||
                    normalized.contains("every month") -> {
                repeatType = "MONTHLY"
            }

            normalized.contains("weekly") ||
                    normalized.contains("every week") -> {
                repeatType = "WEEKLY"
            }

            else -> {
                repeatType = "WEEKLY"
            }
        }

        val durationMatch =
            Pattern.compile(
                """(?:for)\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s+(day|days|week|weeks|month|months|year|years)""",
                Pattern.CASE_INSENSITIVE
            ).matcher(normalized)

        var repeatDuration = 0
        var repeatDurationUnit = ""

        if (durationMatch.find()) {

            repeatDuration =
                numberWordToInt(
                    durationMatch.group(1) ?: ""
                )

            repeatDurationUnit =
                normalizeDurationUnit(
                    durationMatch.group(2) ?: ""
                )
        }

        val date =
            extractDate(input)

        val time =
            extractTime(input)

        val title =
            extractEventTitle(input)

        val eventDuration =
            extractDurationMinutes(input)
                ?: 60

        return AIResult(
            intent = CREATE_RECURRING_EVENT,
            title = title,
            date = date,
            time = time,
            durationMinutes = eventDuration,
            priority = priority ?: 2,
            recurring = true,
            repeatType = repeatType,
            repeatDay = repeatDay,
            repeatDuration = repeatDuration,
            repeatDurationUnit =
                repeatDurationUnit,
            response =
                when {

                    date.isBlank() &&
                            time.isBlank() ->
                        "Sure. What date and time should the recurring event start?"

                    date.isBlank() ->
                        "What date should the recurring event start?"

                    time.isBlank() ->
                        "What time should the recurring event start?"

                    else ->
                        "Sure, I can set that recurring event up."
                }
        )
    }

    private fun parseListEvents(
        input: String
    ): AIResult {

        return AIResult(
            intent = LIST_EVENTS,
            date = extractDate(input),
            response = "I'll check your schedule."
        )
    }

    private fun parseDeleteEvent(
        input: String
    ): AIResult {

        val title =
            extractDeleteTitle(input)

        return AIResult(
            intent = DELETE_EVENT,
            title = title,
            date = extractDate(input),
            time = extractTime(input),
            response =
                if (title.isBlank()) {
                    "Which event would you like me to delete?"
                } else {
                    "I'll look for that event."
                }
        )
    }

    private fun parseUpdateEvent(
        input: String,
        pendingContext: AIPendingContext?
    ): AIResult {

        val title =
            extractUpdateTitle(input)
                .ifBlank {
                    pendingContext?.title ?: ""
                }

        val date =
            extractDate(input)
                .ifBlank {
                    pendingContext?.date ?: ""
                }

        val time =
            extractTime(input)
                .ifBlank {
                    pendingContext?.time ?: ""
                }

        val duration =
            extractDurationMinutes(input)
                ?: pendingContext?.durationMinutes
                ?: 60

        val priority =
            extractPriority(
                normalize(input)
            ) ?: pendingContext?.priority ?: 2

        return AIResult(
            intent = UPDATE_EVENT,
            title = title,
            date = date,
            time = time,
            durationMinutes = duration,
            priority = priority,
            response = "I'll update that event."
        )
    }

    private fun parseFreeTime(
        input: String
    ): AIResult {

        val normalized =
            normalize(input)

        var start = ""
        var end = ""

        when {

            normalized.contains("morning") -> {
                start = "08:00"
                end = "12:00"
            }

            normalized.contains("afternoon") -> {
                start = "12:00"
                end = "17:00"
            }

            normalized.contains("evening") -> {
                start = "17:00"
                end = "21:00"
            }
        }

        return AIResult(
            intent = FIND_FREE_TIME,
            date = extractDate(input),
            availabilityStart = start,
            availabilityEnd = end,
            response = "I'll check when you're free."
        )
    }

    private fun looksLikeOptimize(
        normalized: String
    ): Boolean {

        val patterns =
            listOf(
                "best time",
                "when should i schedule",
                "when should i",
                "optimize my schedule",
                "optimize the schedule",
                "optimize my day",
                "optimize schedule",
                "find the best time",
                "fit a",
                "fit my",
                "schedule this at the best",
                "what time should i schedule"
            )

        return patterns.any {
            normalized.contains(it)
        }
    }

    private fun looksLikeCreate(
        normalized: String
    ): Boolean {

        val patterns =
            listOf(
                "schedule",
                "sched",
                "book",
                "add an event",
                "add event",
                "create an event",
                "create event",
                "set a reminder",
                "set reminder",
                "remind me",
                "plan a",
                "i need to",
                "i have to",
                "i want to",
                "i need an appointment",
                "i have an appointment",
                "meeting",
                "appointment"
            )

        return patterns.any {
            normalized.contains(it)
        }
    }

    private fun looksLikeList(
        normalized: String
    ): Boolean {

        val patterns =
            listOf(
                "what am i doing",
                "what do i have",
                "what's on my calendar",
                "whats on my calendar",
                "show my schedule",
                "show my events",
                "list my events",
                "my schedule",
                "my events",
                "calendar for",
                "agenda"
            )

        return patterns.any {
            normalized.contains(it)
        }
    }

    private fun looksLikeDelete(
        normalized: String
    ): Boolean {

        val patterns =
            listOf(
                "delete",
                "remove",
                "cancel my",
                "cancel the",
                "cancel appointment",
                "cancel meeting",
                "cancel event"
            )

        return patterns.any {
            normalized.contains(it)
        }
    }

    private fun looksLikeUpdate(
        normalized: String
    ): Boolean {

        val patterns =
            listOf(
                "update",
                "change",
                "move",
                "modify",
                "reschedule",
                "make it",
                "actually",
                "instead"
            )

        return patterns.any {
            normalized.contains(it)
        }
    }

    private fun looksLikeFreeTime(
        normalized: String
    ): Boolean {

        val patterns =
            listOf(
                "am i free",
                "are you free",
                "when am i free",
                "when am i available",
                "am i available",
                "free time",
                "available time",
                "availability",
                "do i have anything",
                "anything tomorrow",
                "anything today"
            )

        return patterns.any {
            normalized.contains(it)
        }
    }

    private fun extractPriority(
        normalized: String
    ): Int? {

        return when {

            normalized.contains("priority 1") ||
                    normalized.contains("low priority") ||
                    normalized.contains("low importance") ->
                1

            normalized.contains("priority 2") ||
                    normalized.contains("normal priority") ->
                2

            normalized.contains("priority 3") ||
                    normalized.contains("high priority") ||
                    normalized.contains("important") ->
                3

            normalized.contains("priority 4") ||
                    normalized.contains("urgent") ||
                    normalized.contains("emergency") ||
                    normalized.contains("critical") ->
                4

            else ->
                null
        }
    }

    private fun extractDate(
        input: String
    ): String {

        val normalized =
            normalize(input)

        val today =
            Calendar.getInstance(PH_TIME)

        when {

            normalized.contains("day after tomorrow") -> {

                val cal =
                    today.clone() as Calendar

                cal.add(
                    Calendar.DAY_OF_MONTH,
                    2
                )

                return formatDate(cal)
            }

            normalized.contains("tomorrow") -> {

                val cal =
                    today.clone() as Calendar

                cal.add(
                    Calendar.DAY_OF_MONTH,
                    1
                )

                return formatDate(cal)
            }

            normalized.contains("today") -> {
                return formatDate(today)
            }
        }

        val days =
            arrayOf(
                "sunday",
                "monday",
                "tuesday",
                "wednesday",
                "thursday",
                "friday",
                "saturday"
            )

        for (day in days) {

            if (normalized == day) {

                return getNextWeekdayDate(
                    dayName = day,
                    nextWeek = true
                )
            }

            if (normalized.contains("next $day")) {

                return getNextWeekdayDate(
                    dayName = day,
                    nextWeek = true
                )
            }

            if (normalized.contains("this $day")) {

                return getNextWeekdayDate(
                    dayName = day,
                    nextWeek = false
                )
            }
        }

        val isoPattern =
            Pattern.compile(
                """\b(\d{4})-(\d{1,2})-(\d{1,2})\b"""
            )

        val isoMatcher =
            isoPattern.matcher(input)

        if (isoMatcher.find()) {

            return buildValidatedDate(
                year =
                    isoMatcher.group(1)!!.toInt(),
                month =
                    isoMatcher.group(2)!!.toInt(),
                day =
                    isoMatcher.group(3)!!.toInt()
            )
        }

        val slashPattern =
            Pattern.compile(
                """\b(\d{1,2})/(\d{1,2})/(\d{4})\b"""
            )

        val slashMatcher =
            slashPattern.matcher(input)

        if (slashMatcher.find()) {

            return buildValidatedDate(
                year =
                    slashMatcher.group(3)!!.toInt(),
                month =
                    slashMatcher.group(1)!!.toInt(),
                day =
                    slashMatcher.group(2)!!.toInt()
            )
        }

        val monthPattern =
            Pattern.compile(
                """(?i)\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,\s*(\d{4}))?\b"""
            )

        val monthMatcher =
            monthPattern.matcher(input)

        if (monthMatcher.find()) {

            val monthName =
                monthMatcher.group(1)!!

            val day =
                monthMatcher.group(2)!!.toInt()

            val explicitYear =
                monthMatcher.group(3)?.toIntOrNull()

            val month =
                monthNameToNumber(monthName)

            if (month == 0) {
                return ""
            }

            if (explicitYear != null) {

                return buildValidatedDate(
                    year = explicitYear,
                    month = month,
                    day = day
                )
            }

            val currentYear =
                today.get(Calendar.YEAR)

            val candidate =
                Calendar.getInstance(PH_TIME)

            candidate.clear()
            candidate.timeZone = PH_TIME

            candidate.set(
                Calendar.YEAR,
                currentYear
            )

            candidate.set(
                Calendar.MONTH,
                month - 1
            )

            candidate.set(
                Calendar.DAY_OF_MONTH,
                day
            )

            candidate.set(
                Calendar.HOUR_OF_DAY,
                0
            )

            candidate.set(
                Calendar.MINUTE,
                0
            )

            candidate.set(
                Calendar.SECOND,
                0
            )

            candidate.set(
                Calendar.MILLISECOND,
                0
            )

            if (
                candidate.get(Calendar.MONTH) !=
                month - 1 ||
                candidate.get(Calendar.DAY_OF_MONTH) !=
                day
            ) {
                return ""
            }

            val todayOnly =
                today.clone() as Calendar

            todayOnly.set(
                Calendar.HOUR_OF_DAY,
                0
            )

            todayOnly.set(
                Calendar.MINUTE,
                0
            )

            todayOnly.set(
                Calendar.SECOND,
                0
            )

            todayOnly.set(
                Calendar.MILLISECOND,
                0
            )

            if (candidate.before(todayOnly)) {

                candidate.add(
                    Calendar.YEAR,
                    1
                )
            }

            return formatDate(candidate)
        }

        return ""
    }

    private fun monthNameToNumber(
        monthName: String
    ): Int {

        return when (
            monthName.lowercase(Locale.US)
        ) {

            "january", "jan" ->
                Calendar.JANUARY + 1

            "february", "feb" ->
                Calendar.FEBRUARY + 1

            "march", "mar" ->
                Calendar.MARCH + 1

            "april", "apr" ->
                Calendar.APRIL + 1

            "may" ->
                Calendar.MAY + 1

            "june", "jun" ->
                Calendar.JUNE + 1

            "july", "jul" ->
                Calendar.JULY + 1

            "august", "aug" ->
                Calendar.AUGUST + 1

            "september", "sep", "sept" ->
                Calendar.SEPTEMBER + 1

            "october", "oct" ->
                Calendar.OCTOBER + 1

            "november", "nov" ->
                Calendar.NOVEMBER + 1

            "december", "dec" ->
                Calendar.DECEMBER + 1

            else ->
                0
        }
    }

    private fun buildValidatedDate(
        year: Int,
        month: Int,
        day: Int
    ): String {

        if (month !in 1..12) {
            return ""
        }

        val calendar =
            Calendar.getInstance(PH_TIME)

        calendar.clear()
        calendar.timeZone = PH_TIME

        calendar.set(
            Calendar.YEAR,
            year
        )

        calendar.set(
            Calendar.MONTH,
            month - 1
        )

        calendar.set(
            Calendar.DAY_OF_MONTH,
            day
        )

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )

        calendar.set(
            Calendar.MINUTE,
            0
        )

        calendar.set(
            Calendar.SECOND,
            0
        )

        calendar.set(
            Calendar.MILLISECOND,
            0
        )

        if (
            calendar.get(Calendar.YEAR) != year ||
            calendar.get(Calendar.MONTH) != month - 1 ||
            calendar.get(Calendar.DAY_OF_MONTH) != day
        ) {
            return ""
        }

        return formatDate(calendar)
    }

    private fun getNextWeekdayDate(
        dayName: String,
        nextWeek: Boolean
    ): String {

        val targetDay =
            when (
                dayName.lowercase(Locale.US)
            ) {

                "sunday" ->
                    Calendar.SUNDAY

                "monday" ->
                    Calendar.MONDAY

                "tuesday" ->
                    Calendar.TUESDAY

                "wednesday" ->
                    Calendar.WEDNESDAY

                "thursday" ->
                    Calendar.THURSDAY

                "friday" ->
                    Calendar.FRIDAY

                "saturday" ->
                    Calendar.SATURDAY

                else ->
                    return ""
            }

        val cal =
            Calendar.getInstance(PH_TIME)

        val currentDay =
            cal.get(Calendar.DAY_OF_WEEK)

        var difference =
            targetDay - currentDay

        if (nextWeek) {

            if (difference <= 0) {
                difference += 7
            }

        } else {

            if (difference < 0) {
                difference += 7
            }
        }

        cal.add(
            Calendar.DAY_OF_MONTH,
            difference
        )

        return formatDate(cal)
    }

    private fun extractTime(
        input: String
    ): String {

        val normalized =
            normalize(input)

        if (normalized.contains("noon")) {
            return "12:00"
        }

        if (normalized.contains("midnight")) {
            return "00:00"
        }

        val amPmPattern =
            Pattern.compile(
                """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""",
                Pattern.CASE_INSENSITIVE
            )

        val amPmMatcher =
            amPmPattern.matcher(normalized)

        if (amPmMatcher.find()) {

            var hour =
                amPmMatcher.group(1)!!.toInt()

            val minute =
                amPmMatcher.group(2)
                    ?.toIntOrNull() ?: 0

            val period =
                amPmMatcher.group(3)!!
                    .lowercase(Locale.US)

            if (hour !in 1..12) {
                return ""
            }

            if (period == "pm" && hour != 12) {
                hour += 12
            }

            if (period == "am" && hour == 12) {
                hour = 0
            }

            return String.format(
                Locale.US,
                "%02d:%02d",
                hour,
                minute
            )
        }

        val twentyFourPattern =
            Pattern.compile(
                """\b([01]?\d|2[0-3]):([0-5]\d)\b"""
            )

        val twentyFourMatcher =
            twentyFourPattern.matcher(normalized)

        if (twentyFourMatcher.find()) {

            return String.format(
                Locale.US,
                "%02d:%02d",
                twentyFourMatcher.group(1)!!.toInt(),
                twentyFourMatcher.group(2)!!.toInt()
            )
        }

        val atHourPattern =
            Pattern.compile(
                """\bat\s+(\d{1,2})\b"""
            )

        val atHourMatcher =
            atHourPattern.matcher(normalized)

        if (atHourMatcher.find()) {

            val hour =
                atHourMatcher.group(1)!!.toInt()

            if (hour in 0..23) {

                return String.format(
                    Locale.US,
                    "%02d:00",
                    hour
                )
            }
        }

        return ""
    }

    private fun extractDurationMinutes(
        input: String
    ): Int? {

        val normalized =
            normalize(input)

        val halfHourPattern =
            Pattern.compile(
                """\b(\d+)\s+and\s+a\s+half\s+hours?\b""",
                Pattern.CASE_INSENSITIVE
            )

        val halfHourMatcher =
            halfHourPattern.matcher(normalized)

        if (halfHourMatcher.find()) {

            val hours =
                halfHourMatcher.group(1)
                    ?.toIntOrNull()

            if (hours != null) {

                return (
                        hours * 60 + 30
                        ).coerceIn(
                        15,
                        1440
                    )
            }
        }

        val hourPattern =
            Pattern.compile(
                """\b(\d+(?:\.\d+)?)\s*(?:hours?|hrs?)\b""",
                Pattern.CASE_INSENSITIVE
            )

        val hourMatcher =
            hourPattern.matcher(normalized)

        if (hourMatcher.find()) {

            val hours =
                hourMatcher.group(1)
                    ?.toDoubleOrNull()

            if (
                hours != null &&
                hours > 0
            ) {

                return (
                        hours * 60
                        ).toInt().coerceIn(
                        15,
                        1440
                    )
            }
        }

        val minutePattern =
            Pattern.compile(
                """\b(\d+)\s*(?:minutes?|mins?)\b""",
                Pattern.CASE_INSENSITIVE
            )

        val minuteMatcher =
            minutePattern.matcher(normalized)

        if (minuteMatcher.find()) {

            val minutes =
                minuteMatcher.group(1)
                    ?.toIntOrNull()

            if (
                minutes != null &&
                minutes > 0
            ) {

                return minutes.coerceIn(
                    15,
                    1440
                )
            }
        }

        return null
    }

    private fun extractEventTitle(
        input: String
    ): String {

        var title =
            input.trim()

        title =
            title.replace(
                Regex(
                    """(?i)\b(schedule|sched|book|create|add|plan|set)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(can you|could you|please|i need to|i need|i have to|i want to|remind me to)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(an?|the)\s+(event|reminder)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(day after tomorrow|tomorrow|today)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(next|this)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """\b\d{4}-\d{1,2}-\d{1,2}\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """\b\d{1,2}/\d{1,2}/\d{4}\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bon\s+(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bat\s+(?:\d{1,2}(?::\d{2})?\s*(?:am|pm)|(?:[01]?\d|2[0-3]):[0-5]\d|\d{1,2})\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\baround\s+(?:\d{1,2}(?::\d{2})?\s*(?:am|pm)|(?:[01]?\d|2[0-3]):[0-5]\d|\d{1,2})\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b\d{1,2}(?::\d{2})?\s*(?:am|pm)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """\b(?:[01]?\d|2[0-3]):[0-5]\d\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bfor\s+\d+\s+(?:days?|weeks?|months?|years?)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bevery\s+(?:day|weekday|week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(?:low|normal|high|urgent|important|critical)\s+priority\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bpriority\s*[1-4]\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)^\s*(on|for|at|around)\s+"""
                ),
                ""
            )

        title =
            title.replace(
                Regex("""\s+"""),
                " "
            )

        return title
            .trim()
            .trim('.', ',', '!', '?')
    }

    private fun extractOptimizationTitle(
        input: String
    ): String {

        var title =
            input.trim()

        title =
            title.replace(
                Regex(
                    """(?i)\b(find|what|when|should|can you|please|help me)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(the\s+)?best\s+time\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(schedule|scheduling|fit|optimize|optimization)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(my|me|for me|for)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(on|for)\s+(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(today|tomorrow|day after tomorrow)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(next|this)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(?:for\s+)?\d+(?:\.\d+)?\s*(?:hours?|hrs?|minutes?|mins?)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(?:\d+)\s+and\s+a\s+half\s+hours?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(at|around)\s+\d{1,2}(?::\d{2})?\s*(am|pm)?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """\b\d{4}-\d{1,2}-\d{1,2}\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """\b\d{1,2}/\d{1,2}/\d{4}\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex("""\s+"""),
                " "
            )

        title =
            title.trim()
                .trim('.', ',', '!', '?')

        title =
            title.replace(
                Regex(
                    """(?i)^time\s+for\s+"""
                ),
                ""
            )

        return title.trim()
    }

    private fun extractDeleteTitle(
        input: String
    ): String {

        var title =
            input.trim()

        title =
            title.replace(
                Regex(
                    """(?i)\b(delete|remove|cancel)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(my|the)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(on|for)\s+(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bat\s+\d{1,2}(?::\d{2})?\s*(am|pm)?\b"""
                ),
                ""
            )

        return title
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
            .trim('.', ',', '!', '?')
    }

    private fun extractUpdateTitle(
        input: String
    ): String {

        var title =
            input.trim()

        title =
            title.replace(
                Regex(
                    """(?i)\b(update|change|move|modify|reschedule)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(my|the)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bto\s+\d{1,2}(?::\d{2})?\s*(am|pm)?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bat\s+\d{1,2}(?::\d{2})?\s*(am|pm)?\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(on|for)\s+(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\bmake\s+it\b"""
                ),
                ""
            )

        title =
            title.replace(
                Regex(
                    """(?i)\b(?:for\s+)?\d+(?:\.\d+)?\s*(?:hours?|hrs?|minutes?|mins?)\b"""
                ),
                ""
            )

        return title
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
            .trim('.', ',', '!', '?')
    }

    private fun formatFriendlyDateWithoutYear(
        date: String
    ): String {

        return try {

            val parser =
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.US
                )

            parser.isLenient = false
            parser.timeZone = PH_TIME

            val parsed =
                parser.parse(date)

            if (parsed == null) {

                date

            } else {

                val formatter =
                    SimpleDateFormat(
                        "MMMM d",
                        Locale.US
                    )

                formatter.timeZone = PH_TIME

                formatter.format(parsed)
            }

        } catch (_: Exception) {

            date
        }
    }

    private fun formatFriendlyTime(
        time: String
    ): String {

        return try {

            val parser =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.US
                )

            parser.isLenient = false

            val parsed =
                parser.parse(time)

            if (parsed == null) {

                time

            } else {

                val formatter =
                    SimpleDateFormat(
                        "h:mm a",
                        Locale.US
                    )

                formatter.format(parsed)
            }

        } catch (_: Exception) {

            time
        }
    }

    private fun normalize(
        input: String
    ): String {

        return input
            .lowercase(Locale.US)
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }

    private fun formatDate(
        calendar: Calendar
    ): String {

        val formatter =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            )

        formatter.timeZone =
            PH_TIME

        return formatter.format(
            calendar.time
        )
    }

    private fun getToday(): String {

        return formatDate(
            Calendar.getInstance(PH_TIME)
        )
    }

    private fun normalizeDate(
        value: String
    ): String {

        if (value.isBlank()) {
            return ""
        }

        val formatter =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            )

        formatter.isLenient = false
        formatter.timeZone =
            PH_TIME

        return try {

            val date =
                formatter.parse(value.trim())

            if (date != null) {
                formatter.format(date)
            } else {
                ""
            }

        } catch (_: Exception) {

            ""
        }
    }

    private fun normalizeTime(
        value: String
    ): String {

        if (value.isBlank()) {
            return ""
        }

        val cleaned =
            value.trim()

        val twentyFourPattern =
            Pattern.compile(
                """^([01]\d|2[0-3]):([0-5]\d)$"""
            )

        val matcher =
            twentyFourPattern.matcher(cleaned)

        if (matcher.matches()) {
            return cleaned
        }

        val amPmPattern =
            Pattern.compile(
                """^(\d{1,2})(?::(\d{2}))?\s*(am|pm)$""",
                Pattern.CASE_INSENSITIVE
            )

        val amPmMatcher =
            amPmPattern.matcher(cleaned)

        if (amPmMatcher.matches()) {

            var hour =
                amPmMatcher.group(1)!!.toInt()

            val minute =
                amPmMatcher.group(2)
                    ?.toIntOrNull()
                    ?: 0

            val period =
                amPmMatcher.group(3)!!
                    .lowercase(Locale.US)

            if (hour !in 1..12) {
                return ""
            }

            if (
                period == "pm" &&
                hour != 12
            ) {
                hour += 12
            }

            if (
                period == "am" &&
                hour == 12
            ) {
                hour = 0
            }

            return String.format(
                Locale.US,
                "%02d:%02d",
                hour,
                minute
            )
        }

        return ""
    }

    private fun normalizeDurationUnit(
        unit: String
    ): String {

        return when (
            unit.lowercase(Locale.US)
        ) {

            "day",
            "days" ->
                "days"

            "week",
            "weeks" ->
                "weeks"

            "month",
            "months" ->
                "months"

            "year",
            "years" ->
                "years"

            else ->
                ""
        }
    }

    private fun numberWordToInt(
        value: String
    ): Int {

        return when (
            value.lowercase(Locale.US)
        ) {

            "one" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            "eight" -> 8
            "nine" -> 9
            "ten" -> 10

            else ->
                value.toIntOrNull() ?: 0
        }
    }

    private fun escapeJson(
        value: String
    ): String {

        return value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\r",
                "\\r"
            )
            .replace(
                "\t",
                "\\t"
            )
    }

    private fun isConfirmation(
        normalized: String
    ): Boolean {

        return CONFIRM_WORDS.contains(
            normalized
        )
    }

    private fun isRejection(
        normalized: String
    ): Boolean {

        return REJECT_WORDS.contains(
            normalized
        )
    }

    private fun isExactCancel(
        normalized: String
    ): Boolean {

        return CANCEL_WORDS.contains(
            normalized
        )
    }
}

