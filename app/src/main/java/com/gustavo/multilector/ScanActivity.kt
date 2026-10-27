package com.gustavo.multilector

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Escáner de documentos.
 * 1) Usa el escáner automático de Google si el teléfono lo tiene; si no, la cámara con retoque automático propio.
 * 2) Las hojas se juntan en una "carpeta" hasta que tocás Guardar PDF.
 * 3) Cada hoja se puede retocar cuando quieras, con deshacer, rehacer y volver al original.
 */
class ScanActivity : Activity() {

    /** src = foto sin tocar, auto = retoque automático (para "volver al original"), edit = retoque actual, out = hoja terminada */
    class Page(val src: String, var auto: Edit, var edit: Edit, var out: String)

    private val pages = ArrayList<Page>()
    private lateinit var dir: File
    private lateinit var root: FrameLayout
    private lateinit var countLabel: TextView
    private var editor: View? = null
    private var defaultFilter = ScanFilter.ENHANCED
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(cacheDir, "scan/actual").apply { mkdirs() }
        load()
        buildMain()
        if (pages.isEmpty() && savedInstanceState == null && prefs.getString("pendiente", null) == null) scanAuto()
    }

    // ---------------- estado guardado (si Android cierra la app mientras está la cámara, no se pierde nada) ----------------
    private val prefs get() = getSharedPreferences("escaneo", Context.MODE_PRIVATE)

    private fun save() {
        val a = JSONArray()
        pages.forEach { p -> a.put(JSONObject().put("src", p.src).put("auto", p.auto.toJson()).put("edit", p.edit.toJson()).put("out", p.out)) }
        prefs.edit().putString("hojas2", a.toString()).putString("filtro", defaultFilter.name).apply()
    }

    private fun load() {
        pages.clear()
        try {
            defaultFilter = ScanFilter.valueOf(prefs.getString("filtro", ScanFilter.ENHANCED.name)!!)
            val a = JSONArray(prefs.getString("hojas2", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val p = Page(o.getString("src"), Edit.fromJson(o.getJSONObject("auto")), Edit.fromJson(o.getJSONObject("edit")), o.getString("out"))
                if (File(p.src).exists() && File(p.out).exists()) pages.add(p)
            }
        } catch (_: Exception) {
        }
    }

    private fun clearAll() {
        pages.clear()
        thumbs.clear()
        dir.deleteRecursively(); dir.mkdirs()
        prefs.edit().remove("hojas2").remove("pendiente").apply()
    }

    // ---------------- pantalla principal: la carpeta con las hojas ----------------
    private fun buildMain() {
        root = FrameLayout(this)
        val box = vbox()
        box.setBackgroundColor(Pal.bg)
        box.addView(topBar("Escanear documentos", true).first, lp(MATCH, WRAP))
        countLabel = label("", 15f, Pal.muted)
        countLabel.setPadding(dp(14), dp(10), dp(14), dp(4))
        box.addView(countLabel)
        val grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = dp(8); grid.verticalSpacing = dp(8)
        grid.setPadding(dp(10), dp(6), dp(10), dp(10))
        grid.clipToPadding = false
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> openEditor(pos) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> pageMenu(pos); true }
        box.addView(grid, lp(MATCH, 0, 1f))

        val sc = HorizontalScrollView(this)
        sc.setBackgroundColor(Color.WHITE)
        val bar = hbox()
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        fun b(t: String, c: Int, a: () -> Unit) = bar.addView(smallButton(t, c, a), lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        b("📷 Escanear más", Pal.orange) { scanAuto() }
        b("💾 Guardar PDF", Pal.accent) { askSave() }
        b("🖨 Imprimir", Color.parseColor("#0D47A1")) { printNow() }
        b("🎨 Filtro a todas", Pal.purple) { filterAll() }
        b("⟲ Todas al original", Pal.slate) { resetAll() }
        b("📸 Cámara simple", Pal.slate) { camera() }
        b("🖼 Desde galería", Pal.teal) { gallery() }
        b("🗑 Empezar de nuevo", Pal.red) { askClear() }
        sc.addView(bar)
        box.addView(sc, lp(MATCH, WRAP))
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        refresh()
    }

    private val adapter = object : android.widget.BaseAdapter() {
        override fun getCount() = pages.size
        override fun getItem(p: Int) = pages[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, convert: View?, parent: android.view.ViewGroup?): View {
            val cell = (convert as? FrameLayout) ?: FrameLayout(this@ScanActivity).apply {
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) },
                    FrameLayout.LayoutParams(MATCH, MATCH))
                addView(label("", 13f, Color.WHITE, true).apply {
                    background = rounded(Color.argb(180, 0, 0, 0), dpf(10f)); setPadding(dp(8), dp(2), dp(8), dp(2))
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
                background = rounded(Pal.line, dpf(6f))
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, dp(160))
            }
            (cell.getChildAt(1) as TextView).text = "${pos + 1}"
            (cell.getChildAt(0) as ImageView).setImageBitmap(thumb(pages[pos].out))
            return cell
        }
    }

    private val thumbs = HashMap<String, Bitmap>()
    private fun thumb(path: String): Bitmap? {
        thumbs[path]?.let { return it }
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
        thumbs[path] = b
        return b
    }

    private fun refresh() {
        countLabel.text = when (pages.size) {
            0 -> "Todavía no hay hojas. Tocá \"📷 Escanear más\"."
            1 -> "1 hoja. Tocala para retocarla · Mantené apretado para mover o borrar."
            else -> "${pages.size} hojas. Tocá una para retocarla · Mantené apretado para mover o borrar."
        }
        adapter.notifyDataSetChanged()
        save()
    }

    private fun pageMenu(pos: Int) {
        val opts = listOf("✏️ Retocar", "⟲ Volver al original (deshacer retoques)", "⬅️ Mover antes", "➡️ Mover después",
            "📑 Duplicar", "🔍 Ver grande", "🗑 Borrar hoja")
        choose("Hoja ${pos + 1}", opts) { w ->
            when (w) {
                0 -> openEditor(pos)
                1 -> { val p = pages[pos]; p.edit = p.auto; work("Volviendo al original…") { render(p); runOnUiThread { refresh() } } }
                2 -> if (pos > 0) { val p = pages.removeAt(pos); pages.add(pos - 1, p); refresh() }
                3 -> if (pos < pages.size - 1) { val p = pages.removeAt(pos); pages.add(pos + 1, p); refresh() }
                4 -> work("Duplicando…") {
                    val p = pages[pos]
                    val out = File(dir, "hoja_${System.currentTimeMillis()}.jpg")
                    File(p.out).copyTo(out, true)
                    runOnUiThread { pages.add(pos + 1, Page(p.src, p.auto, p.edit, out.path)); refresh() }
                }
                5 -> F.view(this, File(pages[pos].out))
                6 -> AlertDialog.Builder(this).setTitle("¿Borrar la hoja ${pos + 1}?")
                    .setPositiveButton("Borrar") { _, _ ->
                        val p = pages.removeAt(pos); File(p.out).delete(); thumbs.remove(p.out)
                        if (pages.none { it.src == p.src }) File(p.src).delete()
                        refresh()
                    }.setNegativeButton("No", null).show()
            }
        }
    }

    private fun filterAll() {
        if (pages.isEmpty()) return
        val fs = ScanFilter.values()
        choose("Filtro para todas las hojas", fs.map { "${it.label}: ${it.desc}" }) { i ->
            defaultFilter = fs[i]
            work("Aplicando a ${pages.size} hojas…") {
                pages.forEach { p -> p.edit = p.edit.copy(filter = fs[i]); render(p) }
                runOnUiThread { refresh() }
            }
        }
    }

    private fun resetAll() {
        if (pages.isEmpty()) return
        work("Volviendo todas al original…") {
            pages.forEach { p -> p.edit = p.auto; render(p) }
            runOnUiThread { refresh() }
        }
    }

    // ---------------- procesar ----------------
    private fun loadSrc(path: String, maxSide: Int = 0): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        if (maxSide > 0) while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
        else while (o.outWidth.toLong() / s * (o.outHeight.toLong() / s) > 13_000_000L) s *= 2
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val ori = try { android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }
        return Img.rotate(b, ori)
    }

    /** Arma la hoja final con todos sus retoques y la guarda como JPG. */
    private fun render(p: Page) {
        val src = loadSrc(p.src) ?: return
        val out = Scan.process(src, p.edit)
        src.recycle()
        val bw = p.edit.filter == ScanFilter.DOCUMENT || p.edit.filter == ScanFilter.INK
        File(p.out).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, if (bw) 85 else 90, it) }
        out.recycle()
        thumbs.remove(p.out)
    }

    private fun work(msg: String, job: () -> Unit) {
        if (busy) return
        busy = true
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(150, 0, 0, 0))
        overlay.isClickable = true
        val b = vbox(); b.gravity = Gravity.CENTER
        b.addView(ProgressBar(this))
        b.addView(label(msg, 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(10), dp(20), 0) })
        overlay.addView(b, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        Thread {
            try { job() } catch (e: Throwable) {
                runOnUiThread { alert("Uy", if (e is OutOfMemoryError) "La foto es demasiado grande para la memoria del teléfono." else "No se pudo procesar: ${e.message}") }
            }
            runOnUiThread { root.removeView(overlay); busy = false }
        }.start()
    }

    // ---------------- escanear: automático de Google, cámara simple o galería ----------------
    private fun scanAuto() {
        GoogleScanner.start(this, REQ_GOOGLE) { _ ->
            runOnUiThread {
                toast("Este teléfono no tiene el escáner automático de Google. Uso la cámara con retoque automático.")
                camera()
            }
        }
    }

    private fun camera() {
        val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
        val u = F.providerUri(this, f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        i.putExtra(MediaStore.EXTRA_OUTPUT, u)
        i.clipData = ClipData.newRawUri("", u)
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString("pendiente", f.path).apply()
        try { startActivityForResult(i, REQ_CAM) } catch (e: Exception) { toast("No encontré la app de cámara") }
    }

    private fun gallery() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_GAL) } catch (e: Exception) { toast("No hay app para elegir fotos") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_GOOGLE -> if (resultCode == RESULT_OK) {
                val uris = GoogleScanner.pages(data)
                work("Guardando ${uris.size} hoja(s)…") {
                    val files = uris.mapIndexedNotNull { k, u ->
                        try { F.copyTo(this, u, File(dir, "g_${System.currentTimeMillis()}_$k.jpg")).path } catch (_: Exception) { null }
                    }
                    // Google ya recortó y limpió: no se vuelve a aclarar; solo se endereza el texto si quedó torcido.
                    addFiles(files, detect = false, filter = ScanFilter.ORIGINAL)
                    runOnUiThread { refresh(); if (files.isNotEmpty()) toast("Listo: ${files.size} hoja(s). Tocá una si querés retocarla.") }
                }
            }
            REQ_CAM -> {
                val pend = prefs.getString("pendiente", null)
                prefs.edit().remove("pendiente").apply()
                if (resultCode == RESULT_OK && pend != null && File(pend).length() > 0) {
                    work("Recortando, enderezando y limpiando…") {
                        addFiles(listOf(pend), detect = true, filter = defaultFilter)
                        runOnUiThread {
                            refresh()
                            AlertDialog.Builder(this).setTitle("✅ Hoja ${pages.size} lista")
                                .setMessage("Se retocó sola. ¿Seguís con otra hoja?")
                                .setPositiveButton("📷 Otra hoja") { _, _ -> camera() }
                                .setNeutralButton("✏️ Retocar esta") { _, _ -> openEditor(pages.size - 1) }
                                .setNegativeButton("Listo", null).show()
                        }
                    }
                } else refresh()
            }
            REQ_GAL -> if (resultCode == RESULT_OK && data != null) {
                val uris = MainActivity.urisFrom(data)
                work("Recortando y limpiando ${uris.size} foto(s)…") {
                    val files = uris.mapIndexedNotNull { k, u ->
                        try { F.copyTo(this, u, File(dir, "gal_${System.currentTimeMillis()}_$k.jpg")).path } catch (_: Exception) { null }
                    }
                    addFiles(files, detect = true, filter = defaultFilter)
                    runOnUiThread { refresh() }
                }
            }
        }
    }

    /** Agrega hojas con retoque automático. Corre en segundo plano. */
    private fun addFiles(files: List<String>, detect: Boolean, filter: ScanFilter) {
        for (path in files) {
            val small = loadSrc(path, 1600) ?: continue
            val q = if (detect) Scan.detect(small) else Scan.fullQuad(0f)
            // enderezado automático: mide lo torcido de los renglones después de recortar
            val skew = if (detect) Scan.textSkew(Scan.warp(small, q, 1200f)) else Scan.textSkew(small)
            small.recycle()
            val auto = Edit(q, angle = -skew, filter = filter, sharp = if (filter == ScanFilter.ORIGINAL) 0 else 25)
            val p = Page(path, auto, auto, File(dir, "hoja_${System.nanoTime()}.jpg").path)
            render(p)
            runOnUiThread { pages.add(p) }
        }
    }

    // ---------------- editor de retoques ----------------
    private fun openEditor(pos: Int) {
        val p = pages.getOrNull(pos) ?: return
        val preview = try { loadSrc(p.src, 1400) } catch (_: Throwable) { null } ?: return
        PageEditor(this, p, preview, pos, pages.size,
            onDone = { e ->
                closeEditor()
                p.edit = e
                work("Aplicando retoques a la hoja ${pos + 1}…") { render(p); runOnUiThread { refresh() } }
            },
            onCancel = { closeEditor() }
        ).also {
            editor = it
            root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun closeEditor() {
        (editor as? PageEditor)?.dispose()
        editor?.let { root.removeView(it) }
        editor = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val e = editor as? PageEditor
        if (e != null) { e.askClose(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------- guardar / imprimir ----------------
    private fun buildPdf(out: File) {
        PdfImageWriter(out, PageMode.A4).use { w ->
            for (p in pages) w.addJpeg(File(p.out).readBytes(), null, 1)
        }
    }

    private fun printNow() {
        if (pages.isEmpty()) { toast("Primero escaneá alguna hoja"); return }
        work("Preparando impresión…") {
            val f = File(F.work(this, "print"), "escaneo.pdf")
            buildPdf(f)
            runOnUiThread { Printer.start(this, f, "Escaneo") }
        }
    }

    private fun rememberFolder(f: String) {
        val sp = getSharedPreferences("escaneo_carpetas", Context.MODE_PRIVATE)
        val list = (listOf(f) + sp.getString("lista", "General")!!.split('|')).filter { it.isNotBlank() }.distinct().take(30)
        sp.edit().putString("lista", list.joinToString("|")).apply()
    }

    private fun askSave() {
        if (pages.isEmpty()) { toast("Primero escaneá alguna hoja"); return }
        val box = vbox(); box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(label("Nombre del documento", 13f, Pal.muted))
        val name = EditText(this)
        name.setText("Escaneo " + SimpleDateFormat("dd-MM-yyyy HHmm", Locale.ROOT).format(Date()))
        name.setSelectAllOnFocus(true)
        box.addView(name)
        box.addView(label("Carpeta (dentro de Descargas › MultiLector › Escaneos)", 13f, Pal.muted).apply { setPadding(0, dp(12), 0, 0) })
        val existing = getSharedPreferences("escaneo_carpetas", Context.MODE_PRIVATE).getString("lista", "General")!!
            .split('|').filter { it.isNotBlank() }
        val options = existing + "➕ Carpeta nueva…"
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        box.addView(sp)
        val newFolder = EditText(this)
        newFolder.hint = "Nombre de la carpeta nueva (ej: Facturas, Demum, Impuestos)"
        newFolder.visibility = View.GONE
        box.addView(newFolder)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(pa: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                newFolder.visibility = if (pos == options.size - 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(pa: android.widget.AdapterView<*>?) {}
        }
        AlertDialog.Builder(this).setTitle("Guardar ${pages.size} hoja(s) en PDF").setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val folder = if (sp.selectedItemPosition == options.size - 1) newFolder.text.toString().trim().ifBlank { "General" }
                else options[sp.selectedItemPosition]
                doSave(name.text.toString().trim().ifBlank { "Escaneo" }, F.safe(folder))
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun doSave(name: String, folder: String) {
        rememberFolder(folder)
        val fileName = F.safe(name) + ".pdf"
        val act = this
        Jobs.run(this, "Guardando PDF") { progress ->
            progress("Armando el PDF con ${pages.size} hoja(s)…")
            val local = F.outFile(act, fileName)
            buildPdf(local)
            val pub = F.savePublic(act, local, fileName, "application/pdf", "Escaneos/$folder")
            runOnUiThread { clearAll(); refresh() }
            JobResult("¡Escaneo guardado!",
                "${fileName}\n${F.human(local.length())}\n\n" +
                    (if (pub != null) "Quedó en Descargas › MultiLector › Escaneos › $folder" else "Usá Compartir para guardarlo donde quieras."),
                listOf(Saved(local, pub)))
        }
    }

    private fun askClear() {
        if (pages.isEmpty()) return
        AlertDialog.Builder(this).setTitle("¿Empezar de nuevo?")
            .setMessage("Se borran las ${pages.size} hoja(s) que todavía no guardaste.")
            .setPositiveButton("Borrar todo") { _, _ -> clearAll(); refresh() }
            .setNegativeButton("No", null).show()
    }

    companion object {
        const val REQ_CAM = 31
        const val REQ_GAL = 32
        const val REQ_GOOGLE = 33
    }
}

/**
 * Editor de una hoja: vista previa en vivo, recorte, filtros, ajustes, giro,
 * con Deshacer / Rehacer / Volver al original / Auto.
 */
@SuppressLint("ViewConstructor")
class PageEditor(
    private val act: ScanActivity,
    private val page: ScanActivity.Page,
    private val src: Bitmap,
    pos: Int,
    total: Int,
    private val onDone: (Edit) -> Unit,
    private val onCancel: () -> Unit
) : LinearLayout(act) {

    private var cur: Edit = page.edit
    private val undo = ArrayList<Edit>()
    private val redo = ArrayList<Edit>()
    private val previewView = ImageView(act)
    private val stage = FrameLayout(act)
    private var crop: CropView? = null
    private val panel = FrameLayout(act)
    private val tabs = ArrayList<TextView>()
    private var tab = 1
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0
    private var shown: Bitmap? = null
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private val spinner = ProgressBar(act)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1B1B1B"))
        isClickable = true

        // barra de arriba: título + deshacer/rehacer/original/auto
        val top = act.hbox()
        top.setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(6))
        top.addView(act.label("Hoja ${pos + 1} de $total", 15f, Color.WHITE, true), lp(0, WRAP, 1f))
        undoBtn = icon("↶ Deshacer") { doUndo() }
        redoBtn = icon("↷ Rehacer") { doRedo() }
        top.addView(undoBtn); top.addView(redoBtn)
        addView(top, lp(MATCH, WRAP))
        val top2 = act.hbox()
        top2.setPadding(act.dp(8), 0, act.dp(8), act.dp(6))
        top2.addView(icon("⟲ Volver al original") { push(page.auto); toast("Volvió al retoque automático") })
        top2.addView(icon("📷 Sin retoques") { push(cur.copy(filter = ScanFilter.ORIGINAL, bright = 0, contrast = 0, sat = 0, sharp = 0, angle = 0f, mirror = false)) })
        top2.addView(icon("✨ Auto") { autoAgain() })
        addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(top2) }, lp(MATCH, WRAP))

        previewView.scaleType = ImageView.ScaleType.FIT_CENTER
        stage.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        stage.addView(spinner, FrameLayout.LayoutParams(act.dp(40), act.dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(8), act.dp(8), 0) })
        addView(stage, lp(MATCH, 0, 1f))

        // pestañas
        val tabBar = act.hbox()
        tabBar.setBackgroundColor(Color.parseColor("#2A2A2A"))
        listOf("✂️ Recorte", "🎨 Filtros", "☀️ Ajustes", "🔄 Girar").forEachIndexed { i, t ->
            val tv = act.label(t, 14f, Color.WHITE, true)
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, act.dp(12), 0, act.dp(12))
            tv.setOnClickListener { selectTab(i) }
            tabs += tv
            tabBar.addView(tv, lp(0, WRAP, 1f))
        }
        addView(tabBar, lp(MATCH, WRAP))
        panel.setBackgroundColor(Color.parseColor("#2A2A2A"))
        addView(panel, lp(MATCH, act.dp(170)))

        // abajo: listo / cancelar
        val bottom = act.hbox()
        bottom.setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
        bottom.addView(act.smallButton("✖ Cancelar", Pal.red) { askClose() }, lp(0, WRAP, 1f).apply { marginEnd = act.dp(8) })
        bottom.addView(act.smallButton("✅ Listo", Pal.accent) { finish() }, lp(0, WRAP, 1.4f))
        addView(bottom, lp(MATCH, WRAP))

        selectTab(1)
        updateUndo()
        refreshPreview()
    }

    private fun icon(t: String, a: () -> Unit) = act.label(t, 14f, Color.WHITE, true).apply {
        background = rounded(Color.parseColor("#3A3A3A"), act.dpf(10f))
        setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        setOnClickListener { a() }
        layoutParams = lp(WRAP, WRAP).apply { marginStart = act.dp(6) }
    }

    private fun toast(s: String) = act.toast(s)

    // ---------- historial ----------
    private fun push(e: Edit) {
        if (e == cur) return
        undo.add(cur); if (undo.size > 60) undo.removeAt(0)
        redo.clear()
        cur = e
        afterChange()
    }

    private fun doUndo() {
        if (undo.isEmpty()) { toast("No hay nada para deshacer"); return }
        redo.add(cur); cur = undo.removeAt(undo.size - 1); afterChange(); selectTab(tab)
    }

    private fun doRedo() {
        if (redo.isEmpty()) { toast("No hay nada para rehacer"); return }
        undo.add(cur); cur = redo.removeAt(redo.size - 1); afterChange(); selectTab(tab)
    }

    private fun afterChange() {
        updateUndo()
        crop?.setQuad(cur.quad)
        refreshPreview()
    }

    private fun updateUndo() {
        undoBtn.alpha = if (undo.isEmpty()) 0.4f else 1f
        redoBtn.alpha = if (redo.isEmpty()) 0.4f else 1f
    }

    private fun autoAgain() {
        spinner.visibility = View.VISIBLE
        exec.execute {
            val q = Scan.detect(src)
            val skew = Scan.textSkew(Scan.warp(src, q, 1200f))
            main.post {
                spinner.visibility = View.GONE
                push(cur.copy(quad = q, filter = if (cur.filter == ScanFilter.ORIGINAL) ScanFilter.ENHANCED else cur.filter,
                    bright = 0, contrast = 0, sat = 0, sharp = 25, shadows = true, angle = -skew, rot = 0, mirror = false))
                toast("Retoque automático aplicado")
            }
        }
    }

    // ---------- vista previa en vivo ----------
    private fun refreshPreview() {
        if (tab == 0) return
        val g = ++gen
        val e = cur
        spinner.visibility = View.VISIBLE
        pendingPreview?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (g != gen) return@Runnable
            exec.execute {
                if (g != gen) return@execute
                val b = try { Scan.process(src, e, 1400f) } catch (_: Throwable) { null }
                main.post {
                    if (g != gen || b == null) { b?.recycle(); return@post }
                    previewView.setImageBitmap(b)
                    shown?.recycle()
                    shown = b
                    spinner.visibility = View.GONE
                }
            }
        }
        pendingPreview = r
        main.postDelayed(r, 120)
    }
    private var pendingPreview: Runnable? = null

    // ---------- pestañas ----------
    private fun selectTab(i: Int) {
        val prev = tab
        tab = i
        tabs.forEachIndexed { k, t -> t.setBackgroundColor(if (k == i) Color.parseColor("#2E7D32") else Color.TRANSPARENT) }
        panel.removeAllViews()
        if (i == 0) showCrop() else {
            if (prev == 0) { crop?.let { stage.removeView(it) }; crop = null; previewView.visibility = View.VISIBLE }
            refreshPreview()
        }
        when (i) {
            0 -> cropPanel()
            1 -> filterPanel()
            2 -> adjustPanel()
            3 -> rotatePanel()
        }
    }

    private fun showCrop() {
        gen++
        spinner.visibility = View.GONE
        previewView.visibility = View.GONE
        if (crop == null) {
            val c = CropView(act, src, cur.quad.copyOf()) { q -> push(cur.copy(quad = q)) }
            crop = c
            stage.addView(c, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        } else crop?.setQuad(cur.quad)
    }

    private fun row(): LinearLayout = act.hbox().apply { setPadding(act.dp(8), act.dp(10), act.dp(8), act.dp(10)) }

    private fun panelScroll(content: View) {
        val sv = ScrollView(act); sv.addView(content); panel.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun cropPanel() {
        val box = act.vbox()
        box.addView(act.label("Arrastrá los 4 puntos a las esquinas de la hoja. Aparece una lupa para acertar.", 13.5f, Color.LTGRAY).apply { setPadding(act.dp(12), act.dp(10), act.dp(12), 0) })
        val r = row()
        r.addView(act.smallButton("🎯 Detectar hoja", Pal.slate) { spinner.visibility = View.VISIBLE; exec.execute { val q = Scan.detect(src); main.post { spinner.visibility = View.GONE; push(cur.copy(quad = q)) } } }, lp(0, WRAP, 1f).apply { marginEnd = act.dp(6) })
        r.addView(act.smallButton("🔲 Toda la foto", Pal.slate) { push(cur.copy(quad = Scan.fullQuad(0f))) }, lp(0, WRAP, 1f).apply { marginEnd = act.dp(6) })
        r.addView(act.smallButton("👁 Ver resultado", Pal.accent) { selectTab(1) }, lp(0, WRAP, 1f))
        box.addView(r)
        panelScroll(box)
    }

    private fun filterPanel() {
        val sc = HorizontalScrollView(act)
        val r = row()
        ScanFilter.values().forEach { f ->
            val sel = f == cur.filter
            val chip = act.vbox()
            chip.background = rounded(if (sel) Pal.accent else Color.parseColor("#3A3A3A"), act.dpf(12f))
            chip.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
            chip.addView(act.label(f.label, 15f, Color.WHITE, true))
            chip.addView(act.label(f.desc, 12f, Color.argb(210, 255, 255, 255)).apply { maxWidth = act.dp(150) })
            chip.setOnClickListener { push(cur.copy(filter = f, sharp = if (f == ScanFilter.ORIGINAL) 0 else maxOf(cur.sharp, 20))); filterPanelRefresh() }
            r.addView(chip, lp(act.dp(170), MATCH).apply { marginEnd = act.dp(8) })
        }
        sc.addView(r)
        panel.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun filterPanelRefresh() { panel.removeAllViews(); filterPanel() }

    private fun slider(box: LinearLayout, name: String, value: Int, min: Int, max: Int, set: (Int) -> Edit) {
        val r = act.hbox(); r.setPadding(act.dp(12), act.dp(2), act.dp(8), act.dp(2))
        val l = act.label("$name: $value", 13.5f, Color.WHITE)
        r.addView(l, lp(act.dp(120), WRAP))
        val sb = SeekBar(act)
        sb.max = max - min
        sb.progress = value - min
        var live = cur
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                if (!user) return
                l.text = "$name: ${p + min}"
                // vista previa mientras se mueve, sin llenar el historial
                live = set(p + min)
                val saved = cur; cur = live; refreshPreview(); cur = saved
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { push(set(sb.progress + min)) }
        })
        r.addView(sb, lp(0, WRAP, 1f))
        box.addView(r)
    }

    private fun adjustPanel() {
        val box = act.vbox()
        slider(box, "☀️ Brillo", cur.bright, -100, 100) { cur.copy(bright = it) }
        slider(box, "◐ Contraste", cur.contrast, -100, 100) { cur.copy(contrast = it) }
        slider(box, "🎨 Color", cur.sat, -100, 100) { cur.copy(sat = it) }
        slider(box, "🔍 Nitidez", cur.sharp, 0, 100) { cur.copy(sharp = it) }
        val cb = CheckBox(act)
        cb.text = "Quitar sombras y manchas de luz"
        cb.setTextColor(Color.WHITE)
        cb.isChecked = cur.shadows
        cb.setOnCheckedChangeListener { _, v -> push(cur.copy(shadows = v)) }
        box.addView(cb, lp(WRAP, WRAP).apply { marginStart = act.dp(8) })
        val r = row()
        r.addView(act.smallButton("↺ Ajustes a cero", Pal.slate) { push(cur.copy(bright = 0, contrast = 0, sat = 0, sharp = 30)); panel.removeAllViews(); adjustPanel() })
        box.addView(r)
        panelScroll(box)
    }

    private fun rotatePanel() {
        val box = act.vbox()
        val r = row()
        r.addView(act.smallButton("⟲ 90° izq.", Pal.slate) { push(cur.copy(rot = (cur.rot + 270) % 360)) }, lp(0, WRAP, 1f).apply { marginEnd = act.dp(6) })
        r.addView(act.smallButton("⟳ 90° der.", Pal.slate) { push(cur.copy(rot = (cur.rot + 90) % 360)) }, lp(0, WRAP, 1f).apply { marginEnd = act.dp(6) })
        r.addView(act.smallButton("⇋ Espejo", Pal.slate) { push(cur.copy(mirror = !cur.mirror)) }, lp(0, WRAP, 1f))
        box.addView(r)
        // enderezado fino en medios grados
        val rr = act.hbox(); rr.setPadding(act.dp(12), act.dp(2), act.dp(8), act.dp(2))
        val l = act.label("📐 Enderezar: ${fmt(cur.angle)}°", 13.5f, Color.WHITE)
        rr.addView(l, lp(act.dp(150), WRAP))
        val sb = SeekBar(act)
        sb.max = 60
        sb.progress = (cur.angle * 2).toInt() + 30
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                if (!user) return
                val a = (p - 30) / 2f
                l.text = "📐 Enderezar: ${fmt(a)}°"
                val saved = cur; cur = cur.copy(angle = a); refreshPreview(); cur = saved
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { push(cur.copy(angle = (sb.progress - 30) / 2f)) }
        })
        rr.addView(sb, lp(0, WRAP, 1f))
        box.addView(rr)
        val r2 = row()
        r2.addView(act.smallButton("📐 Enderezar solo (según el texto)", Pal.accent) {
            spinner.visibility = View.VISIBLE
            exec.execute {
                val sk = Scan.textSkew(Scan.rotate(Scan.warp(src, cur.quad, 1200f), cur.rot))
                main.post {
                    spinner.visibility = View.GONE
                    if (sk == 0f) toast("Ya está derecho (o no encontré renglones de texto)") else { push(cur.copy(angle = -sk)); panel.removeAllViews(); rotatePanel() }
                }
            }
        }, lp(MATCH, WRAP))
        box.addView(r2)
        box.addView(act.label("Usá Enderezar si las líneas del texto quedaron un poco torcidas.", 12.5f, Color.LTGRAY).apply { setPadding(act.dp(12), act.dp(4), act.dp(12), 0) })
        panelScroll(box)
    }

    private fun fmt(a: Float) = if (a == Math.floor(a.toDouble()).toFloat()) a.toInt().toString() else String.format(Locale.ROOT, "%.1f", a)

    // ---------- salir ----------
    private fun finish() { onDone(cur) }

    fun askClose() {
        if (cur == page.edit) { onCancel(); return }
        AlertDialog.Builder(act).setTitle("¿Salir del retoque?")
            .setMessage("Hiciste cambios en esta hoja.")
            .setPositiveButton("✅ Guardar cambios") { _, _ -> finish() }
            .setNeutralButton("Descartar") { _, _ -> onCancel() }
            .setNegativeButton("Seguir editando", null).show()
    }

    fun dispose() {
        gen++
        exec.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}

