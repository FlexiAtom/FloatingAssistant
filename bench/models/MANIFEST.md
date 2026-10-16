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

