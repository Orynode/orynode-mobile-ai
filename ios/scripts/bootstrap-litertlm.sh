#!/usr/bin/env bash
# Fetch pinned LiteRT-LM sources into ios/.tools/ (gitignored).
# Pin must match the binary release URLs inside that checkout's Package.swift.
# Sources are stored WITHOUT a nested .git so IDEs do not treat LiteRT-LM as a second repo.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_DIR="$ROOT/.tools"
LITERT_DIR="$TOOLS_DIR/LiteRT-LM"
LITERT_REPO="${LITERT_REPO:-https://github.com/google-ai-edge/LiteRT-LM.git}"
# Keep in sync with ios/docs/development.md and Package.swift binary URLs.
LITERT_TAG="${LITERT_TAG:-v0.15.0}"

mkdir -p "$TOOLS_DIR"

echo "Fetching LiteRT-LM $LITERT_TAG → $LITERT_DIR"
rm -rf "$LITERT_DIR"
git clone --depth 1 --branch "$LITERT_TAG" "$LITERT_REPO" "$LITERT_DIR"
# Drop nested git metadata — this tree must never appear as a workspace repository.
rm -rf "$LITERT_DIR/.git"

# Record pin for humans (not a git repo).
printf '%s\n' "$LITERT_TAG" >"$LITERT_DIR/.orynode-litert-pin"

echo "LiteRT-LM ready: $LITERT_TAG (no nested .git)"
echo "Next: cd ios && xcodegen generate && open OrynodeMobileAI.xcodeproj"
