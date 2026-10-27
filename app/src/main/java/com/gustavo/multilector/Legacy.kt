package com.gustavo.multilector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap

/**
 * Formatos viejos de Office (.doc, .xls, .ppt) — archivos "OLE2".
 * No usa nada de Android (se puede probar en la computadora).
 */
class Ole(private val b: ByteArray) {
    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private val secSize: Int
    private val miniSize: Int
    private val cutoff: Long
    private val fat = ArrayList<Int>()
    private val miniFat = ArrayList<Int>()
    val entries = ArrayList<Entry>()
    private var miniStream = ByteArray(0)

    init {
        if (!isOle(b)) throw IllegalArgumentException("no es OLE")
        secSize = 1 shl u16(0x1E)
        miniSize = 1 shl u16(0x20)
        cutoff = u32(0x38)
        val firstDir = i32(0x30)
        val firstMiniFat = i32(0x3C)
        val firstDifat = i32(0x44)
        val numDifat = i32(0x48)
        // DIFAT -> lista de sectores de la FAT
        val fatSectors = ArrayList<Int>()
        for (k in 0 until 109) { val s = i32(0x4C + k * 4); if (s >= 0) fatSectors.add(s) }
        var d = firstDifat
        var guard = 0
        while (d >= 0 && guard < numDifat + 1 && guard < 10000) {
            val off = secOff(d)
            val per = secSize / 4 - 1
            for (k in 0 until per) { val s = i32(off + k * 4); if (s >= 0) fatSectors.add(s) }
            d = i32(off + per * 4)
            guard++
        }
        for (s in fatSectors) {
            val off = secOff(s)
            if (off + secSize > b.size) break
            for (k in 0 until secSize / 4) fat.add(i32(off + k * 4))
        }
        // directorio
        val dir = chain(firstDir)
        var p = 0
        while (p + 128 <= dir.size) {
            val db = ByteBuffer.wrap(dir, p, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = db.getShort(p + 0x40).toInt() and 0xFFFF
            val type = dir[p + 0x42].toInt() and 0xFF
            val start = db.getInt(p + 0x74)
            val size = db.getInt(p + 0x78).toLong() and 0xFFFFFFFFL
            val name = if (nameLen >= 2) String(dir, p, minOf(nameLen - 2, 62), Charsets.UTF_16LE) else ""
            entries.add(Entry(name, type, start, size))
            p += 128
        }
        val root = entries.firstOrNull { it.type == 5 }
        if (root != null) {
            miniStream = chain(root.start)
            val mf = chain(firstMiniFat)
            val m = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until mf.size / 4) miniFat.add(m.getInt(k * 4))
        }
    }

    private fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
    private fun i32(o: Int) = bb.getInt(o)
    private fun u32(o: Int) = bb.getInt(o).toLong() and 0xFFFFFFFFL
    private fun secOff(s: Int) = (s + 1) * secSize

    private fun chain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size + 1 && guard < 2_000_000) {
            val off = secOff(s)
            if (off >= b.size) break
            out.write(b, off, minOf(secSize, b.size - off))
            s = if (s < fat.size) fat[s] else -2
            guard++
        }
        return out.toByteArray()
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && guard < 2_000_000 && out.size() < size) {
            val off = s * miniSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSize, miniStream.size - off))
            s = if (s < miniFat.size) miniFat[s] else -2
            guard++
        }
        return out.toByteArray()
    }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        val data = if (e.size < cutoff) miniChain(e.start, e.size) else chain(e.start)
        return if (data.size > e.size) data.copyOf(e.size.toInt()) else data
    }

    fun has(name: String) = entries.any { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    companion object {
        fun isOle(b: ByteArray) = b.size > 512 && (b[0].toInt() and 0xFF) == 0xD0 && (b[1].toInt() and 0xFF) == 0xCF &&
            (b[2].toInt() and 0xFF) == 0x11 && (b[3].toInt() and 0xFF) == 0xE0
    }
}

object Legacy {
    private val cp1252 = charset("windows-1252")

