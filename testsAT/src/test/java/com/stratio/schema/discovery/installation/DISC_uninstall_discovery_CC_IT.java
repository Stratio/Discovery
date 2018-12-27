package com.stratio.schema.discovery.installation;

import com.stratio.qa.cucumber.testng.CucumberRunner;
import com.stratio.tests.utils.BaseTest;
import cucumber.api.CucumberOptions;
import org.testng.annotations.Test;

@CucumberOptions(features = { "src/test/resources/features/099_uninstall/004_disc_uninstallDiscoveryCC.feature" },format = "json:target/cucumber.json")
public class DISC_uninstall_discovery_CC_IT extends BaseTest {

    public DISC_uninstall_discovery_CC_IT() {}

    @Test(enabled = true, groups = {"purge_discovery_cc"})
    public void DISC_uninstall_discovery_CC_IT() throws Exception{
        new CucumberRunner(this.getClass()).runCukes();
    }

}
