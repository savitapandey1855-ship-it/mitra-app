package com.mitra.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** App ke andar ka chhota data store: reminders, birthdays, notes, tasks. */
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

    // ---- Notes ----
    fun notes(c: Context): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(p(c).getString("notes", "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {}
        return out
    }

    fun addNote(c: Context, text: String) {
        val l = notes(c)
        l.add(text)
        val arr = JSONArray()
        for (x in l) arr.put(x)
        p(c).edit().putString("notes", arr.toString()).apply()
    }

    // ---- Tasks ----
    fun tasks(c: Context): MutableList<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        try {
            val arr = JSONArray(p(c).getString("tasks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getString("text"), o.getBoolean("done")))
            }
        } catch (e: Exception) {}
        return out
    }

    fun saveTasks(c: Context, list: List<Pair<String, Boolean>>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("text", t.first).put("done", t.second))
        p(c).edit().putString("tasks", arr.toString()).apply()
    }

    fun addTask(c: Context, text: String) {
        val l = tasks(c)
        l.add(Pair(text, false))
        saveTasks(c, l)
    }

    fun doneTask(c: Context, text: String) {
        val l = tasks(c)
        for (i in l.indices) {
            if (l[i].first.lowercase().contains(text.lowercase())) l[i] = Pair(l[i].first, true)
        }
        saveTasks(c, l)
    }
}
