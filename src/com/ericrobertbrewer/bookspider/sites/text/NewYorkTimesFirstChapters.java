package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.DriverUtils;
import com.ericrobertbrewer.web.WebDriverFactory;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NewYorkTimesFirstChapters extends SiteScraper {

    public static void main(String[] args) throws IOException {
        Launcher.launch(args, new Provider() {
            @Override
            public Class<? extends SiteScraper> getScraperClass() {
                return NewYorkTimesFirstChapters.class;
            }

            @Override
            public SiteScraper newInstance(Logger logger) {
                return new NewYorkTimesFirstChapters(logger);
            }

            @Override
            public String getId() {
                return Folders.ID_NYTIMES;
            }
        });
    }

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
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force, String[] otherArgs, Launcher.Callback callback) {
        getLogger().log(Level.INFO, "Scraping New York Times first chapters.");
        final WebDriver driver = factory.newInstance();
        // Set timeout for obsolete API call (to
        // `http://barnesandnoble.bfast.com/booklink/serve?sourceid=4773&categoryid=nytsearch`).
        driver.manage().timeouts().setScriptTimeout(1000L, TimeUnit.MILLISECONDS);
//        driver.manage().timeouts().implicitlyWait(1000L, TimeUnit.MILLISECONDS);
//        driver.manage().timeouts().pageLoadTimeout(1000L, TimeUnit.MILLISECONDS);
        // Navigate to home page.
        driver.navigate().to("https://archive.nytimes.com/www.nytimes.com/books/first/first-index.html");
        // Scroll the page.
        DriverUtils.scrollDown(driver, 100, 25L);
        // Create the contents file.
        // Delete it when `force`==`true`.
        final File contentsFile = new File(contentFolder, "contents.tsv");
        if (contentsFile.exists()) {
            if (force) {
                if (!contentsFile.delete()) {
                    getLogger().log(Level.SEVERE, "Unable to delete contents file `" + contentsFile.getPath() + "` while `force`==`true`.");
                    return;
                } else {
                    getLogger().log(Level.INFO, "Deleted contents file `" + contentsFile.getPath() + "` while `force`==`true`.");
                }
            }
        }
        // Create the PrintWriter.
        final boolean exists = contentsFile.exists();
        final PrintWriter contentsWriter;
        try {
            contentsWriter = new PrintWriter(new FileWriter(contentsFile, exists));
            if (!exists) {
                contentsWriter.println("author\ttitle\tfile\turl");
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to write to contents file.", e);
            return;
        }
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
            scrapeBook(driver, contentFolder, bookItem, contentsWriter, force);
        }
        contentsWriter.close();
        driver.quit();
        callback.onComplete();
    }

    private void scrapeBook(WebDriver driver, File contentFolder, BookItem bookItem, PrintWriter contentsWriter, boolean force) {
        final String fileName = bookItem.getTextFileName();
        final File file = new File(contentFolder, fileName);
        if (file.exists()) {
            if (force) {
                if (!file.delete()) {
                    getLogger().log(Level.SEVERE, "Unable to delete file `" + file.getPath() + "` while `force`==`true`.");
                    return;
                } else {
                    getLogger().log(Level.INFO, "Deleted file `" + file.getPath() + "` while `force`==`true`.");
                }
            } else {
                getLogger().log(Level.INFO, "Skipping scrape because of existing file `" + file.getPath() + "` while `force`==`false`.");
                return;
            }
        }
        getLogger().log(Level.INFO, "Scraping `" + bookItem.author + ": " + bookItem.title + "`.");
        driver.navigate().to(bookItem.url);
        DriverUtils.scrollDown(driver, 60, 25L);
        try {
            writeBook(driver, file);
            contentsWriter.println(bookItem.author + "\t" + bookItem.title + "\t" + fileName + "\t" + bookItem.url);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to write to book file `" + file.getPath() + "`.", e);
        }
    }

    private void writeBook(WebDriver driver, File file) throws IOException {
        final PrintWriter writer = new PrintWriter(new FileWriter(file));
        final List<WebElement> ps = driver.findElements(By.tagName("p"));
        boolean hasReachedText = false;
        for (WebElement p : ps) {
            final String text = p.getText().trim();
            // Skip empty lines.
            if (text.length() == 0) {
                continue;
            }
            // Ignore meta data above review link.
            if (!hasReachedText) {
                if ("Read the Review".equalsIgnoreCase(text)) {
                    hasReachedText = true;
                }
                continue;
            }
            // Detect end of content.
            if (text.contains("(C)") || text.contains("ISBN:")) {
                break;
            }
            writer.println(text);
        }
        writer.close();
    }
}
