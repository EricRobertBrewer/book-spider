package com.ericrobertbrewer.bookspider;

import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.driver.ChromeDriverFactory;
import com.ericrobertbrewer.web.driver.WebDriverFactory;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Launcher {

    public interface Callback {
        void onComplete();
    }

    public static <T extends SiteScraper> void launch(String[] args, SiteScraper.Provider<T> provider) throws IOException {
        // Create web driver factory.
        final WebDriverFactory factory = new ChromeDriverFactory();
        // Create logger.
        final String id = provider.getId();
        final Logger logger = Logger.getLogger(provider.getClass().getSimpleName());
        final File logFile = Folders.getLogFile(id);
        final FileHandler fileHandler = new FileHandler(logFile.getPath());
        logger.addHandler(fileHandler);
        final Formatter formatter = new SimpleFormatter();
        fileHandler.setFormatter(formatter);
        // Create content folder.
        final File contentFolder = Folders.getOrMakeContentFolder(id);
        // Create site scraper.
        final T siteScraper = provider.newInstance(logger);
        System.out.println("Starting scrape...");
        siteScraper.scrape(factory, contentFolder, args, () -> {
            fileHandler.close();
            provider.onComplete(siteScraper);
            System.out.println("Done.");
        });
    }
}
