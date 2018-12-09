package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.WebUtils;
import com.ericrobertbrewer.web.driver.DriverUtils;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
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
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, Launcher.Callback callback) {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Usage: <email> <password> [threads] [force]");
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
        final boolean force;
        if (args.length > 3) {
            force = Boolean.parseBoolean(args[3]);
        } else {
            force = false;
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
                scrapeThreadsRunning.incrementAndGet();
                ensureReaderIsSingleColumn(driver);
                navigateToSignInPage(driver);
                signIn(driver, email, password);
                // Start scraping.
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

    /**
     * Ensure that only one column is shown in the Kindle reader.
     * @param driver        The web driver.
     */
    void ensureReaderIsSingleColumn(WebDriver driver) {
        driver.manage().window().setSize(new Dimension(719, 978));
    }

    void navigateToSignInPage(WebDriver driver) {
        driver.navigate().to("https://www.amazon.com");
        final WebElement signInA = driver.findElement(By.id("nav-link-accountList"));
        signInA.click();
    }

    /**
     * Sign in to Amazon.
     * Works only if you have already navigated to the sign-in page (`https://www.amazon.com/ap/signin?...`).
     * @param driver        The web driver.
     * @param email         Email.
     * @param password      Password.
     */
    void signIn(WebDriver driver, String email, String password) {
        final WebElement mainSectionDiv = driver.findElement(By.id("authportal-main-section"));
        final WebElement form = mainSectionDiv.findElement(By.tagName("form"));
        // Enter email.
        final WebElement emailInput = form.findElement(By.id("ap_email"));
        emailInput.click();
        emailInput.sendKeys(email);
        // Enter password.
        final WebElement passwordInput = form.findElement(By.id("ap_password"));
        passwordInput.click();
        passwordInput.sendKeys(password);
        // Click 'Keep me logged in' to avoid being logged out.
        final WebElement rememberMeSpan = form.findElement(By.className("a-checkbox-label"));
        rememberMeSpan.click();
        // Submit.
        final WebElement signInSubmitInput = form.findElement(By.id("signInSubmit"));
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
        getLogger().log(Level.INFO, "Processing book `" + bookId + "`.");
        driver.navigate().to(url);
//        ensureKindleStorePage(driver);
        // Find the main container.
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        // Get this book's title. This may be used later to return a book borrowed through Kindle Unlimited.
        final WebElement centerColDiv = dpContainerDiv.findElement(By.id("centerCol"));
        final WebElement booksTitleDiv = centerColDiv.findElement(By.id("booksTitle"));
        final WebElement ebooksProductTitle = booksTitleDiv.findElement(By.id("ebooksProductTitle"));
        final String title = ebooksProductTitle.getText().trim();
        // Get this book's Amazon ID.
        // For example: `https://read.amazon.com/?asin=B07JK9Z14K`.
        final String asin = findAmazonId(driver);
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
        } else if (borrowBookThroughKindleUnlimited(driver, buyboxDiv)) {
            getLogger().log(Level.INFO, "Book `" + bookId + "` successfully borrowed. Navigating...");
            isKindleUnlimited = true;
        } else if (isBookFree(buyboxDiv)) {
            getLogger().log(Level.INFO, "Book `" + bookId + "` is free on Kindle. Purchasing...");
            // "Purchase" the book.
            final WebElement buyOneClickForm = buyboxDiv.findElement(By.id("buyOneClick"));
            final WebElement checkoutButtonIdSpan = buyOneClickForm.findElement(By.id("checkoutButtonId"));
            checkoutButtonIdSpan.click();
            // Since it not part of the Kindle Unlimited collection, it does not have to be returned.
            isKindleUnlimited = false;
        } else {
            getLogger().log(Level.INFO, "Book `" + bookId + "` is neither free nor available through Kindle Unlimited. Skipping.");
            return;
        }
        getLogger().log(Level.INFO, "Starting to collect content for book `" + bookId + "`");
        final Map<String, String> text = new HashMap<>();
        final Map<String, String> imgUrlToSrc = new HashMap<>();
        try {
            collectContent(driver, asin, text, imgUrlToSrc, email, password, true);
            writeBook(file, text);
            saveImages(bookFolder, imgUrlToSrc);
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
                // Click the media item. This will navigate to the Kindle store page.
                a.click();
            } else {
                // The 'Kindle' media item is selected. We're looking at the correct page.
                return;
            }
        }
    }

    private String findAmazonId(WebDriver driver) {
        final String url = driver.getCurrentUrl();
        final String[] components = url.split("[/?]");
        for (int i = 0; i < components.length; i++) {
            if ("dp".equals(components[i])) {
                return components[i + 1];
            }
        }
        return WebUtils.getLastUrlComponent(url);
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

    private boolean borrowBookThroughKindleUnlimited(WebDriver driver, WebElement buyboxDiv) {
        try {
            // Check if the book is available through Kindle Unlimited. If so, click 'Read for Free'.
            final WebElement borrowButton = buyboxDiv.findElement(By.id("borrow-button"));
            borrowButton.click();
            DriverUtils.sleep(5000L);
            driver.findElement(By.id("dbs-readnow-bookstore-rw"));
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

    private void collectContent(WebDriver driver, String asin, Map<String, String> text, Map<String, String> imgUrlToSrc, String email, String password, boolean fromStart) {
        driver.navigate().to("https://read.amazon.com/?asin=" + asin);
        DriverUtils.sleep(5000L);
        final WebElement kindleReaderContainer = driver.findElement(By.id("KindleReaderContainer"));
        // Enter the first `iframe`.
        final WebElement kindleReaderFrame = DriverUtils.findElementWithRetries(kindleReaderContainer, By.id("KindleReaderIFrame"), 5, 7500L);
        final WebDriver readerDriver = driver.switchTo().frame(kindleReaderFrame);
        // Close the 'Sync Position' dialog, if it's open.
        try {
            final WebElement syncPositionDiv = readerDriver.findElement(By.id("kindleReader_dialog_syncPosition"));
            // Click 'Make This the Furthest Read Location'.
            final WebElement resetButton = syncPositionDiv.findElement(By.id("kindleReader_dialog_syncPosition_reset_btn"));
            resetButton.click();
            DriverUtils.sleep(3000L);
        } catch (WebDriverException ignored) {
        }
        // Find the main container.
        final WebElement bookContainerDiv = readerDriver.findElement(By.id("kindleReader_book_container"));
        // Find the navigation arrows.
        final WebElement touchLayerDiv = DriverUtils.findElementWithRetries(bookContainerDiv, By.xpath("./div[@id='kindleReader_touchLayer']"), 3, 4500L);
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
        while (true) {
            try {
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
            } catch (StaleElementReferenceException e) {
                // Check to see if we have been signed out automatically.
                final String url = readerDriver.getCurrentUrl();
                if (url.startsWith("https://www.amazon.com/ap/signin")) {
                    // If so, sign in again and continue collecting content from the same position in the reader.
                    signIn(readerDriver, email, password);
                    collectContent(readerDriver, asin, text, imgUrlToSrc, email, password, false);
                }
                return;
            }
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

    boolean returnKindleUnlimitedBook(WebDriver driver, String title, String email, String password) {
        // Navigate to the 'Content and Devices' account page.
        driver.navigate().to("https://www.amazon.com/hz/mycd/myx#/home/content/booksAll/dateDsc/");
        DriverUtils.sleep(2500L);
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
        // Filter the list by books borrowed through Kindle Unlimited.
        filterContentByBorrows(ngAppDiv);
         // Return the book with the given title by clicking [...] (Actions column) -> [Return book] -> [Yes]
        final WebElement contentContainerDiv = ngAppDiv.findElement(By.className("contentContainer_myx"));
        final WebElement contentTableListDiv = contentContainerDiv.findElement(By.className("contentTableList_myx"));
        final WebElement gridUl = contentTableListDiv.findElement(By.tagName("ul"));
        final List<WebElement> lis = gridUl.findElements(By.tagName("li"));
        for (int i = 0; i < lis.size(); i++) {
            final WebElement li = lis.get(i);
            final WebElement titleDiv = li.findElement(By.id("title" + i));
            final String titleText = titleDiv.getText().trim();
            if (title.equalsIgnoreCase(titleText)) {
                final WebElement button = li.findElement(By.tagName("button"));
                button.click();
                DriverUtils.sleep(2000L);
                final WebElement returnLoanDiv = li.findElement(By.id("contentAction_returnLoan_myx"));
                returnLoanDiv.click();
                final WebElement popoverModalDiv = driver.findElement(By.className("myx-popover-modal"));
                final WebElement okButton = popoverModalDiv.findElement(By.id("dialogButton_ok_myx "));  // Apparently there is a space there!
                okButton.click();
                return true;
            }
        }
        return false;
    }

    private void filterContentByBorrows(WebElement ngAppDiv) {
        // Filter the list to show only borrowed books: Click [All] -> [Borrowed].
        final WebElement contentTaskBarDetailsDiv = ngAppDiv.findElement(By.className("contentTaskBarDetails_myx"));
        final WebElement contentTaskBarDiv = contentTaskBarDetailsDiv.findElement(By.className("contentTaskBar_myx"));
        final List<WebElement> contentTaskBarItemDivs = contentTaskBarDiv.findElements(By.className("contentTaskBarItem_myx"));
        // Find the correct task bar item.
        for (WebElement contentTaskBarItemDiv : contentTaskBarItemDivs) {
            final String taskBarItemText = contentTaskBarItemDiv.getText().trim();
            if (!taskBarItemText.startsWith("Show:")) {
                continue;
            }
            // Find the correct task bar element.
            final List<WebElement> contentTaskBarElementDivs = contentTaskBarItemDiv.findElements(By.className("contentTaskBarElement_myx"));
            for (WebElement contentTaskBarElementDiv : contentTaskBarElementDivs) {
                final String contentTaskBarElementText = contentTaskBarElementDiv.getText().trim();
                if (!contentTaskBarElementText.startsWith("All")) {
                    continue;
                }
                // Click [All].
                contentTaskBarElementDiv.click();
                // Find the correct (open) drop down.
                final List<WebElement> customDropdownDivs = contentTaskBarItemDiv.findElements(By.className("customDropdown_myx"));
                for (WebElement customDropdownDiv : customDropdownDivs) {
                    final String customDropdownClassName = customDropdownDiv.getAttribute("class");
                    if (!customDropdownClassName.contains("open_myx")) {
                        continue;
                    }
                    final WebElement ul = customDropdownDiv.findElement(By.tagName("ul"));
                    final List<WebElement> lis = ul.findElements(By.tagName("li"));
                    for (WebElement li : lis) {
                        final String liText = li.getText().trim();
                        if (!liText.equalsIgnoreCase("Borrows")) {
                            continue;
                        }
                        // Click [Borrows].
                        li.click();
                        DriverUtils.sleep(7500L);
                        return;
                    }
                }
            }
        }
    }

    private static final Set<String> FORMATTING_TAGS = new HashSet<>();

    static {
        FORMATTING_TAGS.add("span");
        FORMATTING_TAGS.add("a");
        FORMATTING_TAGS.add("i");
        FORMATTING_TAGS.add("b");
        FORMATTING_TAGS.add("s");
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