/** Foto con 4 puntos para marcar las esquinas de la hoja (con lupa). */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CropView(ctx: Context, private val bmp: Bitmap, q: FloatArray, private val onChanged: (FloatArray) -> Unit = {}) : View(ctx) {
    private var quad: FloatArray = q
    private val img = RectF()
    private var drag = -1
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50"); strokeWidth = ctx.dpf(3f); style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 76, 175, 80) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E7D32"); style = Paint.Style.STROKE; strokeWidth = ctx.dpf(3f) }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setQuad(q: FloatArray) { quad = q.copyOf(); invalidate() }

    override fun onDraw(c: Canvas) {
        val pad = context.dpf(24f)
        val s = minOf((width - 2 * pad) / bmp.width, (height - 2 * pad) / bmp.height)
        val w = bmp.width * s; val h = bmp.height * s
        img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawBitmap(bmp, null, img, bmpPaint)
        val path = Path()
        for (i in 0 until 4) {
            val x = img.left + quad[i * 2] * img.width(); val y = img.top + quad[i * 2 + 1] * img.height()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, fillPaint)
        c.drawPath(path, linePaint)
        val r = context.dpf(13f)
        for (i in 0 until 4) {
            val x = img.left + quad[i * 2] * img.width(); val y = img.top + quad[i * 2 + 1] * img.height()
            c.drawCircle(x, y, r, dot); c.drawCircle(x, y, r, dotBorder)
        }
        if (drag >= 0) {
            val x = img.left + quad[drag * 2] * img.width(); val y = img.top + quad[drag * 2 + 1] * img.height()
            val lr = context.dpf(55f)
            val cx = if (x < width / 2f) width - lr - context.dpf(10f) else lr + context.dpf(10f)
            val cy = lr + context.dpf(10f)
            c.save()
            val clip = Path(); clip.addCircle(cx, cy, lr, Path.Direction.CW)
            c.clipPath(clip)
            c.drawColor(Color.BLACK)
            val z = 2.5f
            c.translate(cx - x * z, cy - y * z); c.scale(z, z)
            c.drawBitmap(bmp, null, img, bmpPaint)
            c.restore()
            lens.style = Paint.Style.STROKE; lens.strokeWidth = context.dpf(2f); lens.color = Color.WHITE
            c.drawCircle(cx, cy, lr, lens)
            c.drawLine(cx - 12, cy, cx + 12, cy, lens); c.drawLine(cx, cy - 12, cx, cy + 12, lens)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1; var bd = Float.MAX_VALUE
                for (i in 0 until 4) {
                    val x = img.left + quad[i * 2] * img.width(); val y = img.top + quad[i * 2 + 1] * img.height()
                    val d = Math.hypot((x - e.x).toDouble(), (y - e.y).toDouble()).toFloat()
                    if (d < bd) { bd = d; best = i }
                }
                drag = if (bd < context.dpf(60f)) best else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0 && img.width() > 0) {
                quad[drag * 2] = ((e.x - img.left) / img.width()).coerceIn(0f, 1f)
                quad[drag * 2 + 1] = ((e.y - img.top) / img.height()).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag >= 0) onChanged(quad.copyOf())
                drag = -1; invalidate()
            }
        }
        return true
    }
}
