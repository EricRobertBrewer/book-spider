package com.ericrobertbrewer.bookspider.sites;

import org.openqa.selenium.WebDriver;

import java.io.File;
import java.util.logging.Logger;

public abstract class SiteScraper {

    private final Logger logger;

    SiteScraper(Logger logger) {
        this.logger = logger;
    }

    public abstract void scrape(WebDriver driver, File rootFolder);

    Logger getLogger() {
        return logger;
    }
}
