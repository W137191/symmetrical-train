# CHANGELOG

## v1.3.1 (2026-11-04) — 构建修复
- 修复会导致 GitHub Actions / 本地编译失败的隐患：补全省略的 `kotlin.math` 显式导入。
- `CostumeView.kt`、`EditView.kt`、`ImageUtils.kt`、`MainActivity.kt`、`TryOnView.kt` 中 `minOf` / `maxOf` / `atan2` 此前未导入（Kotlin 不会自动导入 `kotlin.math` 顶层函数），现全部补全，确保 `assembleRelease` 通过。

## v1.3.0 (2026-11-04)
- 新增「虚拟试穿」功能：用户分别提供服饰照片 + 人物照片，将指定服饰贴合到人物身上。
- 新增 `TryOnActivity` / `TryOnView`：两图输入、手动涂抹抠图（透明底 PNG 直用）、按类型自动初始化贴合区域。
- `TryOnView` 采用**可变形网格 warp**（逐三角仿射映射）合成，支持拖动控制点贴合体态/姿态、双指缩放旋转、滑块微调，保留服饰版型与纹理、减少畸变。
- 新增 `TryOnEngine.kt`：可插拔试穿引擎接口，便于后续接入 VITON / OOTDiffusion 等模型或云端 API 升级为自动高质量试穿。

## v1.2.0 (2026-11-04)
- 新增「换装相机」功能（类似哆啦A梦换装相机）：人物照片 + 文字/道具描述 → 自动贴合换装 → 微调 → 保存。
- 新增 `CostumeActivity` / `CostumeView`：人物输入、服装描述输入、合成处理、输出换装图。
- 新增 `Costume.kt`（7 种矢量服装道具 + 关键词库）、`CostumeGenerator.kt`（可插拔生成器接口）、`FaceUtils.kt`（Android FaceDetector 人脸定位自动贴合）。
- 支持单指拖动、双指缩放+旋转、滑块缩放/旋转微调；自然协调地叠加到人物轮廓。
- 预留 `CostumeGenerator` 接口，便于后续接入云端生图 API / 端侧扩散模型实现“真正生成任意服装”。

## v1.1.0 (2026-11-03)
- 新增「修复前后对比滑块」：对比模式下拖动分割线，左原图 / 右修复结果。
- 新增「撤销」：逐步撤销修复、笔刷/橡皮涂抹与自动检测选区（最多 8 步，回收旧 Bitmap 防内存泄漏）。
- 修复对比原图与实时图像共享同一 Bitmap 对象导致的回收冲突（compareOriginal 改为独立拷贝）。

## v1.0.0 (2026-11-03)
- 初始版本：Android AI 修图 APK（PS 痕迹去除）。
- 选图（相册 / 拍照）、手动笔刷涂抹与橡皮擦除、蒙版显隐。
- 自动遮挡检测（Sobel 边缘 + 连通域形状/饱和度启发式）。
- 一键修复：Telea 快速行进法内容感知修复（纯 Kotlin，无第三方依赖）。
- 结果保存至相册 `Pictures/PsApk`（MediaStore，无需存储权限）。
