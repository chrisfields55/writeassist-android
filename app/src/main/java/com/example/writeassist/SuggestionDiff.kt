package com.example.writeassist

/** Minimal, non-overlapping replacement spans for reviewable writing suggestions. */
object SuggestionDiff {
    data class Edit(val start: Int, val end: Int, val before: String, val after: String)

    fun between(original: String, corrected: String): List<Edit> {
        if (original == corrected) return emptyList()
        val a = Regex("\\S+|\\s+").findAll(original).map { it.value }.toList()
        val b = Regex("\\S+|\\s+").findAll(corrected).map { it.value }.toList()
        // Avoid quadratic work for large drafts. A single replacement is still safe.
        if (a.size.toLong() * b.size > 160000) return listOf(Edit(0, original.length, original, corrected))
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0)
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
        val offsets = IntArray(a.size + 1)
        for (i in a.indices) offsets[i + 1] = offsets[i] + a[i].length
        val edits = mutableListOf<Edit>()
        var i = 0
        var j = 0
        var fromI = -1
        var fromJ = -1
        fun flush() {
            if (fromI >= 0) {
                val start = offsets[fromI]
                val end = offsets[i]
                val after = b.subList(fromJ, j).joinToString("")
                if (original.substring(start, end) != after)
                    edits.add(Edit(start, end, original.substring(start, end), after))
                fromI = -1
                fromJ = -1
            }
        }
        while (i < a.size || j < b.size) {
            if (i < a.size && j < b.size && a[i] == b[j]) {
                flush()
                i++
                j++
            } else {
                if (fromI < 0) { fromI = i; fromJ = j }
                if (j < b.size && (i == a.size || dp[i][j + 1] >= dp[i + 1][j])) j++
                else i++
            }
        }
        flush()
        return edits
    }

    fun mapOffset(offset: Int, edit: Edit?, length: Int): Int {
        val mapped = when {
            edit == null -> offset
            offset <= edit.start -> offset
            offset < edit.end -> edit.start + edit.after.length
            else -> offset + edit.after.length - (edit.end - edit.start)
        }
        return mapped.coerceIn(0, length)
    }

    fun apply(original: String, edit: Edit): String =
        original.substring(0, edit.start) + edit.after + original.substring(edit.end)
}
