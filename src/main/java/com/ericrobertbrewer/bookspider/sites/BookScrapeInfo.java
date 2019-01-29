package com.ericrobertbrewer.bookspider.sites;

public class BookScrapeInfo {

    public final String id;
    public final String[] urls;
    public final String asin;

    public BookScrapeInfo(String id, String[] urls, String asin) {
        this.id = id;
        this.urls = urls;
        this.asin = asin;
    }
}
