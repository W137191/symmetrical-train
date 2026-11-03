package com.example.psapk

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 自动遮挡检测（启发式）。
 *
 * 说明：从单张照片中“无模型地”区分“后期叠加的贴纸/表情包/文字”与“原始内容”，
 * 在学术上属于难题。本实现采用一套实用启发式：
 *   1. 计算 Sobel 边缘强度；
 *   2. 阈值化得到候选前景（强边缘）；
 *   3. 连通域分析，按“面积适中 + 填充率高（块状而非细线/照片边界）+ 平均饱和度较高”
 *      筛选疑似叠加物（表情包/贴纸/文字通常颜色鲜明、边界闭合、内部较均匀）。
 *
 * 该模式为“辅助选区”，精度有限；对于复杂场景，请使用手动笔刷涂抹以获得最佳效果。
 */
object OverlayDetector {

    fun autoMask(src: Bitmap): Bitmap {
        val maxDim = 700
        val longest = max(src.width, src.height)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
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

        val thr = maxEdge * 0.22f
        val fg = BooleanArray(n) { edge[it] > thr }

        // 连通域 (8 邻接) + 统计
        val comp = IntArray(n) { -1 }
        var compId = 0
        val areas = ArrayList<Int>()
        val fillRatios = ArrayList<Float>()
        val avgSats = ArrayList<Float>()

        for (i in 0 until n) {
            if (!fg[i] || comp[i] >= 0) continue
            val stack = ArrayDeque<Int>()
            stack.add(i)
            comp[i] = compId
            var area = 0
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var satSum = 0f
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
            areas.add(area)
            fillRatios.add(area.toFloat() / (bw * bh))
            avgSats.add(satSum / area)
            compId++
        }

        // 筛选疑似叠加物
        val keep = BooleanArray(compId)
        val nF = n.toFloat()
        for (id in 0 until compId) {
            val areaRatio = areas[id] / nF
            val ok = areas[id] >= 25 &&
                    areaRatio <= 0.35f &&
                    fillRatios[id] >= 0.5f &&
                    avgSats[id] >= 0.10f
            keep[id] = ok
        }

        // 生成小尺寸掩码（红色半透明表示待修复区）
        val maskSmall = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mpx = IntArray(n)
        for (i in 0 until n) {
            if (comp[i] >= 0 && keep[comp[i]]) {
                mpx[i] = 0x88FF0000.toInt() // alpha 136, 红
            }
        }
        maskSmall.setPixels(mpx, 0, w, 0, 0, w, h)

        return if (scale < 1f) {
            val up = Bitmap.createScaledBitmap(maskSmall, src.width, src.height, false) // 最近邻，保持硬边缘
            maskSmall.recycle()
            up
        } else {
            maskSmall
        }
    }
}
