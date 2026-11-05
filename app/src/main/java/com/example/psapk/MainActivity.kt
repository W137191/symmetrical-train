package com.example.psapk

import kotlin.math.max
import kotlin.math.min

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
import kotlinx.coroutines.withContext
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
            val old = binding.editView.brushSize
            binding.editView.brushSize = min(200f, old + 20f)
            updateStatusLine()
            if (binding.editView.brushSize == old) toast(getString(R.string.toast_brush_max))
        }
        binding.btnBrushMinus.setOnClickListener {
            val old = binding.editView.brushSize
            binding.editView.brushSize = max(10f, old - 20f)
            updateStatusLine()
            if (binding.editView.brushSize == old) toast(getString(R.string.toast_brush_min))
        }
        binding.btnErase.setOnClickListener {
            binding.editView.mode = if (binding.editView.mode == EditView.MODE_DRAW) EditView.MODE_ERASE else EditView.MODE_DRAW
            binding.btnErase.isSelected = binding.editView.mode == EditView.MODE_ERASE
            updateStatusLine()
        }
        binding.btnMask.setOnClickListener {
            binding.editView.showMask = !binding.editView.showMask
            binding.editView.invalidate()
            binding.btnMask.isSelected = binding.editView.showMask
            updateStatusLine()
        }
        binding.btnCompare.setOnClickListener {
            binding.editView.compareMode = !binding.editView.compareMode
            binding.editView.invalidate()
            binding.btnCompare.isSelected = binding.editView.compareMode
            updateStatusLine()
        }
        binding.btnUndo.setOnClickListener {
            if (binding.editView.undo()) toast("已撤销上一步") else toast("没有可撤销的操作")
        }

        // 初始状态：切换按钮常驻选中态、状态行、空状态引导、当前修复引擎
        binding.btnMask.isSelected = binding.editView.showMask
        binding.btnErase.isSelected = binding.editView.mode == EditView.MODE_ERASE
        binding.btnCompare.isSelected = binding.editView.compareMode
        updateStatusLine()
        updateEmptyState()
        updateEngineBadge()
    }

    /** 常驻状态行：把原先只能靠 Toast 获知的信息直接显示出来。 */
    private fun updateStatusLine() {
        val ev = binding.editView
        val mode = getString(if (ev.mode == EditView.MODE_ERASE) R.string.mode_erase else R.string.mode_draw)
        val mask = getString(if (ev.showMask) R.string.mask_on else R.string.mask_off)
        val cmp = getString(if (ev.compareMode) R.string.compare_on else R.string.compare_off)
        binding.tvStatusLine.text = getString(R.string.status_line, ev.brushSize.toInt(), mode, mask, cmp)
    }

    private fun updateEmptyState() {
        binding.emptyState.visibility =
            if (binding.editView.getBitmap() == null) View.VISIBLE else View.GONE
    }

    /** 标题栏右侧显示实际生效的修复引擎：ML 权重存在时为 ML，否则经典 FMM。 */
    private fun updateEngineBadge() {
        binding.tvEngine.text = getString(
            if (MLInpainter.isAvailable(this)) R.string.engine_ml else R.string.engine_fmm
        )
    }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = ImageUtils.decodeSampled(this, uri)
            original?.recycle()
            original = bmp
            binding.editView.setBitmap(bmp)
            updateEmptyState()
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
                val result = withContext(Dispatchers.Default) {
                    // 优先端侧 ML（LaMa-Dilated）；权重缺失/失败则回退经典 Telea FMM
                    MLInpainter.inpaint(this@MainActivity, bmp, mask) ?: Inpainter.inpaint(bmp, mask)
                }
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
            binding.btnSave.isEnabled = false
            try {
                val name = "PSFix_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                withContext(Dispatchers.IO) { ImageUtils.saveToGallery(this@MainActivity, bmp, name) }
                toast(getString(R.string.saved_toast, name))
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            } finally {
                binding.btnSave.isEnabled = true
            }
        }
    }

    private fun reset() {
        original?.let { binding.editView.setBitmap(Bitmap.createBitmap(it)) }
            ?: toast("尚未载入图片")
        updateEmptyState()
    }

    private fun setBusy(busy: Boolean) {
        // 常驻忙碌遮罩（含「正在处理」文案）并屏蔽误触，覆盖此前漏掉的两个功能入口按钮
        binding.busyOverlay.visibility = if (busy) View.VISIBLE else View.GONE
        val enable = !busy
        listOf(
            binding.btnGallery, binding.btnCamera, binding.btnAuto, binding.btnProcess,
            binding.btnSave, binding.btnReset, binding.btnBrushPlus, binding.btnBrushMinus,
            binding.btnErase, binding.btnMask, binding.btnCompare, binding.btnUndo,
            binding.btnCostume, binding.btnTryOn
        ).forEach { it.isEnabled = enable }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
