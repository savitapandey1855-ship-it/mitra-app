package com.mitra.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** App ke andar ka chhota data store: reminders (schedule) aur birthdays/anniversaries. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("bublu_store", Context.MODE_PRIVATE)

    // ---- Reminders / Schedule ----
    fun reminders(c: Context): MutableList<Triple<Int, Long, String>> {
        val out = ArrayList<Triple<Int, Long, String>>()
        try {
            val arr = JSONArray(p(c).getString("reminders", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Triple(o.getInt("id"), o.getLong("time"), o.getString("text")))
            }
        } catch (e: Exception) {}
        return out
    }

    fun saveReminders(c: Context, list: List<Triple<Int, Long, String>>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("id", t.first).put("time", t.second).put("text", t.third))
        p(c).edit().putString("reminders", arr.toString()).apply()
    }

    fun addReminder(c: Context, id: Int, time: Long, text: String) {
        val l = reminders(c)
        l.add(Triple(id, time, text))
        saveReminders(c, l)
    }

    // ---- Birthdays / Anniversaries ----
    fun events(c: Context): MutableList<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        try {
            val arr = JSONArray(p(c).getString("events", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Triple(o.getString("name"), o.getString("date"), o.getString("type")))
            }
        } catch (e: Exception) {}
        return out
    }

    fun saveEvents(c: Context, list: List<Triple<String, String, String>>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("name", t.first).put("date", t.second).put("type", t.third))
        p(c).edit().putString("events", arr.toString()).apply()
    }

    fun addEvent(c: Context, name: String, date: String, type: String) {
        val l = events(c)
        l.add(Triple(name, date, type))
        saveEvents(c, l)
    }
}
