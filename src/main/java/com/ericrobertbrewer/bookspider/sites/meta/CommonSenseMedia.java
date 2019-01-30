package com.ericrobertbrewer.bookspider.sites.meta;

import com.ericrobertbrewer.bookspider.sites.db.AbstractDatabaseHelper;
import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.web.driver.DriverUtils;
import com.ericrobertbrewer.web.driver.WebDriverFactory;
import com.ericrobertbrewer.web.WebUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.*;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public static class Migrate {

        public static void main(String[] args) throws SQLException {
            final CommonSenseMedia.DatabaseHelper databaseHelper = new CommonSenseMedia.DatabaseHelper(null);
            databaseHelper.connect("../content/commonsensemedia/contents.db");
            final com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper unifiedDatabaseHelper = new com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper(null);
            unifiedDatabaseHelper.connectToContentsDatabase();
            final List<Book> books = databaseHelper.getBooks();
            for (Book book : books) {
                unifiedDatabaseHelper.insert(book);
            }
            final List<BookCategory> bookCategories = databaseHelper.getBookCategories();
            for (BookCategory bookCategory : bookCategories) {
                unifiedDatabaseHelper.insert(bookCategory);
            }
            databaseHelper.close();
            unifiedDatabaseHelper.close();
        }
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
    private final AtomicBoolean isScrapingBooks = new AtomicBoolean(false);

    private CommonSenseMedia(Logger logger) {
        super(logger);
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, String[] args, final Launcher.Callback callback) {
        getLogger().log(Level.INFO, "Scraping Common Sense Media.");
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
            exploreFrontier(frontierDriver, frontier, frontierOut);
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
            databaseHelper.connect(contentFolder.getPath() + Folders.SLASH + "contents.db");
            scrapeBooks(scrapeDriver, frontier, databaseHelper);
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
        driver.navigate().to("https://www.commonsensemedia.org/book-reviews?page=" + page);
        // Allow page elements to load.
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
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

    @SuppressWarnings("StatementWithEmptyBody")
    private void scrapeBook(WebDriver driver, String bookId, DatabaseHelper databaseHelper) throws SQLException, NoSuchElementException {
        // Ignore books which are fresh enough.
        if (!databaseHelper.shouldUpdateBook(bookId)) {
            return;
        }
        // Scrape this book.
        getLogger().log(Level.INFO, "Scraping book: `" + bookId + "`.");
        driver.navigate().to("https://www.commonsensemedia.org/book-reviews/" + bookId);
        // Create the book record to be saved in the database.
        final Book book = new Book();
        book.id = bookId;
        final WebElement contentDiv = driver.findElement(By.id("content"));
        final WebElement topWrapperDiv = contentDiv.findElement(By.className("panel-content-top-wrapper"));
        // Extract title.
        final WebElement titleDiv = topWrapperDiv.findElement(By.className("pane-node-title"));
        book.title = titleDiv.getText().trim();
        // Extract age, stars, and kicker (one-liner).
        final WebElement contentTopMainDiv = topWrapperDiv.findElement(By.className("panel-content-top-main"));
        final WebElement recommendedAgeDiv = contentTopMainDiv.findElement(By.className("field-name-field-review-recommended-age"));
        final String ageText = recommendedAgeDiv.getText().trim();
        if (ageText.startsWith("age ")) {
            book.age = ageText.substring("age ".length()).trim();
        } else {
            book.age = ageText;
        }
        final WebElement paneNodeFieldStarsRatingDiv = contentTopMainDiv.findElement(By.className("pane-node-field-stars-rating"));
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
        final WebElement oneLinerDiv = contentTopMainDiv.findElement(By.className("pane-node-field-one-liner"));
        book.kicker = oneLinerDiv.getText().trim();
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
        final int bookResult = databaseHelper.insertBook(book);
        if (bookResult != 1) {
            getLogger().log(Level.WARNING, "Unusual database response `" + bookResult + "` when inserting book `" + bookId + "`.");
        }
        for (BookCategory bookCategory : bookCategories) {
            final int categoryResult = databaseHelper.insertBookCategory(bookCategory);
            if (categoryResult != 1) {
                getLogger().log(Level.WARNING, "Unusual database response `" + categoryResult + "` when inserting book category `" + bookId + ":" + bookCategory.categoryId + "`.");
            }
        }
    }

    private static class DatabaseHelper extends AbstractDatabaseHelper {
        private static final String TABLE_BOOKS = "Books";
        private static final String TABLE_BOOK_CATEGORIES = "BookCategories";

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
            createTableIfNeeded(TABLE_BOOKS);
            final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOKS +
                    "(id,title,authors,illustrators,age,stars,kicker,amazon_url,apple_books_url,google_play_url,genre,topics,type,know,story,good,talk,publishers,publication_date,publishers_recommended_ages,pages,last_updated)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);");
            insert.setString(1, book.id);
            insert.setString(2, book.title);
            insert.setString(3, book.authors);
            setStringOrNull(insert, 4, book.illustrators);
            insert.setString(5, book.age);
            insert.setInt(6, book.stars);
            insert.setString(7, book.kicker);
            setStringOrNull(insert, 8, book.amazonUrl);
            setStringOrNull(insert, 9, book.appleBooksUrl);
            setStringOrNull(insert, 10, book.googlePlayUrl);
            insert.setString(11, book.genre);
            setStringOrNull(insert, 12, book.topics);
            insert.setString(13, book.type);
            setStringOrNull(insert, 14, book.know);
            setStringOrNull(insert, 15, book.story);
            setStringOrNull(insert, 16, book.good);
            setStringOrNull(insert, 17, book.talk);
            setStringOrNull(insert, 18, book.publishers);
            setStringOrNull(insert, 19, book.publicationDate);
            setStringOrNull(insert, 20, book.publishersRecommendedAges);
            setIntOrNull(insert, 21, book.pages, -1);
            insert.setLong(22, book.lastUpdated);
            final int result = insert.executeUpdate();
            insert.close();
            return result;
        }

        int insertBookCategory(BookCategory bookCategory) throws SQLException {
            createTableIfNeeded(TABLE_BOOK_CATEGORIES);
            final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOK_CATEGORIES +
                    "(book_id,category_id,level,explanation)" +
                    " VALUES(?,?,?,?);");
            insert.setString(1, bookCategory.bookId);
            insert.setString(2, bookCategory.categoryId);
            insert.setInt(3, bookCategory.level);
            setStringOrNull(insert, 4, bookCategory.explanation);
            final int result = insert.executeUpdate();
            insert.close();
            return result;
        }

        public List<Book> getBooks() throws SQLException {
            final List<Book> books = new ArrayList<>();
            final Statement select = getConnection().createStatement();
            final ResultSet result = select.executeQuery("SELECT * FROM " + TABLE_BOOKS + ";");
            while (result.next()) {
                final Book book = makeBookFromResult(result);
                books.add(book);
            }
            select.close();
            return Collections.unmodifiableList(books);
        }

        public List<BookCategory> getBookCategories() throws SQLException {
            final List<BookCategory> bookCategories = new ArrayList<>();
            final Statement select = getConnection().createStatement();
            final ResultSet result = select.executeQuery("SELECT * FROM " + TABLE_BOOK_CATEGORIES + ";");
            while (result.next()) {
                final BookCategory bookCategory = makeBookCategoryFromResult(result);
                bookCategories.add(bookCategory);
            }
            select.close();
            return Collections.unmodifiableList(bookCategories);
        }

        private Book makeBookFromResult(ResultSet result) throws SQLException {
            final Book book = new Book();
            book.id = result.getString("id");
            book.title = result.getString("title");
            book.authors = result.getString("authors");
            book.illustrators = result.getString("illustrators");
            book.age = result.getString("age");
            book.stars = result.getInt("stars");
            book.kicker = result.getString("kicker");
            book.amazonUrl = result.getString("amazon_url");
            book.appleBooksUrl = result.getString("apple_books_url");
            book.googlePlayUrl = result.getString("google_play_url");
            book.genre = result.getString("genre");
            book.topics = result.getString("topics");
            book.type = result.getString("type");
            book.know = result.getString("know");
            book.story = result.getString("story");
            book.good = result.getString("good");
            book.talk = result.getString("talk");
            book.publishers = result.getString("publishers");
            book.publicationDate = result.getString("publication_date");
            book.publishersRecommendedAges = result.getString("publishers_recommended_ages");
            book.pages = getIntOrNull(result, "pages", -1);
            book.lastUpdated = result.getLong("last_updated");
            return book;
        }

        private BookCategory makeBookCategoryFromResult(ResultSet result) throws SQLException {
            final BookCategory bookCategory = new BookCategory();
            bookCategory.bookId = result.getString("book_id");
            bookCategory.categoryId = result.getString("category_id");
            bookCategory.level = result.getInt("level");
            bookCategory.explanation = result.getString("explanation");
            return bookCategory;
        }

        @Override
        public void createTableIfNeeded(String name) throws SQLException {
            if (TABLE_BOOKS.equalsIgnoreCase(name)) {
                final Statement statement = getConnection().createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKS + " (" +
                        " id TEXT PRIMARY KEY" + // the-unwanted-stories-of-the-syrian-refugees
                        ", title TEXT NOT NULL" + // The Unwanted: Stories of the Syrian Refugees
                        ", authors TEXT NOT NULL" + // Don Brown
                        ", illustrators TEXT DEFAULT NULL" + // Don Brown
                        ", age TEXT NOT NULL" + // 13+
                        ", stars INTEGER NOT NULL" + // 5
                        ", kicker TEXT NOT NULL" + // Compassionate graphic novel account of refugees' struggle.
                        ", amazon_url TEXT DEFAULT NULL" +
                        ", apple_books_url TEXT DEFAULT NULL" +
                        ", google_play_url TEXT DEFAULT NULL" +
                        ", genre TEXT NOT NULL" + // graphic novel
                        ", topics TEXT DEFAULT NULL" + // history,misfits and underdogs,pirates
                        ", type TEXT NOT NULL" + // non-fiction
                        ", know TEXT DEFAULT NULL" + // Parents need to know that The Unwanted is a nonfiction graphic novel written and illustrated by...
                        ", story TEXT DEFAULT NULL" + // Beginning in 2011, THE UNWANTED shows how the simple act of spray-painting...
                        ", good TEXT DEFAULT NULL" + // The issues surrounding the ongoing Syrian refugee crisis are numerous and complex,...
                        ", talk TEXT DEFAULT NULL" + // Families can talk about the conditions that force people to leave their homes...
                        ", publishers TEXT DEFAULT NULL" + // HMH Books for Young Readers
                        ", publication_date TEXT DEFAULT NULL" + // September 18, 2018 -> 2018-09-18
                        ", publishers_recommended_ages TEXT DEFAULT NULL" + // NULL or '13 - 18'
                        ", pages INTEGER DEFAULT NULL" + // 112
                        ", last_updated INTEGER NOT NULL" + // System.currentTimeMillis() -> long
                        ");");
            } else if (TABLE_BOOK_CATEGORIES.equalsIgnoreCase(name)) {
                final Statement statement = getConnection().createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOK_CATEGORIES + " (" +
                        " book_id TEXT NOT NULL" + // the-unwanted-stories-of-the-syrian-refugees
                        ", category_id TEXT NOT NULL" +
                        ", level INTEGER NOT NULL" +
                        ", explanation TEXT DEFAULT NULL" +
                        ");");
            } else {
                throw new IllegalArgumentException("Unknown table name: `" + name + "`.");
            }
        }
    }
}
