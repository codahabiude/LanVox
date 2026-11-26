package com.example.wifip2pchat

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Search and highlighting utility for filtering [ChatMessage] items by
 * message body, sender name, or human-readable timestamp tokens.
 */
object ChatSearchHelper {

    /**
     * Returns all messages from [messages] matching [rawQuery] across message text,
     * sender name, and timestamp representations (e.g., `14:30`, `2:30 PM`, `Nov 21`,
     * `2026-11-21`, `today`, `yesterday`).
     */
    fun filter(
        context: Context,
        messages: List<ChatMessage>,
        rawQuery: String
    ): List<ChatMessage> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return messages

        val tokens = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return messages

        val locale = Locale.getDefault()
        val time24 = SimpleDateFormat("HH:mm", locale)
        val time12 = SimpleDateFormat("h:mm a", locale)
        val dateShort = SimpleDateFormat("MMM d yyyy", locale)
        val dateLong = SimpleDateFormat("MMMM d yyyy", locale)
        val dateIso = SimpleDateFormat("yyyy-MM-dd", locale)
        val dayName = SimpleDateFormat("EEEE EEE", locale)

        val yesterdayCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        return messages.filter { msg ->
            val safeTs = if (msg.timestamp > 0L) msg.timestamp else System.currentTimeMillis()
            val date = Date(safeTs)
            val relativeTag = when {
                DateUtils.isToday(safeTs) -> "today"
                isSameDay(safeTs, yesterdayCal) -> "yesterday"
                else -> ""
            }
            val timestampHaystack = buildString {
                append(msg.formattedTimestamp(context)).append(' ')
                append(time24.format(date)).append(' ')
                append(time12.format(date)).append(' ')
                append(dateShort.format(date)).append(' ')
                append(dateLong.format(date)).append(' ')
                append(dateIso.format(date)).append(' ')
                append(dayName.format(date))
                if (relativeTag.isNotEmpty()) {
                    append(' ').append(relativeTag)
                }
            }

            val fullHaystack = buildString {
                if (msg.isPoll) {
                    val poll = msg.parsedPoll()
                    if (poll != null) {
                        append("poll ").append(poll.question).append(' ')
                        append(poll.options.joinToString(" "))
                    } else {
                        append(msg.text)
                    }
                } else {
                    append(msg.text)
                }
                if (!msg.attachmentName.isNullOrBlank()) {
                    append(' ').append(msg.attachmentName)
                }
                if (!msg.attachmentMime.isNullOrBlank()) {
                    append(' ').append(msg.attachmentMime)
                }
                if (!msg.senderName.isNullOrBlank()) {
                    append(' ').append(msg.senderName)
                }
                append(' ').append(timestampHaystack)
            }

            tokens.all { token -> fullHaystack.contains(token, ignoreCase = true) }
        }
    }

    /**
     * Returns a [CharSequence] with case-insensitive matches of [rawQuery] (or its
     * whitespace-separated tokens) highlighted for visual scanning in chat bubbles.
     */
    fun highlight(
        source: String,
        rawQuery: String,
        highlightColor: Int
    ): CharSequence {
        val query = rawQuery.trim()
        if (query.isEmpty() || source.isEmpty()) return source

        val tokens = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()
        if (tokens.isEmpty()) return source

        val builder = SpannableStringBuilder(source)
        var anyMatched = false

        for (token in tokens) {
            var startIndex = source.indexOf(token, startIndex = 0, ignoreCase = true)
            while (startIndex >= 0) {
                anyMatched = true
                val endIndex = startIndex + token.length
                builder.setSpan(
                    BackgroundColorSpan(highlightColor),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = source.indexOf(token, startIndex = endIndex, ignoreCase = true)
            }
        }

        return if (anyMatched) builder else source
    }

    private fun isSameDay(timestampMs: Long, otherCal: Calendar): Boolean {
        val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return targetCal.get(Calendar.YEAR) == otherCal.get(Calendar.YEAR) &&
            targetCal.get(Calendar.DAY_OF_YEAR) == otherCal.get(Calendar.DAY_OF_YEAR)
    }
}
