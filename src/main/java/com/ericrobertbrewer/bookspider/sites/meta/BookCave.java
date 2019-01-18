package com.ericrobertbrewer.bookspider.sites.meta;

import com.ericrobertbrewer.bookspider.sites.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.bookspider.sites.text.AmazonKindle;
import com.ericrobertbrewer.bookspider.sites.text.AmazonPreview;
import com.ericrobertbrewer.web.WebUtils;
import com.ericrobertbrewer.web.driver.DriverUtils;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.*;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BookCave extends SiteScraper {

    public static void main(String[] args) throws IOException {
        Launcher.launch(args, new Provider<BookCave>() {
            @Override
            public BookCave newInstance(Logger logger) {
                return new BookCave(logger);
            }

            @Override
            public String getId() {
                return Folders.ID_BOOKCAVE;
            }

            @Override
            public void onComplete(BookCave instance) {
            }
        });
    }

    public static class Book {
        public String id;
        public String title;
        public String authors;
        public String summary;
        public String description = null;
        public int communityRatingsCount;
        public String communityAverageRating = null;
        public int pages = -1;
        public String genres;
        public String amazonKindleUrl = null;
        public String amazonPrintUrl = null;
        public String audibleUrl = null;
        public String appleBooksUrl = null;
        public String barnesAndNobleUrl = null;
        public String barnesAndNobleAudiobookUrl = null;
        public String barnesAndNoblePrintUrl = null;
        public String googlePlayUrl = null;
        public String koboUrl = null;
        public String smashwordsUrl = null;
        public long lastUpdated;
        public String asin = null;
    }

    public static class BookRating {
        public String bookId;
        public String rating;
        public int count;
    }

    public static class BookRatingLevel {
        public String bookId;
        public String rating;
        public String title;
        public int count;
    }

    private final AtomicBoolean isExploringFrontier = new AtomicBoolean(false);
    private final AtomicBoolean isScrapingBooks = new AtomicBoolean(false);

    private BookCave(Logger logger) {
        super(logger);
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, final Launcher.Callback callback) {
        getLogger().log(Level.INFO, "Scraping Book Cave.");
        // Create frontier.
        final Queue<String> frontier = new LinkedList<>();
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
            }
        }
        // Create PrintWriter.
        final PrintStream frontierOut;
        try {
            frontierOut = new PrintStream(new FileOutputStream(frontierFile, exists));
        } catch (FileNotFoundException e) {
            getLogger().log(Level.SEVERE, "Unable to write to frontier file.", e);
            return;
        }
        // Populate the frontier.
        final Thread frontierThread = new Thread(() -> {
            isExploringFrontier.set(true);
            final WebDriver frontierDriver = factory.newInstance();
            try {
                exploreFrontier(frontierDriver, frontier, frontierOut);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Exiting `exploreFrontier` with cause:", t);
            }
            frontierDriver.quit();
            isExploringFrontier.set(false);
            if (!isScrapingBooks.get()) {
                callback.onComplete();
            }
        }, "frontier");
        frontierThread.start();
        // Create DatabaseHelper.
        final DatabaseHelper databaseHelper = new DatabaseHelper(getLogger());
        // Create a separate thread to scrape books.
        final Thread scrapeThread = new Thread(() -> {
            isScrapingBooks.set(true);
            final WebDriver scrapeDriver = factory.newInstance();
            databaseHelper.connectToContentsDatabase();
            try {
                scrapeBooks(scrapeDriver, frontier, databaseHelper);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Exiting `scrapeBooks` with cause:", t);
            }
            databaseHelper.close();
            scrapeDriver.quit();
            isScrapingBooks.set(false);
            if (!isExploringFrontier.get()) {
                callback.onComplete();
            }
        }, "scrape");
        scrapeThread.start();
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
        int page = 1;
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
                    try {
                        // Give the site 10 seconds to recover.
                        Thread.sleep(10000L);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
                retries--;
            }
            page++;
        }
        getLogger().log(Level.INFO, "Collected " + frontierSet.size() + " unique book IDs, ending on page " + page + ".");
    }

    private boolean exploreFrontierPage(WebDriver driver, Queue<String> frontier, Set<String> frontierSet, PrintStream frontierOut, int page) {
        driver.navigate().to("https://mybookcave.com/mybookratings/rated-books/page/" + page + "/");
        // Allow page elements to load.
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        DriverUtils.scrollDown(driver, 10, 50L);
        // Scrape each item's book ID.
        final WebElement contentMain = driver.findElement(By.id("content"));
        final WebElement bookGridDiv = contentMain.findElement(By.className("book-grid"));
        final List<WebElement> ratedBookDivs = bookGridDiv.findElements(By.className("rated-book"));
        for (WebElement ratedBookDiv : ratedBookDivs) {
            try {
                final WebElement bookDetailsA = ratedBookDiv.findElement(By.className("book-details"));
                final String url = bookDetailsA.getAttribute("href");
                final String bookId = WebUtils.getLastUrlComponent(url);
                if (!frontierSet.contains(bookId)) {
                    frontier.add(bookId);
                    frontierOut.println(bookId);
                    frontierSet.add(bookId);
                }
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Could not find book details link on page " + page + ".", e);
            }
        }
        // Quit when the "next page" link disappears.
        final WebElement paginationBlockDiv = contentMain.findElement(By.className("pagination-block"));
        try {
            paginationBlockDiv.findElement(By.className("fa-angle-right"));
        } catch (NoSuchElementException e) {
            return true;
        }
        return false;
    }

    /**
     * Performed by the scrape thread.
     *
     * @param driver         Driver.
     * @param frontier       Queue of book IDs to scrape.
     * @param databaseHelper To contents database. Should have already been connected.
     *                       This method does not close the connection to the database.
     */
    private void scrapeBooks(WebDriver driver, Queue<String> frontier, DatabaseHelper databaseHelper) {
        // Start scraping.
        getLogger().log(Level.INFO, "Scraping details...");
        while (isExploringFrontier.get() || !frontier.isEmpty()) {
            // Wait for frontier to populate before polling.
            if (frontier.isEmpty()) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }
            final String bookId = frontier.poll();
            // Ignore books which are fresh enough.
            try {
                if (!shouldUpdateBook(databaseHelper, bookId)) {
                    continue;
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Unable to find book `" + bookId + "` during `shouldUpdateBook()`.", e);
                // It's safer to skip this book than to scrape its metadata.
                continue;
            }
            // Try scraping the metadata of the book with a fixed number retries.
            int retries = 3;
            while (retries > 0) {
                try {
                    scrapeBook(driver, bookId, databaseHelper);
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

    private boolean shouldUpdateBook(DatabaseHelper databaseHelper, String id) throws SQLException {
        // TODO: Check freshness of DB entry. Re-scrape the book details if its data is relatively stale.
        //noinspection RedundantIfStatement
        if (databaseHelper.recordExists(DatabaseHelper.TABLE_BOOKCAVE_BOOKS, "id", id)) {
            return false;
        }
        return true;
    }

    private void scrapeBook(WebDriver driver, String bookId, DatabaseHelper databaseHelper) throws SQLException, NoSuchElementException {
        // Scrape this book.
        getLogger().log(Level.INFO, "Scraping book: `" + bookId + "`.");
        driver.navigate().to("https://mybookcave.com/mybookratings/rated-book/" + bookId + "/");
        // Create the book record to be saved in the database.
        final Book book = new Book();
        book.id = bookId;
        final WebElement siteInnerDiv = driver.findElement(By.id("site-inner"));
        // Extract title, author, summary, pages, genres, purchase links.
        final WebElement introSection = siteInnerDiv.findElement(By.id("single-book-intro"));
        final WebElement detailsDiv = introSection.findElement(By.className("book-details"));
        // Extract title.
        final WebElement titleH1 = detailsDiv.findElement(By.className("title"));
        book.title = titleH1.getText().trim();
        // Extract author.
        final WebElement authorDiv = detailsDiv.findElement(By.className("author"));
        final WebElement authorValueDiv = authorDiv.findElement(By.className("value"));
        book.authors = DriverUtils.getConcatenatedTexts(authorValueDiv, By.tagName("a"), "|");
        // Extract summary.
        final WebElement summaryDiv = detailsDiv.findElement(By.className("summary"));
        book.summary = getDescription(summaryDiv);
        // Extract pages and genres.
        final WebElement metadataDiv = detailsDiv.findElement(By.className("metadata"));
        final List<WebElement> metaDivs = metadataDiv.findElements(By.className("meta"));
        for (WebElement metaDiv : metaDivs) {
            final List<WebElement> metaRowDivs = metaDiv.findElements(By.className("meta-row"));
            for (WebElement metaRowDiv : metaRowDivs) {
                final WebElement metaNameDiv = metaRowDiv.findElement(By.className("meta-name"));
                final WebElement metaValueDiv = metaRowDiv.findElement(By.className("meta-value"));
                final String nameText = metaNameDiv.getText().trim();
                final String valueText = metaValueDiv.getText().replaceAll(",", "").trim();
                if (nameText.startsWith("Pages")) {
                    try {
                        book.pages = Integer.parseInt(valueText);
                    } catch (NumberFormatException e) {
                        getLogger().log(Level.WARNING, "Unable to parse pages in book details: `" + nameText + "`:`" + valueText + "`.", e);
                    }
                } else if (nameText.startsWith("Genres")) {
                    book.genres = DriverUtils.getConcatenatedTexts(metaValueDiv, By.tagName("a"), "|", text -> text.replaceAll(" / ", "/"));
                } else {
                    getLogger().log(Level.WARNING, "Unknown meta data item `" + nameText + "`:`" + valueText + "`.");
                }
            }
        }
        // Extract purchase links.
        final WebElement purchaseLinksDiv = introSection.findElement(By.className("purchase-links"));
        final List<WebElement> purchaseLinkAs = purchaseLinksDiv.findElements(By.tagName("a"));
        for (WebElement purchaseLinkA : purchaseLinkAs) {
            final String text = purchaseLinkA.getText().trim();
            final String href = purchaseLinkA.getAttribute("href");
            if ("Amazon Kindle".equalsIgnoreCase(text)) {
                book.amazonKindleUrl = href;
            } else if ("Amazon Print".equalsIgnoreCase(text)) {
                book.amazonPrintUrl = href;
            } else if ("Audible".equalsIgnoreCase(text)) {
                book.audibleUrl = href;
            } else if ("Apple Books".equalsIgnoreCase(text)) {
                book.appleBooksUrl = href;
            } else if ("Barnes & Noble".equalsIgnoreCase(text)) {
                book.barnesAndNobleUrl = href;
            } else if ("B&N Audiobook".equalsIgnoreCase(text)) {
                book.barnesAndNobleAudiobookUrl = href;
            } else if ("B&N Print".equalsIgnoreCase(text)) {
                book.barnesAndNoblePrintUrl = href;
            } else if ("Google Play".equalsIgnoreCase(text)) {
                book.googlePlayUrl = href;
            } else if ("Kobo".equalsIgnoreCase(text)) {
                book.koboUrl = href;
            } else if ("Smashwords".equalsIgnoreCase(text)) {
                book.smashwordsUrl = href;
            } else //noinspection StatementWithEmptyBody
                if ("No retailer links available".equalsIgnoreCase(text)) {
                    // Do nothing.
                } else {
                    getLogger().log(Level.WARNING, "Unknown purchase link found: `" + text + "`.");
                }
        }
        // Extract the community rating info.
        final WebElement ratingsBarSection = siteInnerDiv.findElement(By.id("ratings-bar"));
        final WebElement communityRatingsDiv = ratingsBarSection.findElement(By.id("community-ratings"));
        // Extract the number of community ratings given to this book.
        final WebElement communityRatingsHeader = communityRatingsDiv.findElement(By.tagName("h2"));
        final String communityRatingsText = communityRatingsHeader.getText().trim();
        final String communityRatingsIntegerText = communityRatingsText.substring(communityRatingsText.indexOf("(") + 1, communityRatingsText.lastIndexOf(")"));
        try {
            book.communityRatingsCount = Integer.parseInt(communityRatingsIntegerText);
        } catch (NumberFormatException e) {
            getLogger().log(Level.WARNING, "Unable to parse book community ratings count text `" + communityRatingsIntegerText + "`.", e);
        }
        final List<BookRating> ratings = new ArrayList<>();
        final List<BookRatingLevel> levels = new ArrayList<>();
        // Some books strangely don't have any rating. Therefore, they have no average rating.
        // These books are probably not useful for our purposes.
        // See `https://mybookcave.com/mybookratings/rated-book/demon-ember/`.
        if (book.communityRatingsCount > 0) {
            // Extract the average community rating, community ratings, and rating levels.
            final WebElement communityContainerDiv = communityRatingsDiv.findElement(By.className("community-container"));
            // Extract the average community rating.
            final WebElement communityAverageDiv = communityContainerDiv.findElement(By.className("community-average"));
            final WebElement communityAverageImg = communityAverageDiv.findElement(By.tagName("img"));
            book.communityAverageRating = communityAverageImg.getAttribute("alt").trim();
            // Extract the community ratings and rating levels.
            final WebElement ratingsBarsDiv = communityContainerDiv.findElement(By.className("rating-bars"));
            final List<WebElement> ratingBarDivs = ratingsBarsDiv.findElements(By.className("rating-bar"));
            for (WebElement ratingBarDiv : ratingBarDivs) {
                final BookRating rating = new BookRating();
                rating.bookId = bookId;
                // Extract rating.
                final WebElement ratingNameSpan = ratingBarDiv.findElement(By.className("rating-name"));
                rating.rating = ratingNameSpan.getText().trim();
                // Extract count (the number of users who have given this book this rating).
                final WebElement ratingCountSpan = ratingBarDiv.findElement(By.className("rating-count"));
                final String countIntegerText = ratingCountSpan.getText().replaceAll("[()]", "").trim();
                try {
                    rating.count = Integer.parseInt(countIntegerText);
                } catch (NumberFormatException e) {
                    getLogger().log(Level.WARNING, "Unable to parse book rating count text `" + countIntegerText + "`.", e);
                }
                // Extract rating levels.
                final String className = ratingBarDiv.getAttribute("class");
                if (className.contains("has-tooltip")) {
                    // Click the rating text to open the tooltip.
                    // This is more reliable than clicking the bar, since the bar can be covered by the book cover image.
                    // See `https://mybookcave.com/mybookratings/rated-book/chronicles-of-ara/`.
                    ratingNameSpan.click();
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    try {
                        // The tooltip `div` element should become visible in the page (usually right at the bottom of the `body` element).
                        final WebElement tooltipDiv = driver.findElement(By.className("tooltip"));
                        final List<WebElement> columnDivs = tooltipDiv.findElements(By.className("column"));
                        for (WebElement columnDiv : columnDivs) {
                            final BookRatingLevel level = new BookRatingLevel();
                            level.bookId = bookId;
                            level.rating = rating.rating;
                            final WebElement titleDiv = columnDiv.findElement(By.className("level-title"));
                            final String titleText = titleDiv.getText();
                            level.title = titleText.substring(titleText.indexOf(")") + 1).trim();
                            final WebElement countSpan = columnDiv.findElement(By.className("level-count"));
                            final String levelCountIntegerText = countSpan.getText().replaceAll("[()]", "").trim();
                            try {
                                level.count = Integer.parseInt(levelCountIntegerText);
                            } catch (NumberFormatException e) {
                                getLogger().log(Level.WARNING, "Unable to parse book rating level count text `" + levelCountIntegerText + "`.", e);
                            }
                            levels.add(level);
                        }
                    } catch (NoSuchElementException e) {
                        getLogger().log(Level.SEVERE, "Unable to find tooltip element on the page after clicking rating box for book `" + bookId + "` and rating `" + rating.rating + "`.", e);
                    }
                    // Click outside of the tooltip to hide it.
                    // This allows any bars past the first to be clickable.
                    // See `https://mybookcave.com/mybookratings/rated-book/the-art-of-love/`.
                    titleH1.click();
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    getLogger().log(Level.WARNING, "Found a rating bar without a tool tip for book `" + bookId + "`.");
                }
                ratings.add(rating);
            }
        }
        // Extract the book description.
        final WebElement sidebarContainerDiv = siteInnerDiv.findElement(By.className("sidebar-container"));
        final WebElement contentMain = sidebarContainerDiv.findElement(By.id("content"));
        final WebElement singleBookDetailsSection = contentMain.findElement(By.id("single-book-details"));
        final WebElement descriptionDiv = singleBookDetailsSection.findElement(By.className("description"));
        book.description = getDescription(descriptionDiv);
        // Set the time when this book was last updated.
        book.lastUpdated = System.currentTimeMillis();
        // Add the book, book ratings, and book rating levels to the database.
        final int bookResult = databaseHelper.insert(book);
        if (bookResult != 1) {
            getLogger().log(Level.WARNING, "Unusual database response `" + bookResult + "` when inserting book `" + bookId + "`.");
        }
        for (BookRating rating : ratings) {
            final int ratingResult = databaseHelper.insert(rating);
            if (ratingResult != 1) {
                getLogger().log(Level.WARNING, "Unusual database response `" + ratingResult + "` when inserting book rating `" + bookId + ":" + rating.rating + "`.");
            }
        }
        for (BookRatingLevel level : levels) {
            final int levelResult = databaseHelper.insert(level);
            if (levelResult != 1) {
                getLogger().log(Level.WARNING, "Unusual database response `" + levelResult + "` when inserting book rating level `" + bookId + ":" + level.rating + ":" + level.title + "`.");
            }
        }
    }

    private static String getDescription(WebElement element) {
        StringBuilder description = new StringBuilder();
        final List<WebElement> children = element.findElements(By.xpath("./*"));
        if (children.size() == 0 || areAllFormatting(children)) {
            // The element has no relevant children.
            return element.getText().replaceAll("&nbsp;", " ").trim();
        } else {
            // The element has at least one non-formatting-element child.
            for (WebElement child : children) {
                // Ignore the 'Description' header.
                final String tag = child.getTagName();
                if ("h3".equalsIgnoreCase(tag)) {
                    continue;
                }
                // Ignore empty paragraphs.
                final String text = child.getText().replaceAll("&nbsp;", " ").trim();
                if (text.length() == 0) {
                    continue;
                }
                if (description.length() > 0) {
                    description.append("|");
                }
                description.append(getDescription(child));
            }
        }
        return description.toString();
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

    private static List<BookScrapeInfo> getBookScrapeInfos(DatabaseHelper databaseHelper, List<Book> books) {
        final List<BookScrapeInfo> bookScrapeInfos = new ArrayList<>();
        for (Book book : books) {
            // Skip unattainable books.
            if (book.amazonKindleUrl == null && book.amazonPrintUrl == null) {
                continue;
            }
            final String[] urls;
            if (book.amazonKindleUrl == null) {
                urls = new String[]{book.amazonPrintUrl};
            } else if (book.amazonPrintUrl == null) {
                urls = new String[]{book.amazonKindleUrl};
            } else {
                // Give multiple options for Amazon URLs.
                // Sometimes the BookCave Kindle link will have broken, though a Kindle preview still exists.
                // See `https://mybookcave.com/mybookratings/rated-book/the-warriors-path/`.
                urls = new String[]{book.amazonKindleUrl, book.amazonPrintUrl};
            }
            String price = null;
            if (book.asin != null) {
                try {
                    final AmazonKindle.Book amazonBook = databaseHelper.getAmazonBook(book.asin);
                    price = amazonBook.price;
                } catch (SQLException ignored) {
                }
            }
            bookScrapeInfos.add(new BookScrapeInfo(book.id, urls, price));
        }
        return bookScrapeInfos;
    }

    @SuppressWarnings("unused")
    private static class AmazonPreviewProvider implements Provider<AmazonPreview> {

        public static void main(String[] args) throws IOException {
            Launcher.launch(args, new AmazonPreviewProvider());
        }

        @Override
        public AmazonPreview newInstance(Logger logger) {
            final DatabaseHelper databaseHelper = new DatabaseHelper(logger);
            databaseHelper.connectToContentsDatabase();
            final List<Book> allBooks;
            try {
                allBooks = databaseHelper.getBookCaveBooks();
            } catch (SQLException e) {
                throw new RuntimeException("Unable to retrieve books.", e);
            } finally {
                databaseHelper.close();
            }
            final List<BookScrapeInfo> bookScrapeInfos = getBookScrapeInfos(databaseHelper, allBooks);
            return new AmazonPreview(logger, bookScrapeInfos);
        }

        @Override
        public String getId() {
            return Folders.ID_BOOKCAVE_AMAZON_PREVIEW;
        }

        @Override
        public void onComplete(AmazonPreview instance) {
        }
    }

    @SuppressWarnings("unused")
    private static class AmazonKindleProvider implements Provider<AmazonKindle>, AmazonKindle.Listener {

        public static void main(String[] args) throws IOException {
            Launcher.launch(args, new AmazonKindleProvider());
        }

        private DatabaseHelper databaseHelper;

        @Override
        public AmazonKindle newInstance(Logger logger) {
            databaseHelper = new DatabaseHelper(logger);
            databaseHelper.connectToContentsDatabase();
            final List<Book> allBooks;
            try {
                allBooks = databaseHelper.getBookCaveBooks();
            } catch (SQLException e) {
                throw new RuntimeException("Unable to retrieve books.", e);
            }
            final List<BookScrapeInfo> bookScrapeInfos = getBookScrapeInfos(databaseHelper, allBooks);
            final AmazonKindle amazonKindle = new AmazonKindle(logger, bookScrapeInfos);
            amazonKindle.setListener(this);
            return amazonKindle;
        }

        @Override
        public String getId() {
            return Folders.ID_BOOKCAVE_AMAZON_KINDLE;
        }

        @Override
        public void onComplete(AmazonKindle instance) {
            instance.setListener(null);
            databaseHelper.close();
        }

        @Override
        public boolean shouldUpdateBook(String bookId) {
            final Book book;
            try {
                book = databaseHelper.getBookCaveBook(bookId);
            } catch (SQLException e) {
                databaseHelper.getLogger().log(Level.SEVERE, "Encountered error while retrieving book `" + bookId + "` from database.", e);
                return false;
            }
            if (book == null) {
                databaseHelper.getLogger().log(Level.SEVERE, "Unable to retrieve book `" + bookId + "` from database.");
                return false;
            }
            if (book.asin == null) {
                return true;
            }
            final AmazonKindle.Book amazonBook;
            try {
                amazonBook = databaseHelper.getAmazonBook(book.asin);
            } catch (SQLException e) {
                databaseHelper.getLogger().log(Level.SEVERE, "Encountered error while retrieving Amazon book `asin=" + book.asin + "` from database.", e);
                return false;
            }
            if (amazonBook == null) {
                databaseHelper.getLogger().log(Level.SEVERE, "Unable to retrieveAmazon book `asin=" + book.asin + "` from database.");
                return false;
            }
            return !amazonBook.isKindleUnlimited &&
                    !AmazonKindle.isPriceFree(amazonBook.price) &&
                    System.currentTimeMillis() - amazonBook.lastUpdated > CHECK_AMAZON_PRICE_DELAY_MILLIS;
        }

        @Override
        public void onUpdateBook(String bookId, String asin, boolean isKindleUnlimited, String price) {
            // Update only the `asin` field for the BookCaveBook row.
            try {
                final int updateResult = databaseHelper.updateBookAsin(bookId, asin);
                if (updateResult != 1) {
                    databaseHelper.getLogger().log(Level.WARNING, "Unexpected result `" + updateResult + "` after updating book `" + bookId + "`.");
                }
            } catch (SQLException e) {
                databaseHelper.getLogger().log(Level.SEVERE, "Unable to update book `" + bookId + "` in database.");
            }
            // Update all fields for the AmazonBook row.
            try {
                final AmazonKindle.Book amazonBook = new AmazonKindle.Book();
                amazonBook.asin = asin;
                amazonBook.isKindleUnlimited = isKindleUnlimited;
                amazonBook.price = price;
                amazonBook.lastUpdated = System.currentTimeMillis();
                final int amazonResult = databaseHelper.insertOrReplace(amazonBook);
                if (amazonResult != 1) {
                    databaseHelper.getLogger().log(Level.WARNING, "Unexpected result `" + amazonResult + "` after updating Amazon book `asin=" + asin + "`.");
                }
            } catch (SQLException e) {
                databaseHelper.getLogger().log(Level.SEVERE, "Unable to update Amazon book `asin=" + asin + "` in database.");
            }
        }

        /**
         * The amount of time to wait in milliseconds to re-check the Amazon store page
         * for whether or not a book has been made available on Kindle Unlimited or
         * has been made free.
         * Currently set to seven (7) days.
         */
        private static final long CHECK_AMAZON_PRICE_DELAY_MILLIS = 1000L * 60 * 60 * 24 * 7;
    }
}
