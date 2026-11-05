package com.example.psapk

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.collections.ArrayDeque

/**
 * 自动遮挡检测（启发式，多特征）。
 *
 * 目标：从单张照片中“无模型地”区分“后期叠加的贴纸/表情包/文字/水印”与“原始内容”。
 * 这在学术上属于难题，本实现用一套实用启发式尽量提升准确率，并支持灵敏度调节。
 *
 * 流程：
 *   1. 大图降采样到 [MAX_DIM] 以内，降低耗时；
 *   2. 计算 Sobel 边缘强度，以及“去平滑后的局部对比度异常”（捕捉半透明/柔边叠加物）；
 *   3. 候选前景 = 强边缘 ∪ 局部对比度异常；
 *   4. 8 邻接连通域分析，对每个区域计算多特征评分：
 *        - 内部颜色平整度（方差小 → 更像纯色叠加物，强烈区分照片纹理）
 *        - 边缘密度（文字/贴纸边界密）
 *        - 紧凑度（填充率高 → 块状而非细线/散点）
 *        - 平均饱和度（表情包/文字通常颜色鲜亮）
 *        - 面积/占比门限
 *   5. 综合置信度 = 加权组合，高于阈值（随 [sensitivity] 下降）即判定为疑似叠加物。
 *
 * 纯 Kotlin、零依赖；该模式为“辅助选区”，复杂场景仍建议手动笔刷涂抹。
 */
object OverlayDetector {

    private const val MAX_DIM = 700
    private const val BLUR_R = 3            // 局部对比度用的去平滑半径（7×7 盒式模糊）
    private const val EDGE_FRAC = 0.18f     // Sobel 边缘阈值占最大值的比例
    private const val ANOM_THR = 16f        // 局部对比度异常阈值（0..255），捕捉半透明叠加
    private const val MIN_AREA = 12         // 最小候选面积（降采样后像素数）
    private const val MAX_AREA_RATIO = 0.6f // 候选面积占全图比例上限（过大视为误选）
    private const val STD_MAX = 45f         // 颜色方差归一化上限（越大越宽容）
    const val DEFAULT_SENSITIVITY = 0.5f    // 默认灵敏度（0 严格，1 宽松）

    /**
     * 自动生成遮挡蒙版（使用默认灵敏度）。
     * @return 与 src 同尺寸的 ARGB_8888 蒙版，alpha 非空像素为疑似遮挡（红色半透明）。
     */
    fun autoMask(src: Bitmap): Bitmap = autoMask(src, DEFAULT_SENSITIVITY)

