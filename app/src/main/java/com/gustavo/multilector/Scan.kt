package com.gustavo.multilector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject

enum class ScanFilter(val label: String, val desc: String) {
    ENHANCED("✨ Mejorado", "Fondo blanco, sin sombras, colores reales"),
    MAGIC("🪄 Color intenso", "Como Mejorado pero con colores más vivos"),
    DOCUMENT("📄 Blanco y negro", "Texto bien negro. Ideal facturas e impresora B/N"),
    INK("🖨 Ahorro de tinta", "Blanco y negro más liviano, gasta menos tinta"),
    GRAY("🌫 Gris", "Sin color, suave"),
    PHOTO("📷 Foto", "Para fotos, DNI o carnets: solo mejora luz y color"),
    ORIGINAL("🖼 Original", "Como salió la foto (solo recortada)")
}

/** Todos los retoques de una hoja. Se guardan para poder rehacerlos cuando quieras. */
data class Edit(
    val quad: FloatArray,
    val rot: Int = 0,          // 0, 90, 180, 270
    val angle: Float = 0f,     // enderezado fino, -15..15 grados
    val mirror: Boolean = false,
    val filter: ScanFilter = ScanFilter.ENHANCED,
    val bright: Int = 0,       // -100..100
    val contrast: Int = 0,     // -100..100
    val sat: Int = 0,          // -100..100
    val sharp: Int = 30,       // 0..100
    val shadows: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("q", JSONArray(quad.map { it.toDouble() })).put("rot", rot).put("ang", angle.toDouble())
        .put("mir", mirror).put("f", filter.name).put("b", bright).put("c", contrast).put("s", sat).put("sh", sharp).put("so", shadows)

    override fun equals(other: Any?) = other is Edit && toJson().toString() == other.toJson().toString()
    override fun hashCode() = toJson().toString().hashCode()

    companion object {
        fun fromJson(o: JSONObject): Edit {
            val q = o.getJSONArray("q")
            return Edit(FloatArray(8) { q.getDouble(it).toFloat() }, o.optInt("rot"), o.optDouble("ang", 0.0).toFloat(), o.optBoolean("mir"),
                try { ScanFilter.valueOf(o.optString("f", "ENHANCED")) } catch (_: Exception) { ScanFilter.ENHANCED },
                o.optInt("b"), o.optInt("c"), o.optInt("s"), o.optInt("sh", 30), o.optBoolean("so", true))
        }
    }
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
            val hq = houghQuad(lum, w, h)
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
            if (bestArea < total * 0.10 || bestArea > total * 0.93) return hq ?: fullQuad()
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
            if (quadArea(q) < 0.10f) return hq ?: fullQuad()
            // si los bordes rectos encontraron una hoja parecida pero más prolija, usamos esa
            if (hq != null && Math.abs(quadArea(hq) - quadArea(q)) < 0.12f) return hq
            return q
        } catch (_: Throwable) {
            return fullQuad()
        }
    }

    /**
     * Busca los 4 bordes rectos de la hoja (transformada de Hough). Sirve cuando la hoja
     * está sobre una mesa clara y no se distingue por color.
     */
    fun houghQuad(lum: IntArray, w: Int, h: Int): FloatArray? {
        try {
            // bordes (Sobel)
            val mag = IntArray(w * h)
            var maxM = 1
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = (lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - 1] + lum[i + w - 1])
                val gy = (lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - w] + lum[i - w + 1])
                val m = Math.abs(gx) + Math.abs(gy)
                mag[i] = m
                if (m > maxM) maxM = m
            }
            val hist = IntArray(256)
            for (m in mag) hist[m * 255 / maxM]++
            var acc = 0; var thrBin = 255
            for (k in 255 downTo 0) { acc += hist[k]; if (acc > mag.size * 0.08) { thrBin = k; break } }
            val thr = maxOf(12, thrBin * maxM / 255)
            val diag = Math.ceil(Math.hypot(w.toDouble(), h.toDouble())).toInt()
            val nT = 180
            val cosT = DoubleArray(nT) { Math.cos(Math.toRadians(it.toDouble())) }
            val sinT = DoubleArray(nT) { Math.sin(Math.toRadians(it.toDouble())) }
            val votes = IntArray(nT * (2 * diag + 1))
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                if (mag[y * w + x] < thr) continue
                for (t in 0 until nT) {
                    // solo ángulos casi horizontales o casi verticales (la hoja puede estar torcida hasta ~25°)
                    if (t in 26..64 || t in 116..154) continue
                    val r = Math.round(x * cosT[t] + y * sinT[t]).toInt() + diag
                    votes[t * (2 * diag + 1) + r]++
                }
            }
            class L(val t: Int, val r: Int, val v: Int)
            val lines = ArrayList<L>()
            val stride = 2 * diag + 1
            for (t in 0 until nT) for (r in 1 until stride - 1) {
                val v = votes[t * stride + r]
                if (v < minOf(w, h) / 5) continue
                // máximo local
                var ok = true
                loop@ for (dt in -2..2) for (dr in -3..3) {
                    if (dt == 0 && dr == 0) continue
                    val tt = (t + dt + nT) % nT; val rr = r + dr
                    if (rr < 0 || rr >= stride) continue
                    if (votes[tt * stride + rr] > v) { ok = false; break@loop }
                }
                if (ok) lines.add(L(t, r - diag, v))
            }
            fun posAt(l: L, horizontal: Boolean): Double {
                val th = Math.toRadians(l.t.toDouble())
                return if (horizontal) (l.r - (w / 2.0) * Math.cos(th)) / Math.sin(th) // y en el centro
                else (l.r - (h / 2.0) * Math.sin(th)) / Math.cos(th)                    // x en el centro
            }
            val hor = lines.filter { it.t in 65..115 }
            val ver = lines.filter { it.t <= 25 || it.t >= 155 }
            fun pick(c: List<L>, horizontal: Boolean, first: Boolean): L? {
                val size = if (horizontal) h else w
                val side = c.filter { val p = posAt(it, horizontal); if (first) p < size * 0.5 && p > -size * 0.05 else p > size * 0.5 && p < size * 1.05 }
                val best = side.maxOfOrNull { it.v } ?: return null
                val strong = side.filter { it.v >= best * 0.25 && it.v >= (if (horizontal) w else h) / 4 }
                // la línea fuerte más de afuera es el borde del papel (adentro están los renglones)
                return if (first) strong.minByOrNull { posAt(it, horizontal) } else strong.maxByOrNull { posAt(it, horizontal) }
            }
            val top = pick(hor, true, true) ?: return null
            val bot = pick(hor, true, false) ?: return null
            val left = pick(ver, false, true) ?: return null
            val right = pick(ver, false, false) ?: return null
            fun inter(a: L, b: L): DoubleArray? {
                val t1 = Math.toRadians(a.t.toDouble()); val t2 = Math.toRadians(b.t.toDouble())
                val det = Math.cos(t1) * Math.sin(t2) - Math.sin(t1) * Math.cos(t2)
                if (Math.abs(det) < 1e-6) return null
                val x = (a.r * Math.sin(t2) - b.r * Math.sin(t1)) / det
                val y = (b.r * Math.cos(t1) - a.r * Math.cos(t2)) / det
                return doubleArrayOf(x, y)
            }
            val pts = listOf(inter(top, left), inter(top, right), inter(bot, right), inter(bot, left))
            if (pts.any { it == null }) return null
            val q = FloatArray(8)
            pts.forEachIndexed { k, p ->
                q[k * 2] = (p!![0] / w).toFloat().coerceIn(0f, 1f)
                q[k * 2 + 1] = (p[1] / h).toFloat().coerceIn(0f, 1f)
            }
            val a = quadArea(q)
            return if (a in 0.30f..0.97f) q else null
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Mide cuánto están torcidos los renglones del texto (en grados). 0 si no se puede saber.
     * Para enderezar hay que girar al revés: straighten(-angulo).
     */
    fun textSkew(b: Bitmap): Float {
        try {
            val s = 900f / maxOf(b.width, b.height)
            val w = maxOf(8, (b.width * minOf(1f, s)).toInt()); val h = maxOf(8, (b.height * minOf(1f, s)).toInt())
            val small = Bitmap.createScaledBitmap(b, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== b) small.recycle()
            val lum = IntArray(w * h) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
            val bg = background(lum, w, h)
            val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
            // puntos oscuros (tinta), dejando un margen
            val mx = w / 20; val my = h / 20
            for (y in my until h - my step 1) for (x in mx until w - mx step 1) {
                if (lum[y * w + x] < bg(x, y) * 0.62f) { xs.add(x); ys.add(y) }
            }
            if (xs.size < 300) return 0f
            val stepPick = maxOf(1, xs.size / 60000)
            fun score(deg: Double): Double {
                val t = Math.tan(Math.toRadians(deg))
                val bins = IntArray(h * 2 + 2)
                var i = 0
                while (i < xs.size) {
                    val r = Math.round(ys[i] - xs[i] * t).toInt() + h
                    if (r in bins.indices) bins[r]++
                    i += stepPick
                }
                var sc = 0.0
                for (v in bins) sc += v.toDouble() * v
                return sc
            }
            var best = 0.0; var bestS = score(0.0)
            val base = bestS
            var a = -12.0
            while (a <= 12.0) { val sc = score(a); if (sc > bestS) { bestS = sc; best = a }; a += 0.5 }
            var f = best - 0.5
            while (f <= best + 0.5) { val sc = score(f); if (sc > bestS) { bestS = sc; best = f }; f += 0.1 }
            if (Math.abs(best) < 0.25 || bestS < base * 1.08) return 0f
            return best.toFloat()
        } catch (_: Throwable) {
            return 0f
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

    /** Enderezado fino (pocos grados) y espejo, sin dejar bordes vacíos. */
    fun straighten(b: Bitmap, angle: Float, mirror: Boolean): Bitmap {
        if (Math.abs(angle) < 0.05f && !mirror) return b
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val rad = Math.toRadians(Math.abs(angle).toDouble())
        val cover = (Math.cos(rad) + Math.sin(rad) * maxOf(w / h, h / w)).toFloat()
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val m = Matrix()
        m.postTranslate(-w / 2, -h / 2)
        if (mirror) m.postScale(-1f, 1f)
        m.postRotate(angle)
        m.postScale(cover, cover)
        m.postTranslate(w / 2, h / 2)
        Canvas(out).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        b.recycle()
        return out
    }

    /** Fondo del papel estimado por zonas (lo más claro de cada zona, suavizado). Sirve para sacar sombras. */
    private fun background(lum: IntArray, w: Int, h: Int): (Int, Int) -> Float {
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
        val fin = bg
        return { x, y ->
            val fy = ((y + 0.5f) / bs - 0.5f).coerceIn(0f, (bh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(bh - 1, y0 + 1); val ty = fy - y0
            val fx = ((x + 0.5f) / bs - 0.5f).coerceIn(0f, (bw - 1).toFloat())
            val x0 = fx.toInt(); val x1 = minOf(bw - 1, x0 + 1); val tx = fx - x0
            val top = fin[y0 * bw + x0] * (1 - tx) + fin[y0 * bw + x1] * tx
            val bot = fin[y1 * bw + x0] * (1 - tx) + fin[y1 * bw + x1] * tx
            maxOf(40f, top * (1 - ty) + bot * ty)
        }
    }

    /** Niveles automáticos: estira entre el 0,5% más oscuro y el 0,5% más claro de cada color. */
    private fun autoLevels(px: IntArray) {
        val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
        var i = 0
        while (i < px.size) { val p = px[i]; hr[(p shr 16) and 0xFF]++; hg[(p shr 8) and 0xFF]++; hb[p and 0xFF]++; i += 3 }
        val n = (px.size + 2) / 3
        fun lo(h: IntArray): Int { var a = 0; for (k in 0..255) { a += h[k]; if (a > n * 0.005) return k }; return 0 }
        fun hi(h: IntArray): Int { var a = 0; for (k in 255 downTo 0) { a += h[k]; if (a > n * 0.005) return k }; return 255 }
        val lr = lo(hr); val hrr = maxOf(lr + 30, hi(hr)); val lg = lo(hg); val hgg = maxOf(lg + 30, hi(hg)); val lb = lo(hb); val hbb = maxOf(lb + 30, hi(hb))
        val mr = IntArray(256) { ((it - lr) * 255 / (hrr - lr)).coerceIn(0, 255) }
        val mg = IntArray(256) { ((it - lg) * 255 / (hgg - lg)).coerceIn(0, 255) }
        val mb = IntArray(256) { ((it - lb) * 255 / (hbb - lb)).coerceIn(0, 255) }
        for (k in px.indices) { val p = px[k]; px[k] = (0xFF shl 24) or (mr[(p shr 16) and 0xFF] shl 16) or (mg[(p shr 8) and 0xFF] shl 8) or mb[p and 0xFF] }
    }

    /** Limpia la hoja según el filtro elegido. */
    fun filter(b: Bitmap, f: ScanFilter, shadows: Boolean = true): Bitmap {
        if (f == ScanFilter.ORIGINAL) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        if (f == ScanFilter.PHOTO) {
            autoLevels(px)
        } else {
            val lum = IntArray(w * h) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
            if (f == ScanFilter.GRAY && !shadows) {
                for (i in px.indices) { val v = lum[i]; px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            } else {
                // sin "quitar sombras": usamos un fondo parejo (el blanco más común de la hoja)
                val bgf: (Int, Int) -> Float = if (shadows) background(lum, w, h) else {
                    val hist = IntArray(256); for (v in lum) hist[v]++
                    var a = 0; var paper = 255
                    for (k in 255 downTo 0) { a += hist[k]; if (a > lum.size * 0.10) { paper = k; break } }
                    val pv = maxOf(60f, paper.toFloat()); { _, _ -> pv }
                }
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    val k = 255f / bgf(x, y)
                    when (f) {
                        ScanFilter.DOCUMENT, ScanFilter.INK -> {
                            val n = lum[i] * k
                            var v = if (f == ScanFilter.DOCUMENT) ((n - 95f) * 255f / (232f - 95f)).toInt()
                            else ((n - 60f) * 255f / (225f - 60f)).toInt() + 40
                            v = v.coerceIn(0, 255)
                            if (v > 215) v = 255
                            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                        }
                        ScanFilter.GRAY -> {
                            val v = ((lum[i] * k - 20f) * 255f / 235f).toInt().coerceIn(0, 255).let { if (it > 245) 255 else it }
                            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                        }
                        else -> {
                            val p = px[i]
                            fun c(v: Int): Int {
                                // suave: aclara el papel sin quemar los colores ni el texto claro
                                val r = ((v * k - 12f) * 255f / (250f - 12f)).toInt().coerceIn(0, 255)
                                return if (r > 248) 255 else r
                            }
                            px[i] = (0xFF shl 24) or (c((p shr 16) and 0xFF) shl 16) or (c((p shr 8) and 0xFF) shl 8) or c(p and 0xFF)
                        }
                    }
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (out !== b) b.recycle()
        return out
    }

    /** Brillo, contraste y color. */
    fun adjust(b: Bitmap, bright: Int, contrast: Int, sat: Int): Bitmap {
        if (bright == 0 && contrast == 0 && sat == 0) return b
        val cm = ColorMatrix()
        cm.setSaturation((100 + sat) / 100f)
        val c = (100 + contrast) / 100f
        val t = 128f * (1 - c) + bright * 1.1f
        cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        b.recycle()
        return out
    }

    /** Nitidez (máscara de enfoque): realza los bordes de las letras. */
    fun sharpen(b: Bitmap, amount: Int): Bitmap {
        if (amount <= 0) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val a = amount / 50f
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else y; val yp = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x; val xp = if (x < w - 1) x + 1 else x
                val p = px[y * w + x]
                val n1 = px[ym * w + x]; val n2 = px[yp * w + x]; val n3 = px[y * w + xm]; val n4 = px[y * w + xp]
                fun ch(s: Int): Int {
                    val c = (p shr s) and 0xFF
                    val avg = (((n1 shr s) and 0xFF) + ((n2 shr s) and 0xFF) + ((n3 shr s) and 0xFF) + ((n4 shr s) and 0xFF)) / 4f
                    return (c + a * (c - avg)).toInt().coerceIn(0, 255)
                }
                out[y * w + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        val r = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        r.setPixels(out, 0, w, 0, 0, w, h)
        b.recycle()
        return r
    }

    /** Proceso completo de una hoja con todos sus retoques. */
    fun process(src: Bitmap, e: Edit, maxSide: Float = 3300f): Bitmap {
        val full = e.quad.contentEquals(fullQuad(0f))
        var b = if (full && maxOf(src.width, src.height) <= maxSide) src.copy(Bitmap.Config.ARGB_8888, true) else warp(src, e.quad, maxSide)
        b = rotate(b, e.rot)
        b = straighten(b, e.angle, e.mirror)
        b = filter(b, e.filter, e.shadows)
        b = adjust(b, e.bright, e.contrast + if (e.filter == ScanFilter.MAGIC) 12 else 0, e.sat + if (e.filter == ScanFilter.MAGIC) 45 else 0)
        // la nitidez se ajusta al tamaño para que la vista previa se parezca al resultado final
        b = sharpen(b, if (maxSide < 2000) e.sharp / 2 else e.sharp)
        return b
    }
}
