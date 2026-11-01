#!/usr/bin/env bash
set -eu

cd -- "$(dirname -- "$0")"

mkdir -p out
javac -encoding UTF-8 -d out src/*.java

exec java -cp out Main "$@"