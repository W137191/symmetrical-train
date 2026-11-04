package com.example.psapk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.atan2

/**
 * 虚拟试穿预览视图：把“服饰裁切图”通过可变形网格（mesh warp）贴合到“人物照片”上。
 *
 * - 网格控制点可单独拖动 → 让服饰随人物体态/姿态弯曲，避免简单拉伸造成的畸变，保留版型与纹理；
 * - 支持整体拖动、双指缩放 + 旋转（刚性变换叠加在网格之上）；
 * - 按服饰类型自动初始化贴合区域（上衣/裤子/裙子）。
 *
 * 说明：纯几何形变无法替代深度学习试穿模型；本视图提供“高可控”的近似方案，
 * 自动初始化后由用户微调即可获得协调、清晰的换装效果。
 */
class TryOnView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val TYPE_TOP = "top"
        const val TYPE_PANTS = "pants"
        const val TYPE_SKIRT = "skirt"
        const val TYPE_OTHER = "other"
        private const val GRID = 4 // 4x4 单元 → 5x5 控制点
        private const val NONE = 0
        private const val DRAG_POINT = 1
        private const val DRAG_ALL = 2
        private const val PINCH = 3
    }

    private var person: Bitmap? = null
    private var garment: Bitmap? = null
    private var srcW = 0
    private var srcH = 0
    private var type = TYPE_TOP
    private var face: FaceInfo? = null

    // 目标控制点（人物 Bitmap 像素坐标），尺寸 (GRID+1)x(GRID+1)
    private var pts: Array<Array<PointF>> = emptyArray()

    // 整体刚性变换（围绕质心）
    private var globalScale = 1f
    private var globalRot = 0f

    // fit 变换
    private var fitScale = 1f
    private var fitX = 0f
    private var fitY = 0f

    fun setPerson(bmp: Bitmap) {
        person?.recycle()
        person = bmp
        face = FaceUtils.detectFace(bmp) ?: FaceUtils.fallback(bmp)
        if (garment != null) autoInit()
        computeFit()
        requestLayout()
        invalidate()
    }

    fun setGarment(cutout: Bitmap, type: String) {
        garment?.recycle()
        garment = cutout
        srcW = cutout.width
        srcH = cutout.height
        this.type = type
        autoInit()
        invalidate()
    }

    fun setType(t: String) {
        type = t
        autoInit()
        invalidate()
    }

    fun resetPlacement() {
        globalScale = 1f
        globalRot = 0f
        autoInit()
        invalidate()
    }

    fun setGlobalScale(v: Float) { globalScale = v.coerceIn(0.1f, 6f); invalidate() }
    fun setGlobalRotDeg(v: Float) { globalRot = v; invalidate() }

    fun hasPerson(): Boolean = person != null
    fun hasGarment(): Boolean = garment != null

    fun composeToBitmap(): Bitmap {
        val p = person ?: throw IllegalStateException("尚未载入人物照片")
        val out = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawScene(c)
        return out
    }

    // ---------- 自动初始化：根据人脸与类型确定贴合区域 ----------
    private fun autoInit() {
        val p = person ?: return
        val f = face ?: return
        val cx = f.cx
        val cy = f.cy
        val fw = f.fw
        val fh = f.fh
        val bodyW = fw * 2.3f
        val x0: Float; val y0: Float; val x1: Float; val y1: Float
        when (type) {
            TYPE_TOP -> { x0 = cx - bodyW / 2; y0 = cy + fh * 0.8f; x1 = cx + bodyW / 2; y1 = cy + fh * 0.8f + fh * 2.4f }
            TYPE_PANTS -> { x0 = cx - bodyW * 0.55f; y0 = cy + fh * 2.6f; x1 = cx + bodyW * 0.55f; y1 = cy + fh * 2.6f + fh * 3.2f }
            TYPE_SKIRT -> { x0 = cx - bodyW * 0.7f; y0 = cy + fh * 2.4f; x1 = cx + bodyW * 0.7f; y1 = cy + fh * 2.4f + fh * 2.6f }
            else -> { x0 = cx - bodyW / 2; y0 = cy + fh * 0.8f; x1 = cx + bodyW / 2; y1 = cy + fh * 0.8f + fh * 2.4f }
        }
        // 双线性插值填充网格控制点（矩形初始贴合，后续可手动拖点贴合体态）
        val n = GRID + 1
        pts = Array(n) { i ->
            Array(n) { j ->
                val u = i.toFloat() / GRID
                val v = j.toFloat() / GRID
                PointF(x0 + (x1 - x0) * u, y0 + (y1 - y0) * v)
            }
        }
        globalScale = 1f
        globalRot = 0f
    }

    private fun computeFit() {
        val p = person ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        fitScale = min(vw / p.width, vh / p.height)
        fitX = (vw - p.width * fitScale) / 2f
        fitY = (vh - p.height * fitScale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFit()
    }

    private fun eff(i: Int, j: Int): PointF {
        val p = pts[i][j]
        val c = centroid()
        val dx = p.x - c.x; val dy = p.y - c.y
        val a = Math.toRadians(globalRot.toDouble())
        val rx = (dx * cos(a) - dy * sin(a)) * globalScale
        val ry = (dx * sin(a) + dy * cos(a)) * globalScale
        return PointF(c.x + rx.toFloat(), c.y + ry.toFloat())
    }

    private fun centroid(): PointF {
        var sx = 0f; var sy = 0f
        val n = GRID + 1
        for (i in 0..GRID) for (j in 0..GRID) { sx += pts[i][j].x; sy += pts[i][j].y }
        val count = n * n
        return PointF(sx / count, sy / count)
    }

    override fun onDraw(canvas: Canvas) {
        val p = person ?: return
        computeFit()
        canvas.save()
        canvas.translate(fitX, fitY)
        canvas.scale(fitScale, fitScale)
        drawScene(canvas)
        canvas.restore()
    }

    private fun drawScene(canvas: Canvas) {
        val p = person ?: return
        canvas.drawBitmap(p, 0f, 0f, null)
        val g = garment ?: return
        // 逐单元三角网格 warp 绘制
        for (i in 0 until GRID) {
            for (j in 0 until GRID) {
                // 四个角（有效坐标）
                val tl = eff(i, j); val tr = eff(i + 1, j)
                val bl = eff(i, j + 1); val br = eff(i + 1, j + 1)
                // 源三角形对应点（服饰图坐标）
                val su = i.toFloat() / GRID * srcW; val su2 = (i + 1).toFloat() / GRID * srcW
                val sv = j.toFloat() / GRID * srcH; val sv2 = (j + 1).toFloat() / GRID * srcH
                // 三角形 1: TL, TR, BL
                drawTri(canvas, g,
                    floatArrayOf(su, sv, su2, sv, su, sv2),
                    floatArrayOf(tl.x, tl.y, tr.x, tr.y, bl.x, bl.y))
                // 三角形 2: TR, BR, BL
                drawTri(canvas, g,
                    floatArrayOf(su2, sv, su2, sv2, su, sv2),
                    floatArrayOf(tr.x, tr.y, br.x, br.y, bl.x, bl.y))
            }
        }
        // 绘制控制点（便于微调）
        val dot = android.graphics.Paint().apply { color = 0x80FFFFFF.toInt(); style = android.graphics.Paint.Style.FILL }
        val line = android.graphics.Paint().apply { color = 0x40FFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f }
        for (i in 0..GRID) for (j in 0..GRID) {
            val e = eff(i, j)
            canvas.drawCircle(e.x, e.y, 4f, dot)
        }
        // 网格线
        for (i in 0..GRID) { val a = eff(i,0); val b = eff(i,GRID); canvas.drawLine(a.x,a.y,b.x,b.y,line) }
        for (j in 0..GRID) { val a = eff(0,j); val b = eff(GRID,j); canvas.drawLine(a.x,a.y,b.x,b.y,line) }
    }

    private fun drawTri(canvas: Canvas, g: Bitmap, src: FloatArray, dst: FloatArray) {
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 3)) return
        val path = Path()
        path.moveTo(dst[0], dst[1]); path.lineTo(dst[2], dst[3]); path.lineTo(dst[4], dst[5]); path.close()
        canvas.save()
        canvas.clipPath(path)
        canvas.concat(m)
        // 轻微外扩绘制以消除三角形接缝
        canvas.drawBitmap(g, -2f, -2f, null)
        canvas.restore()
    }

    // ---------- 手势：拖动控制点 / 整体平移 / 双指缩放旋转 ----------
    private var gesture = NONE
    private var lastX = 0f; private var lastY = 0f
    private var prevDist = 1f; private var prevAngle = 0f; private var prevMidX = 0f; private var prevMidY = 0f
    private var dragIdx = -1 to -1

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (person == null || garment == null || pts.isEmpty()) return false
        when (e.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                gesture = if (nearestPoint(e.x, e.y).first >= 0) { dragIdx = nearestPoint(e.x, e.y); DRAG_POINT } else DRAG_ALL
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    gesture = PINCH
                    prevDist = fingerDist(e); prevAngle = fingerAngle(e)
                    prevMidX = (e.getX(0) + e.getX(1)) / 2f; prevMidY = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == DRAG_POINT) {
                    val (i, j) = dragIdx
                    if (i >= 0) { pts[i][j] = screenToBmp(e.x, e.y); invalidate() }
                } else if (gesture == DRAG_ALL) {
                    val dx = (e.x - lastX) / fitScale; val dy = (e.y - lastY) / fitScale
                    for (r in pts) for (pt in r) { pt.x += dx; pt.y += dy }
                    lastX = e.x; lastY = e.y; invalidate()
                } else if (gesture == PINCH && e.pointerCount >= 2) {
                    val d = fingerDist(e); val a = fingerAngle(e)
                    val midX = (e.getX(0) + e.getX(1)) / 2f; val midY = (e.getY(0) + e.getY(1)) / 2f
                    val c = centroid()
                    // 整体缩放/旋转围绕质心
                    globalScale = (globalScale * d / prevDist).coerceIn(0.1f, 6f)
                    globalRot += a - prevAngle
                    val mdx = (midX - prevMidX) / fitScale; val mdy = (midY - prevMidY) / fitScale
                    for (r in pts) for (pt in r) { pt.x += mdx; pt.y += mdy }
                    prevDist = d; prevAngle = a; prevMidX = midX; prevMidY = midY
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount <= 1) { gesture = if (nearestPoint(e.x, e.y).first >= 0) DRAG_POINT else DRAG_ALL; lastX = e.x; lastY = e.y }
            }
            else -> return false
        }
        return true
    }

    private fun screenToBmp(x: Float, y: Float): PointF = PointF((x - fitX) / fitScale, (y - fitY) / fitScale)

    private fun nearestPoint(x: Float, y: Float): Pair<Int, Int> {
        val bx = (x - fitX) / fitScale; val by = (y - fitY) / fitScale
        val thr = min(person!!.width, person!!.height) * 0.05f
        var best = -1 to -1; var bestD = thr
        for (i in 0..GRID) for (j in 0..GRID) {
            val d = hypot(pts[i][j].x - bx, pts[i][j].y - by)
            if (d < bestD) { bestD = d; best = i to j }
        }
        return best
    }

    private fun fingerDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy)
    }
    private fun fingerAngle(e: MotionEvent): Float {
        val dx = e.getX(1) - e.getX(0); val dy = e.getY(1) - e.getY(0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }
}
