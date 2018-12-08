package com.ericrobertbrewer.bookspider;

public class BookScrapeInfo {

    public final String id;
    public final String[] urls;

    public BookScrapeInfo(String id, String[] urls) {
        this.id = id;
        this.urls = urls;
    }
}
