package com.example.psapk

import android.graphics.Bitmap

/**
 * 虚拟试穿引擎接口（可插拔）。
 *
 * 当前 App 的试穿由 [TryOnView] 以“可变形网格手动贴合”实现（离线、无模型）。
 * 若要升级为“自动、高质量”的虚拟试穿（如 VITON / OOTDiffusion 等深度学习模型，
 * 或云端生图 API），只需实现本接口并替换 [TryOnActivity] 中的合成逻辑，UI 无需改动。
 */
interface TryOnEngine {
    fun tryOn(person: Bitmap, garment: Bitmap, type: String): Bitmap
}

/**
 * 默认占位实现：仅把服饰居中放置（不带网格形变），用于在没有自定义引擎时保证接口可用。
 * 真正的贴合仍由 [TryOnView] 的网格形变交互完成。
 */
class NaiveTryOnEngine : TryOnEngine {
    override fun tryOn(person: Bitmap, garment: Bitmap, type: String): Bitmap {
        val out = Bitmap.createBitmap(person.width, person.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawBitmap(person, 0f, 0f, null)
        val w = (person.width * 0.5f).toInt().coerceAtLeast(1)
        val h = (w.toFloat() * garment.height / garment.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(garment, w, h, true)
        c.drawBitmap(scaled, (person.width - w) / 2f, (person.height * 0.3f), null)
        return out
    }
}
