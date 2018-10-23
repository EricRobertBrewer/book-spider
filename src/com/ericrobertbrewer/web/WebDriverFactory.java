package com.ericrobertbrewer.web;

import org.openqa.selenium.chrome.ChromeDriver;

public class WebDriverFactory {

    public WebDriverFactory() {
    }

    public void enableChromeDriver(String driverPath) {
        System.setProperty("webdriver.chrome.driver", driverPath);
    }

    public ChromeDriver newChromeDriver() {
        return new ChromeDriver();
    }
}
