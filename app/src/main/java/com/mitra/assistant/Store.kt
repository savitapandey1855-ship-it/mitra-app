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

    // ---- Habits (aadat seekhna) ----
    fun bumpHabit(c: Context, key: String) {
        try {
            val arr = JSONArray(p(c).getString("habits", "[]"))
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            var found = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("k") == key) {
                    o.put("n", o.optInt("n", 1) + 1)
                    o.put("h", hour)
                    found = true
                    break
                }
            }
            if (!found) arr.put(JSONObject().apply { put("k", key); put("n", 1); put("h", hour) })
            p(c).edit().putString("habits", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    fun habits(c: Context): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        try {
            val arr = JSONArray(p(c).getString("habits", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Triple(o.optString("k"), o.optInt("n", 1), o.optInt("h", -1)))
            }
        } catch (e: Exception) {}
        return out.sortedByDescending { it.second }
    }

    // ---- Notes v2 (title + body) ----
    fun notesV2(c: Context): MutableList<Triple<Int, String, String>> {
        val out = ArrayList<Triple<Int, String, String>>()
        try {
            val arr = JSONArray(p(c).getString("notes2", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Triple(o.getInt("id"), o.getString("title"), o.getString("body")))
            }
        } catch (e: Exception) {}
        return out
    }

    fun saveNotesV2(c: Context, list: List<Triple<Int, String, String>>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("id", t.first).put("title", t.second).put("body", t.third))
        p(c).edit().putString("notes2", arr.toString()).apply()
    }

    fun addNoteV2(c: Context, title: String, body: String): Int {
        val l = notesV2(c)
        val id = (System.currentTimeMillis() % 1000000).toInt()
        l.add(Triple(id, title, body))
        saveNotesV2(c, l)
        return id
    }

    fun updateNoteV2(c: Context, id: Int, title: String, body: String) {
        val l = notesV2(c)
        for (i in l.indices) if (l[i].first == id) l[i] = Triple(id, title, body)
        saveNotesV2(c, l)
    }

    fun deleteNoteV2(c: Context, id: Int) {
        saveNotesV2(c, notesV2(c).filter { it.first != id })
    }

    fun getNoteV2(c: Context, id: Int): Triple<Int, String, String>? =
        notesV2(c).firstOrNull { it.first == id }

    // ---- Aliases (nickname -> asli naam ya number) ----
    fun aliases(c: Context): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(p(c).getString("aliases", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getString("nick"), o.getString("target")))
            }
        } catch (e: Exception) {}
        return out
    }

    fun saveAliases(c: Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("nick", t.first).put("target", t.second))
        p(c).edit().putString("aliases", arr.toString()).apply()
    }

    fun addAlias(c: Context, nick: String, target: String) {
        val l = aliases(c).filter { it.first.lowercase() != nick.lowercase() }.toMutableList()
        l.add(Pair(nick, target))
        saveAliases(c, l)
    }

    // ---- Important WhatsApp groups ----
    fun importantGroups(c: Context): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(p(c).getString("impgroups", "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {}
        return out
    }

    fun addImportantGroup(c: Context, g: String) {
        val l = importantGroups(c)
        if (l.none { it.equals(g, true) }) l.add(g)
        val arr = JSONArray()
        for (x in l) arr.put(x)
        p(c).edit().putString("impgroups", arr.toString()).apply()
    }

    fun deleteGroup(c: Context, g: String) {
        val l = importantGroups(c).filter { !it.equals(g, true) }.toMutableList()
        val arr = JSONArray()
        for (x in l) arr.put(x)
        p(c).edit().putString("impgroups", arr.toString()).apply()
    }

    // ---- Edit / delete helpers ----
    fun updateReminder(c: Context, id: Int, text: String) {
        val l = reminders(c)
        for (i in l.indices) if (l[i].first == id) l[i] = Triple(l[i].first, l[i].second, text)
        saveReminders(c, l)
    }

    fun deleteReminder(c: Context, id: Int) {
        saveReminders(c, reminders(c).filter { it.first != id })
    }

    fun updateNote(c: Context, index: Int, text: String) {
        val l = notes(c)
        if (index in l.indices) { l[index] = text; val arr = JSONArray(); for (x in l) arr.put(x); p(c).edit().putString("notes", arr.toString()).apply() }
    }

    fun deleteNote(c: Context, index: Int) {
        val l = notes(c)
        if (index in l.indices) { l.removeAt(index); val arr = JSONArray(); for (x in l) arr.put(x); p(c).edit().putString("notes", arr.toString()).apply() }
    }

    fun updateTask(c: Context, index: Int, text: String) {
        val l = tasks(c)
        if (index in l.indices) { l[index] = Pair(text, l[index].second); saveTasks(c, l) }
    }

    fun deleteTask(c: Context, index: Int) {
        val l = tasks(c)
        if (index in l.indices) { l.removeAt(index); saveTasks(c, l) }
    }

    fun toggleTask(c: Context, index: Int) {
        val l = tasks(c)
        if (index in l.indices) { l[index] = Pair(l[index].first, !l[index].second); saveTasks(c, l) }
    }

    fun deleteAlias(c: Context, nick: String) {
        saveAliases(c, aliases(c).filter { !it.first.equals(nick, true) })
    }

    fun updateAlias(c: Context, nick: String, newTarget: String) {
        val l = aliases(c)
        for (i in l.indices) if (l[i].first.equals(nick, true)) l[i] = Pair(l[i].first, newTarget)
        saveAliases(c, l)
    }

    fun deleteEvent(c: Context, index: Int) {
        val l = events(c)
        if (index in l.indices) { l.removeAt(index); saveEvents(c, l) }
    }

    fun updateEvent(c: Context, index: Int, name: String, date: String, type: String) {
        val l = events(c)
        if (index in l.indices) { l[index] = Triple(name, date, type); saveEvents(c, l) }
    }
}
