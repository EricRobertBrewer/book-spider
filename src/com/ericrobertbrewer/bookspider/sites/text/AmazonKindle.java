package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.WebDriverFactory;
import com.ericrobertbrewer.web.WebUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AmazonKindle extends SiteScraper {

    private final List<BookScrapeInfo> bookScrapeInfos;

    public AmazonKindle(Logger logger, List<BookScrapeInfo> bookScrapeInfos) {
        super(logger);
        this.bookScrapeInfos = bookScrapeInfos;
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force, String[] otherArgs, Launcher.Callback callback) {
        if (otherArgs.length < 2) {
            throw new IllegalArgumentException("Usage: <email> <password>");
        }
        final WebDriver driver = factory.newInstance();
        // Navigate to sign in page.
        final String email = otherArgs[0];
        final String password = otherArgs[1];
        driver.navigate().to("https://www.amazon.com");
        final WebElement signInA = driver.findElement(By.id("nav-link-accountList"));
        signInA.click();
        // Sign in.
        final WebElement emailInput = driver.findElement(By.id("ap_email"));
        emailInput.click();
        emailInput.sendKeys(email);
        final WebElement passwordInput = driver.findElement(By.id("ap_password"));
        passwordInput.click();
        passwordInput.sendKeys(password);
        final WebElement signInSubmitInput = driver.findElement(By.id("signInSubmit"));
        signInSubmitInput.click();
        // After signing in, adjust the window size to show only one column in the Kindle reader.
        driver.manage().window().setSize(new Dimension(719, 978));
        // Start scraping.
        scrapeBooks(driver, contentFolder, force);
        driver.quit();
        // Finish.
        callback.onComplete();
    }

    private void scrapeBooks(WebDriver driver, File contentFolder, boolean force) {
        for (BookScrapeInfo bookScrapeInfo : bookScrapeInfos) {
            scrape:
            for (String url : bookScrapeInfo.urls) {
                int retries = 3;
                while (retries > 0) {
                    try {
                        scrapeBook(bookScrapeInfo.id, url, driver, contentFolder, force);
                        break scrape;
                    } catch (IOException e) {
                        getLogger().log(Level.WARNING, "Encountered IOException while scraping book `" + bookScrapeInfo.id + "`.", e);
                    } catch (NoSuchElementException e) {
                        getLogger().log(Level.WARNING, "Unable to find element while scraping book `" + bookScrapeInfo.id + "`.", e);
                    } catch (Throwable t) {
                        getLogger().log(Level.WARNING, "Encountered unknown error while scraping book `" + bookScrapeInfo.id + "`.", t);
                    }
                    retries--;
                }
            }
        }
    }

    private void scrapeBook(String bookId, String url, WebDriver driver, File contentFolder, boolean force) throws IOException {
        // Create the book folder if it doesn't exist.
        final File bookFolder = new File(contentFolder, bookId);
        if (!bookFolder.exists() && !bookFolder.mkdirs()) {
            throw new IOException("Unable to create book folder for `" + bookId + "`.");
        }
        // Check if contents for this book already exist.
        final File file = new File(bookFolder, "text.txt");
        if (file.exists()) {
            if (force) {
                // `force`==`true`. Delete the existing file.
                if (!file.delete()) {
                    throw new IOException("Unable to delete existing book text file for `" + bookFolder.getName() + "` when `force`==`true`.");
                }
            } else {
                // `force`==`false`. Quit.
                return;
            }
        }
        // Navigate to the Amazon store page.
        getLogger().log(Level.INFO, "Scraping text for book `" + bookId + "`.");
        driver.navigate().to(url);
        // TODO: Check if the book is available through Kindle Unlimited. If so, click 'Read for Free'.
        // TODO: Check if the book is free. If so, "purchase" it.
        // Navigate to the Amazon Kindle page.
        // TODO: Check if the book has already been acquired through Kindle Unlimited. If so, click 'Read Now'.
        final WebElement readNowSpan = driver.findElement(By.id("dbs-goto-bookstore-rw"));
        readNowSpan.click();
        try {
            Thread.sleep(7500L);
        } catch (InterruptedException ignored) {
        }
        // Enter the first `iframe`.
        final WebElement kindleReaderFrame = driver.findElement(By.id("KindleReaderIFrame"));
        final WebDriver readerDriver = driver.switchTo().frame(kindleReaderFrame);
        // Close the 'Sync Position' dialog, if it's open.
        try {
            final WebElement syncPositionDiv = readerDriver.findElement(By.id("kindleReader_dialog_syncPosition"));
            // Click 'Make This the Furthest Read Location'.
            final WebElement resetButton = syncPositionDiv.findElement(By.id("kindleReader_dialog_syncPosition_reset_btn"));
            resetButton.click();
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ignored) {
            }
        } catch (WebDriverException ignored) {
        }
        // Find the main container.
        final WebElement bookContainerDiv = readerDriver.findElement(By.id("kindleReader_book_container"));
        // Find the navigation arrows.
        final WebElement touchLayerDiv = bookContainerDiv.findElement(By.id("kindleReader_touchLayer"));
        final WebElement sideMarginDiv = touchLayerDiv.findElement(By.id("kindleReader_sideMargin"));
        // Turn pages left as far as possible.
        while (true) {
            final WebElement pageTurnAreaLeftDiv = sideMarginDiv.findElement(By.id("kindleReader_pageTurnAreaLeft"));
            final String className = pageTurnAreaLeftDiv.getAttribute("class");
            if (!className.contains("pageArrow")) {
                break;
            }
            pageTurnAreaLeftDiv.click();
        }
        // Turn pages right while extracting content.
        final WebElement centerDiv = bookContainerDiv.findElement(By.id("kindleReader_center"));
        final Map<String, String> text = new HashMap<>();
        final Map<String, String> imgUrlToSrc = new HashMap<>();
        while (true) {
            final WebElement contentDiv = centerDiv.findElement(By.id("kindleReader_content"));
            // Extract the visible text.
            addVisibleContent(readerDriver, contentDiv, text, imgUrlToSrc);
            // Attempt to turn the page right.
            final WebElement pageTurnAreaRightDiv = sideMarginDiv.findElement(By.id("kindleReader_pageTurnAreaRight"));
            final String className = pageTurnAreaRightDiv.getAttribute("class");
            if (!className.contains("pageArrow")) {
                break;
            }
            pageTurnAreaRightDiv.click();
        }
        writeBook(file, text);
        saveImages(bookFolder, imgUrlToSrc);
    }

    private void addVisibleContent(WebDriver driver, WebElement element, Map<String, String> text, Map<String, String> imgUrlToSrc) {
        // Ignore hidden elements.
        final String visibility = element.getCssValue("visibility");
        if ("hidden".equals(visibility)) {
            return;
        }
        // Ignore elements which are not displayed.
        final String display = element.getCssValue("display");
        if ("none".equals(display)) {
            return;
        }
        // Check whether this textual element has already been scraped.
        final String id = element.getAttribute("id");
        if (text.containsKey(id)) {
            return;
        }
        // Return the visible text of all relevant children, if any exist.
        final List<WebElement> children = element.findElements(By.xpath("./*"));
        if (children.size() > 0 && !areAllFormatting(children)) {
            for (WebElement child : children) {
                addVisibleContent(driver, child, text, imgUrlToSrc);
            }
            return;
        }
        // Check for special tags.
        final String tag = element.getTagName();
        if ("iframe".equals(tag)) {
            // Return the visible text of the <body> element.
            final WebDriver frameDriver = driver.switchTo().frame(element);
            final WebElement body = frameDriver.findElement(By.tagName("body"));
            addVisibleContent(frameDriver, body, text, imgUrlToSrc);
            frameDriver.switchTo().parentFrame();
            return;
        } else if ("img".equals(tag)) {
            final String dataurl = element.getAttribute("dataurl");
            final String url = WebUtils.getLastUrlComponent(dataurl);
            if (imgUrlToSrc.containsKey(url)) {
                return;
            }
            final String src = element.getAttribute("src");
            imgUrlToSrc.put(url, src);
            return;
        }
        // Get this leaf-element's visible text.
        final String visibleText = element.getText().trim();
        if (visibleText.isEmpty()) {
            return;
        }
        getLogger().log(Level.INFO, "Found visible text in <" + tag + "> element with id=\"" + element.getAttribute("id") + "\": `" + visibleText + "`.");
        text.put(id, visibleText);
    }

    private void writeBook(File file, Map<String, String> text) throws IOException {
        final PrintStream out = new PrintStream(file);
        final String[] ids = new ArrayList<>(text.keySet()).stream()
                .sorted(TEXT_ID_COMPARATOR)
                .toArray(String[]::new);
        for (String id : ids) {
            final String line = text.get(id);
            out.println(line);
        }
        out.close();
    }

    private void saveImages(File bookFolder, Map<String, String> imgUrlToSrc) {
        for (String url : imgUrlToSrc.keySet()) {
            try {
                saveImage(bookFolder, url, imgUrlToSrc.get(url));
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Encountered problem while saving image `" + url + "`.", e);
            }
        }
    }

    private void saveImage(File bookFolder, String url, String src) throws IOException {
        final File imageFile = new File(bookFolder, url);
        // Check if image file already exists.
        if (imageFile.exists()) {
            return;
        }
        // Ensure that the `src` attribute is a data URI:
        // See `https://en.wikipedia.org/wiki/Data_URI_scheme#Syntax`.
        if (!src.startsWith("data:")) {
            return;
        }
        // Check for proper syntax.
        final int comma = src.indexOf(',');
        if (comma == -1) {
            return;
        }
        final String meta = src.substring(5, comma);
        final String data = src.substring(comma + 1);
        // Retrieve the meta data.
        final String[] metaParts = meta.split(";");
        final String mimeType = metaParts[0];
        String charset = "utf-8";
        boolean isBase64 = false;
        for (int i = 1; i < metaParts.length; i++) {
            if ("base64".equals(metaParts[i])) {
                isBase64 = true;
            } else if (metaParts[i].startsWith("charset=")) {
                charset = metaParts[i].substring(8);
            }
        }
        if (isBase64) {
            final byte[] bytes = Base64.getDecoder().decode(data);
            final FileOutputStream out = new FileOutputStream(imageFile);
            out.write(bytes);
        }
    }

    private static final Set<String> FORMATTING_TAGS = new HashSet<>();

    static {
        FORMATTING_TAGS.add("span");
        FORMATTING_TAGS.add("a");
        FORMATTING_TAGS.add("i");
    }

    private static boolean areAllFormatting(List<WebElement> elements) {
        return elements.stream()
                .map(WebElement::getTagName)
                .allMatch(FORMATTING_TAGS::contains);
    }

    private static final Map<Character, Integer> ID_CHAR_ORDINALITY = new HashMap<>();

    static {
        final String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < chars.length(); i++) {
            ID_CHAR_ORDINALITY.put(chars.charAt(i), i);
        }
    }

    private static final Comparator<String> TEXT_ID_COMPARATOR = (a, b) -> {
        // For example: `a:X` < `a:p7` < `a:j9`.
        final int aColon = a.indexOf(':');
        final int bColon = b.indexOf(':');
        // Check substrings before the colon.
        final String aBefore = a.substring(0, aColon);
        final String bBefore = b.substring(0, bColon);
        if (!aBefore.equals(bBefore)) {
            return aBefore.compareTo(bBefore);
        }
        // Compare substrings after the colon.
        final String aAfter = a.substring(aColon + 1);
        final String bAfter = b.substring(bColon + 1);
        // Shorter substrings have precedence.
        if (aAfter.length() != bAfter.length()) {
            return aAfter.length() - bAfter.length();
        }
        // Characters are significant from right to left.
        for (int i = 0; i < aAfter.length(); i++) {
            final char aChar = aAfter.charAt(aAfter.length() - 1 - i);
            final char bChar = bAfter.charAt(bAfter.length() - 1 - i);
            if (aChar != bChar) {
                return ID_CHAR_ORDINALITY.get(aChar) - ID_CHAR_ORDINALITY.get(bChar);
            }
        }
        // Strings are equal.
        return 0;
    };
}
