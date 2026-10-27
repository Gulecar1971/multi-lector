package com.gustavo.multilector

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.net.Uri
import android.view.WindowManager
import java.io.File

/** Un archivo generado: la copia local (para compartir) y la guardada a la vista del usuario. */
class Saved(val local: File, val public: Uri?)

class JobResult(
    val title: String,
    val message: String,
    val files: List<Saved> = emptyList(),
    val extra: Pair<String, () -> Unit>? = null
)

class UserError(msg: String) : Exception(msg)

/** Ejecuta un trabajo largo en segundo plano con cartel de progreso. */
object Jobs {
    @Suppress("DEPRECATION")
    fun run(act: Activity, title: String, work: (progress: (String) -> Unit) -> JobResult) {
        val pd = ProgressDialog(act)
        pd.setTitle(title)
        pd.setMessage("Preparando…")
        pd.setCancelable(false)
        pd.show()
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val res: Result<JobResult> = try {
                Result.success(work { msg -> act.runOnUiThread { if (pd.isShowing) pd.setMessage(msg) } })
            } catch (e: OutOfMemoryError) {
                Result.failure(UserError("El archivo es demasiado grande para la memoria del teléfono. Probá con menos archivos o fotos más chicas."))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            act.runOnUiThread {
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                try { pd.dismiss() } catch (_: Exception) {}
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                res.fold({ show(act, it) }, { e ->
                    val msg = if (e is UserError || e is PdfLockedException) e.message ?: "Error"
                    else "No se pudo terminar: ${e.message ?: e.javaClass.simpleName}"
                    act.alert("Uy", msg)
                })
            }
        }.start()
    }

    fun show(act: Activity, r: JobResult) {
        if (r.title.isEmpty()) return
        val b = AlertDialog.Builder(act).setTitle(r.title).setMessage(r.message)
        if (r.files.isNotEmpty()) {
            b.setPositiveButton("Abrir") { _, _ ->
                val f = r.files[0]
                F.view(act, f.local)
            }
            b.setNeutralButton("Compartir") { _, _ -> F.shareFiles(act, r.files.map { it.local }) }
            b.setNegativeButton("Listo", null)
        } else if (r.extra != null) {
            b.setPositiveButton(r.extra.first) { _, _ -> r.extra.second() }
            b.setNegativeButton("Cerrar", null)
        } else b.setPositiveButton("OK", null)
        b.show()
    }
}

/** Todas las operaciones de la app. */
object Ops {
    private class Item(val uri: Uri, val name: String, val kind: Kind)

    private fun items(act: Activity, uris: List<Uri>): List<Item> = uris.map {
        val n = F.name(act, it)
        Item(it, n, F.kind(act, it, n))
    }

    /** Guarda el archivo generado a la vista (Descargas o Imágenes) y deja copia para compartir. */
    private fun keep(act: Activity, tmp: File, name: String, sub: String = ""): Saved {
        val local = F.outFile(act, name)
        if (tmp.absolutePath != local.absolutePath) {
            if (!tmp.renameTo(local)) { tmp.copyTo(local, true); tmp.delete() }
        }
        val pub = F.savePublic(act, local, local.name, F.mimeFromExt(F.ext(local.name)), sub)
        return Saved(local, pub)
    }

    private fun whereMsg(files: List<Saved>): String {
        val imgs = files.any { F.isImageMime(F.mimeFromExt(F.ext(it.local.name))) }
        val docs = files.any { !F.isImageMime(F.mimeFromExt(F.ext(it.local.name))) }
        val failed = files.any { it.public == null }
        val parts = ArrayList<String>()
        if (docs) parts.add("Descargas › MultiLector")
        if (imgs) parts.add("Imágenes (Galería) › MultiLector")
        return if (failed) "\n\nNo se pudo copiar a la carpeta pública: usá Compartir para guardarlo donde quieras."
        else "\n\nQuedó guardado en: " + parts.joinToString(" y ")
    }

    /** Un documento (Word, Excel, texto…) a PDF. */
    private fun docToPdf(act: Activity, it: Item, out: File) {
        val dir = F.work(act, "doc")
        val doc = DocHtml.build(act, it.uri, it.name, it.kind, dir, "a")
        val html = File(dir, "index.html")
        html.writeText(DocHtml.page(it.name, doc.body))
        if (!HtmlToPdf.convert(act, html, out, doc.landscape)) throw UserError("No se pudo convertir \"${it.name}\" a PDF.")
    }

