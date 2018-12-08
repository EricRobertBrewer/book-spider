package com.ericrobertbrewer.web.driver;

import org.openqa.selenium.chrome.ChromeDriver;

public class ChromeDriverFactory implements WebDriverFactory {

    @Override
    public ChromeDriver newInstance() {
        return new ChromeDriver();
    }
}
