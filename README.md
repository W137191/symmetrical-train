# AI 修图 · PS 痕迹去除（Android）

从单张照片中自动/手动检测后期叠加的遮挡物（表情包、贴纸、文字等），
对被覆盖区域进行智能修复与内容还原，使图像恢复为自然无修改的状态。

## 功能
- **选图**：从系统相册选择，或直接拍照（Android 10+ 通过 MediaStore，无需存储权限）。
- **自动检测遮挡区域**：启发式识别疑似叠加物（边缘密度 + 连通域形状/饱和度筛选）。
- **手动笔刷**：手指涂抹选择遮挡区域；支持笔刷大小调节、橡皮擦除、蒙版显隐。
- **一键修复**：默认基于快速行进法（Telea / Fast Marching Method）的内容感知修复；
  放入端侧 ML 模型权重后自动切换为 **LaMa-Dilated（LiteRT / TFLite）** 高质量修复，缺失模型则回退经典算法。
- **实时预览**：编辑视图中图像与掩码共用同一变换，所见即所得。
- **修复前后对比滑块**：「对比」模式下拖动分割线，左侧显示原图、右侧显示修复结果，直观查看差异。
- **撤销**：支持逐步撤销修复、笔刷/橡皮涂抹与自动检测选区（最多 8 步，内存安全回收）。
- **保存**：结果以 PNG 保存到相册 `Pictures/PsApk`。

## 技术要点
- 以纯 Kotlin 为主实现，核心修复/检测算法零依赖、开箱即可编译运行；
  仅在 `assets` 中放置 LaMa-Dilated 权重时通过 TensorFlow Lite 启用端侧 ML（缺失则自动回退，不影响构建与基本功能）。
- 修复算法 `Inpainter.kt`：FMM 距离变换得到像素到达时间 T，按 T 升序用
  `权重 = 等值线方向项 × 距离项 × 时间项` 对已知邻居加权平均，
  使修复区域在色彩、纹理、光照上与周边自然融合（对应 OpenCV 的 `INPAINT_TELEA`）。
- 自动检测 `OverlayDetector.kt`：Sobel 边缘 ∪ 局部对比度异常（7×7 盒式模糊差分，捕捉半透明/柔边叠加）
  → 8 邻接连通域 → 多特征置信度评分（内部颜色平整度 + 边缘密度 + 紧凑度 + 饱和度 + 面积门限），
  可调 `sensitivity`(0 严格 / 1 宽松，默认 0.5)；保留区域做 1px 膨胀覆盖抗锯齿残留。
- 端侧 ML 修复 `MLInpainter.kt`：接入 **LaMa-Dilated** 的 TensorFlow Lite 权重（`app/src/main/assets/lama_dilated.tflite`），
  在端侧完成高质量修复；`MainActivity` 一键修复时优先尝试 ML，失败/缺模型自动回退 `Inpainter`（Telea）。
  详见 `app/src/main/assets/README.md` 的权重获取与 I/O 约定（约 174 MB，会显著增大 APK，默认不入库）。

## 构建与运行
1. 用 **Android Studio (Hedgehog 或更新)** 打开本目录（`File → Open` 选择 `D:/1/PsApk`）。
2. 等待 Gradle 同步（会自动下载 AGP 8.1.4 / Kotlin 1.9.22 / Gradle 8.4）。
3. 连接 Android 设备或启动模拟器（建议 API 26+，相机功能建议真机）。
4. 点击 **Run**（▶）安装并运行。

> 命令行本地打包：仓库已内置 `gradlew` / `gradlew.bat`（含 `gradle-wrapper.jar`），首次运行会自动下载 Gradle 8.4，无需预先安装 Gradle。
> 前提：本机需安装 **JDK 17** 并配置 `JAVA_HOME`（AGP 8.1.4 要求 JDK 17）。
> 命令：`gradlew assembleRelease`（Windows 用 `gradlew.bat`）；或直接用 Android Studio / 下方 GitHub Actions 出包。

## 下载已构建的 APK（GitHub Actions 自动打包）
不想装 Android Studio？推送代码到仓库后，GitHub Actions 会自动编译 **debug APK**（无需签名密钥）：
1. 把 `psapk` 分支推送到远程：`git push -u origin psapk`。
2. 打开仓库的 **Actions** 页面，等待 `Build debug APK` 任务跑完（通常几分钟）。
3. 到 **Releases** 页，下载标签为 **`AIPS`** 的发布中的 `app-debug.apk`（不要选监测工具的 `apk-latest`）。

- 稳定下载地址（始终指向修图 APK 的最新构建，可直接分享/收藏）：
  `https://github.com/W137191/symmetrical-train/releases/download/AIPS/app-debug.apk`
- Release 标签固定为 **`AIPS`**（非预发布），每次构建滚动覆盖更新；**不抢占**监测工具 `apk-latest` 的 Latest 徽章。
- debug 包由 Android 自动用 debug 密钥签名，**无需任何密钥配置**，安装零门槛（与监测工具 `apk-latest` 的构建模式一致，但文件名/标签都已区分）。
- 国内打不开 GitHub 时，给下载地址加镜像前缀：`https://ghproxy.net/https://github.com/W137191/symmetrical-train/releases/download/AIPS/app-debug.apk`

