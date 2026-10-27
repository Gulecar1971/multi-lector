package com.gustavo.multilector

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

enum class Kind(val label: String) {
    PDF("PDF"), IMAGE("Imagen"), DOCX("Word"), XLSX("Excel"), PPTX("PowerPoint"),
    ODT("Documento ODT"), ODS("Planilla ODS"), ODP("Presentación ODP"), EPUB("Libro ePub"),
    RTF("Documento RTF"), DOC("Word viejo (.doc)"), XLS("Excel viejo (.xls)"), PPT("PowerPoint viejo (.ppt)"), TEXT("Texto"), CSV("Planilla CSV"), MD("Texto Markdown"), JSON("JSON"),
    HTML("Página web"), XML("XML"), SVG("Imagen SVG"), ZIP("Archivo ZIP"), VIDEO("Video"),
    AUDIO("Audio"), UNKNOWN("Archivo");

    /** ¿Se puede pasar a PDF? */
    val convertible: Boolean get() = this != ZIP && this != VIDEO && this != AUDIO && this != UNKNOWN
    /** ¿Se convierte a PDF pasando por HTML? */
    val isDoc: Boolean get() = convertible && this != PDF && this != IMAGE
}

object F {
    const val AUTH = "com.gustavo.multilector.files"

    fun name(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "archivo").name
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { if (it.isNotBlank()) return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "archivo"
    }

