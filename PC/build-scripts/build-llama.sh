#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SRC_DIR="${PROJECT_DIR}/external/llama.cpp"
INSTALL_DIR="${PROJECT_DIR}/build/llama"

if [ ! -d "${SRC_DIR}/src" ]; then
    echo "ERROR: llama.cpp submodule not found at ${SRC_DIR}"
    echo "Run: cd ${PROJECT_DIR} && git submodule update --init --recursive"
    exit 1
fi

echo "[llama] Building static library..."

mkdir -p "${INSTALL_DIR}/lib"
mkdir -p "${INSTALL_DIR}/include"

cd "${SRC_DIR}"

cmake -B build \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_METAL=ON \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DCMAKE_OSX_ARCHITECTURES="$(uname -m)" \
    -DCMAKE_C_FLAGS="-O2 -DNDEBUG"

cmake --build build -j"$(sysctl -n hw.ncpu)" --target llama

# Copy library
cp build/src/libllama.a "${INSTALL_DIR}/lib/"

# Copy headers
cp include/llama.h "${INSTALL_DIR}/include/"

# Copy Metal shader if available
if [ -f "build/ggml/src/ggml-metal/libggml-metal.a" ]; then
    echo "[llama] Metal shader library found"
fi

echo "[llama] Done. Library: ${INSTALL_DIR}/lib/libllama.a"
echo "[llama] Headers: ${INSTALL_DIR}/include/llama.h"
