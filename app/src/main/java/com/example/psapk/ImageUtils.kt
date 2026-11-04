package com.example.psapk

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.maxOf

/**
 * 图片工具：从 Uri 解码并降采样、保存到相册、创建相机输出 Uri。
 */
object ImageUtils {

    /**
     * 从 Uri 解码图片，并限制最长边不超过 maxDim，兼顾内存与处理速度。
     */
    fun decodeSampled(context: Context, uri: Uri, maxDim: Int = 1400): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val ow = boundsOpts.outWidth
        val oh = boundsOpts.outHeight
        if (ow <= 0 || oh <= 0) throw IllegalArgumentException("无法读取图片尺寸")

        var sample = 1
        while (ow / sample > maxDim || oh / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalArgumentException("无法解码图片: $uri")

        return if (maxOf(bmp.width, bmp.height) > maxDim) {
            val s = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true
            )
            bmp.recycle()
            scaled
        } else {
            bmp
        }
    }

    /**
     * 保存 Bitmap 到相册 Pictures/PsApk 目录（Android 10+ 用 MediaStore，无需存储权限）。
     */
    fun saveToGallery(context: Context, bmp: Bitmap, name: String): Uri {
        val fname = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fname)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PsApk")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val psDir = File(dir, "PsApk")
                psDir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(psDir, fname).absolutePath)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建媒体条目")

        context.contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: throw IllegalStateException("无法写入图片")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    /**
     * 为相机创建一个输出 Uri（写入 MediaStore，待拍摄完成后 finalize）。
     */
    fun createCameraUri(context: Context): Uri? {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "CAM_${fmt.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PsApk")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    /**
     * 拍摄成功后清除 IS_PENDING，使图片对其他应用可见。
     */
    fun finalizeCameraUri(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val v = ContentValues()
            v.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, v, null, null)
        }
    }
}