## 使用建议
1. 点「相册」或「拍照」载入照片。
2. 点「自动检测」生成初始选区；如不理想，用「笔刷 / 橡皮」手动精修。
3. 点「一键修复」查看结果；可多次修复不同区域。
4. 点「对比」进入对比模式，拖动分割线查看原图与修复结果；再点「对比」退出。
5. 操作失误可点「撤销」（修复、涂抹、自动检测均可撤销，最多 8 步）。
6. 点「保存」导出到相册。

## 关于“自动检测”的说明（重要）
在不依赖云端大模型、纯端侧运行的约束下，从单张图**无监督地区分**
「后期叠加物」与「原始内容」本身是学术级难题，无法 100% 准确。
自动模式为**辅助选区**，对边界清晰、颜色鲜明的表情包/贴纸/文字效果较好；
复杂或低对比场景请以手动笔刷为准。后续可接入端侧分割模型（如 MobileSAM / 自训练
遮挡检测网络）进一步提升自动检测精度——接口已预留（替换 `OverlayDetector` 即可）。

## 换装相机（附加功能）
从主界面点「📸 换装相机」进入，类似哆啦A梦“换装相机”：上传/拍摄人物照片 → 输入期望穿戴的
服装或角色描述（或点下方道具标签）→ 系统自动把服装道具贴合到人物相应位置 → 手动微调 → 保存。

- **输入**：人物照片（相册/拍照）、文字描述或道具点选。
- **合成**：`FaceUtils` 用 Android 自带 `FaceDetector` 定位人脸，按道具锚点（头顶/面部/脖颈/上身）
  自动缩放并放置；支持单指拖动、双指缩放 + 旋转、滑块微调，确保贴合轮廓、自然协调。
- **输出**：`CostumeView.composeToBitmap()` 合成后保存到相册 `Pictures/PsApk`。
- **扩展**：内置 7 种矢量绘制的道具（眼镜/派对帽/皇冠/超级英雄披风/面具/领结/哆啦A梦项铃）。
  描述→道具走 `KeywordCostumeGenerator`（关键词匹配）。要升级成“按文字真正生成任意服装”，
  只需实现 `CostumeGenerator` 接口（例如接入云端生图 API 或端侧扩散模型），界面无需改动。

> 说明：纯离线、无生成式模型的前提下，无法凭任意文字“生成”真实衣物纹理；当前为
> “虚拟换装贴片”方案。若需真正生成，请在 `CostumeGenerator` 处接入模型/API（需网络权限）。

## 虚拟试穿（附加功能）
从主页点「👕 虚拟试穿」进入。与“换装相机（模板道具）”不同，这里是**用户提供两张照片**：
服饰照片 + 人物照片 → 系统把指定服饰贴合到人物身上 → 输出试穿效果图。

流程：
1. **选人物**：载入人物照片（人脸检测用于自动定位躯干/下肢）。
2. **选服饰并涂抹**：载入服饰照片，用手指涂抹出要试穿的部分（透明底 PNG 可整图涂抹）→ 「确认裁切」得到带透明通道的服饰裁切图。
3. **贴合微调**：服饰按类型（上衣/裤子/裙子）自动放置到对应身体区域；通过**可变形网格控制点**拖动让服饰随体态/姿态弯曲，双指缩放+旋转、滑块微调，保留版型与纹理、减少畸变。
4. **保存**到相册 `Pictures/PsApk`。

- 核心 `TryOnView.kt`：网格 warp 合成（逐三角仿射映射，纹理不丢失）+ 手势（拖点/平移/双指缩放旋转）。
- `TryOnEngine.kt`：可插拔引擎接口；当前为手动网格方案，**接真正的试穿模型/云 API 即可升级为自动高质量试穿**（UI 不变）。

> 说明：纯离线无模型时，自动完美贴合（人体解析+姿态估计）无法实现；本方案用“几何网格形变 + 手动微调”
> 给出可控、清晰、低畸变的近似。若要工业级效果，请在 `TryOnEngine` 处接入 VITON / OOTDiffusion 等模型。

## 目录结构
```
PsApk/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/psapk/
│       │   ├── MainActivity.kt      # 主页（修图 + 进入换装）
│       │   ├── EditView.kt          # 选图/笔刷/蒙版/对比/撤销视图
│       │   ├── Inpainter.kt         # Telea 快速行进法修复
│       │   ├── OverlayDetector.kt    # 自动遮挡检测（启发式）
│       │   ├── ImageUtils.kt        # 解码/保存/相机 Uri
│       │   ├── CostumeActivity.kt    # 换装相机界面
│       │   ├── CostumeView.kt       # 人物+道具合成与手势微调视图
│       │   ├── Costume.kt           # 服装道具库（矢量绘制 + 关键词）
│       │   ├── CostumeGenerator.kt  # 可插拔的生成器接口
│       │   ├── FaceUtils.kt         # 人脸检测（自动贴合）
│       │   ├── TryOnActivity.kt     # 虚拟试穿界面
│       │   ├── TryOnView.kt         # 网格 warp 合成 + 手势微调
│       │   └── TryOnEngine.kt       # 可插拔试穿引擎接口
│       ├── res/layout/activity_main.xml
│       ├── res/layout/activity_costume.xml
│       ├── res/layout/activity_tryon.xml
│       └── res/...
├── settings.gradle / build.gradle / gradle.properties
├── CHANGELOG.md
└── README.md
```
