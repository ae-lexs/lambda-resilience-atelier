// Phase 1 — OPEN-LOOP staircase through the knee, 5-minute holds.
//
// Supersedes db-ceiling-ramp.js for locating the collapse point. That script
// used k6's VU executor, which is CLOSED-loop: each VU waits for its response
// before issuing the next request, so when the system slows the offered rate
// falls with it. Under a closed-loop driver "offered load" is not an
// independent variable, and a throughput collapse partly conceals itself --
// the load generator backs off in sympathy with the system it is measuring.
//
// ramping-arrival-rate issues requests at a fixed rate regardless of how the
// system responds, which makes offered load independent and is the
// precondition for saying anything precise about goodput-vs-offered-load.
//
// Stages bracket the knee found by the earlier ramp (goodput peaked near
// 1,150 req/s, then collapsed). Holds are 5 minutes so each stage yields
// several clean 1-minute CloudWatch datapoints rather than one
// boundary-straddled sample -- the resolution limitation called out in
// PHASE_0_SMOKE_FINDINGS.md VIII.
//
// READ ALONGSIDE dropped_iterations. When k6 cannot start an iteration on
// schedule -- because every VU up to maxVUs is still blocked on a slow
// response -- it drops it and counts it. Dropped iterations are offered load
// that never reached the system, and must be subtracted before claiming what
// the system was actually asked to do. A run with heavy drops is measuring
// the load generator as much as the system.
//
// Per the warm-up protocol, run only on an already-warm cluster.

import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.API_URL;
if (!API_URL) {
  throw new Error('API_URL env var is required');
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    staircase: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      // 1200 sits deliberately above Lambda's 1000 account concurrency, so
      // throttling shows up as a signal rather than being masked by a VU
      // ceiling. Anything past 1000 concurrent has a Lambda-tier cause and
      // no database meaning.
      maxVUs: 1200,
      stages: [
        { duration: '30s', target: 400 },  { duration: '5m', target: 400 },
        { duration: '30s', target: 700 },  { duration: '5m', target: 700 },
        { duration: '30s', target: 1000 }, { duration: '5m', target: 1000 },
        { duration: '30s', target: 1300 }, { duration: '5m', target: 1300 },
        { duration: '30s', target: 1600 }, { duration: '5m', target: 1600 },
        { duration: '30s', target: 0 },
      ],
    },
  },
};

export default function () {
  const res = http.get(`${API_URL}/db`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