    fun toPdf(act: Activity, uris: List<Uri>, together: Boolean) {
        Jobs.run(act, "Convirtiendo a PDF") { progress ->
            progress("Revisando archivos…")
            val all = items(act, uris)
            val ok = all.filter { it.kind.convertible }
            val bad = all.filter { !it.kind.convertible }
            if (ok.isEmpty()) throw UserError("Esos archivos no se pueden pasar a PDF (videos, audios, ZIP u otros formatos).")
            val saved = ArrayList<Saved>()
            var note = ""
            if (!together || ok.size == 1) {
                ok.forEachIndexed { i, it ->
                    progress("Archivo ${i + 1} de ${ok.size}:\n${it.name}")
                    val tmp = File(F.work(act, "pdf"), F.safe(F.base(it.name)) + ".pdf")
                    when (it.kind) {
                        Kind.PDF -> F.copyTo(act, it.uri, tmp)
                        Kind.IMAGE -> PdfImageWriter(tmp, PageMode.A4).use { w ->
                            if (!PdfBuild.addImage(act, it.uri, it.name, w)) throw UserError("No se pudo leer la foto ${it.name}")
                        }
                        else -> docToPdf(act, it, tmp)
                    }
                    saved += keep(act, tmp, tmp.name)
                }
            } else {
                val name = "Unidos_${F.stamp()}.pdf"
                val tmp = File(F.work(act, "pdf"), name)
                if (ok.all { it.kind.isDoc }) {
                    // Todo documentos: un solo HTML, el texto queda como texto.
                    val dir = F.work(act, "docs")
                    val sb = StringBuilder()
                    var land = false
                    ok.forEachIndexed { i, it ->
                        progress("Leyendo ${i + 1} de ${ok.size}:\n${it.name}")
                        val d = DocHtml.build(act, it.uri, it.name, it.kind, dir, "f$i")
                        if (d.landscape) land = true
                        if (i > 0) sb.append("<div class=\"pb\"></div>")
                        sb.append("<div class=\"fname\">${DocHtml.esc(it.name)}</div>").append(d.body)
                    }
                    progress("Armando el PDF…")
                    val html = File(dir, "index.html")
                    html.writeText(DocHtml.page(name, sb.toString()))
                    if (!HtmlToPdf.convert(act, html, tmp, land)) throw UserError("No se pudo armar el PDF.")
                } else {
                    PdfImageWriter(tmp, PageMode.A4).use { w ->
                        ok.forEachIndexed { i, it ->
                            progress("Agregando ${i + 1} de ${ok.size}:\n${it.name}")
                            when (it.kind) {
                                Kind.IMAGE -> PdfBuild.addImage(act, it.uri, it.name, w)
                                Kind.PDF -> PdfDoc.open(act, it.uri).use { d ->
                                    PdfBuild.addPdfPages(d, w, 200, 88) { p, n -> progress("${it.name}\npágina $p de $n") }
                                }
                                else -> {
                                    val t = File(F.work(act, "doc"), "doc.pdf")
                                    docToPdf(act, it, t)
                                    PdfDoc.open(t).use { d -> PdfBuild.addPdfPages(d, w, 200, 88) { _, _ -> } }
                                }
                            }
                        }
                        if (w.pageCount == 0) throw UserError("No se pudo agregar ninguna página.")
                    }
                    if (ok.any { it.kind != Kind.IMAGE })
                        note = "\n\nOjo: las páginas de PDFs y documentos quedaron como imagen de alta calidad (el texto no se puede copiar). Las fotos van enteras."
                }
                saved += keep(act, tmp, name)
            }
            val skipped = if (bad.isNotEmpty()) "\n\nNo se pudieron convertir: " + bad.joinToString(", ") { it.name } else ""
            JobResult("¡Listo!", "Se ${if (saved.size == 1) "creó 1 PDF" else "crearon ${saved.size} PDF"}.$note$skipped" + whereMsg(saved), saved)
        }
    }

