package com.example.calendarsms.ai

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class OptimizerEvent(
    val id: String = "",
    val title: String = "",
    val date: String = "",
    val time: String = "",
    val durationMinutes: Int = 30,
    val priority: Int = 2
)

data class OptimizedTimeSlot(
    val date: String,
    val startTime: String,
    val endTime: String,
    val score: Int,
    val reason: String
)

data class ScheduleOptimizationResult(
    val success: Boolean,
    val requestedDate: String = "",
    val requestedDurationMinutes: Int = 30,
    val bestSlot: OptimizedTimeSlot? = null,
    val alternatives: List<OptimizedTimeSlot> = emptyList(),
    val message: String = ""
)

class ScheduleOptimizer {

    companion object {
        private const val DEFAULT_DURATION_MINUTES = 30
        private const val DEFAULT_START_HOUR = 8
        private const val DEFAULT_END_HOUR = 20
        private const val SLOT_INTERVAL_MINUTES = 30

        private const val HIGH_PRIORITY = 3
        private const val URGENT_PRIORITY = 4

        private val TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm")
    }

    fun findBestTimes(
        date: String,
        durationMinutes: Int = DEFAULT_DURATION_MINUTES,
        existingEvents: List<OptimizerEvent>,
        preferredStart: String = "",
        preferredEnd: String = "",
        priority: Int = 2
    ): ScheduleOptimizationResult {

        if (date.isBlank()) {
            return ScheduleOptimizationResult(
                success = false,
                message = "I need a date before I can optimize the schedule."
            )
        }

        val requestedDuration =
            durationMinutes.coerceAtLeast(30)

        val startBoundary =
            parseTimeOrDefault(
                preferredStart,
                LocalTime.of(DEFAULT_START_HOUR, 0)
            )

        val endBoundary =
            parseTimeOrDefault(
                preferredEnd,
                LocalTime.of(DEFAULT_END_HOUR, 0)
            )

        val normalizedEvents =
            existingEvents.filter {
                it.date == date &&
                        it.time.isNotBlank()
            }

        val candidates =
            mutableListOf<OptimizedTimeSlot>()

        var candidateStart = startBoundary

        while (
            candidateStart.plusMinutes(
                requestedDuration.toLong()
            ) <= endBoundary
        ) {

            val candidateEnd =
                candidateStart.plusMinutes(
                    requestedDuration.toLong()
                )

            if (
                !hasConflict(
                    candidateStart,
                    candidateEnd,
                    normalizedEvents
                )
            ) {

                val score =
                    calculateScore(
                        candidateStart = candidateStart,
                        candidateEnd = candidateEnd,
                        events = normalizedEvents,
                        priority = priority,
                        startBoundary = startBoundary,
                        endBoundary = endBoundary
                    )

                val reason =
                    buildReason(
                        candidateStart = candidateStart,
                        candidateEnd = candidateEnd,
                        events = normalizedEvents,
                        priority = priority
                    )

                candidates.add(
                    OptimizedTimeSlot(
                        date = date,
                        startTime =
                            candidateStart.format(
                                TIME_FORMATTER
                            ),
                        endTime =
                            candidateEnd.format(
                                TIME_FORMATTER
                            ),
                        score = score,
                        reason = reason
                    )
                )
            }

            candidateStart =
                candidateStart.plusMinutes(
                    SLOT_INTERVAL_MINUTES.toLong()
                )
        }

        val ranked =
            candidates.sortedByDescending {
                it.score
            }

        if (ranked.isEmpty()) {
            return ScheduleOptimizationResult(
                success = false,
                requestedDate = date,
                requestedDurationMinutes =
                    requestedDuration,
                message =
                    "I couldn't find an available time on $date."
            )
        }

        val best = ranked.first()

        val alternatives =
            ranked
                .drop(1)
                .take(2)

        return ScheduleOptimizationResult(
            success = true,
            requestedDate = date,
            requestedDurationMinutes =
                requestedDuration,
            bestSlot = best,
            alternatives = alternatives,
            message =
                buildResultMessage(
                    best,
                    alternatives
                )
        )
    }

    private fun hasConflict(
        candidateStart: LocalTime,
        candidateEnd: LocalTime,
        events: List<OptimizerEvent>
    ): Boolean {

        for (event in events) {

            val eventStart =
                parseTime(event.time)
                    ?: continue

            val eventEnd =
                eventStart.plusMinutes(
                    event.durationMinutes
                        .coerceAtLeast(30)
                        .toLong()
                )

            val overlaps =
                candidateStart < eventEnd &&
                        candidateEnd > eventStart

            if (overlaps) {
                return true
            }
        }

        return false
    }

