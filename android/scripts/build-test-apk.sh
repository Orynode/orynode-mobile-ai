#!/usr/bin/env bash
# assembleDebug → android/dist/*.apk（gitignore；可用 GitHub Release 分发）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

EMBED_DIR="app/src/main/assets/embedding"
MODEL="$EMBED_DIR/multilingual-e5-small.tflite"
TOKENIZER="$EMBED_DIR/multilingual-e5-small-tokenizer/unigram.tsv"

if [[ ! -f "$MODEL" || ! -f "$TOKENIZER" ]]; then
  echo "缺少 embedding assets，先运行 ./scripts/prepare-embedding-model.sh" >&2
  exit 1
fi

./gradlew :app:assembleDebug

OUT_DIR="$ROOT/dist"
mkdir -p "$OUT_DIR"
APK_SRC="$(ls -1 app/build/outputs/apk/debug/*.apk | head -1)"
STAMP="$(date +%Y%m%d)"
APK_DST="$OUT_DIR/OrynodeMobileAI-${STAMP}-debug.apk"
cp -f "$APK_SRC" "$APK_DST"
echo "$APK_DST"
