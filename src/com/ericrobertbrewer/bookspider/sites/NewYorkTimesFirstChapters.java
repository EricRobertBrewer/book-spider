package com.ericrobertbrewer.bookspider.sites;

import org.openqa.selenium.WebDriver;

import java.io.*;
import java.util.logging.Level;

public class NewYorkTimesFirstChapters extends Site {

    public NewYorkTimesFirstChapters() {
        super();
    }

    @Override
    public void scrape(WebDriver driver, File rootFolder) {
        driver.navigate().to("https://archive.nytimes.com/www.nytimes.com/books/first/first-index.html");
        final File contentsFile = new File(rootFolder, "contents.tsv");
        final PrintWriter writer;
        try {
            writer = new PrintWriter(new FileWriter(contentsFile));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to write to file.", e);
            return;
        }
        writer.println("author\ttitle\turl");
        // TODO: Collect author, title, URL from <li> elements.
        // TODO: Navigate to each URL and collect the literature excerpt.
        writer.close();
    }
}
