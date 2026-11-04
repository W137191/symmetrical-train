package com.example.psapk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 换装预览视图：在人物照片上叠加服装道具，支持：
 *  - 自动按人脸位置/尺寸放置；
 *  - 单指拖动移动；双指缩放 + 旋转 + 平移微调；
 *  - 合成导出为 Bitmap。
 *
 * 图像与道具共用同一 fit 变换，保证对齐。
 */
class CostumeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var person: Bitmap? = null
    private var costume: Costume? = null
    private var face: FaceInfo? = null

    // 道具变换（Bitmap 像素坐标）：中心位置、相对缩放、旋转角度
    private var ox = 0f
    private var oy = 0f
    private var relScale = 1f
    private var rotation = 0f

    // fit 变换
    private var fitScale = 1f
    private var fitX = 0f
    private var fitY = 0f

    fun setPerson(bmp: Bitmap) {
        person?.recycle()
        person = bmp
        face = FaceUtils.detectFace(bmp) ?: FaceUtils.fallback(bmp)
        costume?.let { autoPlace(it) }
        computeFit()
        requestLayout()
        invalidate()
    }

    fun setCostume(c: Costume) {
        costume = c
        autoPlace(c)
        invalidate()
    }

    fun currentCostume(): Costume? = costume

    /** 重新把当前服装按人脸自动贴合（撤销手动微调）。 */
    fun resetPlacement() {
        costume?.let { autoPlace(it) }
        invalidate()
    }

    /** 根据人脸与道具锚点，重置道具位置/缩放/旋转到“自动贴合”状态。 */
    fun autoPlace(c: Costume) {
        val f = face ?: return
        val (cx, cy) = c.anchor(f.cx, f.cy, f.fw, f.fh)
        ox = cx
        oy = cy
        relScale = 1f
        rotation = 0f
    }

    fun setRelScale(v: Float) { relScale = v.coerceIn(0.1f, 6f); invalidate() }
    fun setRotationDeg(v: Float) { rotation = v; invalidate() }

    fun composeToBitmap(): Bitmap {
        val p = person ?: throw IllegalStateException("尚未载入人物照片")
        val out = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawScene(c, p, 1f, 0f, 0f)
        return out
    }

    fun hasPerson(): Boolean = person != null

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

    override fun onDraw(canvas: Canvas) {
        val p = person ?: return
        computeFit()
        canvas.save()
        canvas.translate(fitX, fitY)
        canvas.scale(fitScale, fitScale)
        drawScene(canvas, p, fitScale, fitX, fitY)
        canvas.restore()
    }

    /**
     * 把人物 + 道具绘制到给定画布（同一坐标系，1:1 或已 fit 缩放均可）。
     */
    private fun drawScene(canvas: Canvas, p: Bitmap, _scale: Float, _x: Float, _y: Float) {
        canvas.drawBitmap(p, 0f, 0f, null)
        val c = costume ?: return
        val f = face ?: return
        val size = c.baseSize(f.fw, f.fh) * relScale
        if (size <= 0) return
        canvas.save()
        canvas.rotate(rotation, ox, oy)
        c.draw(canvas, ox, oy, size)
        canvas.restore()
    }

    // ---------- 触摸：拖动 / 双指缩放旋转 ----------
    private var mode = NONE
    private var lastX = 0f; private var lastY = 0f
    private var prevDist = 1f; private var prevAngle = 0f; private var prevMidX = 0f; private var prevMidY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (person == null || costume == null) return false
        when (e.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mode = DRAG
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    mode = PINCH
                    prevDist = fingerDist(e)
                    prevAngle = fingerAngle(e)
                    prevMidX = (e.getX(0) + e.getX(1)) / 2f
                    prevMidY = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    ox += (e.x - lastX) / fitScale
                    oy += (e.y - lastY) / fitScale
                    lastX = e.x; lastY = e.y
                    invalidate()
                } else if (mode == PINCH && e.pointerCount >= 2) {
                    val d = fingerDist(e)
                    val a = fingerAngle(e)
                    val midX = (e.getX(0) + e.getX(1)) / 2f
                    val midY = (e.getY(0) + e.getY(1)) / 2f
                    relScale = (relScale * d / prevDist).coerceIn(0.1f, 6f)
                    rotation += a - prevAngle
                    ox += (midX - prevMidX) / fitScale
                    oy += (midY - prevMidY) / fitScale
                    prevDist = d; prevAngle = a; prevMidX = midX; prevMidY = midY
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount <= 1) mode = DRAG
            }
            else -> return false
        }
        return true
    }

    private fun fingerDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy)
    }

    private fun fingerAngle(e: MotionEvent): Float {
        val dx = e.getX(1) - e.getX(0)
        val dy = e.getY(1) - e.getY(0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val PINCH = 2
    }
}
