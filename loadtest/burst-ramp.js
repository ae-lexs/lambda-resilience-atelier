// Module 06 — Burst-ramp load test against LraProvisionedConcurrencyStack.
// Ramps 0 → 500 VUs over 10 s, holds 500 for 60 s, ramps down over 10 s.
// Thresholds enforce a goodput SLA: p(95) < 500 ms with < 1% protocol
// failures and > 95% application checks. On a healthy stack (PC=2 +
// ASG-to-10) all three pass; cold-start spillover still happens during
// ramp-up but lives in p(99)+ / max where a p(95) threshold doesn't
// reach — see Module 06 Pitfall #11 for why pairing percentile
// thresholds with a max threshold matters for CI cold-start regressions.
//
// Run via: docker compose --profile loadtest run --rm k6 run /loadtest/burst-ramp.js
// Requires: API_URL env (the API Gateway endpoint from `cdk deploy` output).

import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.API_URL;
if (!API_URL) {
  throw new Error('API_URL env var is required (e.g. https://xxxx.execute-api.us-east-1.amazonaws.com)');
}

export const options = {
  stages: [
    { duration: '10s', target: 500 },  // ramp 0 → 500 VUs
    { duration: '60s', target: 500 },  // hold 500 VUs
    { duration: '10s', target: 0 },    // ramp down to 0
  ],
  thresholds: {
    // SLA: 95% of requests must complete in under 500 ms.
    // On a healthy stack (PC=2 + ASG-to-10) this typically PASSES at
    // p(95) ~110 ms — the PC-served population dominates the
    // percentile. Cold-start spillover still happens during ramp-up
    // but lives in p(99)+ / max; pair this threshold with a tail
    // threshold (e.g., 'max<10000' below) to surface bimodality in
    // CI runs. See Module 06 Pitfall #11.
    'http_req_duration': ['p(95)<500'],

    // Operational SLA: fewer than 1% errors.
    // PC's spillover routes excess invocations to on-demand cold
    // starts, which still return HTTP 200 — they're slow, not failed.
    // This threshold should PASS on healthy stacks; if it ever fires,
    // the system is broken at the protocol layer (timeouts, DNS, TCP
    // refusal), not just slow.
    'http_req_failed': ['rate<0.01'],

    // Application-level: 95% of /health responses parse correctly.
    'checks': ['rate>0.95'],
  },
};

export default function () {
  const res = http.get(`${API_URL}/health`);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'body contains status:ok': (r) => r.body && r.body.includes('"status":"ok"'),
  });
}
