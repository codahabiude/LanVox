package com.example.wifip2pchat

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Per-thread disappearing-messages TTL settings stored locally in SharedPreferences.
 */
object EphemeralPrefs {

    private const val PREFS_NAME = "lanvox_ephemeral_prefs"
    private const val KEY_PREFIX = "ttl_"

    private val optionsSeconds = longArrayOf(0L, 30L, 300L, 3600L, 86400L)

    fun getTtlSeconds(context: Context, threadId: String?): Long {
        if (threadId.isNullOrBlank()) return 0L
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PREFIX + threadId, 0L)
            .coerceAtLeast(0L)
    }

    fun setTtlSeconds(context: Context, threadId: String?, ttlSeconds: Long) {
        if (threadId.isNullOrBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PREFIX + threadId, ttlSeconds.coerceAtLeast(0L))
            .apply()
    }

    fun formatShortLabel(ttlSeconds: Long): String? = when (ttlSeconds) {
        30L -> "30s"
        300L -> "5m"
        3600L -> "1h"
        86400L -> "24h"
        else -> if (ttlSeconds > 0L) "${ttlSeconds}s" else null
    }

    fun describeForSheet(context: Context, ttlSeconds: Long): String = when (ttlSeconds) {
        30L -> "Active · Auto-deletes after 30 seconds"
        300L -> "Active · Auto-deletes after 5 minutes"
        3600L -> "Active · Auto-deletes after 1 hour"
        86400L -> "Active · Auto-deletes after 24 hours"
        else -> context.getString(R.string.action_ephemeral_sub_off)
    }

    fun showPickerDialog(
        context: Context,
        threadId: String,
        onSelected: (Long) -> Unit
    ) {
        val current = getTtlSeconds(context, threadId)
        val checkedIndex = optionsSeconds.indexOf(current).let { if (it >= 0) it else 0 }
        val labels = arrayOf(
            context.getString(R.string.ephemeral_off),
            context.getString(R.string.ephemeral_30s),
            context.getString(R.string.ephemeral_5m),
            context.getString(R.string.ephemeral_1h),
            context.getString(R.string.ephemeral_24h)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ephemeral_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val chosen = optionsSeconds.getOrElse(which) { 0L }
                setTtlSeconds(context, threadId, chosen)
                dialog.dismiss()
                onSelected(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
