package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/**
 * Truco conocido: las "callbacks" de impresión solo se pueden crear desde este paquete.
 * Así una WebView escribe el PDF directo al archivo, sin abrir el diálogo de imprimir.
 */
object PdfPrinter {
    fun write(adapter: PrintDocumentAdapter, attrs: PrintAttributes, out: ParcelFileDescriptor, done: (Boolean, String?) -> Unit) {
        try {
            adapter.onStart()
            adapter.onLayout(null, attrs, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    adapter.onWrite(arrayOf(PageRange.ALL_PAGES), out, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>?) {
                            try { adapter.onFinish() } catch (_: Exception) {}
                            done(true, null)
                        }

                        override fun onWriteFailed(error: CharSequence?) = done(false, error?.toString())
                        override fun onWriteCancelled() = done(false, "cancelado")
                    })
                }

                override fun onLayoutFailed(error: CharSequence?) = done(false, error?.toString())
                override fun onLayoutCancelled() = done(false, "cancelado")
            }, null)
        } catch (e: Throwable) {
            done(false, e.toString())
        }
    }
}
