package com.gustavo.multilector

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/** Resultado: el contenido HTML (sin <html>) y si conviene hoja apaisada. */
class HtmlDoc(val body: String, val landscape: Boolean)

/**
 * Pasa documentos a HTML para verlos y para convertirlos a PDF.
 * Las imágenes quedan como archivos en [assets] y el HTML las referencia por nombre.
 */
object DocHtml {

    val CSS = """
        body{font-family:sans-serif;font-size:15px;line-height:1.45;color:#111;margin:14px;word-wrap:break-word;overflow-wrap:anywhere}
        img{max-width:100%;height:auto}
        table{border-collapse:collapse;margin:8px 0;font-size:13px}
        td,th{border:1px solid #bbb;padding:3px 6px;vertical-align:top;overflow-wrap:normal;word-break:normal;max-width:30em}
        th{background:#eef1f5}
        td.n{text-align:right;white-space:nowrap}
        td.cn,th.cn{color:#777;background:#f4f5f7;text-align:center}
        pre{white-space:pre-wrap;font-family:monospace;font-size:13px;margin:0}
        .pb{page-break-before:always;break-before:page;height:0}
        .slide{border:1px solid #ccc;border-radius:8px;padding:12px 16px;margin:12px 0;page-break-inside:avoid;break-inside:avoid}
        .sn{color:#888;font-size:12px;margin-bottom:6px}
        .sheet{font-weight:bold;font-size:17px;margin:18px 0 6px;color:#1E3A5F}
        .fname{font-weight:bold;color:#1E3A5F;border-bottom:2px solid #1E3A5F;margin:4px 0 10px;padding-bottom:4px}
        .pic{text-align:center;page-break-inside:avoid;break-inside:avoid}
        .pic img{max-height:25cm}
        code{background:#f1f1f1;padding:1px 4px;border-radius:3px}
        blockquote{border-left:4px solid #ccc;margin:6px 0;padding-left:10px;color:#444}
        @media print{body{margin:0}.slide{border:none;border-radius:0}}
    """.trimIndent()

