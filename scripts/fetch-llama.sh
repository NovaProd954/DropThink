#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
REF="$(tr -d '[:space:]' < llama.cpp.version)"
DEST=app/src/main/cpp/llama.cpp
if [ -f "$DEST/.fetched-$REF" ]; then echo "llama.cpp $REF already present"; exit 0; fi
rm -rf "$DEST"
git clone --depth 1 --branch "$REF" https://github.com/ggml-org/llama.cpp "$DEST"
rm -rf "$DEST/.git"
touch "$DEST/.fetched-$REF"
