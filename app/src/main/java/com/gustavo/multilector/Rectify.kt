package com.gustavo.multilector

/**
 * Corrector de perspectiva ("aplanar la hoja").
 * Busca las líneas rectas largas del documento (bordes de tablas, recuadros, renglones, bordes del papel),
 * arma el cuadrilátero que forman y lo lleva a un rectángulo perfecto.
 * Todo en Kotlin puro (sin Android) para poder probarlo en la computadora.
 */
object Rectify {

    class Line(val theta: Double, val rho: Double, val votes: Int)

    // ---------------- utilidades ----------------
    fun lumOf(px: IntArray): IntArray = IntArray(px.size) {
        val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /** Achica una imagen (promediando) para analizarla rápido. */
    fun shrink(px: IntArray, w: Int, h: Int, maxSide: Int): Triple<IntArray, Int, Int> {
        val f = maxOf(1, Math.ceil(maxOf(w, h).toDouble() / maxSide).toInt())
        val sw = w / f; val sh = h / f
        val out = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val p = px[(y * f + dy) * w + x * f + dx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val n = f * f
            out[y * sw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Triple(out, sw, sh)
    }

    /** Fondo local (lo más claro de cada zona) para separar la tinta del papel aunque haya sombras. */
    private fun localMax(lum: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(lum.size); val out = IntArray(lum.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (k in maxOf(0, x - r)..minOf(w - 1, x + r) step 2) m = maxOf(m, lum[y * w + k])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (k in maxOf(0, y - r)..minOf(h - 1, y + r) step 2) m = maxOf(m, tmp[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** Máscara de tinta: puntos bastante más oscuros que el papel que los rodea. */
    fun inkMask(lum: IntArray, w: Int, h: Int): BooleanArray {
        val bg = localMax(lum, w, h, maxOf(6, maxOf(w, h) / 40))
        return BooleanArray(lum.size) { lum[it] < bg[it] * 0.70 && lum[it] < 170 }
    }

    // ---------------- búsqueda de líneas (Hough) ----------------
    /** Líneas casi horizontales (horizontal=true) o casi verticales, con resolución de 0,25°. */
    /** Deja solo trazos finos: para líneas horizontales, tinta con papel claro arriba o abajo (lo mismo de costado para verticales). */
    fun thin(mask: BooleanArray, w: Int, h: Int, horizontal: Boolean, gap: Int = 4): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!mask[i]) continue
            out[i] = if (horizontal) {
                val a = y - gap; val b = y + gap
                (a >= 0 && !mask[a * w + x]) || (b < h && !mask[b * w + x])
            } else {
                val a = x - gap; val b = x + gap
                (a >= 0 && !mask[y * w + a]) || (b < w && !mask[y * w + b])
            }
            // descartar manchas grandes: si alrededor (a lo ancho) casi todo es tinta, no es una línea
            if (out[i]) {
                var n = 0; var t = 0
                for (d in -gap * 2..gap * 2 step 2) {
                    val yy = if (horizontal) y + d else y; val xx = if (horizontal) x else x + d
                    if (yy in 0 until h && xx in 0 until w) { t++; if (mask[yy * w + xx]) n++ }
                }
                if (n > t * 0.7) out[i] = false
            }
        }
        return out
    }

    fun lines(mask: BooleanArray, w: Int, h: Int, horizontal: Boolean): List<Line> {
        val steps = 161 // ±20° en pasos de 0,25°
        val thetas = DoubleArray(steps) { Math.toRadians((if (horizontal) 90.0 else 0.0) + (it - 80) * 0.25) }
        val cosT = DoubleArray(steps) { Math.cos(thetas[it]) }
        val sinT = DoubleArray(steps) { Math.sin(thetas[it]) }
        val diag = Math.ceil(Math.hypot(w.toDouble(), h.toDouble())).toInt()
        val stride = 2 * diag + 1
        val acc = IntArray(steps * stride)
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) continue
            for (t in 0 until steps) {
                val r = Math.round(x * cosT[t] + y * sinT[t]).toInt() + diag
                acc[t * stride + r]++
            }
        }
        val minVotes = (if (horizontal) w else h) * 0.12
        val found = ArrayList<Line>()
        for (t in 0 until steps) for (r in 1 until stride - 1) {
            val v = acc[t * stride + r]
            if (v < minVotes) continue
            var peak = true
            loop@ for (dt in -6..6) for (dr in -4..4) {
                if (dt == 0 && dr == 0) continue
                val tt = t + dt; val rr = r + dr
                if (tt < 0 || tt >= steps || rr < 0 || rr >= stride) continue
                val o = acc[tt * stride + rr]
                if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { peak = false; break@loop }
            }
            if (peak) found.add(Line(thetas[t], (r - diag).toDouble(), v))
        }
        return found
    }

    /** Posición de la línea en el centro de la imagen (y para horizontales, x para verticales). */
    fun posAt(l: Line, w: Int, h: Int, horizontal: Boolean): Double =
        if (horizontal) (l.rho - (w / 2.0) * Math.cos(l.theta)) / Math.sin(l.theta)
        else (l.rho - (h / 2.0) * Math.sin(l.theta)) / Math.cos(l.theta)

    fun intersect(a: Line, b: Line): DoubleArray? {
        val det = Math.cos(a.theta) * Math.sin(b.theta) - Math.sin(a.theta) * Math.cos(b.theta)
        if (Math.abs(det) < 1e-9) return null
        val x = (a.rho * Math.sin(b.theta) - b.rho * Math.sin(a.theta)) / det
        val y = (b.rho * Math.cos(a.theta) - a.rho * Math.cos(b.theta)) / det
        return doubleArrayOf(x, y)
    }

    /**
     * Encuentra el cuadrilátero a aplanar (en coordenadas normalizadas 0..1: arriba-izq, arriba-der, abajo-der, abajo-izq).
     * Usa las líneas fuertes de más afuera. Si no hay líneas verticales, usa los márgenes del texto.
     * Devuelve null si no encuentra nada confiable.
     */
    fun findQuad(px: IntArray, w0: Int, h0: Int): FloatArray? {
        val (sp, w, h) = shrink(px, w0, h0, 1400)
        val lum = lumOf(sp)
        val mask = inkMask(lum, w, h)
        // ignorar un margencito: ahí suelen estar las sombras del borde
        val mx = w / 40; val my = h / 40
        for (y in 0 until h) for (x in 0 until w) if (x < mx || x >= w - mx || y < my || y >= h - my) mask[y * w + x] = false
        val hs = lines(thin(mask, w, h, true), w, h, true)
        val vs = lines(thin(mask, w, h, false), w, h, false)
        val paper = paperLevel(lum)
        // líneas "de verdad": trazo largo y continuo, con papel claro a los dos lados (no bordes de mesa ni renglones sueltos)
        // las líneas pegadas al borde de la foto suelen ser sombras o el borde de la mesa: se ignoran
        fun inside(l: Line, horizontal: Boolean): Boolean { val p = posAt(l, w, h, horizontal); val n = if (horizontal) h else w; return p > n * 0.05 && p < n * 0.95 }
        val hGood = hs.filter { inside(it, true) }.filter { val st = stats(it, mask, lum, w, h, true); st[0] >= w * 0.35 && st[1] >= paper * 0.72 && st[2] >= paper * 0.72 }
        val vGood = vs.filter { inside(it, false) }.filter { val st = stats(it, mask, lum, w, h, false); st[0] >= h * 0.15 && st[1] >= paper * 0.72 && st[2] >= paper * 0.72 }
        if (hGood.size < 2) return null
        val top = hGood.minByOrNull { posAt(it, w, h, true) }!!
        val bot = hGood.maxByOrNull { posAt(it, w, h, true) }!!
        if (posAt(bot, w, h, true) - posAt(top, w, h, true) < h * 0.12) return null
        val left: Line; val right: Line
        if (vGood.size >= 2 && posAt(vGood.maxByOrNull { posAt(it, w, h, false) }!!, w, h, false) - posAt(vGood.minByOrNull { posAt(it, w, h, false) }!!, w, h, false) >= w * 0.25) {
            left = vGood.minByOrNull { posAt(it, w, h, false) }!!
            right = vGood.maxByOrNull { posAt(it, w, h, false) }!!
        } else {
            // sin líneas verticales: bordes del texto (columna más a la izquierda / derecha con tinta)
            val colInk = IntArray(w)
            val y0 = maxOf(0, posAt(top, w, h, true).toInt()); val y1 = minOf(h - 1, posAt(bot, w, h, true).toInt())
            for (y in y0..y1) for (x in 0 until w) if (mask[y * w + x]) colInk[x]++
            val thr = maxOf(3, (y1 - y0) / 40)
            val xl = (0 until w).firstOrNull { colInk[it] > thr } ?: return null
            val xr = (w - 1 downTo 0).firstOrNull { colInk[it] > thr } ?: return null
            if (xr - xl < w * 0.3) return null
            left = Line(0.0, xl.toDouble(), 0); right = Line(0.0, xr.toDouble(), 0)
        }
        val pts = listOf(intersect(top, left), intersect(top, right), intersect(bot, right), intersect(bot, left))
        if (pts.any { it == null }) return null
        val q = FloatArray(8)
        pts.forEachIndexed { k, p -> q[k * 2] = (p!![0] / w).toFloat(); q[k * 2 + 1] = (p[1] / h).toFloat() }
        if (q.any { it < -0.2f || it > 1.2f }) return null
        return q
    }

    fun paperLevel(lum: IntArray): Int {
        val hist = IntArray(256); for (v in lum) hist[v.coerceIn(0, 255)]++
        var acc = 0
        for (k in 255 downTo 0) { acc += hist[k]; if (acc > lum.size * 0.30) return maxOf(60, k) }
        return 200
    }

    /** [tramo continuo más largo, brillo de un lado, brillo del otro lado] a lo largo de la línea. */
    fun stats(l: Line, mask: BooleanArray, lum: IntArray, w: Int, h: Int, horizontal: Boolean): DoubleArray {
        val n = if (horizontal) w else h
        var best = 0; var run = 0; var gap = 0
        var sideA = 0.0; var sideB = 0.0; var cnt = 0
        val off = 5
        for (k in 0 until n) {
            // punto de la línea
            val x: Int; val y: Int
            if (horizontal) { x = k; y = Math.round((l.rho - k * Math.cos(l.theta)) / Math.sin(l.theta)).toInt() }
            else { y = k; x = Math.round((l.rho - k * Math.sin(l.theta)) / Math.cos(l.theta)).toInt() }
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) { gap++; if (gap > 4) run = 0; continue }
            var ink = false
            for (d in -1..1) { val xx = if (horizontal) x else x + d; val yy = if (horizontal) y + d else y; if (mask[yy * w + xx]) ink = true }
            if (ink) {
                run += 1 + (if (gap in 1..4) gap else 0); gap = 0; if (run > best) best = run
                val ax = if (horizontal) x else x - off; val ay = if (horizontal) y - off else y
                val bx = if (horizontal) x else x + off; val by = if (horizontal) y + off else y
                if (ax in 0 until w && ay in 0 until h && bx in 0 until w && by in 0 until h) {
                    sideA += lum[ay * w + ax]; sideB += lum[by * w + bx]; cnt++
                }
            } else { gap++; if (gap > 4) run = 0 }
        }
        return doubleArrayOf(best.toDouble(), if (cnt > 0) sideA / cnt else 0.0, if (cnt > 0) sideB / cnt else 0.0)
    }

    /**
     * Encuadra al contenido: recorta el sobrante (mesa, fondo) dejando un margen blanco alrededor del texto.
     * Devuelve el rectángulo [x0, y0, x1, y1] en píxeles, o null si no conviene recortar.
     */
    fun contentBox(px: IntArray, w: Int, h: Int): IntArray? {
        val (sp, sw, sh) = shrink(px, w, h, 900)
        val lum = lumOf(sp)
        val mask = inkMask(lum, sw, sh)
        val th = thin(mask, sw, sh, true)
        val tv = thin(mask, sw, sh, false)
        val rows = IntArray(sh); val cols = IntArray(sw)
        for (y in 0 until sh) for (x in 0 until sw) { val i = y * sw + x; if (th[i] || tv[i]) { rows[y]++; cols[x]++ } }
        val tr = maxOf(2, sw / 150); val tc = maxOf(2, sh / 150)
        val y0 = (0 until sh).firstOrNull { rows[it] > tr } ?: return null
        val y1 = (sh - 1 downTo 0).firstOrNull { rows[it] > tr } ?: return null
        val x0 = (0 until sw).firstOrNull { cols[it] > tc } ?: return null
        val x1 = (sw - 1 downTo 0).firstOrNull { cols[it] > tc } ?: return null
        if (x1 - x0 < sw * 0.2 || y1 - y0 < sh * 0.2) return null
        // margen tipo hoja: 6% del ancho del contenido
        val m = ((x1 - x0) * 0.06).toInt()
        val f = w.toDouble() / sw
        val r = intArrayOf(((x0 - m) * f).toInt().coerceAtLeast(0), ((y0 - m) * f).toInt().coerceAtLeast(0),
            ((x1 + m) * f).toInt().coerceAtMost(w), ((y1 + m) * f).toInt().coerceAtMost(h))
        // si casi no recorta nada, no vale la pena
        if ((r[2] - r[0]).toLong() * (r[3] - r[1]) > w.toLong() * h * 0.92) return null
        return r
    }

    /** ¿Vale la pena corregir? (si el cuadrilátero ya es casi un rectángulo derecho, no se toca) */
    fun isSkewed(q: FloatArray, w: Int, h: Int): Boolean {
        fun ang(x1: Float, y1: Float, x2: Float, y2: Float) = Math.toDegrees(Math.atan2(((y2 - y1) * h).toDouble(), ((x2 - x1) * w).toDouble()))
        val top = ang(q[0], q[1], q[2], q[3]); val bot = ang(q[6], q[7], q[4], q[5])
        val left = ang(q[0], q[1], q[6], q[7]) - 90; val right = ang(q[2], q[3], q[4], q[5]) - 90
        return listOf(top, bot, left, right).any { Math.abs(it) > 0.35 }
    }

    // ---------------- homografía ----------------
    /** Matriz 3x3 que lleva los 4 puntos src a los 4 puntos dst. */
    fun homography(src: DoubleArray, dst: DoubleArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i * 2]; val y = src[i * 2 + 1]; val u = dst[i * 2]; val v = dst[i * 2 + 1]
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            var p = c
            for (r in c + 1 until 8) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c]
            if (Math.abs(d) < 1e-12) continue
            for (k in c until 9) a[c][k] /= d
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c]
                if (f != 0.0) for (k in c until 9) a[r][k] -= f * a[c][k]
            }
        }
        return doubleArrayOf(a[0][8], a[1][8], a[2][8], a[3][8], a[4][8], a[5][8], a[6][8], a[7][8], 1.0)
    }

    fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray {
        val d = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / d, (m[3] * x + m[4] * y + m[5]) / d)
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        return doubleArrayOf(A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det)
    }

    /**
     * Aplana la imagen: el cuadrilátero q (normalizado) pasa a ser un rectángulo.
     * La imagen entera se transforma (no se pierde nada); lo que queda afuera se rellena de blanco.
     * Devuelve los píxeles nuevos y su tamaño.
     */
    fun flatten(px: IntArray, w: Int, h: Int, q: FloatArray, maxSide: Int = 3300): Triple<IntArray, Int, Int> {
        val p = DoubleArray(8) { if (it % 2 == 0) q[it].toDouble() * w else q[it].toDouble() * h }
        fun d(a: Int, b: Int) = Math.hypot(p[a * 2] - p[b * 2], p[a * 2 + 1] - p[b * 2 + 1])
        val rw = maxOf(d(0, 1), d(3, 2)); val rh = maxOf(d(0, 3), d(1, 2))
        val x0 = p[0]; val y0 = p[1]
        val dst = doubleArrayOf(x0, y0, x0 + rw, y0, x0 + rw, y0 + rh, x0, y0 + rh)
        val hm = homography(p, dst)
        // dónde caen las esquinas de la foto
        val corners = listOf(apply(hm, 0.0, 0.0), apply(hm, w.toDouble(), 0.0), apply(hm, w.toDouble(), h.toDouble()), apply(hm, 0.0, h.toDouble()))
        var minX = corners.minOf { it[0] }; var maxX = corners.maxOf { it[0] }
        var minY = corners.minOf { it[1] }; var maxY = corners.maxOf { it[1] }
        // no dejar que crezca sin límite (perspectivas muy fuertes)
        minX = maxOf(minX, x0 - w * 0.6); maxX = minOf(maxX, x0 + rw + w * 0.6)
        minY = maxOf(minY, y0 - h * 0.6); maxY = minOf(maxY, y0 + rh + h * 0.6)
        var sc = 1.0
        val ow0 = maxX - minX; val oh0 = maxY - minY
        if (maxOf(ow0, oh0) > maxSide) sc = maxSide / maxOf(ow0, oh0)
        val ow = maxOf(8, (ow0 * sc).toInt()); val oh = maxOf(8, (oh0 * sc).toInt())
        val inv = invert(hm)
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val yy = oy / sc + minY
            for (ox in 0 until ow) {
                val xx = ox / sc + minX
                val dd = inv[6] * xx + inv[7] * yy + inv[8]
                val sx = (inv[0] * xx + inv[1] * yy + inv[2]) / dd
                val sy = (inv[3] * xx + inv[4] * yy + inv[5]) / dd
                out[oy * ow + ox] = sample(px, w, h, sx, sy)
            }
        }
        return Triple(out, ow, oh)
    }

    private fun sample(px: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return -1 // blanco
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(w - 1, x0 + 1); val y1 = minOf(h - 1, y0 + 1)
        val fx = x - x0; val fy = y - y0
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        fun ch(s: Int): Int {
            val top = ((a shr s) and 0xFF) * (1 - fx) + ((b shr s) and 0xFF) * fx
            val bot = ((c shr s) and 0xFF) * (1 - fx) + ((d shr s) and 0xFF) * fx
            return (top * (1 - fy) + bot * fy).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Limpia los bordes: las franjas oscuras (mesa, sombras) pegadas a los costados se vuelven blancas.
     * Solo toca la franja exterior (hasta 8% de cada lado) y solo lo oscuro que llega hasta el borde.
     */
    fun cleanBorders(px: IntArray, w: Int, h: Int) {
        val (sp, sw, sh) = shrink(px, w, h, 600)
        val lum = lumOf(sp)
        // papel = lo claro más común
        val hist = IntArray(256); for (v in lum) hist[v]++
        var acc = 0; var paper = 230
        for (k in 255 downTo 0) { acc += hist[k]; if (acc > lum.size * 0.30) { paper = k; break } }
        val dark = BooleanArray(lum.size) { lum[it] < paper * 0.80 }
        val bandX = (sw * 0.08).toInt(); val bandY = (sh * 0.08).toInt()
        fun inBand(x: Int, y: Int) = x < bandX || x >= sw - bandX || y < bandY || y >= sh - bandY
        val mark = BooleanArray(lum.size)
        val queue = IntArray(lum.size)
        var head = 0; var tail = 0
        for (x in 0 until sw) for (y in listOf(0, sh - 1)) { val i = y * sw + x; if (dark[i] && !mark[i]) { mark[i] = true; queue[tail++] = i } }
        for (y in 0 until sh) for (x in listOf(0, sw - 1)) { val i = y * sw + x; if (dark[i] && !mark[i]) { mark[i] = true; queue[tail++] = i } }
        while (head < tail) {
            val i = queue[head++]; val x = i % sw; val y = i / sw
            fun go(nx: Int, ny: Int) {
                if (nx < 0 || ny < 0 || nx >= sw || ny >= sh) return
                val j = ny * sw + nx
                if (mark[j] || !dark[j] || !inBand(nx, ny)) return
                mark[j] = true; queue[tail++] = j
            }
            go(x - 1, y); go(x + 1, y); go(x, y - 1); go(x, y + 1)
        }
        // agrandar un poquito la zona marcada (para tapar el borde gris de la sombra)
        val grow = BooleanArray(mark.size)
        for (y in 0 until sh) for (x in 0 until sw) if (mark[y * sw + x]) {
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until sw && ny in 0 until sh && inBand(nx, ny)) grow[ny * sw + nx] = true
            }
        }
        val fx = w.toDouble() / sw; val fy = h.toDouble() / sh
        for (y in 0 until h) {
            val syy = minOf(sh - 1, (y / fy).toInt())
            for (x in 0 until w) {
                val sxx = minOf(sw - 1, (x / fx).toInt())
                if (grow[syy * sw + sxx]) px[y * w + x] = -1
            }
        }
    }
}
