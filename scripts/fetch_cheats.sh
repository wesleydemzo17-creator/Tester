#!/usr/bin/env bash
# Builds app/src/main/assets/cheats.zip from the Nintendo DS cheat files of libretro-database
# (https://github.com/libretro/libretro-database, CC BY-SA 4.0) at a pinned commit.
set -euo pipefail
COMMIT="${CHEATS_COMMIT:-d34f1331841ee28c14592dc8c12e3a3265dd7e2d}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/cheats-src"
OUT="$ROOT/app/src/main/assets/cheats.zip"
DIR="cht/Nintendo - Nintendo DS"

if [ -f "$OUT" ]; then
	echo "Cheat database already present at $OUT"
	exit 0
fi

rm -rf "$WORK"
mkdir -p "$WORK" "$(dirname "$OUT")"
cd "$WORK"
git init -q
git remote add origin https://github.com/libretro/libretro-database.git
git sparse-checkout set --no-cone "/$DIR/*"
git fetch -q --depth 1 --filter=blob:none origin "$COMMIT"
git checkout -q FETCH_HEAD
cd "$DIR"
count=$(ls -1 *.cht | wc -l)
if [ "$count" -lt 1000 ]; then
	echo "Expected thousands of cheat files, found $count" >&2
	exit 1
fi
zip -q -9 "$OUT" *.cht
echo "Cheat database: $count games -> $OUT ($(du -h "$OUT" | cut -f1))"
