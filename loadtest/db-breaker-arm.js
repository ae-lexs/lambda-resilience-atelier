// Phase 1 — THE BREAKER EXPERIMENT: one arm per invocation.
//
// Tests the shedding hypothesis recorded in PHASE_0_SMOKE_FINDINGS.md §IX.
// That section observed Lambda's concurrency cap behaving as an accidental
// load shedder — throttled requests are refused in milliseconds instead of
// occupying a Lambda for ~14 s of borrow-wait — and predicted that a
// deliberate shedder at the pool boundary should, at identical offered
// load past the knee, RAISE goodput and CUT cost simultaneously.
//
//   ENDPOINT=/db          control   — no guard, borrow-wait to timeout
//   ENDPOINT=/db-breaker  treatment — resilience4j breaker, sheds with 429
//
// ── Why one arm per invocation, not one script with staged scenarios ────
//
// §X established that this system is METASTABLE: ten minutes after a
// trigger was withdrawn it still delivered 46% of baseline goodput, and one
// minute after withdrawal only 12.9%. Arms run back-to-back inside a single
// k6 run would therefore not be independent — the second arm would inherit
// the first arm's collapsed state, and whichever arm ran second would be
// handicapped by an amount nobody measured. Ordering would masquerade as
// treatment effect.
//
// So each arm is its own invocation, with recovery verified between arms
// from the CloudWatch side before the next one starts, and the arms are run
// A-B-B-A so that any residual monotonic drift (Aurora continuing to warm
// per §VII, cache fill, k6 host state) is balanced across the two
// treatments rather than loading onto one of them.
//
// ── Why open-loop ──────────────────────────────────────────────────────
//
// constant-arrival-rate, per §IX. A closed-loop VU executor backs off as
// the system slows, which flattered the knee by ~3× and made "offered load"
// a dependent variable. preAllocatedVUs is sized well above the expected
// requirement so that the driver, not the system, is never the thing being
// measured — and dropped_iterations is reported regardless, because a run
// with heavy drops is measuring k6.
//
// ── The measurement ────────────────────────────────────────────────────
//
// Status codes are the primary instrument, and every code below is emitted
// by exactly one layer. That property was not free — see the note after the
// table.
//
//   200  served — a real database answer. This is goodput.
//   429  shed by the BREAKER (application). No connection borrowed,
//        single-digit ms billed. Treatment arm only.
//   512  admitted by the breaker and FAILED anyway (application) — borrow
//        timeout or database error. This request paid the full ~10 s
//        expensive path. Treatment arm only.
//   503  throttled by the PLATFORM. API Gateway emits this when Lambda
//        refuses an invocation because the function's reserved concurrency
//        is saturated. The request never reached application code.
//   500  application exception on the control arm — /db has no error
//        handling, so a HikariCP borrow timeout propagates as a 500.
//
// ⚠ 503 IS NOT AN APPLICATION FAILURE. An earlier revision of this script
// assigned 503 to the breaker's admitted-and-failed path, which would have
// made the treatment arm's headline number a sum of two opposite things:
// the platform shedding cheaply (evidence FOR the §IX hypothesis) and the
// application failing expensively (evidence against it). The warm-up run
// exposed it — 22,688 responses with status 503 at a p50 of 71 ms, far too
// fast to be a 10 s borrow timeout. The application now answers 512, which
// API Gateway never generates.
//
// The latency split is the corroborating instrument: platform throttles
// return in ~70 ms, borrow timeouts in ~9,700 ms. If those two ever appear
// in the same bucket, the taxonomy has drifted again.
//
// Cost is NOT computed here. GB-seconds come from the REPORT lines via
// Logs Insights afterwards, because a client-side duration is a proxy for
// billed duration and PHASE_0 §XI is a standing reminder that a confident
// number placed on a proxy is how this campaign has gone wrong twice.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const API_URL = __ENV.API_URL;
if (!API_URL) {
  throw new Error('API_URL env var is required');
}

const ENDPOINT = __ENV.ENDPOINT || '/db';
const RATE = parseInt(__ENV.RATE || '600', 10);
const DURATION = __ENV.DURATION || '4m';
const ARM = __ENV.ARM || 'unlabelled';

