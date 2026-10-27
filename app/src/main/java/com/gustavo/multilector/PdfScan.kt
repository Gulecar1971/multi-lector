package com.gustavo.multilector

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Lector "a mano" de PDF para recuperar fotos y adjuntos sin tocarlos.
 * No usa nada de Android (se puede probar en la computadora).
 */
class PdfScan(private val b: ByteArray) {

    class Obj(val num: Int, val dict: String?, val value: String?, val sStart: Int, var sLen: Int, val lenRef: Int)

    /** Foto encontrada lista para guardar tal cual (JPG o JPEG2000). */
    class Encoded(val name: String?, val data: ByteArray, val ext: String)

    /** Foto encontrada como píxeles (hay que armar un PNG). */
    class Raw(
        val name: String?, val w: Int, val h: Int, val bpc: Int, val comps: Int, val data: ByteArray,
        val palette: ByteArray?, val palComps: Int, val invert: Boolean
    )

    class Attached(val name: String, val data: ByteArray)

    class Result(val encoded: List<Encoded>, val raw: List<Raw>, val files: List<Attached>, val unsupported: Int, val encrypted: Boolean)

    val objs = HashMap<Int, Obj>()
    private val order = ArrayList<Int>()

    init {
        scan()
        for (o in objs.values) {
            if (o.lenRef >= 0 && o.sStart >= 0) {
                val v = objs[o.lenRef]?.value?.trim()?.toIntOrNull()
                if (v != null && v >= 0 && o.sStart + v <= b.size && endstreamNear(o.sStart + v)) o.sLen = v
            }
        }
    }

    // ---------- lectura de bytes ----------
    private fun at(i: Int): Int = if (i >= 0 && i < b.size) b[i].toInt() and 0xFF else -1
    private fun ws(c: Int) = c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00
    private fun digit(c: Int) = c in 0x30..0x39
    private fun letter(c: Int) = (c in 0x41..0x5A) || (c in 0x61..0x7A)

    private fun startsAt(p: Int, s: String): Boolean {
        if (p < 0 || p + s.length > b.size) return false
        for (k in s.indices) if (at(p + k) != s[k].code) return false
        return true
    }

    private fun indexOf(s: String, from: Int, to: Int = b.size): Int {
        val first = s[0].code.toByte()
        var i = maxOf(0, from)
        val end = minOf(to, b.size) - s.length
        while (i <= end) {
            if (b[i] == first && startsAt(i, s)) return i
            i++
        }
        return -1
    }

    private fun endstreamNear(p: Int): Boolean {
        var i = p
        while (ws(at(i)) && i < p + 4) i++
        return startsAt(i, "endstream")
    }

    private fun findEndstream(s: Int): Int {
        val idx = indexOf("endstream", s)
        if (idx < 0) return b.size - s
        var len = idx - s
        if (len > 0 && at(s + len - 1) == 0x0A) len--
        if (len > 0 && at(s + len - 1) == 0x0D) len--
        return len
    }

    private fun scan() {
        var i = 0
        val n = b.size
        while (i < n - 3) {
            if (b[i] == 0x6F.toByte() && b[i + 1] == 0x62.toByte() && b[i + 2] == 0x6A.toByte()) {
                val num = numBefore(i)
                if (num >= 0) {
                    val next = readObj(num, i + 3)
                    if (next > i) { i = next; continue }
                }
            }
            i++
        }
    }

    private fun numBefore(p: Int): Int {
        var j = p - 1
        if (!ws(at(j))) return -1
        while (ws(at(j))) j--
        val gEnd = j
        while (digit(at(j))) j--
        if (j == gEnd) return -1
        if (!ws(at(j))) return -1
        while (ws(at(j))) j--
        val nEnd = j
        while (digit(at(j))) j--
        if (j == nEnd || nEnd - j > 9) return -1
        if (letter(at(j)) || digit(at(j))) return -1
        return String(b, j + 1, nEnd - j, Charsets.ISO_8859_1).toIntOrNull() ?: -1
    }

