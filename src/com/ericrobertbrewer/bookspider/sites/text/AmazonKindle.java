package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.DriverUtils;
import com.ericrobertbrewer.web.WebDriverFactory;
import com.ericrobertbrewer.web.WebUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AmazonKindle extends SiteScraper {

    private final List<BookScrapeInfo> bookScrapeInfos;
    private final AtomicInteger scrapeThreadsRunning = new AtomicInteger(0);

    public AmazonKindle(Logger logger, List<BookScrapeInfo> bookScrapeInfos) {
        super(logger);
        this.bookScrapeInfos = bookScrapeInfos;
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force, String[] otherArgs, Launcher.Callback callback) {
        if (otherArgs.length < 2 || otherArgs.length > 3) {
            throw new IllegalArgumentException("Usage: <email> <password> [threads]");
        }
        // Collect arguments.
        final String email = otherArgs[0];
        final String password = otherArgs[1];
        final int threads;
        if (otherArgs.length > 2) {
            threads = Integer.parseInt(otherArgs[2]);
        } else {
            threads = 4;
        }
        // Create thread-safe queue.
        final Queue<BookScrapeInfo> queue = new ConcurrentLinkedQueue<>(bookScrapeInfos);
        // Start scraping.
        scrapeBooksThreaded(queue, threads, factory, contentFolder, email, password, force, callback);
    }

    private void scrapeBooksThreaded(Queue<BookScrapeInfo> queue, int threads, WebDriverFactory factory, File contentFolder, String email, String password, boolean force, Launcher.Callback callback) {
        for (int i = 0; i < threads; i++) {
            final Thread scrapeThread = new Thread(() -> {
                final WebDriver driver = factory.newInstance();
                // Ensure that only one column is shown in the Kindle reader.
                driver.manage().window().setSize(new Dimension(719, 978));
                signIn(driver, email, password);
                // Start scraping.
                scrapeThreadsRunning.incrementAndGet();
                scrapeBooks(queue, driver, contentFolder, email, password, force);
                driver.quit();
                // Finish.
                if (scrapeThreadsRunning.decrementAndGet() == 0) {
                    callback.onComplete();
                }
            });
            scrapeThread.start();
        }
    }

    private void signIn(WebDriver driver, String email, String password) {
        // Navigate to sign in page.
        driver.navigate().to("https://www.amazon.com");
        final WebElement signInA = driver.findElement(By.id("nav-link-accountList"));
        signInA.click();
        // Enter email.
        final WebElement emailInput = driver.findElement(By.id("ap_email"));
        emailInput.click();
        emailInput.sendKeys(email);
        // Enter password.
        final WebElement passwordInput = driver.findElement(By.id("ap_password"));
        passwordInput.click();
        passwordInput.sendKeys(password);
        // Click 'Keep me logged in' to avoid being logged out.
        final WebElement rememberMeSpan = driver.findElement(By.className("a-checkbox-label"));
        rememberMeSpan.click();
        // Submit.
        final WebElement signInSubmitInput = driver.findElement(By.id("signInSubmit"));
        signInSubmitInput.click();
    }

    private void scrapeBooks(Queue<BookScrapeInfo> queue, WebDriver driver, File contentFolder, String email, String password, boolean force) {
        while (!queue.isEmpty()) {
            final BookScrapeInfo bookScrapeInfo = queue.poll();
            boolean success = false;
            for (String url : bookScrapeInfo.urls) {
                if (success) {
                    break;
                }
                int retries = 3;
                while (retries > 0) {
                    try {
                        scrapeBook(bookScrapeInfo.id, url, driver, contentFolder, email, password, force);
                        success = true;
                        break;
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

    private void scrapeBook(String bookId, String url, WebDriver driver, File contentFolder, String email, String password, boolean force) throws IOException {
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
        final String asin = WebUtils.getLastUrlComponent(driver.getCurrentUrl());
        final WebElement aPageDiv;
        try {
            aPageDiv = driver.findElement(By.id("a-page"));
        } catch (NoSuchElementException e) {
            throw new NoSuchElementException("The Amazon page for book `" + bookId + "` may no longer exist. Retrying.");
        }
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        // Get this book's title.
        final WebElement booksTitleDiv = dpContainerDiv.findElement(By.id("booksTitle"));
        final WebElement ebooksProductTItle = booksTitleDiv.findElement(By.id("ebooksProductTitle"));
        final String title = ebooksProductTItle.getText().trim();
        // Navigate to this book's Amazon Kindle Cloud Reader page.
        final String readerUrl = "https://read.amazon.com/";
        // For example: `https://read.amazon.com/?asin=B07JK9Z14K`.
        final boolean isKindleUnlimited;
        if (isBookBorrowedThroughKindleUnlimited(dpContainerDiv)) {
            getLogger().log(Level.INFO, "Book already borrowed through Amazon Kindle. Navigating...");
            driver.navigate().to(readerUrl + "?asin=" + asin);
            isKindleUnlimited = true;
        } else if (borrowBookThroughKindleUnlimited(driver, dpContainerDiv)) {
            getLogger().log(Level.INFO, "Book successfully borrowed. Navigating...");
            driver.navigate().to(readerUrl + "?asin=" + asin);
            isKindleUnlimited = true;
        } else if (isBookFree() && purchaseBook()) {
            isKindleUnlimited = false;
        } else {
            isKindleUnlimited = false;
        }
        DriverUtils.sleep(9999L);
        // Enter the first `iframe`.
        WebElement kindleReaderFrame = null;
        int retries = 3;
        while (retries > 0 && kindleReaderFrame == null) {
            try {
                kindleReaderFrame = driver.findElement(By.id("KindleReaderIFrame"));
            } catch (NoSuchElementException ignored) {
            }
            retries--;
        }
        if (kindleReaderFrame == null) {
            throw new RuntimeException("Unable to find KindleReaderIFrame in document.");
        }
        final WebDriver readerDriver = driver.switchTo().frame(kindleReaderFrame);
        final Map<String, String> text = new HashMap<>();
        final Map<String, String> imgUrlToSrc = new HashMap<>();
        collectContent(readerDriver, text, imgUrlToSrc, email, password);
        writeBook(file, text);
        saveImages(bookFolder, imgUrlToSrc);
        // Return this book to avoid reaching the 10-book limit for Kindle Unlimited.
        // Hitting the limit prevents any other books from being borrowed through KU.
        if (isKindleUnlimited) {
            if (returnKindleUnlimitedBook(readerDriver, title, email, password)) {
                getLogger().log(Level.INFO, "Book `" + bookId + "` has been successfully returned through Kindle Unlimited.");
            } else {
                getLogger().log(Level.WARNING, "Unable to return book `" + bookId + "` through Kindle Unlimited.");
            }
        }
    }

    private boolean isBookBorrowedThroughKindleUnlimited(WebElement dpContainerDiv) {
        try {
            // Check if the book has already been acquired through Kindle Unlimited.
            dpContainerDiv.findElement(By.id("dbs-goto-bookstore-rw"));
            return true;
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private boolean borrowBookThroughKindleUnlimited(WebDriver driver, WebElement dpContainerDiv) {
        try {
            // Check if the book is available through Kindle Unlimited. If so, click 'Read for Free'.
            final WebElement borrowButton = dpContainerDiv.findElement(By.id("borrow-button"));
            getLogger().log(Level.INFO, "Borrowing book through Amazon Kindle. Clicking...");
            borrowButton.click();
            DriverUtils.sleep(5000L);
            driver.findElement(By.id("dbs-readnow-bookstore-rw"));
            return true;
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private boolean isBookFree() {
        return false;
    }

    private boolean purchaseBook() {
        return false;
    }

    private void collectContent(WebDriver readerDriver, Map<String, String> text, Map<String, String> imgUrlToSrc, String email, String password) {
        // Close the 'Sync Position' dialog, if it's open.
        try {
            final WebElement syncPositionDiv = readerDriver.findElement(By.id("kindleReader_dialog_syncPosition"));
            // Click 'Make This the Furthest Read Location'.
            final WebElement resetButton = syncPositionDiv.findElement(By.id("kindleReader_dialog_syncPosition_reset_btn"));
            resetButton.click();
            DriverUtils.sleep(1000L);
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
            // TODO: Try turning the page to the right; on failure, look for a sign-in prompt: `id=authportal-main-section`. Then sign in.
            pageTurnAreaLeftDiv.click();
        }
        // Turn pages right while extracting content.
        final WebElement centerDiv = bookContainerDiv.findElement(By.id("kindleReader_center"));
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
        // Ensure that the `src` attribute is a data URI.
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

    private boolean returnKindleUnlimitedBook(WebDriver driver, String title, String email, String password) {
        driver.navigate().to("https://www.amazon.com/hz/mycd/myx#/home/content/booksAll/dateDsc/");
        // TODO: Check for a sign-in prompt: `id=authportal-main-section`. Then sign in.
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement ngAppDiv = aPageDiv.findElement(By.id("ng-app"));
        final WebElement contentTableListDiv = ngAppDiv.findElement(By.className("contentTableList_myx"));
        final WebElement gridUl = contentTableListDiv.findElement(By.tagName("ul"));
        final List<WebElement> contentTableListRows = gridUl.findElements(By.className("contentTableListRow_myx"));
        for (int i = 0; i < contentTableListRows.size(); i++) {
            final WebElement contentTableListRow = contentTableListRows.get(i);
            final WebElement titleDiv = contentTableListRow.findElement(By.id("title" + i));
            final String titleText = titleDiv.getText().trim();
            if (title.equalsIgnoreCase(titleText)) {
                final WebElement button = contentTableListRow.findElement(By.tagName("button"));
                button.click();
                DriverUtils.sleep(2000L);
                final WebElement returnLoanDiv = contentTableListRow.findElement(By.id("contentAction_returnLoan_myx"));
                returnLoanDiv.click();
                final WebElement popoverModalDiv = driver.findElement(By.className("myx-popover-modal"));
                final WebElement okButton = popoverModalDiv.findElement(By.id("dialogButton_ok_myx "));  // Apparently there is a space there!
                okButton.click();
                return true;
            }
        }
        return false;
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
