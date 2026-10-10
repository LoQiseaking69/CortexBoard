package com.hermitech.cortexboard.data

data class Note(
    val id: Long = 0,
    val title: String,
    val body: String = "",
    val tags: List<String> = emptyList(),
    val folder: String = "Inbox",
    val summary: String = "",
    val pinned: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val organized: Boolean = false,
)

data class TaskItem(
    val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    val list: String = "Inbox",
    /** 0 = none, 1 = low, 2 = med, 3 = high */
    val priority: Int = 0,
    /** epoch day, or null */
    val due: Long? = null,
    val noteId: Long? = null,
    val created: Long = System.currentTimeMillis(),
)

data class Board(val id: Long = 0, val name: String)

data class Card(
    val id: Long = 0,
    val boardId: Long,
    val column: String = COLUMNS.first(),
    val title: String,
    val body: String = "",
    val position: Int = 0,
) {
    companion object {
        val COLUMNS = listOf("TODO", "DOING", "DONE")
    }
}

data class McpServerConfig(
    val id: Long = 0,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = true,
)

object WikiLinks {
    private val LINK = Regex("""\[\[([^\]|#]+)(?:[#|][^\]]*)?]]""")
    private val TAG = Regex("""(?<![\w/])#([A-Za-z][\w/-]{1,40})""")

    fun links(body: String): List<String> =
        LINK.findAll(body).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun inlineTags(body: String): List<String> =
        TAG.findAll(body).map { it.groupValues[1].lowercase() }.distinct().toList()

    fun normalizeTag(t: String): String = t.trim().removePrefix("#").lowercase().replace(' ', '-')
}
