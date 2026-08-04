#!/usr/bin/env bash
#
# Pull the billed-duration record for one arm of the breaker experiment
# straight from the Lambda REPORT lines, plus the database-side signals
# over the same window.
#
# Why REPORT lines rather than the client's view: k6 measures how long a
# response took to come back, which is a PROXY for what AWS bills. PHASE_0
# §XI records two separate errors in this campaign that came from putting a
# confident number on a proxy (a trace ID in a log line taken for a stored
# trace; a connection metric read at the wrong dimension scope). @billedDuration
# is the quantity AWS actually charges for, so the cost claim is built on
# that and nothing else.
#
# GB-seconds = billed_seconds x (memorySize / 1024). The function is
# provisioned at 1024 MB, so the multiplier is exactly 1 and billed seconds
# and GB-seconds coincide — stated explicitly rather than left implicit,
# because it stops being true the moment someone changes memorySize.
#
# Usage:
#   ./analyze-arm.sh <arm-name> <start-epoch-seconds> <end-epoch-seconds>

set -euo pipefail

ARM="${1:?arm name required}"
START="${2:?start epoch seconds required}"
END="${3:?end epoch seconds required}"

PROFILE="${AWS_PROFILE:-lambda-resilience}"
REGION="${AWS_REGION:-us-east-1}"
LOG_GROUP="/aws/lambda/lra-database-resilience"
FUNCTION="lra-database-resilience"
DB_INSTANCE="lradatabaseresiliencestack-aurorawriterdc5bc759-nosdp3bbcidl"
PROXY_NAME="lra-rds-proxy"

echo "=== ${ARM}  [$(date -r "${START}" -u '+%H:%M:%SZ') → $(date -r "${END}" -u '+%H:%M:%SZ')] ==="

# ── Billed duration, the authoritative cost quantity ────────────────────
QUERY_ID=$(aws logs start-query \
  --log-group-name "${LOG_GROUP}" \
  --start-time "${START}" --end-time "${END}" \
  --query-string 'filter @type = "REPORT"
    | stats count(*) as invocations,
            sum(@billedDuration)/1000 as billed_seconds,
            avg(@billedDuration) as avg_billed_ms,
            pct(@billedDuration, 50) as p50_billed_ms,
            pct(@billedDuration, 95) as p95_billed_ms,
            max(@billedDuration) as max_billed_ms,
            avg(@maxMemoryUsed)/1000000 as avg_mem_mb' \
  --profile "${PROFILE}" --region "${REGION}" \
  --query queryId --output text)

# Poll rather than sleep-and-hope: Insights returns Running until it is done,
# and reading a Running result silently yields partial numbers.
while :; do
  RESULT=$(aws logs get-query-results --query-id "${QUERY_ID}" \
    --profile "${PROFILE}" --region "${REGION}" --output json)
  STATUS=$(echo "${RESULT}" | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])")
  [ "${STATUS}" = "Running" ] || [ "${STATUS}" = "Scheduled" ] || break
  sleep 2
done

# Quoting note: this stays a single-quoted `python3 -c` because the block
# must read the piped JSON on stdin — a heredoc would occupy stdin itself and
# leave json.load with nothing to read. That means NO single quotes may appear
# anywhere inside, so every value is pulled into a local before the f-strings
# rather than subscripted inside them.
echo "${RESULT}" | python3 -c '
import json, sys
r = json.load(sys.stdin)
if r["status"] != "Complete":
    print("  query status:", r["status"]); sys.exit(0)
if not r["results"]:
    print("  no REPORT lines in window"); sys.exit(0)
row = {f["field"]: f["value"] for f in r["results"][0]}
def g(k):
    return float(row.get(k) or 0)
billed = g("billed_seconds")
inv = int(g("invocations"))
avg_ms = g("avg_billed_ms")
p50_ms = g("p50_billed_ms")
p95_ms = g("p95_billed_ms")
max_ms = g("max_billed_ms")
mem_mb = g("avg_mem_mb")
# memorySize is 1024 MB => GB-seconds == billed seconds. Kept as an explicit
# multiplier so the arithmetic breaks loudly if the function is resized.
gb_s = billed * (1024 / 1024)
usd = gb_s * 0.0000166667
per_inv = (gb_s / inv * 1000) if inv else 0
print(f"  invocations      {inv:,}")
print(f"  billed seconds   {billed:,.1f}")
print(f"  GB-seconds       {gb_s:,.1f}")
print(f"  $ duration       {usd:,.4f} USD  (list price, pre free tier)")
print(f"  avg billed       {avg_ms:,.1f} ms")
print(f"  p50 / p95        {p50_ms:,.0f} / {p95_ms:,.0f} ms")
print(f"  max billed       {max_ms:,.0f} ms")
print(f"  avg memory       {mem_mb:,.0f} MB")
print(f"  GB-s per invoke  {per_inv:,.2f} mGB-s")
'

# ── Platform-side counters and database-side saturation ────────────────
metric () {
  local ns="$1" name="$2" stat="$3" dim="$4"
  aws cloudwatch get-metric-statistics \
    --namespace "${ns}" --metric-name "${name}" \
    --start-time "$(date -r "${START}" -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --end-time   "$(date -r "${END}"   -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --period 60 --statistics "${stat}" --dimensions "${dim}" \
    --profile "${PROFILE}" --region "${REGION}" \
    --query "Datapoints[].${stat}" --output json 2>/dev/null \
    | python3 -c '
import json, sys
raw = sys.stdin.read().strip()
v = json.loads(raw) if raw else []
if v:
    print(f"{max(v):,.2f} max / {sum(v)/len(v):,.2f} avg over {len(v)} pts")
else:
    print("no data")
'
}

echo "  --- platform ---"
echo -n "  Invocations      "; metric AWS/Lambda Invocations Sum "Name=FunctionName,Value=${FUNCTION}"
echo -n "  Throttles        "; metric AWS/Lambda Throttles Sum "Name=FunctionName,Value=${FUNCTION}"
echo -n "  ConcurrentExec   "; metric AWS/Lambda ConcurrentExecutions Maximum "Name=FunctionName,Value=${FUNCTION}"
echo -n "  Duration avg     "; metric AWS/Lambda Duration Average "Name=FunctionName,Value=${FUNCTION}"

echo "  --- database ---"
# DBLoad is active sessions. The instance presents 1 vCPU at 2 ACUs
# (PHASE_0 §III), so this number IS the oversubscription factor.
echo -n "  DBLoad           "; metric AWS/RDS DBLoad Average "Name=DBInstanceIdentifier,Value=${DB_INSTANCE}"
# Aurora-scoped, NOT proxy-scoped. PHASE_0 §XII: the {ProxyName} series
# undercounts real connections by ~2x, and 161 is retired as a ceiling.
echo -n "  Conns (Aurora)   "; metric AWS/RDS DatabaseConnections Maximum "Name=DBInstanceIdentifier,Value=${DB_INSTANCE}"
echo -n "  BorrowLatency µs "; metric AWS/RDS DatabaseConnectionsBorrowLatency Average "Name=ProxyName,Value=${PROXY_NAME}"
echo
