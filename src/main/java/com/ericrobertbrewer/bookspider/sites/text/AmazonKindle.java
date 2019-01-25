package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.BookScrapeInfo;
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

    // TODO: Create an AmazonKindle database with (id, store_url, title, is_kindle_unlimited, price, last_updated).
    public interface Listener {
        boolean shouldUpdateBook(String bookId);
        void onUpdateBook(String bookId, String id, boolean isKindleUnlimited, String price);
    }

    public static boolean isPriceFree(String price) {
        if (price == null) {
            return false;
        }
        return price.startsWith(PRICE_FREE);
    }

    private static final String PRICE_FREE = "$0.00";

    final List<BookScrapeInfo> bookScrapeInfos;
    private final AtomicInteger scrapeThreadsRunning = new AtomicInteger(0);

    private Listener listener;

    public AmazonKindle(Logger logger, List<BookScrapeInfo> bookScrapeInfos) {
        super(logger);
        this.bookScrapeInfos = bookScrapeInfos;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, Launcher.Callback callback) {
        if (args.length < 2 || args.length > 5) {
            throw new IllegalArgumentException("Usage: <email> <password> [threads=1] [max-retries=1] [force=false]");
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
        // Process the books in a random order.
        Collections.shuffle(bookScrapeInfos);
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
                setIsWindowSingleColumn(driver);
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
    void setIsWindowSingleColumn(WebDriver driver) {
        driver.manage().window().setSize(new Dimension(719, 978));
    }

    void scrapeBooks(Queue<BookScrapeInfo> queue, WebDriver driver, File contentFolder, String email, String password, int maxRetries, boolean force) {
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
            // Get the text file for this book.
            final File textFile;
            try {
                textFile = getTextFile(bookFolder, force);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Encountered IOException while accessing text file for book `" + bookScrapeInfo.id + "`.", e);
                continue;
            }
            // Check if this book can be skipped.
            if (listener != null) {
                if (!listener.shouldUpdateBook(bookScrapeInfo.id)) {
                    if (textFile.exists()) {
                        getLogger().log(Level.INFO, "Book `" + bookScrapeInfo.id + "` does not need to be updated and its text file exists. Continuing without processing.");
                        continue;
                    } else if (!isPriceFree(bookScrapeInfo.price)) {
                        getLogger().log(Level.INFO, "Book `" + bookScrapeInfo.id + "` has not been free to purchase (" + bookScrapeInfo.price + ") recently. Continuing without processing.");
                        continue;
                    }
                }
            } else if (textFile.exists()) {
                getLogger().log(Level.INFO, "Book `" + bookScrapeInfo.id + "` has already been scraped. Continuing.");
                continue;
            }
            // Try updating, then scraping the text for this book.
            for (String url : bookScrapeInfo.urls) {
                try {
                    getLogger().log(Level.INFO, "Processing book `" + bookScrapeInfo.id + "` using URL `" + url + "`.");
                    scrapeBook(bookScrapeInfo.id, url, driver, bookFolder, textFile, email, password, maxRetries);
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
        // Check the layout type for this book.
        final LayoutType layoutType = getStorePageLayoutType(driver);
        if (layoutType == LayoutType.UNKNOWN) {
            getLogger().log(Level.SEVERE, "Found unknown store page layout type for book `" + bookId + "`. Quitting.");
            return;
        }
        // Ensure that we have navigated to the Amazon store page for the Kindle version of the book.
        if (!navigateToKindleStorePageIfNeeded(driver, layoutType)) {
            // It's possible that a book is simply not available on Amazon Kindle.
            // See `https://www.amazon.com/dp/0689307764`.
            getLogger().log(Level.WARNING, "Unable to navigate to the page for the Kindle version of book `" + bookId + "`. It may not exist. Skipping.");
            return;
        }
        // Find the main container.
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        // Get this book's title. This may be used later to return a book borrowed through Kindle Unlimited.
        final String title = getBookTitle(dpContainerDiv, layoutType);
        if (title == null) {
            getLogger().log(Level.WARNING, "Unable to find book title for `" + bookId + "`. Perhaps the paperback product page is shown instead of Kindle? Skipping.");
            return;
        }
        // Get this book's Amazon ID.
        // For example: `B07JK9Z14K`.
        // Used as: `https://read.amazon.com/?asin=<AMAZON_ID>`.
        final String asin = getAmazonId(driver);
        if (asin == null) {
            getLogger().log(Level.SEVERE, "Unable to find Amazon ID for book `" + bookId + "` within URL `" + url + "`. Skipping.");
            return;
        }
        // Extract this book's significant Amazon database fields.
        final PurchaseType purchaseType = getPurchaseType(dpContainerDiv, layoutType);
        boolean isKindleUnlimited = false;
        String price = null;
        switch (purchaseType) {
            case PURCHASE_OWNED:
                price = getBookPrice(dpContainerDiv, layoutType);
                getLogger().log(Level.INFO, "Book `" + bookId + "` already purchased.");
                break;
            case KINDLE_UNLIMITED_BORROWED:
                getLogger().log(Level.INFO, "Book `" + bookId + "` already borrowed through Kindle Unlimited.");
                isKindleUnlimited = true;
                break;
            case KINDLE_UNLIMITED_AVAILABLE:
                getLogger().log(Level.INFO, "Book `" + bookId + "` is available through Kindle Unlimited.");
                isKindleUnlimited = true;
                break;
            case PURCHASE_AVAILABLE:
                price = getBookPrice(dpContainerDiv, layoutType);
                getLogger().log(Level.INFO, "Book `" + bookId + "` is available to purchase.");
                break;
            case UNAVAILABLE:
            default:
                break;
        }
        // Update Amazon fields in database, if possible.
        if (listener != null) {
            getLogger().log(Level.INFO, "Updating Amazon fields for book `" + bookId + "`: ID=" + asin + "; isKindleUnlimited=" + isKindleUnlimited + "; price=" + price + ".");
            listener.onUpdateBook(bookId, asin, isKindleUnlimited, price);
        }
        // Check if this book's text has already been extracted.
        if (textFile.exists()) {
            getLogger().log(Level.INFO, "Text for book `" + bookId + "` has already been extracted. Skipping.");
            return;
        }
        // Gain access to this book, if needed.
        if (purchaseType == PurchaseType.KINDLE_UNLIMITED_AVAILABLE) {
            // Click 'Read for Free'.
            try {
                // Check if the borrowing was successful.
                borrowBookThroughKindleUnlimited(driver, dpContainerDiv, layoutType);
            } catch (NoSuchElementException e) {
                // We were unable to borrow the book. Probably the 10-book limit is met.
                getLogger().log(Level.WARNING, "Unable to borrow book `" + bookId + "`. This book may not be available through Kindle Cloud Reader. Or has the 10-book KU limit been met? Skipping.");
                return;
            }
            getLogger().log(Level.INFO, "Book `" + bookId + "` has been successfully borrowed.");
        } else if (purchaseType == PurchaseType.PURCHASE_AVAILABLE) {
            if (price == null) {
                getLogger().log(Level.SEVERE, "Unable to find price for book `" + bookId + "`. Skipping.");
                return;
            } else if (isPriceFree(price)) {
                getLogger().log(Level.INFO, "Book `" + bookId + "` is free on Kindle. Purchasing...");
                // "Purchase" the book.
                purchaseBook(driver, dpContainerDiv, layoutType);
            } else {
                getLogger().log(Level.INFO, "Book `" + bookId + "` is neither free nor available through Kindle Unlimited. Skipping.");
                return;
            }
        } else if (purchaseType == PurchaseType.UNAVAILABLE) {
            getLogger().log(Level.INFO, "Book `" + bookId + "` is unavailable to purchase. Skipping.");
            return;
        }
        // Start collecting content.
        getLogger().log(Level.INFO, "Starting to collect content for book `" + bookId + "`...");
        try {
            // Navigate to this book's Amazon Kindle Cloud Reader page, if possible.
            final Content content = getContent(driver, bookId, asin, email, password, maxRetries);
            // Check whether any content has been extracted.
            if (!content.isEmpty()) {
                // Persist content once it has been totally collected.
                getLogger().log(Level.INFO, "Writing text for book `" + bookId + "`.");
                content.writeBook(textFile);
                getLogger().log(Level.INFO, "Saving images for book `" + bookId + "`.");
                content.saveImages(bookFolder);
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
                    getLogger().log(Level.WARNING, "Unable to return book `" + bookId + "` with title `" + title + "` through Kindle Unlimited.");
                }
            }
        }
    }

    private static final String SIGN_IN_URL_START = "https://www.amazon.com/ap/signin";

    private boolean isSignedIn(WebDriver driver) {
        final WebElement navbarDiv = driver.findElement(By.id("navbar"));
        final WebElement signInA = navbarDiv.findElement(By.id("nav-link-accountList"));
        final String aText = signInA.getText().trim();
//        return aText.startsWith("Hello,");
        return aText.contains("Eric");
    }

    /**
     * Only works when called from Amazon store page.
     * @param driver        The web driver.
     */
    private void navigateToSignInPage(WebDriver driver) {
        while (!driver.getCurrentUrl().startsWith(SIGN_IN_URL_START)) {
            final WebElement navbarDiv = driver.findElement(By.id("navbar"));
            final WebElement signInA = navbarDiv.findElement(By.id("nav-link-accountList"));
            final String href = signInA.getAttribute("href").trim();
            driver.navigate().to(href);
        }
    }

    /**
     * Sign in to Amazon.
     * Works only if you have already navigated to the sign-in page (`https://www.amazon.com/ap/signin?...`).
     * @param driver        The web driver.
     * @param email         Email.
     * @param password      Password.
     */
    private void signIn(WebDriver driver, String email, String password) {
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement centerSectionDiv = aPageDiv.findElement(By.id("authportal-center-section"));
        final WebElement mainSectionDiv = centerSectionDiv.findElement(By.id("authportal-main-section"));
        // Enter email, if necessary.
        final WebElement emailInput;
        try {
            emailInput = DriverUtils.findElementWithRetries(mainSectionDiv, By.id("ap_email"), 5, 2500L);
            emailInput.click();
            emailInput.sendKeys(email);
        } catch (NoSuchElementException ignored) {
            // Since we checked the "Keep me signed in" box, our email has already been entered.
        }
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

    private void closeKindleUnlimitedPopoverIfVisible(WebDriver driver) {
        try {
            final WebElement aModalScrollerDiv = driver.findElement(By.className("a-modal-scroller"));
            final WebElement noButton = aModalScrollerDiv.findElement(By.id("p2dPopoverID-no-button"));
            noButton.click();
        } catch (NoSuchElementException ignored) {
            // It usually doesn't appear.
        }
    }

    private enum LayoutType {
        /**
         * Placeholder for store page layouts containing unrecognized elements.
         */
        UNKNOWN,
        /**
         * The standard store layout type. Almost all books follow this layout format.
         */
        COLUMNS,
        /**
         * A less-common store layout type using tabs. This layout type usually contains many different media options.
         * See `https://www.amazon.com/dp/B007OUUDVK`.
         */
        TABS
        // TODO: Handle the layout type for 'Pride & Prejudice': `https://www.amazon.com/dp/B008476HBM`.
    }

    private LayoutType getStorePageLayoutType(WebDriver driver) {
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        try {
            dpContainerDiv.findElement(By.id("centerCol"));
            return LayoutType.COLUMNS;
        } catch (NoSuchElementException e) {
            try {
                dpContainerDiv.findElement(By.id("ppdFixedGridRightColumn"));
                return LayoutType.TABS;
            } catch (NoSuchElementException ignored) {
            }
        }
        return LayoutType.UNKNOWN;
    }

    /**
     * Ensure that we're looking at the Kindle store page, not the paperback store page.
     * For example, `https://mybookcave.com/t/?u=0&b=94037&r=86&sid=mybookcave&utm_campaign=MBR+site&utm_source=direct&utm_medium=website`.
     * This may cause the driver to navigate to a different page.
     * To avoid `StaleElementReferenceException`s, invoke this method before finding elements via the web driver.
     * @param driver        The web driver.
     * @param layoutType    The layout type of the Amazon store page.
     * @return `true` if the driver was already on, or has been directed to the Kindle store page. Otherwise, `false`.
     */
    private boolean navigateToKindleStorePageIfNeeded(WebDriver driver, LayoutType layoutType) {
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement kindleSwatchLi = getKindleSwatchLi(dpContainerDiv);
            if (kindleSwatchLi != null) {
                final String className = kindleSwatchLi.getAttribute("class");
                if (className.contains("unselected")) {
                    // The 'Kindle' media item is unselected. We're probably looking at the wrong store page.
                    final WebElement a = kindleSwatchLi.findElement(By.tagName("a"));
                    // Navigate to the Kindle store page.
                    final String href = a.getAttribute("href").trim();
                    driver.navigate().to(href);
                }
                // Whether we've navigated to the Kindle store page or we're already there, stop looking for the 'Kindle' item.
                return true;
            }
        } else if (layoutType == LayoutType.TABS) {
            final WebElement ppdFixedGridRightColumnDiv = dpContainerDiv.findElement(By.id("ppdFixedGridRightColumn"));
            final WebElement tabSetContainer = ppdFixedGridRightColumnDiv.findElement(By.id("mediaTabs_tabSetContainer"));
            final WebElement headings = tabSetContainer.findElement(By.id("mediaTabsHeadings"));
            final WebElement tabSet = headings.findElement(By.id("mediaTabs_tabSet"));
            final List<WebElement> lis = tabSet.findElements(By.tagName("li"));
            for (WebElement li : lis) {
                final String text = li.getText().trim();
                if (!text.startsWith("Kindle")) {
                    continue;
                }
                final String className = li.getAttribute("class");
                if (!className.contains("a-active")) {
                    // The 'Kindle' tab is unselected.
                    final WebElement a = li.findElement(By.tagName("a"));
                    // Navigate to the Kindle store page.
                    final String href = a.getAttribute("href").trim();
                    driver.navigate().to(href);
                }
                return true;
            }
        }
        return false;
    }

    private WebElement getKindleSwatchLi(WebElement dpContainerDiv) {
        final WebElement centerColDiv = dpContainerDiv.findElement(By.id("centerCol"));
        final WebElement mediaMatrixDiv = centerColDiv.findElement(By.id("MediaMatrix"));
        final WebElement formatsDiv = mediaMatrixDiv.findElement(By.id("formats"));
        final WebElement tmmSwatchesDiv = formatsDiv.findElement(By.id("tmmSwatches"));
        final WebElement ul = tmmSwatchesDiv.findElement(By.tagName("ul"));
        final List<WebElement> lis = ul.findElements(By.tagName("li"));
        for (WebElement li : lis) {
            final String text = li.getText().trim();
            if (text.startsWith("Kindle")) {
                return li;
            }
        }
        return null;
    }

    private String getBookTitle(WebElement dpContainerDiv, LayoutType layoutType) {
        final WebElement booksTitleDiv;
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement centerColDiv = dpContainerDiv.findElement(By.id("centerCol"));
            booksTitleDiv = centerColDiv.findElement(By.id("booksTitle"));
        } else {
            booksTitleDiv = dpContainerDiv.findElement(By.id("booksTitle"));
        }
        try {
            final WebElement ebooksProductTitle = booksTitleDiv.findElement(By.id("ebooksProductTitle"));
            return ebooksProductTitle.getText().trim();
        } catch (NoSuchElementException ignored) {
        }
        return null;
    }

    private String getAmazonId(WebDriver driver) {
        final String url = driver.getCurrentUrl();
        final String[] components = url.split("[/?]");
        for (int i = 0; i < components.length - 1; i++) {
            if ("dp".equals(components[i])) {
                return components[i + 1];
            }
        }
        return null;
    }

    private enum PurchaseType {
        KINDLE_UNLIMITED_AVAILABLE,
        KINDLE_UNLIMITED_BORROWED,
        PURCHASE_AVAILABLE,
        PURCHASE_OWNED,
        /**
         * The Kindle version of this book cannot be purchased.
         * See `https://www.amazon.com/dp/B002EAZIS8`.
         */
        UNAVAILABLE
    }

    private PurchaseType getPurchaseType(WebElement dpContainerDiv, LayoutType layoutType) {
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
            try {
                buyboxDiv.findElement(By.tagName("table"));
            } catch (NoSuchElementException e) {
                return PurchaseType.UNAVAILABLE;
            }
        }
        if (isBookOwned(dpContainerDiv, layoutType)) {
            return PurchaseType.PURCHASE_OWNED;
        } else if (isBookBorrowedThroughKindleUnlimited(dpContainerDiv, layoutType)) {
            return PurchaseType.KINDLE_UNLIMITED_BORROWED;
        } else if (canBorrowBookThroughKindleUnlimited(dpContainerDiv, layoutType)) {
            return PurchaseType.KINDLE_UNLIMITED_AVAILABLE;
        }
        return PurchaseType.PURCHASE_AVAILABLE;
    }

    private WebElement findBuyboxDiv(WebElement dpContainerDiv) {
        final WebElement rightColDiv = dpContainerDiv.findElement(By.id("rightCol"));
        return rightColDiv.findElement(By.id("buybox"));
    }

    private boolean isBookOwned(WebElement dpContainerDiv, LayoutType layoutType) {
        try {
            // Check if the book has already been purchased.
            final WebElement readNowDescriptionTextSpan;
            if (layoutType == LayoutType.COLUMNS) {
                final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
                readNowDescriptionTextSpan = buyboxDiv.findElement(By.id("read-now-description-text"));
            } else {
                readNowDescriptionTextSpan = dpContainerDiv.findElement(By.id("read-now-description-text"));
            }
            final String readNowDescriptionText = readNowDescriptionTextSpan.getText().trim();
            return readNowDescriptionText.startsWith("You already own this item");
        } catch (NoSuchElementException e) {
            try {
                final WebElement ebooksInstantOrderUpdateSpan = dpContainerDiv.findElement(By.id("ebooksInstantOrderUpdate"));
                final String ebooksInstantOrderUpdateSpanText = ebooksInstantOrderUpdateSpan.getText().trim();
                return ebooksInstantOrderUpdateSpanText.startsWith("You purchased this item");
            } catch (NoSuchElementException ignored) {
            }
        }
        return false;
    }

    private boolean isBookBorrowedThroughKindleUnlimited(WebElement dpContainerDiv, LayoutType layoutType) {
        try {
            // Check if the book has already been acquired through Kindle Unlimited.
            final WebElement readNowDescriptionTextSpan;
            if (layoutType == LayoutType.COLUMNS) {
                final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
                readNowDescriptionTextSpan = buyboxDiv.findElement(By.id("read-now-description-text"));
            } else {
                readNowDescriptionTextSpan = dpContainerDiv.findElement(By.id("read-now-description-text"));
            }
            final String readNowDescriptionText = readNowDescriptionTextSpan.getText().trim();
            return readNowDescriptionText.startsWith("You already borrowed this item");
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private boolean canBorrowBookThroughKindleUnlimited(WebElement dpContainerDiv, LayoutType layoutType) {
        try {
            // Check if the book is available through Kindle Unlimited.
            if (layoutType == LayoutType.COLUMNS) {
                final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
                buyboxDiv.findElement(By.id("borrow-button"));
            } else {
                dpContainerDiv.findElement(By.id("borrow-button"));
            }
            return true;
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private void borrowBookThroughKindleUnlimited(WebDriver driver, WebElement dpContainerDiv, LayoutType layoutType) {
        final WebElement borrowButton;
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
            borrowButton = buyboxDiv.findElement(By.id("borrow-button"));
        } else {
            borrowButton = dpContainerDiv.findElement(By.id("borrow-button"));
        }
        borrowButton.click();
        // Check if the borrowing was successful.
        try {
            DriverUtils.findElementWithRetries(driver, By.id("dbs-readnow-bookstore-rw"), 2, 2500L);
        } catch (NoSuchElementException e) {
            DriverUtils.findElementWithRetries(driver, By.id("dbs-goto-bookstore-rw"), 2, 2500L);
        }
    }

    private String getBookPrice(WebElement dpContainerDiv, LayoutType layoutType) {
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement kindleSwatchLi = getKindleSwatchLi(dpContainerDiv);
            if (kindleSwatchLi != null) {
                final WebElement priceSpan = kindleSwatchLi.findElement(By.className("a-color-price"));
                return priceSpan.getText().trim();
            }
        } else if (layoutType == LayoutType.TABS) {
            final WebElement contentLandingDiv = dpContainerDiv.findElement(By.id("mediaTab_content_landing"));
            final WebElement mediaNoAccordionDiv = contentLandingDiv.findElement(By.id("mediaNoAccordion"));
            final WebElement priceSpan = mediaNoAccordionDiv.findElement(By.className("header-price"));
            return priceSpan.getText().trim();
        }
        return null;
    }

    private void purchaseBook(WebDriver driver, WebElement dpContainerDiv, LayoutType layoutType) {
        final WebElement buyOneClickForm;
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
            buyOneClickForm = buyboxDiv.findElement(By.id("buyOneClick"));
        } else {
            buyOneClickForm = dpContainerDiv.findElement(By.id("buyOneClick"));
        }
        final WebElement checkoutButtonIdSpan = buyOneClickForm.findElement(By.id("checkoutButtonId"));
        checkoutButtonIdSpan.click();
        // Check if the purchase was successful.
        try {
            DriverUtils.findElementWithRetries(driver, By.id("dbs-readnow-bookstore-rw"), 3, 2500L);
        } catch (NoSuchElementException e) {
            DriverUtils.findElementWithRetries(driver, By.id("dbs-goto-bookstore-rw"), 3, 2500L);
        }
    }

    private Content getContent(WebDriver driver, String bookId, String asin, String email, String password, int maxRetries) {
        final Content content = new Content();
        // Catch exceptions the first few times...
        int retries = maxRetries;
        final long baseWaitMillis = 10000L;
        while (retries > 1) {
            try {
                content.collect(driver, bookId, asin, email, password, true, baseWaitMillis + (maxRetries - retries) * 5000L);
                if (!content.isEmpty()) {
                    return content;
                }
                // Occasionally, the text content hasn't been loaded into the page and this method will
                // suppose that it is finished. In this case, pause, then try again.
                getLogger().log(Level.WARNING, "`collectContent` for book `" + bookId + "` completed without failing or extracting any text. " + retries + " retries left. Pausing, then retrying...");
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find unknown element for book `" + bookId + "`.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Encountered error while collecting content for book `" + bookId + "`. Retrying.", t);
            }
            retries--;
        }
        // Then fail the last time.
        content.collect(driver, bookId, asin, email, password, true, baseWaitMillis + (maxRetries - 1) * 5000L);
        return content;
    }

    boolean returnKindleUnlimitedBook(WebDriver driver, String title, String email, String password) {
        // Navigate to the 'Content and Devices' account page, showing only borrowed books.
        driver.navigate().to("https://www.amazon.com/hz/mycd/myx#/home/content/booksBorrows/dateDsc/");
        DriverUtils.sleep(1000L);
        final WebElement aPageDiv = driver.findElement(By.id("a-page"));
        final WebElement ngAppDiv;
        try {
            ngAppDiv = aPageDiv.findElement(By.id("ng-app"));
        } catch (NoSuchElementException e) {
            // Check to see if we have been signed out automatically.
            final String url = driver.getCurrentUrl();
            if (url.startsWith(SIGN_IN_URL_START)) {
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
                final WebElement returnLoanDiv = DriverUtils.findElementWithRetries(li, By.id("contentAction_returnLoan_myx"), 3, 2500L);
                returnLoanDiv.click(); // [Return book]
                final WebElement popoverModalDiv = driver.findElement(By.className("myx-popover-modal"));
                final WebElement okButton = popoverModalDiv.findElement(By.id("dialogButton_ok_myx "));  // Apparently there is a space there!
                okButton.click(); // [Yes]
                return true;
            }
        }
        return false;
    }

    private class Content {

        private final Map<String, String> idToText = new HashMap<>();
        private final Map<String, String> imgUrlToSrc = new HashMap<>();

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isEmpty() {
            // Allow books which only contain images.
            // See `B073XQJV2L`.
            return idToText.size() == 0 && imgUrlToSrc.size() == 0;
        }

        void collect(WebDriver driver, String bookId, String asin, String email, String password, boolean fromStart, long waitMillis) {
            driver.navigate().to("https://read.amazon.com/?asin=" + asin);
            DriverUtils.sleep(waitMillis);
            final WebElement kindleReaderContainerDiv;
            try {
                kindleReaderContainerDiv = DriverUtils.findElementWithRetries(driver, By.id("KindleReaderContainer"), 7, 2500L);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find `KindleReaderContainer` for book `" + bookId + "`.");
                return;
            }
            // Enter the first `iframe`.
            final WebElement kindleReaderFrame;
            try {
                kindleReaderFrame = DriverUtils.findElementWithRetries(kindleReaderContainerDiv, By.id("KindleReaderIFrame"), 9, 2500L);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find `KindleReaderIFrame` for book `" + bookId + "`.");
                return;
            }
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
            // Hide the app bars, if they are visible.
            try {
                final WebElement appBarOverlayDiv = bookContainerDiv.findElement(By.id("appBarOverlay"));
                appBarOverlayDiv.click();
            } catch (NoSuchElementException ignored) {
            }
            // Find the navigation arrows.
            final WebElement touchLayerDiv;
            try {
                touchLayerDiv = DriverUtils.findElementWithRetries(bookContainerDiv, By.id("kindleReader_touchLayer"), 3, 2500L);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find `kindleReader_touchLayer` for book `" + bookId + "`.");
                return;
            }
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
                    addVisibleContent(readerDriver, contentDiv, false);
                    // Attempt to turn the page right.
                    final WebElement pageTurnAreaRightDiv = sideMarginDiv.findElement(By.id("kindleReader_pageTurnAreaRight"));
                    final String className = pageTurnAreaRightDiv.getAttribute("class");
                    if (!className.contains("pageArrow")) {
                        final long totalTime = System.currentTimeMillis() - startTime;
                        getLogger().log(Level.INFO, "Finished collecting content for book `" + bookId + "`; " + pages + " page" + (pages > 1 ? "s" : "") + " turned; " + totalTime + " total ms elapsed; " + (totalTime / pages) + " average ms elapsed per page.");
                        break;
                    }
                    pageTurnAreaRightDiv.click();
                } catch (StaleElementReferenceException e) {
                    // Check to see if we have been signed out automatically.
                    final String url = driver.getCurrentUrl();
                    if (url.startsWith(SIGN_IN_URL_START)) {
                        // If so, sign in again and continue collecting content from the same position in the reader.
                        signIn(driver, email, password);
                        collect(driver, bookId, asin, email, password, false, waitMillis);
                    }
                    return;
                }
            }
        }

        private void addVisibleContent(WebDriver driver, WebElement element, boolean isBelowIframe) {
            // Ignore hidden elements.
            if ("hidden".equals(element.getCssValue("visibility"))) {
                return;
            }
            // Ignore elements which are not displayed.
            if ("none".equals(element.getCssValue("display"))) {
                return;
            }
            // Check whether this textual element has already been scraped.
            final String id = element.getAttribute("id");
            if (idToText.containsKey(id)) {
                return;
            }
            final String dataNid = element.getAttribute("data-nid");
            if (idToText.containsKey(dataNid)) {
                return;
            }
            // TODO: Make traversal of children more efficient (by skipping parents whose ID have already been scraped?).
            // Return the visible text of all relevant children, if any exist.
            final List<WebElement> children = element.findElements(By.xpath("./*"));
            if (children.size() > 0) {
                if (isBelowIframe) {
                    if (children.stream().allMatch(this::canAddChildElementContent)) {
                        for (WebElement child : children) {
                            addVisibleContent(driver, child, true);
                        }
                        return;
                    }
                } else {
                    for (WebElement child : children) {
                        addVisibleContent(driver, child, false);
                    }
                    return;
                }
            }
            // Check for special tags.
            final String tag = element.getTagName();
            if ("iframe".equals(tag)) {
                // Return the visible text of the <body> element.
                final WebDriver frameDriver = driver.switchTo().frame(element);
                final WebElement body = frameDriver.findElement(By.tagName("body"));
                final List<WebElement> bodyChildren = body.findElements(By.xpath("./*"));
                for (WebElement bodyChild : bodyChildren) {
                    addVisibleContent(frameDriver, bodyChild, true);
                }
                frameDriver.switchTo().parentFrame();
                return;
            } else if ("img".equals(tag)) {
                // TODO: Capture ALL images - not just ones that are leaf elements!
                final String src = element.getAttribute("src");
                final String url;
                final String dataurl = element.getAttribute("dataurl");
                if (dataurl != null) {
                    // For primarily textual books.
                    url = WebUtils.getLastUrlComponent(dataurl).trim();
                } else {
                    // For illustrative (children's) books - especially cover page images.
                    url = getImageUrlFromSrc(src);
                }
                if (!imgUrlToSrc.containsKey(url)) {
                    imgUrlToSrc.put(url, src);
                }
                return;
            } else if ("div".equals(tag)) {
                if ("page-img".equals(id)) {
                    // Capture background images of <div> elements, common in illustrative (children's) books.
                    final String backgroundImageValue = element.getCssValue("background-image");
                    if (backgroundImageValue != null && !backgroundImageValue.isEmpty() && !"none".equals(backgroundImageValue)) {
                        final String src;
                        if (backgroundImageValue.startsWith("url(\"") && backgroundImageValue.endsWith("\")")) {
                            src = backgroundImageValue.substring(5, backgroundImageValue.length() - 2).trim();
                        } else {
                            src = backgroundImageValue.trim();
                        }
                        final String url;
                        if (dataNid != null) {
                            url = dataNid.replaceAll(":", "_").trim();
                        } else {
                            url = getImageUrlFromSrc(src);
                        }
                        if (!imgUrlToSrc.containsKey(url)) {
                            imgUrlToSrc.put(url, src);
                        }
                        return;
                    }
                }
            }
            // Get this leaf-element's visible text.
            final String visibleText = element.getText().trim();
            if (visibleText.isEmpty()) {
                return;
            }
            if (isStandardId(id)) {
                idToText.put(id, visibleText);
                return;
            } else if (isStandardId(dataNid)) {
                idToText.put(dataNid, visibleText);
                return;
            }
            getLogger().log(Level.SEVERE, "Found <" + tag + "> element with non-standard ID `" + id + "` at `" + driver.getCurrentUrl() + "` with text `" + visibleText + "`. Skipping.");
        }

        private boolean isStandardId(String id) {
            return id != null && id.contains(":");
        }

        private boolean canAddChildElementContent(WebElement child) {
            final String id = child.getAttribute("id");
            if (isStandardId(id)) {
                return true;
            }
            final String dataNid = child.getAttribute("data-nid");
            if (isStandardId(dataNid)) {
                return true;
            }
            final String tag = child.getTagName();
            if ("img".equals(tag) || "br".equals(tag)) {
                return true;
            }
            //noinspection RedundantIfStatement
            if ("div".equals(tag) && "content-overlays".equals(id)) {
                return true;
            }
            return false;
        }

        private String getImageUrlFromSrc(String src) {
            return src.substring(Math.max(0, src.length() - 18), Math.max(0, src.length() - 12))
                    .replaceAll("/+", "")
                    .trim();
        }

        void writeBook(File file) throws IOException {
            final String[] ids = new ArrayList<>(idToText.keySet()).stream()
                    .sorted(TEXT_ID_COMPARATOR)
                    .toArray(String[]::new);
            final PrintStream out = new PrintStream(file);
            for (String id : ids) {
                final String line = idToText.get(id);
                out.println(line);
            }
            out.close();
        }

        void saveImages(File bookFolder) {
            for (String url : imgUrlToSrc.keySet()) {
                try {
                    saveImage(bookFolder, url, imgUrlToSrc.get(url));
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Encountered problem while saving image `" + url + "`.", e);
                }
            }
        }

        void saveImage(File bookFolder, String url, String src) throws IOException {
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
    }

    private static final Map<Character, Integer> ID_CHAR_ORDINALITY = new HashMap<>();

    static {
        final String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < chars.length(); i++) {
            ID_CHAR_ORDINALITY.put(chars.charAt(i), i);
        }
    }

    static final Comparator<String> TEXT_ID_COMPARATOR = (a, b) -> {
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
