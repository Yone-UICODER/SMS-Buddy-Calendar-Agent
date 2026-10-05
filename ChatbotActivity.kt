package com.example.calendarsms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.calendarsms.ai.AIEngine
import com.example.calendarsms.ai.AIPendingContext
import com.example.calendarsms.ai.AIResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class ChatbotActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var etCommand: EditText
    private lateinit var chatContainer: LinearLayout
    private lateinit var ai: AIEngine

    private var pendingContext: AIPendingContext? = null

    private var pendingSuggestedEvent: AIPendingContext? = null
    private var waitingForConfirmation = false

    private val EVENT_DURATION_MINUTES = 30

    private val DAY_START_MINUTES = 8 * 60
    private val DAY_END_MINUTES = 21 * 60

    private data class ScheduleBlock(
        val title: String,
        val start: Int,
        val end: Int
    )

    private data class ScheduleSuggestion(
        val time: Int,
        val score: Int
    )

    private val PH_TIME =
        TimeZone.getTimeZone("Asia/Manila")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_chatbot)

        ai = AIEngine()
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        etCommand = findViewById(R.id.etCommand)
        chatContainer = findViewById(R.id.chatContainer)

        requestNotificationPermission()

        val btnSend =
            findViewById<Button>(R.id.btnSend)

        val dateFmt =
            SimpleDateFormat(
                "EEEE, MMM d",
                Locale.ENGLISH
            ).apply {
                timeZone = PH_TIME
            }

        findViewById<TextView>(R.id.tvDate)?.text =
            dateFmt.format(
                Calendar.getInstance(PH_TIME).time
            )

        loadChatHistory()

        btnSend.setOnClickListener {

            val command =
                etCommand.text
                    .toString()
                    .trim()

            if (command.isEmpty()) {
                return@setOnClickListener
            }

            addMessage(
                command,
                isUser = true,
                saveToFirestore = true
            )

            etCommand.text.clear()

            processCommand(command)
        }

        findViewById<LinearLayout>(
            R.id.navCalendar
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    CalendarActivity::class.java
                )
            )
        }

        findViewById<LinearLayout>(
            R.id.navReminders
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    RemindersActivity::class.java
                )
            )
        }

        findViewById<LinearLayout>(
            R.id.navChat
        ).setOnClickListener {

            finish()
        }

        findViewById<LinearLayout>(
            R.id.navHome
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    DashboardActivity::class.java
                )
            )

            finish()
        }

        findViewById<LinearLayout>(
            R.id.navSettings
        ).setOnClickListener {

            startActivity(
                Intent(
                    this,
                    SettingsActivity::class.java
                )
            )
        }
    }

    private fun processCommand(
        cmd: String
    ) {

        val userId =
            auth.currentUser?.uid
                ?: return

        fun handleResult(
            result: AIResult,
            originalCommand: String
        ) {

            when (result.intent) {

                "CANCEL_WORKFLOW" -> {

                    clearAllPendingStates()

                    addMessage(
                        result.response.ifEmpty {
                            "Okay, I cancelled that."
                        },
                        isUser = false,
                        saveToFirestore = true
                    )
                }

                "CREATE_EVENT" -> {

                    if (
                        result.date.isBlank() ||
                        result.time.isBlank()
                    ) {

                        pendingContext =
                            AIPendingContext(
                                intent = "CREATE_EVENT",
                                title = result.title,
                                date = result.date,
                                time = result.time,
                                durationMinutes =
                                    result.durationMinutes,
                                priority = result.priority
                            )

                        addMessage(
                            result.response.ifEmpty {
                                buildNormalEventQuestion(result)
                            },
                            isUser = false,
                            saveToFirestore = true
                        )

                        return
                    }

                    pendingContext = null

                    checkForConflict(
                        userId = userId,
                        title = result.title,
                        date = result.date,
                        time = result.time,
                        priority = result.priority
                    )
                }

                "CREATE_RECURRING_EVENT" -> {

                    if (
                        result.time.isBlank()
                    ) {

                        pendingContext =
                            AIPendingContext(
                                intent = "CREATE_RECURRING_EVENT",
                                title = result.title,
                                date = result.date,
                                time = result.time,
                                durationMinutes =
                                    result.durationMinutes,
                                priority = result.priority,
                                recurring = true,
                                repeatType = result.repeatType,
                                repeatDay = result.repeatDay,
                                repeatDuration =
                                    result.repeatDuration,
                                repeatDurationUnit =
                                    result.repeatDurationUnit,
                                repeatEndDate =
                                    result.repeatEndDate
                            )

                        addMessage(
                            result.response.ifEmpty {
                                buildRecurringTimeQuestion(result)
                            },
                            isUser = false,
                            saveToFirestore = true
                        )

                        return
                    }

                    pendingContext = null

                    handleRecurringEvent(result)
                }

                "UPDATE_EVENT" -> {

                    if (
                        result.title.isBlank()
                    ) {

                        pendingContext =
                            AIPendingContext(
                                intent = "UPDATE_EVENT",
                                title = "",
                                date = result.date,
                                time = result.time,
                                durationMinutes =
                                    result.durationMinutes,
                                priority = result.priority
                            )

                        addMessage(
                            result.response.ifEmpty {
                                "Which event would you like me to update?"
                            },
                            isUser = false,
                            saveToFirestore = true
                        )

                        return
                    }

                    pendingContext = null

                    updateEvent(
                        userId,
                        result.title,
                        result.date,
                        result.time,
                        result.response
                    )
                }

                "DELETE_EVENT" -> {

                    if (
                        result.title.isBlank()
                    ) {

                        pendingContext =
                            AIPendingContext(
                                intent = "DELETE_EVENT",
                                title = "",
                                date = result.date,
                                time = result.time,
                                durationMinutes =
                                    result.durationMinutes,
                                priority = result.priority
                            )

                        addMessage(
                            result.response.ifEmpty {
                                "Which event would you like me to delete?"
                            },
                            isUser = false,
                            saveToFirestore = true
                        )

                        return
                    }

                    pendingContext = null

                    deleteEvent(
                        userId,
                        result.title
                    )
                }

                "LIST_EVENTS" -> {

                    pendingContext = null

                    val targetDate =
                        if (
                            result.date.isNotBlank()
                        ) {
                            result.date
                        } else {
                            extractListDate(originalCommand)
                        }

                    loadEvents(
                        userId = userId,
                        targetDate = targetDate
                    )
                }

                "FIND_FREE_TIME" -> {

                    findFreeTime(
                        userId = userId,
                        result = result,
                        originalCommand = originalCommand
                    )
                }

                "OPTIMIZE_SCHEDULE" -> {

                    optimizeSchedule(
                        userId = userId,
                        result = result,
                        originalCommand = originalCommand
                    )
                }

                "CHAT" -> {

                    pendingContext = null

                    addMessage(
                        result.response.ifEmpty {
                            "Sure! How can I help?"
                        },
                        isUser = false,
                        saveToFirestore = true
                    )
                }

                else -> {

                    pendingContext = null

                    addMessage(
                        result.response.ifEmpty {
                            "I'm not sure how to help with that."
                        },
                        isUser = false,
                        saveToFirestore = true
                    )
                }
            }
        }

        if (waitingForConfirmation) {

            handleConfirmation(
                cmd,
                userId
            )

            return
        }

        if (pendingContext != null) {

            val currentPending =
                pendingContext!!

            val localResult =
                ai.process(
                    cmd,
                    currentPending
                )

            if (
                localResult.intent != "UNKNOWN"
            ) {

                handleResult(
                    localResult,
                    cmd
                )

                return
            }

            ai.processAsync(
                input = cmd,
                pendingContext = currentPending
            ) { geminiResult ->

                handleResult(
                    geminiResult,
                    cmd
                )
            }

            return
        }

        val localResult =
            ai.process(
                cmd,
                null
            )

        if (
            localResult.intent != "UNKNOWN"
        ) {

            handleResult(
                localResult,
                cmd
            )

            return
        }

        ai.processAsync(
            input = cmd,
            pendingContext = pendingContext
        ) { geminiResult ->

            handleResult(
                geminiResult,
                cmd
            )
        }
    }

    private fun findFreeTime(
        userId: String,
        result: AIResult,
        originalCommand: String
    ) {

        val targetDate =
            result.date.ifBlank {
                extractListDate(originalCommand)
            }

        if (targetDate.isBlank()) {

            addMessage(
                result.response.ifEmpty {
                    "What day would you like me to check?"
                },
                isUser = false,
                saveToFirestore = true
            )

            pendingContext =
                AIPendingContext(
                    intent = "FIND_FREE_TIME"
                )

            return
        }

        var startMinutes =
            if (result.availabilityStart.isNotBlank()) {
                timeToMinutes(
                    result.availabilityStart
                )
            } else {
                DAY_START_MINUTES
            }

        var endMinutes =
            if (result.availabilityEnd.isNotBlank()) {
                timeToMinutes(
                    result.availabilityEnd
                )
            } else {
                DAY_END_MINUTES
            }

        if (
            result.time.isNotBlank() &&
            result.availabilityStart.isBlank() &&
            result.availabilityEnd.isBlank()
        ) {

            startMinutes =
                timeToMinutes(result.time)

            endMinutes =
                startMinutes +
                        EVENT_DURATION_MINUTES
        }

        startMinutes =
            startMinutes.coerceIn(
                DAY_START_MINUTES,
                DAY_END_MINUTES
            )

        endMinutes =
            endMinutes.coerceIn(
                DAY_START_MINUTES,
                DAY_END_MINUTES
            )

        if (
            endMinutes <= startMinutes
        ) {

            addMessage(
                "I couldn't determine a valid availability window.",
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        val eventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        eventsRef
            .whereEqualTo(
                "date",
                targetDate
            )
            .get()
            .addOnSuccessListener { documents ->

                val occupied =
                    buildScheduleBlocks(
                        documents
                    )

                val freeSlots =
                    findFreeSlots(
                        occupied = occupied,
                        startMinutes = startMinutes,
                        endMinutes = endMinutes
                    )

                val response =
                    buildFreeTimeResponse(
                        targetDate = targetDate,
                        startMinutes = startMinutes,
                        endMinutes = endMinutes,
                        freeSlots = freeSlots,
                        originalCommand = originalCommand
                    )

                addMessage(
                    response,
                    isUser = false,
                    saveToFirestore = true
                )
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't check your schedule right now. Please try again.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun optimizeSchedule(
        userId: String,
        result: AIResult,
        originalCommand: String
    ) {

        val targetDate =
            result.date.ifBlank {
                extractListDate(originalCommand)
            }

        if (targetDate.isBlank()) {

            pendingContext =
                AIPendingContext(
                    intent = "OPTIMIZE_SCHEDULE",
                    title = result.title,
                    durationMinutes =
                        result.durationMinutes,
                    priority = result.priority
                )

            addMessage(
                result.response.ifEmpty {
                    "What day would you like me to find the best time for?"
                },
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        val duration =
            result.durationMinutes
                .coerceAtLeast(30)

        var startMinutes =
            if (
                result.availabilityStart.isNotBlank()
            ) {
                timeToMinutes(
                    result.availabilityStart
                )
            } else {
                DAY_START_MINUTES
            }

        var endMinutes =
            if (
                result.availabilityEnd.isNotBlank()
            ) {
                timeToMinutes(
                    result.availabilityEnd
                )
            } else {
                DAY_END_MINUTES
            }

        startMinutes =
            startMinutes.coerceIn(
                DAY_START_MINUTES,
                DAY_END_MINUTES
            )

        endMinutes =
            endMinutes.coerceIn(
                DAY_START_MINUTES,
                DAY_END_MINUTES
            )

        if (
            endMinutes - startMinutes < duration
        ) {

            addMessage(
                "That availability window is too short for a ${duration}-minute session.",
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        db.collection("users")
            .document(userId)
            .collection("events")
            .whereEqualTo(
                "date",
                targetDate
            )
            .get()
            .addOnSuccessListener { documents ->

                val occupied =
                    buildScheduleBlocks(
                        documents
                    )

                val suggestions =
                    findOptimalScheduleTimes(
                        occupied = occupied,
                        startMinutes = startMinutes,
                        endMinutes = endMinutes,
                        durationMinutes = duration
                    )

                if (
                    suggestions.isEmpty()
                ) {

                    addMessage(
                        "I couldn't find a suitable free period on ${formatDisplayDate(targetDate)}.",
                        isUser = false,
                        saveToFirestore = true
                    )

                    return@addOnSuccessListener
                }

                val titleText =
                    if (
                        result.title.isNotBlank()
                    ) {
                        " for \"${result.title}\""
                    } else {
                        ""
                    }

                val response =
                    StringBuilder()

                response.append(
                    "I found some good times$titleText on ${formatDisplayDate(targetDate)}:\n\n"
                )

                suggestions.forEachIndexed { index, suggestion ->

                    val end =
                        suggestion.time + duration

                    response.append(
                        "${index + 1}. ${formatMinutes(suggestion.time)} – ${formatMinutes(end)}\n"
                    )
                }

                response.append(
                    "\nThe first option is the best match based on your available schedule."
                )

                addMessage(
                    response.toString(),
                    isUser = false,
                    saveToFirestore = true
                )
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't analyze your schedule right now. Please try again.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun findOptimalScheduleTimes(
        occupied: List<ScheduleBlock>,
        startMinutes: Int,
        endMinutes: Int,
        durationMinutes: Int
    ): List<ScheduleSuggestion> {

        val suggestions =
            mutableListOf<ScheduleSuggestion>()

        var candidate =
            startMinutes

        while (
            candidate + durationMinutes <= endMinutes
        ) {

            val candidateEnd =
                candidate + durationMinutes

            val conflict =
                occupied.any { event ->

                    candidate < event.end &&
                            candidateEnd > event.start
                }

            if (!conflict) {

                var score = 100

                when (candidate) {

                    in 8 * 60..10 * 60 ->
                        score += 8

                    in 10 * 60..12 * 60 ->
                        score += 10

                    in 13 * 60..16 * 60 ->
                        score += 12

                    in 16 * 60..18 * 60 ->
                        score += 8

                    in 18 * 60..20 * 60 ->
                        score += 4
                }

                val previousEvent =
                    occupied
                        .filter {
                            it.end <= candidate
                        }
                        .maxByOrNull {
                            it.end
                        }

                val nextEvent =
                    occupied
                        .filter {
                            it.start >= candidateEnd
                        }
                        .minByOrNull {
                            it.start
                        }

                if (
                    previousEvent != null
                ) {

                    val gapBefore =
                        candidate -
                                previousEvent.end

                    if (
                        gapBefore >= 60
                    ) {
                        score += 10
                    }
                } else {

                    score += 5
                }

                if (
                    nextEvent != null
                ) {

                    val gapAfter =
                        nextEvent.start -
                                candidateEnd

                    if (
                        gapAfter >= 60
                    ) {
                        score += 10
                    }
                } else {

                    score += 5
                }

                suggestions.add(
                    ScheduleSuggestion(
                        time = candidate,
                        score = score
                    )
                )
            }

            candidate += 30
        }

        return suggestions
            .sortedWith(
                compareByDescending<ScheduleSuggestion> {
                    it.score
                }.thenBy {
                    it.time
                }
            )
            .take(3)
    }

    private fun buildScheduleBlocks(
        documents: QuerySnapshot
    ): List<ScheduleBlock> {

        val blocks =
            mutableListOf<ScheduleBlock>()

        for (document in documents) {

            val time =
                document.getString("time")
                    ?: continue

            val title =
                document.getString("title")
                    ?: "Untitled Event"

            val start =
                timeToMinutes(time)

            val end =
                start +
                        EVENT_DURATION_MINUTES

            blocks.add(
                ScheduleBlock(
                    title = title,
                    start = start,
                    end = end
                )
            )
        }

        return blocks.sortedBy {
            it.start
        }
    }

    private fun findFreeSlots(
        occupied: List<ScheduleBlock>,
        startMinutes: Int,
        endMinutes: Int
    ): List<Pair<Int, Int>> {

        val freeSlots =
            mutableListOf<Pair<Int, Int>>()

        var candidate =
            startMinutes

        while (
            candidate +
            EVENT_DURATION_MINUTES <=
            endMinutes
        ) {

            val candidateEnd =
                candidate +
                        EVENT_DURATION_MINUTES

            var conflict =
                false

            for (event in occupied) {

                if (
                    candidate < event.end &&
                    candidateEnd > event.start
                ) {

                    conflict = true
                    break
                }
            }

            if (!conflict) {

                val previous =
                    freeSlots.lastOrNull()

                if (
                    previous != null &&
                    previous.second == candidate
                ) {

                    freeSlots[
                        freeSlots.lastIndex
                    ] =
                        Pair(
                            previous.first,
                            candidateEnd
                        )

                } else {

                    freeSlots.add(
                        Pair(
                            candidate,
                            candidateEnd
                        )
                    )
                }
            }

            candidate += 30
        }

        return freeSlots
    }

    private fun buildFreeTimeResponse(
        targetDate: String,
        startMinutes: Int,
        endMinutes: Int,
        freeSlots: List<Pair<Int, Int>>,
        originalCommand: String
    ): String {

        val dateText =
            formatDisplayDate(targetDate)

        if (
            freeSlots.isEmpty()
        ) {

            return """
                You don't have any free 30-minute slots on $dateText between ${formatMinutes(startMinutes)} and ${formatMinutes(endMinutes)}.
            """.trimIndent()
        }

        val exactSlotRequested =
            Regex(
                """\b(at|around)\s+\d""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(originalCommand)

        if (
            exactSlotRequested
        ) {

            val requestedStart =
                startMinutes

            val requestedEnd =
                requestedStart +
                        EVENT_DURATION_MINUTES

            val exactMatch =
                freeSlots.any {
                    requestedStart >= it.first &&
                            requestedEnd <= it.second
                }

            return if (exactMatch) {

                "Yes, you're free on $dateText from ${formatMinutes(requestedStart)} to ${formatMinutes(requestedEnd)}."

            } else {

                "No, you're not free on $dateText at ${formatMinutes(requestedStart)}."
            }
        }

        val displaySlots =
            freeSlots.take(6)

        val slotText =
            displaySlots.joinToString(
                separator = "\n"
            ) { slot ->

                "• ${formatMinutes(slot.first)} – ${formatMinutes(slot.second)}"
            }

        val moreText =
            if (
                freeSlots.size > 6
            ) {

                "\nThere are ${freeSlots.size - 6} more available periods."

            } else {

                ""
            }

        return """
            Yes! You're free on $dateText during:

            $slotText$moreText
        """.trimIndent()
    }

    private fun clearAllPendingStates() {

        pendingContext = null
        pendingSuggestedEvent = null
        waitingForConfirmation = false
    }

    private fun buildNormalEventQuestion(
        result: AIResult
    ): String {

        return when {

            result.date.isBlank() &&
                    result.time.isBlank() -> {

                "What date and time should I schedule \"${result.title}\"?"
            }

            result.date.isBlank() -> {

                "What date should I schedule \"${result.title}\"?"
            }

            result.time.isBlank() -> {

                "What time should I schedule \"${result.title}\" on ${formatDisplayDate(result.date)}?"
            }

            else -> {

                "What date and time should I schedule \"${result.title}\"?"
            }
        }
    }

    private fun buildRecurringTimeQuestion(
        result: AIResult
    ): String {

        val repeatText =
            when (
                result.repeatType
                    .lowercase(Locale.ENGLISH)
            ) {

                "weekly" ->
                    if (result.repeatDay.isNotEmpty()) {
                        "every ${result.repeatDay.lowercase(Locale.ENGLISH)}"
                    } else {
                        "every week"
                    }

                "daily" ->
                    "every day"

                "weekday",
                "weekdays" ->
                    "every weekday"

                "monthly" ->
                    "every month"

                else ->
                    "that schedule"
            }

        return "What time should I schedule \"${result.title}\" $repeatText?"
    }

    private fun handleRecurringEvent(
        result: AIResult
    ) {

        if (
            result.time.isBlank()
        ) {

            pendingContext =
                AIPendingContext(
                    intent = "CREATE_RECURRING_EVENT",
                    title = result.title,
                    date = result.date,
                    time = result.time,
                    priority = result.priority,
                    recurring = true,
                    repeatType = result.repeatType,
                    repeatDay = result.repeatDay,
                    repeatDuration =
                        result.repeatDuration,
                    repeatDurationUnit =
                        result.repeatDurationUnit,
                    repeatEndDate =
                        result.repeatEndDate
                )

            addMessage(
                result.response.ifEmpty {
                    buildRecurringTimeQuestion(result)
                },
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        pendingContext = null

        val userId =
            auth.currentUser?.uid
                ?: run {

                    addMessage(
                        "You must be logged in before I can save the recurring event.",
                        isUser = false,
                        saveToFirestore = true
                    )

                    return
                }

        saveRecurringEvent(
            userId = userId,
            result = result
        )
    }

    private fun saveRecurringEvent(
        userId: String,
        result: AIResult
    ) {

        if (
            result.title.isBlank()
        ) {

            addMessage(
                "I need an event title before I can create the recurring event.",
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        if (
            result.date.isBlank()
        ) {

            addMessage(
                "What date should the recurring event start?",
                isUser = false,
                saveToFirestore = true
            )

            pendingContext =
                AIPendingContext(
                    intent = "CREATE_RECURRING_EVENT",
                    title = result.title,
                    date = result.date,
                    time = result.time,
                    priority = result.priority,
                    recurring = true,
                    repeatType = result.repeatType,
                    repeatDay = result.repeatDay,
                    repeatDuration =
                        result.repeatDuration,
                    repeatDurationUnit =
                        result.repeatDurationUnit,
                    repeatEndDate =
                        result.repeatEndDate
                )

            return
        }

        if (
            result.time.isBlank()
        ) {

            addMessage(
                buildRecurringTimeQuestion(result),
                isUser = false,
                saveToFirestore = true
            )

            pendingContext =
                AIPendingContext(
                    intent = "CREATE_RECURRING_EVENT",
                    title = result.title,
                    date = result.date,
                    time = result.time,
                    priority = result.priority,
                    recurring = true,
                    repeatType = result.repeatType,
                    repeatDay = result.repeatDay,
                    repeatDuration =
                        result.repeatDuration,
                    repeatDurationUnit =
                        result.repeatDurationUnit,
                    repeatEndDate =
                        result.repeatEndDate
                )

            return
        }

        val effectiveEndDate =
            calculateEffectiveRepeatEndDate(result)

        val startCalendar =
            parseCalendarDate(result.date)

        if (
            startCalendar == null
        ) {

            addMessage(
                "I couldn't understand the start date for that recurring event.",
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        if (
            effectiveEndDate.isNotBlank()
        ) {

            val endCalendar =
                parseCalendarDate(
                    effectiveEndDate
                )

            if (
                endCalendar == null ||
                endCalendar.before(startCalendar)
            ) {

                addMessage(
                    "The recurring event's end date is before its start date.",
                    isUser = false,
                    saveToFirestore = true
                )

                return
            }
        }

        val eventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        val documentRef =
            eventsRef.document()

        val event =
            hashMapOf<String, Any>(
                "title" to result.title,
                "date" to result.date,
                "dateText" to result.date,
                "time" to result.time,
                "priority" to result.priority,
                "recurring" to true,
                "repeatType" to result.repeatType,
                "repeatDay" to result.repeatDay,
                "repeatDuration" to result.repeatDuration,
                "repeatDurationUnit" to result.repeatDurationUnit,
                "repeatEndDate" to effectiveEndDate,
                "timestamp" to System.currentTimeMillis()
            )

        documentRef
            .set(event)
            .addOnSuccessListener {

                val reminderScheduled =
                    scheduleFirstRecurringReminder(
                        userId = userId,
                        eventId = documentRef.id,
                        result = result,
                        effectiveEndDate =
                            effectiveEndDate
                    )

                val repeatText =
                    buildRecurringDescription(
                        result
                    )

                val reminderText =
                    if (
                        reminderScheduled
                    ) {

                        " The first reminder is scheduled."

                    } else {

                        " The event was saved, but the first reminder could not be scheduled."
                    }

                addMessage(
                    """
                    Got it! I've scheduled "${result.title}" $repeatText.

                    The recurring schedule is stored as one event and the calendar will show each occurrence automatically.

                    Priority: ${priorityName(result.priority)}.$reminderText
                    """.trimIndent(),
                    isUser = false,
                    saveToFirestore = true
                )
            }
            .addOnFailureListener { error ->

                addMessage(
                    "I couldn't save the recurring event. ${error.message ?: ""}".trim(),
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun scheduleFirstRecurringReminder(
        userId: String,
        eventId: String,
        result: AIResult,
        effectiveEndDate: String
    ): Boolean {

        return try {

            val inputFormat =
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    Locale.ENGLISH
                ).apply {

                    timeZone = PH_TIME
                    isLenient = false
                }

            val parsedDate =
                inputFormat.parse(
                    "${result.date} ${result.time}"
                )
                    ?: return false

            val calendar =
                Calendar.getInstance(
                    PH_TIME
                ).apply {

                    timeInMillis =
                        parsedDate.time
                }

            if (
                calendar.timeInMillis <=
                System.currentTimeMillis()
            ) {

                return false
            }

            if (
                effectiveEndDate.isNotBlank()
            ) {

                val endDate =
                    parseCalendarDate(
                        effectiveEndDate
                    )

                if (
                    endDate != null
                ) {

                    val occurrenceDate =
                        calendar.clone()
                                as Calendar

                    occurrenceDate.set(
                        Calendar.HOUR_OF_DAY,
                        0
                    )

                    occurrenceDate.set(
                        Calendar.MINUTE,
                        0
                    )

                    occurrenceDate.set(
                        Calendar.SECOND,
                        0
                    )

                    occurrenceDate.set(
                        Calendar.MILLISECOND,
                        0
                    )

                    if (
                        occurrenceDate.after(
                            endDate
                        )
                    ) {

                        return false
                    }
                }
            }

            ReminderScheduler.scheduleRecurringReminder(

                context = this,

                title =
                    result.title,

                message =
                    "It's time for your ${result.title}.",

                year =
                    calendar.get(
                        Calendar.YEAR
                    ),

                month =
                    calendar.get(
                        Calendar.MONTH
                    ) + 1,

                day =
                    calendar.get(
                        Calendar.DAY_OF_MONTH
                    ),

                hour =
                    calendar.get(
                        Calendar.HOUR_OF_DAY
                    ),

                minute =
                    calendar.get(
                        Calendar.MINUTE
                    ),

                userId =
                    userId,

                eventId =
                    eventId,

                repeatType =
                    result.repeatType,

                repeatDay =
                    result.repeatDay,

                repeatDuration =
                    result.repeatDuration,

                repeatDurationUnit =
                    result.repeatDurationUnit,

                repeatEndDate =
                    effectiveEndDate,

                startDate =
                    result.date
            )

        } catch (_: Exception) {

            false
        }
    }

    private fun calculateEffectiveRepeatEndDate(
        result: AIResult
    ): String {

        val startDate =
            parseCalendarDate(result.date)
                ?: return result.repeatEndDate

        var durationEndDate =
            ""

        if (
            result.repeatDuration > 0 &&
            result.repeatDurationUnit.isNotBlank()
        ) {

            val durationCalendar =
                startDate.clone() as Calendar

            when (
                result.repeatDurationUnit
                    .lowercase(Locale.ENGLISH)
                    .trim()
            ) {

                "day",
                "days" -> {

                    durationCalendar.add(
                        Calendar.DAY_OF_YEAR,
                        result.repeatDuration - 1
                    )
                }

                "week",
                "weeks" -> {

                    durationCalendar.add(
                        Calendar.DAY_OF_YEAR,
                        (
                                result.repeatDuration * 7
                                ) - 1
                    )
                }

                "month",
                "months" -> {

                    durationCalendar.add(
                        Calendar.MONTH,
                        result.repeatDuration
                    )

                    durationCalendar.add(
                        Calendar.DAY_OF_YEAR,
                        -1
                    )
                }

                "year",
                "years" -> {

                    durationCalendar.add(
                        Calendar.YEAR,
                        result.repeatDuration
                    )

                    durationCalendar.add(
                        Calendar.DAY_OF_YEAR,
                        -1
                    )
                }
            }

            durationEndDate =
                formatCalendarDate(
                    durationCalendar
                )
        }

        val explicitEndDate =
            result.repeatEndDate.trim()

        if (
            durationEndDate.isBlank()
        ) {

            return explicitEndDate
        }

        if (
            explicitEndDate.isBlank()
        ) {

            return durationEndDate
        }

        val durationEnd =
            parseCalendarDate(
                durationEndDate
            )

        val explicitEnd =
            parseCalendarDate(
                explicitEndDate
            )

        if (
            durationEnd == null
        ) {

            return explicitEndDate
        }

        if (
            explicitEnd == null
        ) {

            return durationEndDate
        }

        return if (
            explicitEnd.before(durationEnd)
        ) {

            explicitEndDate

        } else {

            durationEndDate
        }
    }

    private fun buildRecurringDescription(
        result: AIResult
    ): String {

        val repeatText =
            when (
                result.repeatType
                    .lowercase(Locale.ENGLISH)
                    .trim()
            ) {

                "weekly" -> {

                    if (
                        result.repeatDay.isNotBlank()
                    ) {

                        "every ${
                            result.repeatDay
                                .lowercase(Locale.ENGLISH)
                                .replaceFirstChar {
                                    it.uppercase()
                                }
                        }"

                    } else {

                        "every week"
                    }
                }

                "daily" ->
                    "every day"

                "weekday",
                "weekdays" ->
                    "every weekday"

                "monthly" ->
                    "every month"

                "yearly",
                "annual",
                "annually" ->
                    "every year"

                else ->
                    "recurrently"
            }

        if (
            result.repeatDuration > 0 &&
            result.repeatDurationUnit.isNotBlank()
        ) {

            val unit =
                pluralizeUnit(
                    result.repeatDurationUnit,
                    result.repeatDuration
                )

            val effectiveEndDate =
                calculateEffectiveRepeatEndDate(
                    result
                )

            return if (
                effectiveEndDate.isNotBlank()
            ) {

                "$repeatText for ${result.repeatDuration} $unit " +
                        "(through ${formatDisplayDate(effectiveEndDate)})"

            } else {

                "$repeatText for ${result.repeatDuration} $unit"
            }
        }

        if (
            result.repeatEndDate.isNotBlank()
        ) {

            return "$repeatText until ${
                formatDisplayDate(
                    result.repeatEndDate
                )
            }"
        }

        return repeatText
    }

    private fun pluralizeUnit(
        unit: String,
        amount: Int
    ): String {

        val normalized =
            unit
                .lowercase(Locale.ENGLISH)
                .trim()

        if (
            amount == 1
        ) {

            return when (normalized) {

                "days",
                "day" ->
                    "day"

                "weeks",
                "week" ->
                    "week"

                "months",
                "month" ->
                    "month"

                "years",
                "year" ->
                    "year"

                else ->
                    normalized
            }
        }

        return when (normalized) {

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
                normalized
        }
    }

    private fun weekdayNumber(
        day: String
    ): Int? {

        return when (
            day
                .lowercase(Locale.ENGLISH)
                .trim()
        ) {

            "sunday",
            "sun" ->
                Calendar.SUNDAY

            "monday",
            "mon" ->
                Calendar.MONDAY

            "tuesday",
            "tue",
            "tues" ->
                Calendar.TUESDAY

            "wednesday",
            "wed" ->
                Calendar.WEDNESDAY

            "thursday",
            "thu",
            "thur",
            "thurs" ->
                Calendar.THURSDAY

            "friday",
            "fri" ->
                Calendar.FRIDAY

            "saturday",
            "sat" ->
                Calendar.SATURDAY

            else ->
                null
        }
    }

    private fun parseCalendarDate(
        rawDate: String
    ): Calendar? {

        if (
            rawDate.isBlank()
        ) {
            return null
        }

        return try {

            val format =
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.ENGLISH
                ).apply {

                    timeZone = PH_TIME
                    isLenient = false
                }

            val parsed =
                format.parse(
                    rawDate
                )
                    ?: return null

            Calendar.getInstance(
                PH_TIME
            ).apply {

                timeInMillis =
                    parsed.time

                set(
                    Calendar.HOUR_OF_DAY,
                    0
                )

                set(
                    Calendar.MINUTE,
                    0
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

        } catch (_: Exception) {

            null
        }
    }

    private fun formatCalendarDate(
        calendar: Calendar
    ): String {

        return SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.ENGLISH
        ).apply {
            timeZone = PH_TIME
        }.format(
            calendar.time
        )
    }

    private fun priorityName(
        priority: Int
    ): String {

        return when (priority) {

            4 -> "Urgent"
            3 -> "High"
            2 -> "Normal"
            1 -> "Low"
            else -> "Normal"
        }
    }

    private fun checkForConflict(
        userId: String,
        title: String,
        date: String,
        time: String,
        priority: Int = 2
    ) {

        val eventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        eventsRef
            .whereEqualTo(
                "date",
                date
            )
            .get()
            .addOnSuccessListener { documents ->

                val requestedStart =
                    timeToMinutes(time)

                val requestedEnd =
                    requestedStart +
                            EVENT_DURATION_MINUTES

                var conflictingDocument:
                        com.google.firebase.firestore.DocumentSnapshot? =
                    null

                var conflictingStart =
                    0

                var conflictingEnd =
                    0

                for (document in documents) {

                    val existingTime =
                        document.getString("time")
                            ?: continue

                    val existingStart =
                        timeToMinutes(existingTime)

                    val existingEnd =
                        existingStart +
                                EVENT_DURATION_MINUTES

                    val conflict =
                        requestedStart < existingEnd &&
                                requestedEnd > existingStart

                    if (conflict) {

                        conflictingDocument =
                            document

                        conflictingStart =
                            existingStart

                        conflictingEnd =
                            existingEnd

                        break
                    }
                }

                if (
                    conflictingDocument == null
                ) {

                    saveEvent(
                        userId = userId,
                        title = title,
                        date = date,
                        time = time,
                        priority = priority
                    )

                    return@addOnSuccessListener
                }

                val existingDocument =
                    conflictingDocument!!

                val existingTitle =
                    existingDocument.getString("title")
                        ?: "Untitled Event"

                val existingTime =
                    existingDocument.getString("time")
                        ?: minutesToTime(conflictingStart)

                val existingPriority =
                    existingDocument
                        .getLong("priority")
                        ?.toInt()
                        ?: 2

                val existingPriorityText =
                    priorityName(existingPriority)

                val newPriorityText =
                    priorityName(priority)

                val conflictText =
                    "That time conflicts with \"$existingTitle\" " +
                            "(${formatMinutes(conflictingStart)}–${formatMinutes(conflictingEnd)})."

                if (
                    priority > existingPriority
                ) {

                    findAvailableTimes(
                        documents = documents,
                        requestedTime = existingTime,
                        excludeEventId = existingDocument.id,
                        additionalBlockedStart = requestedStart,
                        additionalBlockedEnd = requestedEnd
                    ) { suggestions ->

                        if (
                            suggestions.isEmpty()
                        ) {

                            addMessage(
                                """
                                $conflictText

                                "$title" is ${newPriorityText.lowercase()}, while "$existingTitle" is ${existingPriorityText.lowercase()}.

                                I couldn't find another available time for "$existingTitle".

                                The new event has not been scheduled.
                                """.trimIndent(),
                                isUser = false,
                                saveToFirestore = true
                            )

                            return@findAvailableTimes
                        }

                        val primarySuggestion =
                            suggestions.first()

                        pendingSuggestedEvent =
                            AIPendingContext(
                                intent = "CREATE_EVENT",
                                title = title,
                                date = date,
                                time = primarySuggestion,
                                priority = priority,
                                awaitingConfirmation = true,
                                conflictAction =
                                    "MOVE_EXISTING",
                                conflictEventId =
                                    existingDocument.id,
                                conflictEventTitle =
                                    existingTitle,
                                conflictEventTime =
                                    existingTime,
                                conflictEventPriority =
                                    existingPriority
                            )

                        waitingForConfirmation = true

                        val suggestionText =
                            suggestions.joinToString(
                                separator = "\n"
                            ) {
                                "• ${formatDisplayTime(it)}"
                            }

                        addMessage(
                            """
                            $conflictText

                            "$title" has higher priority ($newPriorityText) than "$existingTitle" ($existingPriorityText).

                            I will keep "$title" at ${formatDisplayTime(time)}.

                            Suggested times to move "$existingTitle":
                            $suggestionText

                            Would you like me to move "$existingTitle" to ${formatDisplayTime(primarySuggestion)} and then schedule "$title" at ${formatDisplayTime(time)}?
                            """.trimIndent(),
                            isUser = false,
                            saveToFirestore = true
                        )
                    }

                    return@addOnSuccessListener
                }

                findAvailableTimes(
                    documents = documents,
                    requestedTime = time
                ) { suggestions ->

                    if (
                        suggestions.isEmpty()
                    ) {

                        val comparisonText =
                            if (
                                priority < existingPriority
                            ) {

                                "\"$existingTitle\" has higher priority ($existingPriorityText) than \"$title\" ($newPriorityText)."

                            } else {

                                "Both \"$existingTitle\" and \"$title\" have the same priority ($newPriorityText)."
                            }

                        addMessage(
                            """
                            $conflictText

                            $comparisonText

                            I couldn't find another available time for "$title" that day.

                            The new event has not been scheduled.
                            """.trimIndent(),
                            isUser = false,
                            saveToFirestore = true
                        )

                        return@findAvailableTimes
                    }

                    val primarySuggestion =
                        suggestions.first()

                    pendingSuggestedEvent =
                        AIPendingContext(
                            intent = "CREATE_EVENT",
                            title = title,
                            date = date,
                            time = primarySuggestion,
                            priority = priority,
                            awaitingConfirmation = true,
                            conflictAction =
                                "MOVE_NEW",
                            conflictEventId =
                                existingDocument.id,
                            conflictEventTitle =
                                existingTitle,
                            conflictEventTime =
                                existingTime,
                            conflictEventPriority =
                                existingPriority
                        )

                    waitingForConfirmation = true

                    val suggestionText =
                        suggestions.joinToString(
                            separator = "\n"
                        ) {
                            "• ${formatDisplayTime(it)}"
                        }

                    val comparisonText =
                        if (
                            priority < existingPriority
                        ) {

                            "\"$existingTitle\" has higher priority ($existingPriorityText) than \"$title\" ($newPriorityText)."

                        } else {

                            "Both events have the same priority ($newPriorityText)."
                        }

                    addMessage(
                        """
                        $conflictText

                        $comparisonText

                        I will keep "$existingTitle" at ${formatDisplayTime(existingTime)}.

                        Suggested times for "$title":
                        $suggestionText

                        Would you like to schedule "$title" at ${formatDisplayTime(primarySuggestion)}?
                        """.trimIndent(),
                        isUser = false,
                        saveToFirestore = true
                    )
                }
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't check your schedule for conflicts. Please try again.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun findAvailableTimes(
        documents: QuerySnapshot,
        requestedTime: String,
        excludeEventId: String? = null,
        additionalBlockedStart: Int? = null,
        additionalBlockedEnd: Int? = null,
        callback: (List<String>) -> Unit
    ) {

        val occupied =
            mutableListOf<ScheduleBlock>()

        for (document in documents) {

            if (
                excludeEventId != null &&
                document.id == excludeEventId
            ) {
                continue
            }

            val time =
                document.getString("time")
                    ?: continue

            val title =
                document.getString("title")
                    ?: "Untitled Event"

            val start =
                timeToMinutes(time)

            val end =
                start +
                        EVENT_DURATION_MINUTES

            occupied.add(
                ScheduleBlock(
                    title = title,
                    start = start,
                    end = end
                )
            )
        }

        if (
            additionalBlockedStart != null &&
            additionalBlockedEnd != null
        ) {

            occupied.add(
                ScheduleBlock(
                    title = "Requested Event",
                    start = additionalBlockedStart,
                    end = additionalBlockedEnd
                )
            )
        }

        occupied.sortBy {
            it.start
        }

        val requested =
            timeToMinutes(requestedTime)

        val candidates =
            mutableListOf<ScheduleSuggestion>()

        for (
        candidate in DAY_START_MINUTES..20 * 60 step 30
        ) {

            val candidateEnd =
                candidate +
                        EVENT_DURATION_MINUTES

            if (
                candidateEnd >
                DAY_END_MINUTES
            ) {
                continue
            }

            var conflict =
                false

            for (event in occupied) {

                if (
                    candidate < event.end &&
                    candidateEnd > event.start
                ) {

                    conflict = true
                    break
                }
            }

            if (conflict) {
                continue
            }

            val score =
                calculateScheduleScore(
                    candidate = candidate,
                    duration = EVENT_DURATION_MINUTES,
                    requestedTime = requested,
                    events = occupied
                )

            candidates.add(
                ScheduleSuggestion(
                    time = candidate,
                    score = score
                )
            )
        }

        candidates.sortByDescending {
            it.score
        }

        val suggestions =
            candidates
                .take(3)
                .map {
                    minutesToTime(it.time)
                }

        callback(
            suggestions
        )
    }

    private fun handleConfirmation(
        cmd: String,
        userId: String
    ) {

        val pending =
            pendingSuggestedEvent
                ?: run {
                    waitingForConfirmation = false
                    return
                }

        val lower =
            cmd.trim()
                .lowercase(Locale.ENGLISH)

        if (
            lower == "cancel" ||
            lower == "cancel it" ||
            lower == "never mind" ||
            lower == "nevermind" ||
            lower == "stop" ||
            lower == "abort" ||
            lower == "quit"
        ) {

            pendingSuggestedEvent = null
            waitingForConfirmation = false

            addMessage(
                "Okay, I cancelled the scheduling request.",
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        if (
            lower == "no" ||
            lower == "nope" ||
            lower == "nah" ||
            lower == "not this time" ||
            lower == "not that time" ||
            lower == "no thanks"
        ) {

            addMessage(
                """
                Okay, I won't use that suggested time.

                You can enter another time such as "5 PM", choose another suggested time, or say "cancel".
                """.trimIndent(),
                isUser = false,
                saveToFirestore = true
            )

            waitingForConfirmation = true

            return
        }

        if (
            lower == "yes" ||
            lower == "yes please" ||
            lower == "yeah" ||
            lower == "yep" ||
            lower == "yup" ||
            lower == "okay" ||
            lower == "ok" ||
            lower == "sure" ||
            lower == "do it" ||
            lower == "schedule it" ||
            lower.contains("schedule it") ||
            lower.contains("sounds good")
        ) {

            if (
                pending.conflictAction ==
                "MOVE_EXISTING"
            ) {

                moveExistingEventAndSaveNewEvent(
                    userId = userId,
                    pending = pending
                )

            } else {

                pendingSuggestedEvent = null
                waitingForConfirmation = false

                saveEvent(
                    userId = userId,
                    title = pending.title,
                    date = pending.date,
                    time = pending.time,
                    priority = pending.priority
                )
            }

            return
        }

        val selectedTime =
            extractAlternativeTime(cmd)

        if (
            selectedTime.isNotBlank()
        ) {

            handleSelectedAlternativeTime(
                userId = userId,
                pending = pending,
                selectedTime = selectedTime
            )

            return
        }

        val newResult =
            ai.process(cmd)

        if (
            newResult.time.isNotEmpty()
        ) {

            handleSelectedAlternativeTime(
                userId = userId,
                pending = pending,
                selectedTime = newResult.time
            )

            return
        }

        addMessage(
            "Please choose an available time, enter another time such as \"4 PM\", accept the suggestion, or say \"cancel\".",
            isUser = false,
            saveToFirestore = true
        )
    }

    private fun handleSelectedAlternativeTime(
        userId: String,
        pending: AIPendingContext,
        selectedTime: String
    ) {

        if (
            pending.conflictAction ==
            "MOVE_EXISTING"
        ) {

            checkExistingEventAlternativeTime(
                userId = userId,
                pending = pending,
                selectedTime = selectedTime
            )

        } else {

            checkNewEventAlternativeTime(
                userId = userId,
                pending = pending,
                selectedTime = selectedTime
            )
        }
    }

    private fun checkExistingEventAlternativeTime(
        userId: String,
        pending: AIPendingContext,
        selectedTime: String
    ) {

        val eventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        eventsRef
            .whereEqualTo(
                "date",
                pending.date
            )
            .get()
            .addOnSuccessListener { documents ->

                val selectedStart =
                    timeToMinutes(selectedTime)

                val selectedEnd =
                    selectedStart +
                            EVENT_DURATION_MINUTES

                val newEventRequestedStart =
                    findNewEventRequestedTimeForExistingMove(
                        pending
                    )

                val newEventRequestedEnd =
                    newEventRequestedStart +
                            EVENT_DURATION_MINUTES

                var conflict =
                    false

                for (document in documents) {

                    if (
                        document.id ==
                        pending.conflictEventId
                    ) {
                        continue
                    }

                    val eventTime =
                        document.getString("time")
                            ?: continue

                    val eventStart =
                        timeToMinutes(eventTime)

                    val eventEnd =
                        eventStart +
                                EVENT_DURATION_MINUTES

                    if (
                        selectedStart < eventEnd &&
                        selectedEnd > eventStart
                    ) {

                        conflict = true
                        break
                    }
                }

                if (
                    newEventRequestedStart < selectedEnd &&
                    newEventRequestedEnd > selectedStart
                ) {

                    conflict = true
                }

                if (!conflict) {

                    pendingSuggestedEvent =
                        pending.copy(
                            time = selectedTime
                        )

                    waitingForConfirmation = true

                    addMessage(
                        """
                        ${formatDisplayTime(selectedTime)} is available for "${pending.conflictEventTitle}".

                        Would you like me to move "${pending.conflictEventTitle}" to ${formatDisplayTime(selectedTime)} and then schedule "${pending.title}" at ${formatDisplayTime(minutesToTime(newEventRequestedStart))}?
                        """.trimIndent(),
                        isUser = false,
                        saveToFirestore = true
                    )

                    return@addOnSuccessListener
                }

                findAvailableTimes(
                    documents = documents,
                    requestedTime = pending.conflictEventTime,
                    excludeEventId = pending.conflictEventId,
                    additionalBlockedStart =
                        newEventRequestedStart,
                    additionalBlockedEnd =
                        newEventRequestedEnd
                ) { suggestions ->

                    if (
                        suggestions.isEmpty()
                    ) {

                        addMessage(
                            "${formatDisplayTime(selectedTime)} is also unavailable. I couldn't find another available time for \"${pending.conflictEventTitle}\".",
                            isUser = false,
                            saveToFirestore = true
                        )

                        return@findAvailableTimes
                    }

                    val primary =
                        suggestions.first()

                    pendingSuggestedEvent =
                        pending.copy(
                            time = primary
                        )

                    waitingForConfirmation = true

                    val suggestionText =
                        suggestions.joinToString(
                            separator = "\n"
                        ) {
                            "• ${formatDisplayTime(it)}"
                        }

                    addMessage(
                        """
                        ${formatDisplayTime(selectedTime)} is not available for "${pending.conflictEventTitle}".

                        Other available times:
                        $suggestionText

                        Would you like to move "${pending.conflictEventTitle}" to ${formatDisplayTime(primary)}?
                        """.trimIndent(),
                        isUser = false,
                        saveToFirestore = true
                    )
                }
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't check that time against your schedule.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun checkNewEventAlternativeTime(
        userId: String,
        pending: AIPendingContext,
        selectedTime: String
    ) {

        val eventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        eventsRef
            .whereEqualTo(
                "date",
                pending.date
            )
            .get()
            .addOnSuccessListener { documents ->

                val requestedStart =
                    timeToMinutes(selectedTime)

                val requestedEnd =
                    requestedStart +
                            EVENT_DURATION_MINUTES

                var conflictingTitle =
                    ""

                for (document in documents) {

                    val existingTime =
                        document.getString("time")
                            ?: continue

                    val existingTitle =
                        document.getString("title")
                            ?: "Untitled Event"

                    val existingStart =
                        timeToMinutes(existingTime)

                    val existingEnd =
                        existingStart +
                                EVENT_DURATION_MINUTES

                    if (
                        requestedStart < existingEnd &&
                        requestedEnd > existingStart
                    ) {

                        conflictingTitle =
                            existingTitle

                        break
                    }
                }

                if (
                    conflictingTitle.isEmpty()
                ) {

                    pendingSuggestedEvent = null
                    waitingForConfirmation = false

                    saveEvent(
                        userId = userId,
                        title = pending.title,
                        date = pending.date,
                        time = selectedTime,
                        priority = pending.priority
                    )

                    return@addOnSuccessListener
                }

                findAvailableTimes(
                    documents = documents,
                    requestedTime = selectedTime
                ) { suggestions ->

                    if (
                        suggestions.isEmpty()
                    ) {

                        addMessage(
                            "${formatDisplayTime(selectedTime)} also conflicts with \"$conflictingTitle\", and I couldn't find another available time that day.",
                            isUser = false,
                            saveToFirestore = true
                        )

                        return@findAvailableTimes
                    }

                    val primary =
                        suggestions.first()

                    pendingSuggestedEvent =
                        pending.copy(
                            time = primary
                        )

                    waitingForConfirmation = true

                    val suggestionText =
                        suggestions.joinToString(
                            separator = "\n"
                        ) {
                            "• ${formatDisplayTime(it)}"
                        }

                    addMessage(
                        """
                        ${formatDisplayTime(selectedTime)} conflicts with "$conflictingTitle".

                        Other available times:
                        $suggestionText

                        Would you like to schedule "${pending.title}" at ${formatDisplayTime(primary)}?
                        """.trimIndent(),
                        isUser = false,
                        saveToFirestore = true
                    )
                }
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't check that time against your schedule.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun findNewEventRequestedTimeForExistingMove(
        pending: AIPendingContext
    ): Int {

        return timeToMinutes(
            pending.conflictEventTime
        )
    }

    private fun moveExistingEventAndSaveNewEvent(
        userId: String,
        pending: AIPendingContext
    ) {

        val eventId =
            pending.conflictEventId

        if (
            eventId.isBlank()
        ) {

            clearAllPendingStates()

            addMessage(
                "I couldn't identify the event that should be moved.",
                isUser = false,
                saveToFirestore = true
            )

            return
        }

        val existingEventRef =
            db.collection("users")
                .document(userId)
                .collection("events")
                .document(eventId)

        existingEventRef
            .update(
                mapOf(
                    "time" to pending.time
                )
            )
            .addOnSuccessListener {

                pendingSuggestedEvent = null
                waitingForConfirmation = false

                val newEventTime =
                    pending.conflictEventTime

                addMessage(
                    "Moved \"${pending.conflictEventTitle}\" to ${formatDisplayTime(pending.time)}.",
                    isUser = false,
                    saveToFirestore = true
                )

                saveEvent(
                    userId = userId,
                    title = pending.title,
                    date = pending.date,
                    time = newEventTime,
                    priority = pending.priority
                )
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't move \"${pending.conflictEventTitle}\". The new event was not scheduled.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun extractAlternativeTime(
        input: String
    ): String {

        val cleaned =
            input
                .trim()
                .lowercase(Locale.ENGLISH)

        val twelveHourRegex =
            Regex(
                """\b(0?[1-9]|1[0-2])(?:\s*:\s*([0-5]\d))?\s*(am|pm)\b""",
                RegexOption.IGNORE_CASE
            )

        val match12 =
            twelveHourRegex.find(cleaned)

        if (
            match12 != null
        ) {

            val hour =
                match12.groupValues[1]
                    .toInt()

            val minute =
                if (
                    match12.groupValues[2].isNotBlank()
                ) {
                    match12.groupValues[2].toInt()
                } else {
                    0
                }

            val meridiem =
                match12.groupValues[3]
                    .lowercase(Locale.ENGLISH)

            var hour24 =
                hour

            if (
                meridiem == "pm" &&
                hour != 12
            ) {
                hour24 += 12
            }

            if (
                meridiem == "am" &&
                hour == 12
            ) {
                hour24 = 0
            }

            return String.format(
                Locale.ENGLISH,
                "%02d:%02d",
                hour24,
                minute
            )
        }

        val twentyFourHourRegex =
            Regex(
                """\b([01]\d|2[0-3])\s*:\s*([0-5]\d)\b"""
            )

        val match24 =
            twentyFourHourRegex.find(cleaned)

        if (
            match24 != null
        ) {

            return String.format(
                Locale.ENGLISH,
                "%02d:%02d",
                match24.groupValues[1].toInt(),
                match24.groupValues[2].toInt()
            )
        }

        return ""
    }

    private fun calculateScheduleScore(
        candidate: Int,
        duration: Int,
        requestedTime: Int,
        events: List<ScheduleBlock>
    ): Int {

        var score =
            100

        val candidateEnd =
            candidate + duration

        val distance =
            abs(
                candidate - requestedTime
            )

        score -=
            (distance / 30) * 10

        when {

            candidate in 9 * 60..18 * 60 -> {
                score += 15
            }

            candidate in 8 * 60..20 * 60 -> {
                score += 5
            }

            else -> {
                score -= 20
            }
        }

        var nearestBeforeGap =
            Int.MAX_VALUE

        var nearestAfterGap =
            Int.MAX_VALUE

        for (event in events) {

            if (
                event.end <= candidate
            ) {

                val gap =
                    candidate -
                            event.end

                if (
                    gap < nearestBeforeGap
                ) {
                    nearestBeforeGap = gap
                }
            }

            if (
                event.start >= candidateEnd
            ) {

                val gap =
                    event.start -
                            candidateEnd

                if (
                    gap < nearestAfterGap
                ) {
                    nearestAfterGap = gap
                }
            }
        }

        if (
            nearestBeforeGap >= 30 &&
            nearestBeforeGap != Int.MAX_VALUE
        ) {
            score += 10
        }

        if (
            nearestAfterGap >= 30 &&
            nearestAfterGap != Int.MAX_VALUE
        ) {
            score += 10
        }

        if (
            nearestBeforeGap in 1..29
        ) {
            score -= 15
        }

        if (
            nearestAfterGap in 1..29
        ) {
            score -= 15
        }

        if (
            nearestBeforeGap >= 60 &&
            nearestBeforeGap != Int.MAX_VALUE
        ) {
            score += 5
        }

        if (
            nearestAfterGap >= 60 &&
            nearestAfterGap != Int.MAX_VALUE
        ) {
            score += 5
        }

        return score
    }

    private fun saveEvent(
        userId: String,
        title: String,
        date: String,
        time: String,
        priority: Int = 2
    ) {

        val event =
            hashMapOf<String, Any>(
                "title" to title,
                "date" to date,
                "dateText" to date,
                "time" to time,
                "priority" to priority,
                "recurring" to false,
                "repeatType" to "",
                "repeatDay" to "",
                "repeatDuration" to 0,
                "repeatDurationUnit" to "",
                "repeatEndDate" to "",
                "timestamp" to System.currentTimeMillis()
            )

        db.collection("users")
            .document(userId)
            .collection("events")
            .add(event)
            .addOnSuccessListener { documentReference ->

                val eventId =
                    documentReference.id

                val reminderScheduled =
                    scheduleEventReminder(
                        userId = userId,
                        eventId = eventId,
                        title = title,
                        date = date,
                        time = time
                    )

                val reminderText =
                    if (reminderScheduled) {
                        " Reminder is scheduled."
                    } else {
                        " The event was saved, but the reminder could not be scheduled."
                    }

                addMessage(
                    "Got it! Scheduled \"$title\" for ${formatDisplayDate(date)} at ${formatDisplayTime(time)}. " +
                            "Priority: ${priorityName(priority)}.$reminderText",
                    isUser = false,
                    saveToFirestore = true
                )
            }
            .addOnFailureListener {

                addMessage(
                    "Failed to save event.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun scheduleEventReminder(
        userId: String,
        eventId: String,
        title: String,
        date: String,
        time: String
    ): Boolean {

        return try {

            val inputFormat =
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    Locale.ENGLISH
                ).apply {

                    timeZone = PH_TIME
                    isLenient = false
                }

            val parsedDate =
                inputFormat.parse(
                    "$date $time"
                )
                    ?: return false

            val calendar =
                Calendar.getInstance(
                    PH_TIME
                ).apply {

                    timeInMillis =
                        parsedDate.time
                }

            ReminderScheduler.scheduleReminder(

                context = this,

                title = title,

                message =
                    "It's time for your $title.",

                year =
                    calendar.get(
                        Calendar.YEAR
                    ),

                month =
                    calendar.get(
                        Calendar.MONTH
                    ) + 1,

                day =
                    calendar.get(
                        Calendar.DAY_OF_MONTH
                    ),

                hour =
                    calendar.get(
                        Calendar.HOUR_OF_DAY
                    ),

                minute =
                    calendar.get(
                        Calendar.MINUTE
                    ),

                userId = userId,
                eventId = eventId
            )

        } catch (_: Exception) {

            false
        }
    }

    private fun updateEvent(
        userId: String,
        targetTitle: String,
        newDate: String,
        newTime: String,
        fallbackTitle: String
    ) {

        val userEventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        userEventsRef
            .get()
            .addOnSuccessListener { documents ->

                var matchedDocId: String? = null
                var matchedTitle = ""

                for (doc in documents) {

                    val title =
                        doc.getString("title")
                            ?: ""

                    if (
                        title.equals(
                            targetTitle,
                            ignoreCase = true
                        ) ||
                        title.lowercase()
                            .contains(
                                targetTitle.lowercase()
                            )
                    ) {

                        matchedDocId =
                            doc.id

                        matchedTitle =
                            title

                        break
                    }
                }

                if (
                    matchedDocId == null
                ) {

                    addMessage(
                        "I couldn't find an event named \"$targetTitle\".",
                        isUser = false,
                        saveToFirestore = true
                    )

                    return@addOnSuccessListener
                }

                val updates =
                    hashMapOf<String, Any>()

                if (
                    newDate.isNotEmpty()
                ) {

                    updates["date"] =
                        newDate

                    updates["dateText"] =
                        newDate
                }

                if (
                    newTime.isNotEmpty()
                ) {

                    updates["time"] =
                        newTime
                }

                if (
                    updates.isEmpty()
                ) {

                    addMessage(
                        "What new date or time would you like to set for \"$matchedTitle\"?",
                        isUser = false,
                        saveToFirestore = true
                    )

                    return@addOnSuccessListener
                }

                userEventsRef
                    .document(matchedDocId)
                    .update(updates)
                    .addOnSuccessListener {

                        val dateText =
                            if (
                                newDate.isNotEmpty()
                            ) {
                                " Date: ${formatDisplayDate(newDate)}."
                            } else {
                                ""
                            }

                        val timeText =
                            if (
                                newTime.isNotEmpty()
                            ) {
                                " Time: ${formatDisplayTime(newTime)}."
                            } else {
                                ""
                            }

                        addMessage(
                            "Updated \"$matchedTitle\"!$dateText$timeText",
                            isUser = false,
                            saveToFirestore = true
                        )
                    }
                    .addOnFailureListener {

                        addMessage(
                            "Failed to update \"$matchedTitle\".",
                            isUser = false,
                            saveToFirestore = true
                        )
                    }
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't retrieve your events.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun deleteEvent(
        userId: String,
        targetTitle: String
    ) {

        val userEventsRef =
            db.collection("users")
                .document(userId)
                .collection("events")

        userEventsRef
            .get()
            .addOnSuccessListener { documents ->

                var matchedDocId: String? = null
                var matchedTitle = ""

                for (doc in documents) {

                    val title =
                        doc.getString("title")
                            ?: ""

                    if (
                        title.equals(
                            targetTitle,
                            ignoreCase = true
                        ) ||
                        title.lowercase()
                            .contains(
                                targetTitle.lowercase()
                            )
                    ) {

                        matchedDocId =
                            doc.id

                        matchedTitle =
                            title

                        break
                    }
                }

                if (
                    matchedDocId == null
                ) {

                    addMessage(
                        "I couldn't find an event named \"$targetTitle\" to delete.",
                        isUser = false,
                        saveToFirestore = true
                    )

                    return@addOnSuccessListener
                }

                userEventsRef
                    .document(matchedDocId)
                    .delete()
                    .addOnSuccessListener {

                        addMessage(
                            "Deleted \"$matchedTitle\" from your events.",
                            isUser = false,
                            saveToFirestore = true
                        )
                    }
                    .addOnFailureListener {

                        addMessage(
                            "Failed to delete \"$matchedTitle\".",
                            isUser = false,
                            saveToFirestore = true
                        )
                    }
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't retrieve your events.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun extractListDate(
        command: String
    ): String {

        val lower =
            command.lowercase(Locale.ENGLISH)

        val calendar =
            Calendar.getInstance(PH_TIME)

        when {

            Regex("\\btoday\\b")
                .containsMatchIn(lower) -> {
            }

            Regex("\\btomorrow\\b")
                .containsMatchIn(lower) -> {

                calendar.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }

            Regex("\\byesterday\\b")
                .containsMatchIn(lower) -> {

                calendar.add(
                    Calendar.DAY_OF_YEAR,
                    -1
                )
            }

            else -> return ""
        }

        return SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.ENGLISH
        ).apply {
            timeZone = PH_TIME
        }.format(calendar.time)
    }

    private fun loadEvents(
        userId: String,
        targetDate: String = ""
    ) {

        var query:
                com.google.firebase.firestore.Query =
            db.collection("users")
                .document(userId)
                .collection("events")

        if (
            targetDate.isBlank()
        ) {

            query =
                query
        }

        query
            .get()
            .addOnSuccessListener { documents ->

                val matchingDocuments =
                    if (
                        targetDate.isBlank()
                    ) {

                        documents.documents

                    } else {

                        documents.documents.filter { document ->

                            val startDate =
                                document.getString(
                                    "date"
                                ) ?: ""

                            val recurring =
                                document.getBoolean(
                                    "recurring"
                                ) ?: false

                            if (!recurring) {

                                startDate ==
                                        targetDate

                            } else {

                                recurringEventOccursOnDate(
                                    document = document,
                                    targetDate =
                                        targetDate
                                )
                            }
                        }
                    }

                if (
                    matchingDocuments.isEmpty()
                ) {

                    val msg =
                        if (
                            targetDate.isNotEmpty()
                        ) {

                            "You don't have any events scheduled for ${formatDisplayDate(targetDate)}."

                        } else {

                            "You don't have any events scheduled."
                        }

                    addMessage(
                        msg,
                        isUser = false,
                        saveToFirestore = true
                    )

                    return@addOnSuccessListener
                }

                val titleHeader =
                    if (
                        targetDate.isNotEmpty()
                    ) {

                        "Scheduled Events for ${formatDisplayDate(targetDate)}:\n\n"

                    } else {

                        "Your Scheduled Events:\n\n"
                    }

                val response =
                    StringBuilder(titleHeader)

                for (document in matchingDocuments) {

                    val title =
                        document.getString("title")
                            ?: "Untitled"

                    val date =
                        document.getString("date")
                            ?: ""

                    val time =
                        document.getString("time")
                            ?: ""

                    val priority =
                        document.getLong("priority")
                            ?.toInt()
                            ?: 2

                    val recurring =
                        document.getBoolean("recurring")
                            ?: false

                    response.append(
                        "• $title\n"
                    )

                    val displayDate =
                        if (
                            targetDate.isNotBlank() &&
                            recurring
                        ) {

                            targetDate

                        } else {

                            date
                        }

                    val dateStr =
                        if (
                            displayDate.isNotEmpty()
                        ) {
                            formatDisplayDate(displayDate)
                        } else {
                            ""
                        }

                    val timeStr =
                        if (
                            time.isNotEmpty()
                        ) {
                            " at ${formatDisplayTime(time)}"
                        } else {
                            ""
                        }

                    response.append(
                        "  $dateStr$timeStr\n"
                    )

                    if (
                        recurring
                    ) {

                        val repeatType =
                            document.getString(
                                "repeatType"
                            ) ?: ""

                        val repeatDay =
                            document.getString(
                                "repeatDay"
                            ) ?: ""

                        val repeatDuration =
                            document.getLong(
                                "repeatDuration"
                            )?.toInt()
                                ?: 0

                        val repeatDurationUnit =
                            document.getString(
                                "repeatDurationUnit"
                            ) ?: ""

                        val repeatEndDate =
                            document.getString(
                                "repeatEndDate"
                            ) ?: ""

                        val repeatText =
                            when (
                                repeatType.lowercase(
                                    Locale.ENGLISH
                                )
                            ) {

                                "weekly" ->
                                    if (
                                        repeatDay.isNotBlank()
                                    ) {
                                        "Every ${repeatDay.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }}"
                                    } else {
                                        "Every week"
                                    }

                                "daily" ->
                                    "Every day"

                                "weekday",
                                "weekdays" ->
                                    "Every weekday"

                                "monthly" ->
                                    "Every month"

                                else ->
                                    "Recurring"
                            }

                        response.append(
                            "  $repeatText"
                        )

                        if (
                            repeatDuration > 0 &&
                            repeatDurationUnit.isNotBlank()
                        ) {

                            response.append(
                                " for $repeatDuration ${
                                    pluralizeUnit(
                                        repeatDurationUnit,
                                        repeatDuration
                                    )
                                }"
                            )
                        }

                        response.append(
                            "\n"
                        )

                        if (
                            repeatEndDate.isNotBlank()
                        ) {

                            response.append(
                                "  Through ${formatDisplayDate(repeatEndDate)}\n"
                            )
                        }
                    }

                    response.append(
                        "  Priority: ${priorityName(priority)}\n\n"
                    )
                }

                addMessage(
                    response.toString().trimEnd(),
                    isUser = false,
                    saveToFirestore = true
                )
            }
            .addOnFailureListener {

                addMessage(
                    "I couldn't retrieve your events.",
                    isUser = false,
                    saveToFirestore = true
                )
            }
    }

    private fun recurringEventOccursOnDate(
        document:
        com.google.firebase.firestore.DocumentSnapshot,
        targetDate: String
    ): Boolean {

        val startDateString =
            document.getString("date")
                ?: return false

        val startDate =
            parseCalendarDate(
                startDateString
            )
                ?: return false

        val targetCalendar =
            parseCalendarDate(
                targetDate
            )
                ?: return false

        if (
            targetCalendar.before(startDate)
        ) {
            return false
        }

        val repeatType =
            document.getString(
                "repeatType"
            )
                ?.lowercase(Locale.ENGLISH)
                ?.trim()
                ?: return false

        val repeatDay =
            document.getString(
                "repeatDay"
            )
                ?.lowercase(Locale.ENGLISH)
                ?.trim()
                ?: ""

        val repeatDuration =
            document.getLong(
                "repeatDuration"
            )?.toInt()
                ?: 0

        val repeatDurationUnit =
            document.getString(
                "repeatDurationUnit"
            ) ?: ""

        val repeatEndDate =
            document.getString(
                "repeatEndDate"
            ) ?: ""

        var endDate: Calendar? =
            if (
                repeatEndDate.isNotBlank()
            ) {
                parseCalendarDate(
                    repeatEndDate
                )
            } else {
                null
            }

        if (
            repeatDuration > 0 &&
            repeatDurationUnit.isNotBlank()
        ) {

            val durationEnd =
                startDate.clone() as Calendar

            when (
                repeatDurationUnit
                    .lowercase(Locale.ENGLISH)
                    .trim()
            ) {

                "day",
                "days" -> {

                    durationEnd.add(
                        Calendar.DAY_OF_YEAR,
                        repeatDuration - 1
                    )
                }

                "week",
                "weeks" -> {

                    durationEnd.add(
                        Calendar.DAY_OF_YEAR,
                        (
                                repeatDuration * 7
                                ) - 1
                    )
                }

                "month",
                "months" -> {

                    durationEnd.add(
                        Calendar.MONTH,
                        repeatDuration
                    )

                    durationEnd.add(
                        Calendar.DAY_OF_YEAR,
                        -1
                    )
                }

                "year",
                "years" -> {

                    durationEnd.add(
                        Calendar.YEAR,
                        repeatDuration
                    )

                    durationEnd.add(
                        Calendar.DAY_OF_YEAR,
                        -1
                    )
                }
            }

            endDate =
                if (
                    endDate == null ||
                    durationEnd.before(endDate)
                ) {

                    durationEnd

                } else {

                    endDate
                }
        }

        if (
            endDate != null &&
            targetCalendar.after(endDate)
        ) {

            return false
        }

        return when (repeatType) {

            "daily" -> {

                true
            }

            "weekday",
            "weekdays" -> {

                val day =
                    targetCalendar.get(
                        Calendar.DAY_OF_WEEK
                    )

                day != Calendar.SATURDAY &&
                        day != Calendar.SUNDAY
            }

            "weekly" -> {

                val requestedDay =
                    weekdayNumber(
                        repeatDay
                    )

                if (
                    requestedDay != null
                ) {

                    targetCalendar.get(
                        Calendar.DAY_OF_WEEK
                    ) == requestedDay

                } else {

                    targetCalendar.get(
                        Calendar.DAY_OF_WEEK
                    ) ==
                            startDate.get(
                                Calendar.DAY_OF_WEEK
                            )
                }
            }

            "monthly" -> {

                targetCalendar.get(
                    Calendar.DAY_OF_MONTH
                ) ==
                        startDate.get(
                            Calendar.DAY_OF_MONTH
                        )
            }

            else -> false
        }
    }

    private fun saveChatMessage(
        text: String,
        isUser: Boolean
    ) {

        val userId =
            auth.currentUser?.uid
                ?: return

        val chatMessage =
            hashMapOf(
                "message" to text,
                "sender" to if (isUser) {
                    "user"
                } else {
                    "bot"
                },
                "timestamp" to System.currentTimeMillis()
            )

        db.collection("users")
            .document(userId)
            .collection("chatHistory")
            .add(chatMessage)
    }

    private fun loadChatHistory() {

        val userId =
            auth.currentUser?.uid
                ?: return

        db.collection("users")
            .document(userId)
            .collection("chatHistory")
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { documents ->

                for (document in documents) {

                    val message =
                        document.getString("message")
                            ?: ""

                    val sender =
                        document.getString("sender")
                            ?: "bot"

                    addMessage(
                        message,
                        isUser =
                            sender == "user",
                        saveToFirestore = false
                    )
                }
            }
    }

    private fun addMessage(
        text: String,
        isUser: Boolean,
        saveToFirestore: Boolean
    ) {

        val tv =
            TextView(this).apply {

                this.text = text

                setTextColor(
                    if (isUser) {
                        0xFFFFFFFF.toInt()
                    } else {
                        0xFF333333.toInt()
                    }
                )

                background =
                    if (isUser) {
                        getDrawable(
                            R.drawable.bg_chat_user
                        )
                    } else {
                        getDrawable(
                            R.drawable.bg_chat_ai
                        )
                    }

                maxWidth =
                    (resources.displayMetrics.widthPixels * 0.78f)
                        .toInt()

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {

                        setMargins(
                            0,
                            0,
                            0,
                            12
                        )

                        gravity =
                            if (isUser) {
                                Gravity.END
                            } else {
                                Gravity.START
                            }
                    }
            }

        chatContainer.addView(tv)

        if (saveToFirestore) {

            saveChatMessage(
                text,
                isUser
            )
        }

        chatContainer.post {

            val scrollView =
                chatContainer.parent

            if (scrollView is ScrollView) {

                scrollView.fullScroll(
                    View.FOCUS_DOWN
                )
            }
        }
    }

    private fun timeToMinutes(
        rawTime: String
    ): Int {

        return try {

            val parts =
                rawTime.split(":")

            val hour =
                parts[0].toInt()

            val minute =
                parts[1].toInt()

            hour * 60 + minute

        } catch (_: Exception) {

            0
        }
    }

    private fun minutesToTime(
        minutes: Int
    ): String {

        val hour =
            minutes / 60

        val minute =
            minutes % 60

        return String.format(
            Locale.ENGLISH,
            "%02d:%02d",
            hour,
            minute
        )
    }

    private fun formatMinutes(
        minutes: Int
    ): String {

        return formatDisplayTime(
            minutesToTime(minutes)
        )
    }

    private fun formatDisplayDate(
        rawDate: String
    ): String {

        return try {

            val inputFormat =
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.ENGLISH
                ).apply {

                    timeZone = PH_TIME
                    isLenient = false
                }

            val outputFormat =
                SimpleDateFormat(
                    "MMMM d, yyyy",
                    Locale.ENGLISH
                ).apply {

                    timeZone = PH_TIME
                }

            val parsed =
                inputFormat.parse(rawDate)

            if (
                parsed != null
            ) {
                outputFormat.format(parsed)
            } else {
                rawDate
            }

        } catch (_: Exception) {

            rawDate
        }
    }

    private fun requestNotificationPermission() {

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    1001
                )
            }
        }
    }

    private fun formatDisplayTime(
        rawTime: String
    ): String {

        return try {

            val inputFormat =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.ENGLISH
                )

            val outputFormat =
                SimpleDateFormat(
                    "h:mm a",
                    Locale.ENGLISH
                )

            val parsed =
                inputFormat.parse(rawTime)

            if (
                parsed != null
            ) {
                outputFormat.format(parsed)
            } else {
                rawTime
            }

        } catch (_: Exception) {

            rawTime
        }
    }
}
