package com.ericrobertbrewer.bookspider.sites.meta;

import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.web.WebUtils;
import com.ericrobertbrewer.web.driver.DriverUtils;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.*;

import java.io.*;
import java.nio.file.Files;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CommonSenseMedia extends SiteScraper {

    public static void main(String[] args) throws IOException {
        Launcher.launch(args, new Provider<CommonSenseMedia>() {
            @Override
            public CommonSenseMedia newInstance(Logger logger) {
                return new CommonSenseMedia(logger);
            }

            @Override
            public String getId() {
                return Folders.ID_COMMONSENSEMEDIA;
            }

            @Override
            public void onComplete(CommonSenseMedia instance) {
            }
        });
    }

    public static class Book {
        public String id;
        public String title;
        public String authors;
        public String illustrators = null;
        public String age;
        public int stars;
        public String kicker;
        public String amazonUrl = null;
        public String appleBooksUrl = null;
        public String googlePlayUrl = null;
        public String genre;
        public String topics = null;
        public String type;
        public String know = null;
        public String story = null;
        public String good = null;
        public String talk = null;
        public String publishers = null;
        public String publicationDate = null;
        public String publishersRecommendedAges = null;
        public int pages = -1;
        public long lastUpdated;
        public String asin = null;
    }

    public static class BookCategory {
        public String bookId;
        public String categoryId;
        public int level;
        public String explanation = null;
    }

    private static final DateFormat PUBLICATION_DATE_FORMAT_WEB = new SimpleDateFormat("MMMM d, yyyy", Locale.US);
    private static final DateFormat PUBLICATION_DATE_FORMAT_DATABASE = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private final AtomicBoolean isExploringFrontier = new AtomicBoolean(false);
    private final AtomicInteger scrapeThreadsRunning = new AtomicInteger(0);

    private CommonSenseMedia(Logger logger) {
        super(logger);
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, final Launcher.Callback callback) {
        // Collect arguments.
        if (args.length > 3) {
            throw new IllegalArgumentException("Usage: [threads=1] [max-retries=1] [force=false]");
        }
        final int threads;
        if (args.length > 0) {
            threads = Integer.parseInt(args[0]);
        } else {
            threads = 1;
        }
        final int maxRetries;
        if (args.length > 1) {
            maxRetries = Integer.parseInt(args[1]);
        } else {
            maxRetries = 1;
        }
        final boolean force;
        if (args.length > 2) {
            force = Boolean.parseBoolean(args[2]);
        } else {
            force = false;
        }

        // Create thread-safe frontier data structure.
        final Queue<String> frontier = new ConcurrentLinkedQueue<>();
        final File frontierFile = new File(contentFolder, "frontier.txt");
        final boolean exists = frontierFile.exists();
        if (exists) {
            try {
                final List<String> bookIds = Files.readAllLines(frontierFile.toPath());
                for (String bookId : bookIds) {
                    frontier.offer(bookId.trim());
                }
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Unable to read existing frontier file.", e);
                return;
            }
        }
        // Create PrintWriter to write the frontier.
        final PrintStream frontierOut;
        try {
            frontierOut = new PrintStream(new FileOutputStream(frontierFile, exists));
        } catch (FileNotFoundException e) {
            getLogger().log(Level.SEVERE, "Unable to write to frontier file.", e);
            return;
        }

        // Create DatabaseHelper.
        final DatabaseHelper databaseHelper = new DatabaseHelper(getLogger());
        databaseHelper.connectToContentsDatabase();

        // Populate the frontier.
        final Thread frontierThread = new Thread(() -> {
            isExploringFrontier.set(true);
            final WebDriver frontierDriver = factory.newInstance();
            exploreFrontier(frontierDriver, frontier, frontierOut);
            frontierDriver.quit();
            isExploringFrontier.set(false);
            if (scrapeThreadsRunning.get() == 0) {
                databaseHelper.close();
                callback.onComplete();
            }
        }, "frontier");
        frontierThread.start();

        // Create separate threads to scrape books.
        scrapeBooksThreaded(frontier, threads, factory, databaseHelper, maxRetries, force, callback);
    }

    /**
     * Performed by the frontier thread.
     *
     * @param driver      Driver.
     * @param frontier    Queue of book IDs to scrape.
     * @param frontierOut Writer to file which contains unique book IDs.
     */
    private void exploreFrontier(WebDriver driver, Queue<String> frontier, PrintStream frontierOut) {
        // Keep a running set of book IDs to avoid writing duplicates.
        final Set<String> frontierSet = new HashSet<>(frontier);
        // Simply scrape each page.
        int page = 0;
        boolean hasReachedEnd = false;
        while (!hasReachedEnd) {
            int retries = 3;
            while (retries > 0) {
                try {
                    hasReachedEnd = exploreFrontierPage(driver, frontier, frontierSet, frontierOut, page);
                    break;
                } catch (NoSuchElementException e) {
                    getLogger().log(Level.WARNING, "Unable to find web element while exploring frontier at page " + page + ".", e);
                } catch (TimeoutException e) {
                    getLogger().log(Level.WARNING, "Received timeout while exploring frontier at page " + page + ".", e);
                    // Give the site 10 seconds to recover.
                    DriverUtils.sleep(10000L);
                }
                retries--;
            }
            page++;
        }
        getLogger().log(Level.INFO, "Collected " + frontierSet.size() + " unique book IDs, ending on page " + page + ".");
    }

    private boolean exploreFrontierPage(WebDriver driver, Queue<String> frontier, Set<String> frontierSet, PrintStream frontierOut, int page) {
        driver.navigate().to("https://www.commonsensemedia.org/book-reviews?page=" + page);
        DriverUtils.sleep(1000L);
        DriverUtils.scrollDown(driver, 40, 50L);
        // Scrape each item's book ID.
        final WebElement reviewsBrowseDiv = driver.findElement(By.className("view-display-id-ctools_context_reviews_browse"));
        final WebElement viewContentDiv = reviewsBrowseDiv.findElement(By.className("view-content"));
        final List<WebElement> viewsRows = viewContentDiv.findElements(By.className("views-row"));
        for (WebElement viewsRow : viewsRows) {
            try {
                final WebElement csmButtonA = viewsRow.findElement(By.className("csm-button"));
                final String url = csmButtonA.getAttribute("href");
                final String bookId = WebUtils.getLastUrlComponent(url);
                if (!frontierSet.contains(bookId)) {
                    frontier.add(bookId);
                    frontierOut.println(bookId);
                    frontierSet.add(bookId);
                }
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Could not find 'Continue Reading' button on page " + page + ".", e);
            }
        }
        // Quit when the "last page" link disappears.
        final WebElement pagerUl = reviewsBrowseDiv.findElement(By.className("pager"));
        try {
            pagerUl.findElement(By.className("pager-last"));
        } catch (NoSuchElementException e) {
            return true;
        }
        return false;
    }

    private void scrapeBooksThreaded(Queue<String> frontier, int threads, WebDriverFactory factory, DatabaseHelper databaseHelper, int maxRetries, boolean force, Launcher.Callback callback) {
        for (int i = 0; i < threads; i++) {
            final Thread scrapeThread = new Thread(() -> {
                scrapeThreadsRunning.incrementAndGet();
                final WebDriver driver = factory.newInstance();
                // Widen the browser window.
                driver.manage().window().setSize(new Dimension(1280, 978));
                // Start scraping.
                scrapeBooks(frontier, driver, databaseHelper, maxRetries, force);
                // Finish.
                driver.quit();
                scrapeThreadsRunning.decrementAndGet();
                if (!isExploringFrontier.get() && scrapeThreadsRunning.get() == 0) {
                    databaseHelper.close();
                    callback.onComplete();
                }
            }, "scrape-" + i);
            scrapeThread.start();
        }
    }

    /**
     * Performed by the scrape thread.
     *
     * @param frontier       Queue of book IDs to scrape.
     * @param driver         Driver.
     * @param databaseHelper To contents database. Should have already been connected.
     *                       This method does not close the connection to the database.
     */
    private void scrapeBooks(Queue<String> frontier, WebDriver driver, DatabaseHelper databaseHelper, int maxRetries, boolean force) {
        // Start scraping.
        getLogger().log(Level.INFO, "Scraping details...");
        while (isExploringFrontier.get() || !frontier.isEmpty()) {
            // Wait for frontier to populate before polling.
            if (frontier.isEmpty()) {
                DriverUtils.sleep(5000L);
                continue;
            }
            final String bookId = frontier.poll();
            // Ignore books which are fresh enough.
            if (!force && !shouldUpdateBook(databaseHelper, bookId)) {
                continue;
            }
            getLogger().log(Level.INFO, "Started scraping book `" + bookId + "`.");
            int retries = maxRetries;
            while (retries > 0) {
                try {
                    scrapeBook(driver, bookId, databaseHelper, force);
                    break;
                } catch (SQLException e) {
                    getLogger().log(Level.SEVERE, "SQLException thrown while scraping book `" + bookId + "`.", e);
                } catch (NoSuchElementException e) {
                    getLogger().log(Level.WARNING, "Unable to find web element for book `" + bookId + "`.", e);
                } catch (TimeoutException e) {
                    getLogger().log(Level.WARNING, "Received timeout while scraping book `" + bookId + "`.", e);
                    // Give the site 10 seconds to recover.
                    DriverUtils.sleep(10000L);
                }
                retries--;
            }
        }
        getLogger().log(Level.INFO, "Done scraping details.");
    }

    private boolean shouldUpdateBook(DatabaseHelper databaseHelper, String id) {
        // TODO: Check freshness of DB entry. Re-scrape the book details if its data is relatively stale.
        try {
            return !databaseHelper.recordExists(DatabaseHelper.TABLE_COMMONSENSEMEDIA_BOOKS, "id", id);
        } catch (SQLException ignored) {
        }
        return true;
    }

    private void scrapeBook(WebDriver driver, String bookId, DatabaseHelper databaseHelper, boolean force) throws SQLException, NoSuchElementException {
        driver.navigate().to("https://www.commonsensemedia.org/book-reviews/" + bookId);
        DriverUtils.sleep(1500L);

        // Create the book record to be saved in the database.
        final Book book = new Book();
        book.id = bookId;

        // Find the `content` <div>.
        final WebElement contentDiv = driver.findElement(By.id("content"));

        // Find the top wrapper (upper section).
        final WebElement topWrapperDiv = contentDiv.findElement(By.className("panel-content-top-wrapper"));
        final WebElement topDiv = topWrapperDiv.findElement(By.className("panel-content-top"));

        // Extract title.
        final WebElement titleDiv = topDiv.findElement(By.className("pane-node-title"));
        book.title = titleDiv.getText().trim();

        // Extract age.
        final WebElement topMainDiv = topDiv.findElement(By.className("panel-content-top-main"));
        final WebElement recommendedAgeDiv = topMainDiv.findElement(By.className("field-name-field-review-recommended-age"));
        final String ageText = recommendedAgeDiv.getText().trim();
        if (ageText.startsWith("age ")) {
            book.age = ageText.substring("age ".length()).trim();
        } else {
            book.age = ageText;
        }

        // Extract stars.
        final WebElement paneNodeFieldStarsRatingDiv = topMainDiv.findElement(By.className("pane-node-field-stars-rating"));
        final WebElement fieldStarsRatingDiv = paneNodeFieldStarsRatingDiv.findElement(By.className("field_stars_rating"));
        final String starsClasses = fieldStarsRatingDiv.getAttribute("class");
        final int starsRatingIndex = starsClasses.indexOf("rating-");
        if (starsRatingIndex != -1) {
            final String starsRatingString = starsClasses.substring(starsRatingIndex + "rating-".length(), starsRatingIndex + "rating-".length() + 1);
            try {
                book.stars = Integer.parseInt(starsRatingString);
            } catch (NumberFormatException e) {
                getLogger().log(Level.WARNING, "Unable to convert stars rating string `" + starsRatingString + "` to a number.", e);
            }
        } else {
            getLogger().log(Level.WARNING, "Unable to find `rating-` in stars class attribute: `" + starsClasses + "`.");
        }

        // Extract kicker.
        final WebElement oneLinerDiv = topMainDiv.findElement(By.className("pane-node-field-one-liner"));
        book.kicker = oneLinerDiv.getText().trim();

        // Extract purchase links.
        final WebElement topRightDiv = topDiv.findElement(By.className("panel-content-top-right"));
        final WebElement buyLinksListDiv = topRightDiv.findElement(By.id("buy-links-list"));
        final WebElement buyLinksDiv = buyLinksListDiv.findElement(By.className("buy-links"));
        final WebElement buyLinksWrapperDiv = buyLinksDiv.findElement(By.className("buy-links-wrapper"));
        try {
            final WebElement buyLinksUl = buyLinksWrapperDiv.findElement(By.tagName("ul"));
            final List<WebElement> buyLinksLis = buyLinksUl.findElements(By.tagName("li"));
            for (WebElement buyLinksLi : buyLinksLis) {
                final String idType = buyLinksLi.getAttribute("id_type").trim();
                final String url = buyLinksLi.getAttribute("url").trim();
                if ("asinproduct".equals(idType)) {
                    book.amazonUrl = url;
                } else if ("itunes".equals(idType)) {
                    book.appleBooksUrl = url;
                } else if ("googleplay".equals(idType)) {
                    book.googlePlayUrl = url;
                } else {
                    getLogger().log(Level.WARNING, "Unknown purchase link for book `" + bookId + "`: id_type=`" + idType + "`, url=`" + url + "`.");
                }
            }
        } catch (NoSuchElementException e) {
            getLogger().log(Level.INFO, "Unable to find purchase links for book `" + bookId + "`. They may not exist.");
        }

        // Find the center wrapper (section).
        final WebElement centerWrapperDiv = contentDiv.findElement(By.className("center-wrapper"));
        final WebElement contentMidMainDiv = centerWrapperDiv.findElement(By.className("panel-content-mid-main"));

        // Extract categories for this book.
        final List<BookCategory> bookCategories = new ArrayList<>();
        final WebElement contentGridContainerDiv = contentMidMainDiv.findElement(By.className("pane-node-field-collection-content-grid"));
        final WebElement contentGridDiv = contentGridContainerDiv.findElement(By.className("field-name-field-collection-content-grid"));
        final WebElement contentGridItemsDiv = contentGridDiv.findElement(By.className("field-items"));
        final List<WebElement> contentGridItemDivs = contentGridItemsDiv.findElements(By.xpath("./*"));
        for (WebElement contentGridItemDiv : contentGridItemDivs) {
            try {
                final BookCategory bookCategory = new BookCategory();
                bookCategory.bookId = bookId;
                final WebElement itemLeftDiv = contentGridItemDiv.findElement(By.className("field-collection-item-field-collection-content-grid"));
                // Extract level.
                final WebElement categoryRatingDiv = itemLeftDiv.findElement(By.className("field_content_grid_rating"));
                final String categoryClasses = categoryRatingDiv.getAttribute("class");
                final String categoryClassBefore = "content-grid-rating content-grid-";
                final int categoryRatingIndex = categoryClasses.indexOf(categoryClassBefore);
                if (categoryRatingIndex != -1) {
                    final String categoryRatingString = categoryClasses.substring(categoryRatingIndex + categoryClassBefore.length(), categoryRatingIndex + categoryClassBefore.length() + 1);
                    try {
                        bookCategory.level = Integer.parseInt(categoryRatingString);
                    } catch (NumberFormatException e) {
                        getLogger().log(Level.WARNING, "Unable to convert category rating string `" + categoryRatingString + "` to a number.", e);
                    }
                } else {
                    getLogger().log(Level.WARNING, "Unable to find `rating-` in category class attribute: `" + categoryClasses + "`.");
                }
                // Extract ID.
                final WebElement categoryTypeDiv = itemLeftDiv.findElement(By.className("field-name-field-content-grid-type"));
                bookCategory.categoryId = categoryTypeDiv.getText().trim();
                // Extract explanation.
                if (bookCategory.level > 0) {
                    try {
                        final WebElement categoryExplanationDiv = itemLeftDiv.findElement(By.className("field-name-field-content-grid-rating-text"));
                        final WebElement categoryExplanationP = categoryExplanationDiv.findElement(By.tagName("p"));
                        bookCategory.explanation = categoryExplanationP.getAttribute("textContent").trim();
                    } catch (NoSuchElementException e) {
                        // This element SHOULD exist under the current condition (when rating > 0, i.e., the category is present).
                        getLogger().log(Level.WARNING, "Unable to find content explanation text for book:category `" + bookId + ":" + bookCategory.categoryId + "` with content rating " + bookCategory.level + ".");
                    }
                }
                bookCategories.add(bookCategory);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find element in content grid `field-item` element.", e);
            }
        }

        // Extract 'What Parents Need to Know'.
        final WebElement knowDiv = contentMidMainDiv.findElement(By.className("pane-node-field-parents-need-to-know"));
        final WebElement knowTextDiv = knowDiv.findElement(By.className("field-name-field-parents-need-to-know"));
        book.know = knowTextDiv.getAttribute("textContent").trim();

        // Extract 'What's the Story?'.
        final WebElement storyDiv = contentMidMainDiv.findElement(By.className("pane-node-field-what-is-story"));
        final WebElement storyTextDiv = storyDiv.findElement(By.className("field-name-field-what-is-story"));
        book.story = storyTextDiv.getAttribute("textContent").trim();

        // Extract 'Is It Any Good?'.
        final WebElement goodDiv = contentMidMainDiv.findElement(By.className("pane-node-field-any-good"));
        final WebElement goodTextDiv = goodDiv.findElement(By.className("field-name-field-any-good"));
        book.good = goodTextDiv.getAttribute("textContent").trim();

        // Extract 'Talk to Your Kids About...'.
        try {
            final WebElement talkDiv = contentMidMainDiv.findElement(By.className("pane-node-field-family-topics"));
            final WebElement talkTextDiv = talkDiv.findElement(By.className("field-name-field-family-topics"));
            final WebElement talkTextListUl = talkTextDiv.findElement(By.className("textformatter-list"));
            final StringBuilder talk = new StringBuilder();
            final List<WebElement> talkTextLis = talkTextListUl.findElements(By.tagName("li"));
            for (WebElement talkTextLi : talkTextLis) {
                final String talkLiText = talkTextLi.getAttribute("textContent").trim();
                if (talk.length() > 0) {
                    talk.append("||");
                }
                talk.append(talkLiText);
            }
            book.talk = talk.toString();
        } catch (NoSuchElementException e) {
            // May not exist.
            // See `https://www.commonsensemedia.org/book-reviews/an-awesome-book-of-love`.
            getLogger().log(Level.WARNING, "Unable to find 'Talk to your kids about...` section.", e);
        }

        // Extract authors, illustrators, genre, topics, type, publishers, publishing date, pages.
        final WebElement detailsDiv = contentMidMainDiv.findElement(By.className("pane-product-details"));
        final WebElement detailsUl = detailsDiv.findElement(By.id("review-product-details-list"));
        final List<WebElement> detailsLis = detailsUl.findElements(By.xpath("./*"));
        for (WebElement detailsLi : detailsLis) {
            final String detailsText = detailsLi.getAttribute("textContent").trim();
            if (detailsText.startsWith("Author:")) {
                book.authors = detailsText.substring("Author:".length()).trim();
            } else if (detailsText.startsWith("Authors:")) {
                book.authors = DriverUtils.getConcatenatedTexts(detailsLi, By.tagName("a"), "|");
            } else if (detailsText.startsWith("Illustrator:")) {
                book.illustrators = detailsText.substring("Illustrator:".length()).trim();
            } else if (detailsText.startsWith("Illustrators:")) {
                book.illustrators = DriverUtils.getConcatenatedTexts(detailsLi, By.tagName("a"), "|");
            } else if (detailsText.startsWith("Genre:")) {
                book.genre = detailsText.substring("Genre:".length()).toLowerCase().trim();
            } else if (detailsText.startsWith("Topics:")) {
                book.topics = DriverUtils.getConcatenatedTexts(detailsLi, By.tagName("a"), "|");
            } else if (detailsText.startsWith("Book Type:") || detailsText.startsWith("Book type:")) {
                book.type = detailsText.substring("Book Type:".length()).toLowerCase().trim();
            } else if (detailsText.startsWith("Publisher:")) {
                book.publishers = detailsText.substring("Publisher:".length()).trim();
            } else if (detailsText.startsWith("Publishers:")) {
                book.publishers = DriverUtils.getConcatenatedTexts(detailsLi, By.tagName("a"), "|");
            } else if (detailsText.startsWith("Publication Date:") || detailsText.startsWith("Publication date:")) {
                final String publicationDateString = detailsText.substring("Publication Date:".length()).trim();
                try {
                    final Date publicationDate = PUBLICATION_DATE_FORMAT_WEB.parse(publicationDateString);
                    book.publicationDate = PUBLICATION_DATE_FORMAT_DATABASE.format(publicationDate);
                } catch (ParseException e) {
                    getLogger().log(Level.WARNING, "Unable to parse publication date: `" + publicationDateString + "`.", e);
                }
            } else if (detailsText.startsWith("Publisher's recommended age(s):")) {
                book.publishersRecommendedAges = detailsText.substring("Publisher's recommended age(s):".length()).trim();
            } else if (detailsText.startsWith("Number of pages:")) {
                final String pagesString = detailsText.substring("Number of pages:".length()).trim();
                try {
                    book.pages = Integer.parseInt(pagesString);
                } catch (NumberFormatException e) {
                    getLogger().log(Level.WARNING, "Unable to parse number of pages: `" + pagesString + "`.", e);
                }
            } else if (detailsText.startsWith("Available on:")) {
                // Do nothing.
            } else if (detailsText.startsWith("Award:") || detailsText.startsWith("Awards:")) {
                // Not useful.
            } else {
                getLogger().log(Level.WARNING, "Unknown book details prefix: `" + detailsText + "`.");
            }
        }

        // Set the time when this book was last updated.
        book.lastUpdated = System.currentTimeMillis();

        // Add the book and the book categories to the database.
        final int bookResult = databaseHelper.insert(book, force);
        if (bookResult == 1) {
            getLogger().log(Level.INFO, "Successfully scraped book `" + bookId + "`.");
        } else {
            getLogger().log(Level.WARNING, "Unusual database response `" + bookResult + "` when inserting book `" + bookId + "`.");
        }
        for (BookCategory bookCategory : bookCategories) {
            final int categoryResult = databaseHelper.insert(bookCategory, force);
            if (categoryResult == 1) {
                getLogger().log(Level.INFO, "Successfully scraped book category `" + bookCategory.categoryId + "` for book `" + bookCategory.bookId + "`.");
            } else {
                getLogger().log(Level.WARNING, "Unusual database response `" + categoryResult + "` when inserting book category `" + bookId + ":" + bookCategory.categoryId + "`.");
            }
        }
    }
}
