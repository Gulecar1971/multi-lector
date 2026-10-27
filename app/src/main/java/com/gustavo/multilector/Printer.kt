package com.gustavo.multilector

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.webkit.WebView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Imprimir con el sistema de impresión de Android.
 * Las impresoras HP aparecen si está instalado "HP Print Service Plugin" (viene con HP Smart).
 */
object Printer {
    private fun pm(act: Activity) = act.getSystemService(Context.PRINT_SERVICE) as PrintManager

    fun pdfFile(act: Activity, file: File, name: String) {
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                val info = PrintDocumentInfo.Builder(F.safe(name).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" })
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                cb.onLayoutFinished(info, true)
            }

            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                try {
                    FileInputStream(file).use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o, 1 shl 16) } }
                    if (cancel?.isCanceled == true) cb.onWriteCancelled() else cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed(e.message)
                }
            }
        }
        try {
            pm(act).print(F.base(name), adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        } catch (e: Exception) {
            act.alert("No se pudo imprimir", "El teléfono no dejó abrir la impresión: ${e.message}")
        }
    }

    /** Imprime un PDF que viene de otra app (se copia primero). */
    fun pdfUri(act: Activity, uri: Uri, name: String) {
        Thread {
            try {
                val f = File(F.work(act, "print"), "doc.pdf")
                F.copyTo(act, uri, f)
                act.runOnUiThread { pdfFile(act, f, name) }
            } catch (e: Exception) {
                act.runOnUiThread { act.alert("No se pudo imprimir", e.message ?: "") }
            }
        }.start()
    }

    /** Imprime fotos: arma un PDF A4 y lo manda a imprimir. */
    fun images(act: Activity, uris: List<Uri>, name: String) {
        Jobs.run(act, "Preparando impresión") { progress ->
            val f = File(F.work(act, "print"), "fotos.pdf")
            PdfImageWriter(f, PageMode.A4).use { w ->
                uris.forEachIndexed { i, u -> progress("Foto ${i + 1} de ${uris.size}"); PdfBuild.addImage(act, u, F.name(act, u), w, false) }
                if (w.pageCount == 0) throw UserError("No se pudo leer la foto.")
            }
            act.runOnUiThread { pdfFile(act, f, name) }
            JobResult("", "")
        }
    }

    /** Imprime lo que muestra una WebView (documentos de Word, Excel, etc.). */
    fun web(act: Activity, wv: WebView, name: String) {
        try {
            pm(act).print(F.base(name), wv.createPrintDocumentAdapter(F.base(name)), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        } catch (e: Exception) {
            act.alert("No se pudo imprimir", e.message ?: "")
        }
    }

    /** Abre la pantalla de impresión de Android con un PDF. La primera vez explica cómo elegir la HP. */
    fun start(act: Activity, file: File, name: String) {
        val sp = act.getSharedPreferences("impresion", Context.MODE_PRIVATE)
        if (sp.getBoolean("ayuda_vista", false)) { pdfFile(act, file, name); return }
        android.app.AlertDialog.Builder(act).setTitle("🖨 Imprimir")
            .setMessage("Ahora se abre la pantalla de impresión del teléfono.\n\n" +
                "• Arriba, en \"Seleccionar impresora\", elegí la HP blanco y negro o la HP color.\n" +
                "• En la flechita ⌄ podés elegir cantidad de copias, color o blanco y negro, y qué páginas.\n" +
                "• Tocá el botón redondo de la impresora para imprimir.\n\n$HELP")
            .setPositiveButton("Entendido") { _, _ -> sp.edit().putBoolean("ayuda_vista", true).apply(); pdfFile(act, file, name) }
            .show()
    }

    /** Imprime cualquier archivo: PDF directo, fotos armadas en A4, documentos pasados a PDF. */
    fun any(act: Activity, uris: List<Uri>) {
        if (uris.isEmpty()) return
        Jobs.run(act, "Preparando impresión") { progress ->
            progress("Revisando…")
            val kinds = uris.map { F.kind(act, it, F.name(act, it)) }
            val first = F.name(act, uris[0])
            val out = File(F.work(act, "print"), "imprimir.pdf")
            when {
                uris.size == 1 && kinds[0] == Kind.PDF -> F.copyTo(act, uris[0], out)
                kinds.all { it == Kind.IMAGE } -> PdfImageWriter(out, PageMode.A4).use { w ->
                    uris.forEachIndexed { i, u -> progress("Foto ${i + 1} de ${uris.size}"); PdfBuild.addImage(act, u, F.name(act, u), w, false) }
                    if (w.pageCount == 0) throw UserError("No se pudo leer la foto.")
                }
                uris.size == 1 && kinds[0].isDoc -> {
                    progress("Pasando a PDF…")
                    val dir = F.work(act, "printdoc")
                    val d = DocHtml.build(act, uris[0], first, kinds[0], dir)
                    val html = File(dir, "index.html")
                    html.writeText(DocHtml.page(first, d.body))
                    if (!HtmlToPdf.convert(act, html, out, d.landscape)) throw UserError("No se pudo preparar el documento para imprimir.")
                }
                else -> throw UserError(if (uris.size > 1) "Para imprimir varios archivos juntos, primero unilos con \"Convertir a PDF › Todo junto\" y después imprimí ese PDF." else "Este tipo de archivo no se puede imprimir.")
            }
            act.runOnUiThread { start(act, out, first) }
            JobResult("", "")
        }
    }

    const val HELP = "Si tu impresora HP no aparece en la lista: instalá \"HP Print Service Plugin\" desde Play Store " +
        "(o abrí HP Smart una vez) y en la pantalla de impresión tocá \"Seleccionar impresora\" › \"Todas las impresoras\"."
}
