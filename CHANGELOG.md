# CHANGELOG

## 打磨经典 FMM 修复（第一梯队）(2026-11-04)
- `Inpainter.kt` 在保持 Telea (2004) 快速行进法架构、纯 Kotlin、零依赖的前提下做低风险增强：
  - **大图降采样加速**：长边超过 `MAX_SIDE`(1280) 时先等比缩小修复、再放大回原尺寸，把手机原始照片（数千万像素）的修复耗时压到可接受范围；小图不受影响。
  - **边缘连续性**：修复邻域半径由 3×3 扩大到以 `FILL_RADIUS`(2, 即 5×5) 为半径的方块，从更大范围采集同向结构信息，宽笔刷 / 大块遮挡边界更顺滑、纹理延续更自然；距离项改用真实欧氏距离 `hypot(dx, dy)` 平滑衰减。
  - **健壮性兜底**：① 全图都是遮挡（无已知像素）直接返回原图副本，避免全黑；② 孤立洞像素窗口内无任何可用邻居时，向外环逐圈搜索就近像素填充，杜绝黑点；③ mask 判定阈值集中为常量 `ALPHA_THRESHOLD`(1)，滤除极小残留 alpha。
- 公开入口 `inpaint(src, mask)` 签名保持不变（调用方 `MainActivity` 无需改动）；新增内部 `inpaintCore` 与 `findNearestUsable`。

## 应用显示名改为 AIPS (2026-11-04)
- `res/values/strings.xml`：`app_name` 由 “AI 修图 · PS痕迹去除” 改为 **AIPS**，与 Release 标签统一（`android:label="@string/app_name"` 作用于应用名与各桌面图标/设置页显示）。活动标签（换装相机/虚拟试穿）保持不变。

## 修复 Kotlin 编译错误（续）(2026-11-04)
- 上一轮把 `maxOf/minOf` 换成根包 `max/min` 引发 **重载歧义**（`kotlin.max` 与 `kotlin.math.max` 同时存在）。改为显式 `import kotlin.math.max` / `import kotlin.math.min`（与 OverlayDetector 一致，确认可解析），消除 `Overload resolution ambiguity` 与 `receiver type mismatch`。
- `FaceUtils.detectFace`：`face.eyesDistance` 须按方法调用 `face.eyesDistance()`（编译器要求），否则报 “Function invocation 'eyesDistance()' expected”。
- `EditView.setMaskFromBitmap`：原误用 `maskBitmap?.let { c -> c.drawBitmap(...) }`——`maskBitmap` 是 `Bitmap` 而非 `Canvas`，导致 `drawBitmap` 无法解析；改为 `maskCanvas`。

## 修复 Kotlin 编译错误（让 AIPS 出包）(2026-11-04)
- 本构建 `assembleDebug` 在资源链接通过后卡在 `compileDebugKotlin`，共 16 处 `e:` 错误，根因分三类：
  - **缺导入**：`MainActivity`/`CostumeActivity`/`TryOnActivity` 用了 `withContext` 却只导入了 `Dispatchers`/`launch`；`EditView` 用了 `PorterDuffXfermode` 却只导入了 `PorterDuff`。已补 `import kotlinx.coroutines.withContext` 与 `import android.graphics.PorterDuffXfermode`。
  - **`FaceDetector.Face()` 不能直接构造**：`FaceUtils.detectFace` 原 `arrayOf(FaceDetector.Face())` 触发“inner class 需外部类接收者”错误，改为 `arrayOfNulls<FaceDetector.Face>(1)` 并 `faces[0]!!`。
  - **`maxOf`/`minOf` 在构建环境的 kotlin-stdlib 中不可解析**（同包 `cos/sin/hypot/atan2/max/min` 均可解析，唯独 `Of` 变体不行）。将所有 `maxOf(`→`max(`、`minOf(`→`min(`（改用根包 `kotlin.max`/`kotlin.min`，自动导入），并移除失效的 `kotlin.math.maxOf`/`minOf` 导入。
- 同时升级 CI 失败诊断：除 build.log 尾部外，额外抓取 `e:` 编译错误行与失败任务名，便于远程定位。

## 修复构建期资源错误（让 AIPS 出包）(2026-11-04)
- `res/drawable/ic_launcher_foreground.xml`：原文件误用了 `<circle android:cx/cy/r>` 元素，而 Android VectorDrawable 只支持 `<path>/<group>/<clip-path>`，导致 AAPT2 报 `attribute android:cx not found` 等。改为用 `<path>` 的圆弧（`a7,7 0 1,0 ...`）绘制同款蓝色圆点。
- `res/layout/activity_costume.xml`：EditText 误用 `android:hintTextColor`（框架无此属性），改为正确的 `android:textColorHint`，消除 `attribute android:hintTextColor not found`。
- 两项均为 CI 自动构建 `assembleDebug` 时的资源链接失败根因，修复后 AIPS 标签应能产出 `app-debug.apk`。

## CI 标签再改名 AIPS (2026-11-04)
- `release-apk.yml`：发布标签 `tag_name` 由 `psapk-latest` 改为 **`AIPS`**，发布标题同步为 “AIPS 最新构建”；保持 `make_latest: false`，不与监测工具 `apk-latest` 抢 Latest 徽章。
- 下载地址改为显式标签 URL：
  `https://github.com/W137191/symmetrical-train/releases/download/AIPS/app-debug.apk`
  （国内仍可用 `ghproxy.net/` 镜像前缀）。README 同步更新。

## CI 标签改名（避免下错 APK）(2026-11-04)
- `release-apk.yml`：`tag_name` 由 `latest` 改为 **`psapk-latest`**，与仓库内监测工具（标签 `apk-latest`）明确区分，避免用户把两者 `app-debug.apk` 搞混。
- `make_latest` 由 `true` 改为 **`false`**：修图 APK 不再抢占监测工具的 Latest 徽章，两个项目各自独立。
- 下载地址改用显式标签 URL：
  `https://github.com/W137191/symmetrical-train/releases/download/psapk-latest/app-debug.apk`
  （不再用 `/latest/download/...`，彻底消除「Latest 指向哪个项目」的歧义）。
- README 同步更新下载说明，并补充国内 ghproxy.net 镜像前缀。

## CI 发布调整（改 debug APK）(2026-11-04)
- `release-apk.yml`：构建目标由 `assembleRelease`（签名 Release）改为 **`assembleDebug`（debug APK）**，产物 `app-debug.apk`，由 Android 自动 debug 签名，无需任何签名密钥，安装零门槛；与仓库内监测工具（`apk-latest`）的「自动构建 debug APK」模式一致。移除 signing keystore 准备步骤，`on.push.branches` 精简为仅 `psapk`（避免误触发仓库主分支的监测项目）。`tag_name: latest` / `make_latest: true` / 非预发布保持不变。

## 构建（gradlew 包装）(2026-11-04)
- 补齐 Gradle Wrapper：新增官方 `gradlew` / `gradlew.bat` 与二进制 `gradle-wrapper.jar`（取自 Gradle v8.4.0 官方标签），`gradle-wrapper.properties` 已指向 gradle-8.4-bin.zip。
- 现可 `./gradlew assembleRelease`（Windows `gradlew.bat`）一键本地打包，首次运行自动下载 Gradle 8.4；无需预先安装 Gradle（仍需本机 JDK 17）。

## CI 发布调整 (2026-11-04)
- `.github/workflows/release-apk.yml`：Release 改用稳定标签 `latest` 且 `prerelease: false`（标记为 Latest），每次构建滚动更新，提供固定下载地址 `…/releases/latest/download/app-release.apk`，免去每次构建更换链接。

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
