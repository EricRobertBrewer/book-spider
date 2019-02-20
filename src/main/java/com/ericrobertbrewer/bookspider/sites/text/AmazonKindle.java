package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.web.WebUtils;
import com.ericrobertbrewer.web.dl.FileDownloadInfo;
import com.ericrobertbrewer.web.dl.FileDownloader;
import com.ericrobertbrewer.web.driver.DriverUtils;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class AmazonKindle extends SiteScraper {

    public interface Listener {
        void onUpdateBook(String bookId, String asin);
    }

    public static class Book {
        public String asin;
        public String title = null;
        public boolean isKindleUnlimited = false;
        public boolean isFreeTimeUnlimited = false;
        public String price = null;
        public long lastUpdated = -1L;
    }

    public static class Options {

        @Option(name = "-mode",
                required = true,
                usage = "Type(s) of content to download. Can be 'preview' (default), 'kindle', or 'both'",
                aliases = {"-m"})
        String mode;

        @Option(name = "-email",
                usage = "Amazon account email address",
                aliases = {"-e"})
        String email;

        @Option(name = "-password",
                usage = "Amazon account password",
                aliases = {"-p"})
        String password;

        @Option(name = "-firstname",
                usage = "Amazon account first name",
                aliases = {"-f"})
        String firstName;

        @Option(name = "-threads",
                usage = "Number of threads",
                aliases = {"-t"})
        int threads = 1;

        @Option(name = "-retries",
                usage = "Maximum number of retries to download a single book before skipping",
                aliases = {"-r"})
        int maxRetries = 1;

        @Option(name = "-force",
                usage = "Force content to be downloaded, even if it already exists")
        boolean force = false;
    }

    private static final String MODE_PREVIEW = "preview";
    private static final String MODE_KINDLE = "kindle";
    private static final String MODE_BOTH = "both";

    final List<BookScrapeInfo> bookScrapeInfos;
    private final DatabaseHelper databaseHelper;
    private final AtomicInteger scrapeThreadsRunning = new AtomicInteger(0);
    private Dimension defaultDimension = null;
    private Dimension singleColumnDimension = new Dimension(719, 978);

    private Listener listener;

    public AmazonKindle(Logger logger, List<BookScrapeInfo> bookScrapeInfos) {
        super(logger);
        this.bookScrapeInfos = bookScrapeInfos;
        databaseHelper = new DatabaseHelper(logger);
        databaseHelper.connectToContentsDatabase();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, Launcher.Callback callback) {
        // Collect arguments and options.
        final Options options = new Options();
        final CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            return;
        }

        // Check for a valid mode option.
        if (!MODE_PREVIEW.equalsIgnoreCase(options.mode) &&
                !MODE_KINDLE.equalsIgnoreCase(options.mode) &&
                !MODE_BOTH.equalsIgnoreCase(options.mode)) {
            throw new IllegalArgumentException("Unrecognized argument for `mode`: " + options.mode + ".");
        }

        // Check for valid login credentials if scraping Kindle content.
        if (!MODE_PREVIEW.equalsIgnoreCase(options.mode)) {
            if (options.email == null || options.password == null || options.firstName == null) {
                throw new IllegalArgumentException("The options 'email', 'password', and 'firstname' are required when collecting Kindle content.");
            }
        }

        // Process the books in a random order.
        Collections.shuffle(bookScrapeInfos);

        // Create thread-safe queue.
        final Queue<BookScrapeInfo> queue = new ConcurrentLinkedQueue<>(bookScrapeInfos);

        // Start scraping.
        scrapeBooksThreaded(queue,
                options.threads,
                factory,
                options.mode,
                contentFolder,
                options.email,
                options.password,
                options.firstName,
                options.maxRetries,
                options.force,
                callback);
    }

    private void scrapeBooksThreaded(Queue<BookScrapeInfo> queue,
                                     int threads,
                                     WebDriverFactory factory,
                                     String mode,
                                     File contentFolder,
                                     String email,
                                     String password,
                                     String firstName,
                                     int maxRetries,
                                     boolean force,
                                     Launcher.Callback callback) {
        // Create images queue.
        final Queue<FileDownloadInfo> imagesQueue = new ConcurrentLinkedQueue<>();

        // Start scrape threads.
        for (int i = 0; i < threads; i++) {
            final WebDriver driver = factory.newInstance();
            if (defaultDimension == null) {
                defaultDimension = driver.manage().window().getSize();
            }
            final Thread scrapeThread = new Thread(() -> {
                scrapeThreadsRunning.incrementAndGet();
                // Start scraping.
                scrapeBooks(queue, driver, mode, contentFolder, imagesQueue, email, password, firstName, maxRetries, force);
                driver.quit();
                // Finish.
                if (scrapeThreadsRunning.decrementAndGet() == 0) {
                    databaseHelper.close();
                }
            });
            scrapeThread.start();
        }

        // Start the image download thread.
        if (MODE_PREVIEW.equalsIgnoreCase(mode) || MODE_BOTH.equalsIgnoreCase(mode)) {
            // Wait until all of the scrape threads have started.
            while (scrapeThreadsRunning.get() < threads) {
                DriverUtils.sleep(3000L);
            }

            getLogger().log(Level.INFO, "Downloading images...");
            final FileDownloader fileDownloader = new FileDownloader(getLogger());
            fileDownloader.downloadFilesThreaded(imagesQueue, force, new FileDownloader.Callback() {
                @Override
                public boolean doStayAlive() {
                    return scrapeThreadsRunning.get() > 0;
                }

                @Override
                public void onComplete() {
                    getLogger().log(Level.INFO, "Finished downloading images.");
                    if (!doStayAlive()) {
                        callback.onComplete();
                    }
                }
            });
        }
    }

    void scrapeBooks(Queue<BookScrapeInfo> queue, WebDriver driver, String mode, File contentFolder, Queue<FileDownloadInfo> imagesQueue, String email, String password, String firstName, int maxRetries, boolean force) {
        while (!queue.isEmpty()) {
            // Pull the next book off of the queue.
            final BookScrapeInfo bookScrapeInfo = queue.poll();

            // Check if this book can be skipped.
            if (!force && !shouldScrapeBook(bookScrapeInfo, mode, contentFolder)) {
                continue;
            }

            // Try updating, then scraping the text for this book.
            for (String url : bookScrapeInfo.urls) {
                try {
                    getLogger().log(Level.INFO, "Processing book `" + bookScrapeInfo.id + "` using URL `" + url + "`.");
                    scrapeBook(driver, url, bookScrapeInfo.id, bookScrapeInfo.asin, mode, contentFolder, imagesQueue, email, password, firstName, maxRetries, force);
                    break;
                } catch (NoSuchElementException e) {
                    getLogger().log(Level.WARNING, "Unable to find element while scraping book `" + bookScrapeInfo.id + "`.", e);
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Encountered unknown error while scraping book `" + bookScrapeInfo.id + "`.", t);
                }
            }
        }
    }

    private boolean shouldScrapeBook(BookScrapeInfo bookScrapeInfo, String mode, File contentFolder) {
        // Process this book when there is no known ASIN.
        if (bookScrapeInfo.asin == null) {
            return true;
        }

        // Fetch the Amazon book data.
        // If an error occurs, try to fix it rather than leave a database row corrupted or non-existent.
        final AmazonKindle.Book book;
        try {
            book = databaseHelper.getAmazonBook(bookScrapeInfo.asin);
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Encountered error while retrieving Amazon book `" + bookScrapeInfo.id + "`, asin=`" + bookScrapeInfo.asin + "` from database. Attempting to fix.", e);
            return true;
        }
        if (book == null) {
            getLogger().log(Level.SEVERE, "Unable to retrieve Amazon book `" + bookScrapeInfo.id + "`, asin=`" + bookScrapeInfo.asin + "` from database. Attempting to fix.");
            return true;
        }

        // Assume that ASINs of the two different objects are interchangeable.
        if (!bookScrapeInfo.asin.equals(book.asin)) {
            return true;
        }

        // Skip this book if `force`=`false` and the text has already been scraped to the ASIN folder.
        final File bookFolder = new File(contentFolder, bookScrapeInfo.asin);
        if (bookFolder.exists() && bookFolder.isDirectory()) {
            final File previewFile = new File(bookFolder, "preview.txt");
            final File textFile = new File(bookFolder, "text.txt");
            if (MODE_PREVIEW.equalsIgnoreCase(mode)) {
                if (previewFile.exists()) {
                    return false;
                }
            } else if (MODE_KINDLE.equalsIgnoreCase(mode)) {
                if (textFile.exists()) {
                    return false;
                }
            } else {
                if (previewFile.exists() && textFile.exists()) {
                    return false;
                }
            }
        }

        // Process this book if it is available through Kindle Unlimited, is free, or has not been checked recently.
        return book.isKindleUnlimited ||
                isPriceFree(book.price) ||
                System.currentTimeMillis() - book.lastUpdated >= CHECK_AMAZON_PRICE_DELAY_MILLIS;
    }

    /**
     * The amount of time to wait in milliseconds to re-check the Amazon store page
     * for whether or not a book has been made available on Kindle Unlimited or
     * has been made free.
     * Currently set to seven (7) days.
     */
    private static final long CHECK_AMAZON_PRICE_DELAY_MILLIS = 1000L * 60 * 60 * 24 * 7;

    private static final String PRICE_FREE = "$0.00";

    private static boolean isPriceFree(String price) {
        if (price == null) {
            return false;
        }
        return price.startsWith(PRICE_FREE);
    }

    private void scrapeBook(WebDriver driver, String url, String bookId, String oldAsin, String mode, File contentFolder, Queue<FileDownloadInfo> imagesQueue, String email, String password, String firstName, int maxRetries, boolean force) throws IOException {
        // Navigate to the Amazon store page.
        driver.navigate().to(url);
        DriverUtils.sleep(1500L);

        // Ensure that the page is valid.
        try {
            // This element won't exist if the book URL is no longer valid (404 error).
            driver.findElement(By.id("a-page"));
        } catch (NoSuchElementException e) {
            getLogger().log(Level.WARNING, "The Amazon page for book `" + bookId + "` may no longer exist; started at `" + url + "`, ended at `" + driver.getCurrentUrl() + "`. Skipping.");
            return;
        }

        // Sign in, if needed.
        if ((MODE_KINDLE.equalsIgnoreCase(mode) || MODE_BOTH.equals(mode)) &&
                !isSignedIn(driver, firstName)) {
            navigateToSignInPage(driver);
            signIn(driver, email, password);
            scrapeBook(driver, url, bookId, oldAsin, mode, contentFolder, imagesQueue, email, password, firstName, maxRetries, force);
            return;
        }

        // Close the "Read this book for free with Kindle Unlimited" popover, if it appears.
        // See `https://www.amazon.com/dp/1980537615`.
        closeKindleUnlimitedPopoverIfVisible(driver);

        // Check the layout type for this book on the first Amazon page, whatever type of media it happens to be.
        final LayoutType firstLayoutType = getStorePageLayoutType(driver);
        if (firstLayoutType == LayoutType.UNKNOWN) {
            getLogger().log(Level.SEVERE, "Found unknown first store page layout type for book `" + bookId + "`. Quitting.");
            return;
        }

        // Ensure that we have navigated to the Amazon store page for the Kindle version of the book.
        if (!navigateToKindleStorePageIfNeeded(driver, firstLayoutType)) {
            // It's possible that a book is simply not available on Amazon Kindle.
            // See `https://www.amazon.com/dp/0689307764`.
            getLogger().log(Level.WARNING, "Unable to navigate to the page for the Kindle version of book `" + bookId + "`. It may not exist. Skipping.");
            return;
        }

        // Since the layout type could have changed, check the layout type for this book on the Kindle store page.
        // See `https://www.amazon.com/Road-Jack-Kerouac-ebook-dp-B002IPZFYQ/dp/B002IPZFYQ/ref=mt_kindle?_encoding=UTF8&me=&qid=`.
        final LayoutType layoutType = getStorePageLayoutType(driver);
        if (layoutType == LayoutType.UNKNOWN) {
            getLogger().log(Level.SEVERE, "Found unknown Kindle store page layout type for book `" + bookId + "`. Quitting.");
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

        // Get the ASIN.
        final String asin = getAsin(driver);
        if (asin == null) {
            getLogger().log(Level.SEVERE, "Unable to find ASIN for book `" + bookId + "` within URL `" + url + "`. Skipping.");
            return;
        }

        // Extract this book's significant Amazon database fields.
        final PurchaseType purchaseType = getPurchaseType(dpContainerDiv, layoutType);
        boolean isKindleUnlimited = false;
        boolean isFreeTimeUnlimited = false;
        String price = null;
        // TODO: Check if a book has already been borrowed through FreeTime Unlimited.
        switch (purchaseType) {
            case KINDLE_UNLIMITED_AVAILABLE:
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` is available through Kindle Unlimited.");
                isKindleUnlimited = true;
                break;
            case KINDLE_UNLIMITED_BORROWED:
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` already borrowed through Kindle Unlimited.");
                isKindleUnlimited = true;
                break;
            case FREETIME_UNLIMITED_AVAILABLE:
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "`` is available through FreeTime Unlimited.");
                isFreeTimeUnlimited = true;
                break;
            case PURCHASE_AVAILABLE:
                price = getBookPrice(dpContainerDiv, layoutType);
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` is available to purchase.");
                break;
            case PURCHASE_OWNED:
                price = getBookPrice(dpContainerDiv, layoutType);
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` already purchased.");
                break;
            case UNAVAILABLE:
            default:
                break;
        }

        // Update ASIN in database, if needed.
        if (oldAsin == null || !oldAsin.equals(asin)) {
            if (listener != null) {
                getLogger().log(Level.INFO, "Updating ASIN for book `" + bookId + "`: asin=`" + asin + "`.");
                listener.onUpdateBook(bookId, asin);
            }
        }
        // Update all fields for the AmazonBook row.
        try {
            final AmazonKindle.Book book = new AmazonKindle.Book();
            book.asin = asin;
            book.title = title;
            book.isKindleUnlimited = isKindleUnlimited;
            book.isFreeTimeUnlimited = isFreeTimeUnlimited;
            book.price = price;
            book.lastUpdated = System.currentTimeMillis();
            final int result = databaseHelper.insertOrReplace(book);
            if (result != 1) {
                databaseHelper.getLogger().log(Level.WARNING, "Unexpected result `" + result + "` after updating Amazon book asin=`" + asin + "`.");
            }
        } catch (SQLException e) {
            databaseHelper.getLogger().log(Level.SEVERE, "Unable to update Amazon book asin=`" + asin + "` in database.", e);
        }

        // Access this book's folder, which will contain its text and images.
        final File bookFolder = new File(contentFolder, asin);
        // Check if this book's content has been saved to another folder.
        if (!bookFolder.exists()) {
            if (oldAsin == null) {
                // Rename this book ID-named folder (legacy) to the ASIN-named folder, if possible.
                final File idBookFolder = new File(contentFolder, bookId);
                if (idBookFolder.exists() && idBookFolder.isDirectory()) {
                    if (!idBookFolder.renameTo(bookFolder)) {
                        getLogger().log(Level.SEVERE, "Unable to rename legacy ID-named book folder `" + bookId + "` to ASIN-named book folder `" + asin + "`. Skipping.");
                        return;
                    } else {
                        getLogger().log(Level.INFO, "Renamed legacy ID-named book folder `" + bookId + "` to ASIN-named book folder `" + asin + "`.");
                    }
                }
            } else if (!oldAsin.equals(asin)) {
                // Rename old ASIN-named book folder, if possible.
                final File oldBookFolder = new File(contentFolder, oldAsin);
                if (oldBookFolder.exists() && oldBookFolder.isDirectory()) {
                    if (!oldBookFolder.renameTo(bookFolder)) {
                        getLogger().log(Level.SEVERE, "Unable to rename old ASIN-named book folder `" + oldAsin + "` to `" + asin + "`. Skipping.");
                        return;
                    } else {
                        getLogger().log(Level.INFO, "Renamed old ASIN-named book folder `" + oldAsin + "` to `" + asin + "`.");
                    }
                }
            }
        }
        // Create the book folder, if needed.
        if (!bookFolder.exists() && !bookFolder.mkdirs()) {
            getLogger().log(Level.SEVERE, "Unable to create book folder for book `" + bookId + "`, asin=`" + asin + "`. Skipping.");
            return;
        }

        // Collect this book's 'Look Inside' preview, if applicable.
        if (MODE_PREVIEW.equalsIgnoreCase(mode) || MODE_BOTH.equalsIgnoreCase(mode)) {
            scrapeBookPreview(driver, bookId, asin, bookFolder, imagesQueue, aPageDiv, dpContainerDiv, force);
        }

        // Finish if we're only scraping book previews.
        if (MODE_PREVIEW.equalsIgnoreCase(mode)) {
            return;
        }

        // Skip collecting the content for this book if `force`=`false` and the text file exists.
        final File textFile = new File(bookFolder, "text.txt");
        if (textFile.exists()) {
            if (force) {
                if (!textFile.delete()) {
                    getLogger().log(Level.SEVERE, "Unable to delete text file for book `" + bookId + "`, asin=`" + asin + "`. Skipping.");
                    return;
                }
                getLogger().log(Level.INFO, "Deleted text file for book `" + bookId + "`, asin=`" + asin + "`. Continuing.");
            } else {
                getLogger().log(Level.INFO, "Text for book `" + bookId + "`, asin=`" + asin + "` has already been extracted. Skipping.");
                return;
            }
        }

        // Gain access to this book, if needed.
        if (purchaseType == PurchaseType.KINDLE_UNLIMITED_AVAILABLE) {
            // Click 'Read for Free'.
            try {
                // Check if the borrowing was successful.
                borrowBookThroughKindleUnlimited(driver, dpContainerDiv, layoutType);
            } catch (NoSuchElementException e) {
                // We were unable to borrow the book. Probably the 10-book limit is met.
                getLogger().log(Level.WARNING, "Unable to borrow book `" + bookId + "`, asin=`" + asin + "` through Kindle Unlimited. This book may not be available through Kindle Cloud Reader. Or has the 10-book KU limit been met? Skipping.");
                return;
            }
            getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` has been successfully borrowed.");
        } else if (purchaseType == PurchaseType.FREETIME_UNLIMITED_AVAILABLE) {
            // TODO: Can FreeTime Unlimited books even be opened in Kindle Cloud Reader?
            try {
                borrowBookThroughFreeTimeUnlimited(driver, dpContainerDiv, layoutType);
            } catch (NoSuchElementException e) {
                // Unable to borrow the book.
                getLogger().log(Level.WARNING, "Unable to borrow book `" + bookId + "`, asin=`" + asin + "` through FreeTime Unlimited. Skipping.");
                return;
            }
        } else if (purchaseType == PurchaseType.PURCHASE_AVAILABLE) {
            if (price == null) {
                getLogger().log(Level.SEVERE, "Unable to find price for book `" + bookId + "`, asin=`" + asin + "`. Skipping.");
                return;
            } else if (isPriceFree(price)) {
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` is free on Kindle. Purchasing...");
                // "Purchase" the book.
                purchaseBook(driver, dpContainerDiv, layoutType);
            } else {
                getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` is not free on Kindle. Skipping.");
                return;
            }
        } else if (purchaseType == PurchaseType.UNAVAILABLE) {
            getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` is unavailable to purchase. Skipping.");
            return;
        }

        // Start collecting content.
        getLogger().log(Level.INFO, "Starting to collect content for book `" + bookId + "`, asin=`" + asin + "`...");
        // Prepare to collect content in this window by shrinking the window width.
        setIsWindowSingleColumn(driver, true);
        try {
            // Navigate to this book's Amazon Kindle Cloud Reader page, if possible.
            final KindleContent content = getKindleContent(driver, bookId, asin, email, password, maxRetries);
            // Check whether any content has been extracted.
            if (!content.isEmpty()) {
                // Persist content once it has been totally collected.
                getLogger().log(Level.INFO, "Writing text for book `" + bookId + "`, asin=`" + asin + "`.");
                content.writeBook(textFile);
                getLogger().log(Level.INFO, "Saving images for book `" + bookId + "`, asin=`" + asin + "`.");
                content.saveImages(bookFolder);
                getLogger().log(Level.INFO, "Successfully collected and saved content for book `" + bookId + "`, asin=`" + asin + "`.");
            } else {
                getLogger().log(Level.WARNING, "Unable to extract any content for book `" + bookId + "`, asin=`" + asin + "` after " + maxRetries + " retries. Quitting.");
            }
        } finally {
            // Return the window to a larger width to avoid non-visible elements while processing the store page.
            setIsWindowSingleColumn(driver, false);
            // Return this book to avoid reaching the 10-book limit for Kindle Unlimited.
            // Hitting the limit prevents any other books from being borrowed through KU.
            if (isKindleUnlimited) {
                if (returnKindleUnlimitedBook(driver, title, email, password)) {
                    getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` has been successfully returned through Kindle Unlimited.");
                } else {
                    getLogger().log(Level.WARNING, "Unable to return book `" + bookId + "`, asin=`" + asin + "` with title `" + title + "` through Kindle Unlimited.");
                }
            }
        }
    }

    private static final String SIGN_IN_URL_START = "https://www.amazon.com/ap/signin";

    /**
     * Check whether we have signed in to Amazon.
     *
     * @param driver    The web driver.
     * @param firstName The first name of the Amazon account holder.
     *                  This is used to see if the sign-in element contains the text, "Hello, [first-name]".
     * @return `true` if the user is signed in. Otherwise, `false`.
     */
    private boolean isSignedIn(WebDriver driver, String firstName) {
        final WebElement navbarDiv = driver.findElement(By.id("navbar"));
        final WebElement signInA = navbarDiv.findElement(By.id("nav-link-accountList"));
        final String aText = signInA.getText().trim();
//        return aText.startsWith("Hello,");
        return aText.contains(firstName);
    }

    /**
     * Only works when called from Amazon store page.
     *
     * @param driver The web driver.
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
     *
     * @param driver   The web driver.
     * @param email    Email.
     * @param password Password.
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
     *
     * @param driver     The web driver.
     * @param layoutType The layout type of the Amazon store page.
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

    /**
     * Get this book's Amazon Standard Identification Number.
     * For example: `B07JK9Z14K`.
     * Used as: `https://read.amazon.com/?asin=<AMAZON_ID>`.
     * This method should only be called from the Amazon store page.
     *
     * @param driver The web driver.
     * @return The ASIN
     */
    private String getAsin(WebDriver driver) {
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
        /**
         * A subscription service for books.
         */
        KINDLE_UNLIMITED_AVAILABLE,
        /**
         * The book has already been borrowed through Kindle Unlimited.
         */
        KINDLE_UNLIMITED_BORROWED,
        /**
         * A subscription service for children's books.
         * See `https://www.amazon.com/dp/B011H55MN6`.
         */
        FREETIME_UNLIMITED_AVAILABLE,
        /**
         * The book is not available through any subscription service.
         */
        PURCHASE_AVAILABLE,
        /**
         * The book has already been purchased.
         */
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
        } else if (canBorrowBookThroughFreeTimeUnlimited(dpContainerDiv, layoutType)) {
            return PurchaseType.FREETIME_UNLIMITED_AVAILABLE;
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
        ensureBorrowSucceeded(driver);
    }

    private void ensureBorrowSucceeded(WebDriver driver) {
        // Check if the borrowing was successful.
        try {
            DriverUtils.findElementWithRetries(driver, By.id("dbs-readnow-bookstore-rw"), 2, 2500L);
        } catch (NoSuchElementException e) {
            DriverUtils.findElementWithRetries(driver, By.id("dbs-goto-bookstore-rw"), 2, 2500L);
        }
    }

    private WebElement findUpsellButton(WebElement dpContainerDiv, LayoutType layoutType) {
        if (layoutType == LayoutType.COLUMNS) {
            final WebElement buyboxDiv = findBuyboxDiv(dpContainerDiv);
            return buyboxDiv.findElement(By.id("upsell-button"));
        }
        return dpContainerDiv.findElement(By.id("upsell-button"));
    }

    private boolean canBorrowBookThroughFreeTimeUnlimited(WebElement dpContainerDiv, LayoutType layoutType) {
        try {
            final WebElement upsellButton = findUpsellButton(dpContainerDiv, layoutType);
            return upsellButton.getText().trim().contains("FreeTime");
        } catch (NoSuchElementException ignored) {
        }
        return false;
    }

    private void borrowBookThroughFreeTimeUnlimited(WebDriver driver, WebElement dpContainerDiv, LayoutType layoutType) {
        final WebElement upsellButton = findUpsellButton(dpContainerDiv, layoutType);
        upsellButton.click();
        ensureBorrowSucceeded(driver);
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

    private void scrapeBookPreview(WebDriver driver, String bookId, String asin, File bookFolder, Queue<FileDownloadInfo> imagesQueue, WebElement aPageDiv, WebElement dpContainerDiv, boolean force) {
        // Check if the file already exists.
        final File previewFile = new File(bookFolder, "preview.txt");
        if (previewFile.exists()) {
            if (force) {
                if (!previewFile.delete()) {
                    getLogger().log(Level.SEVERE, "Unable to delete preview file for book `" + bookId + "`, asin=`" + asin + "`. Skipping.");
                    return;
                }
                getLogger().log(Level.INFO, "Deleted preview file for book `" + bookId + "`, asin=`" + asin + "`. Continuing.");
            } else {
                getLogger().log(Level.INFO, "Preview for book `" + bookId + "`, asin=`" + asin + "` has already been extracted. Skipping.");
                return;
            }
        }

        // Allow elements to load.
        DriverUtils.sleep(2000L);

        // Find the 'Look Inside' image.
        final WebElement lookInsideLogoImg = findLookInsideLogoImg(dpContainerDiv);
        if (lookInsideLogoImg == null) {
            // This book does not have a 'Look Inside' element.
            // Therefore, there is no preview for this book.
            getLogger().log(Level.INFO, "Unable to find 'Look Inside' element for book `" + bookId + "`. Skipping.");
            return;
        }

        // Click the 'Look Inside' image.
        try {
            lookInsideLogoImg.click();
        } catch (WebDriverException e) {
            try {
                // The 'Look Inside' (background) image is not clickable.
                // Since it at least exists, try to click the cover image to open the preview window.
                // See `https://www.amazon.com/dp/1523813342`.
                final WebElement ebooksImageBlockDiv = dpContainerDiv.findElement(By.id("ebooksImageBlock"));
                ebooksImageBlockDiv.click();
            } catch (NoSuchElementException ignored) {
                final WebElement imgBlockFrontImg = dpContainerDiv.findElement(By.id("imgBlockFront"));
                imgBlockFrontImg.click();
            }
        }
        DriverUtils.sleep(1500L);

        // Work in the newly opened preview pane on the same page.
        final WebElement readerPlaceholderDiv = aPageDiv.findElement(By.id("sitbReaderPlaceholder"));
        final WebElement lightboxDiv;
        try {
            lightboxDiv = readerPlaceholderDiv.findElement(By.id("sitbLightbox"));
        } catch (NoSuchElementException e) {
            // "Feature Unavailable"
            // "We're sorry, but this feature is currently unavailable. Please try again later."
            // See `https://www.amazon.com/dp/B01MYH403A`.
            getLogger().log(Level.WARNING, "Kindle sample feature may be unavailable for book `" + bookId + "`, asin=`" + asin + "`. Retrying.");
            return;
        }

        // Prefer the 'Kindle Book' view, but accept the 'Print Book' view.
        final WebElement headerDiv = lightboxDiv.findElement(By.id("sitbLBHeader"));
        final WebElement readerModeDiv = headerDiv.findElement(By.id("sitbReaderMode"));
        try {
            final WebElement readerModeTabKindleDiv = readerModeDiv.findElement(By.id("readerModeTabKindle"));
            try {
                readerModeTabKindleDiv.click();
                DriverUtils.sleep(500L);
            } catch (ElementNotVisibleException e) {
                // The 'Kindle Book' `div` may not be clickable.
                getLogger().log(Level.INFO, "Unable to click 'Kindle Book' tab element for book `" + bookId + "`, asin=`" + asin + "`. Continuing.");
            }
        } catch (NoSuchElementException e) {
            getLogger().log(Level.INFO, "Page for book `" + bookId + "`, asin=`" + asin + "` does not contain 'Kindle Book' reader mode.");
        }

        // Zoom out. This may prevent having to scroll the page a lot further.
        final WebElement readerZoomToolbarDiv = headerDiv.findElement(By.id("sitbReaderZoomToolbar"));
        final WebElement readerTitlebarZoomOutButton = readerZoomToolbarDiv.findElement(By.id("sitbReaderTitlebarZoomOut"));
        try {
            readerTitlebarZoomOutButton.click();
        } catch (ElementNotVisibleException e) {
            DriverUtils.sleep(2000L);
            try {
                readerTitlebarZoomOutButton.click();
            } catch (ElementNotVisibleException ignored) {
            }
        }
        DriverUtils.sleep(1500L);

        // Find the preview reader element.
        final WebElement readerMiddleDiv = lightboxDiv.findElement(By.id("sitbReaderMiddle"));
        final WebElement readerPageareaDiv = readerMiddleDiv.findElement(By.id("sitbReader-pagearea"));
        final WebElement readerPageContainerDiv = readerPageareaDiv.findElement(By.id("sitbReaderPageContainer"));
        final WebElement readerPageScrollDiv = readerPageContainerDiv.findElement(By.id("sitbReaderPageScroll"));
        final WebElement readerKindleSampleDiv;
        try {
            readerKindleSampleDiv = readerPageScrollDiv.findElement(By.id("sitbReaderKindleSample"));
        } catch (NoSuchElementException e) {
            // This book does not have a Kindle sample.
            // Though it has a print sample, it is an `img` which loads lazily, which would require both
            // scrolling the page while waiting for loads and an image-to-text engine.
            // TODO: Do this.
            getLogger().log(Level.INFO, "Book `" + bookId + "`, asin=`" + asin + "` does not have a Kindle sample. Skipping.");
            return;
        }

        // Start collecting content.
        getLogger().log(Level.INFO, "Starting to collect preview for book `" + bookId + "`, asin=`" + asin + "`...");
        final WebElement rootElement = findRootPreviewElement(readerKindleSampleDiv);
        try {
            final PreviewContent content = new PreviewContent(this);
            content.collect(driver, rootElement, bookFolder);
            content.writePreview(previewFile);
            getLogger().log(Level.INFO, "Successfully wrote preview for book `" + bookId + "`, asin=`" + asin + "`.");
            content.downloadImages(imagesQueue);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Encountered error when writing preview ");
        } finally {
            final WebElement readerCloseButtonDiv = headerDiv.findElement(By.id("sitbReaderCloseButton"));
            readerCloseButtonDiv.click();
        }
    }

    private WebElement findLookInsideLogoImg(WebElement dpContainerDiv) {
        try {
            return dpContainerDiv.findElement(By.id("ebooksSitbLogoImg"));
        } catch (NoSuchElementException e) {
            // This page may be a slightly different format than most.
            // See `https://www.amazon.com/dp/B01I39Y1UY`.
            try {
                return dpContainerDiv.findElement(By.id("sitbLogoImg"));
            } catch (NoSuchElementException ignored) {
            }
        }
        return null;
    }

    private WebElement findRootPreviewElement(WebElement sitbReaderKindleSampleDiv) {
        try {
            return sitbReaderKindleSampleDiv.findElement(By.id("sitbReaderFrame"));
        } catch (NoSuchElementException ignored) {
            // This page does not have an `iframe` element.
            // That is OK. The book contents are simply embedded in the same page.
        }
        return sitbReaderKindleSampleDiv;
    }

    private static class PreviewContent {

        private final AmazonKindle kindle;
        private final List<String> lines = new ArrayList<>();
        private final List<FileDownloadInfo> fileDownloadInfos = new ArrayList<>();

        private PreviewContent(AmazonKindle kindle) {
            this.kindle = kindle;
        }

        private Logger getLogger() {
            return kindle.getLogger();
        }

        private void collect(WebDriver driver, WebElement element, File bookFolder) {
            // Search recursively for matching children.
            final List<WebElement> children = element.findElements(By.xpath("./*"));
            if (children.size() > 0 && !areAllFormatting(children)) {
                for (WebElement child : children) {
                    collect(driver, child, bookFolder);
                }
                return;
            }

            // This element is considered relevant. Write its contents.
            final String tag = element.getTagName();

            // Handle certain tags specially.
            if ("style".equals(tag)) {
                // Ignore.
                return;
            } else if ("iframe".equals(tag)) {
                final WebDriver frameDriver = driver.switchTo().frame(element);
                final WebElement frameBody = frameDriver.findElement(By.tagName("body"));
                collect(frameDriver, frameBody, bookFolder);
                frameDriver.switchTo().parentFrame();
                return;
            }

            // Extract the raw inner HTML.
            final String html = element.getAttribute("innerHTML");

            // Check for and download any images within image (`img`) tags.
            final String[] imageUrls = getImageUrls(html);
            for (String url : imageUrls) {
                fileDownloadInfos.add(new FileDownloadInfo(url, bookFolder));
            }

            // On every relevant LEAF-ELEMENT, check for a `background-image` CSS attribute.
            final String backgroundImageValue = element.getCssValue("background-image");
            if (backgroundImageValue != null && !backgroundImageValue.isEmpty() && !"none".equals(backgroundImageValue)) {
                final String url;
                if (backgroundImageValue.startsWith("url(\"") && backgroundImageValue.endsWith("\")")) {
                    url = backgroundImageValue.substring(5, backgroundImageValue.length() - 2).trim();
                } else {
                    url = backgroundImageValue.trim();
                }
                fileDownloadInfos.add(new FileDownloadInfo(url, bookFolder));
            }

            // Convert HTML to human-readable text.
            final String text = html
                    // Extract `img` `alt` text.
                    // An image is used frequently as a "drop cap" (https://graphicdesign.stackexchange.com/questions/85715/first-letter-of-a-book-or-chapter).
                    // See `https://www.amazon.com/dp/B078WY9W7K`.
                    .replaceAll("<img[^>]*? alt=\"([^\"]+)\"[^>]*?>", "$1")
                    // Break lines.
                    // Though the `br` tag is considered a "formatting" tag, this can prevent unwanted
                    // concatenation of texts that are really on separate lines.
                    .replaceAll("<br.*?>", "\n")
                    // Ignore other HTML formatting tags, e.g. links, italics.
                    .replaceAll("<([-a-zA-Z0-9]+).*?>(.*?)</\\1>", "$2")
                    // Ignore self-closing tags.
                    .replaceAll("<[^>]+?/?>", "")
                    // Decode the most common HTML character entity references.
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&quot;", "\"")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .trim();
            if (text.isEmpty()) {
                return;
            }
            lines.add(text);
        }

        private void writePreview(File previewFile) throws IOException {
            final PrintStream out = new PrintStream(previewFile);
            for (String line : lines) {
                out.println(line);
            }
            out.close();
        }

        private void downloadImages(Queue<FileDownloadInfo> imagesQueue) {
            for (FileDownloadInfo fileDownloadInfo : fileDownloadInfos) {
                imagesQueue.offer(fileDownloadInfo);
            }
        }

        /**
         * A set of HTML tag names which are NOT to be considered "containers" of relevant text,
         * where a "container" would typically be `div`, `p`, `h1`, etc. elements.
         * Used as a blacklisting method when capturing text.
         */
        private static final Set<String> FORMATTING_TAGS = new HashSet<>();

        static {
            FORMATTING_TAGS.add("em");
            FORMATTING_TAGS.add("i");
            FORMATTING_TAGS.add("strong");
            FORMATTING_TAGS.add("b");
            FORMATTING_TAGS.add("u");
            FORMATTING_TAGS.add("tt");
            FORMATTING_TAGS.add("strike");
            FORMATTING_TAGS.add("s");
            FORMATTING_TAGS.add("big");
            FORMATTING_TAGS.add("small");
            FORMATTING_TAGS.add("mark");
            FORMATTING_TAGS.add("del");
            FORMATTING_TAGS.add("ins");
            FORMATTING_TAGS.add("sub");
            FORMATTING_TAGS.add("sup");
            FORMATTING_TAGS.add("br");
            // For our purposes, `span`s should probably be ignored.
            // Text in descriptions seems to only be contained in `p`s and `div`s.
            FORMATTING_TAGS.add("span");
            FORMATTING_TAGS.add("style");
            FORMATTING_TAGS.add("img");  // Allows capturing of Drop Caps.
            FORMATTING_TAGS.add("hr");
            FORMATTING_TAGS.add("a");
            FORMATTING_TAGS.add("font");
        }

        private static boolean areAllFormatting(List<WebElement> elements) {
            for (WebElement element : elements) {
                final String tag = element.getTagName();
                if (!FORMATTING_TAGS.contains(tag)) {
                    return false;
                }
            }
            return true;
        }

        private static String[] getImageUrls(String html) {
            // See `https://stackoverflow.com/questions/6020384/create-array-of-regex-matches`.
            return Pattern.compile("<img[^>]*? src=\"([^\"]+)\"[^>]*?>")
                    .matcher(html)
                    .results()
                    .map(matchResult -> matchResult.group(1))
                    .toArray(String[]::new);
        }
    }

    /**
     * Ensure that only one column is shown in the Kindle reader.
     * @param driver        The web driver.
     */
    private void setIsWindowSingleColumn(WebDriver driver, boolean isSingleColumn) {
        driver.manage().window().setSize(isSingleColumn ? singleColumnDimension : defaultDimension);
    }

    private KindleContent getKindleContent(WebDriver driver, String bookId, String asin, String email, String password, int maxRetries) {
        final KindleContent content = new KindleContent(this);
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
                getLogger().log(Level.WARNING, "`collectContent` for book `" + bookId + "`, asin=`" + asin + "` completed without failing or extracting any text. " + retries + " retries left. Pausing, then retrying...");
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find unknown element for book `" + bookId + "`, asin=`" + asin + "`.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Encountered error while collecting content for book `" + bookId + "`, asin=`" + asin + "`. Retrying.", t);
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

    private static class KindleContent {

        private final AmazonKindle kindle;
        private final Map<String, String> idToText = new HashMap<>();
        private final Map<String, String> imgUrlToSrc = new HashMap<>();

        private KindleContent(AmazonKindle kindle) {
            this.kindle = kindle;
        }

        private Logger getLogger() {
            return kindle.getLogger();
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isEmpty() {
            // Allow books which only contain images.
            // See `B073XQJV2L`.
            return idToText.size() == 0 && imgUrlToSrc.size() == 0;
        }

        void collect(WebDriver driver, String bookId, String asin, String email, String password, boolean fromStart, long waitMillis) {
            driver.navigate().to("https://read.amazon.com/?asin=" + asin);
            DriverUtils.sleep(waitMillis);

            // Access the main reader container.
            final WebElement kindleReaderContainerDiv;
            try {
                kindleReaderContainerDiv = DriverUtils.findElementWithRetries(driver, By.id("KindleReaderContainer"), 7, 2500L);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find `KindleReaderContainer` for book `" + bookId + "`, asin=`" + asin + "`.");
                return;
            }

            // Enter the first `iframe`.
            final WebElement kindleReaderFrame;
            try {
                kindleReaderFrame = DriverUtils.findElementWithRetries(kindleReaderContainerDiv, By.id("KindleReaderIFrame"), 9, 2500L);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find `KindleReaderIFrame` for book `" + bookId + "`, asin=`" + asin + "`.");
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

            // Find the main container within the `iframe`.
            final WebElement bookContainerDiv = readerDriver.findElement(By.id("kindleReader_book_container"));

            // Hide the app bars, if they are visible.
            try {
                final WebElement appBarOverlayDiv = bookContainerDiv.findElement(By.id("appBarOverlay"));
                appBarOverlayDiv.click();
            } catch (NoSuchElementException ignored) {
            }

            // Log the footer message, just to inform us how long this book will be (# of pages and locations).
            try {
                final WebElement footerDiv = bookContainerDiv.findElement(By.id("kindleReader_footer"));
                final WebElement footerReaderControlsMiddleDiv = footerDiv.findElement(By.id("kindleReader_footer_readerControls_middle"));
                final WebElement footerMessageDiv = footerReaderControlsMiddleDiv.findElement(By.id("kindleReader_footer_message"));
                final String footerMessage = footerMessageDiv.getAttribute("textContent").trim();
                getLogger().log(Level.INFO, "Found Kindle reader footer message for book `" + bookId + "`, asin=`" + asin + "`: `" + footerMessage + "`.");
            } catch (NoSuchElementException ignored) {
            }

            // Find the navigation arrows.
            final WebElement touchLayerDiv;
            try {
                touchLayerDiv = DriverUtils.findElementWithRetries(bookContainerDiv, By.id("kindleReader_touchLayer"), 3, 2500L);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find `kindleReader_touchLayer` for book `" + bookId + "`, asin=`" + asin + "`.");
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
                        getLogger().log(Level.INFO, "Finished collecting content for book `" + bookId + "`, asin=`" + asin + "`; " + pages + " page" + (pages > 1 ? "s" : "") + " turned; " + totalTime + " total ms elapsed; " + (totalTime / pages) + " average ms elapsed per page.");
                        break;
                    }
                    pageTurnAreaRightDiv.click();
                } catch (StaleElementReferenceException e) {
                    // Check to see if we have been signed out automatically.
                    final String url = driver.getCurrentUrl();
                    if (url.startsWith(SIGN_IN_URL_START)) {
                        // If so, sign in again and continue collecting content from the same position in the reader.
                        kindle.signIn(driver, email, password);
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

            // Currently, the entire DOM tree is traversed after every page turn. This is slow, but accurate.
            // We can't make any guarantees about the structure of the DOM for any given book.
            // Specifically:
            // 1 - We can't assume that once we've seen an element without text that its text won't be filled in later.
            //     See `https://read.amazon.com/?asin=B01A5C7DC0`.
            // 2 - We can't assume that elements are always loaded in ID order.
            // TODO: Make traversal of children more efficient (by skipping parents whose ID have already been scraped?).
            // Return the visible text of all relevant children, if any exist.
            final List<WebElement> children = element.findElements(By.xpath("./*"));
            if (children.size() > 0) {
                if (isBelowIframe) {
                    if (children.stream().allMatch(KindleContent::canAddChildElementContent)) {
                        for (WebElement child : children) {
                            addVisibleContent(driver, child, true);
                        }
                        return;
                    }
                    // If not all children can be added, then this element - the parent - will be added.
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
                // Extract each child of the body.
                // Because the content of the body itself (id=`a:0`) is always changing, we never want it to be added.
                // See `https://read.amazon.com/?asin=B009NGHNJI`.
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

            // Add the visible text to the keyed collection (Map).
            if (isStandardId(id)) {
                idToText.put(id, visibleText);
                return;
            } else if (isStandardId(dataNid)) {
                idToText.put(dataNid, visibleText);
                return;
            }
            throw new RuntimeException("Found <" + tag + "> element with non-standard ID `" + id + "` at `" + driver.getCurrentUrl() + "` with text `" + visibleText + "`. Consider collecting this content manually. Skipping.");
        }

        private static boolean isStandardId(String id) {
            return id != null && id.contains(":");
        }

        private static boolean canAddChildElementContent(WebElement child) {
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

        private static String getImageUrlFromSrc(String src) {
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
}
