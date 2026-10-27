package com.gustavo.multilector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

enum class ScanFilter(val label: String) {
    ENHANCED("✨ Mejorado"),
    DOCUMENT("📄 Blanco y negro"),
    GRAY("🌫 Gris"),
    ORIGINAL("🖼 Original")
}

/** Escáner: detectar la hoja, enderezarla y limpiarla. Todo hecho a mano, sin librerías. */
object Scan {

    /** Esquinas por defecto (casi toda la foto): arriba-izq, arriba-der, abajo-der, abajo-izq. Normalizadas 0..1 */
    fun fullQuad(inset: Float = 0.02f) = floatArrayOf(inset, inset, 1 - inset, inset, 1 - inset, 1 - inset, inset, 1 - inset)

    /** Busca la hoja (papel claro sobre fondo más oscuro). */
    fun detect(b: Bitmap): FloatArray {
        try {
            val s = 360f / maxOf(b.width, b.height)
            val w = maxOf(8, (b.width * s).toInt())
            val h = maxOf(8, (b.height * s).toInt())
            val small = Bitmap.createScaledBitmap(b, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== b) small.recycle()
            var lum = IntArray(w * h) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
            repeat(2) { lum = boxBlur(lum, w, h, 2) }
            // umbral de Otsu
            val hist = IntArray(256)
            for (v in lum) hist[v.coerceIn(0, 255)]++
            val total = lum.size
            var sum = 0.0
            for (i in 0 until 256) sum += i.toDouble() * hist[i]
            var sumB = 0.0; var wB = 0; var best = 0.0; var thr = 128
            for (t in 0 until 256) {
                wB += hist[t]; if (wB == 0) continue
                val wF = total - wB; if (wF == 0) break
                sumB += t.toDouble() * hist[t]
                val mB = sumB / wB; val mF = (sum - sumB) / wF
                val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
                if (between > best) { best = between; thr = t }
            }
            // la mancha clara más grande
            val label = IntArray(total)
            var bestLabel = 0; var bestArea = 0; var cur = 0
            val queue = IntArray(total)
            for (start in 0 until total) {
                if (lum[start] <= thr || label[start] != 0) continue
                cur++
                var head = 0; var tail = 0
                queue[tail++] = start; label[start] = cur
                var area = 0
                while (head < tail) {
                    val i = queue[head++]; area++
                    val x = i % w; val y = i / w
                    if (x > 0) { val j = i - 1; if (label[j] == 0 && lum[j] > thr) { label[j] = cur; queue[tail++] = j } }
                    if (x < w - 1) { val j = i + 1; if (label[j] == 0 && lum[j] > thr) { label[j] = cur; queue[tail++] = j } }
                    if (y > 0) { val j = i - w; if (label[j] == 0 && lum[j] > thr) { label[j] = cur; queue[tail++] = j } }
                    if (y < h - 1) { val j = i + w; if (label[j] == 0 && lum[j] > thr) { label[j] = cur; queue[tail++] = j } }
                }
                if (area > bestArea) { bestArea = area; bestLabel = cur }
            }
            if (bestArea < total * 0.10) return fullQuad()
            var tl = 0; var tr = 0; var br = 0; var bl = 0
            var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
            for (i in 0 until total) {
                if (label[i] != bestLabel) continue
                val x = i % w; val y = i / w
                val su = x + y; val di = x - y
                if (su < minS) { minS = su; tl = i }
                if (su > maxS) { maxS = su; br = i }
                if (di > maxD) { maxD = di; tr = i }
                if (di < minD) { minD = di; bl = i }
            }
            val q = FloatArray(8)
            fun set(k: Int, i: Int) { q[k * 2] = (i % w + 0.5f) / w; q[k * 2 + 1] = (i / w + 0.5f) / h }
            set(0, tl); set(1, tr); set(2, br); set(3, bl)
            if (quadArea(q) < 0.10f) return fullQuad()
            return q
        } catch (_: Throwable) {
            return fullQuad()
        }
    }

