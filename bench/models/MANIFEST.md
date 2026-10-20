# P1 OCR 基准模型来源（PP-OCRv4 mobile ONNX）

> 注：提案 §415 原定 PP-OCRv5 mobile，但镜像(hf-mirror)上**无 v5 mobile 的 ONNX 发布**
> （仅 v5 server，det 88MB / rec 84MB，超端侧体积预算）。故基准先取 **v4 mobile**
> ——RapidOCR 久经考验的默认档，前后处理文档最稳，体积(det 4.7MB / rec 10.9MB)正合
> §415 自己写的预算。v5/v6 mobile ONNX 一旦可得再换。

| 文件 | 用途 | sha256 | 来源 |
|---|---|---|---|
| `ch_PP-OCRv4_det_infer.onnx` | 文本检测(DB) | `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9` | https://hf-mirror.com/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx |
| `ch_PP-OCRv4_rec_infer.onnx` | 文本识别(CTC) | `48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b` | https://hf-mirror.com/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx |
| `ppocr_keys_v1.txt` | rec 字典(6623 字符) | `28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7` | https://raw.githubusercontent.com/frotms/PaddleOCR2Pytorch/main/pytorchocr/utils/ppocr_keys_v1.txt |

## 模型 I/O 形状（onnx 直读确认）
- det：输入 `x [N,3,H,W]`（H,W 为 32 的倍数）→ 输出 `sigmoid_0.tmp_0 [N,1,H,W]` 概率图。
- rec：输入 `x [N,3,?,?]`（高固定 48）→ 输出 `softmax_11.tmp_0 [N,T,6625]`。
  - 6625 = CTC blank(1) + 字符表(6624)，字符表 = `[" "] + ppocr_keys_v1.txt(6623)`。

## 不入库
`.onnx` / `.txt` 二进制由 `.gitignore` 排除（体积）。本清单仅钉来源与哈希，
复现：按上表 URL `curl -L -o` 后 `sha256sum -c` 核对。

---

# P2-T 端侧 MT（Hy-MT2-1.8B STQ 1.25-bit + 特化 llama.cpp 原生库）

## 模型 GGUF（不入库，adb push 到 getExternalFilesDir）
| 文件 | 大小 | sha256 | 来源 |
|---|---|---|---|
| `Hy-MT2-1.8B-1.25Bit.gguf` | 461,860,800 B | `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93` | AngelSlim/Hy-MT2-1.8B-1.25bit-GGUF（hunyuan-dense，量化张量 raw type=42=STQ1_0）；**LIGHT 槽常驻**（默认/日用/降级） |
| `Hy-MT2-1.8B-Q4_K_M.gguf` | 1,133,080,448 B | `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699` | 同模型 Q4_K_M 量化（原生 GGML_TYPE_Q4_K，不依赖 STQ 特化）；**HEAVY 槽常驻**（黑名单命中或人工强制时升它） |

设备路径（两文件各归各名、并存，工厂按 `MtModelKind` 取对应名）：
`/sdcard/Android/data/com.flexiatom.floatingassistant/files/{Hy-MT2-1.8B-1.25Bit.gguf, Hy-MT2-1.8B-Q4_K_M.gguf}`。
**两模型并存 ≈1.6GB 设备存储**（用户已知并接受）；任一时刻只 mmap 一个进内存（见下"单引擎驻留"）。

**密集页对照实测（P3 demo，870 字技术正文，22 OCR 框，分段 SEG_MAX_CHARS=48 → 20 段）**：
- 1.25-bit：整屏及细粒度分段均坍缩（空输出 `gen=0` / 乱码 `A A A` / 复读 `gen=256`），okSegs 远低于 segs。
- Q4_K_M：`segs=20 okSegs=20 outChars=3184 transMs≈103221`（含 6.5s 冷加载，纯译 ~4.85s/段），英文连贯可读、个别字残留（如"布"未译、"热循环"译成 thermal cycle）。**结论：Q4 决定性改善密集页翻译**。代价：模型 2.45× 体积、冷加载 ~4×、单段延迟上升。

