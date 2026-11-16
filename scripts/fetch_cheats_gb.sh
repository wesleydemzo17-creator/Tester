#!/usr/bin/env bash
# Builds app/src/main/assets/cheats_gb.zip from the Game Boy, Game Boy Color and Game Boy Advance
# cheat files of libretro-database (https://github.com/libretro/libretro-database, CC BY-SA 4.0)
# at a pinned commit. Inside the zip the files sit under gb/, gbc/ and gba/.
set -euo pipefail
COMMIT="${CHEATS_COMMIT:-d34f1331841ee28c14592dc8c12e3a3265dd7e2d}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/cheats-gb-src"
OUT="$ROOT/app/src/main/assets/cheats_gb.zip"

if [ -f "$OUT" ]; then
	echo "Cheat database already present at $OUT"
	exit 0
fi

rm -rf "$WORK"
mkdir -p "$WORK" "$(dirname "$OUT")"
cd "$WORK"
git init -q
git remote add origin https://github.com/libretro/libretro-database.git
git sparse-checkout set --no-cone "/cht/Nintendo - Game Boy/*" "/cht/Nintendo - Game Boy Color/*" "/cht/Nintendo - Game Boy Advance/*"
git fetch -q --depth 1 --filter=blob:none origin "$COMMIT"
git checkout -q FETCH_HEAD

mkdir stage
mv "cht/Nintendo - Game Boy" stage/gb
mv "cht/Nintendo - Game Boy Color" stage/gbc
mv "cht/Nintendo - Game Boy Advance" stage/gba
cd stage
count=$(find gb gbc gba -name '*.cht' | wc -l)
if [ "$count" -lt 1000 ]; then
	echo "Expected thousands of cheat files, found $count" >&2
	exit 1
fi
zip -q -9 -r "$OUT" gb gbc gba -i '*.cht'
echo "Cheat database: $count games -> $OUT ($(du -h "$OUT" | cut -f1))"