    fun quadArea(q: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return Math.abs(a) / 2
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            var acc = 0; var n = 0
            for (x in -r..r) { val xx = x.coerceIn(0, w - 1); acc += src[y * w + xx]; n++ }
            for (x in 0 until w) {
                tmp[y * w + x] = acc / n
                val outX = (x - r).coerceIn(0, w - 1); val inX = (x + r + 1).coerceIn(0, w - 1)
                acc += src[y * w + inX] - src[y * w + outX]
            }
        }
        for (x in 0 until w) {
            var acc = 0; var n = 0
            for (y in -r..r) { val yy = y.coerceIn(0, h - 1); acc += tmp[yy * w + x]; n++ }
            for (y in 0 until h) {
                out[y * w + x] = acc / n
                val outY = (y - r).coerceIn(0, h - 1); val inY = (y + r + 1).coerceIn(0, h - 1)
                acc += tmp[inY * w + x] - tmp[outY * w + x]
            }
        }
        return out
    }

    /** Endereza la hoja: lleva las 4 esquinas a un rectángulo. */
    fun warp(src: Bitmap, qn: FloatArray, maxSide: Float = 3300f): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val p = FloatArray(8) { if (it % 2 == 0) qn[it] * w else qn[it] * h }
        fun d(a: Int, b: Int) = Math.hypot((p[a * 2] - p[b * 2]).toDouble(), (p[a * 2 + 1] - p[b * 2 + 1]).toDouble()).toFloat()
        var ow = maxOf(d(0, 1), d(3, 2))
        var oh = maxOf(d(0, 3), d(1, 2))
        val sc = minOf(1f, maxSide / maxOf(ow, oh))
        ow = maxOf(16f, ow * sc); oh = maxOf(16f, oh * sc)
        val out = Bitmap.createBitmap(ow.toInt(), oh.toInt(), Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val m = Matrix()
        m.setPolyToPoly(p, 0, floatArrayOf(0f, 0f, ow, 0f, ow, oh, 0f, oh), 0, 4)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return b
        val m = Matrix(); m.setRotate(deg.toFloat())
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    /** Limpia la hoja: blanquea el fondo, saca sombras y realza el texto. */
    fun filter(b: Bitmap, f: ScanFilter): Bitmap {
        if (f == ScanFilter.ORIGINAL) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
        if (f == ScanFilter.GRAY) {
            for (i in px.indices) { val v = lum[i]; px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        } else {
            // fondo (papel) estimado por bloques: lo más claro de cada zona, suavizado
            val bs = maxOf(8, maxOf(w, h) / 90)
            val bw = (w + bs - 1) / bs; val bh = (h + bs - 1) / bs
            var bg = IntArray(bw * bh)
            for (by in 0 until bh) for (bx in 0 until bw) {
                var mx = 0
                var y = by * bs
                while (y < minOf(h, (by + 1) * bs)) {
                    var x = bx * bs
                    while (x < minOf(w, (bx + 1) * bs)) { val v = lum[y * w + x]; if (v > mx) mx = v; x += 2 }
                    y += 2
                }
                bg[by * bw + bx] = mx
            }
            // agrandar lo claro (tapa letras grandes) y suavizar
            repeat(2) {
                val n = IntArray(bg.size)
                for (y in 0 until bh) for (x in 0 until bw) {
                    var mx = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, bw - 1); val yy = (y + dy).coerceIn(0, bh - 1)
                        mx = maxOf(mx, bg[yy * bw + xx])
                    }
                    n[y * bw + x] = mx
                }
                bg = n
            }
            bg = boxBlur(bg, bw, bh, 2)
            for (y in 0 until h) {
                val fy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, (bh - 1).toFloat())
                val y0 = fy.toInt(); val y1 = minOf(bh - 1, y0 + 1); val ty = fy - y0
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, (bw - 1).toFloat())
                    val x0 = fx.toInt(); val x1 = minOf(bw - 1, x0 + 1); val tx = fx - x0
                    val top = bg[y0 * bw + x0] * (1 - tx) + bg[y0 * bw + x1] * tx
                    val bot = bg[y1 * bw + x0] * (1 - tx) + bg[y1 * bw + x1] * tx
                    val back = maxOf(40f, top * (1 - ty) + bot * ty)
                    val i = y * w + x
                    val k = 255f / back
                    if (f == ScanFilter.DOCUMENT) {
                        val n = lum[i] * k
                        var v = ((n - 95f) * 255f / (232f - 95f)).toInt().coerceIn(0, 255)
                        if (v > 215) v = 255
                        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    } else {
                        val p = px[i]
                        fun c(v: Int): Int {
                            val n = v * k
                            val r = ((n - 25f) * 255f / (238f - 25f)).toInt().coerceIn(0, 255)
                            return if (r > 240) 255 else r
                        }
                        px[i] = (0xFF shl 24) or (c((p shr 16) and 0xFF) shl 16) or (c((p shr 8) and 0xFF) shl 8) or c(p and 0xFF)
                    }
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (out !== b) b.recycle()
        return out
    }

    /** Proceso completo de una hoja. */
    fun process(src: Bitmap, quad: FloatArray, rot: Int, f: ScanFilter, maxSide: Float = 3300f): Bitmap {
        val warped = warp(src, quad, maxSide)
        val rotated = rotate(warped, rot)
        return filter(rotated, f)
    }
}
