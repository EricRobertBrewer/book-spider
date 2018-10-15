package com.ericrobertbrewer.bookspider.sites;

import com.ericrobertbrewer.web.DriverUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NewYorkTimesFirstChapters extends SiteScraper {

    public NewYorkTimesFirstChapters(Logger logger) {
        super(logger);
    }

    private static class BookItem {
        final String author;
        final String title;
        final String url;

        BookItem(String author, String title, String url) {
            this.author = author;
            this.title = title;
            this.url = url;
        }

        String getTextFileName() {
            final int lastSlashIndex = url.lastIndexOf("/");
            final int lastDotIndex = url.lastIndexOf(".");
            return url.substring(lastSlashIndex + 1, lastDotIndex) + ".txt";
        }
    }

    @Override
    public void scrape(WebDriver driver, File contentFolder) {
        // Set timeout for obsolete API call (to
        // `http://barnesandnoble.bfast.com/booklink/serve?sourceid=4773&categoryid=nytsearch`).
        driver.manage().timeouts().setScriptTimeout(1000L, TimeUnit.MILLISECONDS);
//        driver.manage().timeouts().implicitlyWait(1000L, TimeUnit.MILLISECONDS);
//        driver.manage().timeouts().pageLoadTimeout(1000L, TimeUnit.MILLISECONDS);
        // Navigate to home page.
        driver.navigate().to("https://archive.nytimes.com/www.nytimes.com/books/first/first-index.html");
        getLogger().log(Level.INFO, "Starting to scrape New York Times first chapters.");
        // Scroll the page.
        DriverUtils.scrollDown(driver, 100, 25L);
        // Create the contents file
        final File contentsFile = new File(contentFolder, "contents.tsv");
        final PrintWriter contentsWriter;
        try {
            contentsWriter = new PrintWriter(new FileWriter(contentsFile));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to write to contents file.", e);
            return;
        }
        contentsWriter.println("author\ttitle\tfile\turl");
        final List<BookItem> bookItems = new ArrayList<>();
        final List<WebElement> lis = driver.findElements(By.tagName("li"));
        for (WebElement li : lis) {
            final String text = li.getText();
            if (!text.contains(":")) {
                continue;
            }
            try {
                final WebElement a = li.findElement(By.tagName("a"));
                // For example, `https://www.nytimes.com/books/first/a/abbott-wet.html`.
                final String url = a.getAttribute("href").trim();
                if (!url.startsWith("https://www.nytimes.com/books/first/")) {
                    continue;
                }
                final String author = text.substring(0, text.indexOf(":")).trim();
                final String title = a.getText().trim();
                bookItems.add(new BookItem(author, title, url));
            } catch (NoSuchElementException e) {
                getLogger().log(Level.INFO, "Anchor <a> element does not exist in list item <li> element.", e);
            }
        }
        for (BookItem bookItem : bookItems) {
            scrapeBook(driver, contentFolder, bookItem, contentsWriter);
        }
        contentsWriter.close();
    }

    private void scrapeBook(WebDriver driver, File rootFolder, BookItem bookItem, PrintWriter contentsWriter) {
        driver.navigate().to(bookItem.url);
        getLogger().log(Level.INFO, "Scraping `" + bookItem.author + ": " + bookItem.title + "`.");
        DriverUtils.scrollDown(driver, 60, 25L);
        final String fileName = bookItem.getTextFileName();
        final File file = new File(rootFolder, fileName);
        try {
            writeBook(driver, file);
            contentsWriter.println(bookItem.author + "\t" + bookItem.title + "\t" + fileName + "\t" + bookItem.url);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to write to book file.", e);
        }
    }

    private void writeBook(WebDriver driver, File file) throws IOException {
        final PrintWriter writer = new PrintWriter(new FileWriter(file));
        final List<WebElement> ps = driver.findElements(By.tagName("p"));
        boolean hasReachedText = false;
        for (WebElement p : ps) {
            final String text = p.getText().trim();
            if (text.length() == 0) {
                continue;
            }
            if ("Read the Review".equalsIgnoreCase(text)) {
                hasReachedText = true;
                continue;
            }
            if (!hasReachedText) {
                continue;
            }
            if (text.contains("(C)") || text.contains("ISBN:")) {
                break;
            }
            writer.println(text);
        }
        writer.close();
    }
}
