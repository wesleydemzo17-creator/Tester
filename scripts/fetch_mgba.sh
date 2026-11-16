#!/usr/bin/env bash
# Downloads the official mGBA source (mgba-emu/mgba) at a pinned commit.
set -euo pipefail
COMMIT="${MGBA_COMMIT:-d58ea8a86f30598fbf3958585de3d33028535072}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/mgba-upstream"

if [ -f "$DEST/CMakeLists.txt" ]; then
	echo "mGBA source already present at $DEST"
	exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cd "$DEST"
git init -q
git remote add origin https://github.com/mgba-emu/mgba.git
git fetch -q --depth 1 origin "$COMMIT"
git checkout -q FETCH_HEAD
echo "mGBA $COMMIT ready at $DEST"
