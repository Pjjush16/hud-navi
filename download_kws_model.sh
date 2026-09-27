#!/bin/bash
# 下载 sherpa-onnx 中文 KWS 模型并放入 Android assets
# 运行此脚本后将模型文件准备就绪，然后编译 APK 即可

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets/kws"
MODEL_NAME="sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"

mkdir -p "$ASSETS_DIR"

# 模型文件列表
FILES=(
    "tokens.txt"
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
)

# 检查是否已存在
ALL_EXIST=true
for f in "${FILES[@]}"; do
    if [ ! -f "$ASSETS_DIR/$f" ]; then
        ALL_EXIST=false
        break
    fi
done

if [ "$ALL_EXIST" = true ]; then
    echo "✓ 所有模型文件已存在: $ASSETS_DIR"
    echo "  总计: $(du -sh "$ASSETS_DIR" | cut -f1)"
    exit 0
fi

echo "正在下载中文关键词识别模型..."
echo "模型: $MODEL_NAME"
echo "目标: $ASSETS_DIR"
echo ""

# 方式1: GitHub Release
GH_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL_NAME"
ARCHIVE="/tmp/${MODEL_NAME}.tar.bz2"

if [ ! -f "$ARCHIVE" ]; then
    echo "下载 $ARCHIVE ..."
    wget -q --show-progress "$GH_BASE.tar.bz2" -O "$ARCHIVE" || {
        echo "GitHub 下载失败，尝试 HuggingFace 镜像..."
        HF_BASE="https://hf-mirror.com/csukuangfj/$MODEL_NAME/resolve/main"
        for f in "${FILES[@]}"; do
            echo "  下载 $f ..."
            wget -q --show-progress "$HF_BASE/$f" -O "$ASSETS_DIR/$f" || {
                echo "  ✗ 下载失败: $f"
            }
        done
        echo ""
        echo "检查下载结果:"
        ls -la "$ASSETS_DIR"
        exit 0
    }
fi

echo "解压模型文件..."
TMPDIR=$(mktemp -d)
tar xjf "$ARCHIVE" -C "$TMPDIR"

for f in "${FILES[@]}"; do
    src="$TMPDIR/$MODEL_NAME/$f"
    if [ -f "$src" ]; then
        cp "$src" "$ASSETS_DIR/$f"
        echo "  ✓ $f ($(stat -c%s "$ASSETS_DIR/$f") bytes)"
    else
        echo "  ✗ 未找到: $src"
    fi
done

rm -rf "$TMPDIR"

echo ""
echo "模型文件准备完成!"
echo "目录: $ASSETS_DIR"
echo "总计: $(du -sh "$ASSETS_DIR" | cut -f1)"
echo ""
echo "下一步: 编译 APK"
echo "  ./gradlew assembleDebug"
echo "  或推送到 GitHub 让 Actions 自动编译"
