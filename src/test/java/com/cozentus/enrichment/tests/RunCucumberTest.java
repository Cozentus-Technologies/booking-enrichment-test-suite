package com.cozentus.enrichment.tests;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * The single Cucumber entry point.
 *
 * <p>Selects {@code features} from the classpath, which is both the hand-authored
 * files and {@code city_correction.feature} generated from the CSV at build time.
 *
 * <p>Reporting is configured in {@code junit-platform.properties}; tag filtering
 * comes from {@code -Dcucumber.filter.tags}, which is how run-tests.sh slices.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.cozentus.enrichment.tests")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
public class RunCucumberTest {
}
