package com.gustavo.multilector

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

enum class PageMode { A4, PHOTO, EXACT }

/**
 * Escritor de PDF propio, pensado para fotos.
 * - Los JPG se guardan byte a byte (sin recomprimir): al sacarlos del PDF salen idénticos.
 * - Las demás fotos (PNG, WEBP, HEIC…) se guardan sin pérdida, y además se adjunta el archivo original.
 * No usa nada de Android, así se puede probar en la computadora.
 */
class PdfImageWriter(file: File, var mode: PageMode = PageMode.A4) : Closeable {

    class JInfo(val w: Int, val h: Int, val comps: Int, val adobe: Boolean)

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private class NoClose(val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun close() {}
        override fun flush() = o.flush()
    }

    private val out = Counting(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
    private val offsets = TreeMap<Int, Long>()
    private var nextId = 3 // 1 = catálogo, 2 = árbol de páginas
    private val pages = ArrayList<Int>()
    private val attachments = ArrayList<Pair<String, Int>>()
    val pageCount: Int get() = pages.size

    init {
        raw("%PDF-1.7\n")
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
    }

    private fun raw(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
    private fun alloc() = nextId++
    private fun begin(id: Int) { offsets[id] = out.count; raw("$id 0 obj\n") }
    private fun obj(id: Int, body: String) { begin(id); raw(body); raw("\nendobj\n") }

    private fun stream(dict: String, body: (OutputStream) -> Unit): Int {
        val id = alloc()
        val lenId = alloc()
        begin(id)
        raw("<<$dict /Length $lenId 0 R>>\nstream\n")
        val start = out.count
        body(NoClose(out))
        val len = out.count - start
        raw("\nendstream\nendobj\n")
        obj(lenId, len.toString())
        return id
    }

    /** Agrega una página con un JPG tal cual. orientation = valor EXIF (1..8). */
    fun addJpeg(data: ByteArray, name: String?, orientation: Int = 1, exactW: Double = 0.0, exactH: Double = 0.0): Boolean {
        val info = jpegInfo(data) ?: return false
        val cs = when (info.comps) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }
        val decode = if (info.comps == 4 && info.adobe) " /Decode [1 0 1 0 1 0 1 0]" else ""
        val nm = if (name != null) " /MLName ${hexStr(name)}" else ""
        val img = stream("/Type /XObject /Subtype /Image /Width ${info.w} /Height ${info.h} /ColorSpace $cs /BitsPerComponent 8 /Filter /DCTDecode$decode$nm") {
            it.write(data)
        }
        val o = if (orientation in 1..8) orientation else 1
        val swap = o >= 5
        val dw = (if (swap) info.h else info.w).toDouble()
        val dh = (if (swap) info.w else info.h).toDouble()
        page(img, dw, dh, o, exactW, exactH)
        return true
    }

    /**
     * Agrega una foto sin pérdida a partir de sus píxeles (ARGB, fila por fila).
     * [row] llena el IntArray con la fila y. [original] = true si además se adjunta el archivo original.
     */
    fun addPixels(w: Int, h: Int, mayHaveAlpha: Boolean, name: String?, original: Boolean, row: (y: Int, buf: IntArray) -> Unit) {
        val nm = if (name != null) " /MLName ${hexStr(name)}" else ""
        val smaskId = if (mayHaveAlpha) alloc() else -1
        val sm = if (smaskId > 0) " /SMask $smaskId 0 R" else ""
        val alphaBuf = ByteArrayOutputStream()
        val aDef = Deflater(6)
        val aos = DeflaterOutputStream(alphaBuf, aDef, 1 shl 16)
        val px = IntArray(w)
        val rgb = ByteArray(w * 3)
        val al = ByteArray(w)
        val img = stream("/Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode$sm$nm${if (original) " /MLOrig true" else ""}") { os ->
            val def = Deflater(6)
            val dos = DeflaterOutputStream(os, def, 1 shl 16)
            for (y in 0 until h) {
                row(y, px)
                var j = 0
                for (x in 0 until w) {
                    val p = px[x]
                    rgb[j++] = (p shr 16).toByte(); rgb[j++] = (p shr 8).toByte(); rgb[j++] = p.toByte()
                    if (smaskId > 0) al[x] = (p ushr 24).toByte()
                }
                dos.write(rgb)
                if (smaskId > 0) aos.write(al)
            }
            dos.finish(); def.end()
        }
        if (smaskId > 0) {
            aos.finish(); aDef.end()
            val a = alphaBuf.toByteArray()
            begin(smaskId)
            raw("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length ${a.size} >>\nstream\n")
            out.write(a)
            raw("\nendstream\nendobj\n")
        } else {
            aDef.end()
        }
        page(img, w.toDouble(), h.toDouble(), 1, 0.0, 0.0)
    }

    /** Adjunta un archivo dentro del PDF (por ejemplo la foto original PNG/HEIC). */
    fun attach(name: String, mime: String, data: ByteArray) {
        val sub = mime.replace("/", "#2F").replace(Regex("[^A-Za-z0-9#.+-]"), "")
        val ef = stream("/Type /EmbeddedFile /Subtype /$sub /Params << /Size ${data.size} >>") { it.write(data) }
        val fs = alloc()
        obj(fs, "<< /Type /Filespec /F ${lit(name)} /UF ${hexStr(name)} /EF << /F $ef 0 R /UF $ef 0 R >> /Desc (Foto original) >>")
        attachments += name to fs
    }

    private fun page(img: Int, dw: Double, dh: Double, o: Int, exW: Double, exH: Double) {
        val pw: Double; val ph: Double; val x: Double; val y: Double; val w: Double; val h: Double
        when {
            exW > 0 && exH > 0 -> { pw = exW; ph = exH; x = 0.0; y = 0.0; w = exW; h = exH }
            mode == PageMode.PHOTO -> {
                val s = 842.0 / maxOf(dw, dh)
                pw = dw * s; ph = dh * s; x = 0.0; y = 0.0; w = pw; h = ph
            }
            else -> {
                val land = dw > dh
                pw = if (land) 842.0 else 595.0
                ph = if (land) 595.0 else 842.0
                val m = 28.0
                val s = minOf((pw - 2 * m) / dw, (ph - 2 * m) / dh)
                w = dw * s; h = dh * s; x = (pw - w) / 2; y = (ph - h) / 2
            }
        }
        val t = ORIENT[o - 1]
        val a = w * t[0]; val c = w * t[1]; val e = x + w * t[2]
        val b = h * t[3]; val d = h * t[4]; val f = y + h * t[5]
        val content = "q ${n(a)} ${n(b)} ${n(c)} ${n(d)} ${n(e)} ${n(f)} cm /Im0 Do Q"
        val cId = stream("") { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
        val pid = alloc()
        obj(pid, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pw)} ${n(ph)}] /Resources << /XObject << /Im0 $img 0 R >> /ProcSet [/PDF /ImageB /ImageC] >> /Contents $cId 0 R >>")
        pages += pid
    }