## 分场景路由（`MtModelHub`：整页级 + 单引擎驻留，真机实测 2026-10-16）

优先级：**人工模式（强制轻量/强制 Q4）> 自动（黑名单命中→HEAVY，否则→LIGHT）**。目标模型与当前驻留不同 → 先 `close()` 旧再懒建新，**刻意不双驻**（6GB 设备两模型同时在内存会挤掉前台应用）。

真机 Redmi 13R 5G 采集实测（logcat + `dumpsys meminfo`）：

| 场景 | 关键日志 | Native Heap |
|---|---|---|
| 强制轻量翻密集页 | `MtHub route=LIGHT matched=false mode=1 pageChars=817`；`CaptureRouter MT done segs=20 okSegs=14 outChars=989` | ~199MB（轻量在位） |
| 切强制 Q4（换面升档） | `MtFactory HEAVY ready loadMs=6795`；`MtHub route=HEAVY matched=false mode=2` | **787MB**（Q4 在位） |
| 切回强制轻量（换面降档） | `MtFactory LIGHT ready loadMs=1573`；`MtHub route=LIGHT matched=true mode=1` | **回落 250MB**（Q4 已释放） |

- **单引擎驻留坐实**：RSS 走 199→787→250，换面时旧引擎 mmap 被释放（不是两模型叠加），配对 `loadMs`（HEAVY 6795 / LIGHT 1573）证明"先关后建"。
- **人工优先于黑名单**：`route=LIGHT matched=true mode=1`——黑名单命中(matched=true)但强制轻量仍走 LIGHT。
- **缺 Q4 降级**：把 Q4 改名 `.off` + 强制 Q4 → `MtFactory HEAVY model missing … → passthrough` → `LIGHT ready loadMs=730` → `MtHub 降级到另一槽: LIGHT` → `route=LIGHT mode=2`；OCR 覆盖层不受影响，仅回落轻量（会重现其密集页复读坍缩）。
- **坏正则不崩**：黑名单含 `(` + 关键词时点设置页「测试」→ 正常返回「未命中 → 走轻量模型」，进程存活（`BlacklistMatcher` 跳过非法 Pattern）。
- **设置页**：`SettingsActivity` 完整渲染（模式单选/黑名单增删/关键词·正则切换/试匹配），`mt_settings.xml` 序列化往返正确（`kw:`/`re:` 前缀 + 版本自增）。
- **P2-C 共存（用户基准机实测，M3 硬验收）**：FORCE_Q4 下遍历 QQ / PiliPala / 番茄小说 / B站 / LACPD高画质 / com.eltechs.ragh(RA2)，**前台 App 均未被杀**（1.25-bit 同样不杀）。旁证 logcat 见 `lowmemorykiller` 高频 `Kill … reason: cw` 清**缓存态后台**（settings/xmsf/miui.core，oom_score_adj 800–935），即 Q4 常驻压入内存后系统"杀后台、不杀前台"→ 默认 1.25-bit 共存立足点坐实。**Q4 整页延迟实测**：`transMs=68558`（14 段）/ `252079`（4.2 分钟，11 段），单段 ~20s；据此后续上 `availMem` 加载门槛 + 悬浮球"再点=取消在途翻译"（提交 9488abe）。
- **取消在途翻译（真机坐实，提交 9488abe）**：FORCE_Q4 下密集页翻译进行中再点悬浮球 → `CaptureRouter: MT cancelled at 2/12 outChars=178`（另见 8/14·1/18·1/7·2/7，各保留已译部分），紧接新 `ROUTE=A` 采集正常起；未取消那次 `MT done transMs=51213 segs=14`。修掉"Q4 长翻译期间 busy 锁死、点击无反馈、覆盖层卡旧结果"。HEAVY 冷加载 `loadMs=7135` 与旧测一致。
- **前台切走即卸载引擎（真机坐实，提交 10be166，§5.5 修订后保留的内存护栏）**：FORCE_Q4 下 QQ 聊天页翻完 `MT done transMs=26342 segs=5` 进入空闲 → 切到别的 App → `CaptureRouter: engine unloaded (foreground left)`；`dumpsys meminfo` Native Heap 由 HEAVY 驻留的 ~787 MB 回落至 **~4 MB（TOTAL PSS 157 MB）**，证 `mtHub.close()` 实释放 mmap/native。P2-C 已 falsify「Q4 杀前台」，故 M2 不做 availMem 硬拦（FORCE_HEAVY 豁免），仅保留此"切走即卸载"软护栏降低常驻内存。

