#!/usr/bin/env bash
set -euo pipefail

if [[ "${CONFIGURATION:-Debug}" != "Release" ]]; then
  exit 0
fi

ROOT="${SRCROOT:?SRCROOT is required}"
ASSETS="$ROOT/Sources/Resources/Embedding"
MODEL="$ASSETS/multilingual-e5-small.mlmodelc"
TOKENIZER="$ASSETS/multilingual-e5-small-tokenizer"

if [[ ! -d "$MODEL" || ! -f "$TOKENIZER/tokenizer.json" || ! -f "$TOKENIZER/tokenizer_config.json" ]]; then
  echo "error: Release requires bundled multilingual-e5-small Core ML and tokenizer assets."
  echo "error: Run ios/scripts/prepare-embedding-model.sh before archiving."
  exit 1
fi
