package com.gustavo.multilector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.util.zip.ZipInputStream

/** Visor de cualquier archivo. */
class ViewerActivity : Activity() {
    private lateinit var uri: Uri
    private var name = "archivo"
    private var kind = Kind.UNKNOWN
    private lateinit var content: FrameLayout
    private lateinit var buttons: LinearLayout
    private lateinit var title: TextView
    private var pdfDoc: PdfDoc? = null
    private var pdfPane: PdfPane? = null
    private var pageLabel: TextView? = null
    private var htmlFile: File? = null
    private var htmlLandscape = false
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = intent.data ?: @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        if (u == null) { finish(); return }
        uri = u

        val root = vbox()
        root.setBackgroundColor(Pal.bg)
        val (bar, t) = topBar("Abriendo…", true)
        title = t
        root.addView(bar, lp(MATCH, WRAP))
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))
        val sc = HorizontalScrollView(this)
        sc.isHorizontalScrollBarEnabled = false
        sc.setBackgroundColor(Color.WHITE)
        buttons = hbox()
        buttons.setPadding(dp(6), dp(6), dp(6), dp(6))
        sc.addView(buttons)
        root.addView(sc, lp(MATCH, WRAP))
        setContentView(root)
        loading()

        Thread {
            name = F.name(this, uri)
            kind = F.kind(this, uri, name)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                title.text = name
                var keep = uri.authority == "media"
                if (uri.scheme == "content") try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    keep = true
                } catch (_: Exception) {}
                if (!keep) keep = contentResolver.persistedUriPermissions.any { it.uri == uri }
                if (keep) Recents.add(this, uri, name)
                show()
            }
        }.start()
    }

    private fun loading(msg: String = "Abriendo…") {
        content.removeAllViews()
        val box = vbox(); box.gravity = Gravity.CENTER
        box.addView(ProgressBar(this))
        box.addView(label(msg, 15f, Pal.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        content.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun btn(text: String, color: Int = Pal.primary, action: () -> Unit) {
        val b = smallButton(text, color, action)
        buttons.addView(b, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
    }

    private fun commonButtons() {
        btn("📤 Compartir", Pal.slate) { F.shareUris(this, arrayListOf(uri), F.mime(this, uri, name)) }
        btn("↗ Abrir con…", Pal.slate) { F.openWith(this, uri, F.mime(this, uri, name)) }
    }

    private fun show() {
        buttons.removeAllViews()
        when (kind) {
            Kind.PDF -> showPdf()
            Kind.IMAGE -> showImage()
            Kind.VIDEO, Kind.AUDIO -> showMedia()
            Kind.ZIP -> showZip()
            Kind.UNKNOWN -> showUnknown()
            else -> showDoc()
        }
    }

    // ---------- PDF ----------
    private fun showPdf() {
        Thread {
            try {
                val d = PdfDoc.open(this, uri)
                d.sizes
                runOnUiThread {
                    if (isFinishing) { d.close(); return@runOnUiThread }
                    pdfDoc = d
                    content.removeAllViews()
                    val start = Recents.lastPage(this, uri)
                    val pane = PdfPane(this, d, start) { p ->
                        pageLabel?.text = "${p + 1} / ${d.count}"
                        Recents.setLastPage(this, uri, p)
                    }
                    pdfPane = pane
                    content.addView(pane, FrameLayout.LayoutParams(MATCH, MATCH))
                    val pl = label("1 / ${d.count}", 13f, Color.WHITE, true)
                    pl.background = rounded(Color.argb(170, 0, 0, 0), dpf(12f))
                    pl.setPadding(dp(10), dp(4), dp(10), dp(4))
                    pageLabel = pl
                    content.addView(pl, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
                    btn("➖") { pane.setZoom(pane.zoom / 1.4f) }
                    btn("➕") { pane.setZoom(pane.zoom * 1.4f) }
                    btn("🖨 Imprimir", Color.parseColor("#0D47A1")) { Printer.any(this, listOf(uri)) }
                    btn("🔢 Ir a pág.") { askPage(d.count) }
                    btn("📤 Sacar fotos", Pal.purple) { Ops.extractImages(this, uri) }
                    btn("🖼 Páginas a fotos", Pal.teal) {
                        choose("Calidad de las fotos", listOf("Normal (para ver en el celu)", "Alta (para imprimir)", "PNG sin pérdida")) {
                            when (it) { 0 -> Ops.pagesToImages(this, uri, 150, Fmt.JPG); 1 -> Ops.pagesToImages(this, uri, 300, Fmt.JPG); else -> Ops.pagesToImages(this, uri, 200, Fmt.PNG) }
                        }
                    }
                    btn("🗜 Achicar", Pal.orange) { askCompress(this, uri) }
                    commonButtons()
                }
            } catch (e: Exception) {
                runOnUiThread { error(if (e is PdfLockedException) e.message!! else "No se pudo abrir el PDF: ${e.message}") }
            }
        }.start()
    }

    private fun askPage(count: Int) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1 a $count"
        AlertDialog.Builder(this).setTitle("Ir a la página").setView(et)
            .setPositiveButton("Ir") { _, _ -> et.text.toString().toIntOrNull()?.let { pdfPane?.goTo(it - 1) } }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---------- FOTOS ----------
    private fun showImage() {
        Thread {
            var anim: android.graphics.drawable.Drawable? = null
            var bmp: android.graphics.Bitmap? = null
            try {
                val e = F.ext(name)
                if (Build.VERSION.SDK_INT >= 28 && (e == "gif" || e == "webp")) {
                    val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri))
                    if (d is AnimatedImageDrawable) anim = d
                }
                if (anim == null) bmp = Img.decode(this, uri, 16_000_000)
            } catch (_: Throwable) {
            }
            val size = Img.displaySize(this, uri)
            val bytes = F.size(this, uri)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (anim == null && bmp == null) { showDoc(); return@runOnUiThread }
                content.removeAllViews()
                val iv = ZoomImageView(this)
                if (anim != null) { iv.setImageDrawable(anim); (anim as AnimatedImageDrawable).start() } else iv.setImageBitmap(bmp)
                content.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                val info = label("${size[0]}×${size[1]} · ${F.human(bytes)}", 12.5f, Color.WHITE)
                info.background = rounded(Color.argb(150, 0, 0, 0), dpf(10f))
                info.setPadding(dp(8), dp(3), dp(8), dp(3))
                content.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
                btn("🖨 Imprimir", Color.parseColor("#0D47A1")) { Printer.any(this, listOf(uri)) }
                btn("📄 A PDF", Pal.accent) { askPhotoPdf(this, listOf(uri)) }
                btn("📐 Cambiar tamaño/formato", Pal.pink) { PhotoActivity.start(this, listOf(uri)) }
                commonButtons()
            }
        }.start()
    }

    // ---------- DOCUMENTOS ----------
    private fun showDoc() {
        loading(if (kind == Kind.XLSX || kind == Kind.EPUB) "Leyendo ${kind.label}… (puede tardar un poco)" else "Abriendo…")
        Thread {
            try {
                val dir = F.work(this, "view")
                val d = DocHtml.build(this, uri, name, kind, dir)
                val f = File(dir, "index.html")
                f.writeText(DocHtml.page(name, d.body))
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    htmlFile = f; htmlLandscape = d.landscape
                    content.removeAllViews()
                    val wv = WebView(this)
                    web = wv
                    wv.settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    wv.settings.allowFileAccessFromFileURLs = true
                    wv.settings.builtInZoomControls = true
                    wv.settings.displayZoomControls = false
                    wv.settings.useWideViewPort = kind == Kind.XLSX || kind == Kind.CSV || kind == Kind.ODS
                    wv.settings.loadWithOverviewMode = wv.settings.useWideViewPort
                    wv.settings.javaScriptEnabled = false
                    wv.webViewClient = object : WebViewClient() {
                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                    }
                    wv.loadUrl(Uri.fromFile(f).toString())
                    content.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
                    btn("🖨 Imprimir", Color.parseColor("#0D47A1")) { Printer.web(this, wv, name) }
                    btn("📄 Convertir a PDF", Pal.accent) { docToPdf() }
                    btn("🔠 Letra +") { wv.settings.textZoom = (wv.settings.textZoom + 15).coerceAtMost(250) }
                    btn("🔡 Letra −") { wv.settings.textZoom = (wv.settings.textZoom - 15).coerceAtLeast(60) }
                    commonButtons()
                }
            } catch (e: Throwable) {
                runOnUiThread { error("No se pudo leer este archivo (${kind.label}).\n${e.message ?: ""}") }
            }
        }.start()
    }

    private fun docToPdf() {
        val html = htmlFile ?: return
        val outName = F.safe(F.base(name)) + ".pdf"
        Jobs.run(this, "Convirtiendo a PDF") { progress ->
            progress("Armando el PDF…")
            val tmp = File(F.work(this, "pdf"), outName)
            if (!HtmlToPdf.convert(this, html, tmp, htmlLandscape)) throw UserError("No se pudo convertir a PDF.")
            val local = F.outFile(this, outName)
            tmp.copyTo(local, true)
            val pub = F.savePublic(this, local, outName, "application/pdf")
            JobResult("¡PDF listo!", if (pub != null) "Quedó guardado en Descargas › MultiLector" else "Usá Compartir para guardarlo.", listOf(Saved(local, pub)))
        }
    }

    // ---------- VIDEO / AUDIO ----------
    private fun showMedia() {
        content.removeAllViews()
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        val vv = VideoView(this)
        frame.addView(vv, FrameLayout.LayoutParams(MATCH, if (kind == Kind.AUDIO) dp(2) else MATCH, Gravity.CENTER))
        if (kind == Kind.AUDIO) {
            frame.addView(label("🎵\n$name", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        content.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        val mc = MediaController(this)
        mc.setAnchorView(frame)
        vv.setMediaController(mc)
        vv.setVideoURI(uri)
        vv.setOnPreparedListener { vv.start(); mc.show(0) }
        vv.setOnErrorListener { _, _, _ -> error("Este ${kind.label.lowercase()} no se puede reproducir acá. Probá con \"Abrir con…\"."); commonButtons(); true }
        frame.setOnClickListener { mc.show() }
        commonButtons()
    }

    // ---------- ZIP ----------
    private fun showZip() {
        loading()
        Thread {
            val entries = ArrayList<Pair<String, Long>>()
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val z = ZipInputStream(ins.buffered())
                    while (entries.size < 3000) {
                        val e = z.nextEntry ?: break
                        if (!e.isDirectory) entries.add(e.name to e.size)
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (entries.isEmpty()) { error("El ZIP está vacío, dañado o protegido con contraseña."); commonButtons(); return@runOnUiThread }
                content.removeAllViews()
                val box = vbox()
                box.addView(label("${entries.size} archivos adentro. Tocá uno para abrirlo.", 14f, Pal.muted).apply { setPadding(dp(14), dp(10), dp(14), dp(6)) })
                val lv = ListView(this)
                lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries.map { "📄 ${it.first}" + if (it.second >= 0) "  (${F.human(it.second)})" else "" })
                lv.setOnItemClickListener { _, _, pos, _ -> openZipEntry(entries[pos].first) }
                box.addView(lv, lp(MATCH, 0, 1f))
                content.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
                btn("📦 Extraer todo", Pal.accent) { extractZip() }
                commonButtons()
            }
        }.start()
    }

    private fun openZipEntry(entry: String) {
        Thread {
            try {
                val dir = F.work(this, "zip")
                var out: File? = null
                contentResolver.openInputStream(uri)?.use { ins ->
                    val z = ZipInputStream(ins.buffered())
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.name == entry) {
                            val f = File(dir, F.safe(entry.substringAfterLast('/')))
                            f.outputStream().use { z.copyTo(it) }
                            out = f; break
                        }
                    }
                }
                runOnUiThread { out?.let { F.view(this, it) } ?: toast("No se pudo abrir") }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo abrir: ${e.message}") }
            }
        }.start()
    }

    private fun extractZip() {
        val base = F.safe(F.base(name))
        Jobs.run(this, "Extrayendo ZIP") { progress ->
            var n = 0
            val dir = F.work(this, "unz")
            contentResolver.openInputStream(uri)?.use { ins ->
                val z = ZipInputStream(ins.buffered())
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    n++
                    progress("Archivo $n")
                    val fn = F.safe(e.name.substringAfterLast('/'))
                    val f = File(dir, fn)
                    f.outputStream().use { z.copyTo(it) }
                    val sub = (base + "/" + e.name.substringBeforeLast('/', "")).trimEnd('/')
                    val mime = F.mimeFromExt(F.ext(fn))
                    F.savePublic(this, f, fn, mime, sub, toDownloads = true)
                    f.delete()
                }
            }
            JobResult("¡Listo!", "Se extrajeron $n archivo(s) en Descargas › MultiLector › $base")
        }
    }

    // ---------- OTROS ----------
    private fun showUnknown() {
        content.removeAllViews()
        val box = vbox(); box.gravity = Gravity.CENTER; box.setPadding(dp(24), dp(24), dp(24), dp(24))
        box.addView(label("📁", 54f).apply { gravity = Gravity.CENTER })
        box.addView(label(name, 17f, Pal.text, true).apply { gravity = Gravity.CENTER })
        box.addView(label("${F.mime(this, uri, name)} · ${F.human(F.size(this, uri))}", 14f, Pal.muted).apply { gravity = Gravity.CENTER })
        box.addView(label("\nEste tipo de archivo no lo puedo mostrar. Podés abrirlo con otra app o verlo como texto.", 15f, Pal.text).apply { gravity = Gravity.CENTER })
        content.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        btn("🔤 Ver como texto") { kind = Kind.TEXT; buttons.removeAllViews(); showDoc() }
        commonButtons()
    }

    private fun error(msg: String) {
        content.removeAllViews()
        val t = label("⚠️\n\n$msg", 16f, Pal.text)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(24), dp(24), dp(24), dp(24))
        content.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        if (buttons.childCount == 0) commonButtons()
    }

    override fun onDestroy() {
        pdfPane?.close()
        try { pdfDoc?.close() } catch (_: Exception) {}
        web?.destroy()
        super.onDestroy()
    }

    companion object {
        fun askCompress(act: Activity, uri: Uri) {
            act.choose("¿Cuánto achicarlo?", listOf(
                "Fuerte (lo más liviano, para mandar por WhatsApp o mail)",
                "Media (buen equilibrio)",
                "Suave (casi no se nota)"
            )) { Ops.compress(act, uri, it) }
        }

        fun askPhotoPdf(act: Activity, uris: List<Uri>) {
            act.choose("¿Cómo querés las hojas?", listOf(
                "Hoja A4 (para imprimir)",
                "Del tamaño de cada foto (sin bordes)"
            )) { Ops.imagesToPdf(act, uris, if (it == 0) PageMode.A4 else PageMode.PHOTO, true) }
        }
    }
}
