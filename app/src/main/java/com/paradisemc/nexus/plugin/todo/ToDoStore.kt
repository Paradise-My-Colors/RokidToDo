package com.paradisemc.nexus.plugin.todo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ToDoStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<ToDoItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val label = obj.optString("label").trim()
                if (label.isBlank()) return@mapNotNull null
                ToDoItem(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    label = label.take(MAX_LABEL_CHARS),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    sequence = obj.optLong("sequence", i.toLong()),
                    doneAt = if (obj.has("doneAt") && !obj.isNull("doneAt")) obj.optLong("doneAt") else null,
                )
            }.sortedWith(compareByDescending<ToDoItem> { it.sequence }.thenByDescending { it.createdAt })
        }.getOrDefault(emptyList())
    }

    fun pending(filter: ToDoFilter = ToDoFilter.ALL, now: Long = System.currentTimeMillis()): List<ToDoItem> =
        load().asSequence().filterNot { it.isDone }.filter { item ->
            when (filter) {
                ToDoFilter.ALL -> true
                ToDoFilter.TODAY -> isToday(item.createdAt, now)
                ToDoFilter.OLDER -> !isToday(item.createdAt, now)
            }
        }.toList()

    fun completed(): List<ToDoItem> = load().filter { it.isDone }

    fun add(label: String): ToDoItem? = addAll(listOf(label)).firstOrNull()

    fun addAll(labels: List<String>): List<ToDoItem> {
        val clean = labels.map { it.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL_CHARS) }
            .filter { it.isNotBlank() }.take(MAX_AI_BATCH)
        if (clean.isEmpty()) return emptyList()
        val items = load().toMutableList()
        if (items.size >= MAX_ITEMS) return emptyList()
        var seq = prefs.getLong(KEY_SEQUENCE, items.maxOfOrNull { it.sequence } ?: 0L)
        val now = System.currentTimeMillis()
        val added = mutableListOf<ToDoItem>()
        clean.forEachIndexed { index, label ->
            if (items.size >= MAX_ITEMS) return@forEachIndexed
            seq += 1
            val item = ToDoItem(
                id = UUID.randomUUID().toString(),
                label = label,
                createdAt = now + index,
                sequence = seq,
            )
            items.add(item)
            added.add(item)
        }
        save(items)
        prefs.edit().putLong(KEY_SEQUENCE, seq).apply()
        return added.sortedByDescending { it.sequence }
    }

    fun markDone(id: String, done: Boolean = true) {
        val now = System.currentTimeMillis()
        save(load().map { if (it.id == id) it.copy(doneAt = if (done) now else null) else it })
    }

    fun delete(id: String) = save(load().filterNot { it.id == id })

    fun apiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    fun model(): String {
        val saved = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().trim()
        return when {
            saved.isBlank() -> DEFAULT_MODEL
            saved == LEGACY_INVALID_MODEL -> DEFAULT_MODEL
            else -> saved
        }
    }

    fun saveAiSettings(apiKey: String, model: String = DEFAULT_MODEL) {
        val cleanModel = model.trim().ifBlank { DEFAULT_MODEL }
            .let { if (it == LEGACY_INVALID_MODEL) DEFAULT_MODEL else it }
        prefs.edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, cleanModel)
            .apply()
    }

    private fun save(items: List<ToDoItem>) {
        val array = JSONArray()
        items.sortedWith(compareByDescending<ToDoItem> { it.sequence }.thenByDescending { it.createdAt })
            .take(MAX_ITEMS).forEach { item ->
                array.put(JSONObject()
                    .put("id", item.id)
                    .put("label", item.label)
                    .put("createdAt", item.createdAt)
                    .put("sequence", item.sequence)
                    .put("doneAt", item.doneAt ?: JSONObject.NULL))
            }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun isToday(timestamp: Long, now: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val itemDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return itemDate == today
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.7-flash"
        private const val LEGACY_INVALID_MODEL = "gemini-3.8-flash"
        private const val PREFS = "nexus_plugin_todo"
        private const val KEY_ITEMS = "items"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        private const val MAX_ITEMS = 250
        private const val MAX_AI_BATCH = 20
        private const val MAX_LABEL_CHARS = 160
    }
}
