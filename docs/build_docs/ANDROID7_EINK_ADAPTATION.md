# SpeedyNote 安卓 7 (API 24) 墨水屏平板适配说明

> 本文档记录将 SpeedyNote 从主线（Qt 6.9.3 / Android 8+）适配到 **Android 7 墨水屏平板**
> 的全部改动、原理、构建步骤与验证清单。
>
> ⚠️ 本适配**取代**了 [`ANDROID_BUILD_GUIDE.md`](./ANDROID_BUILD_GUIDE.md) 中的版本号
> （那份文档描述的是主线 Qt 6.9.3 / API 26 / NDK 26.1 工具链）。以本文为准。

---

## 1. 目标设备

| 项目 | 参数 |
|------|------|
| 屏幕尺寸 | 10.3 英寸 |
| 分辨率 | 1404 × 1872（3:4 竖屏） |
| 像素密度 | ≈ 227 dpi（`√(1404²+1872²) / 10.3 ≈ 227`） |
| 显示技术 | 墨水屏 / 电子纸（E-Ink / E-Paper） |
| 系统 | Android 7.0 / 7.1（**API 24 / 25**） |
| CPU 架构 | arm64-v8a（保留 armeabi-v7a 32 位兜底） |

227 dpi + 3:4 竖屏 + 电子纸，是典型的手写笔记类墨水屏平板特征。

---

## 2. 核心障碍与方案

### 2.1 为什么主线无法直接跑在 Android 7

主线使用 **Qt 6.9.3**。Qt for Android 各分支的**官方最低 API**如下：

| Qt 分支 | 最低 Android API | 对应系统 |
|---------|-----------------|----------|
| Qt 6.2 – 6.4 | **API 23** | Android 6.0 |
| Qt 6.5 – 6.7 | API 26 | Android 8.0 |
| Qt 6.8+ | API 28 | Android 9.0 |

Qt 6.9.3 的预编译库是针对 API 28+ 编译的，**在 Android 7 设备上无法加载**
（缺少符号 / libc 版本不符）。这是根本性障碍，无法通过改配置绕过。

### 2.2 方案

**降级到 Qt 6.4.3** —— 唯一官方支持 API 23（因而能合法覆盖 Android 7 / API 24）的 Qt6 分支，
并**整套工具链联动降级**（Qt 决定了 NDK、AGP、Gradle、compileSdk 的可用版本）。
在此基础上叠加**墨水屏显示优化**（禁用惯性滚动与动画，消除残影）。

---

## 3. 工具链版本锁定

| 组件 | 主线 | 适配后 | 原因 |
|------|------|--------|------|
| Qt | 6.9.3 | **6.4.3** | 唯一支持 API 23/24 的 Qt6 分支 |
| NDK | 26.1.10909125 | **23.1.7779620 (r23b)** | Qt 6.4 用 r23b 编译；不一致会缺 `__emutls_get_address` |
| minSdk | 26 | **24** | Android 7.0 |
| compileSdk | 34/35 | **33** | 与 AGP 7.4.x 干净配对 |
| targetSdk | 35 | **33** | 同上 |
| build-tools | 34.x | **33.0.2** | 匹配 compileSdk 33 |
| AGP | 8.8.0 | **7.4.2** | AGP 8.x 要求 compileSdk 34+ / Gradle 8.10+ |
| Gradle wrapper | (Qt 模板自带) | **7.6.4（显式固定）** | AGP 7.4.2 要求 Gradle ≥ 7.5；JDK 17 要求 ≥ 7.3；Gradle 8 又需 AGP 8 |
| androidx.core | 1.16.0 | **1.10.1** | 1.16.0 要求 compileSdk 34+ |
| ML Kit digital-ink | 19.0.0 | **18.2.3** | 19.0.0 要求 compileSdk 34+；所用 API 未变 |

### 两个易混淆的 "platform"

- **CMake `-DANDROID_PLATFORM=android-24`** —— 原生 NDK 编译平台级，**必须 = minSdk = 24**，
  保证生成的 `.so` 不含 API 24 之后才有的 libc 符号。
- **androiddeployqt `--android-platform android-33`** —— Java 层的 compileSdk，= 33。
- 两者是不同概念，取值也不同，不要写成一样。

