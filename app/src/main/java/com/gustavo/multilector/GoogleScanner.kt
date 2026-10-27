package com.gustavo.multilector

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Escáner automático de Google (el mismo que usa Google Drive). Funciona sin internet una vez descargado.
 * Detecta la hoja con la cámara abierta, la saca sola, recorta, endereza y limpia sombras, manchas y dedos.
 * Si el teléfono no lo soporta, se usa el escáner propio de la app.
 */
object GoogleScanner {
    fun start(act: Activity, requestCode: Int, onFail: (String) -> Unit) {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(100)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(act)
                .addOnSuccessListener { sender ->
                    try {
                        act.startIntentSenderForResult(sender, requestCode, null, 0, 0, 0)
                    } catch (e: Exception) {
                        onFail(e.message ?: "error")
                    }
                }
                .addOnFailureListener { e -> onFail(e.message ?: "error") }
        } catch (e: Throwable) {
            onFail(e.message ?: "error")
        }
    }

    /** Fotos de las hojas que devolvió el escáner. */
    fun pages(data: Intent?): List<Uri> = try {
        GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.map { it.imageUri } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}
