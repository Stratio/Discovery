package com.stratio.schema.discovery.installation;

import com.stratio.qa.cucumber.testng.CucumberRunner;
import com.stratio.qa.data.BrowsersDataProvider;
import com.stratio.tests.utils.BaseTest;
import cucumber.api.CucumberOptions;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@CucumberOptions(features = { "src/test/resources/features/001_installation/002_disc_installDiscoveryCC.feature" },format = "json:target/cucumber.json")
public class DISC_install_discovery_CC_IT extends BaseTest {

    @Factory(dataProviderClass = BrowsersDataProvider.class, dataProvider = "availableUniqueBrowsers")
    public DISC_install_discovery_CC_IT(String browser) {this.browser = browser;}

    @Test(enabled = true, groups = {"install_discovery_cc"})
    public void DISC_install_discovery_CC_IT() throws Exception {
        new CucumberRunner(this.getClass()).runCukes();
    }

}