**已知限制**：`强制 Q4 + Q4 缺失` 组合下，`loadedKind` 记为降级后的 LIGHT，而每页 `want` 仍是 HEAVY → 每次采集重试 Q4、失败、重载 LIGHT（loadMs ~700 反复）。仅该罕见组合触发；Q4 在位或 AUTO/强制轻量模式不受影响。

## 原生库 .so（不入库，由 build-android.sh 重建，见 §3.1b 构建时校验）
源 = llama.cpp PR #22836 head `1e411d8f…` + `native/llama-stq/stq-renumber.patch`（STQ1_0↔Q2_0：42↔43），
NDK r29、`armv8.2-a+dotprod+fp16`（**不含 i8mm**——天玑6100 无 ARMv8.6 i8mm，加了会让 Q4_K_M 的 gemm 编出非法指令、真机 SIGILL）、`c++_static`。sha256（重建后应逐字节一致，否则 verify 或本表报警）：

| .so | sha256 |
|---|---|
| `libggml-base.so` | `0dcf96d2ec5d8bc1844d32c3dfd2b764f68b70df9d1dcdaba69688621e574814` |
| `libggml-cpu.so` | `e7f13a7cecaa64a0abb8d1d4cf16ee8b9264da00bd2a5a332ca95943efcbe461` |
| `libggml.so` | `66c4a7c0a1e05fee5fd5efcc4e1c3724e702dd06401c177b603384b3bdce6d91` |
| `libllama.so` | `f0d11ae50befdf841d470a34c5dc35b36c1b4c8c025f32e90f69afd71266f432` |
| `libmtjni.so` | `b993c444ced41f17481b445c82bb3591ed4bf2b98943dccd43e7eed41c55e46a` |

缺模型 → `PassthroughMtEngine`（只显 OCR 原文）；缺 .so → `assembleDebug` 时 `verifyStqSo` fail，或运行时 `UnsatisfiedLinkError` 降级。

## 许可（GPLv3 × Apache-2.0）
- 本 app：GPLv3。llama.cpp/ggml：MIT。Hy-MT2 模型：Apache-2.0（LICENSE.txt 实测纯 Apache-2.0，无附加限制）。
- MIT/Apache-2.0 组件静态/动态链入 GPLv3 产物合法（许可证兼容）；分发 APK 须随附第三方许可声明（见 assets/THIRD-PARTY-NOTICES）。

---

# TTS（tts-engine-replacement，读法①）依赖与模型

## 依赖：用 sherpa-onnx 的 **static-link-onnxruntime** AAR（常规 fat AAR 在本项目里不可用）
Maven Central 上**没有** sherpa-onnx（`repo1.maven.org/maven2/com/k2fsa/sherpa/onnx/` → 404，实测）；
官方 Android 发布物只有 GitHub release 里的两个 AAR：**常规 fat AAR**（带 `libonnxruntime.so`）与
**static-link AAR**（ORT 编进 JNI 库）。本项目只能用后者，理由见下。

