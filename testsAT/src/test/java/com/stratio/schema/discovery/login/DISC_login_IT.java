package com.stratio.schema.discovery.login;

import com.stratio.qa.cucumber.testng.CucumberFeatureWrapper;
import com.stratio.qa.cucumber.testng.CucumberRunner;
import com.stratio.qa.cucumber.testng.PickleEventWrapper;
import com.stratio.qa.data.BrowsersDataProvider;
import com.stratio.tests.utils.BaseTest;
import cucumber.api.CucumberOptions;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@CucumberOptions(features = {
        "src/test/resources/features/003_login/001_disc_loginUserPassword.feature",
        "src/test/resources/features/003_login/002_disc_createGroups.feature",
        "src/test/resources/features/003_login/003_disc_updateEnvDiscovery.feature",
        "src/test/resources/features/003_login/004_disc_loginWithHeaders.feature"
}, plugin = "json:target/cucumber.json")
public class DISC_login_IT extends BaseTest{

    @Factory(enabled = false, dataProviderClass = BrowsersDataProvider.class, dataProvider = "availableUniqueBrowsers")
    public DISC_login_IT(String browser) {
        this.browser = browser;
    }
    @Test(enabled = true, groups = {"login"}, dataProvider = "scenarios")
    public void runFeatures(PickleEventWrapper pickleWrapper, CucumberFeatureWrapper featureWrapper) throws Throwable {
        runScenario(pickleWrapper, featureWrapper);
    }
}
