package com.gustavo.multilector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var recentBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        F.cleanOld(this)
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        build()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    private fun build() {
        window.statusBarColor = Pal.primary
        val sv = ScrollView(this)
        sv.setBackgroundColor(Pal.bg)
        val box = vbox()
        box.setPadding(dp(16), 0, dp(16), dp(28))
        sv.addView(box)

        val head = vbox()
        head.setBackgroundColor(Pal.primary)
        head.setPadding(dp(18), dp(18), dp(18), dp(20))
        head.addView(label("MultiLector", 26f, Color.WHITE, true))
        head.addView(label("Leé y convertí todo. Gratis, sin límites y sin internet.", 14f, Color.argb(225, 255, 255, 255)))
        val root = vbox()
        root.addView(head, lp(MATCH, WRAP))
        root.addView(sv, lp(MATCH, 0, 1f))

        box.addView(bigButton("📂  Abrir un archivo", "PDF, Word, Excel, PowerPoint, fotos, textos, ePub, ZIP, videos…", Pal.primary) { pickOpen() })
        box.addView(bigButton("🔄  Convertir a PDF", "Uno o varios archivos: Word, Excel, textos, fotos… a PDF", Pal.accent) { pickAny(REQ_CONVERT, true) })
        box.addView(bigButton("🖼  Fotos a PDF", "Van enteras: si después las sacás del PDF, salen iguales", Pal.teal) { pickImages(REQ_IMG2PDF) })
        box.addView(bigButton("📷  Escanear documentos", "Recorta, endereza y limpia la hoja. Juntá varias hojas y guardalas en PDF", Pal.orange) {
            startActivity(Intent(this, ScanActivity::class.java))
        })
        box.addView(bigButton("🖨  Imprimir", "PDF, fotos, Word, Excel… en tus impresoras HP (color o blanco y negro)", Color.parseColor("#0D47A1")) { pickAny(REQ_PRINT, true) })
        box.addView(bigButton("📤  Sacar las fotos de un PDF", "Recupera las fotos originales que tiene adentro", Pal.purple) { pickAny(REQ_EXTRACT, false, "application/pdf") })
        box.addView(bigButton("📐  Achicar / cambiar fotos", "WhatsApp, Instagram, Mercado Libre, carnet, trámites…", Pal.pink) { PhotoActivity.start(this, emptyList()) })
        box.addView(bigButton("🧰  Más herramientas PDF", "Unir, achicar, pasar páginas a fotos", Pal.slate) { moreTools() })

        box.addView(label("Recientes", 17f, Pal.primary, true).apply { setPadding(dp(2), dp(22), 0, dp(6)) })
        recentBox = vbox()
        box.addView(recentBox, lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun refreshRecents() {
        recentBox.removeAllViews()
        val list = Recents.list(this)
        if (list.isEmpty()) {
            recentBox.addView(label("Todavía no abriste archivos.", 14f, Pal.muted))
            return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT)
        list.take(15).forEach { r ->
            val row = hbox()
            row.background = rounded(Color.WHITE, dpf(12f))
            row.setPadding(dp(14), dp(10), dp(14), dp(10))
            val icon = when (F.ext(r.name)) {
                "pdf" -> "📕"; "doc", "docx", "odt", "rtf" -> "📘"; "xls", "xlsx", "ods", "csv" -> "📗"; "ppt", "pptx", "odp" -> "📙"
                "jpg", "jpeg", "png", "webp", "heic", "gif" -> "🖼"; "zip" -> "📦"; "epub" -> "📖"; "mp4", "mkv", "mov" -> "🎬"; "mp3", "m4a", "ogg", "opus" -> "🎵"
                else -> "📄"
            }
            row.addView(label(icon, 22f), lp(WRAP, WRAP).apply { marginEnd = dp(10) })
            val t = vbox()
            t.addView(label(r.name, 15f, Pal.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            t.addView(label(fmt.format(Date(r.time)), 12f, Pal.muted))
            row.addView(t, lp(0, WRAP, 1f))
            row.setOnClickListener { F.view(this, Uri.parse(r.uri)) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(r.name)
                    .setItems(arrayOf("Quitar de recientes", "Borrar toda la lista")) { _, w ->
                        if (w == 0) Recents.remove(this, r.uri) else Recents.clear(this)
                        refreshRecents()
                    }.show()
                true
            }
            recentBox.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
    }

    // ---------- elegir archivos ----------
    private fun pickOpen() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        start(i, REQ_OPEN)
    }

    private fun pickAny(req: Int, multiple: Boolean, mime: String = "*/*") {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        start(i, req)
    }

    private fun pickImages(req: Int) {
        choose("¿De dónde son las fotos?", listOf("🖼 Galería", "📂 Carpetas (Descargas, WhatsApp, Drive…)")) { w ->
            val i = if (w == 0) Intent(Intent.ACTION_GET_CONTENT) else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "image/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            start(i, req)
        }
    }

    private fun start(i: Intent, req: Int) {
        try { startActivityForResult(i, req) } catch (e: Exception) { toast("No encontré el selector de archivos del teléfono") }
    }

    private fun moreTools() {
        choose("Herramientas PDF", listOf(
            "🔗 Unir varios PDF (y fotos) en uno",
            "🗜 Achicar un PDF (que pese menos)",
            "🖼 Pasar las páginas de un PDF a fotos",
            "📄 Abrir un PDF"
        )) { w ->
            when (w) {
                0 -> pickAny(REQ_MERGE, true)
                1 -> pickAny(REQ_COMPRESS, false, "application/pdf")
                2 -> pickAny(REQ_PAGES, false, "application/pdf")
                else -> pickAny(REQ_OPEN, false, "application/pdf")
            }
        }
    }

    // ---------- cámara ----------
    private val prefs get() = getSharedPreferences("camara", Context.MODE_PRIVATE)

    private fun shots(): MutableList<String> = prefs.getString("fotos", "")!!.split('|').filter { it.isNotBlank() && File(it).length() > 0 }.toMutableList()

    private fun takePhoto() {
        val f = File(cacheDir, "cam/foto_${System.currentTimeMillis()}.jpg")
        f.parentFile?.mkdirs()
        val u = F.providerUri(this, f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        i.putExtra(MediaStore.EXTRA_OUTPUT, u)
        i.clipData = ClipData.newRawUri("", u)
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString("pendiente", f.path).apply()
        try { startActivityForResult(i, REQ_CAMERA) } catch (e: Exception) { toast("No encontré la app de cámara") }
    }

    private fun afterShot(ok: Boolean) {
        val pend = prefs.getString("pendiente", null)
        val list = shots()
        if (ok && pend != null && File(pend).length() > 0 && !list.contains(pend)) list.add(pend)
        prefs.edit().putString("fotos", list.joinToString("|")).remove("pendiente").apply()
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("📷 ${list.size} foto(s) lista(s)")
            .setMessage("¿Sacás otra o armamos el PDF?")
            .setPositiveButton("Armar PDF") { _, _ ->
                val uris = list.map { F.providerUri(this, File(it)) }
                prefs.edit().remove("fotos").apply()
                Ops.imagesToPdf(this, uris, PageMode.A4, true)
            }
            .setNeutralButton("Otra foto") { _, _ -> takePhoto() }
            .setNegativeButton("Descartar") { _, _ -> list.forEach { File(it).delete() }; prefs.edit().remove("fotos").apply() }
            .setCancelable(false)
            .show()
    }

    // ---------- resultados ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAMERA) { afterShot(resultCode == RESULT_OK); return }
        if (resultCode != RESULT_OK || data == null) return
        val uris = urisFrom(data)
        if (uris.isEmpty()) return
        for (u in uris) try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        when (requestCode) {
            REQ_OPEN -> F.view(this, uris[0])
            REQ_PRINT -> Printer.any(this, uris)
            REQ_CONVERT -> askTogether(uris) { Ops.toPdf(this, uris, it) }
            REQ_IMG2PDF -> ViewerActivity.askPhotoPdf(this, uris)
            REQ_EXTRACT -> Ops.extractImages(this, uris[0])
            REQ_MERGE -> if (uris.size < 2) toast("Elegí 2 o más archivos (mantené apretado para marcar varios)") else Ops.toPdf(this, uris, true)
            REQ_COMPRESS -> ViewerActivity.askCompress(this, uris[0])
            REQ_PAGES -> choose("Calidad de las fotos", listOf("Normal (para ver en el celu)", "Alta (para imprimir)", "PNG sin pérdida")) {
                when (it) { 0 -> Ops.pagesToImages(this, uris[0], 150, Fmt.JPG); 1 -> Ops.pagesToImages(this, uris[0], 300, Fmt.JPG); else -> Ops.pagesToImages(this, uris[0], 200, Fmt.PNG) }
            }
        }
    }

    private fun askTogether(uris: List<Uri>, go: (Boolean) -> Unit) {
        if (uris.size == 1) { go(false); return }
        choose("Elegiste ${uris.size} archivos", listOf("Un PDF por cada archivo", "Todo junto en un solo PDF")) { go(it == 1) }
    }

    // ---------- "Compartir" desde otra app ----------
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            else -> return
        }
        intent.action = null
        if (uris.isEmpty()) { toast("No llegó ningún archivo"); return }
        Thread {
            val kinds = uris.map { F.kind(this, it, F.name(this, it)) }
            runOnUiThread { sharedMenu(uris, kinds) }
        }.start()
    }

    private fun sharedMenu(uris: List<Uri>, kinds: List<Kind>) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        val allImg = kinds.all { it == Kind.IMAGE }
        val onePdf = uris.size == 1 && kinds[0] == Kind.PDF
        if (uris.size == 1) opts += "👁 Ver" to { F.view(this, uris[0]) }
        if ((uris.size == 1 && kinds[0].convertible) || allImg) opts += "🖨 Imprimir" to { Printer.any(this, uris) }
        if (allImg) opts += "🖼 Fotos a PDF (enteras)" to { ViewerActivity.askPhotoPdf(this, uris) }
        if (allImg) opts += "📐 Achicar / cambiar fotos" to { PhotoActivity.start(this, uris) }
        if (!allImg && !onePdf) opts += "🔄 Convertir a PDF" to { askTogether(uris) { Ops.toPdf(this, uris, it) } }
        if (uris.size > 1 && !allImg) opts += "🔗 Unir todo en un PDF" to { Ops.toPdf(this, uris, true) }
        if (onePdf) {
            opts += "📤 Sacar las fotos" to { Ops.extractImages(this, uris[0]) }
            opts += "🗜 Achicar el PDF" to { ViewerActivity.askCompress(this, uris[0]) }
            opts += "🖼 Páginas a fotos" to { Ops.pagesToImages(this, uris[0], 150, Fmt.JPG) }
        }
        choose(if (uris.size == 1) "¿Qué hago con este archivo?" else "¿Qué hago con estos ${uris.size} archivos?", opts.map { it.first }) { opts[it].second() }
    }

    companion object {
        const val REQ_OPEN = 1
        const val REQ_CONVERT = 2
        const val REQ_IMG2PDF = 3
        const val REQ_EXTRACT = 4
        const val REQ_MERGE = 6
        const val REQ_COMPRESS = 7
        const val REQ_PAGES = 8
        const val REQ_CAMERA = 9
        const val REQ_PRINT = 10

        fun urisFrom(data: Intent): List<Uri> {
            val out = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
            if (out.isEmpty()) data.data?.let { out.add(it) }
            return out
        }
    }
}
