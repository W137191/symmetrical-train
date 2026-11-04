# 端侧 ML 修复模型权重（lama_dilated.tflite）

本目录用于放置 **LaMa-Dilated** 的 TensorFlow Lite 权重文件，启用「端侧 ML 修复」。

## 需要的文件
- 文件名必须为本目录下的 `lama_dilated.tflite`（MLInpainter.MODEL 硬编码）。

## 下载来源（Apache-2.0，可商用 / 可再分发）
- Qualcomm AI Hub / HuggingFace：`https://huggingface.co/qualcomm/LaMa-Dilated/resolve/main/LaMa-Dilated.tflite`
  （约 174 MB，FP32，输入 512×512）
- 或 LiteRT-Models 镜像（`lama_dilated.tflite`，同规格）。

一键获取（任选其一）：
```bash
# HuggingFace
curl -L -o app/src/main/assets/lama_dilated.tflite \
  https://huggingface.co/qualcomm/LaMa-Dilated/resolve/main/LaMa-Dilated.tflite

# 或国内镜像（若 HuggingFace 不通）
curl -L -o app/src/main/assets/lama_dilated.tflite \
  https://hf-mirror.com/qualcomm/LaMa-Dilated/resolve/main/LaMa-Dilated.tflite
```

## 模型 I/O 约定（MLInpainter 已按此实现，并兼容 NCHW / 单输入 4 通道变体）
- 输入 0：图像 `[1, H, W, 3]` NHWC，RGB 归一化到 0..1（÷255）
- 输入 1：掩码 `[1, H, W, 1]` NHWC，单通道 0..1（1 = 待修复区域）
- 输出 0：修复结果 `[1, H, W, 3]` NHWC，0..1
- 输入尺寸由模型张量形状动态读取（通常 512×512），源图自动缩放到该尺寸再推理，结果放大回原尺寸。

## 启用行为
- 存在 `lama_dilated.tflite`：一键修复自动走 ML（质量更高），耗时主要在 512×512 推理。
- 缺失 / 推理异常：自动回退经典 Telea FMM（Inpainter），功能不受影响。
- 注意：该文件约 174 MB，会显著增大 APK。**本仓库默认不提交该二进制**；
  本地放好即可在本地构建中生效；要发布到 CI/AIPS 的 APK 含 ML，请自行 `git add` 并提交（或改为 LFS / 不提交以保持仓库精简）。
