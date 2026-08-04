package io.github.aelexs.api.db;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * TREATMENT ARM of the breaker experiment — an explicit circuit breaker at
 * the connection-pool boundary, the Java analog of CRA Module 08's
 * `sony/gobreaker`.
 *
 * <h2>The prediction this endpoint exists to falsify</h2>
 *
 * PHASE_0 §IX observed that Lambda's account concurrency cap was acting as
 * an <em>accidental</em> load shedder: throttled requests are rejected in
 * milliseconds instead of occupying a Lambda for ~14 s of borrow-wait, and
 * that is probably why goodput above the knee did not fall to zero. If that
 * reading is right, then a shedder placed deliberately — here — should, at
 * identical offered load past the knee, <strong>raise goodput and cut
 * cost at the same time</strong>. Those two normally trade against each
 * other, which is what makes the prediction worth testing: cost falls
 * because a shed request bills ~5 ms instead of ~10,000 ms, and goodput
 * rises because the requests that <em>are</em> admitted meet a database
 * that is no longer 60× oversubscribed.
 *
 * The falsifier is symmetric and specific: if goodput does not rise, the
 * shedding hypothesis in §IX is wrong and the concurrency cap was helping
 * for some other reason.
 *
 * <h2>Why the window is absurdly small by server standards</h2>
 *
 * A conventional resilience4j deployment uses a sliding window of 100
 * calls, because one long-lived JVM serves the whole traffic stream and
 * accumulates a representative sample quickly. <strong>That reasoning does
 * not transfer to Lambda.</strong> Breaker state is per-JVM, and under
 * Lambda every execution environment is its own JVM serving
 * <em>one request at a time</em>. So this is not one breaker with a
 * population of N calls — it is N independent breakers, each of which must
 * learn that the database is sick from its own tiny private sample,
 * sequentially.
 *
 * With a 100-call window and 200 concurrent containers, the fleet would
 * have to absorb 20,000 slow calls before it finished tripping — at ~10 s
 * each, that is most of the experiment spent not shedding. The window
 * below (trip on 3 calls, 5-call window) is sized so a container commits
 * after ~3 bad requests. The cost of a small window is exactly what
 * statistics says it is: a higher false-trip rate on noise. In a shedding
 * control that is the cheap direction to err.
 *
 * SnapStart interacts with this. The breaker is constructed during bean
 * initialization, i.e. <em>before</em> the checkpoint, so the snapshot
 * captures a CLOSED breaker with an empty window and every restored
 * container begins naive. There is no warm-start path for breaker state
 * and no shared store — deliberately, since adding one would make this a
 * distributed-coordination experiment instead of a shedding experiment.
 *
 * <h2>Why there is no bulkhead</h2>
 *
 * The obvious companion pattern is a bulkhead capping concurrent calls
 * into the pool. It is <strong>vacuous here</strong>: the Lambda execution
 * model already pins per-container concurrency at exactly 1, so a
 * semaphore of any size ≥ 1 can never reject anything. The bulkhead's role
 * — bounding total concurrent demand on the dependency — is played at the
 * platform layer instead, by the function's reserved concurrency
 * (see DatabaseResilienceStack). Serverless does not remove the pattern;
 * it relocates it from the application to the deployment descriptor.
 *
 * <h2>Why slow calls, not failures, are the trip signal</h2>
 *
 * Congestive collapse does not present as errors first. PHASE_0 §VIII
 * measured borrow latency crossing four orders of magnitude — 105 µs to
 * 4.73 s — while requests still ultimately succeeded. A breaker
 * configured only on failure rate would not trip until requests began
 * timing out at 10 s, by which point the fleet is already billing for the
 * waiting, which is the cost this endpoint exists to avoid. The
 * slow-call threshold is therefore the load-bearing setting, and 250 ms is
 * chosen as ~10× the healthy p50 of ~27 ms measured at 400 rps: far enough
 * above normal to avoid tripping on jitter, far enough below the 10 s
 * HikariCP borrow timeout to fire long before the expensive path.
 */
@RestController
@ConditionalOnProperty(name = "DB_PROXY_ENDPOINT")
public class BreakerDbController {

    private final DbQuery dbQuery;
    private final CircuitBreaker breaker;

    public BreakerDbController(final DbQuery dbQuery) {
        this.dbQuery = dbQuery;
        this.breaker = CircuitBreaker.of("db-pool", CircuitBreakerConfig.custom()
            // Count-based, not time-based: a container may sit idle for
            // minutes between invocations, and a time window would decay
            // to empty and re-open the breaker for reasons that have
            // nothing to do with the database's health.
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(5)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(50f)
            .slowCallDurationThreshold(Duration.ofMillis(250))
            .slowCallRateThreshold(50f)
            // How long a tripped container refuses before probing again.
            // This is the knob that sets fleet-wide admitted load while
            // the database is sick: with N tripped containers each
            // probing once per wait period, admitted rate ≈ N / 5 s.
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .permittedNumberOfCallsInHalfOpenState(1)
            // Without this, a half-open container that is never invoked
            // again holds the state forever; Lambda containers idle out
            // constantly, so let the transition happen on time too.
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .build());
    }

    /**
     * Returns 200 with the query result when the breaker admits the call,
     * 429 in single-digit milliseconds when it sheds, and 512 when the call
     * was admitted and the database failed it anyway.
     *
     * The status codes are the measurement. k6 counts them per stage, and
     * the split between 429 (cheap refusal) and 512 (expensive failure) is
     * precisely the quantity the cost prediction turns on.
     *
     * <h3>Why 512 and not the obvious 503</h3>
     *
     * Because API Gateway itself emits 503 when it throttles a Lambda
     * invocation. Measured during this experiment's warm-up: the first
     * burst against a cold cluster produced 22,688 responses with status
     * 503 at a p50 of 71 ms — far too fast to be a 10 s borrow timeout,
     * and in fact the platform refusing to invoke at all once the
     * function's reserved concurrency was saturated.
     *
     * Had the application also answered 503, the treatment arm's most
     * important number would have been a sum of two opposite things: the
     * platform shedding (cheap, and evidence FOR the §IX hypothesis) and
     * the application failing expensively (evidence against it). 512 is
     * unassigned in the IANA registry and API Gateway passes proxy
     * responses through unaltered, so it cannot be confused with anything
     * the infrastructure generates.
     *
     * Nothing is logged per request on purpose: at 400+ rps a log line per
     * invocation would add a CloudWatch Logs ingestion bill comparable to
     * the compute bill being measured, and PHASE_0 §XI is a standing
     * reminder to enumerate every metered service before running.
     */
    @GetMapping("/db-breaker")
    public ResponseEntity<Map<String, Object>> queryNow() {
        try {
            return ResponseEntity.ok(breaker.executeCallable(dbQuery::now));
        } catch (CallNotPermittedException shed) {
            // The breaker is OPEN. No connection was borrowed, no database
            // work was attempted, and the invocation ends here — this is
            // the path that turns a ~10,000 ms billed request into a ~5 ms
            // one.
            return ResponseEntity.status(429).body(Map.of(
                "shed", true,
                "state", breaker.getState().toString()));
        } catch (Exception failed) {
            // Admitted, attempted, and failed — a borrow timeout or a
            // database error. Distinct from a shed on purpose: this
            // request paid the full expensive path.
            return ResponseEntity.status(512).body(Map.of(
                "shed", false,
                "state", breaker.getState().toString(),
                "error", failed.getClass().getSimpleName()));
        }
    }
}
