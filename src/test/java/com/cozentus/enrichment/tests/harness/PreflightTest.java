package com.cozentus.enrichment.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E-3. Proves the preflight's judgement without a broker.
 *
 * <p>The point of a preflight is that it is trusted: someone reads its verdict
 * and decides whether to start a twenty-minute run. A check that reported a
 * missing topic as present, or that printed a verdict without saying which value
 * it used to reach it, would be worse than not running it at all.
 */
class PreflightTest {

    private static final List<String> TOPICS =
            List.of("booking.raw", "booking.enriched", "booking.flagged");

    @Test
    void everyConfiguredTopicPresentPasses() {
        Preflight.Check check = Preflight.topicsPresent(TOPICS,
                Set.of("booking.raw", "booking.enriched", "booking.flagged", "__consumer_offsets"));

        assertThat(check.passed()).isTrue();
        assertThat(check.valueUsed()).isEqualTo("booking.raw, booking.enriched, booking.flagged");
    }

    @Test
    void aMissingTopicFailsAndIsNamed() {
        Preflight.Check check = Preflight.topicsPresent(TOPICS,
                Set.of("booking.raw", "booking.enriched"));

        assertThat(check.passed()).isFalse();
        assertThat(check.detail()).contains("booking.flagged");
        assertThat(check.detail()).doesNotContain("booking.raw");
    }

    @Test
    void aReadinessEndpointThatIsNotAnsweringFails() {
        Preflight.Check check = Preflight.readiness("http://localhost:1/ready",
                new ServiceController.Readiness(-1, "ConnectException: Connection refused"));

        assertThat(check.passed()).isFalse();
        assertThat(check.valueUsed()).isEqualTo("http://localhost:1/ready");
        assertThat(check.detail()).contains("Connection refused");
    }

    /**
     * A 503 is a deployment that is up and not ready, which is exactly the case
     * a preflight exists to catch before a run mistakes it for a routing bug.
     */
    @Test
    void anythingOtherThanTwoHundredFails() {
        assertThat(Preflight.readiness("http://host/ready",
                new ServiceController.Readiness(503, "HTTP 503")).passed()).isFalse();
        assertThat(Preflight.readiness("http://host/ready",
                new ServiceController.Readiness(200, "HTTP 200")).passed()).isTrue();
    }

    @Test
    void everyLineNamesTheValueTheCheckUsed() {
        String rendered = Preflight.render(new Preflight.Check(
                "Broker reachable", "localhost:9092", true, "12 topic(s) visible"));

        assertThat(rendered).contains("PASS", "Broker reachable", "localhost:9092", "12 topic(s) visible");
    }

    @Test
    void oneFailedCheckIsEnoughToFailTheWholePreflight() {
        assertThat(Preflight.failed(List.of(
                new Preflight.Check("a", "x", true, ""),
                new Preflight.Check("b", "y", false, "")))).isEqualTo(1);
        assertThat(Preflight.failed(List.of(new Preflight.Check("a", "x", true, "")))).isZero();
    }
}
