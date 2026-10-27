package com.gustavo.multilector

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Comparte archivos del caché de la app (resultados, fotos de cámara) sin librerías extra. */
class FilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val root = context!!.cacheDir.canonicalFile
        val f = File(root, uri.path ?: "").canonicalFile
        if (!f.path.startsWith(root.path + File.separator)) throw FileNotFoundException("fuera de la carpeta")
        return f
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String = F.mimeFromExt(F.ext(uri.lastPathSegment ?: ""))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(uri)
        val m = if (mode.contains('w')) {
            f.parentFile?.mkdirs()
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        } else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(f, m)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Lista de archivos abiertos hace poco. */
object Recents {
    class Item(val uri: String, val name: String, val time: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("recientes", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Item> = try {
        val a = JSONArray(prefs(ctx).getString("lista", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.getString("u"), it.getString("n"), it.getLong("t")) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, items: List<Item>) {
        val a = JSONArray()
        items.take(25).forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("t", it.time)) }
        prefs(ctx).edit().putString("lista", a.toString()).apply()
    }

    fun add(ctx: Context, uri: Uri, name: String) {
        if (uri.scheme != "content" || uri.authority == F.AUTH) return
        val s = uri.toString()
        save(ctx, listOf(Item(s, name, System.currentTimeMillis())) + list(ctx).filter { it.uri != s })
    }

    fun remove(ctx: Context, uri: String) = save(ctx, list(ctx).filter { it.uri != uri })
    fun clear(ctx: Context) = save(ctx, emptyList())

    fun lastPage(ctx: Context, uri: Uri): Int = prefs(ctx).getInt("pag_" + uri.toString().hashCode(), 0)
    fun setLastPage(ctx: Context, uri: Uri, p: Int) = prefs(ctx).edit().putInt("pag_" + uri.toString().hashCode(), p).apply()
}