    fun page(title: String, body: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${esc(title)}</title><style>$CSS</style></head><body>$body</body></html>"

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '&' -> sb.append("&amp;"); '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** Arma el HTML de un archivo. prefix = para que no choquen nombres de imágenes al unir varios. */
    fun build(ctx: Context, uri: Uri, name: String, kind: Kind, assets: File, prefix: String = "a"): HtmlDoc {
        assets.mkdirs()
        return when (kind) {
            Kind.DOCX -> HtmlDoc(docx(zip(ctx, uri), assets, prefix), false)
            Kind.XLSX -> xlsx(zip(ctx, uri))
            Kind.PPTX -> HtmlDoc(pptx(zip(ctx, uri), assets, prefix), true)
            Kind.ODT, Kind.ODS, Kind.ODP -> odf(zip(ctx, uri), assets, prefix, kind)
            Kind.EPUB -> HtmlDoc(epub(zip(ctx, uri), assets, prefix), false)
            Kind.DOC -> HtmlDoc(paragraphs(Legacy.docText(F.readAll(ctx, uri))), false)
            Kind.XLS -> {
                val sheets = Legacy.xls(F.readAll(ctx, uri))
                val out = StringBuilder()
                var wide = false
                sheets.forEachIndexed { i, sh -> if (gridTable(out, sh.name, sh.grid, sh.truncated, i > 0) > 6) wide = true }
                if (sheets.isEmpty()) out.append("<p>(planilla vacía)</p>")
                HtmlDoc(out.toString(), wide)
            }
            Kind.PPT -> {
                val slides = Legacy.pptText(F.readAll(ctx, uri))
                val out = StringBuilder()
                slides.forEachIndexed { i, texts ->
                    if (i > 0) out.append("<div class=\"pb\"></div>")
                    out.append("<div class=\"slide\"><div class=\"sn\">Diapositiva ${i + 1}</div>")
                    texts.forEachIndexed { k, t -> val h = esc(t).replace("\n", "<br>"); out.append(if (k == 0) "<h2>$h</h2>" else "<p>$h</p>") }
                    out.append("</div>")
                }
                if (slides.isEmpty()) out.append("<p>(no encontré texto en la presentación)</p>")
                HtmlDoc("<p style=\"color:#888\"><i>Formato viejo de PowerPoint: se muestra solo el texto.</i></p>" + out, true)
            }
            Kind.RTF -> HtmlDoc(textBlock(rtf(String(F.readAll(ctx, uri), Charsets.ISO_8859_1))), false)
            Kind.CSV -> csv(F.decodeText(F.readAll(ctx, uri)), F.ext(name) == "tsv")
            Kind.MD -> HtmlDoc(markdown(F.decodeText(F.readAll(ctx, uri))), false)
            Kind.JSON -> HtmlDoc(json(F.decodeText(F.readAll(ctx, uri))), false)
            Kind.HTML -> HtmlDoc(htmlBody(F.decodeText(F.readAll(ctx, uri))), false)
            Kind.SVG -> {
                val f = File(assets, "${prefix}_img.svg")
                F.copyTo(ctx, uri, f)
                HtmlDoc("<div class=\"pic\"><img src=\"${f.name}\" style=\"width:100%\"></div>", false)
            }
            Kind.IMAGE -> {
                val f = File(assets, "${prefix}_img.${F.ext(name).ifBlank { "jpg" }}")
                F.copyTo(ctx, uri, f)
                HtmlDoc("<div class=\"pic\"><img src=\"${f.name}\"></div>", false)
            }
            else -> {
                val bytes = F.readAll(ctx, uri)
                val text = F.decodeText(if (bytes.size > 6_000_000) bytes.copyOf(6_000_000) else bytes)
                HtmlDoc(textBlock(text) + if (bytes.size > 6_000_000) "<p><i>(Archivo muy largo: se muestran los primeros 6 MB)</i></p>" else "", false)
            }
        }
    }

    fun textBlock(t: String): String = "<pre>${esc(t)}</pre>"

    // ---------------- ZIP ----------------
    fun zip(ctx: Context, uri: Uri): Map<String, ByteArray> {
        val m = HashMap<String, ByteArray>()
        var total = 0L
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val z = ZipInputStream(ins.buffered())
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val data = z.readBytes()
                total += data.size
                if (total > 300_000_000) break
                m[e.name.trimStart('/')] = data
            }
        }
        return m
    }

    private fun parser(data: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(data), null)
        return p
    }

    private fun local(n: String?): String = n?.substringAfter(':') ?: ""

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == name) return p.getAttributeValue(i)
        return null
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = ArrayList(base.split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) {
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** Lee un archivo .rels: Id -> ruta completa. */
    private fun rels(z: Map<String, ByteArray>, relsPath: String, baseDir: String): Map<String, String> {
        val data = z[relsPath] ?: return emptyMap()
        val m = HashMap<String, String>()
        try {
            val p = parser(data)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                    val id = attr(p, "Id"); val t = attr(p, "Target")
                    if (id != null && t != null && attr(p, "TargetMode") != "External") m[id] = resolve(baseDir, Uri.decode(t))
                }
                ev = p.next()
            }
        } catch (_: Exception) {
        }
        return m
    }

    private var imgCounter = 0

    /** Escribe una imagen del documento a la carpeta y devuelve el nombre (o null si no se puede mostrar). */
    private fun saveImg(z: Map<String, ByteArray>, path: String, assets: File, prefix: String): String? {
        val data = z[path] ?: return null
        val e = F.ext(path)
        if (e in setOf("emf", "wmf", "tif", "tiff", "wdp")) return null
        imgCounter++
        val f = File(assets, "${prefix}_${imgCounter}.${e.ifBlank { "png" }}")
        f.writeBytes(data)
        return f.name
    }

    // ---------------- WORD ----------------
    private class PState { var style = ""; var align = ""; var list = false; var pageBreak = false; var level = 0 }

    fun docx(z: Map<String, ByteArray>, assets: File, prefix: String): String {
        val xml = z["word/document.xml"] ?: return "<p>(documento vacío o dañado)</p>"
        val rels = rels(z, "word/_rels/document.xml.rels", "word")
        val p = parser(xml)
        val stack = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
        val pst = ArrayList<PState>()
        val spans = ArrayList<Int>()
        var inPPr = false; var inRPr = false; var inT = false
        var bold = false; var ital = false; var und = false; var strike = false
        var color = ""; var hl = ""; var sz = 0; var sup = ""
        var run: StringBuilder? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "p" -> { stack.add(StringBuilder()); pst.add(PState()) }
                    "pPr" -> inPPr = true
                    "pStyle" -> if (inPPr) pst.lastOrNull()?.style = attr(p, "val") ?: ""
                    "jc" -> if (inPPr) pst.lastOrNull()?.align = attr(p, "val") ?: ""
                    "numPr" -> if (inPPr) pst.lastOrNull()?.list = true
                    "ilvl" -> if (inPPr) pst.lastOrNull()?.level = attr(p, "val")?.toIntOrNull() ?: 0
                    "r" -> { run = StringBuilder(); bold = false; ital = false; und = false; strike = false; color = ""; hl = ""; sz = 0; sup = "" }
                    "rPr" -> inRPr = true
                    "b" -> if (inRPr && !inPPr) bold = on(p)
                    "i" -> if (inRPr && !inPPr) ital = on(p)
                    "u" -> if (inRPr && !inPPr) und = (attr(p, "val") ?: "single") != "none"
                    "strike", "dstrike" -> if (inRPr && !inPPr) strike = on(p)
                    "color" -> if (inRPr && !inPPr) color = attr(p, "val")?.takeIf { it != "auto" && it.length == 6 } ?: ""
                    "highlight" -> if (inRPr && !inPPr) hl = attr(p, "val")?.takeIf { it != "none" } ?: ""
                    "sz" -> if (inRPr && !inPPr) sz = attr(p, "val")?.toIntOrNull() ?: 0
                    "vertAlign" -> if (inRPr && !inPPr) sup = attr(p, "val") ?: ""
                    "t" -> inT = true
                    "tab" -> if (!inPPr) run?.append("&emsp;")
                    "br" -> if (!inPPr) {
                        if (attr(p, "type") == "page") pst.lastOrNull()?.pageBreak = true else run?.append("<br>")
                    }
                    "cr" -> run?.append("<br>")
                    "blip" -> {
                        val id = attr(p, "embed")
                        val path = if (id != null) rels[id] else null
                        val f = if (path != null) saveImg(z, path, assets, prefix) else null
                        if (f != null) (run ?: stack.last()).append("<img src=\"$f\">")
                    }
                    "imagedata" -> {
                        val id = attr(p, "id")
                        val path = if (id != null) rels[id] else null
                        val f = if (path != null) saveImg(z, path, assets, prefix) else null
                        if (f != null) (run ?: stack.last()).append("<img src=\"$f\">")
                    }
                    "tbl" -> stack.last().append("<table>")
                    "tr" -> stack.last().append("<tr>")
                    "tc" -> { stack.add(StringBuilder()); spans.add(1) }
                    "gridSpan" -> if (spans.isNotEmpty()) spans[spans.size - 1] = attr(p, "val")?.toIntOrNull() ?: 1
                }
                XmlPullParser.TEXT -> if (inT) run?.append(esc(p.text))
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "t" -> inT = false
                    "rPr" -> inRPr = false
                    "pPr" -> inPPr = false
                    "r" -> {
                        val r = run
                        if (r != null && r.isNotEmpty() && stack.size > 0) {
                            var s = r.toString()
                            val st = StringBuilder()
                            if (color.isNotEmpty()) st.append("color:#$color;")
                            if (hl.isNotEmpty()) st.append("background:$hl;")
                            if (sz > 0) st.append("font-size:${sz / 2.0}pt;")
                            if (st.isNotEmpty()) s = "<span style=\"$st\">$s</span>"
                            if (bold) s = "<b>$s</b>"
                            if (ital) s = "<i>$s</i>"
                            if (und) s = "<u>$s</u>"
                            if (strike) s = "<s>$s</s>"
                            if (sup == "superscript") s = "<sup>$s</sup>" else if (sup == "subscript") s = "<sub>$s</sub>"
                            stack.last().append(s)
                        }
                        run = null
                    }
                    "p" -> if (stack.size > 1 && pst.isNotEmpty()) {
                        val content = stack.removeAt(stack.size - 1).toString()
                        val st = pst.removeAt(pst.size - 1)
                        val out = StringBuilder()
                        if (st.pageBreak) out.append("<div class=\"pb\"></div>")
                        val style = st.style.lowercase(Locale.ROOT)
                        val align = when (st.align) { "center" -> "text-align:center;"; "right", "end" -> "text-align:right;"; "both" -> "text-align:justify;"; else -> "" }
                        val hLevel = when {
                            style == "title" || style.startsWith("ttulo") && !style.any { it.isDigit() } -> 1
                            style.contains("heading") || style.contains("ttulo") || style.contains("titulo") ->
                                (style.firstOrNull { it.isDigit() }?.digitToInt() ?: 1).coerceIn(1, 6)
                            else -> 0
                        }
                        val body = if (content.isBlank()) "&nbsp;" else content
                        when {
                            hLevel > 0 && content.isNotBlank() -> out.append("<h$hLevel style=\"$align\">$content</h$hLevel>")
                            st.list || style.startsWith("list") || style.startsWith("prrafodelista") || style.startsWith("vieta") -> out.append("<p style=\"margin:2px 0 2px ${18 + st.level * 18}px;$align\">•&nbsp;$body</p>")
                            else -> out.append("<p style=\"margin:4px 0;$align\">$body</p>")
                        }
                        stack.last().append(out)
                    }
                    "tbl" -> stack.last().append("</table>")
                    "tr" -> stack.last().append("</tr>")
                    "tc" -> if (stack.size > 1 && spans.isNotEmpty()) {
                        val c = stack.removeAt(stack.size - 1)
                        val sp = spans.removeAt(spans.size - 1)
                        stack.last().append("<td${if (sp > 1) " colspan=\"$sp\"" else ""}>$c</td>")
                    }
                }
            }
            ev = p.next()
        }
        while (stack.size > 1) { val c = stack.removeAt(stack.size - 1); stack.last().append(c) }
        return stack[0].toString()
    }

    private fun on(p: XmlPullParser): Boolean {
        val v = attr(p, "val") ?: return true
        return v != "0" && v != "false" && v != "none"
    }

    // ---------------- EXCEL ----------------
    fun xlsx(z: Map<String, ByteArray>): HtmlDoc {
        val shared = ArrayList<String>()
        z["xl/sharedStrings.xml"]?.let { data ->
            val p = parser(data)
            var cur: StringBuilder? = null
            var inT = false
            var inRPh = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (local(p.name)) { "si" -> cur = StringBuilder(); "t" -> inT = true; "rPh" -> inRPh = true }
                    XmlPullParser.TEXT -> if (inT && !inRPh) cur?.append(p.text)
                    XmlPullParser.END_TAG -> when (local(p.name)) { "si" -> { shared.add(cur?.toString() ?: ""); cur = null }; "t" -> inT = false; "rPh" -> inRPh = false }
                }
                ev = p.next()
            }
        }
        // estilos: qué celdas son fechas
        val dateStyles = HashSet<Int>()
        z["xl/styles.xml"]?.let { data ->
            val custom = HashMap<Int, String>()
            val xfs = ArrayList<Int>()
            val p = parser(data)
            var inXfs = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                    "numFmt" -> { val id = attr(p, "numFmtId")?.toIntOrNull(); val c = attr(p, "formatCode"); if (id != null && c != null) custom[id] = c }
                    "cellXfs" -> inXfs = true
                    "xf" -> if (inXfs) xfs.add(attr(p, "numFmtId")?.toIntOrNull() ?: 0)
                }
                if (ev == XmlPullParser.END_TAG && local(p.name) == "cellXfs") inXfs = false
                ev = p.next()
            }
            xfs.forEachIndexed { i, id ->
                val isDate = id in 14..22 || id in 45..47 || custom[id]?.let { code ->
                    val clean = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\."), "").lowercase(Locale.ROOT)
                    clean.contains('d') || clean.contains('y') || (clean.contains('m') && !clean.contains('0') && !clean.contains('#')) || clean.contains('h')
                } == true
                if (isDate) dateStyles.add(i)
            }
        }
        // hojas en orden
        val wbRels = rels(z, "xl/_rels/workbook.xml.rels", "xl")
        val sheets = ArrayList<Pair<String, String>>()
        z["xl/workbook.xml"]?.let { data ->
            val p = parser(data)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "sheet") {
                    val nm = attr(p, "name") ?: "Hoja"
                    val rid = attr(p, "id")
                    val path = rid?.let { wbRels[it] }
                    if (path != null && z.containsKey(path)) sheets.add(nm to path)
                }
                ev = p.next()
            }
        }
        if (sheets.isEmpty()) z.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.sorted()
            .forEachIndexed { i, k -> sheets.add("Hoja ${i + 1}" to k) }

        val out = StringBuilder()
        var wide = false
        val maxRows = 4000
        val maxCols = 60
        sheets.forEachIndexed { si, (sheetName, path) ->
            val grid = java.util.TreeMap<Int, java.util.TreeMap<Int, Pair<String, Boolean>>>()
            var maxCol = -1
            val p = parser(z[path]!!)
            var ref = ""; var type = ""; var style = 0
            var inV = false; var inIs = false; var inT = false
            var value = StringBuilder()
            var rowIdx = 0
            var colAuto = 0
            var truncated = false
            var ev = p.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (local(p.name)) {
                        "row" -> { rowIdx = (attr(p, "r")?.toIntOrNull() ?: (rowIdx + 1)); colAuto = 0; if (rowIdx > maxRows) { truncated = true; break@loop } }
                        "c" -> { ref = attr(p, "r") ?: ""; type = attr(p, "t") ?: ""; style = attr(p, "s")?.toIntOrNull() ?: 0; value = StringBuilder() }
                        "v" -> inV = true
                        "is" -> inIs = true
                        "t" -> if (inIs) inT = true
                    }
                    XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                    XmlPullParser.END_TAG -> when (local(p.name)) {
                        "v" -> inV = false
                        "t" -> inT = false
                        "is" -> inIs = false
                        "c" -> {
                            val col = if (ref.isNotEmpty()) colIndex(ref) else colAuto
                            colAuto = col + 1
                            val raw = value.toString()
                            if (raw.isNotEmpty() && col < maxCols) {
                                var num = false
                                val text = when (type) {
                                    "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: ""
                                    "inlineStr", "str" -> raw
                                    "b" -> if (raw.trim() == "1") "VERDADERO" else "FALSO"
                                    "e" -> raw
                                    else -> {
                                        val d = raw.toDoubleOrNull()
                                        if (d == null) raw else { num = true; if (style in dateStyles) excelDate(d) else number(d) }
                                    }
                                }
                                grid.getOrPut(rowIdx) { java.util.TreeMap() }[col] = text to num
                                if (col > maxCol) maxCol = col
                            }
                        }
                    }
                }
                ev = p.next()
            }
            if (gridTable(out, sheetName, grid, truncated, si > 0) > 6) wide = true
        }
        if (sheets.isEmpty()) out.append("<p>(planilla vacía o dañada)</p>")
        return HtmlDoc(out.toString(), wide)
    }

    /** Dibuja una hoja como tabla con letras de columna y números de fila. Devuelve la cantidad de columnas. */
    fun gridTable(out: StringBuilder, sheetName: String, grid: java.util.TreeMap<Int, java.util.TreeMap<Int, Pair<String, Boolean>>>, truncated: Boolean, pageBreak: Boolean): Int {
        if (pageBreak) out.append("<div class=\"pb\"></div>")
        out.append("<div class=\"sheet\">${esc(sheetName)}</div>")
        if (grid.isEmpty()) { out.append("<p><i>(hoja vacía)</i></p>"); return 0 }
        val maxCol = grid.values.maxOf { it.lastKey() }
        out.append("<table><tr><th class=\"cn\"></th>")
        for (c in 0..maxCol) out.append("<th class=\"cn\">${colName(c)}</th>")
        out.append("</tr>")
        for ((r, cells) in grid) {
            out.append("<tr><td class=\"cn\">$r</td>")
            for (c in 0..maxCol) {
                val cell = cells[c]
                if (cell == null) out.append("<td></td>")
                else out.append(if (cell.second) "<td class=\"n\">" else "<td>").append(esc(cell.first).replace("\n", "<br>")).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
        if (truncated) out.append("<p><i>(Se muestran las primeras filas solamente)</i></p>")
        return maxCol + 1
    }

    fun paragraphs(t: String): String {
        val sb = StringBuilder()
        for (para in t.split('\n')) {
            if (para == "\u000C") { sb.append("<div class=\"pb\"></div>"); continue }
            if (para.contains('\t')) {
                val cells = para.split('\t').dropLastWhile { it.isBlank() }
                if (cells.size > 1) { sb.append("<table><tr>").append(cells.joinToString("") { "<td>${esc(it)}</td>" }).append("</tr></table>"); continue }
            }
            sb.append("<p style=\"margin:4px 0\">${if (para.isBlank()) "&nbsp;" else esc(para)}</p>")
        }
        return sb.toString()
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1) else break }
        return n - 1
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun number(d: Double): String {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        val s = String.format(Locale.ROOT, "%.6f", d).trimEnd('0').trimEnd('.')
        return s.replace('.', ',')
    }

    private fun excelDate(d: Double): String {
        return try {
            val days = Math.floor(d).toLong()
            val frac = d - days
            val date = LocalDate.of(1899, 12, 30).plusDays(days)
            if (frac > 0.00001) {
                val secs = Math.round(frac * 86400)
                val dt = LocalDateTime.of(date.year, date.month, date.dayOfMonth, 0, 0).plusSeconds(secs)
                if (days == 0L) dt.format(DateTimeFormatter.ofPattern("HH:mm")) else dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            } else date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        } catch (_: Exception) {
            number(d)
        }
    }

    // ---------------- POWERPOINT ----------------
    fun pptx(z: Map<String, ByteArray>, assets: File, prefix: String): String {
        val presRels = rels(z, "ppt/_rels/presentation.xml.rels", "ppt")
        val slides = ArrayList<String>()
        z["ppt/presentation.xml"]?.let { data ->
            val p = parser(data)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "sldId") attr(p, "id")?.let { presRels[it] }?.let { if (z.containsKey(it)) slides.add(it) }
                ev = p.next()
            }
        }
        if (slides.isEmpty()) z.keys.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .sortedBy { Regex("\\d+").findAll(it).last().value.toInt() }.forEach { slides.add(it) }
        val out = StringBuilder()
        slides.forEachIndexed { idx, path ->
            val dir = path.substringBeforeLast('/')
            val r = rels(z, "$dir/_rels/${path.substringAfterLast('/')}.rels", dir)
            val p = parser(z[path]!!)
            val sb = StringBuilder()
            var para: StringBuilder? = null
            var inT = false
            var title = false
            var bold = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (local(p.name)) {
                        "sp" -> title = false
                        "ph" -> { val t = attr(p, "type"); if (t == "title" || t == "ctrTitle") title = true }
                        "p" -> para = StringBuilder()
                        "rPr" -> bold = attr(p, "b") == "1"
                        "t" -> inT = true
                        "br" -> para?.append("<br>")
                        "blip" -> {
                            val f = attr(p, "embed")?.let { r[it] }?.let { saveImg(z, it, assets, prefix) }
                            if (f != null) sb.append("<div class=\"pic\"><img src=\"$f\" style=\"max-height:12cm\"></div>")
                        }
                    }
                    XmlPullParser.TEXT -> if (inT) para?.append(if (bold) "<b>${esc(p.text)}</b>" else esc(p.text))
                    XmlPullParser.END_TAG -> when (local(p.name)) {
                        "t" -> inT = false
                        "p" -> {
                            val t = para?.toString() ?: ""
                            if (t.isNotBlank()) sb.append(if (title) "<h2>$t</h2>" else "<p>$t</p>")
                            para = null
                        }
                    }
                }
                ev = p.next()
            }
            if (idx > 0) out.append("<div class=\"pb\"></div>")
            out.append("<div class=\"slide\"><div class=\"sn\">Diapositiva ${idx + 1}</div>$sb</div>")
        }
        if (slides.isEmpty()) out.append("<p>(presentación vacía o dañada)</p>")
        return out.toString()
    }

    // ---------------- OpenDocument (LibreOffice) ----------------
    fun odf(z: Map<String, ByteArray>, assets: File, prefix: String, kind: Kind): HtmlDoc {
        val data = z["content.xml"] ?: return HtmlDoc("<p>(documento vacío o dañado)</p>", false)
        val p = parser(data)
        val stack = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
        val tags = ArrayList<String>()
        val reps = ArrayList<Int>()
        var bullet = false
        var pageN = 0
        var maxCells = 0
        var cellsInRow = 0
        var inTable = 0
        var inBody = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = local(p.name)
                    if (n == "body") inBody = true
                    if (inBody) when (n) {
                        "h", "p" -> { stack.add(StringBuilder()); tags.add(n) }
                        "s" -> stack.last().append("&nbsp;".repeat(attr(p, "c")?.toIntOrNull()?.coerceIn(1, 50) ?: 1))
                        "tab" -> stack.last().append("&emsp;")
                        "line-break" -> stack.last().append("<br>")
                        "list-item" -> bullet = true
                        "table" -> { stack.last().append(if (kind == Kind.ODS) "<div class=\"sheet\">${esc(attr(p, "name") ?: "")}</div><table>" else "<table>"); inTable++ }
                        "table-row" -> { stack.last().append("<tr>"); cellsInRow = 0 }
                        "table-cell", "covered-table-cell" -> {
                            stack.add(StringBuilder()); tags.add("cell")
                            reps.add(attr(p, "number-columns-repeated")?.toIntOrNull() ?: 1)
                        }
                        "image" -> {
                            val href = attr(p, "href")
                            val f = href?.let { saveImg(z, it.removePrefix("./"), assets, prefix) }
                            if (f != null) stack.last().append("<img src=\"$f\">")
                        }
                        "page" -> { if (pageN > 0) stack.last().append("<div class=\"pb\"></div>"); pageN++; stack.last().append("<div class=\"slide\"><div class=\"sn\">Diapositiva $pageN</div>") }
                    }
                }
                XmlPullParser.TEXT -> if (inBody && stack.size > 1) stack.last().append(esc(p.text))
                XmlPullParser.END_TAG -> {
                    val n = local(p.name)
                    if (inBody) when (n) {
                        "h", "p" -> if (stack.size > 1) {
                            val c = stack.removeAt(stack.size - 1).toString()
                            tags.removeAt(tags.size - 1)
                            val inCell = tags.lastOrNull() == "cell"
                            when {
                                n == "h" -> stack.last().append("<h2>$c</h2>")
                                inCell -> stack.last().append(if (stack.last().isNotEmpty()) "<br>$c" else c)
                                bullet -> { stack.last().append("<p style=\"margin:2px 0 2px 18px\">•&nbsp;$c</p>"); bullet = false }
                                else -> stack.last().append("<p style=\"margin:4px 0\">${c.ifBlank { "&nbsp;" }}</p>")
                            }
                        }
                        "table-cell", "covered-table-cell" -> if (stack.size > 1) {
                            val c = stack.removeAt(stack.size - 1).toString()
                            tags.removeAt(tags.size - 1)
                            var rep = reps.removeAt(reps.size - 1)
                            rep = if (c.isBlank()) minOf(rep, 200) else minOf(rep, 50)
                            repeat(rep) { stack.last().append("<td>$c</td>") }
                            cellsInRow += rep
                            if (c.isNotBlank() && cellsInRow > maxCells) maxCells = cellsInRow
                        }
                        "table-row" -> {
                            val sb = stack.last()
                            while (sb.endsWith("<td></td>")) sb.setLength(sb.length - 9)
                            sb.append("</tr>")
                        }
                        "table" -> { stack.last().append("</table>"); inTable-- }
                        "list-item" -> bullet = false
                        "page" -> stack.last().append("</div>")
                        "body" -> inBody = false
                    }
                }
            }
            ev = p.next()
        }
        while (stack.size > 1) { val c = stack.removeAt(stack.size - 1); stack.last().append(c) }
        return HtmlDoc(stack[0].toString(), kind == Kind.ODP || maxCells > 6)
    }

    // ---------------- EPUB ----------------
    fun epub(z: Map<String, ByteArray>, assets: File, prefix: String): String {
        val container = z["META-INF/container.xml"]?.let { String(it, Charsets.UTF_8) } ?: ""
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: z.keys.firstOrNull { it.endsWith(".opf") } ?: return "<p>(libro dañado)</p>"
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = z[opfPath] ?: return "<p>(libro dañado)</p>"
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        val p = parser(opf)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (local(p.name)) {
                "item" -> { val id = attr(p, "id"); val h = attr(p, "href"); if (id != null && h != null) manifest[id] = resolve(opfDir, Uri.decode(h)) }
                "itemref" -> attr(p, "idref")?.let { spine.add(it) }
            }
            ev = p.next()
        }
        // copiamos las imágenes del libro
        val dirName = "${prefix}_epub"
        val root = File(assets, dirName)
        for ((k, v) in z) {
            val e = F.ext(k)
            if (e in setOf("jpg", "jpeg", "png", "gif", "svg", "webp", "bmp")) {
                val f = File(root, k)
                if (f.canonicalPath.startsWith(root.canonicalPath)) { f.parentFile?.mkdirs(); f.writeBytes(v) }
            }
        }
        val out = StringBuilder()
        var first = true
        for (id in spine) {
            val path = manifest[id] ?: continue
            val data = z[path] ?: continue
            val html = String(data, Charsets.UTF_8)
            var body = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: html
            body = body.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            val chapterDir = path.substringBeforeLast('/', "")
            body = Regex("(src|xlink:href|href)=\"([^\"]+)\"").replace(body) { m ->
                val a = m.groupValues[1]; val v = m.groupValues[2]
                if (a == "href" && !F.ext(v.substringBefore('#')).let { it in setOf("jpg", "jpeg", "png", "gif", "svg", "webp") }) {
                    "href=\"#\""
                } else if (v.startsWith("http") || v.startsWith("data:")) m.value
                else "$a=\"$dirName/${resolve(chapterDir, Uri.decode(v.substringBefore('#')))}\""
            }
            if (!first) out.append("<div class=\"pb\"></div>")
            first = false
            out.append(body)
        }
        if (out.isEmpty()) out.append("<p>(libro vacío)</p>")
        return out.toString()
    }

    // ---------------- RTF ----------------
    fun rtf(s: String): String {
        val out = StringBuilder()
        val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
            "footerl", "footerr", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "listtable",
            "listoverridetable", "rsidtbl", "generator", "mmathPr", "pgdsctbl", "filetbl", "revtbl", "fldinst", "bkmkstart", "bkmkend")
        val skipStack = ArrayList<Boolean>()
        var skip = false
        var uc = 1
        var pendingSkip = 0
        var i = 0
        val cp = charset("windows-1252")
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '{' -> { skipStack.add(skip); i++ }
                '}' -> { skip = if (skipStack.isNotEmpty()) skipStack.removeAt(skipStack.size - 1) else false; i++ }
                '\\' -> {
                    i++
                    if (i >= s.length) break
                    val n = s[i]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { if (!skip) out.append(n); i++ }
                        n == '\'' -> {
                            val hex = s.substring(i + 1, minOf(i + 3, s.length))
                            i += 3
                            if (pendingSkip > 0) pendingSkip--
                            else if (!skip) hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), cp)) }
                        }
                        n == '*' -> { skip = true; i++ }
                        n == '~' -> { if (!skip) out.append(' '); i++ }
                        n == '\n' || n == '\r' -> { if (!skip) out.append('\n'); i++ }
                        n.isLetter() -> {
                            val st = i
                            while (i < s.length && s[i].isLetter()) i++
                            val word = s.substring(st, i)
                            val ps = i
                            if (i < s.length && (s[i] == '-' || s[i].isDigit())) { i++; while (i < s.length && s[i].isDigit()) i++ }
                            val param = s.substring(ps, i).toIntOrNull()
                            if (i < s.length && s[i] == ' ') i++
                            if (word in skipDest) skip = true
                            else if (!skip) when (word) {
                                "par", "line", "sect", "row" -> out.append('\n')
                                "page" -> out.append("\n\n")
                                "tab", "cell" -> out.append('\t')
                                "emdash" -> out.append('—'); "endash" -> out.append('–'); "bullet" -> out.append('•')
                                "lquote" -> out.append('‘'); "rquote" -> out.append('’')
                                "ldblquote" -> out.append('“'); "rdblquote" -> out.append('”')
                                "u" -> { if (param != null) out.append((if (param < 0) param + 65536 else param).toChar()); pendingSkip = uc }
                                "uc" -> uc = param ?: 1
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else if (!skip) out.append(c)
                    i++
                }
            }
        }
        return out.toString().trim()
    }

    // ---------------- CSV ----------------
    fun csv(text: String, tsv: Boolean): HtmlDoc {
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val delim = if (tsv) '\t' else listOf(';', ',', '\t', '|').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var i = 0
        while (i < text.length && rows.size < 5000) {
            val c = text[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false }
                else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                delim -> { row.add(field.toString()); field = StringBuilder() }
                '\n' -> { row.add(field.toString()); field = StringBuilder(); rows.add(row); row = ArrayList() }
                '\r' -> {}
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        val cols = rows.maxOfOrNull { it.size } ?: 0
        val sb = StringBuilder("<table>")
        rows.forEachIndexed { r, cells ->
            sb.append("<tr>")
            for (c in 0 until cols) {
                val v = esc(cells.getOrElse(c) { "" })
                sb.append(if (r == 0) "<th>$v</th>" else "<td>$v</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        if (rows.size >= 5000) sb.append("<p><i>(Se muestran las primeras 5000 filas)</i></p>")
        return HtmlDoc(sb.toString(), cols > 6)
    }

    // ---------------- MARKDOWN ----------------
    fun markdown(t: String): String {
        val sb = StringBuilder()
        var inCode = false
        var inList = false
        fun inline(s: String): String {
            var x = esc(s)
            x = x.replace(Regex("`([^`]+)`"), "<code>$1</code>")
            x = x.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
            x = x.replace(Regex("__([^_]+)__"), "<b>$1</b>")
            x = x.replace(Regex("(?<![*\\w])\\*([^*]+)\\*(?!\\w)"), "<i>$1</i>")
            x = x.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "[$1]")
            x = x.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<u>$1</u>")
            return x
        }
        for (line in t.lines()) {
            if (line.trimStart().startsWith("```")) {
                if (inList) { sb.append("</ul>"); inList = false }
                sb.append(if (inCode) "</pre>" else "<pre style=\"background:#f4f4f4;padding:8px\">")
                inCode = !inCode
                continue
            }
            if (inCode) { sb.append(esc(line)).append('\n'); continue }
            val h = Regex("^(#{1,6})\\s+(.*)").find(line)
            val li = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)").find(line)
            when {
                h != null -> { if (inList) { sb.append("</ul>"); inList = false }; val n = h.groupValues[1].length; sb.append("<h$n>${inline(h.groupValues[2])}</h$n>") }
                li != null -> { if (!inList) { sb.append("<ul>"); inList = true }; sb.append("<li>${inline(li.groupValues[2])}</li>") }
                line.startsWith(">") -> sb.append("<blockquote>${inline(line.removePrefix(">").trim())}</blockquote>")
                line.isBlank() -> { if (inList) { sb.append("</ul>"); inList = false }; sb.append("<br>") }
                Regex("^\\s*(---+|\\*\\*\\*+)\\s*$").matches(line) -> sb.append("<hr>")
                else -> { if (inList) { sb.append("</ul>"); inList = false }; sb.append("<p style=\"margin:3px 0\">${inline(line)}</p>") }
            }
        }
        if (inCode) sb.append("</pre>")
        if (inList) sb.append("</ul>")
        return sb.toString()
    }

    fun json(t: String): String {
        val pretty = try {
            val s = t.trim()
            if (s.startsWith("[")) JSONArray(s).toString(2) else JSONObject(s).toString(2)
        } catch (_: Exception) {
            t
        }
        return textBlock(pretty)
    }

    fun htmlBody(h: String): String {
        val styles = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).findAll(h).joinToString("") { it.value }
        var body = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1) ?: h
        body = body.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        return styles + body
    }
}
