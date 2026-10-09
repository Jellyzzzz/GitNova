#!/bin/sh
set -eu
: "${GITNOVA_WORKLINE_ID:?missing GITNOVA_WORKLINE_ID}"
: "${GITNOVA_SESSION_ID:?missing session id}"
: "${GITNOVA_RUNNER_EPOCH:?missing runner epoch}"
: "${GITNOVA_WORKER_TOKEN:?missing worker token}"
: "${GITNOVA_MODEL_ENDPOINT:?missing model endpoint}"
: "${GITNOVA_MODEL_TOKEN:?missing model token}"
: "${GITNOVA_PLATFORM_ENDPOINT:?missing platform capability endpoint}"
: "${GITNOVA_PLATFORM_TOKEN:?missing scoped platform capability token}"
: "${GITNOVA_RUNTIME_CONFIG_JSON:?missing frozen runtime config}"
root=${GITNOVA_SESSION_ROOT:-/session}
[ -d "$root" ] && [ -w "$root" ] || { echo 'session root not writable' >&2; exit 78; }
mkdir -p "$root/worktree" "$root/state" "$root/exports" "$root/bootstrap" "$root/baseline"
exec java -jar /opt/gitnova/agent-worker.jar
