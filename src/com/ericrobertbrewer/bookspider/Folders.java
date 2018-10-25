package com.ericrobertbrewer.bookspider;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Folders {

    public static final String SLASH = File.separator;

    /**
     * Root of downloaded (scraped) content.
     */
    private static final String CONTENT_ROOT = "content" + SLASH;
    /**
     * Root of log files.
     */
    private static final String LOGS_ROOT = "logs" + SLASH;

    public static final String ID_BOOK_CAVE = "bookcave";
    public static final String ID_BOOK_CAVE_AMAZON = ID_BOOK_CAVE + SLASH + "amazon";
    public static final String ID_COMMON_SENSE_MEDIA = "commonsensemedia";
    public static final String ID_NY_TIMES = "nytimes";

    public static File getContentFolder(String id) throws IOException {
        final File folder = new File(CONTENT_ROOT + id + SLASH);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Unable to create content folder: `" + folder.getPath() + "`.");
        }
        return folder;
    }

    static File getLogFile(String id) throws IOException {
        return new File(getLogsFolder(id), LOG_DATE_FORMAT.format(new Date()) + ".log");
    }

    private static final DateFormat LOG_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private static File getLogsFolder(String id) throws IOException {
        final File folder = new File(LOGS_ROOT + id + SLASH);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Unable to create logs folder: `" + folder.getPath() + "`.");
        }
        return folder;
    }
}
