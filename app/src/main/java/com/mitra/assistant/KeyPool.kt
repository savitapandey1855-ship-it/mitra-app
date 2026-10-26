package com.mitra.assistant

import android.content.Context
import org.json.JSONArray

/** Kai API keys ka pool - ek fail ho to chupke se agli chalti hai. */
object KeyPool {

    private var app: Context? = null

    fun init(c: Context) { app = c.applicationContext }

    private fun prefs() = app?.getSharedPreferences("mitra", Context.MODE_PRIVATE)

    fun keys(kind: String): List<String> {
        val p = prefs() ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(p.getString(kind + "_keys", "[]"))
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) out.add(v)
            }
        } catch (e: Exception) {}
        // purani single key bhi list me le aao
        val single = p.getString(kind + "_key", "")?.trim() ?: ""
        if (single.isNotBlank() && !out.contains(single)) out.add(0, single)
        return out.distinct()
    }

    fun save(kind: String, list: List<String>) {
        val p = prefs() ?: return
        val clean = list.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val arr = JSONArray()
        for (k in clean) arr.put(k)
        p.edit()
            .putString(kind + "_keys", arr.toString())
            .putString(kind + "_key", clean.firstOrNull() ?: "")
            .putInt(kind + "_idx", 0)
            .apply()
    }

    fun count(kind: String): Int = keys(kind).size

    fun index(kind: String): Int {
        val n = count(kind)
        if (n == 0) return 0
        val p = prefs() ?: return 0
        return p.getInt(kind + "_idx", 0).coerceIn(0, n - 1)
    }

    fun setIndex(kind: String, i: Int) {
        val p = prefs() ?: return
        val n = count(kind)
        if (n == 0) return
        val v = ((i % n) + n) % n
        p.edit().putInt(kind + "_idx", v).apply()
    }

    fun current(kind: String): String? {
        val l = keys(kind)
        if (l.isEmpty()) return null
        return l[index(kind).coerceIn(0, l.size - 1)]
    }

    /** Fail hone pe agli key - chupke se, user ko pata nahi chalta. */
    fun markBad(kind: String) {
        val n = count(kind)
        if (n == 0) return
        setIndex(kind, index(kind) + 1)
        val k = current(kind) ?: return
        prefs()?.edit()?.putString(kind + "_key", k)?.apply()
    }
}
