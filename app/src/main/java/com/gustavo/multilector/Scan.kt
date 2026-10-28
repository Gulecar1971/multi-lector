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
    val shadows: Boolean = true,
    val flat: FloatArray? = null,   // perspectiva a corregir (cuadrilátero en la hoja ya recortada)
    val clean: Boolean = true       // limpiar franjas oscuras de los bordes
) {
    fun toJson(): JSONObject = JSONObject().put("q", JSONArray(quad.map { it.toDouble() })).put("rot", rot).put("ang", angle.toDouble())
        .put("mir", mirror).put("f", filter.name).put("b", bright).put("c", contrast).put("s", sat).put("sh", sharp).put("so", shadows)
        .put("fl", flat?.let { f -> JSONArray(f.map { it.toDouble() }) } ?: JSONObject.NULL).put("cl", clean)

    override fun equals(other: Any?) = other is Edit && toJson().toString() == other.toJson().toString()
    override fun hashCode() = toJson().toString().hashCode()

    companion object {
        fun fromJson(o: JSONObject): Edit {
            val q = o.getJSONArray("q")
            return Edit(FloatArray(8) { q.getDouble(it).toFloat() }, o.optInt("rot"), o.optDouble("ang", 0.0).toFloat(), o.optBoolean("mir"),
                try { ScanFilter.valueOf(o.optString("f", "ENHANCED")) } catch (_: Exception) { ScanFilter.ENHANCED },
                o.optInt("b"), o.optInt("c"), o.optInt("s"), o.optInt("sh", 30), o.optBoolean("so", true),
                o.optJSONArray("fl")?.let { a -> FloatArray(8) { a.getDouble(it).toFloat() } }, o.optBoolean("cl", true))
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
    fun textSkew(b: Bitmap, sensitive: Boolean = false): Float {
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
            var a = -15.0
            while (a <= 15.0) { val sc = score(a); if (sc > bestS) { bestS = sc; best = a }; a += 0.5 }
            var f = best - 0.5
            while (f <= best + 0.5) { val sc = score(f); if (sc > bestS) { bestS = sc; best = f }; f += 0.1 }
            // solo endereza si está seguro: renglones claramente torcidos y no más de 10°
            if (sensitive) { if (Math.abs(best) < 0.2 || bestS < base * 1.03) return 0f }
            else if (Math.abs(best) < 0.4 || Math.abs(best) > 10 || bestS < base * 1.15) return 0f
            return best.toFloat()
        } catch (_: Throwable) {
            return 0f
        }
    }

    // ================= Enderezado de perspectiva (hoja "en trapecio") =================

    /** Resultado de rectify: esquinas a recortar (normalizadas) y si se encontraron líneas rectas para corregir. */
    class Rectified(val quad: FloatArray, val usedLines: Boolean)

    /** Homografía que lleva 4 puntos src a 4 puntos dst (x0,y0,x1,y1,…). */
    fun homography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i * 2]; val y = src[i * 2 + 1]; val u = dst[i * 2]; val v = dst[i * 2 + 1]
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            var piv = c
            for (r in c + 1 until 8) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r
            if (Math.abs(a[piv][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c until 9) a[r][k] -= f * a[c][k]
            }
        }
        val h = DoubleArray(9)
        for (i in 0 until 8) h[i] = a[i][8] / a[i][i]
        h[8] = 1.0
        return h
    }

    fun applyH(h: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    fun invertH(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (Math.abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }

    private class Ln(val theta: Double, val rho: Double, val votes: Int)

    /** Líneas rectas largas (bordes de tablas, recuadros, borde del papel) casi horizontales o casi verticales. */
    private fun longLines(lum: IntArray, w: Int, h: Int, horizontal: Boolean): List<Ln> {
        val mag = BooleanArray(w * h)
        // borde fuerte: cambio de claro a oscuro en la dirección perpendicular a la línea
        var cnt = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val g = if (horizontal) Math.abs(lum[i + w] - lum[i - w]) else Math.abs(lum[i + 1] - lum[i - 1])
            if (g > 40) { mag[i] = true; cnt++ }
        }
        if (cnt < 50) return emptyList()
        val step = 0.25
        val n = (40 / step).toInt() + 1 // -20..+20 grados alrededor de la dirección
        val thetas = DoubleArray(n) { Math.toRadians((if (horizontal) 90.0 else 0.0) - 20.0 + it * step) }
        val cs = DoubleArray(n) { Math.cos(thetas[it]) }; val sn = DoubleArray(n) { Math.sin(thetas[it]) }
        val diag = Math.hypot(w.toDouble(), h.toDouble()).toInt() + 2
        val stride = 2 * diag + 1
        val votes = IntArray(n * stride)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (!mag[y * w + x]) continue
            for (t in 0 until n) votes[t * stride + (Math.round(x * cs[t] + y * sn[t]).toInt() + diag)]++
        }
        val minVotes = (if (horizontal) w else h) * 0.28
        val out = ArrayList<Ln>()
        for (t in 0 until n) for (r in 2 until stride - 2) {
            val v = votes[t * stride + r]
            if (v < minVotes) continue
            var ok = true
            loop@ for (dt in -6..6) for (dr in -6..6) {
                if (dt == 0 && dr == 0) continue
                val tt = t + dt; val rr = r + dr
                if (tt < 0 || tt >= n) continue
                if (votes[tt * stride + rr] > v || (votes[tt * stride + rr] == v && (dt < 0 || (dt == 0 && dr < 0)))) { ok = false; break@loop }
            }
            if (ok) out.add(Ln(thetas[t], (r - diag).toDouble(), v))
        }
        return out
    }

    private fun lineAt(l: Ln, horizontal: Boolean, c: Double): Double =
        if (horizontal) (l.rho - c * Math.cos(l.theta)) / Math.sin(l.theta) else (l.rho - c * Math.sin(l.theta)) / Math.cos(l.theta)

    private fun cross(a: Ln, b: Ln): DoubleArray? {
        val det = Math.cos(a.theta) * Math.sin(b.theta) - Math.sin(a.theta) * Math.cos(b.theta)
        if (Math.abs(det) < 1e-9) return null
        return doubleArrayOf((a.rho * Math.sin(b.theta) - b.rho * Math.sin(a.theta)) / det, (b.rho * Math.cos(a.theta) - a.rho * Math.cos(b.theta)) / det)
    }

    /**
     * Endereza la hoja como un escáner: busca líneas rectas (tablas, recuadros, borde del papel),
     * corrige la perspectiva para que queden derechas y paralelas, y recorta bordes oscuros.
     * Devuelve las esquinas a usar con warp(), o null si no hay nada para corregir.
     */
    fun rectify(b: Bitmap): Rectified? {
        try {
            val s = 1000f / maxOf(b.width, b.height)
            val w = maxOf(16, (b.width * minOf(1f, s)).toInt()); val h = maxOf(16, (b.height * minOf(1f, s)).toInt())
            val small = Bitmap.createScaledBitmap(b, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== b) small.recycle()
            val lum = IntArray(w * h) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }

            val hs = longLines(lum, w, h, true)
            val vs = longLines(lum, w, h, false)
            if (System.getProperty("scan.debug") != null) {
                hs.forEach { println("H th=%.2f y@mid=%.1f v=%d".format(Math.toDegrees(it.theta), lineAt(it, true, w / 2.0), it.votes)) }
                vs.forEach { println("V th=%.2f x@mid=%.1f v=%d".format(Math.toDegrees(it.theta), lineAt(it, false, h / 2.0), it.votes)) }
            }
            fun pick(c: List<Ln>, horizontal: Boolean, first: Boolean): Ln? {
                val mid = if (horizontal) w / 2.0 else h / 2.0
                val size = if (horizontal) h else w
                // fuera los bordes de la foto (sombras, borde de la mesa) y las "líneas" falsas que forma el texto
                val inside = c.filter { val p = lineAt(it, horizontal, mid); p > size * 0.04 && p < size * 0.96 }
                val best = inside.maxOfOrNull { it.votes } ?: return null
                val strong = inside.filter { it.votes >= best * 0.6 }
                return if (first) strong.minByOrNull { lineAt(it, horizontal, mid) } else strong.maxByOrNull { lineAt(it, horizontal, mid) }
            }
            var top = pick(hs, true, true); var bot = pick(hs, true, false)
            var left = pick(vs, false, true); var right = pick(vs, false, false)
            if (top != null && bot != null && lineAt(bot, true, w / 2.0) - lineAt(top, true, w / 2.0) < h * 0.25) {
                // una sola línea horizontal: la usamos para la dirección, abajo va una paralela
                bot = Ln(top.theta, top.rho + h * 0.5, top.votes)
            }
            if (left != null && right != null && lineAt(right, false, h / 2.0) - lineAt(left, false, h / 2.0) < w * 0.25) {
                right = Ln(left.theta, left.rho + w * 0.5, left.votes)
            }
            val haveH = top != null && bot != null
            val haveV = left != null && right != null
            var usedLines = haveH || haveV
            if (!haveH && haveV) {
                // líneas horizontales perpendiculares a las verticales
                val th = (left!!.theta + right!!.theta) / 2 + Math.PI / 2
                top = Ln(th, h * 0.15 * Math.sin(th) + w * 0.5 * Math.cos(th), 0); bot = Ln(th, h * 0.85 * Math.sin(th) + w * 0.5 * Math.cos(th), 0)
            }
            if (haveH && !haveV) {
                val th = (top!!.theta + bot!!.theta) / 2 - Math.PI / 2
                left = Ln(th, w * 0.15 * Math.cos(th) + h * 0.5 * Math.sin(th), 0); right = Ln(th, w * 0.85 * Math.cos(th) + h * 0.5 * Math.sin(th), 0)
            }
            var hm = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            if (usedLines) {
                val tl = cross(top!!, left!!); val tr = cross(top, right!!); val br = cross(bot!!, right); val bl = cross(bot, left)
                if (tl == null || tr == null || br == null || bl == null) usedLines = false
                else {
                    val x0 = (tl[0] + bl[0]) / 2; val x1 = (tr[0] + br[0]) / 2
                    val y0 = (tl[1] + tr[1]) / 2; val y1 = (bl[1] + br[1]) / 2
                    val hh = homography(doubleArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]),
                        doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
                    if (hh == null) usedLines = false else hm = hh
                }
            }
            val inv = invertH(hm) ?: return null
            // zona útil: la imagen completa vista desde el lado corregido
            val c0 = applyH(hm, 0.0, 0.0); val c1 = applyH(hm, w.toDouble(), 0.0); val c2 = applyH(hm, w.toDouble(), h.toDouble()); val c3 = applyH(hm, 0.0, h.toDouble())
            var bx0 = maxOf(c0[0], c3[0]); var bx1 = minOf(c1[0], c2[0]); var by0 = maxOf(c0[1], c1[1]); var by1 = minOf(c2[1], c3[1])
            if (bx1 - bx0 < w * 0.5 || by1 - by0 < h * 0.5) return null

            // recortar bordes oscuros (sombra, mesa, borde de otra hoja)
            val sorted = lum.copyOf(); sorted.sort()
            val paper = sorted[(sorted.size * 0.9).toInt()]
            val dark = paper * 0.62
            fun darkFrac(horizontalLine: Boolean, pos: Double, a0: Double, a1: Double): Double {
                var d = 0; var t = 0
                val nS = 160
                for (k in 0 until nS) {
                    val q = a0 + (a1 - a0) * (k + 0.5) / nS
                    val p = if (horizontalLine) applyH(inv, q, pos) else applyH(inv, pos, q)
                    val xi = p[0].toInt(); val yi = p[1].toInt()
                    if (xi < 0 || yi < 0 || xi >= w || yi >= h) { d++; t++; continue }
                    if (lum[yi * w + xi] < dark) d++
                    t++
                }
                return d.toDouble() / t
            }
            val maxCutX = (bx1 - bx0) * 0.12; val maxCutY = (by1 - by0) * 0.12
            val stp = 1.0
            run { var c = 0.0; while (c < maxCutY && darkFrac(true, by0 + c, bx0, bx1) > 0.12) c += stp; by0 += c + if (c > 0) 2 else 0 }
            run { var c = 0.0; while (c < maxCutY && darkFrac(true, by1 - c, bx0, bx1) > 0.12) c += stp; by1 -= c + if (c > 0) 2 else 0 }
            run { var c = 0.0; while (c < maxCutX && darkFrac(false, bx0 + c, by0, by1) > 0.12) c += stp; bx0 += c + if (c > 0) 2 else 0 }
            run { var c = 0.0; while (c < maxCutX && darkFrac(false, bx1 - c, by0, by1) > 0.12) c += stp; bx1 -= c + if (c > 0) 2 else 0 }

            val corners = listOf(applyH(inv, bx0, by0), applyH(inv, bx1, by0), applyH(inv, bx1, by1), applyH(inv, bx0, by1))
            val q = FloatArray(8)
            corners.forEachIndexed { k, p -> q[k * 2] = (p[0] / w).toFloat(); q[k * 2 + 1] = (p[1] / h).toFloat() }
            // si casi no cambia nada, no tocamos la hoja
            var diff = 0f
            val full = fullQuad(0f)
            for (k in 0 until 8) diff = maxOf(diff, Math.abs(q[k] - full[k]))
            if (diff < 0.004f) return null
            return Rectified(q, usedLines)
        } catch (_: Throwable) {
            return null
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

    /** Enderezado fino (pocos grados) y espejo, sin recortar la hoja. */
    fun straighten(b: Bitmap, angle: Float, mirror: Boolean): Bitmap {
        if (Math.abs(angle) < 0.05f && !mirror) return b
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val rad = Math.toRadians(Math.abs(angle).toDouble())
        // achica apenas para que entre la hoja entera (no recorta nada; las esquinas quedan blancas)
        val c = Math.cos(rad); val sn = Math.sin(rad)
        val fit = minOf(w / (w * c + h * sn), h / (w * sn + h * c)).toFloat()
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val m = Matrix()
        m.postTranslate(-w / 2, -h / 2)
        if (mirror) m.postScale(-1f, 1f)
        m.postRotate(angle)
        m.postScale(fit, fit)
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

    private fun pixels(b: Bitmap): IntArray { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return px }

    private fun toBitmap(px: IntArray, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Aplana la perspectiva (la hoja que se ve como trapecio pasa a ser rectángulo). */
    fun flattenBitmap(b: Bitmap, q: FloatArray, maxSide: Int): Bitmap {
        val (o, w, h) = Rectify.flatten(pixels(b), b.width, b.height, q, maxSide)
        b.recycle()
        return toBitmap(o, w, h)
    }

    /** Blanquea las franjas oscuras de los bordes (mesa, sombras). */
    fun cleanBitmap(b: Bitmap): Bitmap {
        val px = pixels(b)
        Rectify.cleanBorders(px, b.width, b.height)
        val out = toBitmap(px, b.width, b.height)
        b.recycle()
        return out
    }

    /**
     * Enderezado automático completo: primero intenta aplanar la perspectiva con las líneas del documento;
     * si no hay líneas claras, gira según los renglones del texto.
     * Devuelve (cuadrilátero de perspectiva o null, ángulo de giro).
     */
    fun autoFix(src: Bitmap, quad: FloatArray, rot: Int, sensitive: Boolean): Pair<FloatArray?, Float> {
        val warped = rotate(warp(src, quad, 1400f), rot)
        val px = pixels(warped)
        val q = try { Rectify.findQuad(px, warped.width, warped.height) } catch (_: Throwable) { null }
        if (q != null && Rectify.isSkewed(q, warped.width, warped.height)) { warped.recycle(); return q to 0f }
        val sk = textSkew(warped, sensitive)
        warped.recycle()
        return null to -sk
    }

    /** Proceso completo de una hoja con todos sus retoques. */
    fun process(src: Bitmap, e: Edit, maxSide: Float = 3300f): Bitmap {
        val full = e.quad.contentEquals(fullQuad(0f))
        var b = if (full && maxOf(src.width, src.height) <= maxSide) src.copy(Bitmap.Config.ARGB_8888, true) else warp(src, e.quad, maxSide)
        b = rotate(b, e.rot)
        e.flat?.let { q -> b = flattenBitmap(b, q, maxSide.toInt()) }
        b = straighten(b, e.angle, e.mirror)
        if (e.clean) b = cleanBitmap(b)
        b = filter(b, e.filter, e.shadows)
        b = adjust(b, e.bright, e.contrast + if (e.filter == ScanFilter.MAGIC) 12 else 0, e.sat + if (e.filter == ScanFilter.MAGIC) 45 else 0)
        // la nitidez se ajusta al tamaño para que la vista previa se parezca al resultado final
        b = sharpen(b, if (maxSide < 2000) e.sharp / 2 else e.sharp)
        return b
    }
}
