package com.gustavo.multilector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/** Achicar, recortar y cambiar formato de fotos con medidas listas para cada uso. */
class PhotoActivity : Activity() {

    class Preset(
        val title: String, val desc: String, val w: Int, val h: Int, val fit: Fit,
        val fmt: Fmt, val q: Int, val maxKB: Int = 0, val bg: Int = 0, val suffix: String
    )

    private val presets = listOf(
        Preset("💬 WhatsApp / mensajes", "Liviana y nítida en el celu: lado mayor 1600 px", 1600, 1600, Fit.FIT, Fmt.JPG, 82, suffix = "_whatsapp"),
        Preset("🛍 Catálogo WhatsApp Business", "Cuadrada 1080×1080 con fondo blanco, sin recortar el producto", 1080, 1080, Fit.PAD, Fmt.JPG, 90, bg = 0, suffix = "_catalogo"),
        Preset("🛒 Mercado Libre / tienda online", "1200×1200 fondo blanco (lo que piden las tiendas)", 1200, 1200, Fit.PAD, Fmt.JPG, 92, bg = 0, suffix = "_tienda"),
        Preset("📸 Instagram cuadrada", "1080×1080 recortada al centro", 1080, 1080, Fit.CROP, Fmt.JPG, 92, suffix = "_ig"),
        Preset("📱 Instagram vertical", "1080×1350 (la que más se ve en el feed)", 1080, 1350, Fit.CROP, Fmt.JPG, 92, suffix = "_ig_vertical"),
        Preset("⏳ Historia / Estado", "1080×1920 pantalla completa", 1080, 1920, Fit.CROP, Fmt.JPG, 90, suffix = "_historia"),
        Preset("👤 Foto de perfil", "640×640 cuadrada", 640, 640, Fit.CROP, Fmt.JPG, 90, suffix = "_perfil"),
        Preset("🖼 Portada de Facebook", "1640×624", 1640, 624, Fit.CROP, Fmt.JPG, 90, suffix = "_portada"),
        Preset("🪪 Foto carnet 4×4 cm", "472×472 px (4 cm a 300 dpi), recortada", 472, 472, Fit.CROP, Fmt.JPG, 95, suffix = "_carnet"),
        Preset("🏛 Trámites online (máx. 200 KB)", "Para formularios que piden poco peso (AFIP/ARCA, ANSES, etc.)", 1400, 1400, Fit.FIT, Fmt.JPG, 85, maxKB = 200, suffix = "_tramite"),
        Preset("✉️ Mail liviano", "1024 px, poco peso", 1024, 1024, Fit.FIT, Fmt.JPG, 75, suffix = "_mail"),
        Preset("🖨 Imprimir 10×15", "1200×1800 a 300 dpi", 1200, 1800, Fit.CROP, Fmt.JPG, 95, suffix = "_10x15"),
        Preset("🔷 Logo / ícono PNG", "512×512 con fondo transparente", 512, 512, Fit.PAD, Fmt.PNG, 100, bg = 2, suffix = "_logo"),
        Preset("🔄 Solo cambiar formato", "Mismo tamaño, elegís JPG, PNG o WEBP abajo", 0, 0, Fit.KEEP, Fmt.JPG, 92, suffix = "_conv"),
        Preset("✏️ A mi medida", "Poné vos el ancho, alto, formato y peso", 1000, 1000, Fit.FIT, Fmt.JPG, 90, suffix = "_editada"),
    )