    // ---------------- WORD .doc ----------------
    /** Devuelve el texto del documento (párrafos separados por \n). */
    fun docText(data: ByteArray): String {
        val ole = Ole(data)
        val wd = ole.stream("WordDocument") ?: throw Exception("no es un Word")
        val w = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
        val flags = w.getShort(0x0A).toInt() and 0xFFFF
        if (flags and 0x0100 != 0) throw Exception("El documento está protegido con contraseña.")
        val table = ole.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: ole.stream("1Table") ?: ole.stream("0Table")
        val sb = StringBuilder()
        var ok = false
        if (table != null && wd.size > 0x1AA) {
            val fcClx = w.getInt(0x1A2)
            val lcbClx = w.getInt(0x1A6)
            if (fcClx >= 0 && lcbClx > 0 && fcClx + lcbClx <= table.size) {
                val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
                var p = fcClx
                val end = fcClx + lcbClx
                while (p < end && table[p].toInt() == 1) { val cb = t.getShort(p + 1).toInt() and 0xFFFF; p += 3 + cb }
                if (p < end && table[p].toInt() == 2) {
                    val lcb = t.getInt(p + 1)
                    val plc = p + 5
                    val n = (lcb - 4) / 12
                    for (i in 0 until n) {
                        val cpStart = t.getInt(plc + i * 4)
                        val cpEnd = t.getInt(plc + (i + 1) * 4)
                        val pcd = plc + (n + 1) * 4 + i * 8
                        val fcRaw = t.getInt(pcd + 2)
                        val chars = cpEnd - cpStart
                        if (chars <= 0) continue
                        val compressed = fcRaw and 0x40000000 != 0
                        if (compressed) {
                            val fc = (fcRaw and 0x3FFFFFFF) / 2
                            if (fc + chars <= wd.size) sb.append(String(wd, fc, chars, cp1252))
                        } else {
                            if (fcRaw + chars * 2 <= wd.size) sb.append(String(wd, fcRaw, chars * 2, Charsets.UTF_16LE))
                        }
                    }
                    ok = sb.isNotEmpty()
                }
            }
        }
        if (!ok) {
            // plan B: Word viejo sin tabla de piezas
            val fcMin = w.getInt(0x18); val fcMac = w.getInt(0x1C)
            if (fcMin in 0 until fcMac && fcMac <= wd.size) sb.append(String(wd, fcMin, fcMac - fcMin, cp1252))
        }
        return cleanWord(sb.toString())
    }

    private fun cleanWord(s: String): String {
        val out = StringBuilder(s.length)
        var fieldDepth = 0
        val inInstr = ArrayList<Boolean>()
        for (c in s) {
            when (c.code) {
                0x13 -> { fieldDepth++; inInstr.add(true) }
                0x14 -> { if (inInstr.isNotEmpty()) inInstr[inInstr.size - 1] = false }
                0x15 -> { if (fieldDepth > 0) { fieldDepth--; inInstr.removeAt(inInstr.size - 1) } }
                else -> {
                    if (inInstr.any { it }) continue
                    when (c.code) {
                        0x0D -> out.append('\n')
                        0x07 -> out.append('\t')
                        0x0B -> out.append('\n')
                        0x0C -> out.append("\n\u000C\n")
                        0x1E -> out.append('-')
                        0x1F -> {}
                        0x01, 0x08, 0x05, 0x02, 0x03, 0x04 -> {}
                        else -> if (c.code >= 0x20 || c == '\t') out.append(c)
                    }
                }
            }
        }
        return out.toString().trim()
    }

