#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/app/src/main/assets/embedding"
VENV="${ROOT}/.embedding-tools/venv"

mkdir -p "${ROOT}/.embedding-tools"
if [[ ! -d "$VENV" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -U pip
  "$VENV/bin/pip" install \
    "torch" \
    "transformers" \
    "onnx" \
    "onnxscript" \
    "onnx2tf" \
    "onnx-graphsurgeon" \
    "sng4onnx" \
    "onnxsim" \
    "psutil" \
    "tf_keras" \
    "tensorflow"
fi

"$VENV/bin/python" "$ROOT/scripts/convert-multilingual-e5-small-tflite.py" --output "$OUT"
echo "Embedding assets ready under $OUT"
echo "Remember: *.tflite and tokenizer binaries are gitignored; Release must bundle them."
