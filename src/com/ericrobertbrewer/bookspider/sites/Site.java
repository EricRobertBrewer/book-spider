package com.ericrobertbrewer.bookspider.sites;

import org.openqa.selenium.WebDriver;

import java.io.File;
import java.util.logging.Logger;

public abstract class Site {

    private final Logger logger;

    Site() {
        this.logger = Logger.getLogger(getClass().getSimpleName());
    }

    public abstract void scrape(WebDriver driver, File rootFolder);

    Logger getLogger() {
        return logger;
    }
}
