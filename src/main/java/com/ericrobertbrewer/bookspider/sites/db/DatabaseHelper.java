package com.ericrobertbrewer.bookspider.sites.db;


import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.sites.meta.BookCave;
import com.ericrobertbrewer.bookspider.sites.meta.CommonSenseMedia;
import com.ericrobertbrewer.bookspider.sites.text.AmazonKindle;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseHelper extends AbstractDatabaseHelper {

    public static final String TABLE_AMAZON_BOOKS = "AmazonBooks";
    public static final String TABLE_BOOKCAVE_BOOKS = "BookCaveBooks";
    public static final String TABLE_BOOKCAVE_BOOK_RATINGS = "BookCaveBookRatings";
    public static final String TABLE_BOOKCAVE_BOOK_RATING_LEVELS = "BookCaveBookRatingLevels";
    public static final String TABLE_COMMONSENSEMEDIA_BOOKS = "CommonSenseMediaBooks";
    public static final String TABLE_COMMONSENSEMEDIA_BOOK_CATEGORIES = "CommonSenseMediaBookCategories";

    public DatabaseHelper(Logger logger) {
        super(logger);
    }

    public void connectToContentsDatabase() {
        connect(Folders.CONTENTS_DATABASE_FILE_NAME);
        createTablesIfNeeded();
    }

    /**
     * Just make sure that all fields in `book` have been updated with real values.
     * @param book  The book.
     * @return      Not `-1`.
     * @throws SQLException When an error occurs.
     */
    public int insertOrReplace(AmazonKindle.Book book) throws SQLException {
        final PreparedStatement insertOrReplace = getConnection().prepareStatement("INSERT OR REPLACE INTO " + TABLE_AMAZON_BOOKS +
                "(asin,is_kindle_unlimited,price,last_updated)" +
                " VALUES(?,?,?,?);");
        insertOrReplace.setString(1, book.asin);
        insertOrReplace.setBoolean(2, book.isKindleUnlimited);
        setStringOrNull(insertOrReplace, 3, book.price);
        insertOrReplace.setLong(4, book.lastUpdated);
        final int result = insertOrReplace.executeUpdate();
        insertOrReplace.close();
        return result;
    }

    public int insert(BookCave.Book book) throws SQLException {
        final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOKCAVE_BOOKS + "(" +
                "id,title,authors,summary,description," +
                "community_ratings_count,community_average_rating,pages,genres,amazon_kindle_url," +
                "amazon_print_url,audible_url,apple_books_url,barnes_and_noble_url,barnes_and_noble_audiobook_url," +
                "barnes_and_noble_print_url,google_play_url,kobo_url,smashwords_url,last_updated" +
                ")  VALUES(" +
                "?,?,?,?,?," +
                "?,?,?,?,?," +
                "?,?,?,?,?," +
                "?,?,?,?,?);");
        insert.setString(1, book.id);
        insert.setString(2, book.title);
        insert.setString(3, book.authors);
        insert.setString(4, book.summary);
        setStringOrNull(insert, 5, book.description);
        insert.setInt(6, book.communityRatingsCount);
        setStringOrNull(insert, 7, book.communityAverageRating);
        setIntOrNull(insert, 8, book.pages, -1);
        insert.setString(9, book.genres);
        setStringOrNull(insert, 10, book.amazonKindleUrl);
        setStringOrNull(insert, 11, book.amazonPrintUrl);
        setStringOrNull(insert, 12, book.audibleUrl);
        setStringOrNull(insert, 13, book.appleBooksUrl);
        setStringOrNull(insert, 14, book.barnesAndNobleUrl);
        setStringOrNull(insert, 15, book.barnesAndNobleAudiobookUrl);
        setStringOrNull(insert, 16, book.barnesAndNoblePrintUrl);
        setStringOrNull(insert, 17, book.googlePlayUrl);
        setStringOrNull(insert, 18, book.koboUrl);
        setStringOrNull(insert, 19, book.smashwordsUrl);
        insert.setLong(20, book.lastUpdated);
        final int result = insert.executeUpdate();
        insert.close();
        return result;
    }

    public int insert(BookCave.BookRating rating) throws SQLException {
        final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOKCAVE_BOOK_RATINGS +
                "(book_id,rating,count)" +
                " VALUES(?,?,?);");
        insert.setString(1, rating.bookId);
        insert.setString(2, rating.rating);
        insert.setInt(3, rating.count);
        final int result = insert.executeUpdate();
        insert.close();
        return result;
    }

    public int insert(BookCave.BookRatingLevel level) throws SQLException {
        final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_BOOKCAVE_BOOK_RATING_LEVELS +
                "(book_id,rating,title,count)" +
                " VALUES(?,?,?,?);");
        insert.setString(1, level.bookId);
        insert.setString(2, level.rating);
        insert.setString(3, level.title);
        insert.setInt(4, level.count);
        final int result = insert.executeUpdate();
        insert.close();
        return result;
    }

    public int insert(CommonSenseMedia.Book book) throws SQLException {
        final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_COMMONSENSEMEDIA_BOOKS +
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

    public int insert(CommonSenseMedia.BookCategory bookCategory) throws SQLException {
        final PreparedStatement insert = getConnection().prepareStatement("INSERT INTO " + TABLE_COMMONSENSEMEDIA_BOOK_CATEGORIES +
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

    public AmazonKindle.Book getAmazonBook(String asin) throws SQLException {
        final PreparedStatement select = getConnection().prepareStatement("SELECT * FROM " + TABLE_AMAZON_BOOKS +
                " WHERE asin=?;");
        select.setString(1, asin);
        final ResultSet result = select.executeQuery();
        if (!result.next()) {
            return null;
        }
        final AmazonKindle.Book book = makeAmazonBookFromResult(result);
        select.close();
        return book;
    }

    public BookCave.Book getBookCaveBook(String id) throws SQLException {
        final PreparedStatement select = getConnection().prepareStatement("SELECT * FROM " + TABLE_BOOKCAVE_BOOKS +
                " WHERE id=?;");
        select.setString(1, id);
        final ResultSet result = select.executeQuery();
        if (!result.next()) {
            return null;
        }
        final BookCave.Book book = makeBookCaveBookFromResult(result);
        select.close();
        return book;
    }

    public List<BookCave.Book> getBookCaveBooks() throws SQLException {
        final List<BookCave.Book> books = new ArrayList<>();
        final Statement select = getConnection().createStatement();
        final ResultSet result = select.executeQuery("SELECT * FROM " + TABLE_BOOKCAVE_BOOKS + ";");
        while (result.next()) {
            final BookCave.Book book = makeBookCaveBookFromResult(result);
            books.add(book);
        }
        select.close();
        return Collections.unmodifiableList(books);
    }

    public int updateBookAsin(String bookId, String asin) throws SQLException {
        final PreparedStatement update = getConnection().prepareStatement("UPDATE " + DatabaseHelper.TABLE_BOOKCAVE_BOOKS + " SET " +
                "asin=?" +
                " WHERE id=?;");
        update.setString(1, asin);
        update.setString(2, bookId);
        final int result = update.executeUpdate();
        update.close();
        return result;
    }

    private void createTablesIfNeeded() {
        final String[] tables = {
                TABLE_AMAZON_BOOKS,
                TABLE_BOOKCAVE_BOOKS,
                TABLE_BOOKCAVE_BOOK_RATINGS,
                TABLE_BOOKCAVE_BOOK_RATING_LEVELS,
                TABLE_COMMONSENSEMEDIA_BOOKS,
                TABLE_COMMONSENSEMEDIA_BOOK_CATEGORIES
        };
        for (String table : tables) {
            try {
                createTableIfNeeded(table);
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Unable to create table.", e);
            }
        }
    }

    private void createTableIfNeeded(String name) throws SQLException {
        if (TABLE_AMAZON_BOOKS.equalsIgnoreCase(name)) {
            final Statement create = getConnection().createStatement();
            create.execute("CREATE TABLE IF NOT EXISTS " + TABLE_AMAZON_BOOKS + " (" +
                    "asin TEXT PRIMARY KEY" +
                    ", is_kindle_unlimited BOOLEAN DEFAULT 0" +
                    ", price TEXT DEFAULT NULL" +
                    ", last_updated INTEGER DEFAULT NULL" +
                    ");");
        } else if (TABLE_BOOKCAVE_BOOKS.equalsIgnoreCase(name)) {
            final Statement create = getConnection().createStatement();
            create.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKCAVE_BOOKS + " (" +
                    "id TEXT PRIMARY KEY" + // the-haunting-of-gillespie-house
                    ", title TEXT NOT NULL" + // The Haunting of Gillespie House
                    ", authors TEXT NOT NULL" + // Darcy Coates
                    ", summary TEXT NOT NULL" + // Elle is thrilled to spend a month minding the beautiful Gillespie property...
                    ", description TEXT DEFAULT NULL" + // Elle is thrilled to spend a month minding the beautiful Gillespie property...
                    ", community_ratings_count INTEGER NOT NULL" + // 1
                    ", community_average_rating TEXT DEFAULT NULL" + // Moderate
                    ", pages INTEGER DEFAULT NULL" + // 200
                    ", genres TEXT DEFAULT NULL" + // Fiction/Horror
                    ", amazon_kindle_url TEXT DEFAULT NULL" + // https://...
                    ", amazon_print_url TEXT DEFAULT NULL" + // https://...
                    ", audible_url TEXT DEFAULT NULL" + // https://...
                    ", apple_books_url TEXT DEFAULT NULL" + // https://...
                    ", barnes_and_noble_url TEXT DEFAULT NULL" + // https://...
                    ", barnes_and_noble_audiobook_url TEXT DEFAULT NULL" + // https://...
                    ", barnes_and_noble_print_url TEXT DEFAULT NULL" + // https://...
                    ", google_play_url TEXT DEFAULT NULL" + // https://...
                    ", kobo_url TEXT DEFAULT NULL" + // https://...
                    ", smashwords_url TEXT DEFAULT NULL" + // https://...
                    ", last_updated INTEGER NOT NULL" + // System.currentTimeMillis() -> long
                    ", asin TEXT DEFAULT NULL" +
                    ");");
        } else if (TABLE_BOOKCAVE_BOOK_RATINGS.equalsIgnoreCase(name)) {
            final Statement create = getConnection().createStatement();
            create.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKCAVE_BOOK_RATINGS + " (" +
                    "book_id TEXT NOT NULL" +
                    ", rating TEXT NOT NULL" +
                    ", count INTEGER NOT NULL" +
                    ", PRIMARY KEY (book_id, rating)" +
                    ");");
        } else if (TABLE_BOOKCAVE_BOOK_RATING_LEVELS.equalsIgnoreCase(name)) {
            final Statement create = getConnection().createStatement();
            create.execute("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKCAVE_BOOK_RATING_LEVELS + " (" +
                    "book_id TEXT NOT NULL" +
                    ", rating TEXT NOT NULL" +
                    ", title TEXT NOT NULL" +
                    ", count INTEGER NOT NULL" +
                    ", PRIMARY KEY (book_id, rating, title)" +
                    ");");
        } else if (TABLE_COMMONSENSEMEDIA_BOOKS.equalsIgnoreCase(name)) {
            final Statement statement = getConnection().createStatement();
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_COMMONSENSEMEDIA_BOOKS + " (" +
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
        } else if (TABLE_COMMONSENSEMEDIA_BOOK_CATEGORIES.equalsIgnoreCase(name)) {
            final Statement statement = getConnection().createStatement();
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_COMMONSENSEMEDIA_BOOK_CATEGORIES + " (" +
                    " book_id TEXT NOT NULL" + // the-unwanted-stories-of-the-syrian-refugees
                    ", category_id TEXT NOT NULL" +
                    ", level INTEGER NOT NULL" +
                    ", explanation TEXT DEFAULT NULL" +
                    ", PRIMARY KEY (book_id, category_id)" +
                    ");");
        } else {
            throw new IllegalArgumentException("Unknown table name: `" + name + "`.");
        }
    }

    private AmazonKindle.Book makeAmazonBookFromResult(ResultSet result) throws SQLException {
        final AmazonKindle.Book book = new AmazonKindle.Book();
        book.asin = result.getString("asin");
        book.isKindleUnlimited = result.getBoolean("is_kindle_unlimited");
        book.price = result.getString("price");
        book.lastUpdated = getLongOrNull(result, "last_updated", -1L);
        return book;
    }

    private BookCave.Book makeBookCaveBookFromResult(ResultSet result) throws SQLException {
        final BookCave.Book book = new BookCave.Book();
        book.id = result.getString("id");
        book.title = result.getString("title");
        book.authors = result.getString("authors");
        book.summary = result.getString("summary");
        book.description = result.getString("description");
        book.communityRatingsCount = result.getInt("community_ratings_count");
        book.communityAverageRating = result.getString("community_average_rating");
        book.pages = getIntOrNull(result, "pages", -1);
        book.genres = result.getString("genres");
        book.amazonKindleUrl = result.getString("amazon_kindle_url");
        book.amazonPrintUrl = result.getString("amazon_print_url");
        book.audibleUrl = result.getString("audible_url");
        book.appleBooksUrl = result.getString("apple_books_url");
        book.barnesAndNobleUrl = result.getString("barnes_and_noble_url");
        book.barnesAndNobleAudiobookUrl = result.getString("barnes_and_noble_audiobook_url");
        book.barnesAndNoblePrintUrl = result.getString("barnes_and_noble_print_url");
        book.googlePlayUrl = result.getString("google_play_url");
        book.koboUrl = result.getString("kobo_url");
        book.smashwordsUrl = result.getString("smashwords_url");
        book.lastUpdated = result.getLong("last_updated");
        book.asin = result.getString("asin");
        return book;
    }
}
