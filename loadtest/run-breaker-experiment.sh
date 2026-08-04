#!/usr/bin/env bash
#
# THE BREAKER EXPERIMENT — A-B-B-A at a fixed offered rate past the knee.
#
# Tests the shedding hypothesis from PHASE_0_SMOKE_FINDINGS.md §IX: that a
# deliberate shedder at the pool boundary should RAISE goodput and CUT cost
# at identical offered load, because a shed request bills single-digit ms
# instead of ~15,000 ms of borrow-wait.
#
#   A = /db          control, unguarded
#   B = /db-breaker  treatment, resilience4j circuit breaker
#
# ── Why an adaptive recovery gate and not a fixed sleep ────────────────────
#
# §X measured this system as METASTABLE: ten minutes after a trigger was
# withdrawn it still delivered 46% of baseline goodput, and one minute after
# withdrawal only 12.9%. An arm that starts while the previous arm's collapse
# is still draining does not measure its own treatment — it measures the
# leftover. With A-B-B-A ordering that error would not even cancel; it would
# systematically punish whichever arm follows the worst collapse.
#
# So between arms this script does not sleep for a guessed interval. It goes
# quiet, then PROBES at a rate the system is known to serve cleanly, and
# refuses to start the next arm until the probe comes back healthy. The gate
# is the measurement deciding when to proceed, not the clock.
#
# ── Why A-B-B-A ───────────────────────────────────────────────────────────
#
# Aurora keeps warming (§VII), so there is a monotonic drift component across
# the session. A-B and B-A both confound treatment with position; A-B-B-A
# balances position across treatments, so a linear drift cancels in the
# arm-pair means rather than loading onto one treatment.
#
# Usage:
#   ./run-breaker-experiment.sh <api-url> [rate] [duration]

set -euo pipefail

API_URL="${1:?api url required}"
RATE="${2:-1600}"
DURATION="${3:-3m}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_DIR}"

# Rate the system serves cleanly, established by this session's baseline run
# (400 rps -> 48,001/48,001 served, p50 82.8 ms). Recovery is defined as
# "can it do that again", not as "has enough time passed".
PROBE_RATE=400
PROBE_DURATION=45s
PROBE_MIN_SERVED_PCT=98
MAX_PROBE_ATTEMPTS=5
QUIET_SECONDS=180
EXTRA_QUIET_SECONDS=120

k6run () {
  local arm="$1" endpoint="$2" rate="$3" duration="$4"
  echo "##### ${arm}  endpoint=${endpoint}  rate=${rate}  duration=${duration}  START=$(date +%s)"
  docker compose --profile loadtest run --rm \
    -e API_URL="${API_URL}" -e ENDPOINT="${endpoint}" \
    -e RATE="${rate}" -e DURATION="${duration}" -e ARM="${arm}" \
    k6 run /loadtest/db-breaker-arm.js 2>&1 | sed -n '/^{/,/^}/p'
  echo "##### ${arm} END=$(date +%s)"
}

served_pct_of () {
  python3 -c "
import json, sys
print(json.load(open(sys.argv[1]))['served_pct'])
" "loadtest/results/$1.json"
}

recover () {
  local label="$1"
  echo
  echo "===== RECOVERY GATE (${label}) ====="
  sleep "${QUIET_SECONDS}"
  local attempt=1
  while [ "${attempt}" -le "${MAX_PROBE_ATTEMPTS}" ]; do
    k6run "probe-${label}-${attempt}" /db "${PROBE_RATE}" "${PROBE_DURATION}" >/dev/null 2>&1 || true
    local pct
    pct=$(served_pct_of "probe-${label}-${attempt}")
    echo "  probe ${attempt}: served ${pct}% (need >= ${PROBE_MIN_SERVED_PCT}%)"
    # Bash cannot compare floats; python returns the verdict.
    if python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) >= ${PROBE_MIN_SERVED_PCT} else 1)" "${pct}"; then
      echo "  RECOVERED after ${attempt} probe(s)"
      echo "===== END RECOVERY GATE ====="
      echo
      return 0
    fi
    attempt=$((attempt + 1))
    sleep "${EXTRA_QUIET_SECONDS}"
  done
  # Do not silently proceed from an ungated state — a reader must be able to
  # see that this arm started dirty, or the A-B-B-A balance is a fiction.
  echo "  !! NOT RECOVERED after ${MAX_PROBE_ATTEMPTS} probes — arm starts DEGRADED, flag in write-up"
  echo "===== END RECOVERY GATE ====="
  echo
  return 0
}

echo "BREAKER EXPERIMENT  rate=${RATE}  duration=${DURATION}  api=${API_URL}"
echo "reservation: $(aws lambda get-function-concurrency --function-name lra-database-resilience --query ReservedConcurrentExecutions --output text 2>/dev/null || echo unknown)"
echo

k6run A1 /db          "${RATE}" "${DURATION}"; recover after-A1
k6run B1 /db-breaker  "${RATE}" "${DURATION}"; recover after-B1
k6run B2 /db-breaker  "${RATE}" "${DURATION}"; recover after-B2
k6run A2 /db          "${RATE}" "${DURATION}"

echo
echo "ALL ARMS COMPLETE"
