package com.ericrobertbrewer.bookspider;

public class BookScrapeInfo {

    public final String id;
    public final String[] urls;
    public final String price;

    public BookScrapeInfo(String id, String[] urls, String price) {
        this.id = id;
        this.urls = urls;
        this.price = price;
    }
}
