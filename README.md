# 悬浮助手 · FloatingAssistant

Android 无障碍悬浮助手：**屏读 OCR、整屏翻译、朗读 TTS、就地编辑的记事本**，全部在你自己手机里跑完。

端侧推理，**没有 INTERNET 权限**（`app/src/main/AndroidManifest.xml` 里一条都不曾声明），也就无法把屏幕内容发出去。

> 状态：开发中的个人工具，仍在逐片验收（见下方「已知限制」）。没有发布版 APK，克隆后自行构建。

---

## 它做什么

| 能力 | 实现 | 说明 |
|---|---|---|
| 屏读 OCR | PP-OCRv4 mobile（ONNX Runtime） | 对当前屏幕（或选定区域）做检测+识别，结果读成文字 |
| 整屏翻译 | Hy-MT2-1.8B GGUF + 特化 llama.cpp | 两档权重按场景路由：整页走轻量 1.25-bit，命中黑名单或人工强制时升 Q4_K_M |
| 朗读 TTS | sherpa-onnx + `vits-melo-tts-zh_en` | 中文+英文单女声；合成产物落 `files/tts_out/` |
| 记事本 / 就地编辑 | 自绘 `TYPE_ACCESSIBILITY_OVERLAY` 面板 | 可聚焦正文、全选/复制/粘贴/撤销、编码与换行可重选、外部文件读写、按字数分页、脏判自动落库 |
| 扇形菜单 | 悬浮球展开的同心环 | 工具注册即出现在菜单里（`tool/Tool.java`）；当前全是叶子节点 |
| 设备自调 / 一键自检 | 调参页 + `SelfTestRunner` | 截图组热生效；写系统动画缩放需一次 adb 授权 |

## 环境要求

- **Android 11（API 30）以上**，`minSdk = 30`。
- **只有 `arm64-v8a`**：端侧 MT 原生库是 STQ NEON 特化交叉编译产物，`abiFilters` 只留 arm64。
- 构建需 JDK 17 + Android SDK（`compileSdk 35`）；重建 MT 原生库还须把 `ANDROID_NDK` 指向一份带
  `toolchains/llvm/prebuilt/linux-x86_64` 与 `build/cmake/android.toolchain.cmake` 的 NDK。
- 验证机：Redmi 13R 5G（HyperOS 2.0.208 / Android 15，720×1600，density 2.0）。其他机型未验证。

## 装好并开起来

1. 构建并安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. **开启无障碍服务**（这是唯一必需的手动步骤）：设置 → 无障碍 → 已下载的服务 / 更多无障碍设置 → **悬浮助手** → 开启。
   悬浮球、面板、编辑器全部用 `TYPE_ACCESSIBILITY_OVERLAY` 画，**不需要单独授「显示在其他应用上层」权限**，
   也没有任何动态申请弹窗。
3. 前台服务（`KeepAliveService`，`foregroundServiceType=specialUse`）会要一次通知权限。
4. 可选：调参页里「关掉系统动画缩放」需要写 `Settings.Global`，授一次就永久有效：

   ```bash
   adb shell pm grant com.flexiatom.floatingassistant android.permission.WRITE_SECURE_SETTINGS
   ```

## 权重与二进制：都不入 git，自备

体积与可重建性决定它们不进仓库（见 `.gitignore`），落点都在应用的**外部文件目录**：
`/storage/emulated/0/Android/data/com.flexiatom.floatingassistant/files/`

| 需要的东西 | 落点 | 出处 / 重建方式 |
|---|---|---|
| `ch_PP-OCRv4_det_infer.onnx`、`ch_PP-OCRv4_rec_infer.onnx`、`ppocr_keys_v1.txt` | `files/` 根 | RapidOCR / PaddleOCR；sha256 与逐条说明见 `bench/models/MANIFEST.md` |
| `Hy-MT2-1.8B-1.25Bit.gguf`（轻量档）、`Hy-MT2-1.8B-Q4_K_M.gguf`（重载档） | `files/` 根 | AngelSlim/Hy-MT2-1.8B-1.25bit-GGUF；同上 |
| `vits-melo-tts-zh_en/`（`model.onnx` + `tokens.txt` + `lexicon.txt` + `dict/` + 四个 `.fst`） | `files/` 下的同名子目录 | sherpa-onnx 的 `tts-models` release；**注意**：`adb shell mkdir` 建出来的子目录是 `shell:ext_data_rw` 且 `770`，应用侧读成**空目录**，push 完补一次 `chmod 755` 才可见 |
| `sherpa-onnx-1.13.8-arm64-static.aar` | `app/libs/` | 上游 static-link AAR + 只删非 arm64 ABI（`zip -d`），哈希与逐字节核对见 MANIFEST 的 TTS 段 |
| `libllama.so` / `libggml*.so` / `libggml-cpu.so` / `libmtjni.so` | `app/src/main/jniLibs/arm64-v8a/` | `native/llama-stq/build-android.sh`（锁定 llama.cpp PR #22836 head + `stq-renumber.patch`）重建 |

缺哪个就降级哪个，不会崩：缺 OCR 模型 → `StubOcrEngine`；缺 MT 权重 → `PassthroughMtEngine`（只显原文）；缺 TTS 目录 → 朗读项自述不可用。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug                    # 需要联网取依赖；已缓存可加 --offline
./gradlew -g "$PWD/.cache/gradle-home" --offline assembleDebug   # 本项目日常用的那条
```

`preBuild` 挂着 `verifyStqSo`：`ANDROID_NDK` 未设或 `jniLibs` 里没有 `.so` 时它**只告警跳过**，
纯 Java 开发照常 `assembleDebug`。

## 无触摸调试（这个仓库的一条硬要求）

这台机器上「能不能不看截屏把问题定位掉」就是验收口径，所以每条通路都留了 `am start` 探针门，
回执一律走 `logcat`，不需要人眼、不需要点屏：

```bash
adb -s <serial> shell am start -f 0x20000000 -n com.flexiatom.floatingassistant/.MainActivity \
  --ez p5_menu true --es p5_editor "<spec>"