    /**
     * 自动生成遮挡蒙版（可调灵敏度）。
     * @param sensitivity 0..1，越大检测越宽松（召回高但可能多误圈），越小越严格。
     */
    fun autoMask(src: Bitmap, sensitivity: Float): Bitmap {
        val sens = sensitivity.coerceIn(0f, 1f)
        val longest = max(src.width, src.height)
        val scale = if (longest > MAX_DIM) MAX_DIM.toFloat() / longest else 1f
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else {
            src
        }

        val w = small.width
        val h = small.height
        val n = w * h
        val px = IntArray(n)
        small.getPixels(px, 0, w, 0, 0, w, h)

        val gray = FloatArray(n)
        val sat = FloatArray(n)
        for (i in 0 until n) {
            val r = (px[i] ushr 16) and 0xFF
            val g = (px[i] ushr 8) and 0xFF
            val b = px[i] and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat[i] = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        }

        // 去平滑（盒式模糊）用于局部对比度异常检测
        val blur = boxBlur(gray, w, h, BLUR_R)
        // Sobel 边缘强度
        val edge = FloatArray(n)
        var maxEdge = 1f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = gray[(y - 1) * w + (x - 1)] + 2f * gray[y * w + (x - 1)] + gray[(y + 1) * w + (x - 1)] -
                        gray[(y - 1) * w + (x + 1)] - 2f * gray[y * w + (x + 1)] - gray[(y + 1) * w + (x + 1)]
                val gy = gray[(y - 1) * w + (x - 1)] + 2f * gray[(y - 1) * w + x] + gray[(y - 1) * w + (x + 1)] -
                        gray[(y + 1) * w + (x - 1)] - 2f * gray[(y + 1) * w + x] - gray[(y + 1) * w + (x + 1)]
                val e = hypot(gx, gy)
                edge[i] = e
                if (e > maxEdge) maxEdge = e
            }
        }

        val thrEdge = maxEdge * EDGE_FRAC
        // 候选前景：强边缘 ∪ 局部对比度异常（柔边/半透明叠加）
        val fg = BooleanArray(n)
        val strongEdge = BooleanArray(n)
        for (i in 0 until n) {
            val se = edge[i] > thrEdge
            strongEdge[i] = se
            val anom = kotlin.math.abs(gray[i] - blur[i])
            fg[i] = se || anom > ANOM_THR
        }

        // 连通域 (8 邻接) + 统计
        val comp = IntArray(n) { -1 }
        var compId = 0
        val areas = ArrayList<Int>()
        val fillRatios = ArrayList<Float>()
        val avgSats = ArrayList<Float>()
        val flatness = ArrayList<Float>()
        val edgeDens = ArrayList<Float>()
        val keeps = ArrayList<Boolean>()

        for (i in 0 until n) {
            if (!fg[i] || comp[i] >= 0) continue
            val stack = ArrayDeque<Int>()
            stack.add(i)
            comp[i] = compId
            var area = 0
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var satSum = 0f
            var graySum = 0f
            var graySumSq = 0f
            var edgeCount = 0
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                area++
                val cx = c % w
                val cy = c / w
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                satSum += sat[c]
                val gv = gray[c]
                graySum += gv
                graySumSq += gv * gv
                if (strongEdge[c]) edgeCount++
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val j = ny * w + nx
                        if (fg[j] && comp[j] < 0) {
                            comp[j] = compId
                            stack.add(j)
                        }
                    }
                }
            }
            val bw = (maxX - minX + 1)
            val bh = (maxY - minY + 1)
            val aR = area.toFloat() / n
            val fill = area.toFloat() / (bw * bh)
            val mean = graySum / area
            val variance = (graySumSq / area) - mean * mean
            val std = sqrt(if (variance > 0f) variance else 0f)
            val flat = (1f - std / STD_MAX).coerceIn(0f, 1f)
            val eDens = edgeCount.toFloat() / area

            val gate = area >= MIN_AREA && aR <= MAX_AREA_RATIO
            val fFlat = flat
            val fEdge = (eDens / 0.45f).coerceIn(0f, 1f)
            val fFill = ((fill - 0.4f) / 0.5f).coerceIn(0f, 1f)
            val fSat = ((satSum / area - 0.05f) / 0.25f).coerceIn(0f, 1f)
            // 权重：平整度 0.40（最强区分照片纹理）、边缘密度 0.25、紧凑度 0.20、饱和度 0.10、尺寸基数 0.05
            val conf = 0.40f * fFlat + 0.25f * fEdge + 0.20f * fFill + 0.10f * fSat + 0.05f
            val acceptThr = 0.55f - 0.45f * sens

            areas.add(area)
            fillRatios.add(fill)
            avgSats.add(satSum / area)
            flatness.add(flat)
            edgeDens.add(eDens)
            keeps.add(gate && conf >= acceptThr)
            compId++
        }

        // 生成小尺寸掩码（红色半透明表示待修复区），并对保留区域做 1px 膨胀以覆盖抗锯齿残留
        val maskSmall = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mpx = IntArray(n)
        for (i in 0 until n) {
            if (comp[i] >= 0 && keeps[comp[i]]) mpx[i] = 0x88EF6B73.toInt()
        }
        // 膨胀：保留像素的 4 邻域也标红，避免修复后留下细边残影
        val mpx2 = mpx.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (mpx[i] != 0) {
                    if (x > 0) mpx2[i - 1] = 0x88EF6B73.toInt()
                    if (x < w - 1) mpx2[i + 1] = 0x88EF6B73.toInt()
                    if (y > 0) mpx2[i - w] = 0x88EF6B73.toInt()
                    if (y < h - 1) mpx2[i + w] = 0x88EF6B73.toInt()
                }
            }
        }
        maskSmall.setPixels(mpx2, 0, w, 0, 0, w, h)

        return if (scale < 1f) {
            val up = Bitmap.createScaledBitmap(maskSmall, src.width, src.height, false) // 最近邻，保持硬边缘
            maskSmall.recycle()
            up
        } else {
            maskSmall
        }
    }

    /**
     * 盒式模糊（积分图实现，O(n)），用于局部对比度异常检测。
     */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val iw = w + 1
        val I = FloatArray(iw * (h + 1))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = src[y * w + x]
                I[(y + 1) * iw + (x + 1)] = v + I[y * iw + (x + 1)] + I[(y + 1) * iw + x] - I[y * iw + x]
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val sum = I[(y1 + 1) * iw + (x1 + 1)] - I[y0 * iw + (x1 + 1)] - I[(y1 + 1) * iw + x0] + I[y0 * iw + x0]
                val cnt = (x1 - x0 + 1) * (y1 - y0 + 1)
                out[y * w + x] = sum / cnt
            }
        }
        return out
    }
}
