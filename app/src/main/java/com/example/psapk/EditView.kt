package com.example.psapk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 自定义编辑视图：
 *  - 显示当前 Bitmap；
 *  - 维护一张与 Bitmap 同尺寸的掩码（红色半透明 = 待修复遮挡区）；
 *  - 支持手指/笔刷在掩码上涂抹（DRAW）或擦除（ERASE）；
 *  - 图像与掩码共用同一套 fit 变换，保证对齐；
 *  - 可实时显示/隐藏蒙版；
 *  - 对比模式：拖动分割线查看“原图 vs 修复结果”；
 *  - 撤销栈：可撤销修复与笔刷/橡皮操作。
 */
class EditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val MODE_DRAW = 0
        const val MODE_ERASE = 1
        private const val MAX_UNDO = 8
    }

    private var bitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    /** 载入时的原始图像，用于对比模式。 */
    private var compareOriginal: Bitmap? = null

    private val drawPaint = Paint().apply {
        isAntiAlias = true
        color = Color.RED
        alpha = 170
    }
    private val erasePaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        color = Color.TRANSPARENT
    }
    private val linePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    var brushSize = 40f
    var mode = MODE_DRAW
    var showMask = true
    var compareMode = false
    private var dividerFrac = 0.5f

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f

    // 撤销栈：bitmap 为 null 表示仅撤销掩码（笔刷/橡皮），非 null 表示同时撤销图像（修复）。
    private data class UndoEntry(val bitmap: Bitmap?, val mask: Bitmap)
    private val undoStack = ArrayDeque<UndoEntry>()
    private var pendingStrokeMask: Bitmap? = null

    fun setBitmap(bmp: Bitmap) {
        bitmap?.recycle()
        maskBitmap?.recycle()
        compareOriginal?.recycle()
        bitmap = bmp
        compareOriginal = Bitmap.createBitmap(bmp)
        maskBitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(maskBitmap!!)
        dividerFrac = 0.5f
        computeTransform()
        requestLayout()
        invalidate()
    }

    /** 应用修复结果：替换为新 Bitmap 并清空掩码；保留原图用于对比。 */
    fun applyResult(bmp: Bitmap) {
        bitmap?.recycle()
        maskBitmap?.recycle()
        bitmap = bmp
        maskBitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(maskBitmap!!)
        computeTransform()
        invalidate()
    }

    fun getBitmap(): Bitmap? = bitmap
    fun getMaskBitmap(): Bitmap? = maskBitmap

    /** 将检测得到的掩码叠加到当前掩码上。 */
    fun setMaskFromBitmap(mask: Bitmap) {
        maskBitmap?.let { c ->
            c.drawBitmap(mask, 0f, 0f, null)
            invalidate()
        }
    }

    /** 为“仅修改掩码”的操作（自动检测 / 外部覆盖）记录一步撤销。 */
    fun pushMaskUndo() {
        if (maskBitmap != null) pushUndo(UndoEntry(null, copyMask()))
    }

    // ---------- 撤销 ----------
    fun storeUndoFull() {
        val b = bitmap ?: return
        val m = maskBitmap ?: return
        pushUndo(UndoEntry(copyBitmap(b), copyBitmap(m)))
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val e = undoStack.removeLast()
        if (e.bitmap != null) {
            bitmap?.recycle()
            bitmap = e.bitmap
            compareOriginal?.let { /* 保持原图不变 */ }
            computeTransform()
        }
        maskBitmap?.recycle()
        maskBitmap = e.mask
        maskCanvas = Canvas(e.mask)
        invalidate()
        return true
    }

    private fun pushUndo(entry: UndoEntry) {
        if (undoStack.size >= MAX_UNDO) {
            val old = undoStack.removeFirst()
            old.bitmap?.recycle()
            old.mask.recycle()
        }
        undoStack.add(entry)
    }

    private fun copyBitmap(src: Bitmap): Bitmap = Bitmap.createBitmap(src)
    private fun copyMask(): Bitmap = Bitmap.createBitmap(maskBitmap!!)

    // ---------- 变换 ----------
    private fun computeTransform() {
        val b = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        scale = minOf(vw / b.width, vh / b.height)
        offsetX = (vw - b.width * scale) / 2f
        offsetY = (vh - b.height * scale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        computeTransform()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        if (compareMode && compareOriginal != null) {
            // 右侧为当前（修复后）图像，左侧为原图
            canvas.drawBitmap(b, 0f, 0f, null)
            val div = dividerFrac * b.width
            canvas.save()
            canvas.clipRect(0f, 0f, div, b.height.toFloat())
            canvas.drawBitmap(compareOriginal!!, 0f, 0f, null)
            canvas.restore()
            linePaint.strokeWidth = 4f / scale
            canvas.drawLine(div, 0f, div, b.height.toFloat(), linePaint)
        } else {
            canvas.drawBitmap(b, 0f, 0f, null)
            if (showMask) maskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
        canvas.restore()
    }

    private fun viewToBitmap(vx: Float, vy: Float): Pair<Float, Float> {
        return Pair((vx - offsetX) / scale, (vy - offsetY) / scale)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false

        // 对比模式：拖动分割线
        if (compareMode) {
            when (e.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    val frac = ((e.x - offsetX) / (b.width * scale)).coerceIn(0f, 1f)
                    dividerFrac = frac
                    invalidate()
                }
                else -> return false
            }
            return true
        }

        if (maskCanvas == null) return false
        val (bx, by) = viewToBitmap(e.x, e.y)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                pendingStrokeMask = copyMask()
                lastX = bx; lastY = by
                stamp(bx, by)
            }
            MotionEvent.ACTION_MOVE -> {
                stampLine(lastX, lastY, bx, by)
                lastX = bx; lastY = by
            }
            MotionEvent.ACTION_UP -> {
                stamp(bx, by)
                pendingStrokeMask?.let { pushUndo(UndoEntry(null, it)) }
                pendingStrokeMask = null
            }
            else -> return false
        }
        invalidate()
        return true
    }

    private fun stamp(cx: Float, cy: Float) {
        val p = if (mode == MODE_DRAW) drawPaint else erasePaint
        maskCanvas!!.drawCircle(cx, cy, brushSize / 2f, p)
    }

    private fun stampLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        val dist = hypot(x1 - x0, y1 - y0)
        val steps = maxOf(1, (dist / (brushSize / 4f)).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            stamp(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }
}
