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
