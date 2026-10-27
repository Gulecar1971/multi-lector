package com.gustavo.multilector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

class PdfLockedException : Exception("El PDF tiene contraseña: no se puede abrir.")

/** Páginas de un PDF dibujadas con el lector que trae Android. */
class PdfDoc private constructor(private val pfd: ParcelFileDescriptor, val renderer: PdfRenderer) : Closeable {
    val count: Int get() = renderer.pageCount

    /** Tamaño de cada página en puntos (1/72 de pulgada). */
    val sizes: List<FloatArray> by lazy {
        synchronized(this) {
            (0 until count).map { i ->
                renderer.openPage(i).use { p -> floatArrayOf(p.width.toFloat(), p.height.toFloat()) }
            }
        }
    }

    /** Dibuja la página i. scale = píxeles por punto. Limita el tamaño para no quedarse sin memoria. */
    fun render(i: Int, scale: Float, maxPixels: Long = 9_000_000, forPrint: Boolean = false): Bitmap = synchronized(this) {
        renderer.openPage(i).use { p ->
            var s = scale
            val px = p.width * s * p.height * s
            if (px > maxPixels) s *= Math.sqrt(maxPixels / px.toDouble()).toFloat()
            val w = (p.width * s).toInt().coerceAtLeast(1)
            val h = (p.height * s).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() {
        synchronized(this) {
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    companion object {
        fun open(ctx: Context, uri: Uri): PdfDoc {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw Exception("no")
                try {
                    return PdfDoc(pfd, PdfRenderer(pfd))
                } catch (e: SecurityException) {
                    pfd.close(); throw PdfLockedException()
                } catch (e: Exception) {
                    pfd.close()
                }
            } catch (e: PdfLockedException) {
                throw e
            } catch (_: Exception) {
            }
            // Algunos lugares (Drive, WhatsApp) no dejan leer salteado: copiamos primero.
            val f = File(F.work(ctx, "pdf"), "doc.pdf")
            F.copyTo(ctx, uri, f)
            return open(f)
        }

        fun open(f: File): PdfDoc {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return PdfDoc(pfd, PdfRenderer(pfd))
            } catch (e: SecurityException) {
                pfd.close(); throw PdfLockedException()
            } catch (e: Exception) {
                pfd.close(); throw Exception("El PDF está dañado o no se puede leer.")
            }
        }
    }
}

object PdfBuild {
    /** Agrega una foto: si es JPG va byte a byte; si no, sin pérdida + el original adjunto. */
    fun addImage(ctx: Context, uri: Uri, name: String, w: PdfImageWriter, keepOriginal: Boolean = true): Boolean {
        val data = F.readAll(ctx, uri)
        if (PdfImageWriter.jpegInfo(data) != null) {
            val o = Img.orientation(data.inputStream())
            return w.addJpeg(data, name, o)
        }
        val bmp = Img.decodeBytes(data, 40_000_000) ?: return false
        w.addPixels(bmp.width, bmp.height, bmp.hasAlpha(), name, keepOriginal) { y, buf -> bmp.getPixels(buf, 0, bmp.width, 0, y, bmp.width, 1) }
        bmp.recycle()
        if (keepOriginal) w.attach(name, F.mimeFromExt(F.ext(name)).let { if (it.startsWith("image/")) it else "image/png" }, data)
        return true
    }

    fun jpeg(b: Bitmap, q: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, bos)
        return bos.toByteArray()
    }

    /** Agrega las páginas de un PDF como fotos (para unir o comprimir). */
    fun addPdfPages(doc: PdfDoc, w: PdfImageWriter, dpi: Int, quality: Int, progress: (Int, Int) -> Unit) {
        val n = doc.count
        for (i in 0 until n) {
            progress(i + 1, n)
            val sz = doc.sizes[i]
            val bmp = doc.render(i, dpi / 72f, 16_000_000, forPrint = true)
            val j = jpeg(bmp, quality)
            bmp.recycle()
            w.addJpeg(j, null, 1, sz[0].toDouble(), sz[1].toDouble())
        }
    }
}
