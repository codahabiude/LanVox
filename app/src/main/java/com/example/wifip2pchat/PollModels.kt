package com.example.wifip2pchat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Interactive single-choice poll payload model serialized inside [ChatMessage.text]
 * when `messageType == ChatMessage.TYPE_POLL`.
 */
data class PollOption(
    val text: String,
    val voters: List<String>
)

data class PollData(
    val question: String,
    val options: List<PollOption>
) {
    val totalVotes: Int
        get() = options.sumOf { it.voters.size }

    fun votedOptionIndex(voterId: String, selfKey: String = ChatMessage.SELF_REACTOR_KEY): Int {
        return options.indexOfFirst { opt ->
            opt.voters.contains(voterId) || opt.voters.contains(selfKey)
        }
    }

    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("q", question)
        val arr = JSONArray()
        options.forEach { opt ->
            val o = JSONObject()
            o.put("t", opt.text)
            o.put("v", JSONArray(opt.voters))
            arr.put(o)
        }
        obj.put("opts", arr)
        return obj.toString()
    }

    fun toWirePayload(): String = Constants.MSG_TYPE_POLL + toJsonString()

    fun summaryText(): String = "📊 Poll: $question"

    companion object {
        fun create(question: String, optionTexts: List<String>): PollData {
            val cleanQ = question.replace('\r', ' ').replace('\n', ' ').trim().take(120)
            val cleanOpts = optionTexts
                .map { it.replace('\r', ' ').replace('\n', ' ').trim().take(60) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(4)
            return PollData(
                question = cleanQ,
                options = cleanOpts.map { PollOption(text = it, voters = emptyList()) }
            )
        }

        fun fromJsonString(raw: String?): PollData? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                val q = obj.optString("q").trim()
                val arr = obj.optJSONArray("opts") ?: return null
                if (q.isEmpty() || arr.length() < 2) return null
                val opts = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val text = o.optString("t").trim()
                        if (text.isEmpty()) continue
                        val vArr = o.optJSONArray("v")
                        val voters = buildList {
                            if (vArr != null) {
                                for (j in 0 until vArr.length()) {
                                    val voter = vArr.optString(j).trim()
                                    if (voter.isNotEmpty() && !contains(voter)) add(voter)
                                }
                            }
                        }
                        add(PollOption(text = text, voters = voters))
                    }
                }
                if (opts.size < 2) null else PollData(question = q, options = opts)
            }.getOrNull()
        }

        fun fromWirePayload(payload: String): PollData? {
            if (!payload.startsWith(Constants.MSG_TYPE_POLL)) return null
            return fromJsonString(payload.removePrefix(Constants.MSG_TYPE_POLL).trim())
        }

        fun applyVote(
            rawJson: String?,
            optionIndex: Int,
            voterId: String,
            alsoClearId: String? = null
        ): String? {
            val poll = fromJsonString(rawJson) ?: return null
            if (optionIndex !in poll.options.indices) return null
            val alreadyInTarget = poll.options[optionIndex].voters.let {
                it.contains(voterId) || (alsoClearId != null && it.contains(alsoClearId))
            }
            val updatedOptions = poll.options.mapIndexed { idx, opt ->
                val filtered = opt.voters.filterNot { it == voterId || (alsoClearId != null && it == alsoClearId) }
                    .toMutableList()
                if (idx == optionIndex && !alreadyInTarget) {
                    filtered.add(voterId)
                }
                opt.copy(voters = filtered)
            }
            return poll.copy(options = updatedOptions).toJsonString()
        }
    }
}