    private fun put(o: Obj) {
        if (!objs.containsKey(o.num)) order.add(o.num)
        objs[o.num] = o
    }

    private fun readObj(num: Int, from: Int): Int {
        if (letter(at(from)) || digit(at(from))) return -1
        var q = from
        while (ws(at(q))) q++
        if (at(q) == 0x3C && at(q + 1) == 0x3C) {
            val e = dictEnd(q)
            if (e < 0) return -1
            val dict = String(b, q, e - q, Charsets.ISO_8859_1)
            var s = e
            while (ws(at(s))) s++
            if (startsAt(s, "stream")) {
                s += 6
                if (at(s) == 0x0D) s++
                if (at(s) == 0x0A) s++
                val top = topLevel(dict)
                val m = Regex("/Length\\s+(\\d+)(?:\\s+(\\d+)\\s+R)?").find(top)
                var len = -1
                var ref = -1
                if (m != null) {
                    if (m.groupValues[2].isNotEmpty()) ref = m.groupValues[1].toIntOrNull() ?: -1
                    else len = m.groupValues[1].toIntOrNull() ?: -1
                }
                if (len < 0 || s + len > b.size || !endstreamNear(s + len)) len = findEndstream(s)
                put(Obj(num, dict, null, s, len, ref))
                return s + maxOf(len, 0)
            }
            put(Obj(num, dict, null, -1, 0, -1))
            return e
        }
        val endIdx = indexOf("endobj", q, minOf(b.size, q + 4096))
        if (endIdx > 0) {
            put(Obj(num, null, String(b, q, endIdx - q, Charsets.ISO_8859_1).trim(), -1, 0, -1))
            return endIdx
        }
        return -1
    }

    private fun skipString(p: Int): Int {
        var i = p + 1
        var d = 1
        while (i < b.size && d > 0) {
            when (at(i)) {
                0x5C -> i++
                0x28 -> d++
                0x29 -> d--
            }
            i++
        }
        return i
    }

    private fun dictEnd(start: Int): Int {
        var i = start
        var depth = 0
        val n = b.size
        while (i < n) {
            val c = at(i)
            when {
                c == 0x3C && at(i + 1) == 0x3C -> { depth++; i += 2 }
                c == 0x3E && at(i + 1) == 0x3E -> { depth--; i += 2; if (depth == 0) return i }
                c == 0x28 -> i = skipString(i)
                c == 0x3C -> { i++; while (i < n && at(i) != 0x3E) i++; i++ }
                c == 0x25 -> { while (i < n && at(i) != 0x0A && at(i) != 0x0D) i++ }
                else -> i++
            }
            if (i - start > 4_000_000) return -1
        }
        return -1
    }

