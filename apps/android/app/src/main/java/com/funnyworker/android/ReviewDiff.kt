package com.funnyworker.android

import org.json.JSONObject

data class ReviewLine(val text: String, val old: Int?, val new: Int?, val added: Boolean = false) {
    fun reference(sequence: Long, path: String, oldLabel: String, newLabel: String): String? {
        val number = new ?: old ?: return null
        return "#$sequence · $path · ${if (new != null) newLabel else oldLabel} $number\n> $text\n"
    }
}
object ReviewDiff {
    fun parse(text: String): List<ReviewLine> {
        val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
        var old = 0; var new = 0; var oldLeft = 0; var newLeft = 0
        return text.split('\n').map { line ->
            val match = header.find(line)
            if (match != null) {
                val numbers = (1..4).map { match.groupValues[it].ifBlank { "1" }.toIntOrNull()?.takeIf { n -> n <= 1_000_000_000 } }
                if (numbers.all { it != null }) { old = numbers[0]!!; oldLeft = numbers[1]!!; new = numbers[2]!!; newLeft = numbers[3]!! }
                else { oldLeft = 0; newLeft = 0 }
                ReviewLine(line, null, null)
            } else if (line.startsWith("\\")) ReviewLine(line, null, null)
            else if (line.startsWith("+") && (oldLeft > 0 || newLeft > 0 || !line.startsWith("+++"))) {
                val n = if (newLeft > 0) new++ else null; if (newLeft > 0) newLeft--
                ReviewLine(line, null, n, true)
            } else if (line.startsWith("-") && (oldLeft > 0 || newLeft > 0 || !line.startsWith("---"))) {
                val n = if (oldLeft > 0) old++ else null; if (oldLeft > 0) oldLeft--
                ReviewLine(line, n, null)
            } else if (line.startsWith(" ") && (oldLeft > 0 || newLeft > 0)) {
                val a = if (oldLeft > 0) old++ else null; val b = if (newLeft > 0) new++ else null
                if (oldLeft > 0) oldLeft--; if (newLeft > 0) newLeft--
                ReviewLine(line, a, b)
            } else { oldLeft = 0; newLeft = 0; ReviewLine(line, null, null) }
        }
    }
}
object ReviewFeedback {
    fun text(review: JSONObject, title: String): String {
        val notes = review.getJSONObject("notes")
        val text = listOf(review.text("comment")) + notes.keys().asSequence().sorted().filter { notes.text(it).isNotBlank() }.map { "$it\n${notes.text(it)}" }.toList()
        require(text.any { it.isNotBlank() })
        val sequences = review.getJSONArray("sequences")
        val sources = if (sequences.length() == 0) "#${review.getLong("end")}" else (0 until sequences.length()).joinToString(", ") { "#${sequences.getLong(it)}" }
        return "$title\n$sources\n" + text.filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
