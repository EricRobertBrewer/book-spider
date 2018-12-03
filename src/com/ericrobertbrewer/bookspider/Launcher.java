package com.ericrobertbrewer.bookspider;

import com.ericrobertbrewer.web.ChromeDriverFactory;
import com.ericrobertbrewer.web.WebDriverFactory;

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

    public static void launch(String[] args, SiteScraper.Provider provider) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: <driver-path> [force]");
        }
        // Create web driver factory.
        final String driverPath = args[0];
        ChromeDriverFactory.setDriverPath(driverPath);
        final WebDriverFactory factory = new ChromeDriverFactory();
        // Check `force` flag.
        final boolean force;
        if (args.length > 1) {
            force = Boolean.parseBoolean(args[1]);
        } else {
            force = false;
        }
        // Create logger.
        final String id = provider.getId();
        final Logger logger = Logger.getLogger(provider.getScraperClass().getSimpleName());
        final File logFile = Folders.getLogFile(id);
        final FileHandler fileHandler = new FileHandler(logFile.getPath());
        logger.addHandler(fileHandler);
        final Formatter formatter = new SimpleFormatter();
        fileHandler.setFormatter(formatter);
        // Create content folder.
        final File contentFolder = Folders.getContentFolder(id);
        // Create site scraper.
        final SiteScraper siteScraper = provider.newInstance(logger);
        System.out.println("Starting scrape...");
        siteScraper.scrape(factory, contentFolder, force, () -> {
            fileHandler.close();
            System.out.println("Done.");
        });
    }
}