---

## 4. 改动清单（按文件）

### 构建 / 工具链

| 文件 | 改动 |
|------|------|
| `android/Dockerfile` | `QT_VERSION=6.4.3`、`NDK_VERSION=23.1.7779620`、`ANDROID_MIN_SDK=24`、`ANDROID_TARGET_SDK=33`、`build-tools;33.0.2`、`platforms;android-33`；新增**固定 Gradle wrapper 到 7.6.4** 的 `RUN` 步骤 |
| `android/build-speedynote.sh` | Qt 路径 → `6.4.3`（arm64/armv7/host）、NDK → `r23b`、`-DANDROID_PLATFORM=android-24`、`--android-platform android-33` |
| `android/build-mupdf.sh` | NDK → `23.1.7779620`、`ANDROID_API=24`（静态库，保证 `.so` 可在 Android 7 加载） |
| `android/build-test-app.sh` | Qt → `6.4.3`、NDK → `r23b`、`--android-platform android-33`（保持一致） |
| `android/app-resources/build.gradle` | AGP → `7.4.2`、`androidx.core:core:1.10.1`、`digital-ink-recognition:18.2.3` |
| `CMakeLists.txt` | `QT_ANDROID_MIN_SDK_VERSION 24`、`QT_ANDROID_TARGET_SDK_VERSION 33`；`qt_policy(QTP0002)` 用 `if(QT_KNOWN_POLICY_QTP0002)` 门控（Android + OHOS 两处）；Android 分支 `add_compile_definitions(SPEEDYNOTE_EINK_DEFAULT)` |

> **`qt_policy` 门控说明**：`qt_policy()` 命令与 `QTP0002` 策略是 Qt **6.5** 引入的。
> 在 Qt 6.4 上直接调用会报 "未知命令" 硬错误。用 `if(QT_KNOWN_POLICY_QTP0002)` 包裹后，
> 同一份 `CMakeLists.txt` 在 6.4 与 6.5+ 上都能配置。QTP0002 只影响含生成器表达式的
> Android 路径属性，本项目用普通路径，跳过它安全无副作用。

### 墨水屏（E-Ink）优化

| 文件 | 改动 |
|------|------|
| `source/ui/EInkMode.h` | **新增**。头文件内联助手，集中式墨水屏开关（详见第 5 节） |
| 12 个源文件 | 共 **15 处**门控：10 处 `QPropertyAnimation` 时长 + 5 处惯性滚动触发 |

---

## 5. 墨水屏（E-Ink）优化

### 5.1 原理

电子纸刷新慢、且有**残影（ghosting）**：会保留上一帧的淡影。在 LCD/OLED 上顺滑的
高帧率连续效果，在墨水屏上反而有害：

- **惯性 / fling 滚动**：一次手势重绘数十帧 → 严重残影、几乎无法阅读。
- **`QPropertyAnimation` 过渡**（淡入淡出、滑动、吸附、旋转）：中间帧在电子纸上糊成一片。

优化策略：墨水屏模式下，把动画时长降为 **0**（瞬间完成，只保留终态），并**跳过惯性滚动**
（滚动 1:1 跟手，松手即停）。以牺牲动效换取干净、无残影、易读的画面。

### 5.2 开关：`EInkMode`

`source/ui/EInkMode.h` 提供两个内联函数：

```cpp
EInkMode::enabled()          // 是否处于墨水屏模式（会话内解析一次并缓存）
EInkMode::anim(nominalMs)    // 墨水屏开时返回 0，否则返回原时长
```

**激活优先级**（首个命中生效）：

1. **环境变量** `SPEEDYNOTE_EINK`：`1`/`true`/`on`/`yes` → 开；`0`/`false` → 关。
2. **QSettings** 键 `display/einkMode`（组织 `SpeedyNote`，应用 `App`，bool）。
3. **编译期默认**：定义了 `SPEEDYNOTE_EINK_DEFAULT` 时默认开。
   **Android 构建在 `CMakeLists.txt` 中定义了它 → 安卓包默认开启墨水屏模式。**
   桌面构建未定义 → 默认关闭，不受影响（除非显式打开）。