    // ---------------- POWERPOINT .ppt ----------------
    fun pptText(data: ByteArray): List<List<String>> {
        val ole = Ole(data)
        val s = ole.stream("PowerPoint Document") ?: throw Exception("no es un PowerPoint")
        val bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
        val fromList = ArrayList<MutableList<String>>()   // SlideListWithText (texto principal)
        val fromSlides = ArrayList<MutableList<String>>() // dentro de cada diapositiva
        val stack = ArrayList<IntArray>() // tipo, instancia, fin
        var p = 0
        while (p + 8 <= s.size) {
            while (stack.isNotEmpty() && p >= stack.last()[2]) stack.removeAt(stack.size - 1)
            val verInst = bb.getShort(p).toInt() and 0xFFFF
            val type = bb.getShort(p + 2).toInt() and 0xFFFF
            val len = bb.getInt(p + 4)
            if (len < 0 || p + 8 + len > s.size) break
            val ver = verInst and 0x0F
            val inst = verInst shr 4
            if (ver == 0x0F) {
                stack.add(intArrayOf(type, inst, p + 8 + len))
                if (type == 0x03EE) fromSlides.add(ArrayList())
                p += 8
                continue
            }
            val skip = stack.any { it[0] == 0x03F8 || it[0] == 0x03F0 || it[0] == 0x0FC9 || (it[0] == 0x0FF0 && it[1] != 0) }
            val inList = stack.any { it[0] == 0x0FF0 && it[1] == 0 }
            val inSlide = stack.any { it[0] == 0x03EE }
            if (type == 0x03F3 && inList) fromList.add(ArrayList())
            if (!skip && (type == 0x0FA0 || type == 0x0FA8)) {
                val t = if (type == 0x0FA8) String(s, p + 8, len, cp1252) else String(s, p + 8, len, Charsets.UTF_16LE)
                val clean = t.replace('\r', '\n').replace('\u000B', '\n').trim()
                if (clean.isNotEmpty() && clean != "*") {
                    when {
                        inList && fromList.isNotEmpty() -> fromList.last().add(clean)
                        inSlide && fromSlides.isNotEmpty() -> fromSlides.last().add(clean)
                    }
                }
            }
            p += 8 + len
        }
        val base = if (fromList.any { it.isNotEmpty() }) fromList else fromSlides
        val other = if (base === fromList) fromSlides else ArrayList()
        for (i in base.indices) other.getOrNull(i)?.forEach { if (it !in base[i]) base[i].add(it) }
        return base.filter { it.isNotEmpty() }
    }

    // ---------------- EXCEL .xls ----------------
    class Sheet(val name: String, val grid: TreeMap<Int, TreeMap<Int, Pair<String, Boolean>>>, val truncated: Boolean)

