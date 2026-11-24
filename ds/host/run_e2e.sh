#!/bin/sh
# Roda o teste ponta a ponta: servidor Kotlin (o mesmo do app) <-> simulador do DS.
# Precisa de: cc, kotlinc (https://github.com/JetBrains/kotlin/releases) e java.
# Uso: sh ds/host/run_e2e.sh   (a partir da raiz do projeto)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
make -C "$ROOT/ds/host"
A="$ROOT/android/app/src"
kotlinc "$ROOT/ds/host/JunitStub.kt" "$A"/main/java/br/pokebank/core/*.kt "$A"/main/java/br/pokebank/net/*.kt \
  "$A"/test/java/br/pokebank/CoreTest.kt "$ROOT/ds/host/E2E.kt" -d "$OUT/classes"
STD=$(dirname "$(command -v kotlinc)")/../lib/kotlin-stdlib.jar
cd "$ROOT/android/app"
for sc in ok cancel fail; do java -cp "$OUT/classes:$STD" E2EKt "$ROOT/ds/host/pokebank_host" $sc | tail -1; done
