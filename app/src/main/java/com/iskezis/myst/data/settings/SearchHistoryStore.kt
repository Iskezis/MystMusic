package com.iskezis.myst.data.settings

import android.content.Context

/**
 * Buscas recentes por conta Myst. Ficam no aparelho (SharedPreferences) e são
 * separadas por usuário; não vão para o YouTube nem para o backup da conta.
 *
 * Ao digitar, a busca só é executada depois do debounce. Quando o usuário
 * continua digitando, a consulta maior substitui a anterior em vez de encher
 * a lista com prefixos ("the", "the w", "the we").
 */
class SearchHistoryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(userId: Long): List<String> = runCatching {
        prefs.getString(key(userId), null)
            ?.split(SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinctBy { it.lowercase() }
            ?.take(MAX_ITEMS)
            .orEmpty()
    }.getOrElse { emptyList() }

    fun add(userId: Long, rawQuery: String): List<String> {
        val query = rawQuery.trim().replace(WHITESPACE, " ")
        if (query.length < MIN_LENGTH) return load(userId)
        val current = load(userId).toMutableList()
        val exact = current.indexOfFirst { it.equals(query, ignoreCase = true) }
        if (exact >= 0) {
            current.removeAt(exact)
        } else if (current.isNotEmpty() &&
            query.startsWith(current.first(), ignoreCase = true)
        ) {
            // O usuário continuou digitando a mesma busca.
            current.removeAt(0)
        }
        current.add(0, query)
        val trimmed = current.take(MAX_ITEMS)
        runCatching {
            prefs.edit()
                .putString(key(userId), trimmed.joinToString(SEPARATOR))
                .apply()
        }
        return trimmed
    }

    /**
     * Mescla buscas recebidas de outro aparelho. A ordem preserva as buscas
     * mais recentes dos dois lados e remove duplicatas sem diferenciar
     * maiúsculas de minúsculas.
     */
    fun merge(userId: Long, incoming: List<String>): List<String> {
        if (userId <= 0L || incoming.isEmpty()) return load(userId)
        val merged = mutableListOf<String>()
        (load(userId) + incoming).forEach { raw ->
            val query = raw.trim().replace(WHITESPACE, " ")
            if (query.length < MIN_LENGTH) return@forEach
            if (merged.any { it.equals(query, ignoreCase = true) }) return@forEach
            merged += query
        }
        val result = merged.take(MAX_ITEMS)
        runCatching {
            prefs.edit()
                .putString(key(userId), result.joinToString(SEPARATOR))
                .apply()
        }
        return result
    }

    fun clear(userId: Long) {
        runCatching { prefs.edit().remove(key(userId)).apply() }
    }

    /** Usado só na TV ao encerrar a sessão temporária. */
    fun clearAll() {
        runCatching { prefs.edit().clear().apply() }
    }

    private fun key(userId: Long): String = "recent_$userId"

    companion object {
        private const val PREFS_NAME = "myst_search_history"
        private const val MAX_ITEMS = 8
        private const val MIN_LENGTH = 2
        private const val SEPARATOR = "\u001F"
        private val WHITESPACE = Regex("\\s+")
    }
}
