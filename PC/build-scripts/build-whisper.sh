#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SRC_DIR="${PROJECT_DIR}/external/whisper.cpp"
INSTALL_DIR="${PROJECT_DIR}/build/whisper"

if [ ! -d "${SRC_DIR}/ggml/src" ]; then
    echo "ERROR: whisper.cpp submodule not found at ${SRC_DIR}"
    echo "Run: cd ${PROJECT_DIR} && git submodule update --init --recursive"
    exit 1
fi

echo "[whisper] Building static library..."

mkdir -p "${INSTALL_DIR}/lib"
mkdir -p "${INSTALL_DIR}/include"

cd "${SRC_DIR}"

# Build using cmake
cmake -B cmake/build \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DWHISPER_METAL=ON \
    -DWHISPER_BUILD_EXAMPLES=OFF \
    -DWHISPER_BUILD_TESTS=OFF \
    -DCMAKE_OSX_ARCHITECTURES="$(uname -m)" \
    -DCMAKE_C_FLAGS="-O2 -DNDEBUG"

cmake --build cmake/build -j"$(sysctl -n hw.ncpu)" --target whisper

# Copy library
cp cmake/build/src/libwhisper.a "${INSTALL_DIR}/lib/"

# Copy headers
cp include/whisper.h "${INSTALL_DIR}/include/"

echo "[whisper] Done. Library: ${INSTALL_DIR}/lib/libwhisper.a"
echo "[whisper] Headers: ${INSTALL_DIR}/include/whisper.h"
