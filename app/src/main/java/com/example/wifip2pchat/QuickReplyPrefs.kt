package com.example.wifip2pchat

import android.content.Context
import org.json.JSONArray

/**
 * Stores default and user-defined quick-reply message templates in SharedPreferences.
 */
object QuickReplyPrefs {

    private const val PREFS = "lanvox_quick_replies"
    private const val KEY_TEMPLATES = "templates_json"

    private val DEFAULT_TEMPLATES = listOf(
        "On my way",
        "Call me when free",
        "Got it 👍",
        "Where are you?",
        "Sending the file now"
    )

    fun getTemplates(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TEMPLATES, null) ?: return DEFAULT_TEMPLATES
        val parsed = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrNull()
        return if (parsed.isNullOrEmpty()) DEFAULT_TEMPLATES else parsed
    }

    fun addTemplate(context: Context, template: String): List<String> {
        val clean = template.replace('\r', ' ').replace('\n', ' ').trim().take(64)
        if (clean.isEmpty()) return getTemplates(context)
        val list = getTemplates(context).toMutableList()
        if (!list.contains(clean)) {
            list.add(0, clean)
        }
        save(context, list.take(15))
        return list.take(15)
    }

    fun removeTemplate(context: Context, template: String): List<String> {
        val list = getTemplates(context).filterNot { it == template }
        val final = if (list.isEmpty()) DEFAULT_TEMPLATES else list
        save(context, final)
        return final
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray(list)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEMPLATES, arr.toString())
            .apply()
    }
}