| 文件 | 角色 | sha256 | 来源 / 改法 |
|---|---|---|---|
| `sherpa-onnx-1.13.8.aar`（50,129,134 B，现存 `~/Downloads/`） | 常规 fat AAR | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` | github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar |
| `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`（38,691,998 B，现存 `~/Downloads/`） | **构建的输入** | `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` | 同 release 的 `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` |
| `app/libs/sherpa-onnx-1.13.8-arm64-static.aar`（9,208,854 B，`.gitignore` 排除） | **构建实际用的那份** | `c3505d66b267d2934341b45b11c70292d784b208f9ee544ece6aef10d6bb32b5` | 上一行 `cp` 后 `zip -d … 'jni/armeabi-v7a/*' 'jni/x86/*' 'jni/x86_64/*'`。**只删不改**；剩 `AndroidManifest.xml` + `classes.jar`（238,706 B，类集合与常规 AAR 逐字节同一：122 个 class，`diff` 空）+ `jni/arm64-v8a/libsherpa-onnx-jni.so`（24,169,352 B）。 |

### 「删掉 AAR 自带 ORT、让 Maven 1.29.0 赢」——已证伪（2026-10-20）
先记这条**错**的推理，免得下次再走一遍：以为 C API 向后兼容 ⇒ sherpa 跑在比构建时更新的 ORT 上是安全方向。
装机探针的原文（`adb logcat -s P5TTS`，Redmi 13R 5G）：

```
E P5TTS: kind=aishell3 load failed: java.lang.UnsatisfiedLinkError: dlopen failed:
         cannot locate symbol "OrtGetApiBase" referenced by "…/base.apk!/lib/arm64-v8a/libsherpa-onnx-jni.so"