> 临时验证：`adb shell setprop debug.speedynote.eink ...` 不适用；请用应用内设置写入
> `display/einkMode`，或在启动前设 `SPEEDYNOTE_EINK` 环境变量。

### 5.3 已门控的位置（共 15 处）

**动画时长（`setDuration(EInkMode::anim(X))`，10 处）：**

| 文件 | 动画 |
|------|------|
| `source/MainWindow.cpp` | 启动窗口淡入 |
| `source/harmony/HarmonyWindowSwitch.cpp` | 窗口切换淡入（Launcher ↔ 主窗口，全平台生效） |
| `source/ui/launcher/Launcher.cpp` | Launcher 淡入 |
| `source/ui/launcher/FloatingActionButton.cpp` | 悬浮按钮展开 + 旋转（2 处） |
| `source/ui/actionbars/ActionBarContainer.cpp` | 操作条滑动（2 处） |
| `source/ui/banners/MissingPdfBanner.cpp` | 横幅滑入 |
| `source/ui/widgets/ExportProgressWidget.cpp` | 导出进度淡入 |
| `source/ui/widgets/PageWheelPicker.cpp` | 页轮吸附 |

**惯性 / fling 滚动（`if (EInkMode::enabled()) …`，5 处）：**

| 文件 | 机制 | 处理 |
|------|------|------|
| `source/ui/launcher/KineticScrollHelper.cpp` | `finishTracking()` | 直接返回 `false`，不启动 fling |
| `source/ui/widgets/PageWheelPicker.cpp` | `startInertia()` | 改为直接 `snapToPage()` |
| `source/ui/sidebars/PagePanelListView.cpp` | `startKineticScroll()` | 开头直接 `return` |
| `source/ui/sidebars/OutlinePanelTreeWidget.cpp` | `startKineticScroll()` | 开头直接 `return` |
| `source/ui/sidebars/NotesTreeWidget.cpp` | `startKineticScroll()` | 开头直接 `return` |

> 代码库中存在**四套彼此独立**的 kinetic 滚动实现（`KineticScrollHelper` +
> `PagePanelListView` / `OutlinePanelTreeWidget` / `NotesTreeWidget` 各自的
> `startKineticScroll`），已**全部**门控。其余列表/树视图（如 `LayerPanel`）本就采用
> 1:1 手动滚动、无动量，天然适配墨水屏。
>
> `source/ui/dialogs/DialogSizing.h` 里的 `QScroller::grabGesture` 被 `#ifdef Q_OS_HARMONY`
> 包裹，**Android 构建不编译**，故无需处理。

### 5.4 可选的后续增强（未实施）

电子纸的**全刷（full refresh）**通常需通过厂商的 EPD 控制 API 触发（非 Qt 层能力）。
若设备厂商提供了 Android SDK，可在关键界面切换后主动请求一次全刷以彻底清除残影。
本适配停留在 Qt 层，未涉及厂商私有 API。

---

## 6. 构建步骤

> 前置：Linux + Docker（当前 Windows 开发机无法直接构建，需在 Linux/Docker 环境执行）。

```bash
# 1) 构建 Docker 镜像（含 Qt 6.4.3 / NDK r23b / SDK 33，并固定 Gradle 7.6.4）
cd android
./docker-build.sh          # 等价于 docker build -t speedynote-android:latest -f Dockerfile .

# 2) 进入容器（工程挂载到 /workspace）
./docker-shell.sh

# 3) 容器内，先交叉编译 MuPDF（静态库，API 24）
cd /workspace
./android/build-mupdf.sh

# 4) 构建 SpeedyNote APK
./android/build-speedynote.sh --apk
#    可选： --aab（Play 上架）  --both  --arm64 / --arm32  --release（需签名环境变量）

# 5) 产物
#    android/SpeedyNote.apk
```

安装到平板（USB 调试已开启）：

```bash
adb install -r android/SpeedyNote.apk
```

> 首次构建需联网：Gradle 会下载 7.6.4 发行包、AGP 7.4.2、androidx.core 1.10.1、
> ML Kit 18.2.3 及其传递依赖；aqtinstall 会下载 Qt 6.4.3。`docker-shell.sh` 已挂载
> `~/.gradle` 缓存以加速后续构建。

---

## 7. 验证清单

### 7.1 构建期（Docker 内）

