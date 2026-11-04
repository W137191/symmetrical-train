package com.example.psapk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.psapk.databinding.ActivityCostumeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CostumeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCostumeBinding
    private val generator: CostumeGenerator = KeywordCostumeGenerator()
    private var cameraUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadPerson(it) }
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
        binding = ActivityCostumeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPerm.launch(Manifest.permission.CAMERA)
            }
        }
        binding.btnSave.setOnClickListener { save() }
        binding.btnResetPos.setOnClickListener {
            binding.costumeView.resetPlacement()
        }
        binding.btnApplyDesc.setOnClickListener {
            val desc = binding.editDesc.text.toString().trim()
            if (desc.isEmpty()) {
                toast("请输入服装/角色描述")
                return@setOnClickListener
            }
            val c = generator.generate(desc)
            selectCostume(c)
            toast("已匹配：${c.name}")
        }

        binding.seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                binding.costumeView.setRelScale(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.seekRotate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                binding.costumeView.setRotationDeg(p - 180f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        buildCostumeChips()
        // 默认进入时给一个示例提示
        toast("请先选择人物照片，再选服装或输入描述")
    }

    private fun buildCostumeChips() {
        val list = binding.costumeList
        list.removeAllViews()
        for (c in CostumeLibrary.all) {
            val btn = Button(this).apply {
                text = c.name
                setOnClickListener { selectCostume(c) }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
            }
            list.addView(btn)
        }
    }

    private fun selectCostume(c: Costume) {
        if (!binding.costumeView.hasPerson()) {
            toast("请先选择人物照片")
            return
        }
        binding.costumeView.setCostume(c)
        binding.seekScale.progress = 100
        binding.seekRotate.progress = 180
    }

    private fun launchCamera() {
        cameraUri = ImageUtils.createCameraUri(this)
        cameraUri?.let { takePicture.launch(it) } ?: toast("无法创建相机输出")
    }

    private fun loadPerson(uri: Uri) {
        try {
            val bmp = ImageUtils.decodeSampled(this, uri)
            binding.costumeView.setPerson(bmp)
            toast("人物已载入，选择服装或输入描述")
        } catch (e: Exception) {
            toast("加载失败：${e.message}")
        }
    }

    private fun save() {
        if (!binding.costumeView.hasPerson()) return toast("请先选择人物照片")
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { binding.costumeView.composeToBitmap() }
                val name = "Costume_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                val uri = withContext(Dispatchers.IO) { ImageUtils.saveToGallery(this@CostumeActivity, bmp, name) }
                toast("已保存到相册：$uri")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
