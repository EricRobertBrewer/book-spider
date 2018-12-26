package com.ericrobertbrewer.bookspider.sites;

import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.web.driver.ChromeDriverFactory;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public abstract class SiteScraperTest<T extends SiteScraper> {

    private WebDriverFactory factory;
    private FileHandler fileHandler;
    private File contentFolder;
    private T scraper;

    protected abstract SiteScraper.Provider<T> getTestProvider();

    protected WebDriverFactory getFactory() {
        return factory;
    }

    protected File getContentFolder() {
        return contentFolder;
    }

    protected T getScraper() {
        return scraper;
    }

    @BeforeEach
    public void setUp() throws IOException {
        factory = new ChromeDriverFactory();
        final SiteScraper.Provider<T> provider = getTestProvider();
        final String id = provider.getId();
        final Logger logger = Logger.getLogger(provider.getClass().getSimpleName());
        final File logFile = Folders.getLogFile(id);
        fileHandler = new FileHandler(logFile.getPath());
        logger.addHandler(fileHandler);
        final Formatter formatter = new SimpleFormatter();
        fileHandler.setFormatter(formatter);
        contentFolder = Folders.makeContentFolder(id);
        scraper = provider.newInstance(logger);
    }

    @AfterEach
    public void tearDown() {
        fileHandler.close();
    }
}