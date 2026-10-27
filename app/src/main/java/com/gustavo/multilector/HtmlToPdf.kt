package com.gustavo.multilector

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PdfPrinter
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Convierte una página HTML (con sus imágenes) en PDF usando la WebView del teléfono. */
object HtmlToPdf {
    /** Se llama desde un hilo de fondo: espera hasta que termina. */
    fun convert(ctx: Context, html: File, out: File, landscape: Boolean): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                val wv = WebView(ctx)
                wv.settings.allowFileAccess = true
                wv.settings.javaScriptEnabled = false
                wv.settings.loadsImagesAutomatically = true
                @Suppress("DEPRECATION")
                wv.settings.allowFileAccessFromFileURLs = true
                wv.webViewClient = object : WebViewClient() {
                    var started = false
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (started) return
                        started = true
                        main.postDelayed({
                            try {
                                val size = if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4.asPortrait()
                                val attrs = PrintAttributes.Builder()
                                    .setMediaSize(size)
                                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                                    .setMinMargins(PrintAttributes.Margins(550, 550, 550, 550))
                                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                    .build()
                                val adapter = view.createPrintDocumentAdapter(html.nameWithoutExtension)
                                val pfd = ParcelFileDescriptor.open(out,
                                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                                PdfPrinter.write(adapter, attrs, pfd) { success, _ ->
                                    try { pfd.close() } catch (_: Exception) {}
                                    ok = success
                                    latch.countDown()
                                    main.post { try { view.destroy() } catch (_: Exception) {} }
                                }
                            } catch (e: Throwable) {
                                latch.countDown()
                            }
                        }, 700)
                    }
                }
                wv.layout(0, 0, 1240, 1754)
                wv.loadUrl(Uri.fromFile(html).toString())
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        latch.await(240, TimeUnit.SECONDS)
        return ok && out.length() > 0
    }
}
