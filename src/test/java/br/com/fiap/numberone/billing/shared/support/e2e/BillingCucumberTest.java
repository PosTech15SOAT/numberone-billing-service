package br.com.fiap.numberone.billing.shared.support.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "br.com.fiap.numberone.billing.shared.support.e2e,br.com.fiap.numberone.billing.budget.e2e,br.com.fiap.numberone.billing.payment.e2e")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,html:target/cucumber-reports/index.html,json:target/cucumber-reports/cucumber.json,junit:target/cucumber-reports/cucumber.xml")
public class BillingCucumberTest {
}
