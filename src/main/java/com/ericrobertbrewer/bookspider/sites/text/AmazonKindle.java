package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.WebUtils;
import com.ericrobertbrewer.web.driver.DriverUtils;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
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
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, Launcher.Callback callback) {
        if (args.length < 2 || args.length > 5) {
            throw new IllegalArgumentException("Usage: <email> <password> [threads] [max-retries] [force]");
        }
        // Collect arguments.
        final String email = args[0];
        final String password = args[1];
        final int threads;
        if (args.length > 2) {
            threads = Integer.parseInt(args[2]);
        } else {
            threads = 1;
        }
        final int maxRetries;
        if (args.length > 3) {
            maxRetries = Integer.parseInt(args[3]);
        } else {
            maxRetries = 1;
        }
        final boolean force;
        if (args.length > 4) {
            force = Boolean.parseBoolean(args[4]);
        } else {
            force = false;
        }
        // Create thread-safe queue.
        final Queue<BookScrapeInfo> queue = new ConcurrentLinkedQueue<>(bookScrapeInfos);
        // Start scraping.
        scrapeBooksThreaded(queue, threads, factory, contentFolder, email, password, maxRetries, force, callback);
    }

    private void scrapeBooksThreaded(Queue<BookScrapeInfo> queue, int threads, WebDriverFactory factory, File contentFolder, String email, String password, int maxRetries, boolean force, Launcher.Callback callback) {
        for (int i = 0; i < threads; i++) {
            final Thread scrapeThread = new Thread(() -> {
                final WebDriver driver = factory.newInstance();
                scrapeThreadsRunning.incrementAndGet();
                ensureReaderIsSingleColumn(driver);
                driver.manage().timeouts().implicitlyWait(1, TimeUnit.SECONDS);
                // Start scraping.
                scrapeBooks(queue, driver, contentFolder, email, password, maxRetries, force);
                driver.quit();
                // Finish.
                if (scrapeThreadsRunning.decrementAndGet() == 0) {
                    callback.onComplete();
                }
            });
            scrapeThread.start();
        }
    }

    /**
     * Ensure that only one column is shown in the Kindle reader.
     * @param driver        The web driver.
     */
    void ensureReaderIsSingleColumn(WebDriver driver) {
        driver.manage().window().setSize(new Dimension(719, 978));
    }

    private void scrapeBooks(Queue<BookScrapeInfo> queue, WebDriver driver, File contentFolder, String email, String password, int maxRetries, boolean force) {
        while (!queue.isEmpty()) {
            // Pull the next book off of the queue.
            final BookScrapeInfo bookScrapeInfo = queue.poll();
            // Get the folder which will hold this book's data.
            final File bookFolder;
            try {
                bookFolder = getBookFolder(contentFolder, bookScrapeInfo.id);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Unable to create book folder for `" + bookScrapeInfo.id + "`. Skipping.");
                continue;
            }
            // Check whether we can skip this book.
            if (!shouldScrapeBook(bookFolder, force)) {
                continue;
            }
            // Get the text file for this book.
            final File textFile;
            try {
                textFile = getTextFile(bookFolder, force);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Encountered IOException while accessing text file for book `" + bookScrapeInfo.id + "`.", e);
                continue;
            }
            // Try scraping the text for this book.
            for (String url : bookScrapeInfo.urls) {
                try {
                    getLogger().log(Level.INFO, "Processing book `" + bookScrapeInfo.id + "` using URL `" + url + "`.");
                    scrapeBook(bookScrapeInfo.id, url, driver, contentFolder, textFile, email, password, maxRetries);
                    break;
                } catch (NoSuchElementException e) {
                    getLogger().log(Level.WARNING, "Unable to find element while scraping book `" + bookScrapeInfo.id + "`.", e);
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Encountered unknown error while scraping book `" + bookScrapeInfo.id + "`.", t);
                }
            }
        }
    }

    private File getBookFolder(File contentFolder, String bookId) throws IOException {
        final File bookFolder = new File(contentFolder, bookId);
        // Create the book folder if it doesn't exist.
        if (!bookFolder.exists() && !bookFolder.mkdirs()) {
            throw new IOException("Unable to create book folder for `" + bookId + "`.");
        }
        return bookFolder;
    }

    private boolean shouldScrapeBook(File bookFolder, boolean force) {
        if (force) {
            return true;
        }
        final File file = new File(bookFolder, "text.txt");
        return !file.exists();
    }

    private File getTextFile(File bookFolder, boolean force) throws IOException {
        // Check if contents for this book already exist.
        final File file = new File(bookFolder, "text.txt");
        if (file.exists()) {
            if (force) {
                // Delete the existing file.
                if (!file.delete()) {
                    throw new IOException("Unable to delete existing book text file for `" + bookFolder.getName() + "` when `force`==`true`.");
                }
            }
        }
        return file;
    }

    private void scrapeBook(String bookId, String url, WebDriver driver, File bookFolder, File textFile, String email, String password, int maxRetries) throws IOException {
        // Navigate to the Amazon store page.
        driver.navigate().to(url);
        // Ensure that the page is valid.
        try {
            // This element won't exist if the book URL is no longer valid (404 error).
            driver.findElement(By.id("a-page"));
        } catch (NoSuchElementException e) {
            getLogger().log(Level.WARNING, "The Amazon page for book `" + bookId + "`. may no longer exist; started at `" + url + "`, ended at `" + driver.getCurrentUrl() + "`. Skipping.");
            return;
        }
        if (!isSignedIn(driver)) {
            navigateToSignInPage(driver);
            signIn(driver, email, password);
            scrapeBook(bookId, url, driver, bookFolder, textFile, email, password, maxRetries);
            return;
        }
        // Close the "Read this book for free with Kindle Unlimited" popover, if it appears.
        // See `https://www.amazon.com/dp/1980537615`.
        closeKindleUnlimitedPopoverIfVisible(driver);
        // Ensure that we have navigated to the Amazon store page for the Kindle version of the book.
        ensureKindleStorePage(driver);
        // Find the main container.
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        // Get this book's title. This may be used later to return a book borrowed through Kindle Unlimited.
        final WebElement centerColDiv = dpContainerDiv.findElement(By.id("centerCol"));
        final String title;
        try {
            final WebElement booksTitleDiv = centerColDiv.findElement(By.id("booksTitle"));
            final WebElement ebooksProductTitle = booksTitleDiv.findElement(By.id("ebooksProductTitle"));
            title = ebooksProductTitle.getText().trim();
        } catch (NoSuchElementException e) {
            getLogger().log(Level.WARNING, "Unable to find book title for `" + bookId + "`. Perhaps the paperback product page is shown instead of Kindle? Skipping.");
            return;
        }
        // Get this book's Amazon ID.
        // For example: `B07JK9Z14K`.
        // Used as: `https://read.amazon.com/?asin=<AMAZON_ID>`.
        final String asin = findAmazonId(driver);
        if (asin == null) {
            getLogger().log(Level.SEVERE, "Unable to find Amazon ID for book `" + bookId + "` within URL `" + url + "`. Skipping.");
            return;
        }
        // Navigate to this book's Amazon Kindle Cloud Reader page, if possible.
        final boolean isKindleUnlimited;
        final WebElement rightColDiv = dpContainerDiv.findElement(By.id("rightCol"));
        final WebElement buyboxDiv = rightColDiv.findElement(By.id("buybox"));
        if (isBookOwned(buyboxDiv)) {
            getLogger().log(Level.INFO, "Book `" + bookId + "` already owned. Navigating...");
            isKindleUnlimited = false;
        } else if (isBookBorrowedThroughKindleUnlimited(buyboxDiv)) {
            getLogger().log(Level.INFO, "Book `" + bookId + "` already borrowed through Amazon Kindle. Navigating...");
            isKindleUnlimited = true;
        } else if (canBorrowBookThroughKindleUnlimited(buyboxDiv)) {
            // Click 'Read for Free'
            final WebElement borrowButton = buyboxDiv.findElement(By.id("borrow-button"));
            borrowButton.click();
            try {
                // Check if the borrowing was successful.
                final WebDriverWait wait = new WebDriverWait(driver, 5);
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("dbs-readnow-bookstore-rw")));
                getLogger().log(Level.INFO, "Book `" + bookId + "` successfully borrowed. Navigating...");
            } catch (NoSuchElementException e) {
                // We were unable to borrow the book. Probably the 10-book limit is met.
                getLogger().log(Level.WARNING, "Unable to borrow book `" + bookId + "`. Has the 10-book limit been met? Skipping.");
                return;
            }
            isKindleUnlimited = true;
        } else if (isBookFree(buyboxDiv)) {
            getLogger().log(Level.INFO, "Book `" + bookId + "` is free on Kindle. Purchasing...");
            // "Purchase" the book.
            final WebElement buyOneClickForm = buyboxDiv.findElement(By.id("buyOneClick"));
            final WebElement checkoutButtonIdSpan = buyOneClickForm.findElement(By.id("checkoutButtonId"));
            checkoutButtonIdSpan.click();
            // Since it's not part of the Kindle Unlimited collection, it does not have to be returned.
            isKindleUnlimited = false;
        } else {
            getLogger().log(Level.INFO, "Book `" + bookId + "` is neither free nor available through Kindle Unlimited. Skipping.");
            return;
        }
        // Start collecting content.
        getLogger().log(Level.INFO, "Starting to collect content for book `" + bookId + "`");
        final Map<String, String> text = new HashMap<>();
        final Map<String, String> imgUrlToSrc = new HashMap<>();
        try {
            navigateToReaderPage(driver, asin);
            collectContentWithRetries(driver, bookId, asin, text, imgUrlToSrc, email, password, maxRetries);
            // Check whether any content has been extracted.
            if (text.size() > 0) {
                // Persist content once it has been totally collected.
                getLogger().log(Level.INFO, "Writing text for book `" + bookId + "`.");
                writeBook(textFile, text);
                getLogger().log(Level.INFO, "Saving images for book `" + bookId + "`.");
                saveImages(bookFolder, imgUrlToSrc);
                getLogger().log(Level.INFO, "Successfully collected and saved content for book `" + bookId + "`.");
            } else {
                getLogger().log(Level.WARNING, "Unable to extract any content for book `" + bookId + "` after " + maxRetries + " retries. Quitting.");
            }
        } finally {
            // Return this book to avoid reaching the 10-book limit for Kindle Unlimited.
            // Hitting the limit prevents any other books from being borrowed through KU.
            if (isKindleUnlimited) {
                if (returnKindleUnlimitedBook(driver, title, email, password)) {
                    getLogger().log(Level.INFO, "Book `" + bookId + "` has been successfully returned through Kindle Unlimited.");
                } else {
                    getLogger().log(Level.WARNING, "Unable to return book `" + bookId + "` through Kindle Unlimited.");
                }
            }
        }
    }

    private boolean isSignedIn(WebDriver driver) {
        final WebElement navbarDiv = driver.findElement(By.id("navbar"));
        final WebElement signInA = navbarDiv.findElement(By.id("nav-link-accountList"));
        final String aText = signInA.getText().trim();
        return !aText.startsWith("Hello. Sign in");
    }

    /**
     * Only works when called from Amazon store page.
     * @param driver        The web driver.
     */
    private void navigateToSignInPage(WebDriver driver) {
        final WebElement navbarDiv = driver.findElement(By.id("navbar"));
        final WebElement signInA = navbarDiv.findElement(By.id("nav-link-accountList"));
        final String href = signInA.getAttribute("href").trim();
        driver.navigate().to(href);
    }

    /**
     * Sign in to Amazon.
     * Works only if you have already navigated to the sign-in page (`https://www.amazon.com/ap/signin?...`).
     * @param driver        The web driver.
     * @param email         Email.
     * @param password      Password.
     */
    private void signIn(WebDriver driver, String email, String password) {
        final WebElement mainSectionDiv = driver.findElement(By.id("authportal-main-section"));
        // Enter email.
        final WebElement emailInput = mainSectionDiv.findElement(By.id("ap_email"));
        emailInput.click();
        emailInput.sendKeys(email);
        // Enter password.
        final WebElement passwordInput = mainSectionDiv.findElement(By.id("ap_password"));
        passwordInput.click();
        passwordInput.sendKeys(password);
        // Click 'Keep me logged in' to avoid being logged out.
        final WebElement rememberMeSpan = mainSectionDiv.findElement(By.className("a-checkbox-label"));
        rememberMeSpan.click();
        // Submit.
        final WebElement signInSubmitInput = mainSectionDiv.findElement(By.id("signInSubmit"));
        signInSubmitInput.click();
    }

    private void closeKindleUnlimitedPopoverIfVisible(WebDriver driver) {
        try {
            final WebElement aModalScrollerDiv = driver.findElement(By.className("a-modal-scroller"));
            final WebElement noButton = aModalScrollerDiv.findElement(By.id("p2dPopoverID-no-button"));
            noButton.click();
        } catch (NoSuchElementException ignored) {
            // It usually doesn't appear.
        }
    }

    /**
     * Ensure that we're looking at the Kindle store page, not the paperback store page.
     * For example, `https://mybookcave.com/t/?u=0&b=94037&r=86&sid=mybookcave&utm_campaign=MBR+site&utm_source=direct&utm_medium=website`.
     * This may cause the driver to navigate to a different page.
     * To avoid `StaleElementReferenceException`s, invoke this method before finding elements via the web driver.
     * @param driver        The web driver.
     */
    private void ensureKindleStorePage(WebDriver driver) {
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        final WebElement centerColDiv = dpContainerDiv.findElement(By.id("centerCol"));
        final WebElement mediaMatrixDiv = centerColDiv.findElement(By.id("MediaMatrix"));
        final WebElement formatsDiv = mediaMatrixDiv.findElement(By.id("formats"));
        final WebElement tmmSwatchesDiv = formatsDiv.findElement(By.id("tmmSwatches"));
        final WebElement ul = tmmSwatchesDiv.findElement(By.tagName("ul"));
        final List<WebElement> lis = ul.findElements(By.tagName("li"));
        for (WebElement li : lis) {
            final String text = li.getText().trim();
            if (!text.startsWith("Kindle")) {
                continue;
            }
            final String className = li.getAttribute("class");
            if (className.contains("unselected")) {
                // The 'Kindle' media item is unselected. We're probably looking at the wrong store page.
                final WebElement a = li.findElement(By.tagName("a"));
                // Navigate to the Kindle store page.
                final String href = a.getAttribute("href").trim();
                driver.navigate().to(href);
            }
            // Whether we've navigated to the Kindle store page or we're already there, stop looking for the 'Kindle' item.
            return;
        }
    }

    private String findAmazonId(WebDriver driver) {
        final String url = driver.getCurrentUrl();
        final String[] components = url.split("[/?]");
        for (int i = 0; i < components.length - 1; i++) {
            if ("dp".equals(components[i])) {
                return components[i + 1];
            }
        }
        return null;
    }

    private boolean isBookOwned(WebElement buyboxDiv) {
        try {
            // Check if the book has already been purchased.
            final WebElement readNowDescriptionTextSpan = buyboxDiv.findElement(By.id("read-now-description-text"));
            final String readNowDescriptionText = readNowDescriptionTextSpan.getText().trim();
            return readNowDescriptionText.startsWith("You already own this item");
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private boolean isBookBorrowedThroughKindleUnlimited(WebElement buyboxDiv) {
        try {
            // Check if the book has already been acquired through Kindle Unlimited.
            final WebElement readNowDescriptionTextSpan = buyboxDiv.findElement(By.id("read-now-description-text"));
            final String readNowDescriptionText = readNowDescriptionTextSpan.getText().trim();
            return readNowDescriptionText.startsWith("You already borrowed this item");
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private boolean canBorrowBookThroughKindleUnlimited(WebElement buyboxDiv) {
        try {
            // Check if the book is available through Kindle Unlimited.
            buyboxDiv.findElement(By.id("borrow-button"));
            return true;
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private boolean isBookFree(WebElement buyboxDiv) {
        final WebElement buyTable = buyboxDiv.findElement(By.tagName("table"));
        final List<WebElement> trs = buyTable.findElements(By.tagName("tr"));
        for (WebElement tr : trs) {
            final List<WebElement> tds = tr.findElements(By.tagName("td"));
            if (tds.size() < 2) {
                continue;
            }
            final String td0Text = tds.get(0).getText().trim();
            if (!"Kindle Price:".equalsIgnoreCase(td0Text)) {
                continue;
            }
            final String td1Text = tds.get(1).getText().trim();
            return td1Text.startsWith("$0.00");
        }
        return false;
    }

    private void navigateToReaderPage(WebDriver driver, String asin) {
        driver.navigate().to("https://read.amazon.com/?asin=" + asin);
    }

    private void collectContentWithRetries(WebDriver driver, String bookId, String asin, Map<String, String> text, Map<String, String> imgUrlToSrc, String email, String password, int maxRetries) {
        // Catch exceptions the first few times...
        int retries = maxRetries;
        while (retries > 1) {
            try {
                collectContent(driver, asin, text, imgUrlToSrc, email, password, retries == maxRetries);
                if (text.size() > 0) {
                    return;
                } else {
                    // Occasionally, the text content hasn't been loaded into the page and this method will
                    // suppose that it is finished. In this case, pause, then try again.
                    getLogger().log(Level.WARNING, "`collectContent` for book `" + bookId + "` completed without failing or extracting any text. " + retries + " retries left. Pausing, then retrying...");
                }
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Encountered error while collecting content for book `" + bookId + "`. Retrying.", t);
            }
            retries--;
        }
        // Then fail the last time.
        collectContent(driver, asin, text, imgUrlToSrc, email, password, false);
    }

    private void collectContent(WebDriver driver, String asin, Map<String, String> text, Map<String, String> imgUrlToSrc, String email, String password, boolean fromStart) {
        // Enter the first `iframe`.
        final WebElement kindleReaderFrame = DriverUtils.findElementWithRetries(driver, By.id("KindleReaderIFrame"), 7, 2500L);
        final WebDriver readerDriver = driver.switchTo().frame(kindleReaderFrame);
        // Close the 'Sync Position' dialog, if it's open.
        try {
            final WebElement syncPositionDiv = readerDriver.findElement(By.id("kindleReader_dialog_syncPosition"));
            // Click 'Make This the Furthest Read Location'.
            final WebElement resetButton = syncPositionDiv.findElement(By.id("kindleReader_dialog_syncPosition_reset_btn"));
            resetButton.click();
        } catch (WebDriverException ignored) {
        }
        // Find the main container.
        final WebElement bookContainerDiv = readerDriver.findElement(By.id("kindleReader_book_container"));
        // Find the navigation arrows.
        final WebElement touchLayerDiv = DriverUtils.findElementWithRetries(bookContainerDiv, By.id("kindleReader_touchLayer"), 3, 2500L);
        final WebElement sideMarginDiv = touchLayerDiv.findElement(By.id("kindleReader_sideMargin"));
        if (fromStart) {
            // Turn pages left as far as possible.
            while (true) {
                final WebElement pageTurnAreaLeftDiv = sideMarginDiv.findElement(By.id("kindleReader_pageTurnAreaLeft"));
                final String className = pageTurnAreaLeftDiv.getAttribute("class");
                if (!className.contains("pageArrow")) {
                    break;
                }
                pageTurnAreaLeftDiv.click();
            }
        }
        // Turn pages right while extracting content.
        final WebElement centerDiv = bookContainerDiv.findElement(By.id("kindleReader_center"));
        final long startTime = System.currentTimeMillis();
        int pages = 0;
        while (true) {
            pages++;
            try {
                final WebElement contentDiv = centerDiv.findElement(By.id("kindleReader_content"));
                // Extract the visible text on this page.
                addVisibleContent(readerDriver, contentDiv, text, imgUrlToSrc);
                // Attempt to turn the page right.
                final WebElement pageTurnAreaRightDiv = sideMarginDiv.findElement(By.id("kindleReader_pageTurnAreaRight"));
                final String className = pageTurnAreaRightDiv.getAttribute("class");
                if (!className.contains("pageArrow")) {
                    final long totalTime = System.currentTimeMillis() - startTime;
                    getLogger().log(Level.INFO, "Finished collecting content; " + pages + " page" + (pages > 1 ? "s" : "") + " turned; " + totalTime + " total ms elapsed; " + (totalTime / pages) + " average ms elapsed per page.");
                    break;
                }
                pageTurnAreaRightDiv.click();
            } catch (StaleElementReferenceException e) {
                // Check to see if we have been signed out automatically.
                final String url = driver.getCurrentUrl();
                if (url.startsWith("https://www.amazon.com/ap/signin")) {
                    // If so, sign in again and continue collecting content from the same position in the reader.
                    signIn(driver, email, password);
                    navigateToReaderPage(driver, asin);
                    collectContent(driver, asin, text, imgUrlToSrc, email, password, false);
                }
                return;
            }
        }
    }

    private void addVisibleContent(WebDriver driver, WebElement element, Map<String, String> text, Map<String, String> imgUrlToSrc) {
        // Check whether this textual element has already been scraped.
        final String id = element.getAttribute("id");
        if (text.containsKey(id)) {
            return;
        }
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
        if (!id.contains(":")) {
            getLogger().log(Level.WARNING, "Found <" + tag + "> element with non-standard ID `" + id + "` at `" + driver.getCurrentUrl() + "` with text `" + visibleText + "`. Skipping");
            return;
        }
        text.put(id, visibleText);
    }

    private void writeBook(File file, Map<String, String> text) throws IOException {
        final String[] ids = new ArrayList<>(text.keySet()).stream()
                .sorted(TEXT_ID_COMPARATOR)
                .toArray(String[]::new);
        final PrintStream out = new PrintStream(file);
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
        // Retrieve the MIME type.
        final String[] metaParts = meta.split(";");
        final String mimeType = metaParts[0];
        // Ensure proper file extension.
        final String fileName;
        if (url.contains(".")) {
            fileName = url;
        } else if ("image/jpeg".equalsIgnoreCase(mimeType) || "image/jpg".equals(mimeType)) {
            fileName = url + ".jpg";
        } else if ("image/png".equalsIgnoreCase(mimeType)) {
            fileName = url + ".png";
        } else if ("image/gif".equalsIgnoreCase(mimeType)) {
            fileName = url + ".gif";
        } else if ("image/svg+xml".equalsIgnoreCase(mimeType)) {
            fileName = url + ".svg";
        } else if ("image/bmp".equalsIgnoreCase(mimeType)) {
            fileName = url + ".bmp";
        } else {
            getLogger().log(Level.WARNING, "Found unknown MIME type `" + mimeType + "` for image `" + url + "` for book `" + bookFolder.getName() + "`.");
            fileName = url;
        }
        final File imageFile = new File(bookFolder, fileName);
        // Check if image file already exists.
        if (imageFile.exists()) {
            return;
        }
        // Retrieve the other meta data.
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
            final byte[] inBytes = data.getBytes(charset);
            final byte[] outBytes = Base64.getDecoder().decode(inBytes);
            final FileOutputStream out = new FileOutputStream(imageFile);
            out.write(outBytes);
        }
    }

    boolean returnKindleUnlimitedBook(WebDriver driver, String title, String email, String password) {
        // Navigate to the 'Content and Devices' account page, showing only borrowed books.
        driver.navigate().to("https://www.amazon.com/hz/mycd/myx#/home/content/booksBorrows/dateDsc/");
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement ngAppDiv;
        try {
            ngAppDiv = aPageDiv.findElement(By.id("ng-app"));
        } catch (NoSuchElementException e) {
            // Check to see if we have been signed out automatically.
            final String url = driver.getCurrentUrl();
            if (url.startsWith("https://www.amazon.com/ap/signin")) {
                // If so, sign in again and try to return the book again.
                signIn(driver, email, password);
                return returnKindleUnlimitedBook(driver, title, email, password);
            }
            return false;
        }
         // Return the book with the given title by clicking [...] (Actions column) -> [Return book] -> [Yes]
        final WebElement contentAppDiv = DriverUtils.findElementWithRetries(ngAppDiv, By.className("contentApp_myx"), 3, 2500L);
        final WebElement contentContainerDiv = contentAppDiv.findElement(By.className("contentContainer_myx"));
        final WebElement contentTableListDiv = contentContainerDiv.findElement(By.className("contentTableList_myx"));
        final WebElement gridUl = contentTableListDiv.findElement(By.tagName("ul"));
        final List<WebElement> lis = gridUl.findElements(By.tagName("li"));
        for (int i = 0; i < lis.size(); i++) {
            final WebElement li = lis.get(i);
            final WebElement titleDiv = li.findElement(By.id("title" + i));
            final String titleText = titleDiv.getText().trim();
            if (title.equalsIgnoreCase(titleText)) {
                final WebElement button = li.findElement(By.tagName("button"));
                button.click(); // [...]
                final Wait<WebElement> wait = new FluentWait<>(li)
                        .withTimeout(Duration.ofMillis(3000L))
                        .pollingEvery(Duration.ofMillis(1000L))
                        .ignoring(NoSuchElementException.class);
                final WebElement returnLoanDiv = wait.until(e -> e.findElement(By.id("contentAction_returnLoan_myx")));
                returnLoanDiv.click(); // [Return book]
                final WebElement popoverModalDiv = driver.findElement(By.className("myx-popover-modal"));
                final WebElement okButton = popoverModalDiv.findElement(By.id("dialogButton_ok_myx "));  // Apparently there is a space there!
                okButton.click(); // [Yes]
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
        FORMATTING_TAGS.add("b");
        FORMATTING_TAGS.add("s");
        FORMATTING_TAGS.add("br");
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