    // ---------- ayudas sobre el texto del diccionario ----------
    companion object {
        /** Deja solo el primer nivel del diccionario (saca los diccionarios anidados). */
        fun topLevel(d: String): String {
            val sb = StringBuilder()
            var depth = 0
            var i = 0
            while (i < d.length) {
                val c = d[i]
                if (c == '<' && i + 1 < d.length && d[i + 1] == '<') {
                    depth++; if (depth == 1) sb.append("<<") else sb.append(' '); i += 2; continue
                }
                if (c == '>' && i + 1 < d.length && d[i + 1] == '>') {
                    if (depth == 1) sb.append(">>"); depth--; i += 2; continue
                }
                if (c == '(') {
                    var j = i + 1; var dd = 1
                    while (j < d.length && dd > 0) { if (d[j] == '\\') j++ else if (d[j] == '(') dd++ else if (d[j] == ')') dd--; j++ }
                    if (depth <= 1) sb.append(d, i, minOf(j, d.length))
                    i = j; continue
                }
                if (depth <= 1) sb.append(c)
                i++
            }
            return sb.toString()
        }

        /** Diccionario anidado bajo una clave (por ejemplo /DecodeParms << … >>). */
        fun nested(d: String, key: String): String? {
            val m = Regex("/$key\\s*<<").find(d) ?: return null
            var i = m.range.last - 1
            var depth = 0
            val start = i
            while (i < d.length - 1) {
                if (d[i] == '<' && d[i + 1] == '<') { depth++; i += 2; continue }
                if (d[i] == '>' && d[i + 1] == '>') { depth--; i += 2; if (depth == 0) return d.substring(start, i); continue }
                i++
            }
            return null
        }

        fun name(top: String, key: String): String? =
            Regex("/$key\\s*/([A-Za-z0-9#._+\\-]+)").find(top)?.groupValues?.get(1)

        fun filters(top: String): List<String> {
            Regex("/Filter\\s*\\[([^\\]]*)\\]").find(top)?.let { m ->
                return Regex("/([A-Za-z0-9]+)").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            }
            return name(top, "Filter")?.let { listOf(it) } ?: emptyList()
        }

        /** Lee una cadena PDF (literal o hexadecimal) que sigue a la clave. */
        fun string(d: String, key: String): String? {
            val m = Regex("/$key(?=[\\s(<])\\s*").find(d) ?: return null
            var i = m.range.last + 1
            if (i >= d.length) return null
            val bytes = ByteArrayOutputStream()
            if (d[i] == '(') {
                i++
                var depth = 1
                while (i < d.length) {
                    val c = d[i]
                    if (c == '\\' && i + 1 < d.length) {
                        val n = d[i + 1]
                        when (n) {
                            'n' -> { bytes.write(10); i += 2 }
                            'r' -> { bytes.write(13); i += 2 }
                            't' -> { bytes.write(9); i += 2 }
                            'b' -> { bytes.write(8); i += 2 }
                            'f' -> { bytes.write(12); i += 2 }
                            in '0'..'7' -> {
                                var j = i + 1; var v = 0; var k = 0
                                while (k < 3 && j < d.length && d[j] in '0'..'7') { v = v * 8 + (d[j] - '0'); j++; k++ }
                                bytes.write(v and 0xFF); i = j
                            }
                            '\r', '\n' -> { i += 2 }
                            else -> { bytes.write(n.code and 0xFF); i += 2 }
                        }
                        continue
                    }
                    if (c == '(') depth++
                    if (c == ')') { depth--; if (depth == 0) break }
                    bytes.write(c.code and 0xFF)
                    i++
                }
            } else if (d[i] == '<' && (i + 1 >= d.length || d[i + 1] != '<')) {
                val end = d.indexOf('>', i)
                if (end < 0) return null
                val hex = d.substring(i + 1, end).filter { it.isLetterOrDigit() }
                var k = 0
                while (k + 1 < hex.length) { bytes.write(hex.substring(k, k + 2).toInt(16)); k += 2 }
                if (k < hex.length) bytes.write(hex.substring(k, k + 1).toInt(16) * 16)
            } else return null
            return decodePdfText(bytes.toByteArray())
        }

        fun decodePdfText(a: ByteArray): String =
            if (a.size >= 2 && a[0] == 0xFE.toByte() && a[1] == 0xFF.toByte()) String(a, 2, a.size - 2, Charsets.UTF_16BE)
            else String(a, Charsets.ISO_8859_1)

        fun inflate(d: ByteArray): ByteArray {
            fun run(nowrap: Boolean): ByteArray {
                val inf = Inflater(nowrap)
                inf.setInput(d)
                val bos = ByteArrayOutputStream(maxOf(1024, d.size * 3))
                val buf = ByteArray(1 shl 16)
                try {
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break }
                        bos.write(buf, 0, n)
                    }
                } catch (_: DataFormatException) {
                }
                inf.end()
                return bos.toByteArray()
            }
            val r = run(false)
            return if (r.isNotEmpty()) r else run(true)
        }

        /** Convierte una fila de una foto "cruda" del PDF a colores ARGB. */
        fun argbRow(r: Raw, y: Int, out: IntArray) {
            val pal = r.palette
            val comps = if (pal != null) 1 else r.comps
            val rowBytes = (r.w * comps * r.bpc + 7) / 8
            val rs = y * rowBytes
            val max = (1 shl minOf(r.bpc, 8)) - 1
            val d = r.data
            fun sample(idx: Int): Int {
                return when (r.bpc) {
                    8 -> { val p = rs + idx; if (p < d.size) d[p].toInt() and 0xFF else 0 }
                    16 -> { val p = rs + idx * 2; if (p < d.size) d[p].toInt() and 0xFF else 0 }
                    else -> {
                        val bit = idx * r.bpc
                        val p = rs + bit / 8
                        if (p >= d.size) 0 else ((d[p].toInt() and 0xFF) shr (8 - r.bpc - (bit % 8))) and max
                    }
                }
            }
            for (x in 0 until r.w) {
                var rr: Int; var gg: Int; var bb: Int
                if (pal != null) {
                    val i = sample(x) * r.palComps
                    fun pv(k: Int) = if (i + k < pal.size) pal[i + k].toInt() and 0xFF else 0
                    when (r.palComps) {
                        1 -> { rr = pv(0); gg = rr; bb = rr }
                        4 -> { val k = pv(3); rr = (255 - pv(0)) * (255 - k) / 255; gg = (255 - pv(1)) * (255 - k) / 255; bb = (255 - pv(2)) * (255 - k) / 255 }
                        else -> { rr = pv(0); gg = pv(1); bb = pv(2) }
                    }
                } else {
                    fun c(k: Int): Int {
                        var v = sample(x * comps + k)
                        if (r.bpc < 8) v = v * 255 / max
                        return if (r.invert) 255 - v else v
                    }
                    when (comps) {
                        1 -> { rr = c(0); gg = rr; bb = rr }
                        4 -> { val k = c(3); rr = (255 - c(0)) * (255 - k) / 255; gg = (255 - c(1)) * (255 - k) / 255; bb = (255 - c(2)) * (255 - k) / 255 }
                        else -> { rr = c(0); gg = c(1); bb = c(2) }
                    }
                }
                out[x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        fun asciiHex(d: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(d.size / 2)
            var hi = -1
            for (x in d) {
                val c = x.toInt().toChar()
                if (c == '>') break
                val v = Character.digit(c, 16)
                if (v < 0) continue
                if (hi < 0) hi = v else { o.write(hi * 16 + v); hi = -1 }
            }
            if (hi >= 0) o.write(hi * 16)
            return o.toByteArray()
        }

        fun ascii85(d: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(d.size)
            var tuple = 0L
            var cnt = 0
            var i = 0
            if (d.size > 1 && d[0] == '<'.code.toByte() && d[1] == '~'.code.toByte()) i = 2
            while (i < d.size) {
                val c = d[i].toInt() and 0xFF
                i++
                if (c == '~'.code) break
                if (c <= 32) continue
                if (c == 'z'.code && cnt == 0) { o.write(ByteArray(4)); continue }
                if (c < 33 || c > 117) continue
                tuple = tuple * 85 + (c - 33)
                cnt++
                if (cnt == 5) {
                    for (k in 3 downTo 0) o.write(((tuple shr (8 * k)) and 0xFF).toInt())
                    tuple = 0; cnt = 0
                }
            }
            if (cnt > 1) {
                for (k in cnt until 5) tuple = tuple * 85 + 84
                for (k in 3 downTo (5 - cnt)) o.write(((tuple shr (8 * k)) and 0xFF).toInt())
            }
            return o.toByteArray()
        }

        fun runLength(d: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(d.size * 2)
            var i = 0
            while (i < d.size) {
                val n = d[i].toInt() and 0xFF
                i++
                when {
                    n == 128 -> break
                    n < 128 -> { val len = minOf(n + 1, d.size - i); o.write(d, i, len); i += len }
                    else -> { if (i < d.size) { val v = d[i].toInt(); repeat(257 - n) { o.write(v) }; i++ } }
                }
            }
            return o.toByteArray()
        }

        fun lzw(d: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(d.size * 3)
            val dict = ArrayList<ByteArray>(4096)
            fun reset() { dict.clear(); for (k in 0 until 256) dict.add(byteArrayOf(k.toByte())); dict.add(ByteArray(0)); dict.add(ByteArray(0)) }
            reset()
            var bits = 9
            var buf = 0L
            var nbits = 0
            var prev: ByteArray? = null
            var i = 0
            while (true) {
                while (nbits < bits && i < d.size) { buf = (buf shl 8) or (d[i].toLong() and 0xFF); nbits += 8; i++ }
                if (nbits < bits) break
                val code = ((buf shr (nbits - bits)) and ((1L shl bits) - 1)).toInt()
                nbits -= bits
                if (code == 257) break
                if (code == 256) { reset(); bits = 9; prev = null; continue }
                val entry: ByteArray = when {
                    code < dict.size -> dict[code]
                    prev != null -> prev + prev[0]
                    else -> break
                }
                o.write(entry)
                if (prev != null) dict.add(prev + entry[0])
                prev = entry
                val next = dict.size + 1
                bits = when { next >= 2048 -> 12; next >= 1024 -> 11; next >= 512 -> 10; else -> 9 }
            }
            return o.toByteArray()
        }

        fun unpredict(d: ByteArray, pred: Int, colors: Int, bpc: Int, cols: Int): ByteArray {
            if (pred < 10) return d
            val bpp = maxOf(1, colors * bpc / 8)
            val rowLen = (cols * colors * bpc + 7) / 8
            if (rowLen <= 0) return d
            val rows = d.size / (rowLen + 1)
            val out = ByteArray(rows * rowLen)
            val prev = ByteArray(rowLen)
            for (r in 0 until rows) {
                val type = d[r * (rowLen + 1)].toInt() and 0xFF
                val off = r * (rowLen + 1) + 1
                val o = r * rowLen
                for (i in 0 until rowLen) {
                    val x = d[off + i].toInt() and 0xFF
                    val a = if (i >= bpp) out[o + i - bpp].toInt() and 0xFF else 0
                    val up = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val v = when (type) {
                        1 -> x + a
                        2 -> x + up
                        3 -> x + ((a + up) shr 1)
                        4 -> {
                            val p = a + up - c
                            val pa = Math.abs(p - a); val pb = Math.abs(p - up); val pc = Math.abs(p - c)
                            x + if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c
                        }
                        else -> x
                    }
                    out[o + i] = v.toByte()
                }
                System.arraycopy(out, o, prev, 0, rowLen)
            }
            return out
        }
    }

    // ---------- valores ----------
    private fun intOf(top: String, key: String): Int? {
        val m = Regex("/$key\\s+(-?\\d+)(?:\\s+(\\d+)\\s+R)?").find(top) ?: return null
        val v = m.groupValues[1].toIntOrNull() ?: return null
        if (m.groupValues[2].isNotEmpty()) return objs[v]?.value?.trim()?.toIntOrNull()
        return v
    }

    private fun rawStream(o: Obj): ByteArray {
        if (o.sStart < 0 || o.sLen <= 0) return ByteArray(0)
        val end = minOf(b.size, o.sStart + o.sLen)
        return b.copyOfRange(o.sStart, end)
    }

    /** Decodifica los filtros "comunes" (Flate). Devuelve null si hay un filtro que no conocemos. */
    private fun decoded(o: Obj): ByteArray? {
        val top = topLevel(o.dict ?: return null)
        var data = rawStream(o)
        for (f in filters(top)) {
            data = when (f) {
                "FlateDecode", "Fl" -> inflate(data)
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "ASCII85Decode", "A85" -> ascii85(data)
                "RunLengthDecode", "RL" -> runLength(data)
                "LZWDecode", "LZW" -> lzw(data)
                else -> return null
            }
        }
        val parms = nested(o.dict, "DecodeParms")
        if (parms != null) {
            val pred = Regex("/Predictor\\s+(\\d+)").find(parms)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            if (pred >= 10) {
                val colors = Regex("/Colors\\s+(\\d+)").find(parms)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val bpc = Regex("/BitsPerComponent\\s+(\\d+)").find(parms)?.groupValues?.get(1)?.toIntOrNull() ?: 8
                val cols = Regex("/Columns\\s+(\\d+)").find(parms)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                data = unpredict(data, pred, colors, bpc, cols)
            }
        }
        return data
    }

    val encrypted: Boolean by lazy {
        val tail = String(b, maxOf(0, b.size - 4096), minOf(4096, b.size), Charsets.ISO_8859_1)
        tail.contains("/Encrypt") || objs.values.any { it.dict != null && it.dict.contains("/XRef") && it.dict.contains("/Encrypt") } ||
            Regex("trailer\\s*<<[^>]*?/Encrypt").containsMatchIn(String(b, maxOf(0, b.size - 200_000), minOf(200_000, b.size), Charsets.ISO_8859_1))
    }

    /** Componentes de color de un espacio de color (y paleta si es indexado). */
    private class Cs(val comps: Int, val palette: ByteArray?, val palComps: Int, val sep: Boolean)

    private fun colorSpace(top: String): Cs? {
        var text: String? = null
        Regex("/ColorSpace\\s*(/[A-Za-z0-9]+|\\[[^\\]]*\\]|(\\d+)\\s+\\d+\\s+R)").find(top)?.let { m ->
            text = if (m.groupValues[2].isNotEmpty()) {
                val o = objs[m.groupValues[2].toInt()]
                o?.value ?: o?.dict
            } else m.groupValues[1]
        }
        val t = text ?: return null
        return parseCs(t, 0)
    }

    private fun parseCs(t: String, depth: Int): Cs? {
        val s = t.trim()
        fun baseComps(x: String): Int = when {
            x.contains("DeviceGray") || x.contains("CalGray") || x == "/G" -> 1
            x.contains("DeviceCMYK") || x == "/CMYK" -> 4
            x.contains("DeviceRGB") || x.contains("CalRGB") || x == "/RGB" || x.contains("Lab") -> 3
            else -> {
                Regex("/ICCBased\\s+(\\d+)\\s+\\d+\\s+R").find(x)?.let { m ->
                    objs[m.groupValues[1].toInt()]?.dict?.let { d -> Regex("/N\\s+(\\d+)").find(d)?.groupValues?.get(1)?.toIntOrNull() }
                } ?: 0
            }
        }
        if (s.contains("/Indexed")) {
            val inner = s.substringAfter("/Indexed")
            val m = Regex("^\\s*(/[A-Za-z0-9]+|\\[[^\\]]*\\]|(\\d+)\\s+\\d+\\s+R)\\s+(\\d+)").find(inner)
            var bc = 3
            var rest = inner
            if (m != null) {
                rest = inner.substring(m.range.last + 1)
                bc = if (m.groupValues[2].isNotEmpty()) {
                    val o = objs[m.groupValues[2].toInt()]
                    (o?.value ?: o?.dict)?.let { if (depth < 2) parseCs(it, depth + 1)?.comps else null } ?: 3
                } else baseComps(m.groupValues[1]).let { if (it == 0) 3 else it }
            }
            var pal: ByteArray? = null
            val hex = Regex("^\\s*<([0-9A-Fa-f\\s]+)>").find(rest)
            val ref = Regex("^\\s*(\\d+)\\s+\\d+\\s+R").find(rest)
            if (hex != null) {
                val h = hex.groupValues[1].filter { !it.isWhitespace() }
                pal = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } else if (ref != null) {
                objs[ref.groupValues[1].toInt()]?.let { o -> if (o.sStart >= 0) pal = decoded(o) }
            } else if (rest.trimStart().startsWith("(")) {
                pal = string("/X " + rest.trimStart(), "X")?.toByteArray(Charsets.ISO_8859_1)
            }
            return Cs(1, pal, bc, false)
        }
        if (s.contains("/Separation") || s.contains("/DeviceN")) return Cs(1, null, 0, true)
        val c = baseComps(s)
        return if (c > 0) Cs(c, null, 0, false) else null
    }

    fun extract(): Result {
        val enc = ArrayList<Encoded>()
        val raws = ArrayList<Raw>()
        val files = ArrayList<Attached>()
        var unsupported = 0

        // nombres de adjuntos
        val efName = HashMap<Int, String>()
        for (o in objs.values) {
            val d = o.dict ?: continue
            if (!d.contains("/EF")) continue
            val ef = nested(d, "EF") ?: continue
            val ref = Regex("/(?:UF|F)\\s+(\\d+)\\s+\\d+\\s+R").find(ef)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val nm = string(topLevel(d), "UF") ?: string(topLevel(d), "F") ?: continue
            efName[ref] = nm.substringAfterLast('/').substringAfterLast('\\')
        }
        var fileN = 0
        for (num in order) {
            val o = objs[num] ?: continue
            val d = o.dict ?: continue
            if (o.sStart < 0) continue
            val top = topLevel(d)
            if (!Regex("/Type\\s*/EmbeddedFile").containsMatchIn(top)) continue
            val data = decoded(o) ?: continue
            fileN++
            files += Attached(efName[num] ?: "adjunto_$fileN", data)
        }

        val masks = HashSet<Int>()
        for (o in objs.values) {
            val d = o.dict ?: continue
            Regex("/(?:SMask|Mask)\\s+(\\d+)\\s+\\d+\\s+R").findAll(d).forEach { masks += it.groupValues[1].toInt() }
        }
        val seen = HashSet<String>()
        val haveOriginals = files.isNotEmpty()
        for (num in order) {
            val o = objs[num] ?: continue
            val d = o.dict ?: continue
            if (o.sStart < 0 || num in masks) continue
            val top = topLevel(d)
            if (!Regex("/Subtype\\s*/Image").containsMatchIn(top)) continue
            if (haveOriginals && top.contains("/MLOrig true")) continue
            val w = intOf(top, "Width") ?: continue
            val h = intOf(top, "Height") ?: continue
            if (w < 40 || h < 40) continue
            val raw = rawStream(o)
            val key = "${raw.size}_${raw.contentHashCode()}"
            if (!seen.add(key)) continue
            val name = string(top, "MLName")
            val fl = filters(top)
            val last = fl.lastOrNull()
            if (last == "DCTDecode" || last == "DCT" || last == "JPXDecode") {
                var data = raw
                var ok = true
                for (f in fl.dropLast(1)) {
                    if (f == "FlateDecode" || f == "Fl") data = inflate(data) else ok = false
                }
                if (!ok) { unsupported++; continue }
                enc += Encoded(name, data, if (last == "JPXDecode") "jp2" else "jpg")
                continue
            }
            val data = decoded(o)
            if (data == null) { unsupported++; continue }
            val isMask = Regex("/ImageMask\\s+true").containsMatchIn(top)
            var bpc = if (isMask) 1 else intOf(top, "BitsPerComponent") ?: 8
            val cs = if (isMask) Cs(1, null, 0, false) else colorSpace(top)
            var comps = cs?.comps ?: 0
            if (comps == 0) {
                val per = data.size.toLong() * 8 / (w.toLong() * h * maxOf(1, bpc))
                comps = when { per >= 4 -> 4; per >= 3 -> 3; else -> 1 }
            }
            if (bpc !in setOf(1, 2, 4, 8, 16)) { unsupported++; continue }
            val decodeArr = Regex("/Decode\\s*\\[\\s*1(\\.0)?\\s+0").containsMatchIn(top)
            val invert = decodeArr || (cs?.sep == true)
            raws += Raw(name, w, h, bpc, comps, data, cs?.palette, cs?.palComps ?: 0, invert)
        }
        return Result(enc, raws, files, unsupported, encrypted)
    }
}
