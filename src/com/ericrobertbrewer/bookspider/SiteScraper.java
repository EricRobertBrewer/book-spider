package com.ericrobertbrewer.bookspider;

import com.ericrobertbrewer.web.WebDriverFactory;

import java.io.File;
import java.util.logging.Logger;

public abstract class SiteScraper {

    public interface Provider {
        Class<? extends SiteScraper> getScraperClass();
        SiteScraper newInstance(Logger logger);
        String getId();
    }

    protected static String getLastUrlComponent(String url) {
        if (url.endsWith("/")) {
            return getLastUrlComponent(url.substring(0, url.length() - 1));
        }
        return url.substring(url.lastIndexOf("/") + 1);
    }

    private final Logger logger;

    protected SiteScraper(Logger logger) {
        this.logger = logger;
    }

    /**
     * Scrape the content from a website.
     * @param factory Web driver factory.
     * @param contentFolder Root of the folder where content files will be written.
     * @param force When `true`, all web pages will be scraped whether or not the corresponding content file already exists.
     *              Else, only those pages whose content files do not exist will be scraped.
     *              Default is `false`.
     * @param callback Used to notify the Launcher that scraping is complete, so that it can close and release resources.
     */
    public abstract void scrape(WebDriverFactory factory, File contentFolder, boolean force, Launcher.Callback callback);

    protected Logger getLogger() {
        return logger;
    }
}
