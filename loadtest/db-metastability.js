// Phase 1 — METASTABILITY test: does the system stay collapsed after the
// trigger is withdrawn?
//
// The question this answers is the one nothing in Phase 0 tested. Overload
// and metastable failure look identical while the load is applied -- goodput
// falls, latency climbs, errors appear. They differ only in what happens
// AFTER the load is removed:
//
//   Plain overload      -- withdraw the excess load, throughput returns to
//                          its previous level. The system was merely busy.
//   Metastable failure  -- withdraw the excess load and throughput STAYS
//                          collapsed, because a self-reinforcing feedback
//                          loop now sustains the bad state without help from
//                          the original trigger. The system must be kicked
//                          (restarted, drained, shed) to recover.
//
// The distinction decides the mitigation. Autoscaling and capacity headroom
// address overload. Neither touches metastability -- for that you need to
// break the feedback loop: load shedding, circuit breaking, bounded retries.
//
// DESIGN: three phases at a FIXED arrival rate, open-loop.
//
//   BASELINE  600 req/s for 5 min   -- comfortably sustainable; establishes
//                                      the reference goodput.
//   TRIGGER  1600 req/s for 5 min   -- past the knee; induces collapse.
//   RECOVERY  600 req/s for 10 min  -- EXACTLY the baseline rate again.
//
// The measurement is a single comparison: goodput in RECOVERY versus goodput
// in BASELINE, at identical offered load. Recovery to baseline means
// overload. Persistent depression means metastability. The recovery phase is
// deliberately twice as long as the baseline so that slow recovery can be
// distinguished from no recovery -- a system that takes four minutes to come
// back is telling us something different from one that never does.
//
// Open-loop is essential here. Under a closed-loop VU driver, withdrawing
// load and "returning to baseline VUs" would not return to baseline offered
// RATE while responses are still slow, so the recovery phase would be
// confounded by the driver's own backpressure.
//
// Per the warm-up protocol, run only on an already-warm cluster.

import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.API_URL;
if (!API_URL) {
  throw new Error('API_URL env var is required');
}

// Rates chosen from the open-loop staircase rather than guessed. That run
// measured the knee between 400 and 700 req/s -- far below the ~1,150 req/s
// the earlier CLOSED-loop ramp implied, because a closed-loop driver backs
// off as the system slows and so never asks for more than it can give.
//
//   400 req/s -- measured clean: 0 errors, 0 throttles, 1.07 ms borrow-wait,
//                DBLoad 0.12. A trustworthy reference.
//   1200 req/s -- past the knee: DBLoad ~60 against 1 vCPU, borrow-wait ~1-2 s.
//
// An earlier draft used 600 req/s as the baseline. The staircase showed 600
// sits INSIDE the knee, which would have contaminated the reference the whole
// experiment depends on.
const BASELINE = 400;
const TRIGGER = 1200;

export const options = {
  discardResponseBodies: true,
  scenarios: {
    metastability: {
      executor: 'ramping-arrival-rate',
      startRate: BASELINE,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 1200,
      stages: [
        // BASELINE — reference goodput at a sustainable rate.
        { duration: '5m', target: BASELINE },
        // TRIGGER — step (not ramp) past the knee. A step is the right shape:
        // metastability is about a shock pushing the system over an edge, and
        // a gradual ramp would let it find equilibrium on the way up.
        { duration: '10s', target: TRIGGER },
        { duration: '5m', target: TRIGGER },
        // RECOVERY — step straight back to the exact baseline rate.
        { duration: '10s', target: BASELINE },
        { duration: '10m', target: BASELINE },
      ],
    },
  },
};

export default function () {
  const res = http.get(`${API_URL}/db`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