    override fun close() {
        obj(2, "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        val names = if (attachments.isEmpty()) "" else
            " /Names << /EmbeddedFiles << /Names [${attachments.mapIndexed { i, p -> "(${String.format(Locale.ROOT, "%04d", i)}) ${p.second} 0 R" }.joinToString(" ")}] >> >>"
        obj(1, "<< /Type /Catalog /Pages 2 0 R$names >>")
        val info = alloc()
        obj(info, "<< /Producer (MultiLector) /Creator (MultiLector) >>")
        val xref = out.count
        val size = nextId
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (i in 1 until size) sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i] ?: 0L))
        sb.append("trailer\n<< /Size $size /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        raw(sb.toString())
        out.close()
    }

    companion object {
        // Para cada orientación EXIF: p = a·u + b·v + c ; q = d·u + e·v + f  (coordenadas normalizadas)
        private val ORIENT = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 1.0, 0.0, 1.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 1.0),
            doubleArrayOf(0.0, -1.0, 1.0, -1.0, 0.0, 1.0),
            doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 1.0),
            doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(0.0, -1.0, 1.0, 1.0, 0.0, 0.0),
        )

        fun n(d: Double): String {
            val s = String.format(Locale.ROOT, "%.4f", d).trimEnd('0').trimEnd('.')
            return if (s == "-0" || s.isEmpty()) "0" else s
        }

        fun hexStr(s: String): String {
            val sb = StringBuilder("<FEFF")
            for (b in s.toByteArray(Charsets.UTF_16BE)) sb.append(String.format(Locale.ROOT, "%02X", b.toInt() and 0xFF))
            return sb.append('>').toString()
        }

        fun lit(s: String): String {
            val sb = StringBuilder("(")
            for (ch in s) {
                when {
                    ch == '(' || ch == ')' || ch == '\\' -> sb.append('\\').append(ch)
                    ch.code in 32..126 -> sb.append(ch)
                    else -> sb.append('_')
                }
            }
            return sb.append(')').toString()
        }

        fun jpegInfo(d: ByteArray): JInfo? {
            if (d.size < 4 || d[0] != 0xFF.toByte() || d[1] != 0xD8.toByte()) return null
            fun u8(i: Int) = d[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            var i = 2
            var adobe = false
            while (i + 3 < d.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val m = u8(i + 1)
                if (m == 0xFF) { i++; continue }
                if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
                if (m == 0xD9 || m == 0xDA) break
                val len = u16(i + 2)
                if (m == 0xEE && len >= 7 && i + 9 < d.size && String(d, i + 4, 5, Charsets.ISO_8859_1) == "Adobe") adobe = true
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    if (i + 9 >= d.size) return null
                    val h = u16(i + 5); val w = u16(i + 7); val c = u8(i + 9)
                    return if (w > 0 && h > 0 && c in 1..4) JInfo(w, h, c, adobe) else null
                }
                i += 2 + len
            }
            return null
        }
    }
}
