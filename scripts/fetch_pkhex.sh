#!/usr/bin/env bash
# Downloads the official PKHeX source (kwsch/PKHeX) at a pinned commit.
set -euo pipefail
COMMIT="${PKHEX_COMMIT:-c758bac1082878eb2e439be1cd3b5cdfc26b5e04}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/pkhex-upstream"

if [ -f "$DEST/PKHeX.Core/PKHeX.Core.csproj" ]; then
	echo "PKHeX source already present at $DEST"
	exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cd "$DEST"
git init -q
git remote add origin https://github.com/kwsch/PKHeX.git
git fetch -q --depth 1 origin "$COMMIT"
git checkout -q FETCH_HEAD
echo "PKHeX $COMMIT ready at $DEST"
