package com.ericrobertbrewer.web.driver;

import org.openqa.selenium.*;

import java.util.List;

public class DriverUtils {

    public interface TextFormatter {
        String format(String text);
    }

    /**
     * By 250 pixels.
     *
     * @param driver      The driver.
     * @param times       Number of times to scroll down.
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

    public static String getConcatenatedTexts(WebElement element, By by, String separator) {
        return getConcatenatedTexts(element, by, separator, null);
    }

    /**
     * Extract a concatenation of texts of child elements.
     *
     * @param element   The element from which child elements will be found.
     * @param by        The criterion to find child elements.
     * @param separator Used to separate text of different child elements.
     * @param formatter May be `null`. If provided, text from each child element will be formatted by this.
     * @return The concatenated text from the specified child elements.
     */
    public static String getConcatenatedTexts(WebElement element, By by, String separator, TextFormatter formatter) {
        final List<WebElement> children = element.findElements(by);
        final StringBuilder s = new StringBuilder();
        for (WebElement child : children) {
            final String text = child.getAttribute("textContent").trim();
            // Ignore empty elements.
            if (text.isEmpty()) {
                continue;
            }
            if (s.length() > 0) {
                s.append(separator);
            }
            if (formatter != null) {
                s.append(formatter.format(text));
            } else {
                s.append(text);
            }
        }
        return s.toString();
    }

    public static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ignored) {
        }
    }

    public static WebElement findElementWithRetries(WebDriver driver, By by, int retries, long delayMillis) {
        while (retries > 1) {
            try {
                return driver.findElement(by);
            } catch (NoSuchElementException e) {
                sleep(delayMillis);
            }
            retries--;
        }
        return driver.findElement(by);
    }

    public static WebElement findElementWithRetries(WebElement element, By by, int retries, long delayMillis) {
        while (retries > 1) {
            try {
                return element.findElement(by);
            } catch (NoSuchElementException e) {
                sleep(delayMillis);
            }
            retries--;
        }
        return element.findElement(by);
    }
}