    fun xls(data: ByteArray, maxRows: Int = 4000, maxCols: Int = 60): List<Sheet> {
        val ole = Ole(data)
        val wb = ole.stream("Workbook") ?: ole.stream("Book") ?: throw Exception("no es un Excel")
        val bb = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        // pasada 1: hojas, SST, formatos
        val sheets = ArrayList<Pair<String, Int>>()
        val sst = ArrayList<String>()
        val xfFmt = ArrayList<Int>()
        val customFmt = HashMap<Int, String>()
        var p = 0
        var inGlobals = true
        while (p + 4 <= wb.size && inGlobals) {
            val id = bb.getShort(p).toInt() and 0xFFFF
            val len = bb.getShort(p + 2).toInt() and 0xFFFF
            val d = p + 4
            if (d + len > wb.size) break
            when (id) {
                0x2F -> throw Exception("La planilla está protegida con contraseña.")
                0x85 -> {
                    val pos = bb.getInt(d)
                    val cch = wb[d + 6].toInt() and 0xFF
                    val hi = wb[d + 7].toInt() and 1
                    val nm = if (hi == 1) String(wb, d + 8, minOf(cch * 2, len - 8), Charsets.UTF_16LE) else String(wb, d + 8, minOf(cch, len - 8), cp1252)
                    val type = wb[d + 5].toInt() and 0xFF
                    if (type == 0) sheets.add(nm to pos)
                }
                0xE0 -> xfFmt.add(bb.getShort(d + 2).toInt() and 0xFFFF)
                0x41E -> {
                    val ifmt = bb.getShort(d).toInt() and 0xFFFF
                    val cch = bb.getShort(d + 2).toInt() and 0xFFFF
                    val hi = wb[d + 4].toInt() and 1
                    val code = if (hi == 1) String(wb, d + 5, minOf(cch * 2, len - 5), Charsets.UTF_16LE) else String(wb, d + 5, minOf(cch, len - 5), cp1252)
                    customFmt[ifmt] = code
                }
                0xFC -> {
                    // SST + CONTINUE
                    val parts = ArrayList<IntArray>()
                    parts.add(intArrayOf(d, len))
                    var q = d + len
                    while (q + 4 <= wb.size && (bb.getShort(q).toInt() and 0xFFFF) == 0x3C) {
                        val l = bb.getShort(q + 2).toInt() and 0xFFFF
                        parts.add(intArrayOf(q + 4, l))
                        q += 4 + l
                    }
                    readSst(wb, parts, sst)
                    p = q
                    continue
                }
                0x0A -> inGlobals = false
            }
            p = d + len
        }
        val dateXf = HashSet<Int>()
        xfFmt.forEachIndexed { i, f ->
            val isDate = f in 14..22 || f in 45..47 || customFmt[f]?.let { code ->
                val clean = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\."), "").lowercase(Locale.ROOT)
                clean.contains('d') || clean.contains('y') || (clean.contains('m') && !clean.contains('0') && !clean.contains('#')) || clean.contains('h')
            } == true
            if (isDate) dateXf.add(i)
        }
        val result = ArrayList<Sheet>()
        for ((name, pos) in sheets) {
            val grid = TreeMap<Int, TreeMap<Int, Pair<String, Boolean>>>()
            var truncated = false
            fun put(r: Int, c: Int, v: String, num: Boolean) {
                if (r >= maxRows) { truncated = true; return }
                if (c >= maxCols || v.isEmpty()) return
                grid.getOrPut(r + 1) { TreeMap() }[c] = v to num
            }
            fun numStr(v: Double, xf: Int) = if (xf in dateXf) excelDate(v) else number(v)
            var q = pos
            var pendingFormula: IntArray? = null
            while (q + 4 <= wb.size) {
                val id = bb.getShort(q).toInt() and 0xFFFF
                val len = bb.getShort(q + 2).toInt() and 0xFFFF
                val d = q + 4
                if (d + len > wb.size) break
                if (id == 0x0A) break
                when (id) {
                    0xFD -> { // LABELSST
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c = bb.getShort(d + 2).toInt() and 0xFFFF
                        val i = bb.getInt(d + 6)
                        put(r, c, sst.getOrNull(i) ?: "", false)
                    }
                    0x203 -> { // NUMBER
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c = bb.getShort(d + 2).toInt() and 0xFFFF
                        val xf = bb.getShort(d + 4).toInt() and 0xFFFF
                        put(r, c, numStr(bb.getDouble(d + 6), xf), true)
                    }
                    0x27E -> { // RK
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c = bb.getShort(d + 2).toInt() and 0xFFFF
                        val xf = bb.getShort(d + 4).toInt() and 0xFFFF
                        put(r, c, numStr(rk(bb.getInt(d + 6)), xf), true)
                    }
                    0xBD -> { // MULRK
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c0 = bb.getShort(d + 2).toInt() and 0xFFFF
                        val n = (len - 6) / 6
                        for (k in 0 until n) {
                            val xf = bb.getShort(d + 4 + k * 6).toInt() and 0xFFFF
                            put(r, c0 + k, numStr(rk(bb.getInt(d + 6 + k * 6)), xf), true)
                        }
                    }
                    0x06 -> { // FORMULA
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c = bb.getShort(d + 2).toInt() and 0xFFFF
                        val xf = bb.getShort(d + 4).toInt() and 0xFFFF
                        val b6 = wb[d + 12].toInt() and 0xFF; val b7 = wb[d + 13].toInt() and 0xFF
                        if (b6 == 0xFF && b7 == 0xFF) {
                            when (wb[d + 6].toInt() and 0xFF) {
                                0 -> pendingFormula = intArrayOf(r, c)
                                1 -> put(r, c, if (wb[d + 8].toInt() != 0) "VERDADERO" else "FALSO", false)
                                2 -> put(r, c, "#ERROR", false)
                            }
                        } else put(r, c, numStr(bb.getDouble(d + 6), xf), true)
                    }
                    0x207 -> { // STRING (resultado de fórmula)
                        val pf = pendingFormula
                        if (pf != null) {
                            val cch = bb.getShort(d).toInt() and 0xFFFF
                            val hi = wb[d + 2].toInt() and 1
                            val s = if (hi == 1) String(wb, d + 3, minOf(cch * 2, len - 3), Charsets.UTF_16LE) else String(wb, d + 3, minOf(cch, len - 3), cp1252)
                            put(pf[0], pf[1], s, false)
                            pendingFormula = null
                        }
                    }
                    0x204 -> { // LABEL
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c = bb.getShort(d + 2).toInt() and 0xFFFF
                        val cch = bb.getShort(d + 6).toInt() and 0xFFFF
                        val hi = wb[d + 8].toInt() and 1
                        val s = if (hi == 1) String(wb, d + 9, minOf(cch * 2, len - 9), Charsets.UTF_16LE) else String(wb, d + 9, minOf(cch, len - 9), cp1252)
                        put(r, c, s, false)
                    }
                    0x205 -> { // BOOLERR
                        val r = bb.getShort(d).toInt() and 0xFFFF; val c = bb.getShort(d + 2).toInt() and 0xFFFF
                        val v = wb[d + 6].toInt() and 0xFF; val err = wb[d + 7].toInt() and 0xFF
                        put(r, c, if (err == 1) "#ERROR" else if (v != 0) "VERDADERO" else "FALSO", false)
                    }
                }
                q = d + len
            }
            result.add(Sheet(name, grid, truncated))
        }
        return result
    }

