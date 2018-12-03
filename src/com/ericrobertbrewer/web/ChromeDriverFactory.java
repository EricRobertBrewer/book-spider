package com.ericrobertbrewer.web;

import org.openqa.selenium.chrome.ChromeDriver;

public class ChromeDriverFactory implements WebDriverFactory {

    public static void setDriverPath(String driverPath) {
        System.setProperty("webdriver.chrome.driver", driverPath);
    }

    @Override
    public ChromeDriver newInstance() {
        return new ChromeDriver();
    }
}
