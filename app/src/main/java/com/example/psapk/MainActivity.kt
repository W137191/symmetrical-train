package com.example.psapk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.psapk.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var original: Bitmap? = null
    private var cameraUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadImage(it) }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            ImageUtils.finalizeCameraUri(this, uri)
            loadImage(uri)
        }
    }
    private val requestCameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("需要相机权限才能拍照")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnCostume.setOnClickListener { startActivity(Intent(this, CostumeActivity::class.java)) }
        binding.btnTryOn.setOnClickListener { startActivity(Intent(this, TryOnActivity::class.java)) }
        binding.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPerm.launch(Manifest.permission.CAMERA)
            }
        }
        binding.btnAuto.setOnClickListener { runAutoDetect() }
        binding.btnProcess.setOnClickListener { runProcess() }
        binding.btnSave.setOnClickListener { runSave() }
        binding.btnReset.setOnClickListener { reset() }

        binding.btnBrushPlus.setOnClickListener {
            binding.editView.brushSize += 20f
            toast("笔刷 ${binding.editView.brushSize.toInt()}")
        }
        binding.btnBrushMinus.setOnClickListener {
            binding.editView.brushSize = maxOf(10f, binding.editView.brushSize - 20f)
            toast("笔刷 ${binding.editView.brushSize.toInt()}")
        }
        binding.btnErase.setOnClickListener {
            binding.editView.mode = if (binding.editView.mode == EditView.MODE_DRAW) EditView.MODE_ERASE else EditView.MODE_DRAW
            toast(if (binding.editView.mode == EditView.MODE_ERASE) "已切换：橡皮（擦除选区）" else "已切换：涂抹（选择遮挡）")
        }
        binding.btnMask.setOnClickListener {
            binding.editView.showMask = !binding.editView.showMask
            binding.editView.invalidate()
            toast(if (binding.editView.showMask) "蒙版：显示" else "蒙版：隐藏")
        }
        binding.btnCompare.setOnClickListener {
            binding.editView.compareMode = !binding.editView.compareMode
            binding.editView.invalidate()
            toast(if (binding.editView.compareMode) "对比模式：拖动查看原图/修复" else "对比模式：关闭")
        }
        binding.btnUndo.setOnClickListener {
            if (binding.editView.undo()) toast("已撤销上一步") else toast("没有可撤销的操作")
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = ImageUtils.decodeSampled(this, uri)
            original?.recycle()
            original = bmp
            binding.editView.setBitmap(bmp)
            toast("图片已载入，请涂抹或自动检测遮挡区域")
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    private fun launchCamera() {
        cameraUri = ImageUtils.createCameraUri(this)
        cameraUri?.let { takePicture.launch(it) } ?: toast("无法创建相机输出")
    }

    private fun runAutoDetect() {
        val bmp = binding.editView.getBitmap() ?: return toast("请先选择图片")
        lifecycleScope.launch {
            setBusy(true)
            try {
                val mask = withContext(Dispatchers.Default) { OverlayDetector.autoMask(bmp) }
                binding.editView.pushMaskUndo()
                binding.editView.setMaskFromBitmap(mask)
                toast("自动检测完成，可手动微调后一键修复")
            } catch (e: Exception) {
                toast("自动检测失败：${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runProcess() {
        val bmp = binding.editView.getBitmap() ?: return toast("请先选择图片")
        val mask = binding.editView.getMaskBitmap() ?: return toast("无掩码")
        lifecycleScope.launch {
            setBusy(true)
            try {
                val result = withContext(Dispatchers.Default) { Inpainter.inpaint(bmp, mask) }
                binding.editView.storeUndoFull()
                binding.editView.applyResult(result)
                toast("修复完成，可继续编辑或保存")
            } catch (e: Exception) {
                toast("修复失败：${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runSave() {
        val bmp = binding.editView.getBitmap() ?: return toast("请先选择图片")
        lifecycleScope.launch {
            try {
                val name = "PSFix_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                val uri = withContext(Dispatchers.IO) { ImageUtils.saveToGallery(this@MainActivity, bmp, name) }
                toast("已保存到相册：$uri")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }
    }

    private fun reset() {
        original?.let { binding.editView.setBitmap(Bitmap.createBitmap(it)) }
            ?: toast("尚未载入图片")
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        val enable = !busy
        binding.btnGallery.isEnabled = enable
        binding.btnCamera.isEnabled = enable
        binding.btnAuto.isEnabled = enable
        binding.btnProcess.isEnabled = enable
        binding.btnSave.isEnabled = enable
        binding.btnReset.isEnabled = enable
        binding.btnBrushPlus.isEnabled = enable
        binding.btnBrushMinus.isEnabled = enable
        binding.btnErase.isEnabled = enable
        binding.btnMask.isEnabled = enable
        binding.btnCompare.isEnabled = enable
        binding.btnUndo.isEnabled = enable
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
