#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/bootstrap.py
if [[ $# -eq 0 ]]; then
  set -- :patches:buildAndroid :patches:checkStringResources :patches:test :extensions:youtube:testDebugUnitTest
fi
exec ./gradlew -Pgpr.user=source-build -Pgpr.key=unused --no-daemon --console=plain "$@"
