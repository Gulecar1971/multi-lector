package com.gustavo.multilector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

enum class Fmt(val ext: String, val mime: String, val label: String) {
    JPG("jpg", "image/jpeg", "JPG (fotos, liviano)"),
    PNG("png", "image/png", "PNG (sin pérdida, admite transparencia)"),
    WEBP("webp", "image/webp", "WEBP (muy liviano, web)")
}

enum class Fit(val label: String) {
    FIT("Encajar sin recortar"),
    CROP("Recortar al tamaño exacto"),
    PAD("Tamaño exacto con fondo (sin recortar)"),
    STRETCH("Estirar al tamaño exacto"),
    KEEP("Mantener el tamaño original")
}

class Encoded(val bytes: ByteArray, val w: Int, val h: Int)

object Img {
    fun orientation(ins: InputStream): Int = try {
        ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } catch (_: Exception) {
        1
    }

    fun orientation(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openInputStream(uri)?.use { orientation(it) } ?: 1
    } catch (_: Exception) {
        1
    }

    fun bounds(ctx: Context, uri: Uri): IntArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        } catch (_: Exception) {
        }
        return intArrayOf(o.outWidth, o.outHeight)
    }

    /** Tamaño como se ve (teniendo en cuenta la rotación EXIF). */
    fun displaySize(ctx: Context, uri: Uri): IntArray {
        val b = bounds(ctx, uri)
        val o = orientation(ctx, uri)
        return if (o in 5..8) intArrayOf(b[1], b[0]) else b
    }

    fun rotate(b: Bitmap, o: Int): Bitmap {
        if (o <= 1 || o > 8) return b
        val m = Matrix()
        when (o) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    private fun sample(w: Int, h: Int, maxPixels: Long): Int {
        var s = 1
        while (w.toLong() / s * (h.toLong() / s) > maxPixels) s *= 2
        return s
    }

    /** Abre una foto desde el teléfono, ya girada como corresponde. */
    fun decode(ctx: Context, uri: Uri, maxPixels: Long = 24_000_000): Bitmap? {
        val b = bounds(ctx, uri)
        val o = orientation(ctx, uri)
        if (b[0] > 0 && b[1] > 0) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample(b[0], b[1], maxPixels)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (_: OutOfMemoryError) {
                null
            }
            if (bmp != null) return rotate(bmp, o)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            return try {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val px = info.size.width.toLong() * info.size.height
                    if (px > maxPixels) {
                        val f = Math.sqrt(maxPixels.toDouble() / px)
                        dec.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun decodeBytes(data: ByteArray, maxPixels: Long = 24_000_000): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var bmp: Bitmap? = null
        if (o.outWidth > 0 && o.outHeight > 0) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample(o.outWidth, o.outHeight, maxPixels)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bmp = try { BitmapFactory.decodeByteArray(data, 0, data.size, opts) } catch (_: OutOfMemoryError) { null }
        }
        if (bmp == null && Build.VERSION.SDK_INT >= 28) {
            bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data))) { dec, _, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) {
                null
            }
            return bmp
        }
        return bmp?.let { rotate(it, orientation(ByteArrayInputStream(data))) }
    }

    /** Achica con buena calidad (por pasos, para que no quede "serruchado"). */
    fun smoothScale(src: Bitmap, w: Int, h: Int): Bitmap {
        val tw = w.coerceAtLeast(1)
        val th = h.coerceAtLeast(1)
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            val n = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = n
        }
        if (cur.width == tw && cur.height == th) return if (cur === src) cur.copy(Bitmap.Config.ARGB_8888, false) else cur
        val r = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && r !== cur) cur.recycle()
        return r
    }

    fun resize(src: Bitmap, tw: Int, th: Int, fit: Fit, bg: Int, upscale: Boolean): Bitmap {
        val sw = src.width
        val sh = src.height
        if (fit == Fit.KEEP || tw <= 0 || th <= 0) return src
        return when (fit) {
            Fit.FIT -> {
                var s = minOf(tw.toDouble() / sw, th.toDouble() / sh)
                if (!upscale) s = minOf(s, 1.0)
                if (s == 1.0) src else smoothScale(src, Math.round(sw * s).toInt(), Math.round(sh * s).toInt())
            }
            Fit.STRETCH -> smoothScale(src, tw, th)
            Fit.CROP -> {
                val targetRatio = tw.toDouble() / th
                val srcRatio = sw.toDouble() / sh
                val cw: Int; val ch: Int
                if (srcRatio > targetRatio) { ch = sh; cw = Math.round(sh * targetRatio).toInt().coerceIn(1, sw) }
                else { cw = sw; ch = Math.round(sw / targetRatio).toInt().coerceIn(1, sh) }
                val cropped = Bitmap.createBitmap(src, (sw - cw) / 2, (sh - ch) / 2, cw, ch)
                val r = smoothScale(cropped, tw, th)
                if (cropped !== src && cropped !== r) cropped.recycle()
                r
            }
            Fit.PAD -> {
                val s = minOf(tw.toDouble() / sw, th.toDouble() / sh)
                val iw = Math.round(sw * s).toInt().coerceIn(1, tw)
                val ih = Math.round(sh * s).toInt().coerceIn(1, th)
                val scaled = smoothScale(src, iw, ih)
                val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(bg)
                c.drawBitmap(scaled, Rect(0, 0, iw, ih), Rect((tw - iw) / 2, (th - ih) / 2, (tw - iw) / 2 + iw, (th - ih) / 2 + ih),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                scaled.recycle()
                out
            }
            else -> src
        }
    }

    private fun cf(f: Fmt): Bitmap.CompressFormat = when (f) {
        Fmt.JPG -> Bitmap.CompressFormat.JPEG
        Fmt.PNG -> Bitmap.CompressFormat.PNG
        Fmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    private fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(o)
        c.drawColor(Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        return o
    }

    /** Guarda en el formato pedido; si hay peso máximo, baja calidad y después tamaño hasta entrar. */
    fun encode(b: Bitmap, fmt: Fmt, quality: Int, maxKB: Int): Encoded {
        fun enc(bm: Bitmap, q: Int): ByteArray {
            val bos = ByteArrayOutputStream()
            bm.compress(cf(fmt), q, bos)
            return bos.toByteArray()
        }
        var cur = if (fmt == Fmt.JPG) flatten(b) else b
        val first = enc(cur, quality)
        val limit = maxKB * 1024L
        if (maxKB <= 0 || first.size <= limit) return Encoded(first, cur.width, cur.height)
        var last = first
        for (round in 0 until 12) {
            if (fmt != Fmt.PNG) {
                var lo = 25
                var hi = minOf(quality, 95)
                var best: ByteArray? = null
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val d = enc(cur, mid)
                    if (d.size <= limit) { best = d; lo = mid + 1 } else hi = mid - 1
                }
                if (best != null) return Encoded(best, cur.width, cur.height)
            }
            val n = smoothScale(cur, (cur.width * 0.85).toInt(), (cur.height * 0.85).toInt())
            if (cur !== b) cur.recycle()
            cur = n
            last = enc(cur, if (fmt == Fmt.PNG) 100 else 70)
            if (last.size <= limit) return Encoded(last, cur.width, cur.height)
            if (cur.width < 64 || cur.height < 64) break
        }
        return Encoded(last, cur.width, cur.height)
    }

    /** Copia fecha y cámara de la foto original al JPG nuevo (no copia la ubicación). */
    fun copyExif(ctx: Context, src: Uri, dest: File) {
        try {
            val s = ctx.contentResolver.openInputStream(src)?.use { ExifInterface(it) } ?: return
            val d = ExifInterface(dest.path)
            for (t in listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)) {
                s.getAttribute(t)?.let { d.setAttribute(t, it) }
            }
            d.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            d.saveAttributes()
        } catch (_: Exception) {
        }
    }
}