    private var uris = ArrayList<Uri>()
    private var selected = 0
    private val rows = ArrayList<View>()
    private lateinit var thumb: ImageView
    private lateinit var info: TextView
    private lateinit var etW: EditText
    private lateinit var etH: EditText
    private lateinit var etKB: EditText
    private lateinit var spFit: Spinner
    private lateinit var spFmt: Spinner
    private lateinit var spBg: Spinner
    private lateinit var seek: SeekBar
    private lateinit var qLabel: TextView
    private lateinit var cbUp: CheckBox
    private lateinit var units: Spinner
    private var suffix = "_whatsapp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>("uris")?.let { uris = it }
        build()
        if (uris.isEmpty()) pick() else refreshHeader()
    }

    private fun build() {
        val root = vbox()
        root.setBackgroundColor(Pal.bg)
        root.addView(topBar("Achicar / cambiar fotos", true).first, lp(MATCH, WRAP))
        val sv = ScrollView(this)
        val box = vbox()
        box.setPadding(dp(14), dp(10), dp(14), dp(24))
        sv.addView(box)
        root.addView(sv, lp(MATCH, 0, 1f))

        // cabecera
        val head = hbox()
        head.background = rounded(Color.WHITE, dpf(14f))
        head.setPadding(dp(10), dp(10), dp(10), dp(10))
        thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = rounded(Pal.line, dpf(8f))
        head.addView(thumb, lp(dp(72), dp(72)))
        info = label("Elegí fotos…", 15f)
        info.setPadding(dp(12), 0, dp(8), 0)
        head.addView(info, lp(0, WRAP, 1f))
        head.addView(smallButton("Elegir", Pal.slate) { pick() })
        box.addView(head, lp(MATCH, WRAP))

        box.addView(section("¿Para qué la querés?"))
        presets.forEachIndexed { i, p ->
            val r = vbox()
            r.setPadding(dp(14), dp(10), dp(14), dp(10))
            r.addView(label(p.title, 16f, Pal.text, true))
            r.addView(label(p.desc, 13f, Pal.muted))
            r.setOnClickListener { select(i) }
            rows += r
            box.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        box.addView(section("Ajustes (podés cambiarlos)"))
        val card = vbox()
        card.background = rounded(Color.WHITE, dpf(14f))
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        box.addView(card, lp(MATCH, WRAP))

        val sizeRow = hbox()
        etW = num("Ancho"); etH = num("Alto")
        sizeRow.addView(etW, lp(0, WRAP, 1f))
        sizeRow.addView(label(" × ", 18f, Pal.muted, true))
        sizeRow.addView(etH, lp(0, WRAP, 1f))
        units = spinner(listOf("px", "cm"))
        sizeRow.addView(units, lp(WRAP, WRAP))
        card.addView(small("Tamaño final (en cm se calcula a 300 dpi)"))
        card.addView(sizeRow)

        card.addView(small("Cómo encajarla"))
        spFit = spinner(Fit.values().map { it.label })
        card.addView(spFit)
        card.addView(small("Fondo (para \"con fondo\")"))
        spBg = spinner(listOf("Blanco", "Negro", "Transparente (PNG/WEBP)"))
        card.addView(spBg)
        card.addView(small("Formato"))
        spFmt = spinner(Fmt.values().map { it.label })
        card.addView(spFmt)

        qLabel = small("Calidad: 90")
        card.addView(qLabel)
        seek = SeekBar(this)
        seek.max = 70
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { qLabel.text = "Calidad: ${30 + p}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        card.addView(seek)
        card.addView(small("Peso máximo en KB (vacío = sin límite)"))
        etKB = num("Ej: 200")
        card.addView(etKB)
        cbUp = CheckBox(this)
        cbUp.text = "Agrandar si la foto es más chica"
        cbUp.textSize = 14f
        card.addView(cbUp)

        val actions = hbox()
        actions.setPadding(0, dp(14), 0, 0)
        actions.addView(smallButton("👁 Probar con la primera", Pal.slate) { preview() }, lp(0, WRAP, 1f).apply { marginEnd = dp(8) })
        actions.addView(smallButton("✅ Convertir", Pal.accent) { go() }, lp(0, WRAP, 1f))
        box.addView(actions, lp(MATCH, WRAP))

        setContentView(root)
        select(0)
    }

    private fun section(t: String) = label(t, 17f, Pal.primary, true).apply { setPadding(dp(2), dp(18), 0, dp(4)) }
    private fun small(t: String) = label(t, 13f, Pal.muted).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun num(hint: String) = EditText(this).apply {
        this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; textSize = 16f
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@PhotoActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun select(i: Int) {
        selected = i
        rows.forEachIndexed { k, v ->
            v.background = if (k == i) rounded(Color.parseColor("#E3F2E5"), dpf(12f), dp(2), Pal.accent) else rounded(Color.WHITE, dpf(12f))
        }
        val p = presets[i]
        units.setSelection(0)
        etW.setText(if (p.w > 0) p.w.toString() else "")
        etH.setText(if (p.h > 0) p.h.toString() else "")
        spFit.setSelection(p.fit.ordinal)
        spFmt.setSelection(p.fmt.ordinal)
        spBg.setSelection(p.bg)
        seek.progress = (p.q - 30).coerceIn(0, 70)
        etKB.setText(if (p.maxKB > 0) p.maxKB.toString() else "")
        cbUp.isChecked = false
        suffix = p.suffix
    }

    private fun spec(): Ops.PhotoSpec? {
        val cm = units.selectedItemPosition == 1
        fun v(e: EditText): Int {
            val d = e.text.toString().replace(',', '.').toDoubleOrNull() ?: return 0
            return if (cm) Math.round(d / 2.54 * 300).toInt() else d.toInt()
        }
        var w = v(etW); var h = v(etH)
        val fit = Fit.values()[spFit.selectedItemPosition]
        if (fit != Fit.KEEP) {
            if (w <= 0 && h <= 0) { alert("Falta el tamaño", "Poné el ancho y/o el alto."); return null }
            if (fit == Fit.FIT) { if (w <= 0) w = 100000; if (h <= 0) h = 100000 }
            else if (w <= 0 || h <= 0) { alert("Falta el tamaño", "Para recortar o poner fondo necesito ancho y alto."); return null }
            if (w > 12000 || h > 12000) { if (w != 100000 && h != 100000) { alert("Muy grande", "El máximo es 12000 px por lado."); return null } }
        }
        val fmt = Fmt.values()[spFmt.selectedItemPosition]
        val bg = when (spBg.selectedItemPosition) {
            1 -> Color.BLACK
            2 -> if (fmt == Fmt.JPG) Color.WHITE else Color.TRANSPARENT
            else -> Color.WHITE
        }
        val kb = etKB.text.toString().toIntOrNull() ?: 0
        return Ops.PhotoSpec(w, h, fit, fmt, 30 + seek.progress, kb, bg, cbUp.isChecked, suffix)
    }

    private fun go() {
        if (uris.isEmpty()) { pick(); return }
        val s = spec() ?: return
        Ops.convertPhotos(this, uris, s)
    }

    private fun preview() {
        if (uris.isEmpty()) { pick(); return }
        val s = spec() ?: return
        Jobs.run(this, "Probando") { p ->
            p("Convirtiendo la primera foto…")
            val r = Ops.convertOne(this, uris[0], s, F.work(this, "prev")) ?: throw UserError("No se pudo abrir la foto.")
            runOnUiThread {
                val iv = ImageView(this)
                iv.adjustViewBounds = true
                iv.setBackgroundColor(Color.parseColor("#DDDDDD"))
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(r.first.path))
                val box = vbox(); box.setPadding(dp(12), dp(8), dp(12), 0)
                box.addView(label("${r.second.w}×${r.second.h} px · ${F.human(r.second.bytes.size.toLong())}", 15f, Pal.text, true))
                box.addView(iv, lp(MATCH, WRAP).apply { topMargin = dp(8) })
                AlertDialog.Builder(this).setTitle("Así queda").setView(box)
                    .setPositiveButton("Convertir todas") { _, _ -> go() }
                    .setNegativeButton("Cambiar ajustes", null).show()
            }
            JobResult("", "", emptyList())
        }
    }

    private fun refreshHeader() {
        if (uris.isEmpty()) { info.text = "Elegí fotos…"; return }
        Thread {
            val first = uris[0]
            val sz = Img.displaySize(this, first)
            val total = uris.sumOf { F.size(this, it).coerceAtLeast(0) }
            val bmp = Img.decode(this, first, 200_000)
            runOnUiThread {
                thumb.setImageBitmap(bmp)
                info.text = if (uris.size == 1) "${F.name(this, first)}\n${sz[0]}×${sz[1]} px · ${F.human(total)}"
                else "${uris.size} fotos elegidas\nla 1ª: ${sz[0]}×${sz[1]} px · total ${F.human(total)}"
            }
        }.start()
    }

    private fun pick() {
        choose("¿De dónde?", listOf("🖼 Galería", "📂 Carpetas (Descargas, WhatsApp, Drive…)")) { which ->
            val i = if (which == 0) Intent(Intent.ACTION_GET_CONTENT) else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "image/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try { startActivityForResult(i, 7) } catch (e: Exception) { toast("No hay app para elegir fotos") }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK || data == null) return
        val list = MainActivity.urisFrom(data)
        if (list.isNotEmpty()) { uris = ArrayList(list); refreshHeader() }
    }

    companion object {
        fun start(act: Activity, uris: List<Uri>) {
            val i = Intent(act, PhotoActivity::class.java)
            i.putParcelableArrayListExtra("uris", ArrayList(uris))
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri("", uris[0])
                for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
                i.clipData = clip
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.startActivity(i)
        }
    }
}
