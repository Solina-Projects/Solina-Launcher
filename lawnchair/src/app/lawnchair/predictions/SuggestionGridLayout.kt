package app.lawnchair.predictions

/**
 * Pure layout policy for the independent app-drawer suggestion strip.
 *
 * The returned rows are in visual top-to-bottom order. Dock-backed multi-row layouts
 * are populated from the bottom upward so a short strip stays anchored to the dock.
 */
object SuggestionGridLayout {

    fun <T : Any> arrange(
        source: Source,
        dockRanked: List<T>,
        usageRanked: List<T>,
        suggestionRows: Int,
        columns: Int,
        dockRows: Int,
    ): List<List<T>> {
        require(suggestionRows in MIN_ROWS..MAX_ROWS) {
            "suggestionRows must be between $MIN_ROWS and $MAX_ROWS"
        }
        require(columns > 0) { "columns must be positive" }
        require(dockRows > 0) { "dockRows must be positive" }

        val capacity = suggestionRows * columns
        val seen = LinkedHashSet<T>()
        fun unique(values: List<T>, limit: Int): List<T> = buildList {
            for (value in values) {
                if (size >= limit) break
                if (seen.add(value)) add(value)
            }
        }

        if (source == Source.MOST_USED) {
            return unique(usageRanked, capacity).chunked(columns)
        }

        val dock = unique(dockRanked, capacity)
        val usage = unique(usageRanked, capacity - dock.size)
        val rows = MutableList(suggestionRows) { emptyList<T>() }

        if (dockRows <= 1) {
            // A one-row dock owns the first suggestion row. Additional rows are
            // completed from usage ranking, with dock entries excluded.
            rows[0] = dock.take(columns)
            val fallback = usage.iterator()
            for (rowIndex in 1 until suggestionRows) {
                rows[rowIndex] = buildList {
                    while (size < columns && fallback.hasNext()) add(fallback.next())
                }
            }
            return rows.filter { it.isNotEmpty() }
        }

        // Multi-row docks anchor content at the bottom. Fill each dock-backed row
        // bottom-up, then use usage ranking for any still-empty row.
        var offset = 0
        var rowIndex = suggestionRows - 1
        while (offset < dock.size && rowIndex >= 0) {
            rows[rowIndex] = dock.drop(offset).take(columns)
            offset += columns
            rowIndex--
        }

        val fallback = usage.iterator()
        for (index in rows.indices.reversed()) {
            if (rows[index].isEmpty() && fallback.hasNext()) {
                rows[index] = buildList {
                    while (size < columns && fallback.hasNext()) add(fallback.next())
                }
            }
        }
        return rows.filter { it.isNotEmpty() }
    }

    enum class Source {
        MOST_USED,
        DOCK,
    }

    const val MIN_ROWS = 1
    const val MAX_ROWS = 3
}
