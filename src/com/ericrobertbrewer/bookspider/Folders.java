package com.ericrobertbrewer.bookspider;

import java.io.File;

class Folders {

    private static final String SLASH = File.separator;

    /**
     * Root of downloaded (scraped) content.
     */
    private static final String CONTENT_ROOT = "content" + SLASH;

    static final String NY_TIMES = CONTENT_ROOT + SLASH + "nytimes" + SLASH;
}
