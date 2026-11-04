package com.example.psapk

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector

/**
 * 人脸检测工具：在人物照片中定位人脸（中心点 + 估算的脸部宽高），
 * 用于把服装道具自动贴合到头部/脖颈相应位置。
 *
 * 说明：使用 Android 自带的 FaceDetector（无需额外模型，离线可用，但仅对较正面的人脸有效）。
 * 检测失败时返回 null，由调用方回退到“图像居中”放置。
 */
data class FaceInfo(val cx: Float, val cy: Float, val fw: Float, val fh: Float)

object FaceUtils {

    fun detectFace(src: Bitmap): FaceInfo? {
        val maxW = 800
        val w = if (src.width > maxW) maxW else src.width
        val h = (src.height * w.toFloat() / src.width).toInt()
        val ew = if (w % 2 == 1) w - 1 else w
        val eh = if (h % 2 == 1) h - 1 else h
        if (ew < 8 || eh < 8) return null

        val tmp = Bitmap.createScaledBitmap(src, ew, eh, true)
        val gray = tmp.copy(Bitmap.Config.RGB_565, true)
        tmp.recycle()

        return try {
            val fd = FaceDetector(ew, eh, 1)
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val n = fd.findFaces(gray, faces)
            gray.recycle()
            if (n > 0) {
                val face = faces[0]!!
                val mid = PointF()
                face.getMidPoint(mid)
                val eyes = if (face.eyesDistance > 0f) face.eyesDistance else (ew * 0.25f)
                val sx = src.width.toFloat() / ew
                val sy = src.height.toFloat() / eh
                val cx = mid.x * sx
                val cy = mid.y * sy
                val fw = eyes * 2.4f * sx
                val fh = fw * 1.3f
                FaceInfo(cx, cy, fw, fh)
            } else null
        } catch (e: Exception) {
            gray.recycle()
            null
        }
    }

    /** 无检测结果时的回退：以图像中心、按图像尺寸估算的“虚拟人脸框”。 */
    fun fallback(src: Bitmap): FaceInfo {
        val cx = src.width / 2f
        val cy = src.height * 0.42f
        val fw = src.width * 0.42f
        val fh = fw * 1.3f
        return FaceInfo(cx, cy, fw, fh)
    }
}