```

拦路的是 **ELF 符号版本**，不是 API 兼容性。`readelf` 三份库的实际出入：

| 库 | 需要的版本节点（`.gnu.version_r`） | 提供的版本节点（`.gnu.version_d`） |
|---|---|---|
| sherpa fat AAR 的 `libsherpa-onnx-jni.so` | `libonnxruntime.so` → `VERS_1.28.2`（未定义符号写作 `OrtGetApiBase@VERS_1.28.2`） | — |
| Maven 1.29.0 的 `libonnxruntime.so` | — | `VERS_1.29.0`（导出 `OrtGetApiBase@@VERS_1.29.0`） |
| Maven 1.29.0 的 `libonnxruntime4j_jni.so`（OCR 用） | `VERS_1.29.0`（另需 `OrtSessionOptionsAppendExecutionProvider_{CPU,Nnapi}@VERS_1.29.0`） | — |

⇒ 两个消费方钉在**同一个 SONAME** 的**不同版本节点**上，一份 `libonnxruntime.so` 不可能同时满足；
`pickFirst` 或"删一份留一份"怎么选都必然打断另一条路。而**对齐版本也走不通**：Maven 的
`onnxruntime-android` 只有 1.28.0 / 1.29.0 / 1.30.0 / 1.31.0，**没有 1.28.2**（maven-metadata.xml 实测；
`…/1.28.2/…aar` → HTTP 404），而版本节点字符串精确到 patch 位；sherpa 最新发布 v1.13.8 又正是链 1.28.2 的那份。
⇒ 剩下的三条里：改 ORT 版本=无解，自行重编 sherpa=一次完整 NDK 交叉编译（不进成本账），
**用上游已经提供的 static-link 版=零戏法**，故选第三条：OCR 那条已量测的路一个字节不动，
代价是 APK 原生 +24,169,352 B（`unzip -l` 复核：APK 里仍只有一份 `libonnxruntime.so`，32,120,992 B＝Maven 1.29.0）。

## 模型（放 `~/Downloads/`；不入 git、不放 `/tmp`——RAM 盘会被清，人 2026-10-20 明确过）
| 文件 | 下载体积 | sha256 | 解包后目录字节 | 来源 |
|---|---|---|---|---|
| `vits-icefall-zh-aishell3.tar.bz2` | 31,559,701 B | `ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1` | **32,762,508**（不含 `rule.far`）／含 `rule.far` 为 213,479,522 | github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2 |
| `vits-melo-tts-zh_en.tar.bz2` | 167,006,755 B | `e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514` | **191,246,123**（`model.onnx` 170,429,550 + `lexicon.txt` 6,837,671 + `dict/` 13,742,697 + 四个 `.fst`） | github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2 |

- Melo 包里那个 `model.int8.onnx` 只有 **133 B**，内容是 git-lfs 指针（`oid sha256:f085f507…`，真身 53,517,430 B）
  ⇒ 想要 int8 档得单独从 LFS 取，别把指针当模型。包内 `LICENSE` 实测是 **MIT（MyShell.ai）**，README 写明 zh+en 只有**一个女声**。
- AISHELL3 包里**没有 LICENSE 文件** ⇒ R9（权重许可逐项核实）仍未做，核实前不作默认分发档。

> **体积账的两次打脸**：① 提案 §3.2 说 AISHELL3「model.onnx 29 MB（已确认）」只量了模型本身——同包
> `rule.far` = **180,717,014 B ≈ 172 MiB**，整包 ~213 MiB 比它要替换的 Melo 下载包还大；② 真装机后发现
> 小体积那一档的代价在别处：**AISHELL3 是 8 kHz 采样**（Melo 44.1 kHz），33 MB 换来的是电话音质。
> ⇒ 端上到底需不需要 `rule.far` 是**功能问题不是体积问题**（它管数字/日期读法），而读法对不对由**人耳**裁
> （见下方听感待测项），不靠猜。

## 装机实测（2026-10-20，Redmi 13R 5G，`p5_tts` 探针，`am start` 触发、无触摸）
`ruleFsts` = 四个 `.fst`（`phone,date,number,new_heteronym`），`ruleFars` 一律不带，`sid=0`，`speed=1.0`：

| 档 | 线程 | loadMs | sampleRate | 音色 | avgRTF | worstRTF | native 峰值 | release 后 native |
|---|---|---|---|---|---|---|---|---|
| aishell3 | 2 | 2093 | **8000** | 174 | **0.10** | 0.11 | 213.6 MB | 30.6 MB |
| aishell3 | 4 | 2235 | 8000 | 174 | 0.11 | 0.13 | 213.6 MB | 30.4 MB |
| melo | 2 | 7171 | **44100** | 1 | 1.51 | 2.04 | 401.0 MB | 30.4 MB |
| melo | 4 | 7236 | 44100 | 1 | **1.36** | 1.47 | 401.1 MB | 30.4 MB |

- **合成 5/5 + 7/7 全成功**，无一格空跑（`wav` 全部落盘并可 `adb pull` 复核）。
- RTF = 合成墙钟 / 音频时长。aishell3 **0.10 ⇒ 比实时快 10 倍**；melo **1.4~1.5 ⇒ 比实时慢**，且 2→4 线程只从 1.51 降到 1.36
  （那 170 MB 的模型是访存受限，不是核数受限；本机 8 核仅 2 大核）。整屏朗读按 30 句估：aishell3 量级在十秒内，melo 要分钟级。
- `release()` 后 native 堆回到 ~30 MB ⇒ **没泄漏**，模型可随开随关（这条直接支持"按需加载、不做常驻"的实现取向）。
- **换 AAR 后 OCR 通路未退化**（同一 APK、同一次开机，`run_ort` 探针）：`RESULT model=ch_PP-OCRv4_det_infer.onnx input=1x3x448x960 threads=4 loadMs=147 outShape=1x1x448x960 inferMs[min=280 p50=286 avg=283 max=286]` ⇒ static-link 那份 sherpa 与 Maven `libonnxruntime.so` 1.29.0 在同进程里互不干扰（OCR 走 4j_jni，sherpa 走自带静态 ORT）。
- 内存读数的坑：**PSS 量不出加载增量**——`getProcessMemoryInfo().getTotalPss()` 在加载 30 MB 模型后报
  `62.21 MB → 62.21 MB`（delta 0.00），改用 `Debug.getNativeHeapAllocatedSize()` + `/proc/self/statm` 才有响应。
  ⇒ 以后在这台机器上量原生内存别看 PSS 那条。
- 目录权限的坑：模型目录是 `adb shell mkdir` 建的（`drwxrws--- shell:ext_data_rw`），app 侧读成**空目录**
  （探针报 `exists=true files=0 bytes=0`），`chmod 755` 后才可见。⇒ 以后 push 出**子目录**都要补一次 chmod；
  OCR/MT 的模型平铺在 files 根（app 自己建的目录）才一直没撞上这条。
- 触发方式的坑：冷启动一次 `am start` 会把同一 intent 交**两遍**（`onCreate` 后 2.6 s 又走一次 `onNewIntent`），
  而进程已在前台时 `am start` 又**一个回调都不走**（`Warning: Activity not started, intent has been delivered to
  currently running top-most instance`）。⇒ 探针按参数串做 20 s 去重，后续档位用 `am start -f 0x20000000`（SINGLE_TOP）触发。

## 设备侧落点
沿用现行约定：`getExternalFilesDir(null)` 根（与 OCR 的 `ch_PP-OCRv4_*.onnx`、MT 的 `*.gguf` 同一处）。
模型各占一个子目录（`vits-icefall-zh-aishell3/`、`vits-melo-tts-zh_en/`），合成产物落 `files/tts_out/`。

## 听感裁决（2026-10-21，人耳）
机器只能定速度/内存；可懂度由人听，**结论已回**（原话入 `~/.Athena` 台账）：
1. **「aishell3-nf* 无法听清，melo-nf* 能听清」** ⇒ aishell3 不是"低音质快档"，是**不可懂**，整档出局；
   默认档定为 **melo**（RTF 1.36–1.51 / 191 MB / native 峰值 401 MB 这三笔代价照付）。
2. `rule.far` 那一格随 aishell3 归零：melo 目录带四个 `.fst`（`phone/date/number/new_heteronym`，合计
   234,240 B）但**不带 `rule.far`**；探针与生产加载时都自动挂上这四个 `.fst`（生产日志 `ruleFsts=` 已证），
   `_nf` 仅表示"不带 rule.far"。含数字/日期的 `melo-nf_4` 落在"能听清"组里，故端上 footprint =
   **191,246,123 B**（文件字节和），不挂 `rule.far`。
3. 机器指标不能替代可懂度预设档位——这是本轮最大的一条教训（已记项目 pitfall）。

## 生产管线实测（2026-10-21，`TtsEngine` 播放链路，`am start --es tts_speak …`）
无障碍服务当时未连接（`install -r` 后系统未回绑），走进程内引擎、与生产同一个类；声音本身仍归人耳。
```
TTS: 无障碍服务未连接，改用进程内引擎量播放管线
TTS: synth chars=16 synthMs=4128 audioMs=2751 queuedSamples=121344
TTS: synth chars=13 synthMs=3839 audioMs=2368 queuedSamples=225792
TTS: speak done chars=29 chunks=2 samples=225792 wallMs=21963
TTS: engine unloaded (idle/foreground left)        # 完成 60 s 后
RuntimeStats: tts|1|0|21970|21970|21970
```
⇒ AudioTrack 写满并由 drain 等到播完（无 timeout 警告）；29 字 / 5.1 s 音频墙钟 22 s（冷加载 7 s + 两段合成 8 s + 播放 5 s）。
⇒ **RTF>1 的断流在真机上落地了**：第 1 段 2.75 s 音频播完时第 2 段还没合成完（3.84 s），两句之间有约 3.7 s 空隙。
这正是 `finished/tts-engine-replacement.md` §4 记的那笔账——朗读按"一框/一段"交付，整屏朗读不做。