    private fun calculateScore(
        candidateStart: LocalTime,
        candidateEnd: LocalTime,
        events: List<OptimizerEvent>,
        priority: Int,
        startBoundary: LocalTime,
        endBoundary: LocalTime
    ): Int {

        var score = 50

        val hour = candidateStart.hour

        when (hour) {
            in 9..11 -> score += 20
            in 13..16 -> score += 15
            8 -> score += 5
            in 17..18 -> score += 5
            else -> score -= 10
        }

        val previousEvent =
            findPreviousEvent(
                candidateStart,
                events
            )

        val nextEvent =
            findNextEvent(
                candidateEnd,
                events
            )

        if (previousEvent != null) {

            val previousStart =
                parseTime(previousEvent.time)

            if (previousStart != null) {

                val previousEnd =
                    previousStart.plusMinutes(
                        previousEvent.durationMinutes
                            .coerceAtLeast(30)
                            .toLong()
                    )

                val gapBefore =
                    Duration.between(
                        previousEnd,
                        candidateStart
                    ).toMinutes()

                if (gapBefore in 0..30) {
                    score -= 10
                }

                if (gapBefore >= 60) {
                    score += 8
                }
            }

        } else {
            score += 5
        }

        if (nextEvent != null) {

            val nextStart =
                parseTime(nextEvent.time)

            if (nextStart != null) {

                val gapAfter =
                    Duration.between(
                        candidateEnd,
                        nextStart
                    ).toMinutes()

                if (gapAfter in 0..30) {
                    score -= 10
                }

                if (gapAfter >= 60) {
                    score += 8
                }

            } else {
                score += 5
            }

        } else {
            score += 5
        }

        when (priority) {
            URGENT_PRIORITY ->
                if (hour <= 12) {
                    score += 12
                }

            HIGH_PRIORITY ->
                if (hour <= 13) {
                    score += 8
                }
        }

        score +=
            (
                    endBoundary.hour - hour
                    )
                .coerceAtLeast(0)
                .coerceAtMost(5)

        val minutesFromStart =
            Duration.between(
                startBoundary,
                candidateStart
            ).toMinutes()

        if (
            minutesFromStart >= 240 &&
            priority >= HIGH_PRIORITY
        ) {
            score -= 5
        }

        return score
    }

    private fun findPreviousEvent(
        candidateStart: LocalTime,
        events: List<OptimizerEvent>
    ): OptimizerEvent? {

        return events
            .mapNotNull { event ->

                val time =
                    parseTime(event.time)

                if (
                    time != null &&
                    time < candidateStart
                ) {
                    event to time
                } else {
                    null
                }
            }
            .maxByOrNull {
                it.second
            }
            ?.first
    }

    private fun findNextEvent(
        candidateEnd: LocalTime,
        events: List<OptimizerEvent>
    ): OptimizerEvent? {

        return events
            .mapNotNull { event ->

                val time =
                    parseTime(event.time)

                if (
                    time != null &&
                    time >= candidateEnd
                ) {
                    event to time
                } else {
                    null
                }
            }
            .minByOrNull {
                it.second
            }
            ?.first
    }

    private fun buildReason(
        candidateStart: LocalTime,
        candidateEnd: LocalTime,
        events: List<OptimizerEvent>,
        priority: Int
    ): String {

        val reasons =
            mutableListOf<String>()

        reasons.add("No calendar conflicts")

        val hour =
            candidateStart.hour

        if (hour in 9..11) {
            reasons.add("good daytime slot")
        }

        val previous =
            findPreviousEvent(
                candidateStart,
                events
            )

        val next =
            findNextEvent(
                candidateEnd,
                events
            )

        if (previous == null) {
            reasons.add("starts a free block")
        }

        if (next == null) {
            reasons.add("leaves the rest of the day open")
        }

        if (priority >= HIGH_PRIORITY) {
            reasons.add(
                "suitable for a high-priority task"
            )
        }

        return reasons.joinToString(", ")
    }

    private fun buildResultMessage(
        best: OptimizedTimeSlot,
        alternatives: List<OptimizedTimeSlot>
    ): String {

        val alternativeText =
            if (alternatives.isEmpty()) {
                ""
            } else {
                val formatted =
                    alternatives.joinToString(", ") {
                        "${it.startTime}-${it.endTime}"
                    }

                " Other good options are $formatted."
            }

        return """
            I found a good time at ${best.startTime}-${best.endTime}.
            ${best.reason.replaceFirstChar { it.uppercase() }}.$alternativeText
        """.trimIndent()
    }

    private fun parseTime(
        value: String
    ): LocalTime? {

        return try {
            LocalTime.parse(
                value,
                TIME_FORMATTER
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTimeOrDefault(
        value: String,
        default: LocalTime
    ): LocalTime {

        return parseTime(value)
            ?: default
    }
}