    fun imagesToPdf(act: Activity, uris: List<Uri>, mode: PageMode, keepOriginals: Boolean) {
        Jobs.run(act, "Fotos a PDF") { progress ->
            val first = F.name(act, uris[0])
            val name = if (uris.size == 1) F.safe(F.base(first)) + ".pdf" else "Fotos_${F.stamp()}.pdf"
            val tmp = File(F.work(act, "img"), name)
            val failed = ArrayList<String>()
            PdfImageWriter(tmp, mode).use { w ->
                uris.forEachIndexed { i, u ->
                    val n = F.name(act, u)
                    progress("Foto ${i + 1} de ${uris.size}")
                    val ok = try { PdfBuild.addImage(act, u, n, w, keepOriginals) } catch (e: OutOfMemoryError) { false }
                    if (!ok) failed += n
                }
                if (w.pageCount == 0) throw UserError("No se pudo leer ninguna foto.")
            }
            val s = keep(act, tmp, name)
            val f = if (failed.isNotEmpty()) "\n\nNo se pudieron agregar: ${failed.joinToString(", ")}" else ""
            JobResult("¡PDF listo!", "${uris.size - failed.size} foto(s) en el PDF, enteras y sin perder calidad. " +
                "Si después las sacás con \"Sacar fotos de un PDF\", salen iguales a las originales.$f" + whereMsg(listOf(s)), listOf(s))
        }
    }

    fun extractImages(act: Activity, uri: Uri) {
        Jobs.run(act, "Sacando fotos del PDF") { progress ->
            val name = F.name(act, uri)
            val base = F.safe(F.base(name))
            progress("Leyendo el PDF…")
            val bytes = F.readAll(act, uri)
            val r = PdfScan(bytes).extract()
            val dir = F.work(act, "ext")
            val saved = ArrayList<Saved>()
            val used = HashSet<String>()
            fun unique(n: String): String {
                var x = F.safe(n)
                var k = 2
                while (!used.add(x.lowercase())) { x = "${F.base(n)}_$k.${F.ext(n)}"; k++ }
                return x
            }
            var count = 0
            val total = r.encoded.size + r.raw.size + r.files.size
            for (f in r.files) {
                count++; progress("Guardando $count de $total")
                val n = unique(f.name)
                val tmp = File(dir, n); tmp.writeBytes(f.data)
                saved += keep(act, tmp, n, base)
            }
            r.encoded.forEachIndexed { i, e ->
                count++; progress("Guardando $count de $total")
                val n = unique(e.name?.let { nm -> if (F.ext(nm) in setOf("jpg", "jpeg")) nm else F.base(nm) + "." + e.ext } ?: "${base}_foto_${"%02d".format(i + 1)}.${e.ext}")
                val tmp = File(dir, n); tmp.writeBytes(e.data)
                saved += keep(act, tmp, n, base)
            }
            r.raw.forEachIndexed { i, raw ->
                count++; progress("Guardando $count de $total")
                val bmp = android.graphics.Bitmap.createBitmap(raw.w, raw.h, android.graphics.Bitmap.Config.ARGB_8888)
                val row = IntArray(raw.w)
                for (y in 0 until raw.h) { PdfScan.argbRow(raw, y, row); bmp.setPixels(row, 0, raw.w, 0, y, raw.w, 1) }
                val n = unique((raw.name?.let { F.base(it) } ?: "${base}_imagen_${"%02d".format(i + 1)}") + ".png")
                val tmp = File(dir, n)
                tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                saved += keep(act, tmp, n, base)
            }
            if (saved.isEmpty()) {
                val why = when {
                    r.encrypted -> "Este PDF está protegido y no deja sacar las fotos directamente."
                    r.unsupported > 0 -> "Las imágenes de este PDF están en un formato especial (típico de escáneres)."
                    else -> "Este PDF no tiene fotos adentro (puede ser solo texto o dibujos)."
                }
                JobResult("No encontré fotos", "$why\n\nPodés guardar cada página como foto.",
                    extra = "Guardar páginas como fotos" to { pagesToImages(act, uri, 200, Fmt.JPG) })
            } else {
                val exact = r.encoded.count { it.ext == "jpg" } + r.files.size
                val msg = StringBuilder("Saqué ${saved.size} archivo(s).")
                if (exact > 0) msg.append("\n\n$exact salieron exactamente como estaban guardadas en el PDF.")
                if (r.raw.isNotEmpty()) msg.append("\n${r.raw.size} se guardaron como PNG sin pérdida.")
                if (r.unsupported > 0) msg.append("\n${r.unsupported} no se pudieron sacar (formato de escáner).")
                JobResult("¡Listo!", msg.toString() + "\n\nQuedaron en Imágenes › MultiLector › $base", saved)
            }
        }
    }