    fun size(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: "").length()
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (_: Exception) {
        }
        return -1
    }

    fun ext(name: String): String = if (name.contains('.')) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    fun base(name: String): String = name.substringBeforeLast('.').ifBlank { "archivo" }
    fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifBlank { "archivo" }

    fun mimeFromExt(e: String): String {
        when (e) {
            "heic", "heif" -> return "image/heif"
            "md" -> return "text/markdown"
            "kt", "kts", "log", "ini", "cfg", "conf" -> return "text/plain"
            "jp2" -> return "image/jp2"
            "epub" -> return "application/epub+zip"
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
    }

    fun mime(ctx: Context, uri: Uri, name: String): String {
        val m = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
        if (m != null && m != "application/octet-stream") return m
        return mimeFromExt(ext(name))
    }

    private fun byExt(e: String): Kind? = when (e) {
        "pdf" -> Kind.PDF
        "jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "ico" -> Kind.IMAGE
        "svg" -> Kind.SVG
        "docx", "docm", "dotx" -> Kind.DOCX
        "xlsx", "xlsm", "xltx" -> Kind.XLSX
        "pptx", "ppsx", "potx" -> Kind.PPTX
        "odt" -> Kind.ODT
        "ods" -> Kind.ODS
        "odp" -> Kind.ODP
        "epub" -> Kind.EPUB
        "rtf" -> Kind.RTF
        "doc", "dot" -> Kind.DOC
        "xls", "xlt" -> Kind.XLS
        "ppt", "pps", "pot" -> Kind.PPT
        "txt", "text", "log", "ini", "cfg", "conf", "kt", "kts", "java", "py", "js", "ts", "css", "c", "cpp", "h",
        "sh", "bat", "sql", "yml", "yaml", "properties", "srt", "vtt", "nfo", "tex", "gradle", "env", "toml" -> Kind.TEXT
        "csv", "tsv" -> Kind.CSV
        "md", "markdown" -> Kind.MD
        "json" -> Kind.JSON
        "html", "htm", "xhtml" -> Kind.HTML
        "xml" -> Kind.XML
        "zip" -> Kind.ZIP
        "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v" -> Kind.VIDEO
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi" -> Kind.AUDIO
        else -> null
    }

    fun kind(ctx: Context, uri: Uri, name: String): Kind {
        val e = ext(name)
        val k = byExt(e)
        if (k != null && k != Kind.ZIP) return k
        val head = head(ctx, uri, 1024)
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { (head[it].toInt() and 0xFF) == b[it] }
        when {
            starts(0x25, 0x50, 0x44, 0x46) -> return Kind.PDF
            starts(0xFF, 0xD8) || starts(0x89, 0x50, 0x4E, 0x47) || starts(0x47, 0x49, 0x46) || starts(0x42, 0x4D) -> return Kind.IMAGE
            head.size > 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> return Kind.IMAGE
            head.size > 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> {
                val brand = String(head, 8, 4, Charsets.ISO_8859_1)
                return if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("avi")) Kind.IMAGE else Kind.VIDEO
            }
            starts(0x50, 0x4B) -> return zipKind(ctx, uri)
            starts(0x7B, 0x5C, 0x72, 0x74, 0x66) -> return Kind.RTF
            starts(0xD0, 0xCF, 0x11, 0xE0) && k == null -> {
                try {
                    val ole = Ole(readAll(ctx, uri))
                    if (ole.has("WordDocument")) return Kind.DOC
                    if (ole.has("Workbook") || ole.has("Book")) return Kind.XLS
                    if (ole.has("PowerPoint Document")) return Kind.PPT
                } catch (_: Throwable) {}
            }
        }
        if (k != null) return k
        val m = mime(ctx, uri, name)
        when {
            m == "application/pdf" -> return Kind.PDF
            m.startsWith("image/svg") -> return Kind.SVG
            m.startsWith("image/") -> return Kind.IMAGE
            m.startsWith("video/") -> return Kind.VIDEO
            m.startsWith("audio/") -> return Kind.AUDIO
            m == "text/html" -> return Kind.HTML
            m == "text/csv" || m == "text/comma-separated-values" -> return Kind.CSV
            m.startsWith("text/") -> return Kind.TEXT
        }
        if (head.isNotEmpty() && looksText(head)) return Kind.TEXT
        return Kind.UNKNOWN
    }

    private fun zipKind(ctx: Context, uri: Uri): Kind {
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val z = ZipInputStream(ins)
                var n = 0
                while (n < 60) {
                    val e = z.nextEntry ?: break
                    n++
                    val nm = e.name
                    when {
                        nm == "mimetype" -> {
                            val t = String(z.readBytes().take(80).toByteArray(), Charsets.ISO_8859_1)
                            when {
                                t.contains("opendocument.text") -> return Kind.ODT
                                t.contains("opendocument.spreadsheet") -> return Kind.ODS
                                t.contains("opendocument.presentation") -> return Kind.ODP
                                t.contains("epub") -> return Kind.EPUB
                            }
                        }
                        nm.startsWith("word/") -> return Kind.DOCX
                        nm.startsWith("xl/") -> return Kind.XLSX
                        nm.startsWith("ppt/") -> return Kind.PPTX
                        nm == "META-INF/container.xml" -> return Kind.EPUB
                    }
                }
            }
        } catch (_: Exception) {
        }
        return Kind.ZIP
    }

    fun head(ctx: Context, uri: Uri, n: Int): ByteArray = try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(n)
            var total = 0
            while (total < n) {
                val r = ins.read(buf, total, n - total)
                if (r <= 0) break
                total += r
            }
            buf.copyOf(total)
        } ?: ByteArray(0)
    } catch (_: Exception) {
        ByteArray(0)
    }

    fun looksText(b: ByteArray): Boolean {
        var bad = 0
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == 0) return false
            if (c < 9 || (c in 14..31 && c != 27)) bad++
        }
        return bad * 20 < b.size
    }

    fun readAll(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("No se pudo leer el archivo")

    fun copyTo(ctx: Context, uri: Uri, dest: File): File {
        dest.parentFile?.mkdirs()
        ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            ?: throw Exception("No se pudo leer el archivo")
        return dest
    }

    /** Carpeta de trabajo nueva dentro del caché. */
    fun work(ctx: Context, tag: String): File {
        val d = File(ctx.cacheDir, "work/${tag}_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}")
        d.mkdirs()
        return d
    }

    fun cleanOld(ctx: Context) {
        Thread {
            val now = System.currentTimeMillis()
            listOf("work" to 6 * 3600_000L, "out" to 3 * 86400_000L, "cam" to 3 * 86400_000L).forEach { (dir, age) ->
                File(ctx.cacheDir, dir).listFiles()?.forEach { f ->
                    if (now - f.lastModified() > age) f.deleteRecursively()
                }
            }
        }.start()
    }

    fun human(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale("es", "AR"), "%.0f KB", b / 1024.0)
        else -> String.format(Locale("es", "AR"), "%.1f MB", b / 1048576.0)
    }

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset("windows-1252"))
        }
    }

    /** Uri content:// propia para compartir/abrir un archivo del caché. */
    fun providerUri(ctx: Context, f: File): Uri {
        val rel = f.canonicalFile.relativeTo(ctx.cacheDir.canonicalFile).path
        return Uri.Builder().scheme("content").authority(AUTH).encodedPath("/" + rel.split('/').joinToString("/") { Uri.encode(it) }).build()
    }

    fun isImageMime(m: String) = m in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heif", "image/heic")

    /**
     * Guarda una copia visible para el usuario:
     * fotos -> Imágenes/MultiLector, el resto -> Descargas/MultiLector.
     */
    fun savePublic(ctx: Context, src: File, displayName: String, mime: String, sub: String = "", toDownloads: Boolean = false): Uri? {
        val image = isImageMime(mime) && !toDownloads
        val subPath = if (sub.isNotBlank()) "/" + safe(sub) else ""
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rel = (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/MultiLector" + subPath
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val coll = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(coll, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStoragePublicDirectory(if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)
                val dir = File(root, "MultiLector$subPath")
                dir.mkdirs()
                var f = File(dir, displayName)
                var n = 1
                while (f.exists()) {
                    f = File(dir, "${base(displayName)} ($n).${ext(displayName)}"); n++
                }
                src.copyTo(f)
                MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null)
                Uri.fromFile(f)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Carpeta donde quedan copias de lo generado (para compartir). */
    fun outFile(ctx: Context, name: String): File {
        val d = File(ctx.cacheDir, "out/${System.currentTimeMillis()}")
        d.mkdirs()
        return File(d, safe(name))
    }

    fun shareFiles(act: Activity, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { providerUri(act, it) })
        val mime = files.map { mimeFromExt(ext(it.name)) }.distinct().let { if (it.size == 1) it[0] else "*/*" }
        shareUris(act, uris, mime)
    }

    fun shareUris(act: Activity, uris: ArrayList<Uri>, mime: String) {
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = mime
        val clip = ClipData.newRawUri("", uris[0])
        for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            act.startActivity(Intent.createChooser(i, "Compartir con…"))
        } catch (e: Exception) {
            act.toast("No hay apps para compartir")
        }
    }

    fun openWith(act: Activity, uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            act.startActivity(Intent.createChooser(i, "Abrir con…"))
        } catch (e: Exception) {
            act.toast("No hay otra app que abra este archivo")
        }
    }

    /** Abre un archivo en nuestro visor. */
    fun view(act: Activity, uri: Uri) {
        val i = Intent(act, ViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(i)
    }

    fun view(act: Activity, f: File) = view(act, providerUri(act, f))
}
