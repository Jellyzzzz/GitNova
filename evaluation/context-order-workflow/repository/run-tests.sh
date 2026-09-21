#!/bin/sh
set -eu
build=$(mktemp -d /tmp/orderflow-build.XXXXXX)
trap 'rm -rf "$build"' EXIT HUP INT TERM
find src/main/java src/test/java -name '*.java' -print | sort > "$build/sources"
javac -encoding UTF-8 -d "$build/classes" @"$build/sources"
java -cp "$build/classes" orderflow.AllTests "$@"
