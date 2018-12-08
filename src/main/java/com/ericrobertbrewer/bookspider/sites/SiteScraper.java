package com.ericrobertbrewer.bookspider.sites;

import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.web.driver.WebDriverFactory;

import java.io.File;
import java.util.logging.Logger;

public abstract class SiteScraper {

    public interface Provider<T extends SiteScraper> {
        Class<T> getScraperClass();

        T newInstance(Logger logger);

        String getId();
    }

    private final Logger logger;

    protected SiteScraper(Logger logger) {
        this.logger = logger;
    }

    /**
     * Scrape the content from a website.
     *
     * @param factory       Web driver factory.
     * @param contentFolder Root of the folder where content files will be written.
     * @param args          Any additional arguments for this scraping session.
     * @param callback      Used to notify the Launcher that scraping is complete, so that it can close and release resources.
     */
    public abstract void scrape(WebDriverFactory factory, File contentFolder, String[] args, Launcher.Callback callback);

    protected Logger getLogger() {
        return logger;
    }
}
