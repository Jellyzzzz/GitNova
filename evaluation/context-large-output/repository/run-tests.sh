#!/bin/sh
set -eu
cd "$(dirname "$0")"
# Builds stay outside the repository, so test execution does not change its file tree.
classes=$(mktemp -d /tmp/pricing-test.XXXXXX)
javac -d "$classes" src/main/java/pricing/*.java src/test/java/pricing/BatchRegression.java
java -cp "$classes" pricing.BatchRegression fixtures/orders.csv
