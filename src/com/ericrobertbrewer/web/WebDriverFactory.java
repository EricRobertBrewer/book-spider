package com.ericrobertbrewer.web;

import org.openqa.selenium.chrome.ChromeDriver;

public class WebDriverFactory {

    WebDriverFactory() {
    }

    void enableChromeDriver(String driverPath) {
        System.setProperty("webdriver.chrome.driver", driverPath);
    }

    public ChromeDriver newChromeDriver() {
        return new ChromeDriver();
    }
}
