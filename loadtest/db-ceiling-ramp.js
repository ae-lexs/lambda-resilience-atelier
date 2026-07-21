// Phase 1 — staircase ramp to locate the DB connection ceiling.
//
// Drives /db (SELECT now() through the RDS Proxy) in five holds of increasing
// concurrency, looking for the inflection where RDS Proxy
// DatabaseConnectionsBorrowLatency departs from flat — i.e. where client
// sessions begin queueing for a backend connection.
//
// Run via:
//   docker compose --profile loadtest run --rm k6 run /loadtest/db-ceiling-ramp.js
//
// Two constraints bound the design:
//
//   1. Lambda account concurrency is 1000 (L-B99A9384). Above that, requests
//      are throttled at the Lambda tier and the resulting latency has nothing
//      to do with the database. The top hold is therefore 1000, and Throttles
//      must be verified to be zero for the run to be interpretable.
//   2. Per the warm-up protocol, this must run on an ALREADY-WARM cluster.
//      A first burst after cluster creation costs ~22x more DBLoad and ~152x
//      more pool-wait, which would masquerade as a ceiling. Discard the first
//      burst after any deploy before running this.
//
// Each hold is 60s so that 1-minute CloudWatch datapoints resolve cleanly;
// the ramps between holds are deliberately short to keep the holds distinct.

import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.API_URL;
if (!API_URL) {
  throw new Error('API_URL env var is required');
}

export const options = {
  stages: [
    { duration: '20s', target: 100 },  { duration: '60s', target: 100 },
    { duration: '20s', target: 250 },  { duration: '60s', target: 250 },
    { duration: '20s', target: 500 },  { duration: '60s', target: 500 },
    { duration: '20s', target: 750 },  { duration: '60s', target: 750 },
    { duration: '20s', target: 1000 }, { duration: '90s', target: 1000 },
    { duration: '20s', target: 0 },
  ],
  // No thresholds: this run is looking for where the system bends, and an
  // aborted run tells us less than a completed one.
};

export default function () {
  const res = http.get(`${API_URL}/db`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