- [ ] Docker 镜像构建成功；日志出现 `Pinned Gradle wrapper -> 7.6.4 in: ...`（每个 Qt 安装各一行）。
- [ ] `build-mupdf.sh` 产出 `android/mupdf-build/{arm64-v8a,armeabi-v7a}/lib/libmupdf.a`。
- [ ] `build-speedynote.sh --apk` 无 `qt_policy` 未知命令错误（验证门控生效）。
- [ ] 无 `__emutls_get_address` 等缺符号错误（验证 NDK r23b 一致）。
- [ ] 无 "Android Gradle plugin was tested up to compileSdk 33" 之类警告升级为错误。
- [ ] 生成 `android/SpeedyNote.apk`。

### 7.2 打包产物核验

```bash
# 用 build-tools 33.0.2 的 aapt 查看 minSdk / targetSdk
$ANDROID_SDK_ROOT/build-tools/33.0.2/aapt dump badging android/SpeedyNote.apk | grep -i sdk
# 期望： sdkVersion:'24'   targetSdkVersion:'33'
```

- [ ] `sdkVersion:'24'`（= minSdk，可装到 Android 7）。
- [ ] `targetSdkVersion:'33'`。

### 7.3 设备期（Android 7 实机）

- [ ] APK 能在 Android 7.0/7.1 平板上**安装并启动**（主线包会因 API 28 依赖而崩溃/无法安装）。
- [ ] 打开 / 标注 PDF、手写、手写识别（ML Kit 18.2.3，注意首次需联网下载模型）。
- [ ] 运行时存储权限：Android 7 属旧存储模型，`READ_EXTERNAL_STORAGE`（manifest 中
      `maxSdkVersion=32`）会正常授予；确认能读写文档目录。

### 7.4 墨水屏效果（1404×1872 / 227 dpi）

- [ ] 滚动列表/大纲/笔记树/页缩略图时**无惯性滑动**、**无明显残影**。
- [ ] 界面切换、悬浮按钮、操作条、导出进度等**无中间帧拖影**（动画瞬间完成）。
- [ ] **DPI / 缩放**：227 dpi 下 UI 元素与字号大小合适、点击目标不过小。如偏小/偏大，
      可调 `QT_SCALE_FACTOR` 或应用内字号（本适配未改布局尺寸）。
- [ ] **竖屏 3:4**：确认布局在 1404×1872 竖屏下正常（`AndroidManifest` 未硬编码
      `screenOrientation`，跟随系统/传感器；`supports-screens` 已含 `xlargeScreens=true`）。

---

## 8. 已知风险与限制

1. **未做实机验证**：当前开发环境为 Windows，无 Docker / Qt / Linux，改动均为**静态配置
   与源码级适配**，未在真实构建与设备上跑通。请务必按第 6、7 节在 Linux/Docker + 实机验证。
2. **1404×1872 竖屏专项布局未做**：本次范围是「打通 Android 7 SDK + 墨水屏优化」，
   未针对该分辨率重排版面。若实机发现布局问题，属后续独立工作项。
3. **Qt 6.4.3 的其它行为差异**：主线基于 6.9.3 开发，降级到 6.4.3 后，个别 6.5–6.9
   新增的 Qt 行为/默认值可能不同。已确认源码仅用 `QT_VERSION_CHECK(6,0,0)/(5,14,0)`
   门控、无 6.5+ 独占 API（除已处理的 `qt_policy`）；但运行时仍建议回归测试主要功能。
4. **ML Kit 18.2.3**：手写识别 API 与 19.0.0 一致，但模型下载/兼容性需在实机联网确认。
5. **Gradle 缓存**：若宿主机 `~/.gradle` 里缓存了旧版本产物导致异常，可清理后重试。

---

## 9. 快速回滚

所有改动集中在构建配置 + `EInkMode` 门控，且门控在**非墨水屏模式下等价于原行为**
（`EInkMode::anim(X)` 返回 `X`，`EInkMode::enabled()` 为 `false` 时惯性逻辑照旧）。
桌面/主线构建**未定义** `SPEEDYNOTE_EINK_DEFAULT`，因此默认完全不受影响。
如需回到主线 Android 8+ 工具链，`git` 还原上述文件即可。
