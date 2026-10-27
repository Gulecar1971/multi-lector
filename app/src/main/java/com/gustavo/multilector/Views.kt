package com.gustavo.multilector

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import java.util.concurrent.Executors

/** Foto con zoom de dos dedos, arrastre y doble toque. */
@SuppressLint("ClickableViewAccessibility")
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private var minScale = 1f
    private val vals = FloatArray(9)

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = scale()
            var f = d.scaleFactor
            if (cur * f < minScale) f = minScale / cur
            if (cur * f > minScale * 10) f = minScale * 10 / cur
            m.postScale(f, f, d.focusX, d.focusY)
            fix(); return true
        }
    })

    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy); fix(); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale() > minScale * 1.2f) fitNow() else { m.postScale(2.5f, 2.5f, e.x, e.y); fix() }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        setBackgroundColor(Color.parseColor("#222222"))
    }

    private fun scale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }

    fun fitNow() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0 || width == 0) return
        val s = minOf(width / dw, height / dh)
        m.reset(); m.setScale(s, s); m.postTranslate((width - dw * s) / 2, (height - dh * s) / 2)
        minScale = s
        imageMatrix = m
    }

    private fun fix() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fitNow() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        if (!scaleDet.isInProgress) gest.onTouchEvent(e)
        return true
    }
}

/** Contenedor que deja al hijo más ancho que la pantalla y se arrastra de costado. */
@SuppressLint("ClickableViewAccessibility")
class PanFrame(ctx: Context) : FrameLayout(ctx) {
    var childWidth = 0
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val cw = if (childWidth > 0) childWidth else w
        for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) { val c = getChildAt(i); c.layout(0, 0, c.measuredWidth, c.measuredHeight) }
        clamp()
    }

    fun maxScroll() = maxOf(0, (if (childWidth > 0) childWidth else width) - width)
    fun clamp() { scrollTo(scrollX.coerceIn(0, maxScroll()), 0) }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                if (maxScroll() > 0 && dx > slop && dx > dy * 1.2f) { dragging = true; lastX = e.x; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { scrollBy((lastX - e.x).toInt(), 0); lastX = e.x; clamp() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

/** Visor de PDF: páginas una debajo de otra, zoom con dos dedos, doble toque o botones. */
@SuppressLint("ViewConstructor")
class PdfPane(ctx: Context, private val doc: PdfDoc, startPage: Int, private val onPage: (Int) -> Unit) : FrameLayout(ctx) {
    private val pan = PanFrame(ctx)
    val list = ListView(ctx)
    var zoom = 1f
        private set
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = HashSet<String>()
    private val sizes = doc.sizes
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var pinch = 1f
    private var cancelled = false
    private var closed = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = doc.count
        override fun getItem(p: Int) = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val iv = (convert as? ImageView) ?: ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.WHITE) }
            val pw = pageWidth()
            val sz = sizes[pos]
            val h = (pw * sz[1] / sz[0]).toInt().coerceAtLeast(1)
            iv.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            iv.tag = pos
            val key = "$gen:$pos"
            val b = cache.get(key)
            if (b != null && !b.isRecycled) iv.setImageBitmap(b) else { iv.setImageDrawable(ColorDrawable(Color.WHITE)); request(pos, pw) }
            return iv
        }
    }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinch = 1f; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch *= d.scaleFactor
            val z = (zoom * pinch).coerceIn(1f, 4f) / zoom
            pan.pivotX = d.focusX; pan.pivotY = d.focusY
            pan.scaleX = z; pan.scaleY = z
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            pan.scaleX = 1f; pan.scaleY = 1f
            setZoom(zoom * pinch, d.focusX)
        }
    })

    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(if (zoom > 1.2f) 1f else 2.2f, e.x); return true }
    })

    init {
        setBackgroundColor(Color.parseColor("#C9CED6"))
        list.divider = ColorDrawable(Color.parseColor("#C9CED6"))
        list.dividerHeight = ctx.dp(8)
        list.setBackgroundColor(Color.parseColor("#C9CED6"))
        list.adapter = adapter
        list.isFastScrollEnabled = doc.count > 8
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                val mid = if (visible > 1 && (list.getChildAt(0)?.bottom ?: 0) < height / 3) first + 1 else first
                onPage(mid.coerceIn(0, doc.count - 1))
            }
        })
        pan.addView(list)
        addView(pan, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        list.post { if (startPage in 1 until doc.count) list.setSelection(startPage) }
    }

    private fun pageWidth(): Int = if (pan.childWidth > 0) pan.childWidth else (if (width > 0) width else resources.displayMetrics.widthPixels)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w != ow) main.post { applyWidth() }
    }

    private fun applyWidth() {
        pan.childWidth = (width * zoom).toInt()
        gen++
        cache.evictAll()
        pan.requestLayout()
        adapter.notifyDataSetChanged()
    }

    fun setZoom(z: Float, focusX: Float = width / 2f) {
        val nz = z.coerceIn(1f, 4f)
        if (Math.abs(nz - zoom) < 0.02f) return
        val pos = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        val r = nz / zoom
        val oldX = pan.scrollX
        zoom = nz
        applyWidth()
        list.post {
            list.setSelectionFromTop(pos, (top * r).toInt())
            pan.scrollTo(((oldX + focusX) * r - focusX).toInt(), 0)
            pan.clamp()
        }
    }

    fun goTo(page: Int) = list.setSelection(page.coerceIn(0, doc.count - 1))

    private fun request(pos: Int, w: Int) {
        val g = gen
        val key = "$g:$pos"
        if (!pending.add(key)) return
        exec.execute {
            if (closed || g != gen) { main.post { pending.remove(key) }; return@execute }
            val bmp = try {
                val maxW = minOf(w, (resources.displayMetrics.widthPixels * 2.6f).toInt())
                doc.render(pos, maxW / sizes[pos][0], 9_000_000)
            } catch (_: Throwable) { null }
            main.post {
                pending.remove(key)
                if (bmp == null || closed) return@post
                if (g != gen) { bmp.recycle(); return@post }
                cache.put(key, bmp)
                for (i in 0 until list.childCount) {
                    val c = list.getChildAt(i) as? ImageView ?: continue
                    if (c.tag == pos) c.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDet.onTouchEvent(ev)
        gest.onTouchEvent(ev)
        if (ev.pointerCount > 1 || scaleDet.isInProgress) {
            if (!cancelled) {
                val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c); c.recycle(); cancelled = true
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) cancelled = false
        if (cancelled) return true
        return super.dispatchTouchEvent(ev)
    }

    fun close() {
        closed = true
        exec.shutdownNow()
        cache.evictAll()
    }
}
