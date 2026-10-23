#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of FloatingAssistant.
#
# Copyright (C) 2026 FlexiAtom
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

# 构建时校验 STQ 特化是否到位。三种模式：
#   verify-stq.sh source <ggml.h>            # 编译前：枚举值必须 STQ1_0=42 / Q2_0=43 / COUNT=44
#   verify-stq.sh so <libggml-cpu.so>        # 编译后：必须是 aarch64 ELF 且含 NEON ggml_vec_dot_stq1_0_q8_K
#   verify-stq.sh jni <libmtjni.so>          # JNI 桥：aarch64 ELF + 导出 native* 符号 + NEEDED 引擎库
set -euo pipefail
NM="${NM:-$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm}"
READELF="${READELF:-$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf}"
mode="${1:?用法: verify-stq.sh source|so|jni <路径>}"
target="${2:?缺少目标文件}"

# readelf -h 直连 grep -q 同样有 SIGPIPE 误判风险（Machine 行命中即关管道），先取进变量再判。
aarch64_ok() {
  local hdr; hdr="$("$READELF" -h "$1" 2>/dev/null || true)"
  grep -qE 'Machine:.*AArch64' <<< "$hdr"
}

if [ "$mode" = source ]; then
  grep -q 'GGML_TYPE_STQ1_0  = 42' "$target" || { echo "FAIL: STQ1_0 != 42"; exit 1; }
  grep -q 'GGML_TYPE_Q2_0    = 43' "$target" || { echo "FAIL: Q2_0 != 43"; exit 1; }
  grep -q 'GGML_TYPE_COUNT   = 44' "$target" || { echo "FAIL: COUNT != 44"; exit 1; }
  echo "PASS source: STQ1_0=42 / Q2_0=43 / COUNT=44"
elif [ "$mode" = so ]; then
  aarch64_ok "$target" || { echo "FAIL: 非 aarch64 ELF"; exit 1; }
  # NEON 版（非 _generic）必须定义为已链接符号（T），证 dotprod 核进来了
  # 先整体取符号表进变量再 grep——nm 全量输出直连 grep -q 会因提前关管道触发 SIGPIPE（pipefail 下误判 FAIL）
  SYMS="$("$NM" -C "$target" 2>/dev/null || true)"
  grep -qE ' T ggml_vec_dot_stq1_0_q8_K$' <<< "$SYMS" \
    || { echo "FAIL: 缺 NEON ggml_vec_dot_stq1_0_q8_K"; exit 1; }
  echo "PASS so: aarch64 + NEON ggml_vec_dot_stq1_0_q8_K 已在 $target"
elif [ "$mode" = jni ]; then
  aarch64_ok "$target" || { echo "FAIL: 非 aarch64 ELF"; exit 1; }
  # 先整体取动态符号表进变量，再逐项 grep——避免 grep -q 提前关管道触发 nm SIGPIPE（pipefail 下会误判 FAIL）
  SYMS="$("$NM" -D --defined-only "$target" 2>/dev/null || true)"
  grep -q 'Java_com_flexiatom_floatingassistant_mt_LlamaMtEngine_nativeInit' <<< "$SYMS" \
    || { echo "FAIL: 缺 nativeInit 导出"; exit 1; }
  grep -q 'Java_com_flexiatom_floatingassistant_mt_LlamaMtEngine_nativeTranslate' <<< "$SYMS" \
    || { echo "FAIL: 缺 nativeTranslate 导出"; exit 1; }
  grep -q 'Java_com_flexiatom_floatingassistant_mt_LlamaMtEngine_nativeRelease' <<< "$SYMS" \
    || { echo "FAIL: 缺 nativeRelease 导出"; exit 1; }
  NEEDS="$("$READELF" -d "$target" 2>/dev/null || true)"
  grep -q 'Shared library: \[libllama\.so\]' <<< "$NEEDS" \
    || { echo "FAIL: 未 NEEDED libllama.so"; exit 1; }
  # 提示词已移到 Java 侧（MtPrompt），原生里**不该再有任何指令字面量**。
  # 这道检查不是装饰：nativeTranslate 的 JNI 签名（JLjava/lang/String;)[B 在改造前后完全相同，
  # 一份旧的 libmtjni.so 能照常链接、照常跑，只是把整条官方指令再冠一遍 "Translate into English: "
  # ——不崩，只是译文悄悄坏掉。故按"不该出现的字符串"当场拦。
  if grep -a -q 'Translate into English' "$target"; then
    echo "FAIL: libmtjni.so 仍含已退役的硬编提示词（陈旧 .so 混入：签名相同不会报错，只会悄悄译坏）"
    exit 1
  fi
  echo "PASS jni: aarch64 + native* 导出 + NEEDED libllama + 无退役硬编提示词，已在 $target"
else
  echo "未知模式: $mode"; exit 2
fi
