package com.example.psapk

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
 *
 * 相较 v1.0 的打磨点（低风险、零外部资源、不改算法架构）：
 *  - 大图降采样加速：长边超过 [MAX_SIDE] 时先缩小修复再放大回贴，FMM 复杂度随像素数上升，
 *    此处理可把手机原始照片（数千万像素）的修复耗时压到可接受范围。
 *  - 边缘连续性：修复邻域半径由 3×3 扩大到以 [FILL_RADIUS] 为半径的方块，从更大范围采集
 *    同向结构信息，宽笔刷 / 大块遮挡的边界更顺滑、纹理延续更自然。
 *  - 健壮性兜底：全图都是洞（无已知像素）直接返回原图避免全黑；孤立洞像素无任何可用邻居时
 *    向外环搜索就近填充，杜绝黑点；alpha 阈值集中为常量便于调参。
 */
object Inpainter {

    private const val SQRT2 = 1.41421356f
    private const val EPS = 1e-4f
    // 大图降采样上限：长边超过此值先缩小修复再放大回原尺寸，显著降低耗时。
    private const val MAX_SIDE = 1280
    // 像素被判定为“待修复”的 mask alpha 阈值：mask 中 alpha >= 该值视为遮挡。
    // 检测/笔刷蒙版 alpha 多为 136 / 255，远大于该阈值，阈值仅用于滤除极小残留。
    private const val ALPHA_THRESHOLD = 1
    // 修复时参与加权平均的邻域半径（1 = 3×3，2 = 5×5）：半径越大结构延续越平滑、连续性越好。
    private const val FILL_RADIUS = 2

    /**
     * 公开入口：自动按尺寸选择是否降采样，再调用核心修复。
     * @param src  原始 Bitmap (ARGB_8888)
     * @param mask 掩码 Bitmap (ARGB_8888)，alpha >= [ALPHA_THRESHOLD] 的像素视为需要去除/修复的遮挡区域
     * @return 修复后的 Bitmap（与 src 同尺寸、同配置）
     */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        require(mask.width == src.width && mask.height == src.height) { "src 与 mask 尺寸必须一致" }

        val maxSide = max(src.width, src.height)
        if (maxSide > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / maxSide
            val tw = max(1, (src.width * scale).toInt())
            val th = max(1, (src.height * scale).toInt())
            val sSmall = Bitmap.createScaledBitmap(src, tw, th, true)
            val mSmall = Bitmap.createScaledBitmap(mask, tw, th, true)
            val outSmall = inpaintCore(sSmall, mSmall)
            val out = Bitmap.createScaledBitmap(outSmall, src.width, src.height, true)
            sSmall.recycle()
            mSmall.recycle()
            outSmall.recycle()
            return out
        }
        return inpaintCore(src, mask)
    }

    /**
     * 核心修复：在给定尺寸的 src/mask 上执行 Telea FMM，返回同尺寸修复结果。
     */
    private fun inpaintCore(src: Bitmap, mask: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val spx = IntArray(n)
        val mpx = IntArray(n)
        src.getPixels(spx, 0, w, 0, 0, w, h)
        mask.getPixels(mpx, 0, w, 0, 0, w, h)

        val R = FloatArray(n)
        val G = FloatArray(n)
        val B = FloatArray(n)
        val isHole = BooleanArray(n)

        var knownCount = 0
        for (i in 0 until n) {
            val ma = (mpx[i] ushr 24) and 0xFF
            if (ma >= ALPHA_THRESHOLD) {
                isHole[i] = true
            } else {
                knownCount++
                R[i] = ((spx[i] ushr 16) and 0xFF).toFloat()
                G[i] = ((spx[i] ushr 8) and 0xFF).toFloat()
                B[i] = (spx[i] and 0xFF).toFloat()
            }
        }

        // 健壮性：没有任何已知像素（整张图都是遮挡）无法修复，直接返回原图副本避免全黑。
        if (knownCount == 0) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        // 健壮性：没有任何洞，原样返回。
        if (knownCount == n) {
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
     * 因此可直接用于加权平均。邻域半径由 [FILL_RADIUS] 控制，半径越大结构延续越平滑。
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
        val radius = FILL_RADIUS

        for (p in holes) {
            val x = p % w
            val y = p / w

            fun isUsable(k: Int): Boolean = !isHole[k] || filled[k]

            var sR = 0f; var sG = 0f; var sB = 0f; var wsum = 0f

            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
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

                    val dist = hypot(dx.toFloat(), dy.toFloat())
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
                // 兜底 1：等权平均窗口内可用邻居
                var aR = 0f; var aG = 0f; var aB = 0f; var c = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
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
                } else {
                    // 兜底 2：窗口内仍无任何可用邻居（极小概率的孤立洞），向外环搜索就近像素填充，杜绝黑点。
                    val q = findNearestUsable(p, ::isUsable, w, h, max(w, h))
                    if (q >= 0) {
                        R[p] = R[q]; G[p] = G[q]; B[p] = B[q]
                    }
                }
            }
            filled[p] = true
        }
    }

    /**
     * 从像素 p 出发逐环向外搜索第一个可用（已知或已修复）像素，返回其下标；找不到返回 -1。
     */
    private fun findNearestUsable(
        p: Int, isUsable: (Int) -> Boolean, w: Int, h: Int, maxRing: Int
    ): Int {
        val x = p % w
        val y = p / w
        for (r in 1..maxRing) {
            for (dy in -r..r) {
                for (dx in -r..r) {
                    // 仅检查半径为 r 的方框边界（即当前环），避免重复内层
                    if (dx != -r && dx != r && dy != -r && dy != r) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (isUsable(q)) return q
                }
            }
        }
        return -1
    }
}
