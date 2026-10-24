package com.mitra.assistant

/** Buttons ki samajh: symbol / shabd -> us se related possible labels/descriptions. */
object Buttons {

    private val groups: List<List<String>> = listOf(
        listOf("+", "add", "new", "create", "naya", "nayi", "banao"),
        listOf("send", "bhejo", "submit", "go"),
        listOf("search", "khojo", "dhoondho", "find"),
        listOf("done", "ok", "theek", "save"),
        listOf("install", "download", "get"),
        listOf("allow", "permit"),
        listOf("next", "aage", "continue"),
        listOf("skip", "baad me", "later"),
        listOf("close", "cancel", "cross"),
        listOf("back", "peeche"),
        listOf("menu", "more", "options"),
        listOf("settings", "setting"),
        listOf("share"),
        listOf("edit"),
        listOf("delete", "remove", "hatao"),
        listOf("off", "disable", "turn off", "band"),
        listOf("on", "enable", "turn on", "chalu"),
        listOf("play"),
        listOf("pause"),
        listOf("mic", "voice", "speak", "bolo"),
        listOf("camera"),
        listOf("attach", "attachment", "clip", "file"),
        listOf("call"),
        listOf("profile", "account"),
        listOf("logout", "sign out")
    )

    /** Query ke saath related saare labels return karta hai (pehle original). */
    fun expand(q: String): List<String> {
        val out = ArrayList<String>()
        val lower = q.lowercase().trim()
        out.add(lower)
        for (g in groups) {
            if (g.any { it.equals(lower, true) || it.contains(lower) || lower.contains(it) }) {
                for (x in g) if (!out.contains(x)) out.add(x)
            }
        }
        return out
    }
}