    private fun readSst(wb: ByteArray, parts: List<IntArray>, out: ArrayList<String>) {
        var pi = 0
        var pos = parts[0][0]
        var end = parts[0][0] + parts[0][1]
        fun ensure(n: Int): Boolean {
            if (pos + n <= end) return true
            if (pos >= end && pi + 1 < parts.size) { pi++; pos = parts[pi][0]; end = pos + parts[pi][1]; return pos + n <= end }
            return false
        }
        fun u8(): Int { if (!ensure(1)) throw IndexOutOfBoundsException(); return wb[pos++].toInt() and 0xFF }
        fun u16(): Int { val a = u8(); val b2 = u8(); return a or (b2 shl 8) }
        fun i32(): Int { val a = u16(); val b2 = u16(); return a or (b2 shl 16) }
        fun skip(n: Int) { var left = n; while (left > 0) { if (pos >= end) { if (pi + 1 >= parts.size) return; pi++; pos = parts[pi][0]; end = pos + parts[pi][1] }; val k = minOf(left, end - pos); pos += k; left -= k } }
        try {
            i32(); val unique = i32()
            for (i in 0 until unique) {
                val cch = u16()
                var flags = u8()
                val rich = flags and 0x08 != 0
                val ext = flags and 0x04 != 0
                val runs = if (rich) u16() else 0
                val extLen = if (ext) i32() else 0
                val sb = StringBuilder(cch)
                var left = cch
                while (left > 0) {
                    if (pos >= end) {
                        if (pi + 1 >= parts.size) break
                        pi++; pos = parts[pi][0]; end = pos + parts[pi][1]
                        flags = u8() // al cortar en un CONTINUE se repite el byte de compresión
                    }
                    val hi = flags and 1 != 0
                    val avail = if (hi) (end - pos) / 2 else end - pos
                    val n = minOf(left, avail)
                    if (n <= 0) break
                    if (hi) { sb.append(String(wb, pos, n * 2, Charsets.UTF_16LE)); pos += n * 2 } else { sb.append(String(wb, pos, n, cp1252)); pos += n }
                    left -= n
                }
                skip(runs * 4)
                skip(extLen)
                out.add(sb.toString())
            }
        } catch (_: IndexOutOfBoundsException) {
        }
    }

    private fun rk(v: Int): Double {
        val d = if (v and 0x02 != 0) (v shr 2).toDouble()
        else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 0x01 != 0) d / 100.0 else d
    }

    fun number(d: Double): String {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return String.format(Locale.ROOT, "%.6f", d).trimEnd('0').trimEnd('.').replace('.', ',')
    }

    fun excelDate(d: Double): String = try {
        val days = Math.floor(d).toLong()
        val frac = d - days
        val date = LocalDate.of(1899, 12, 30).plusDays(days)
        if (frac > 0.00001) {
            val dt = LocalDateTime.of(date.year, date.month, date.dayOfMonth, 0, 0).plusSeconds(Math.round(frac * 86400))
            if (days == 0L) dt.format(DateTimeFormatter.ofPattern("HH:mm")) else dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        } else date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    } catch (_: Exception) {
        number(d)
    }
}
