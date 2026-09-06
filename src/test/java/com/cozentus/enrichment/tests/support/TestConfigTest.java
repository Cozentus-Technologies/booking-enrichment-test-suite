package com.cozentus.enrichment.tests.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * E-1. {@code suite.env} used to reach exactly one line of the suite, in
 * {@link EntryCriteria}, while every other decision that depends on it - start a
 * process, create a topic, delete it afterwards - was taken unconditionally.
 * The question is asked in one place now, so these pin it.
 */
class TestConfigTest {

    @Test
    void theExternalProfileIsTheOneThatDoesNotOwnItsDeployment() {
        assertThat(TestConfig.load("external").isExternal()).isTrue();
        assertThat(TestConfig.load("local").isExternal()).isFalse();
        assertThat(TestConfig.load("ci").isExternal()).isFalse();
    }

    /**
     * E-1 requires that {@code service.jar.path} is never read under external.
     * Deleting the key from the profile is what makes that provable rather than
     * asserted: a code path that reaches for it fails loudly here instead of
     * quietly succeeding against a jar that happens to be on the machine.
     */
    @Test
    void theExternalProfileCarriesNoServiceJarPathToRead() {
        assertThatThrownBy(() -> TestConfig.load("external").required("service.jar.path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service.jar.path");

        assertThat(TestConfig.load("local").required("service.jar.path")).isNotBlank();
    }

    @Test
    void theExternalProfileGetsTheLongerReadinessTimeout() {
        assertThat(TestConfig.load("external").readinessTimeout())
                .isGreaterThan(TestConfig.load("local").readinessTimeout());
    }

    @Test
    void anUnknownProfileNamesTheFileItLookedFor() {
        assertThatThrownBy(() -> TestConfig.load("staging"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/config/test-staging.properties");
    }
}
