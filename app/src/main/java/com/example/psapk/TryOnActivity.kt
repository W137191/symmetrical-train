package com.example.psapk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.psapk.databinding.ActivityTryonBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TryOnActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTryonBinding
    private var cameraUri: Uri? = null
    private var garmentBmp: Bitmap? = null
    private var type = TryOnView.TYPE_TOP

    private val pickPerson = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadPerson(it) }
    }
    private val pickGarment = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadGarment(it) }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            ImageUtils.finalizeCameraUri(this, uri)
            loadPerson(uri)
        }
    }
    private val requestCameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTryonBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPerson.setOnClickListener { pickOrCapturePerson() }
        binding.btnGarment.setOnClickListener { pickGarment.launch("image/*") }
        binding.btnConfirmCutout.setOnClickListener { confirmCutout() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener { binding.tryOnView.resetPlacement() }

        binding.btnTypeTop.setOnClickListener { setType(TryOnView.TYPE_TOP) }
        binding.btnTypePants.setOnClickListener { setType(TryOnView.TYPE_PANTS) }
        binding.btnTypeSkirt.setOnClickListener { setType(TryOnView.TYPE_SKIRT) }
        binding.btnTypeOther.setOnClickListener { setType(TryOnView.TYPE_OTHER) }

        binding.seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { binding.tryOnView.setGlobalScale(p / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.seekRotate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { binding.tryOnView.setGlobalRotDeg(p - 180f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        showTip("① 先点「选人物」载入人物照片")
    }

    private fun pickOrCapturePerson() {
        // 人物优先相册，长按需拍照：这里提供相册；拍照请在权限允许后使用相机
        pickPerson.launch("image/*")
        // 若想用相机，可改为 launchCamera()
    }

    private fun launchCamera() {
        cameraUri = ImageUtils.createCameraUri(this)
        cameraUri?.let { takePicture.launch(it) } ?: toast("无法创建相机输出")
    }

    private fun loadPerson(uri: Uri) {
        try {
            val bmp = ImageUtils.decodeSampled(this, uri)
            binding.tryOnView.setPerson(bmp)
            showTryOnView()
            showTip("② 点「选服饰」并涂抹出服饰区域")
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    private fun loadGarment(uri: Uri) {
        try {
            val bmp = ImageUtils.decodeSampled(this, uri, 1000)
            garmentBmp = bmp
            binding.cutoutView.setBitmap(bmp)
            showCutoutView()
            showTip("在服饰照片上涂抹出要试穿的部分（透明底 PNG 可整图涂抹），然后点「确认裁切」")
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    private fun confirmCutout() {
        val g = garmentBmp ?: return toast("请先选择服饰照片")
        if (!binding.tryOnView.hasPerson()) return toast("请先选择人物照片")
        val cutout = buildCutout(g, binding.cutoutView.getMaskBitmap())
        binding.tryOnView.setGarment(cutout, type)
        binding.seekScale.progress = 100
        binding.seekRotate.progress = 180
        showTryOnView()
        showTip("拖动网格控制点让服饰贴合身体；双指缩放/旋转；满意后「保存」")
    }

    /** 由“服饰图 + 用户涂抹掩码”生成带透明通道的裁切图。 */
    private fun buildCutout(g: Bitmap, mask: Bitmap?): Bitmap {
        val w = g.width; val h = g.height
        val n = w * h
        val gp = IntArray(n); g.getPixels(gp, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val op = IntArray(n)
        var painted = 0
        if (mask != null) {
            val mp = IntArray(n); mask.getPixels(mp, 0, w, 0, 0, w, h)
            for (i in 0 until n) {
                val ma = (mp[i] ushr 24) and 0xFF
                if (ma > 0) {
                    painted++
                    val a = (gp[i] ushr 24) and 0xFF
                    // 保留原图颜色（含其自身 alpha），仅把掩码外区域设为透明
                    op[i] = (a shl 24) or (gp[i] and 0x00FFFFFF)
                } else {
                    op[i] = gp[i] and 0x00FFFFFF // alpha 0
                }
            }
        }
        if (painted == 0) {
            // 未涂抹：直接使用原图（适用于已透明底的 PNG）
            for (i in 0 until n) op[i] = gp[i]
        }
        out.setPixels(op, 0, w, 0, 0, w, h)
        return out
    }

    private fun setType(t: String) {
        type = t
        if (binding.tryOnView.hasGarment()) binding.tryOnView.setType(t)
    }

    private fun save() {
        if (!binding.tryOnView.hasPerson() || !binding.tryOnView.hasGarment()) return toast("请先完成人物与服饰的试穿")
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { binding.tryOnView.composeToBitmap() }
                val name = "TryOn_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                val uri = withContext(Dispatchers.IO) { ImageUtils.saveToGallery(this@TryOnActivity, bmp, name) }
                toast("已保存到相册：$uri")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }
    }

    private fun showTryOnView() {
        binding.tryOnView.visibility = android.view.View.VISIBLE
        binding.cutoutView.visibility = android.view.View.GONE
    }
    private fun showCutoutView() {
        binding.cutoutView.visibility = android.view.View.VISIBLE
        binding.tryOnView.visibility = android.view.View.GONE
    }
    private fun showTip(s: String) { binding.tip.text = s }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
