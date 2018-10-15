package com.ericrobertbrewer.web;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

public class DriverUtils {

    /**
     * By 250 pixels.
     * @param driver The driver.
     * @param times Number of times to scroll down.
     * @param delayMillis Number of milliseconds to delay after each downward scroll.
     */
    public static void scrollDown(WebDriver driver, int times, long delayMillis) {
        for (int i = 0; i < times; i++) {
            ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 250);");
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
