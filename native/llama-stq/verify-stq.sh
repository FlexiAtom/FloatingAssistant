#!/usr/bin/env bash
# 构建时校验 STQ 特化是否到位。两种模式：
#   verify-stq.sh source <ggml.h>            # 编译前：枚举值必须 STQ1_0=42 / Q2_0=43 / COUNT=44
#   verify-stq.sh so <libggml-cpu.so>        # 编译后：必须是 aarch64 ELF 且含 NEON ggml_vec_dot_stq1_0_q8_K
set -euo pipefail
NM="${NM:-$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm}"
READELF="${READELF:-$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf}"
mode="${1:?用法: verify-stq.sh source|so <路径>}"
target="${2:?缺少目标文件}"

if [ "$mode" = source ]; then
  grep -q 'GGML_TYPE_STQ1_0  = 42' "$target" || { echo "FAIL: STQ1_0 != 42"; exit 1; }
  grep -q 'GGML_TYPE_Q2_0    = 43' "$target" || { echo "FAIL: Q2_0 != 43"; exit 1; }
  grep -q 'GGML_TYPE_COUNT   = 44' "$target" || { echo "FAIL: COUNT != 44"; exit 1; }
  echo "PASS source: STQ1_0=42 / Q2_0=43 / COUNT=44"
elif [ "$mode" = so ]; then
  "$READELF" -h "$target" | grep -qE 'Machine:.*AArch64' || { echo "FAIL: 非 aarch64 ELF"; exit 1; }
  # NEON 版（非 _generic）必须定义为已链接符号（T），证 dotprod 核进来了
  "$NM" -C "$target" 2>/dev/null | grep -qE ' T ggml_vec_dot_stq1_0_q8_K$' \
    || { echo "FAIL: 缺 NEON ggml_vec_dot_stq1_0_q8_K"; exit 1; }
  echo "PASS so: aarch64 + NEON ggml_vec_dot_stq1_0_q8_K 已在 $target"
else
  echo "未知模式: $mode"; exit 2
fi
