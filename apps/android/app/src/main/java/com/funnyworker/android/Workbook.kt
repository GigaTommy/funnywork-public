package com.funnyworker.android

import org.json.JSONObject

data class WorkbookSheet(val id: String, val name: String, val rows: Int, val columns: Int, val tiles: List<String>, val widths: List<Double>) {
    fun width(column: Int) = ((widths.getOrNull(column) ?: 10.0) * 7 + 12).coerceIn(60.0, 240.0).toFloat()
}
data class WorkbookCell(val row: Int, val column: Int, val text: String, val missing: Boolean, val bold: Boolean, val italic: Boolean, val align: String, val rows: Int, val columns: Int)
data class WorkbookTile(val firstRow: Int, val cells: List<WorkbookCell>)
object WorkbookData {
    fun index(data: JSONObject): List<WorkbookSheet> {
        require(data.sequence("schema") == 1L)
        val items = data.getJSONArray("sheets"); require(items.length() <= 100 && items.objects().size == items.length())
        val sheets = items.objects().map { row ->
            val rows = requireNotNull(row.sequence("rows")).also { require(it <= 250000) }.toInt()
            val columns = requireNotNull(row.sequence("columns")).also { require(it <= 16384) }.toInt()
            val tiles = row.getJSONArray("tiles"); require(tiles.length() <= 250)
            val keys = (0 until tiles.length()).map { tiles.getString(it).also { k -> require(k.matches(Regex("[A-Za-z0-9_-]{1,80}"))) } }
            val widths = row.getJSONArray("widths"); require(widths.length() <= 16384)
            val values = (0 until widths.length()).map { (widths.get(it) as? Number)?.toDouble()?.also { n -> require(n.isFinite() && n in 0.0..255.0) } ?: error("Invalid width") }
            WorkbookSheet(row.getString("id"), row.getString("name"), rows, columns, keys, values)
        }
        require(sheets.map { it.id }.distinct().size == sheets.size)
        return sheets
    }
    fun tile(data: JSONObject, sheet: WorkbookSheet): WorkbookTile {
        require(data.sequence("schema") == 1L && data.text("sheetId") == sheet.id)
        val first = requireNotNull(data.sequence("firstRow")).also { require(it <= sheet.rows) }.toInt()
        val rows = data.getJSONArray("cells"); require(rows.length() <= 250000 && rows.objects().size == rows.length())
        val cells = rows.objects().map { item ->
            val row = requireNotNull(item.sequence("row")).also { require(it < sheet.rows) }.toInt(); val col = requireNotNull(item.sequence("column")).also { require(it < sheet.columns) }.toInt()
            require(row >= first && row < minOf(first + 100, sheet.rows) && col >= 0 && col < sheet.columns)
            val rs = if (item.has("mergeRows")) requireNotNull(item.sequence("mergeRows")) else 1L
            val cs = if (item.has("mergeColumns")) requireNotNull(item.sequence("mergeColumns")) else 1L
            require(rs in 1..maxOf(1, sheet.rows).toLong() && cs in 1..maxOf(1, sheet.columns).toLong())
            WorkbookCell(row, col, item.text("text"), item.optBoolean("missingResult"), item.optBoolean("bold"), item.optBoolean("italic"), item.text("align"), rs.toInt(), cs.toInt())
        }.distinctBy { it.row to it.column }
        return WorkbookTile(first, cells)
    }
    fun columnName(column: Int): String {
        require(column in 0..16383)
        var n = column + 1; var result = ""
        while (n > 0) { n--; result = ('A' + n % 26) + result; n /= 26 }
        return result
    }
}
