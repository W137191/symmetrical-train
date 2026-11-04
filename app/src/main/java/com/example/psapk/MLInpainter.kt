package com.example.psapk

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 端侧 ML 修复（LaMa-Dilated / LiteRT）。
 *
 * 在 assets 中放置 `lama_dilated.tflite`（Apache-2.0，来自 Qualcomm AI Hub / LiteRT-Models）后自动启用；
 * 权重缺失或推理失败则 [inpaint] 返回 null，由调用方回退到经典 Telea FMM（[Inpainter]），保证功能始终可用。
 *
 * 约定模型 I/O（以官方 `lama_dilated.tflite` 为准，代码同时兼容 NCHW 布局与单输入 4 通道拼接变体）：
 *  - 输入 0：图像 [1, H, W, 3] NHWC，RGB 归一化到 0..1（÷255）
 *  - 输入 1：掩码 [1, H, W, 1] NHWC，单通道 0..1（1 = 待修复区域）
 *  - 输出 0：修复后图像 [1, H, W, 3] NHWC，0..1
 * 输入尺寸由模型张量形状动态读取（通常为 512×512），源图会先缩放至该尺寸再推理，结果放大回原尺寸。
 */
object MLInpainter {

    private const val MODEL = "lama_dilated.tflite"
    private const val MASK_THRESHOLD = 128 // 掩码 alpha >= 该值视为待修复

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var available = false
    @Volatile private var tried = false

    /** 模型权重是否已就绪（会触发一次加载尝试）。 */
    @Synchronized
    fun isAvailable(context: Context): Boolean {
        ensureLoaded(context)
        return available
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (tried) return
        tried = true
        try {
            val buffer = loadModelFile(context, MODEL)
            val opts = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(buffer, opts)
            available = true
        } catch (e: Exception) {
            available = false
        }
    }

    private fun loadModelFile(context: Context, path: String): MappedByteBuffer {
        val fd = context.assets.openFd(path)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /**
     * 端侧 ML 修复。成功返回修复结果（与 src 同尺寸），失败/无模型返回 null。
     */
    @Synchronized
    fun inpaint(context: Context, src: Bitmap, mask: Bitmap): Bitmap? {
        ensureLoaded(context)
        val interp = interpreter ?: return null
        return try {
            val nIn = interp.inputTensorCount
            val imgShape = interp.getInputTensor(0).shape()
            val imgNchw = isNchw(imgShape)
            val (h, w) = hwOf(imgShape)

            val sImg = Bitmap.createScaledBitmap(src, w, h, true)
            val sMask = Bitmap.createScaledBitmap(mask, w, h, true)
            val imgNhwc = fillNhwc(sImg, 3, false, h, w)
            val maskNhwc = fillNhwc(sMask, 1, true, h, w)

            val inputs = if (nIn >= 2) {
                val i0 = if (imgNchw) nhwcToNchw(imgNhwc, h, w, 3) else imgNhwc
                val i1 = if (imgNchw) nhwcToNchw(maskNhwc, h, w, 1) else maskNhwc
                arrayOf<Any>(i0, i1)
            } else {
                // 单输入变体：图像 + 掩码沿通道拼接为 4 通道
                arrayOf<Any>(concat4(imgNhwc, maskNhwc, h, w))
            }

            val outShape = interp.getOutputTensor(0).shape()
            val outNchw = isNchw(outShape)
            val outTotal = outShape.fold(1) { a, v -> a * v }
            val outArr = FloatArray(outTotal)
            val outputs = HashMap<Int, Any>().apply { put(0, outArr) }
            interp.runForMultipleInputsOutputs(inputs, outputs)

            val outNhwc = if (outNchw) nchwToNhwc(outArr, h, w, 3) else outArr
            val small = nhwcToBitmap(outNhwc, h, w)
            val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            sImg.recycle()
            sMask.recycle()
            small.recycle()
            result
        } catch (e: Exception) {
            null
        }
    }

    // ---- 形状解析 ----
    private fun isNchw(shape: IntArray): Boolean = shape.size == 4 && (shape[1] == 1 || shape[1] == 3)
    private fun hwOf(shape: IntArray): Pair<Int, Int> =
        if (isNchw(shape)) shape[2] to shape[3] else shape[1] to shape[2]

    // ---- 预处理 ----
    private fun fillNhwc(bmp: Bitmap, ch: Int, isMask: Boolean, h: Int, w: Int): FloatArray {
        val px = IntArray(h * w)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(h * w * ch)
        var o = 0
        for (i in 0 until h * w) {
            val p = px[i]
            if (isMask) {
                val a = (p ushr 24) and 0xFF
                out[o++] = if (a >= MASK_THRESHOLD) 1f else 0f
            } else {
                out[o++] = ((p ushr 16) and 0xFF).toFloat() / 255f
                out[o++] = ((p ushr 8) and 0xFF).toFloat() / 255f
                out[o++] = (p and 0xFF).toFloat() / 255f
            }
        }
        return out
    }

    private fun nhwcToNchw(nhwc: FloatArray, h: Int, w: Int, ch: Int): FloatArray {
        val out = FloatArray(nhwc.size)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until ch) {
            out[c * h * w + y * w + x] = nhwc[(y * w + x) * ch + c]
        }
        return out
    }

    private fun nchwToNhwc(nchw: FloatArray, h: Int, w: Int, ch: Int): FloatArray {
        val out = FloatArray(nchw.size)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until ch) {
            out[(y * w + x) * ch + c] = nchw[c * h * w + y * w + x]
        }
        return out
    }

    private fun concat4(img: FloatArray, mask: FloatArray, h: Int, w: Int): FloatArray {
        val out = FloatArray(h * w * 4)
        var o = 0
        for (i in 0 until h * w) {
            out[o++] = img[i * 3]
            out[o++] = img[i * 3 + 1]
            out[o++] = img[i * 3 + 2]
            out[o++] = mask[i]
        }
        return out
    }

    // ---- 后处理 ----
    private fun nhwcToBitmap(arr: FloatArray, h: Int, w: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(h * w)
        for (i in 0 until h * w) {
            val r = (arr[i * 3] * 255f).coerceIn(0f, 255f).toInt()
            val g = (arr[i * 3 + 1] * 255f).coerceIn(0f, 255f).toInt()
            val b = (arr[i * 3 + 2] * 255f).coerceIn(0f, 255f).toInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 释放解释器占用的 native 资源（进程退出前可调用）。 */
    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
        tried = false
        available = false
    }
}
