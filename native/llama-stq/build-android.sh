#!/usr/bin/env bash
# 悬浮助手 · P2-S：把 STQ 特化 llama.cpp 交叉编译到 Android/arm64-v8a。
# 锁定源：llama.cpp PR #22836 head 1e411d8f5a1e23525fa3265dfb4bd76265465397 (sjl623:STQ_0)。
# 该 PR 引入 GGML_TYPE_STQ1_0，但出厂 1.25-bit GGUF 的量化张量 raw type=42（AngelSlim 内部构建里 STQ 落在 42）,
# PR/主线默认 STQ1_0=43、Q2_0=42，读 42 会按 Q2_0 的行尺寸静默误解析 offset。
# 本脚本先套 stq-renumber.patch（STQ1_0<->Q2_0：42<->43），再交叉编译，最后校验产物真含 STQ NEON 核。
set -euo pipefail

: "${ANDROID_NDK:?需要 ANDROID_NDK（含 toolchains/llvm/prebuilt + build/cmake/android.toolchain.cmake）}"
LLAMA_SRC="${1:-$HOME/clone/llama-stq}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$LLAMA_SRC/build-android"
PR_HEAD="1e411d8f5a1e23525fa3265dfb4bd76265465397"

echo "[1/4] 套重编号补丁（幂等：已套则跳过）"
if grep -q 'GGML_TYPE_STQ1_0  = 42' "$LLAMA_SRC/ggml/include/ggml.h"; then
  echo "  已应用（STQ1_0=42）"
else
  ( cd "$LLAMA_SRC" && git apply "$HERE/stq-renumber.patch" )
  echo "  已应用补丁"
fi

echo "[2/4] 构建时校验：枚举值必须 STQ1_0=42 / Q2_0=43 / COUNT=44"
"$HERE/verify-stq.sh" source "$LLAMA_SRC/ggml/include/ggml.h"

echo "[3/4] 配置 + 交叉编译（armv8.2-a：dotprod 由 -march 保证，避开 host 无法 run 的 check_cxx_source_runs）"
cmake -G Ninja -B "$BUILD" -S "$LLAMA_SRC" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-35 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF \
  -DGGML_CPU_ARM_ARCH=armv8.2-a \
  -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TESTS=OFF
# RAM 紧时降到 -j1；libllama 目标只链引擎，不带 server/UI
cmake --build "$BUILD" --target llama -j"${JOBS:-2}"

echo "[4/4] 构建时校验：产物 arm64 .so 必须携带 STQ NEON 核符号"
"$HERE/verify-stq.sh" so "$BUILD/bin/libggml-cpu.so"
echo "OK: $BUILD/bin/libllama.so (PR head $PR_HEAD + 重编号)"
