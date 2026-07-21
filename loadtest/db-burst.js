// Phase 0 — DB-path burst against LraDatabaseResilienceStack.
//
// Why this exists separately from burst-ramp.js: that script drives /health,
// and HealthController returns a static map without touching the database.
// It exercises Lambda cold-start behavior and nothing below it, so it moves
// neither Aurora DBLoad nor RDS Proxy DatabaseConnectionsBorrowLatency. Any
// experiment about the connection ceiling or DB-tier saturation has to drive
// /db, which issues SELECT now() through the RDS Proxy on an IAM-auth
// connection.
//
// Run via:
//   docker compose --profile loadtest run --rm k6 run /loadtest/db-burst.js
// Requires: API_URL env (ApiUrl output from `cdk deploy`).
//
// VU counts are deliberately NOT carried over from the pre-2026-07-20
// configuration. The cluster is now pinned at 2 ACUs (was max 1), so
// max_connections roughly doubled and the old ~188-connection ceiling no
// longer applies. Ceiling to be re-derived empirically.

import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.API_URL;
if (!API_URL) {
  throw new Error('API_URL env var is required (e.g. https://xxxx.execute-api.us-east-1.amazonaws.com)');
}

// Smoke profile: enough concurrency to make the DB-tier signals move, well
// short of the level meant to exhaust the pool. The goal here is instrument
// verification — confirming DBLoad and BorrowLatency respond at all — not
// finding the ceiling.
export const options = {
  stages: [
    { duration: '20s', target: 100 },  // ramp 0 → 100 VUs
    { duration: '90s', target: 100 },  // hold, long enough for 1-min metrics
    { duration: '10s', target: 0 },    // ramp down
  ],
  // No thresholds. A smoke run that "fails" on latency still tells us what we
  // came to learn; asserting an SLA here would only add noise.
};

export default function () {
  const res = http.get(`${API_URL}/db`);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'body contains db_now': (r) => r.body && r.body.includes('db_now'),
  });
}