    fun pagesToImages(act: Activity, uri: Uri, dpi: Int, fmt: Fmt) {
        Jobs.run(act, "Páginas a fotos") { progress ->
            val base = F.safe(F.base(F.name(act, uri)))
            val dir = F.work(act, "pag")
            val saved = ArrayList<Saved>()
            PdfDoc.open(act, uri).use { doc ->
                for (i in 0 until doc.count) {
                    progress("Página ${i + 1} de ${doc.count}")
                    val bmp = doc.render(i, dpi / 72f, 20_000_000, forPrint = true)
                    val n = "${base}_pag_${"%03d".format(i + 1)}.${fmt.ext}"
                    val tmp = File(dir, n)
                    val e = Img.encode(bmp, fmt, if (fmt == Fmt.PNG) 100 else 92, 0)
                    tmp.writeBytes(e.bytes)
                    bmp.recycle()
                    saved += keep(act, tmp, n, base)
                }
            }
            JobResult("¡Listo!", "Se guardaron ${saved.size} página(s) como foto.\n\nQuedaron en Imágenes › MultiLector › $base", saved)
        }
    }

    fun compress(act: Activity, uri: Uri, level: Int) {
        val (dpi, q) = when (level) { 0 -> 96 to 55; 1 -> 130 to 68; else -> 170 to 78 }
        Jobs.run(act, "Achicando PDF") { progress ->
            val name = F.name(act, uri)
            val before = F.size(act, uri)
            val out = F.safe(F.base(name)) + "_liviano.pdf"
            val tmp = File(F.work(act, "cmp"), out)
            PdfDoc.open(act, uri).use { d ->
                PdfImageWriter(tmp, PageMode.EXACT).use { w -> PdfBuild.addPdfPages(d, w, dpi, q) { p, n -> progress("Página $p de $n") } }
            }
            val after = tmp.length()
            val s = keep(act, tmp, out)
            val msg = if (before > 0 && after >= before)
                "Quedó en ${F.human(after)} (antes ${F.human(before)}): este PDF ya estaba bien comprimido. Probá con el nivel \"Fuerte\"."
            else "Antes: ${F.human(before)}\nAhora: ${F.human(after)}\n\nLas páginas quedaron como imagen (el texto no se puede copiar)."
            JobResult("PDF achicado", msg + whereMsg(listOf(s)), listOf(s))
        }
    }

    /** Achicar / cambiar formato de fotos. */
    class PhotoSpec(
        val w: Int, val h: Int, val fit: Fit, val fmt: Fmt, val quality: Int, val maxKB: Int,
        val bg: Int, val upscale: Boolean, val suffix: String
    )

    fun convertOne(act: Activity, uri: Uri, spec: PhotoSpec, dir: File): Pair<File, Encoded>? {
        val target = if (spec.fit == Fit.KEEP || spec.w <= 0) 30_000_000L else maxOf(spec.w.toLong() * spec.h * 4, 2_000_000L).coerceAtMost(30_000_000L)
        val src = Img.decode(act, uri, target) ?: return null
        val out = Img.resize(src, spec.w, spec.h, spec.fit, spec.bg, spec.upscale)
        if (out !== src) src.recycle()
        val e = Img.encode(out, spec.fmt, spec.quality, spec.maxKB)
        out.recycle()
        val n = F.safe(F.base(F.name(act, uri))) + spec.suffix + "." + spec.fmt.ext
        val f = File(dir, n)
        f.writeBytes(e.bytes)
        if (spec.fmt == Fmt.JPG) Img.copyExif(act, uri, f)
        return f to e
    }

    fun convertPhotos(act: Activity, uris: List<Uri>, spec: PhotoSpec) {
        Jobs.run(act, "Convirtiendo fotos") { progress ->
            val dir = F.work(act, "fot")
            val saved = ArrayList<Saved>()
            val lines = ArrayList<String>()
            uris.forEachIndexed { i, u ->
                progress("Foto ${i + 1} de ${uris.size}")
                val before = F.size(act, u)
                val r = try { convertOne(act, u, spec, dir) } catch (e: OutOfMemoryError) { null }
                if (r == null) lines += "✗ ${F.name(act, u)}: no se pudo abrir"
                else {
                    val s = keep(act, r.first, r.first.name)
                    saved += s
                    lines += "✓ ${r.first.name}: ${r.second.w}×${r.second.h}, ${F.human(before)} → ${F.human(r.second.bytes.size.toLong())}"
                }
            }
            if (saved.isEmpty()) throw UserError("No se pudo convertir ninguna foto.")
            JobResult("¡Fotos listas!", lines.take(15).joinToString("\n") + (if (lines.size > 15) "\n…" else "") +
                "\n\nQuedaron en Imágenes (Galería) › MultiLector", saved)
        }
    }
}
