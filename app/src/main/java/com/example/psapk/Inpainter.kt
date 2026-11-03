package com.example.psapk

import android.graphics.Bitmap
import kotlin.math.hypot

/**
 * 基于 Telea (2004) 快速行进法 (Fast Marching Method) 的图像修复 (Inpainting)。
 *
 * 思路：
 *  1. 由“待修复区域（洞）”的边界向内外做 FMM 距离变换，得到每个像素到边界的到达时间 T。
 *  2. 按 T 从小到大的顺序逐个填充洞内像素：用其“已知邻居”做加权平均，
 *     权重 = 等值线方向项(dir) × 距离项(dst) × 时间项(lev)，
 *     从而使被覆盖区域的颜色、纹理与光照沿原有结构自然延续。
 *
 * 纯 Kotlin 实现，无任何原生 / 第三方依赖，保证可直接编译运行。
 */
object Inpainter {

    private const val SQRT2 = 1.41421356f
    private const val EPS = 1e-4f

    /**
     * @param src  原始 Bitmap (ARGB_8888)
     * @param mask 掩码 Bitmap (ARGB_8888)，alpha > 0 的像素视为需要去除/修复的遮挡区域
     * @return 修复后的 Bitmap
     */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        require(mask.width == w && mask.height == h) { "src 与 mask 尺寸必须一致" }

        val n = w * h
        val spx = IntArray(n)
        val mpx = IntArray(n)
        src.getPixels(spx, 0, w, 0, 0, w, h)
        mask.getPixels(mpx, 0, w, 0, 0, w, h)

        val R = FloatArray(n)
        val G = FloatArray(n)
        val B = FloatArray(n)
        val isHole = BooleanArray(n)

        for (i in 0 until n) {
            val ma = (mpx[i] ushr 24) and 0xFF
            if (ma > 0) {
                isHole[i] = true
            } else {
                R[i] = ((spx[i] ushr 16) and 0xFF).toFloat()
                G[i] = ((spx[i] ushr 8) and 0xFF).toFloat()
                B[i] = (spx[i] and 0xFF).toFloat()
            }
        }

        if (isHole.none()) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }

        val T = computeDistance(isHole, w, h)
        fillHoles(R, G, B, isHole, T, w, h)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val opx = IntArray(n)
        for (i in 0 until n) {
            val r = clamp(R[i])
            val g = clamp(G[i])
            val b = clamp(B[i])
            opx[i] = (0xFF shl 24) or
                    ((r.toInt() and 0xFF) shl 16) or
                    ((g.toInt() and 0xFF) shl 8) or
                    (b.toInt() and 0xFF)
        }
        out.setPixels(opx, 0, w, 0, 0, w, h)
        return out
    }

    private fun clamp(v: Float): Float = if (v < 0f) 0f else if (v > 255f) 255f else v

    /**
     * 快速行进距离变换：从洞的边界（紧邻洞的已知像素）出发，
     * 用欧氏距离（含对角 sqrt2）向整张图传播，得到每个像素到边界的到达时间 T。
     */
    private fun computeDistance(isHole: BooleanArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val T = FloatArray(n) { Float.POSITIVE_INFINITY }
        val done = BooleanArray(n)
        val pq = java.util.PriorityQueue<Int>(compareBy { T[it] })

        // 种子：紧邻洞的已知像素，T = 0
        for (i in 0 until n) {
            if (isHole[i]) continue
            val x = i % w
            val y = i / w
            val adj = (x > 0 && isHole[i - 1]) ||
                    (x < w - 1 && isHole[i + 1]) ||
                    (y > 0 && isHole[i - w]) ||
                    (y < h - 1 && isHole[i + w])
            if (adj) {
                T[i] = 0f
                pq.add(i)
            }
        }

        while (pq.isNotEmpty()) {
            val i = pq.poll()!!
            if (done[i]) continue
            done[i] = true
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (done[j]) continue
                    val d = if (dx == 0 || dy == 0) 1f else SQRT2
                    val nt = T[i] + d
                    if (nt < T[j]) {
                        T[j] = nt
                        pq.add(j)
                    }
                }
            }
        }
        return T
    }

    /**
     * 按 T 升序填充所有洞内像素。处理某个洞像素时，其 T 更小的邻居必然已经修复或本就是已知，
     * 因此可直接用于加权平均。
     */
    private fun fillHoles(
        R: FloatArray, G: FloatArray, B: FloatArray,
        isHole: BooleanArray, T: FloatArray, w: Int, h: Int
    ) {
        val n = w * h
        val holes = ArrayList<Int>(n)
        for (i in 0 until n) if (isHole[i]) holes.add(i)
        holes.sortBy { T[it] }

        val filled = BooleanArray(n) // 洞像素被修复后置 true，可作为后续邻居使用

        for (p in holes) {
            val x = p % w
            val y = p / w

            fun isUsable(k: Int): Boolean = !isHole[k] || filled[k]

            var sR = 0f; var sG = 0f; var sB = 0f; var wsum = 0f

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (!isUsable(q)) continue

                    // 在邻居 q 处估计梯度（仅使用可用像素，缺失处用自身值代替）
                    val left = if (nx > 0 && isUsable(q - 1)) R[q - 1] else R[q]
                    val right = if (nx < w - 1 && isUsable(q + 1)) R[q + 1] else R[q]
                    val up = if (ny > 0 && isUsable(q - w)) R[q - w] else R[q]
                    val down = if (ny < h - 1 && isUsable(q + w)) R[q + w] else R[q]
                    val gx = (right - left) * 0.5f
                    val gy = (down - up) * 0.5f

                    val vx = (x - nx).toFloat()
                    val vy = (y - ny).toFloat()
                    val gradMag = hypot(gx, gy)
                    val dir = if (gradMag > EPS) {
                        val d = (vx * gx + vy * gy) / gradMag
                        if (d < 0f) 0f else d
                    } else 1f

                    val dist = if (dx == 0 || dy == 0) 1f else SQRT2
                    val dst = 1f / (dist + EPS)
                    val lev = 1f / (T[q] + EPS)
                    val wq = dir * dst * lev
                    if (wq > 0f) {
                        sR += wq * R[q]
                        sG += wq * G[q]
                        sB += wq * B[q]
                        wsum += wq
                    }
                }
            }

            if (wsum > 0f) {
                R[p] = sR / wsum
                G[p] = sG / wsum
                B[p] = sB / wsum
            } else {
                // 兜底：等权平均可用邻居
                var aR = 0f; var aG = 0f; var aB = 0f; var c = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (!isUsable(q)) continue
                        aR += R[q]; aG += G[q]; aB += B[q]; c++
                    }
                }
                if (c > 0) {
                    R[p] = aR / c; G[p] = aG / c; B[p] = aB / c
                }
            }
            filled[p] = true
        }
    }
}
