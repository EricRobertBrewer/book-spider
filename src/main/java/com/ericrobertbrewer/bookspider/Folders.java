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
    private static final String CONTENT_ROOT = ".." + SLASH + "content" + SLASH;
    /**
     * Root of log files.
     */
    private static final String LOGS_ROOT = ".." + SLASH + "logs" + SLASH;

    public static final String CONTENTS_DATABASE_FILE_NAME = Folders.CONTENT_ROOT + "contents.db";

    public static final String ID_AMAZON_KINDLE = "amazon_kindle";
    public static final String ID_BOOKCAVE = "bookcave";
    public static final String ID_BOOKCAVE_AMAZON_PREVIEW = "bookcave_amazon_preview";
    public static final String ID_COMMONSENSEMEDIA = "commonsensemedia";
    public static final String ID_NYTIMES = "nytimes";

    private static String getContentFolderName(String id) {
        return CONTENT_ROOT + id + SLASH;
    }

    public static File getLogFile(String id) throws IOException {
        return new File(getLogsFolder(id), LOG_DATE_FORMAT.format(new Date()) + ".log");
    }

    public static File makeContentFolder(String id) throws IOException {
        final String name = getContentFolderName(id);
        final File folder = new File(name);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Unable to create content folder: `" + folder.getPath() + "`.");
        }
        return folder;
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
