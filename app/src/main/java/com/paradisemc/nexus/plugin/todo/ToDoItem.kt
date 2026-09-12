package com.paradisemc.nexus.plugin.todo

data class ToDoItem(
    val id: String,
    val label: String,
    val createdAt: Long,
    val sequence: Long,
    val doneAt: Long? = null,
) {
    val isDone: Boolean get() = doneAt != null
}

enum class ToDoFilter(val displayName: String) {
    ALL("All"), TODAY("Today"), OLDER("Older");

    fun next(): ToDoFilter = entries[(ordinal + 1) % entries.size]
}
