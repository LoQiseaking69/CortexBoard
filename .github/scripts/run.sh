#!/usr/bin/env bash
# Run a command, keep a log, and on failure surface the tail as ::error:: annotations
# (readable from the checks API even when raw logs aren't).
set -o pipefail
log="${RUNNER_TEMP:-/tmp}/step-$$.log"
"$@" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ]; then
  grep -E "error:|e: |FAILED|What went wrong|Exception|fatal|CMake Error|undefined reference" "$log" \
    | grep -v "^\s*at " | head -40 > "$log.err" || true
  tail -60 "$log" >> "$log.err"
  # chunk into annotations (~3500 chars each, max 8)
  python3 - "$log.err" <<'PY'
import sys
data = open(sys.argv[1], errors="replace").read()
chunks = [data[i:i+3500] for i in range(0, len(data), 3500)][:8]
for i, c in enumerate(chunks):
    c = c.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::error title=fail-{i}::{c}")
PY
fi
exit "$rc"
