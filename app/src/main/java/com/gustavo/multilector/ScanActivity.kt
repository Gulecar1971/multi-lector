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
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escáner de documentos: sacás fotos de las hojas, la app encuentra la hoja, la endereza y la limpia.
 * Las hojas se juntan en una "carpeta" (el escaneo en curso) hasta que tocás Guardar PDF.
 */
class ScanActivity : Activity() {

    class Page(val src: String, var quad: FloatArray, var rot: Int, var filter: ScanFilter, var out: String)

    private val pages = ArrayList<Page>()
    private lateinit var dir: File
    private lateinit var root: FrameLayout
    private lateinit var grid: GridView
    private lateinit var countLabel: TextView
    private var editor: View? = null
    private var defaultFilter = ScanFilter.ENHANCED
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(cacheDir, "scan/actual").apply { mkdirs() }
        load()
        buildMain()
        if (pages.isEmpty() && savedInstanceState == null && prefs.getString("pendiente", null) == null) camera()
    }

    // ---------------- guardar el estado (por si Android cierra la app mientras está la cámara) ----------------
    private val prefs get() = getSharedPreferences("escaneo", Context.MODE_PRIVATE)

    private fun save() {
        val a = JSONArray()
        pages.forEach { p ->
            a.put(JSONObject().put("src", p.src).put("q", JSONArray(p.quad.map { it.toDouble() })).put("rot", p.rot)
                .put("f", p.filter.name).put("out", p.out))
        }
        prefs.edit().putString("hojas", a.toString()).putString("filtro", defaultFilter.name).apply()
    }

    private fun load() {
        pages.clear()
        try {
            defaultFilter = ScanFilter.valueOf(prefs.getString("filtro", ScanFilter.ENHANCED.name)!!)
            val a = JSONArray(prefs.getString("hojas", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val q = o.getJSONArray("q")
                val p = Page(o.getString("src"), FloatArray(8) { q.getDouble(it).toFloat() }, o.getInt("rot"),
                    ScanFilter.valueOf(o.getString("f")), o.getString("out"))
                if (File(p.src).exists() && File(p.out).exists()) pages.add(p)
            }
        } catch (_: Exception) {
        }
    }

    private fun clearAll() {
        pages.clear()
        dir.deleteRecursively(); dir.mkdirs()
        prefs.edit().clear().apply()
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
        grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = dp(8); grid.verticalSpacing = dp(8)
        grid.setPadding(dp(10), dp(6), dp(10), dp(10))
        grid.clipToPadding = false
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> pageMenu(pos) }
        box.addView(grid, lp(MATCH, 0, 1f))

        val sc = HorizontalScrollView(this)
        sc.setBackgroundColor(Color.WHITE)
        val bar = hbox()
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        fun b(t: String, c: Int, a: () -> Unit) = bar.addView(smallButton(t, c, a), lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        b("📷 Otra hoja", Pal.orange) { camera() }
        b("💾 Guardar PDF", Pal.accent) { askSave() }
        b("🖨 Imprimir", Pal.primary) { printNow() }
        b("🖼 Desde galería", Pal.teal) { gallery() }
        b("🎨 Filtro a todas", Pal.purple) { filterAll() }
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
                layoutParams = android.widget.AbsListView.LayoutParams(MATCH, dp(150))
            }
            val iv = cell.getChildAt(0) as ImageView
            (cell.getChildAt(1) as TextView).text = "${pos + 1}"
            iv.setImageBitmap(thumb(pages[pos].out))
            return cell
        }
    }

    private val thumbs = HashMap<String, Bitmap>()
    private fun thumb(path: String): Bitmap? {
        thumbs[path]?.let { return it }
        val o = BitmapFactory.Options().apply { inSampleSize = 8 }
        val b = BitmapFactory.decodeFile(path, o) ?: return null
        thumbs[path] = b
        return b
    }

    private fun refresh() {
        countLabel.text = when (pages.size) {
            0 -> "Todavía no hay hojas. Tocá \"📷 Otra hoja\" para sacar la primera."
            1 -> "1 hoja en la carpeta. Tocá una hoja para editarla."
            else -> "${pages.size} hojas en la carpeta. Tocá una hoja para editarla o moverla."
        }
        adapter.notifyDataSetChanged()
        save()
    }

    private fun pageMenu(pos: Int) {
        val opts = listOf("✂️ Ajustar recorte / girar", "🎨 Cambiar filtro", "⬅️ Mover antes", "➡️ Mover después", "🔍 Ver grande", "🗑 Borrar hoja")
        choose("Hoja ${pos + 1}", opts) { w ->
            when (w) {
                0 -> openEditor(pos)
                1 -> chooseFilter { f -> reprocess(listOf(pos), f) }
                2 -> if (pos > 0) { val p = pages.removeAt(pos); pages.add(pos - 1, p); refresh() }
                3 -> if (pos < pages.size - 1) { val p = pages.removeAt(pos); pages.add(pos + 1, p); refresh() }
                4 -> F.view(this, File(pages[pos].out))
                5 -> AlertDialog.Builder(this).setTitle("¿Borrar la hoja ${pos + 1}?")
                    .setPositiveButton("Borrar") { _, _ ->
                        val p = pages.removeAt(pos); File(p.out).delete(); File(p.src).delete(); thumbs.remove(p.out); refresh()
                    }.setNegativeButton("No", null).show()
            }
        }
    }

    private fun chooseFilter(go: (ScanFilter) -> Unit) {
        val fs = ScanFilter.values()
        choose("¿Cómo querés que quede?", listOf(
            "✨ Mejorado: fondo blanco, sin sombras, colores vivos",
            "📄 Blanco y negro: texto bien negro (ideal facturas e impresora B/N)",
            "🌫 Gris: sin color, suave",
            "🖼 Original: como salió la foto (solo recortada)"
        )) { go(fs[it]) }
    }

    private fun filterAll() {
        if (pages.isEmpty()) return
        chooseFilter { f -> defaultFilter = f; reprocess(pages.indices.toList(), f) }
    }

    private fun reprocess(idx: List<Int>, f: ScanFilter) {
        work("Aplicando filtro…") {
            idx.forEach { i -> val p = pages[i]; p.filter = f; render(p) }
            runOnUiThread { refresh() }
        }
    }

    // ---------------- procesar ----------------
    private fun loadSrc(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth.toLong() / s * (o.outHeight.toLong() / s) > 13_000_000L) s *= 2
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val ori = try { android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }
        return Img.rotate(b, ori)
    }

    /** Recalcula la hoja final (recorte + giro + filtro) y la guarda como JPG. */
    private fun render(p: Page) {
        val src = loadSrc(p.src) ?: return
        val out = Scan.process(src, p.quad, p.rot, p.filter)
        src.recycle()
        val f = File(p.out)
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, if (p.filter == ScanFilter.DOCUMENT) 85 else 88, it) }
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
        b.addView(label(msg, 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        overlay.addView(b, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        Thread {
            try { job() } catch (e: Throwable) {
                runOnUiThread { alert("Uy", if (e is OutOfMemoryError) "La foto es demasiado grande para la memoria del teléfono." else "No se pudo procesar: ${e.message}") }
            }
            runOnUiThread { root.removeView(overlay); busy = false }
        }.start()
    }

    // ---------------- cámara y galería ----------------
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
        if (requestCode == REQ_CAM) {
            val pend = prefs.getString("pendiente", null)
            prefs.edit().remove("pendiente").apply()
            if (resultCode == RESULT_OK && pend != null && File(pend).length() > 0) addPhotos(listOf(pend), true)
            else if (pages.isEmpty()) refresh()
        } else if (requestCode == REQ_GAL && resultCode == RESULT_OK && data != null) {
            val uris = MainActivity.urisFrom(data)
            work("Cargando ${uris.size} foto(s)…") {
                val files = uris.mapIndexedNotNull { k, u ->
                    try { F.copyTo(this, u, File(dir, "gal_${System.currentTimeMillis()}_$k.jpg")).path } catch (_: Exception) { null }
                }
                val added = processFiles(files)
                runOnUiThread { refresh(); if (added.size == 1) openEditor(added[0], fresh = false) }
            }
        }
    }

    /** Detecta la hoja en cada foto y la agrega a la carpeta. Corre en segundo plano. */
    private fun processFiles(files: List<String>): List<Int> {
        val added = ArrayList<Int>()
        for (path in files) {
            val src = loadSrc(path) ?: continue
            val q = Scan.detect(src)
            src.recycle()
            val p = Page(path, q, 0, defaultFilter, File(dir, "hoja_${System.currentTimeMillis()}_${pages.size}.jpg").path)
            render(p)
            synchronized(pages) { pages.add(p); added.add(pages.size - 1) }
        }
        return added
    }

    /** Foto nueva de la cámara: la procesa y abre el editor para revisar el recorte. */
    private fun addPhotos(files: List<String>, edit: Boolean) {
        if (files.isEmpty()) return
        work("Buscando la hoja y limpiándola…") {
            val added = processFiles(files)
            runOnUiThread {
                refresh()
                if (edit && added.size == 1) openEditor(added[0], fresh = true)
            }
        }
    }

    // ---------------- editor de recorte ----------------
    private fun openEditor(pos: Int, fresh: Boolean = false) {
        val p = pages.getOrNull(pos) ?: return
        val bmp = try { loadPreview(p.src) } catch (_: Throwable) { null } ?: return
        val box = vbox()
        box.setBackgroundColor(Color.parseColor("#1B1B1B"))
        box.isClickable = true
        val title = label(if (fresh) "Hoja ${pos + 1}: revisá que los puntos estén en las esquinas" else "Hoja ${pos + 1}: mové los puntos a las esquinas",
            15f, Color.WHITE, true)
        title.setPadding(dp(14), dp(12), dp(14), dp(8))
        box.addView(title)
        val crop = CropView(this, bmp, p.quad.copyOf())
        box.addView(crop, lp(MATCH, 0, 1f))
        var rot = p.rot
        val rotLabel = label("", 13f, Color.LTGRAY).apply { setPadding(dp(14), dp(4), dp(14), 0) }
        fun showRot() { rotLabel.text = if (rot % 360 == 0) "Sin girar" else "Se va a girar ${rot % 360}°" }
        showRot()
        box.addView(rotLabel)
        val sc = HorizontalScrollView(this)
        val bar = hbox(); bar.setPadding(dp(6), dp(8), dp(6), dp(8))
        fun b(t: String, c: Int, a: () -> Unit) = bar.addView(smallButton(t, c, a), lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        b("✅ Listo", Pal.accent) {
            p.quad = crop.quad; p.rot = rot
            closeEditor()
            work("Enderezando y limpiando…") { render(p); runOnUiThread { refresh() } }
        }
        b("⟳ Girar", Pal.slate) { rot = (rot + 90) % 360; showRot() }
        b("🔲 Toda la foto", Pal.slate) { crop.setQuad(Scan.fullQuad(0f)) }
        b("🎯 Detectar", Pal.slate) { crop.setQuad(Scan.detect(bmp)) }
        if (fresh) b("📷 Repetir foto", Pal.orange) {
            closeEditor()
            val old = pages.removeAt(pos); File(old.out).delete(); File(old.src).delete(); refresh(); camera()
        }
        b("✖ Cancelar", Pal.red) { closeEditor() }
        sc.addView(bar)
        box.addView(sc, lp(MATCH, WRAP))
        editor = box
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun loadPreview(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1600) s *= 2
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val ori = try { android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }
        return Img.rotate(b, ori)
    }

    private fun closeEditor() {
        editor?.let { root.removeView(it) }
        editor = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editor != null) { closeEditor(); return }
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
            .setPositiveButton("Borrar todo") { _, _ -> clearAll(); thumbs.clear(); refresh() }
            .setNegativeButton("No", null).show()
    }

    companion object {
        const val REQ_CAM = 31
        const val REQ_GAL = 32
    }
}

/** Foto con 4 puntos para marcar las esquinas de la hoja. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CropView(ctx: Context, private val bmp: Bitmap, q: FloatArray) : View(ctx) {
    var quad: FloatArray = q
        private set
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
        // lupa mientras se arrastra, para acertar la esquina
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; invalidate() }
        }
        return true
    }
}