adb -s <serial> logcat -d -v brief -s P5EDITOR
```

常用开关（`MainActivity` 的 intent extras，全量看 `grep -n 'EXTRA_' app/src/main/java/**/MainActivity.java`）：

| 开关 | 类型 | 作用 |
|---|---|---|
| `p5_ball` | string `"x,y"` | 把悬浮球放到指定坐标（原点像素层） |
| `p5_ball_state` / `p5_menu` / `p5_menu_hide` | bool | 读球状态 / 展开扇形菜单 / 收起菜单 |
| `p5_panel_open` / `p5_panel_close` | bool | 开、关当前面板 |
| `p5_panel_pick` | string | 按工具代码选中面板里的一项（代替手指点） |
| `p5_editor` | string | 对编辑器下指令。动词全量见 `FloatingEditorView.runActionForTest`：`state`（不带参即是它）、`open-uri:<file://…>`、`page:<n>`、`type:`、`sel:`、`caret:`、`save`、`undo`、`rename:`、`open-stats`……回执走 `P5EDITOR` |
| `p5_a11y` / `p5_a11y_tap` / `p5_a11y_child` | bool / bool / int | 从无障碍树探节点、对自家 UI 打一次 tap（代替手指） |
| `run_p0` / `run_ort` / `run_ort_nnapi` / `run_p1` | bool + `*_iterations`、`*_threads`、`*_model` 等 | 截图通路、ONNX/NNAPI 单发、OCR 全链基准 |
| `p5_tts` / `tts_speak` | bool / string | TTS 装机探针、直接合成一句话 |
| `p6_mt` / `p6_mt_tgt` | string | 直接翻一段文本、指定目标语言 |
| `p7_selftest` / `p7_selftest_n` | bool / int | 一键自检（默认 8 轮） |
| `anim_toggle` | bool | 动画缩放自调（需上面那条 adb 授权） |

两个已踩过的触发坑（`bench/models/MANIFEST.md` 记的原文）：冷启动一次 `am start` 会把同一 intent 交**两遍**
（`onCreate` 后约 2.6 s 又走一次 `onNewIntent`），而进程已在前台时又**一个回调都不走**——所以示例里带
`-f 0x20000000`（SINGLE_TOP），探针侧另按参数串做 20 s 去重。

日志 tag：`P5EDITOR`（编辑器指令回执）、`EDITORSTATE`（状态戳）、`P5LAYOUT`（**慢帧表**，`onMeasure ≥ 100 ms` 才落一行）、
`P5PANEL`、`P0CAPTURE`、`P1OCR`、`ORTPROBE`、`NNAPI`、`P5TTS`、`TTS`、`A11YPROBE`、`TuningSelfTest`。

## 目录结构

```
app/src/main/java/com/flexiatom/floatingassistant/
  capture/    屏幕采集与采集策略（含 P0 基准）
  core/       引擎接口、设置/调参/统计存储、记事本（NotesStore）与编解码（NotesCodec）
  mt/         端侧翻译：模型仓库、路由、提示词、JNI 引擎与降级实现
  ocr/        PP-OCRv4 引擎、ONNX/NNAPI 探针、P1 基准
  overlay/    全部自绘 UI：扇形菜单、面板、拖动手柄、悬浮编辑器、输入法桥
  service/    无障碍服务（唯一入口）、前台服务、探针
  settings/   设置页与设备自调页
  tool/       扇形菜单的工具注册表
  tts/        sherpa-onnx 封装与探针
lib/beast/    兽音（beast）编解码，纯 Java 模块 + 单元测试（尚未接进 app）
native/llama-stq/  MT 原生库的交叉编译脚本、STQ 重编号补丁、构建期校验
bench/models/MANIFEST.md  每个模型/二进制的出处、尺寸、sha256 与实测记录
```

## 已知限制

- **只做了那一台机型的验证**（Redmi 13R 5G）。HyperOS 上有一批平台特有坑，源码注释里逐条记着原因；
  换机型等于换一批未知数。
- 悬浮编辑器按字数分页（6,000 字/页），载入前先查文件大小，超过 **16 MB 整篇不读**；
  **翻页会清撤销栈**（跨页回滚会把另一页的字盖进当前页，宁可不做）；坏字（`U+FFFD`）闸门在写回盘前拦一次。
- 无障碍服务画的 overlay 在 `uiautomator dump` 里**看不见**（HyperOS 实测），所以自动化验证走自己的探针门。
- APK 因为 static-link 版 sherpa AAR 静态自带 ONNX Runtime，体积 **+24 MB**（选型理由与证伪过程见 MANIFEST）。
- 兽音编解码（`lib/beast`）还没有接进 app；扇形菜单的容器/同心环是下一片。
- 没有 release 版、没有签名、没有 CI，`versionName` 只是给安装器看的 `0.5.0`。

## 许可

- 本程序以 **GPL-3.0-or-later** 授权，全文见 [`LICENSE`](LICENSE)；每个源文件头部带传统 boilerplate 与 `SPDX-License-Identifier`。
- 第三方组件的许可、出处与重建路径见 [`NOTICE`](NOTICE)；同一份内容打进 APK 的形态在
  `app/src/main/assets/THIRD-PARTY-NOTICES`（拿到 APK 而没有仓库的人也能读到条款正文）。
- 模型权重按各自条款（Apache-2.0 / MIT）由用户自行从上游取得，本项目不重授权它们。
