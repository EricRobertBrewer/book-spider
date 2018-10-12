package com.ericrobertbrewer.bookspider;

import com.ericrobertbrewer.bookspider.sites.NewYorkTimesFirstChapters;
import com.ericrobertbrewer.bookspider.sites.Site;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

import java.io.File;
import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 1) {
            throw new IllegalArgumentException("Usage: <driver-path>");
        }
        final String driverPath = args[0];
        System.setProperty("webdriver.chrome.driver", driverPath);
	    final WebDriver driver = new ChromeDriver();
	    final Site site = new NewYorkTimesFirstChapters();
	    final File rootFolder = new File(Folders.NY_TIMES);
	    if (!rootFolder.exists() && !rootFolder.mkdirs()) {
	        throw new IOException("Unable to create content directory: `" + rootFolder.getPath() + "`.");
        }
	    System.out.println("Starting scrape...");
	    site.scrape(driver, rootFolder);
	    driver.quit();
	    System.out.println("Done.");
    }
}
