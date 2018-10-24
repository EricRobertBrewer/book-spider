package com.ericrobertbrewer.bookspider.sites;

import com.ericrobertbrewer.bookspider.AbstractDatabaseHelper;
import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.SiteScraper;
import com.ericrobertbrewer.web.DriverUtils;
import com.ericrobertbrewer.web.WebDriverFactory;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.*;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BookCave extends SiteScraper {

    public static void main(String[] args) throws IOException {
        Launcher.launch(args, new Provider() {
            @Override
            public Class<? extends SiteScraper> getScraperClass() {
                return BookCave.class;
            }

            @Override
            public SiteScraper newInstance(Logger logger) {
                return new BookCave(logger);
            }

            @Override
            public String getId() {
                return Folders.ID_BOOK_CAVE;
            }
        });
    }

    private final AtomicBoolean isExploringFrontier = new AtomicBoolean(false);
    private final AtomicBoolean isScrapingBooks = new AtomicBoolean(false);

    private BookCave(Logger logger) {
        super(logger);
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force, final Launcher.Callback callback) {
        if (force) {
            throw new IllegalArgumentException("BookCave does not support `force`=`true`.");
        }
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
            final WebDriver frontierDriver = factory.newChromeDriver();
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
            final WebDriver scrapeDriver = factory.newChromeDriver();
            databaseHelper.connect(contentFolder.getPath() + Folders.SLASH + "contents.db");
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
     * @param driver Driver.
     * @param frontier Queue of book IDs to scrape.
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
                final String bookId = getLastUrlComponent(url);
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
     * @param driver Driver.
     * @param frontier Queue of book IDs to scrape.
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
                    try {
                        // Give the site 10 seconds to recover.
                        Thread.sleep(10000L);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
                retries--;
            }
        }
        getLogger().log(Level.INFO, "Done scraping details.");
    }

    private void scrapeBook(WebDriver driver, String bookId, DatabaseHelper databaseHelper) throws SQLException, NoSuchElementException {
        // Ignore books which are fresh enough.
        if (!databaseHelper.shouldUpdateBook(bookId)) {
            return;
        }
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
        book.author = authorValueDiv.getText().trim();
        // Extract summary.
        final WebElement summaryDiv = detailsDiv.findElement(By.className("summary"));
        book.summary = summaryDiv.getText().trim();
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
                    final StringBuilder genres = new StringBuilder();
                    final List<WebElement> metaValueAs = metaValueDiv.findElements(By.tagName("a"));
                    for (WebElement metaValueA : metaValueAs) {
                        final String aText = metaValueA.getText().trim();
                        if (aText.isEmpty()) {
                            continue;
                        }
                        if (genres.length() > 0) {
                            genres.append("|");
                        }
                        genres.append(aText.replaceAll(" / ", "/"));
                    }
                    book.genres = genres.toString();
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
            } else if ("B&N Print".equalsIgnoreCase(text)) {
                book.barnesAndNoblePrintUrl = href;
            } else if ("Google Play".equalsIgnoreCase(text)) {
                book.googlePlayUrl = href;
            } else if ("Kobo".equalsIgnoreCase(text)) {
                book.koboUrl = href;
            } else if ("Smashwords".equalsIgnoreCase(text)) {
                book.smashwordsUrl = href;
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
        // Extract the average community rating, community ratings, and rating levels.
        final WebElement communityContainerDiv = communityRatingsDiv.findElement(By.className("community-container"));
        // Extract the average community rating.
        final WebElement communityAverageDiv = communityContainerDiv.findElement(By.className("community-average"));
        final WebElement communityAverageImg = communityAverageDiv.findElement(By.tagName("img"));
        book.communityAverageRating = communityAverageImg.getAttribute("alt").trim();
        // Extract the community ratings and rating levels.
        final List<BookRating> ratings = new ArrayList<>();
        final List<BookRatingLevel> levels = new ArrayList<>();
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
                // Click the bar to open the tooltip.
                final WebElement barContainerDiv = ratingBarDiv.findElement(By.className("bar-container"));
                final WebElement barDiv = barContainerDiv.findElement(By.className("bar"));
                barDiv.click();
                try {
                    Thread.sleep(250L);
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
            } else {
                getLogger().log(Level.WARNING, "Found a rating bar without a tool tip for book `" + bookId + "`.");
            }
            ratings.add(rating);
        }
        // Extract the book description.
        final WebElement sidebarContainerDiv = siteInnerDiv.findElement(By.className("sidebar-container"));
        final WebElement contentMain = sidebarContainerDiv.findElement(By.id("content"));
        final WebElement singleBookDetailsSection = contentMain.findElement(By.id("single-book-details"));
        final WebElement descriptionDiv = singleBookDetailsSection.findElement(By.className("description"));
        book.description = getDescription(descriptionDiv);
        // Set the time when this book was last updated.
        book.lastUpdated = System.currentTimeMillis();
        // Add the book and the book categories to the database.
        final int bookResult = databaseHelper.insertBook(book);
        if (bookResult != 1) {
            getLogger().log(Level.WARNING, "Unusual database response `" + bookResult + "` when inserting book `" + bookId + "`.");
        }
        for (BookRating rating : ratings) {
            final int ratingResult = databaseHelper.insertBookRating(rating);
            if (ratingResult != 1) {
                getLogger().log(Level.WARNING, "Unusual database response `" + ratingResult + "` when inserting book rating `" + bookId + ":" + rating.rating + "`.");
            }
        }
        for (BookRatingLevel level : levels) {
            final int levelResult = databaseHelper.insertBookRatingLevel(level);
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

    private static final Set<String> FORMATTING_TAGS = new HashSet<>();
    static {
        FORMATTING_TAGS.add("em");
        FORMATTING_TAGS.add("i");
        FORMATTING_TAGS.add("strong");
        FORMATTING_TAGS.add("b");
        FORMATTING_TAGS.add("strike");
        FORMATTING_TAGS.add("s");
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

    private static class Book {
        String id;
        String title;
        String author;
        String summary;
        int pages = -1;
        String genres;
        int communityRatingsCount;
        String communityAverageRating;
        String amazonKindleUrl = null;
        String amazonPrintUrl = null;
        String audibleUrl = null;
        String appleBooksUrl = null;
        String barnesAndNobleUrl = null;
        String barnesAndNoblePrintUrl = null;
        String googlePlayUrl = null;
        String koboUrl = null;
        String smashwordsUrl = null;
        String description = null;
        long lastUpdated;
    }

    private static class BookRating {
        String bookId;
        String rating;
        int count;
    }

    private static class BookRatingLevel {
        String bookId;
        String rating;
        String title;
        int count;
    }

    private static class DatabaseHelper extends AbstractDatabaseHelper {
        private static final String TABLE_BOOKS = "Books";
        private static final String TABLE_BOOK_RATINGS = "BookRatings";
        private static final String TABLE_BOOK_RATING_LEVELS = "BookRatingLevels";

        DatabaseHelper(Logger logger) {
            super(logger);
        }

        boolean shouldUpdateBook(String id) throws SQLException {
            // TODO: Check freshness of DB entry. Re-scrape the book details if its data is relatively stale.
            return !bookExists(id);
        }

        boolean bookExists(String id) throws SQLException {
            return recordExists(TABLE_BOOKS, "id", id);
        }

        int insertBook(Book book) throws SQLException {
            ensureTableExists(TABLE_BOOKS);
            final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOKS +
                    "(id,title,author,summary,pages,genres,community_ratings_count,community_average_rating,amazon_kindle_url,amazon_print_url,audible_url,apple_books_url,barnes_and_noble_url,barnes_and_noble_print_url,google_play_url,kobo_url,smashwords_url,description,last_updated)\n" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);");
            insert.setString(1, book.id);
            insert.setString(2, book.title);
            insert.setString(3, book.author);
            insert.setString(4, book.summary);
            if (book.pages != -1) {
                insert.setInt(5, book.pages);
            } else {
                insert.setNull(5, Types.INTEGER);
            }
            insert.setString(6, book.genres);
            insert.setInt(7, book.communityRatingsCount);
            insert.setString(8, book.communityAverageRating);
            setStringOrNull(insert, 9, book.amazonKindleUrl);
            setStringOrNull(insert, 10, book.amazonPrintUrl);
            setStringOrNull(insert, 11, book.audibleUrl);
            setStringOrNull(insert, 12, book.appleBooksUrl);
            setStringOrNull(insert, 13, book.barnesAndNobleUrl);
            setStringOrNull(insert, 14, book.barnesAndNoblePrintUrl);
            setStringOrNull(insert, 15, book.googlePlayUrl);
            setStringOrNull(insert, 16, book.koboUrl);
            setStringOrNull(insert, 17, book.smashwordsUrl);
            setStringOrNull(insert, 18, book.description);
            insert.setLong(19, book.lastUpdated);
            final int result = insert.executeUpdate();
            insert.close();
            return result;
        }

        int insertBookRating(BookRating rating) throws SQLException {
            ensureTableExists(TABLE_BOOK_RATINGS);
            final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOK_RATINGS +
                    "(book_id,rating,count)\n" +
                    " VALUES(?,?,?);");
            insert.setString(1, rating.bookId);
            insert.setString(2, rating.rating);
            insert.setInt(3, rating.count);
            final int result = insert.executeUpdate();
            insert.close();
            return result;
        }

        int insertBookRatingLevel(BookRatingLevel level) throws SQLException {
            ensureTableExists(TABLE_BOOK_RATING_LEVELS);
            final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOK_RATING_LEVELS +
                    "(book_id,rating,title,count)\n" +
                    " VALUES(?,?,?,?);");
            insert.setString(1, level.bookId);
            insert.setString(2, level.rating);
            insert.setString(3, level.title);
            insert.setInt(4, level.count);
            final int result = insert.executeUpdate();
            insert.close();
            return result;
        }

        @Override
        public void ensureTableExists(String name) throws SQLException {
            if (TABLE_BOOKS.equalsIgnoreCase(name)) {
                final Statement statement = getConnection().createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKS + " (\n" +
                        " id TEXT PRIMARY KEY,\n" + // the-haunting-of-gillespie-house
                        " title TEXT NOT NULL,\n" + // The Haunting of Gillespie House
                        " author TEXT NOT NULL,\n" + // Darcy Coates
                        " summary TEXT NOT NULL,\n" + // Elle is thrilled to spend a month minding the beautiful Gillespie property...
                        " pages INTEGER DEFAULT NULL,\n" + // 200
                        " genres TEXT DEFAULT NULL,\n" + // Fiction/Horror
                        " community_ratings_count INTEGER NOT NULL,\n" + // 1
                        " community_average_rating TEXT NOT NULL,\n" + // Moderate
                        " amazon_kindle_url TEXT DEFAULT NULL,\n" + // https://...
                        " amazon_print_url TEXT DEFAULT NULL,\n" + // https://...
                        " audible_url TEXT DEFAULT NULL,\n" + // https://...
                        " apple_books_url TEXT DEFAULT NULL,\n" + // https://...
                        " barnes_and_noble_url TEXT DEFAULT NULL,\n" + // https://...
                        " barnes_and_noble_print_url TEXT DEFAULT NULL,\n" + // https://...
                        " google_play_url TEXT DEFAULT NULL,\n" + // https://...
                        " kobo_url TEXT DEFAULT NULL,\n" + // https://...
                        " smashwords_url TEXT DEFAULT NULL,\n" + // https://...
                        " description TEXT DEFAULT NULL,\n" + // Elle is thrilled to spend a month minding the beautiful Gillespie property...
                        " last_updated INTEGER NOT NULL\n" + // System.currentTimeMillis() -> long
                        ");");
            } else if (TABLE_BOOK_RATINGS.equalsIgnoreCase(name)) {
                final Statement statement = getConnection().createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOK_RATINGS + " (\n" +
                        " book_id TEXT NOT NULL,\n" +
                        " rating TEXT NOT NULL,\n" +
                        " count INTEGER NOT NULL,\n" +
                        " PRIMARY KEY (book_id, rating)\n" +
                        ");");
            } else if (TABLE_BOOK_RATING_LEVELS.equalsIgnoreCase(name)) {
                final Statement statement = getConnection().createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOK_RATING_LEVELS + " (\n" +
                        " book_id TEXT NOT NULL,\n" +
                        " rating TEXT NOT NULL,\n" +
                        " title TEXT NOT NULL,\n" +
                        " count INTEGER NOT NULL,\n" +
                        " PRIMARY KEY (book_id, rating, title)\n" +
                        ");");
            } else {
                throw new IllegalArgumentException("Unknown table name: `" + name + "`.");
            }
        }
    }
}
