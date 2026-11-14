#!/usr/bin/env bash
# Downloads the official DeSmuME source (TASEmulators/desmume) at a pinned commit.
set -euo pipefail
COMMIT="${DESMUME_COMMIT:-40eafedde5548e3597ea456f1971f1bed3e9c8e8}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/desmume-upstream"

if [ -f "$DEST/desmume/src/NDSSystem.cpp" ]; then
	echo "DeSmuME source already present at $DEST"
	exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cd "$DEST"
git init -q
git remote add origin https://github.com/TASEmulators/desmume.git
git fetch -q --depth 1 origin "$COMMIT"
git checkout -q FETCH_HEAD
echo "DeSmuME $COMMIT ready at $DEST"
