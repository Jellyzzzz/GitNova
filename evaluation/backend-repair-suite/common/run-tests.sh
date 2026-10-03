#!/bin/sh
set -eu
build_dir=$(mktemp -d /tmp/backend-repair-build.XXXXXX)
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
find src/main/java src/test/java -name '*.java' -print | sort > "$build_dir/sources"
javac -encoding UTF-8 -d "$build_dir/classes" @"$build_dir/sources"
java -cp "$build_dir/classes" @PACKAGE@.PublicChecks
if [ -f src/test/java/@PACKAGE@/RegressionChecks.java ]; then
    java -cp "$build_dir/classes" @PACKAGE@.RegressionChecks
fi
