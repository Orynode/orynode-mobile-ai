#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="$ROOT/.embedding-tools"
OUTPUT="$ROOT/Sources/Resources/Embedding"

if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
fi

"$VENV/bin/python" -m pip install --upgrade pip
"$VENV/bin/python" -m pip install \
  "torch==2.7.0" \
  "transformers==4.48.3" \
  "coremltools==9.0" \
  "sentencepiece==0.2.1" \
  "safetensors==0.6.2"

mkdir -p "$OUTPUT"
"$VENV/bin/python" "$ROOT/scripts/convert-multilingual-e5-small.py" \
  --output "$OUTPUT"

echo "Prepared bundled embedding assets in $OUTPUT"