// Sized from Little's Law against the WORST case, not the healthy one.
// The control arm past the knee holds ~200 requests (the function's
// reserved concurrency) at ~10 s each while the remainder are throttled
// in ~20 ms, giving a mean response time near 0.5 s and a VU requirement
// near RATE/2. Allocating 4x that leaves the driver with no excuse.
const PRE_ALLOCATED_VUS = Math.max(300, RATE * 2);
const MAX_VUS = Math.max(600, RATE * 4);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    arm: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
};

const served = new Counter('lra_served_200');
const shedByBreaker = new Counter('lra_shed_429');
const failedAdmitted = new Counter('lra_failed_512');
const throttledByPlatform = new Counter('lra_throttled_503');
const appError = new Counter('lra_app_error_500');
const otherStatus = new Counter('lra_other_status');

// Latency split by outcome. The whole cost argument rests on shed requests
// being orders of magnitude faster than admitted-and-failed ones, so the
// two must never share a percentile — and platform throttles must not
// share one with either, since they are cheap for a different reason.
const servedLatency = new Trend('lra_latency_served', true);
const shedLatency = new Trend('lra_latency_shed', true);
const failedLatency = new Trend('lra_latency_failed', true);
const throttledLatency = new Trend('lra_latency_throttled', true);

export default function () {
  const res = http.get(`${API_URL}${ENDPOINT}`);

  if (res.status === 200) {
    served.add(1);
    servedLatency.add(res.timings.duration);
  } else if (res.status === 429) {
    shedByBreaker.add(1);
    shedLatency.add(res.timings.duration);
  } else if (res.status === 512) {
    failedAdmitted.add(1);
    failedLatency.add(res.timings.duration);
  } else if (res.status === 503) {
    throttledByPlatform.add(1);
    throttledLatency.add(res.timings.duration);
  } else if (res.status === 500) {
    appError.add(1);
    failedLatency.add(res.timings.duration);
  } else {
    otherStatus.add(1);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);

  const total = count('lra_served_200') + count('lra_shed_429')
    + count('lra_failed_512') + count('lra_throttled_503')
    + count('lra_app_error_500') + count('lra_other_status');

  const durationSeconds = { s: 1, m: 60, h: 3600 }[DURATION.slice(-1)]
    * parseFloat(DURATION);

  const pct = (n) => (total ? +(100 * n / total).toFixed(2) : 0);

  const summary = {
    arm: ARM,
    endpoint: ENDPOINT,
    offered_rate: RATE,
    duration: DURATION,
    outcomes: {
      served_200: count('lra_served_200'),
      shed_429_breaker: count('lra_shed_429'),
      failed_512_admitted: count('lra_failed_512'),
      throttled_503_platform: count('lra_throttled_503'),
      app_error_500: count('lra_app_error_500'),
      other: count('lra_other_status'),
      total_responses: total,
    },
    // Goodput is the headline: successful responses per second of wall
    // clock, at a fixed offered rate. Comparing arms on anything else
    // (success RATIO, say) would let an arm look good by answering fewer
    // requests overall.
    goodput_per_s: +(count('lra_served_200') / durationSeconds).toFixed(1),
    served_pct: pct(count('lra_served_200')),
    dropped_iterations: count('dropped_iterations'),
    latency_ms: {
      served_p50: m.lra_latency_served ? m.lra_latency_served.values.med : null,
      served_p95: m.lra_latency_served ? m.lra_latency_served.values['p(95)'] : null,
      shed_p50: m.lra_latency_shed ? m.lra_latency_shed.values.med : null,
      shed_p95: m.lra_latency_shed ? m.lra_latency_shed.values['p(95)'] : null,
      failed_p50: m.lra_latency_failed ? m.lra_latency_failed.values.med : null,
      failed_p95: m.lra_latency_failed ? m.lra_latency_failed.values['p(95)'] : null,
      throttled_p50: m.lra_latency_throttled ? m.lra_latency_throttled.values.med : null,
    },
  };

  return {
    stdout: '\n' + JSON.stringify(summary, null, 2) + '\n',
    [`/loadtest/results/${ARM}.json`]: JSON.stringify(summary, null, 2),
  };
}
