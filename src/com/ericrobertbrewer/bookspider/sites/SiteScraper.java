package com.ericrobertbrewer.bookspider.sites;

import org.openqa.selenium.WebDriver;

import java.io.File;
import java.util.logging.Logger;

public abstract class SiteScraper {

    public interface Creator {
        Class<? extends SiteScraper> getScraperClass();
        SiteScraper newInstance(Logger logger);
    }

    private final Logger logger;

    SiteScraper(Logger logger) {
        this.logger = logger;
    }

    /**
     * Scrape the content from a website.
     * @param driver Web driver.
     * @param contentFolder Root of the folder where content files will be written.
     * @param force When `true`, all web pages will be scraped whether or not the corresponding content file already exists.
     *              Else, only those pages whose content files do not exist will be scraped.
     *              Default is `false`.
     */
    public abstract void scrape(WebDriver driver, File contentFolder, boolean force);

    Logger getLogger() {
        return logger;
    }
}
