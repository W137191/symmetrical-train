package com.example.psapk

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一个“服装道具”的定义：包含识别关键词、在人脸上的锚点位置、基础尺寸，以及如何在画布上绘制自己。
 * 道具用纯 Canvas 图元绘制（无需图片资源），可随人物自动缩放、旋转、平移并自然叠加。
 */
data class Costume(
    val id: String,
    val name: String,
    val keywords: List<String>,
    /** 根据检测到的脸部信息，返回该道具在图像（Bitmap 像素坐标）中的中心位置。 */
    val anchor: (faceCx: Float, faceCy: Float, faceW: Float, faceH: Float) -> Pair<Float, Float>,
    /** 根据脸部尺寸，返回道具的基础绘制尺寸（约等于道具宽度，单位 Bitmap 像素）。 */
    val baseSize: (faceW: Float, faceH: Float) -> Float,
    /** 以 (cx, cy) 为中心、size 为基准尺寸绘制道具。 */
    val draw: (canvas: Canvas, cx: Float, cy: Float, size: Float) -> Unit
)

object CostumeLibrary {

    private val red = Paint().apply { color = 0xFFE53935.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val redDark = Paint().apply { color = 0xFFC62828.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val yellow = Paint().apply { color = 0xFFFFEB3B.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val gold = Paint().apply { color = 0xFFFFD700.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val blue = Paint().apply { color = 0xFF1976D2.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val indigo = Paint().apply { color = 0xFF3949AB.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val indigoLt = Paint().apply { color = 0xFF5C6BC0.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val black = Paint().apply { color = 0x22000000.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val whiteTint = Paint().apply { color = 0x55FFFFFF.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val blackLine = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; isAntiAlias = true }
    private val darkTint = Paint().apply { color = 0x33000000.toInt(); style = Paint.Style.FILL; isAntiAlias = true }

    private fun drawGlasses(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val r = s * 0.17f
        blackLine.strokeWidth = s * 0.045f
        canvas.drawCircle(cx - s * 0.22f, cy, r, blackLine)
        canvas.drawCircle(cx + s * 0.22f, cy, r, blackLine)
        canvas.drawLine(cx - s * 0.05f, cy, cx + s * 0.05f, cy, blackLine)
        canvas.drawLine(cx - s * 0.39f, cy, cx - s * 0.47f, cy - s * 0.03f, blackLine)
        canvas.drawLine(cx + s * 0.39f, cy, cx + s * 0.47f, cy - s * 0.03f, blackLine)
        canvas.drawCircle(cx - s * 0.22f, cy, r * 0.92f, darkTint)
        canvas.drawCircle(cx + s * 0.22f, cy, r * 0.92f, darkTint)
    }

    private fun drawHat(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val path = Path()
        path.moveTo(cx, cy - s * 0.48f)
        path.lineTo(cx - s * 0.3f, cy + s * 0.3f)
        path.lineTo(cx + s * 0.3f, cy + s * 0.3f)
        path.close()
        canvas.drawPath(path, red)
        canvas.drawCircle(cx, cy - s * 0.48f, s * 0.08f, yellow)
        canvas.drawOval(cx - s * 0.36f, cy + s * 0.24f, cx + s * 0.36f, cy + s * 0.36f, redDark)
    }

    private fun drawCrown(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val baseY = cy + s * 0.25f
        val path = Path()
        path.moveTo(cx - s * 0.35f, baseY)
        path.lineTo(cx - s * 0.35f, cy - s * 0.05f)
        path.lineTo(cx - s * 0.18f, cy + s * 0.12f)
        path.lineTo(cx, cy - s * 0.28f)
        path.lineTo(cx + s * 0.18f, cy + s * 0.12f)
        path.lineTo(cx + s * 0.35f, cy - s * 0.05f)
        path.lineTo(cx + s * 0.35f, baseY)
        path.close()
        canvas.drawPath(path, gold)
        val gem = Paint().apply { color = 0xFFF44336.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawCircle(cx, cy + 0.02f * s, s * 0.05f, gem)
    }

    private fun drawCape(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val path = Path()
        path.moveTo(cx - s * 0.22f, cy - s * 0.28f)
        path.lineTo(cx + s * 0.22f, cy - s * 0.28f)
        path.lineTo(cx + s * 0.42f, cy + s * 0.5f)
        path.lineTo(cx - s * 0.42f, cy + s * 0.5f)
        path.close()
        canvas.drawPath(path, red)
        canvas.drawRect(cx - s * 0.26f, cy - s * 0.32f, cx + s * 0.26f, cy - s * 0.2f, blue)
        val star = Paint().apply { color = 0xFFFFEB3B.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
        drawStar(canvas, cx, cy + s * 0.02f, s * 0.13f, star)
    }

    private fun drawMask(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val w = s * 0.72f
        val h = s * 0.3f
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h * 0.45f, h * 0.45f, black)
        canvas.drawOval(cx - w * 0.3f, cy - h * 0.22f, cx - w * 0.02f, cy + h * 0.22f, whiteTint)
        canvas.drawOval(cx + w * 0.02f, cy - h * 0.22f, cx + w * 0.3f, cy + h * 0.22f, whiteTint)
    }

    private fun drawBowtie(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val hl = s * 0.24f
        val hw = s * 0.16f
        val l = Path(); l.moveTo(cx, cy); l.lineTo(cx - hw, cy - hl); l.lineTo(cx - hw, cy + hl); l.close()
        val r = Path(); r.moveTo(cx, cy); r.lineTo(cx + hw, cy - hl); r.lineTo(cx + hw, cy + hl); r.close()
        canvas.drawPath(l, indigoLt)
        canvas.drawPath(r, indigoLt)
        canvas.drawCircle(cx, cy, s * 0.07f, indigo)
    }

    private fun drawDoraemon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.drawRoundRect(cx - s * 0.42f, cy - s * 0.08f, cx + s * 0.42f, cy + s * 0.14f, s * 0.08f, s * 0.08f, red)
        val bell = Paint().apply { color = 0xFFFBC02D.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawCircle(cx, cy + s * 0.03f, s * 0.13f, bell)
        val line = Paint().apply { color = 0xFFF9A825.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.02f; isAntiAlias = true }
        canvas.drawLine(cx - s * 0.1f, cy - s * 0.04f, cx + s * 0.1f, cy - s * 0.04f, line)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val path = Path()
        val pts = 5
        for (i in 0 until pts * 2) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val ang = Math.PI / 2 + i * Math.PI / pts
            val x = cx + rad * cos(ang).toFloat()
            val y = cy - rad * sin(ang).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    val all: List<Costume> = listOf(
        Costume(
            id = "glasses", name = "眼镜",
            keywords = listOf("眼镜", "墨镜", "镜", "geek", "glasses", "sunglasses", "学霸", "书呆"),
            anchor = { cx, cy, _, fh -> cx to cy - fh * 0.05f },
            baseSize = { fw, _ -> fw * 1.0f },
            draw = ::drawGlasses
        ),
        Costume(
            id = "hat", name = "派对帽",
            keywords = listOf("帽", "帽子", "派对", "生日", "hat", "cap", "party"),
            anchor = { cx, cy, _, fh -> cx to cy - fh * 0.55f },
            baseSize = { fw, _ -> fw * 1.05f },
            draw = ::drawHat
        ),
        Costume(
            id = "crown", name = "皇冠",
            keywords = listOf("皇冠", "公主", "王子", "女王", "国王", "王", "crown", "princess", "queen", "king", "贵族"),
            anchor = { cx, cy, _, fh -> cx to cy - fh * 0.5f },
            baseSize = { fw, _ -> fw * 0.9f },
            draw = ::drawCrown
        ),
        Costume(
            id = "cape", name = "超级英雄披风",
            keywords = listOf("披风", "斗篷", "超级英雄", "超人", "蜘蛛侠", "英雄", "cape", "superhero", "hero", "cosplay", "角色", "装扮"),
            anchor = { cx, cy, _, fh -> cx to cy + fh * 1.1f },
            baseSize = { fw, _ -> fw * 3.2f },
            draw = ::drawCape
        ),
        Costume(
            id = "mask", name = "面具",
            keywords = listOf("面具", "蒙面", "侠", "mask", "domino"),
            anchor = { cx, cy, _, fh -> cx to cy - fh * 0.05f },
            baseSize = { fw, _ -> fw * 1.0f },
            draw = ::drawMask
        ),
        Costume(
            id = "bowtie", name = "领结",
            keywords = listOf("领结", "蝴蝶结", "绅士", "bowtie", "tie", "bow"),
            anchor = { cx, cy, _, fh -> cx to cy + fh * 0.72f },
            baseSize = { fw, _ -> fw * 0.6f },
            draw = ::drawBowtie
        ),
        Costume(
            id = "doraemon", name = "哆啦A梦项铃",
            keywords = listOf("哆啦", "蓝猫", "猫", "叮当", "doraemon", "bell"),
            anchor = { cx, cy, _, fh -> cx to cy + fh * 0.75f },
            baseSize = { fw, _ -> fw * 1.15f },
            draw = ::drawDoraemon
        )
    )

    fun match(description: String): Costume {
        val d = description.lowercase()
        for (c in all) {
            if (c.keywords.any { d.contains(it.lowercase()) }) return c
        }
        return all.first { it.id == "cape" } // 默认：超级英雄披风
    }
}
