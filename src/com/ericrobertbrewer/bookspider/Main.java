package com.ericrobertbrewer.bookspider;

import com.ericrobertbrewer.bookspider.sites.NewYorkTimesFirstChapters;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 1) {
            throw new IllegalArgumentException("Usage: <driver-path>");
        }
        // Create web driver.
        final String driverPath = args[0];
        System.setProperty("webdriver.chrome.driver", driverPath);
	    final WebDriver driver = new ChromeDriver();
	    // Create logger.
	    final String id = Folders.ID_NY_TIMES;
		final Logger logger = Logger.getLogger(NewYorkTimesFirstChapters.class.getSimpleName());
		final File logFile = Folders.getLogFile(id);
		final FileHandler fileHandler = new FileHandler(logFile.getPath());
		logger.addHandler(fileHandler);
		final Formatter formatter = new SimpleFormatter();
		fileHandler.setFormatter(formatter);
		// Create content folder.
	    final File contentFolder = Folders.getContentFolder(id);
		// Create site scraper.
		final SiteScraper siteScraper = new NewYorkTimesFirstChapters(logger);
	    System.out.println("Starting scrape...");
	    siteScraper.scrape(driver, contentFolder);
	    driver.quit();
	    fileHandler.close();
	    System.out.println("Done.");
    }
}
