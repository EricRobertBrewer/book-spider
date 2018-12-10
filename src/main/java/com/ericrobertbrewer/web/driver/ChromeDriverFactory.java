package com.ericrobertbrewer.web.driver;

import org.openqa.selenium.chrome.ChromeDriver;

public class ChromeDriverFactory implements WebDriverFactory {

    /**
     * Get a new instance of {@link ChromeDriver}.
     * Be sure to set the {@code webdriver.chrome.driver} system property as the path to your local Chrome driver
     * executable before invoking this method. There are two ways to do so:
     * <ol>
     *     <li>In the source code, using: {@code System.setProperty("webdriver.chrome.driver", path);}, or</li>
     *     <li>As a VM argument to the program: {@code -Dwebdriver.chrome.driver=path}.</li>
     * </ol>
     * @return      A new instance of {@link ChromeDriver}.
     */
    @Override
    public ChromeDriver newInstance() {
        return new ChromeDriver();
    }
}
