#!/usr/bin/env bash
# Builds app/src/main/assets/gamedb.tsv (game code / CRC -> No-Intro name) from the No-Intro .dat
# files of libretro-database (https://github.com/libretro/libretro-database, CC BY-SA 4.0).
set -euo pipefail
COMMIT="${GAMEDB_COMMIT:-d34f1331841ee28c14592dc8c12e3a3265dd7e2d}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/gamedb-src"
OUT="$ROOT/app/src/main/assets/gamedb.tsv"

if [ -f "$OUT" ]; then
	echo "Game database already present at $OUT"
	exit 0
fi

rm -rf "$WORK"
mkdir -p "$WORK" "$(dirname "$OUT")"
cd "$WORK"
git init -q
git remote add origin https://github.com/libretro/libretro-database.git
git sparse-checkout set --no-cone "/metadat/no-intro/Nintendo - Nintendo DS.dat" \
	"/metadat/no-intro/Nintendo - Game Boy Advance.dat" \
	"/metadat/no-intro/Nintendo - Game Boy Color.dat" \
	"/metadat/no-intro/Nintendo - Game Boy.dat"
git fetch -q --depth 1 --filter=blob:none origin "$COMMIT"
git checkout -q FETCH_HEAD

D="$WORK/metadat/no-intro"
python3 -I "$ROOT/tools/make_gamedb.py" "$OUT" \
	"nds=$D/Nintendo - Nintendo DS.dat" \
	"gba=$D/Nintendo - Game Boy Advance.dat" \
	"gbc=$D/Nintendo - Game Boy Color.dat" \
	"gb=$D/Nintendo - Game Boy.dat"
