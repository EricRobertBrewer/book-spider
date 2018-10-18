package com.ericrobertbrewer.bookspider.sites;

import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.SiteScraper;
import com.ericrobertbrewer.bookspider.WebDriverFactory;
import com.ericrobertbrewer.web.DriverUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.sql.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CommonSenseMedia extends SiteScraper {

    public static void main(String[] args) throws IOException {
        Launcher.launch(args, new Creator() {
            @Override
            public Class<? extends SiteScraper> getScraperClass() {
                return CommonSenseMedia.class;
            }

            @Override
            public SiteScraper newInstance(Logger logger) {
                return new CommonSenseMedia(logger);
            }

            @Override
            public String getId() {
                return Folders.ID_COMMON_SENSE_MEDIA;
            }
        });
    }

    /**
     * The beginning of the URL of the book detail page.
     * Used like: `DETAILS_URL` + `bookId`.
     */
    private static final String DETAILS_URL = "https://www.commonsensemedia.org/book-reviews/";
    private static final DateFormat PUBLICATION_DATE_FORMAT_WEB = new SimpleDateFormat("MMMM d, yyyy", Locale.US);
    private static final DateFormat PUBLICATION_DATE_FORMAT_DATABASE = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private CommonSenseMedia(Logger logger) {
        super(logger);
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force) {
        if (force) {
            throw new IllegalArgumentException("CommonSenseMedia does not support `force`=`true`.");
        }
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
        final PrintWriter frontierWriter;
        try {
            frontierWriter = new PrintWriter(new FileWriter(frontierFile, exists));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to write to frontier file.", e);
            return;
        }
        // Create DatabaseHelper.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            getLogger().log(Level.SEVERE, "Unable to find suitable SQLite driver (JDBC).", e);
        }
        final DatabaseHelper databaseHelper = new DatabaseHelper();
        // Create a separate thread to scrape books.
        final Thread scrapeThread = new Thread(() -> {
            final WebDriver scrapeDriver = factory.newChromeDriver();
            databaseHelper.connect(contentFolder.getPath() + Folders.SLASH + "contents.db");
            scrapeBooks(scrapeDriver, frontier, databaseHelper);
            databaseHelper.close();
            scrapeDriver.quit();
        }, "scrape");
        scrapeThread.start();
        // Populate the frontier (on the main thread).
        final WebDriver frontierDriver = factory.newChromeDriver();
        exploreFrontier(frontierDriver, frontier, frontierWriter);
        frontierDriver.quit();
    }

    /**
     * Performed by the frontier (main) thread.
     * @param driver Driver.
     * @param frontier Queue of book IDs to scrape.
     * @param frontierWriter Writer to file which contains unique book IDs.
     */
    private void exploreFrontier(WebDriver driver, Queue<String> frontier, PrintWriter frontierWriter) {
        // Keep a running set of book IDs to avoid writing duplicates.
        final Set<String> frontierSet = new HashSet<>(frontier);
        // Simply scrape each page.
        int page = 0;
        while (true) {
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
//            final List<WebElement> viewsRows = paneContentDiv.findElements(By.className("views-row"));
            for (WebElement viewsRow : viewsRows) {
                try {
                    final WebElement csmButtonA = viewsRow.findElement(By.className("csm-button"));
                    final String url = csmButtonA.getAttribute("href");
                    final String bookId = url.substring(url.lastIndexOf("/") + 1);
                    if (!frontierSet.contains(bookId)) {
                        frontier.add(bookId);
                        frontierWriter.println(bookId);
                        frontierSet.add(bookId);
                        getLogger().log(Level.INFO, "Added new book `" + bookId + "` to frontier.");
                    }
                } catch (NoSuchElementException e) {
                    getLogger().log(Level.WARNING, "Could not find 'Continue Reading' button on page " + page + ".", e);
                }
            }
            // Quit when the "last page" link disappears.
            final WebElement pagerUl = reviewsBrowseDiv.findElement(By.className("pager"));
            try {
                pagerUl.findElement(By.className("pager-last"));
                page++;
            } catch (NoSuchElementException e) {
                break;
            }
        }
    }

    /**
     * Performed by the scrape thread.
     * @param driver Driver.
     * @param frontier Queue of book IDs to scrape.
     * @param databaseHelper To contents database. Should have already been connected.
     *                       This method does not close the connection to the database.
     */
    private void scrapeBooks(WebDriver driver, Queue<String> frontier, DatabaseHelper databaseHelper) {
        // Wait for frontier to populate a little.
        while (frontier.size() < 11) {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        // Start scraping.
        // It is assumed that the frontier will be populated faster than book details can be scraped.
        // Thus, it is assumed that when the frontier becomes empty after having been non-empty, we can safely quit.
        getLogger().log(Level.INFO, "Scraping details...");
        while (!frontier.isEmpty()) {
            final String bookId = frontier.poll();
            try {
                scrapeBook(driver, bookId, databaseHelper);
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "SQLException thrown while scraping book `" + bookId + "`.", e);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find web element for book `" + bookId + "`.", e);
            }
        }
        getLogger().log(Level.INFO, "Done scraping details.");
    }

    private void scrapeBook(WebDriver driver, String bookId, DatabaseHelper databaseHelper) throws SQLException, NoSuchElementException {
        // TODO: Check freshness of DB entry. Re-scrape the book details if its data is relatively stale.
        // Ignore books which have already been scraped.
        if (databaseHelper.bookExists(bookId)) {
            return;
        }
        // Scrape this book.
        getLogger().log(Level.INFO, "Scraping book: `" + bookId + "`.");
        driver.navigate().to(DETAILS_URL + bookId);
        final Book book = new Book();
        book.id = bookId;
        final WebElement contentDiv = driver.findElement(By.id("content"));
        // Extract title.
        final WebElement topWrapperDiv = contentDiv.findElement(By.className("panel-content-top-wrapper"));
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
//        final WebElement paneProductSubtitleDiv = panelContentTopMainDiv.findElement(By.className("pane-product-subtitle"));
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
                try {
                    final WebElement categoryExplanationDiv = itemLeftDiv.findElement(By.className("field-name-field-content-grid-rating-text"));
                    bookCategory.explanation = categoryExplanationDiv.getText().trim();
                } catch (NoSuchElementException e) {
                    // This element may not exist (especially when the rating is 0, or the category is not present).
                }
                bookCategories.add(bookCategory);
            } catch (NoSuchElementException e) {
                getLogger().log(Level.WARNING, "Unable to find element in content grid `field-item` element.", e);
            }
        }
        // Extract 'What Parents Need to Know'.
        final WebElement knowDiv = contentMidMainDiv.findElement(By.className("pane-node-field-parents-need-to-know"));
        final WebElement knowTextDiv = knowDiv.findElement(By.className("field-name-field-parents-need-to-know"));
        book.know = knowTextDiv.getText().trim();
        // Extract 'What's the Story?'.
        final WebElement storyDiv = contentMidMainDiv.findElement(By.className("pane-node-field-what-is-story"));
        final WebElement storyTextDiv = storyDiv.findElement(By.className("field-name-field-what-is-story"));
        book.story = storyTextDiv.getText().trim();
        // Extract 'Is It Any Good?'.
        final WebElement goodDiv = contentMidMainDiv.findElement(By.className("pane-node-field-any-good"));
        final WebElement goodTextDiv = goodDiv.findElement(By.className("field-name-field-any-good"));
        book.good = goodTextDiv.getText().trim();
        // Extract 'Talk to Your Kids About...'.
        final WebElement talkDiv = contentMidMainDiv.findElement(By.className("pane-node-field-family-topics"));
        final WebElement talkTextDiv = talkDiv.findElement(By.className("field-name-field-family-topics"));
        final WebElement talkTextListUl = talkTextDiv.findElement(By.className("textformatter-list"));
        final StringBuilder talk = new StringBuilder();
        final List<WebElement> talkTextLis = talkTextListUl.findElements(By.tagName("li"));
        for (WebElement talkTextLi : talkTextLis) {
            final String talkLiText = talkTextLi.getText().trim();
            if (talk.length() > 0) {
                talk.append("||");
            }
            talk.append(talkLiText);
        }
        book.talk = talk.toString();
        // Extract author, illustrator, genre, topics, type, publisher, publishing date, pages.
        final WebElement detailsDiv = contentMidMainDiv.findElement(By.className("pane-product-details"));
        final WebElement detailsUl = detailsDiv.findElement(By.id("review-product-details-list"));
        final List<WebElement> detailsLis = detailsUl.findElements(By.xpath("./*"));
        for (WebElement detailsLi : detailsLis) {
            final String detailsText = detailsLi.getText().trim();
            if (detailsText.startsWith("Author:")) {
                book.authors = detailsText.substring("Author:".length()).trim();
            } else if (detailsText.startsWith("Authors:")) {
                // Assume that each author is contained in an anchor <a> tag.
                final List<WebElement> authorsAs = detailsLi.findElements(By.tagName("a"));
                final StringBuilder authors = new StringBuilder();
                for (WebElement authorsA : authorsAs) {
                    final String authorsAText = authorsA.getText().trim();
                    if (authors.length() > 0) {
                        authors.append("|");
                    }
                    authors.append(authorsAText);
                }
                book.authors = authors.toString();
            } else if (detailsText.startsWith("Illustrator:")) {
                book.illustrator = detailsText.substring("Illustrator:".length()).trim();
            } else if (detailsText.startsWith("Genre:")) {
                book.genre = detailsText.substring("Genre:".length()).toLowerCase().trim();
            } else if (detailsText.startsWith("Topics:")) {
                final List<WebElement> topicsAs = detailsLi.findElements(By.tagName("a"));
                final StringBuilder topics = new StringBuilder();
                for (WebElement topicsA : topicsAs) {
                    final String topicsAText = topicsA.getText().trim();
                    if (topics.length() > 0) {
                        topics.append("|");
                    }
                    topics.append(topicsAText);
                }
                book.topics = topics.toString();
            } else if (detailsText.startsWith("Book Type:") || detailsText.startsWith("Book type:")) {
                book.type = detailsText.substring("Book Type:".length()).toLowerCase().trim();
            } else if (detailsText.startsWith("Publisher:")) {
                book.publishers = detailsText.substring("Publisher:".length()).trim();
            } else if (detailsText.startsWith("Publishers:")) {
                // Assume that every publisher is contained in an anchor (`<a>`) tag.
                final List<WebElement> publishersAs = detailsLi.findElements(By.tagName("a"));
                final StringBuilder publishers = new StringBuilder();
                for (WebElement publishersA : publishersAs) {
                    final String publishersAText = publishersA.getText().trim();
                    if (publishers.length() > 0) {
                        publishers.append("|");
                    }
                    publishers.append(publishersAText);
                }
                book.publishers = publishers.toString();
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
            } else //noinspection StatementWithEmptyBody
                if (detailsText.startsWith("Available on:")) {
                // Do nothing.
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

    private static class Book {
        String id;
        String title;
        String authors;
        String illustrator = null;
        String age;
        int stars;
        String kicker;
        String genre;
        String topics;
        String type;
        String know = null;
        String story = null;
        String good = null;
        String talk = null;
        String publishers = null;
        String publicationDate = null;
        String publishersRecommendedAges = null;
        int pages = -1;
        long lastUpdated;
    }

    private static class BookCategory {
        String bookId;
        String categoryId;
        int level;
        String explanation;
    }

    private class DatabaseHelper {
        private static final String TABLE_BOOKS = "Books";
        private static final String TABLE_BOOK_CATEGORIES = "BookCategories";

        private Connection connection = null;

        DatabaseHelper() {
        }

        void connect(String fileName) {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + fileName);
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Unable to connect to database: `" + fileName + "`.", e);
            }
        }

        boolean isConnected() {
            if (connection == null) {
                return false;
            }
            try {
                return !connection.isClosed();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Unable to query whether database connection is closed.", e);
            }
            return false;
        }

        void close() {
            try {
                connection.close();
                connection = null;
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Unable to close database connection.", e);
            }
        }

        boolean bookExists(String id) throws SQLException {
            ensureTableExists(DatabaseHelper.TABLE_BOOKS);
            final PreparedStatement s = connection.prepareStatement(
                    "SELECT id\n" +
                    " FROM " + DatabaseHelper.TABLE_BOOKS + "\n" +
                    " WHERE id=?;");
            s.setString(1, id);
            final ResultSet r = s.executeQuery();
            final boolean exists = r.next();
            s.close();  // Also closes `r`.
            return exists;
        }

        int insertBook(Book book) throws SQLException {
            ensureTableExists(TABLE_BOOKS);
            final PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO " + TABLE_BOOKS +
                    "(id,title,authors,illustrator,age,stars,kicker,genre,topics,type,know,story,good,talk,publishers,publication_date,publishers_recommended_ages,pages,last_updated)\n" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);");
            s.setString(1, book.id);
            s.setString(2, book.title);
            s.setString(3, book.authors);
            setStringOrNull(s, 4, book.illustrator);
            s.setString(5, book.age);
            s.setInt(6, book.stars);
            s.setString(7, book.kicker);
            s.setString(8, book.genre);
            s.setString(9, book.topics);
            s.setString(10, book.type);
            setStringOrNull(s, 11, book.know);
            setStringOrNull(s, 12, book.story);
            setStringOrNull(s, 13, book.good);
            setStringOrNull(s, 14, book.talk);
            setStringOrNull(s, 15, book.publishers);
            setStringOrNull(s, 16, book.publicationDate);
            setStringOrNull(s, 17, book.publishersRecommendedAges);
            s.setInt(18, book.pages);
            s.setLong(19, book.lastUpdated);
            final int r = s.executeUpdate();
            s.close();
            return r;
        }

        int insertBookCategory(BookCategory bookCategory) throws SQLException {
            ensureTableExists(TABLE_BOOK_CATEGORIES);
            final PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO " + TABLE_BOOK_CATEGORIES +
                    "(book_id,category_id,level,explanation)\n" +
                    "VALUES(?,?,?,?);");
            s.setString(1, bookCategory.bookId);
            s.setString(2, bookCategory.categoryId);
            s.setInt(3, bookCategory.level);
            s.setString(4, bookCategory.explanation);
            final int r = s.executeUpdate();
            s.close();
            return r;
        }

        private void ensureTableExists(String name) throws SQLException {
            if (TABLE_BOOKS.equalsIgnoreCase(name)) {
                final Statement statement = connection.createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKS + " (\n" +
                        " id TEXT PRIMARY KEY,\n" + // the-unwanted-stories-of-the-syrian-refugees
                        " title TEXT NOT NULL,\n" + // The Unwanted: Stories of the Syrian Refugees
                        " authors TEXT NOT NULL,\n" + // Don Brown
                        " illustrator TEXT DEFAULT NULL,\n" + // Don Brown
                        " age TEXT NOT NULL,\n" + // 13+
                        " stars INTEGER NOT NULL,\n" + // 5
                        " kicker TEXT NOT NULL,\n" + // Compassionate graphic novel account of refugees' struggle.
                        " genre TEXT NOT NULL,\n" + // graphic novel
                        " topics TEXT NOT NULL,\n" + // history,misfits and underdogs,pirates
                        " type TEXT NOT NULL,\n" + // non-fiction
                        " know TEXT DEFAULT NULL,\n" + // Parents need to know that The Unwanted is a nonfiction graphic novel written and illustrated by...
                        " story TEXT DEFAULT NULL,\n" + // Beginning in 2011, THE UNWANTED shows how the simple act of spray-painting...
                        " good TEXT DEFAULT NULL,\n" + // The issues surrounding the ongoing Syrian refugee crisis are numerous and complex,...
                        " talk TEXT DEFAULT NULL,\n" + // Families can talk about the conditions that force people to leave their homes...
                        " publishers TEXT DEFAULT NULL,\n" + // HMH Books for Young Readers
                        " publication_date TEXT DEFAULT NULL,\n" + // September 18, 2018 -> 2018-09-18
                        " publishers_recommended_ages TEXT DEFAULT NULL,\n" + // NULL or '13 - 18'
                        " pages INTEGER DEFAULT NULL,\n" + // 112
                        " last_updated INTEGER NOT NULL\n" + // System.currentTimeMillis() -> long
                        ");");
            } else if (TABLE_BOOK_CATEGORIES.equalsIgnoreCase(name)) {
                final Statement statement = connection.createStatement();
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOK_CATEGORIES + " (\n" +
                        " book_id TEXT NOT NULL,\n" + // the-unwanted-stories-of-the-syrian-refugees
                        " category_id TEXT NOT NULL,\n" +
                        " level INTEGER NOT NULL,\n" +
                        " explanation TEXT DEFAULT NULL\n" +
                        ");");
            } else {
                throw new IllegalArgumentException("Unknown table name: `" + name + "`.");
            }
        }

        private void setStringOrNull(PreparedStatement s, int parameterIndex, String value) throws SQLException {
            if (value != null) {
                s.setString(parameterIndex, value);
            } else {
                s.setNull(parameterIndex, Types.VARCHAR);
            }
        }
    }
}
