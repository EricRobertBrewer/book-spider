package com.ericrobertbrewer.web;

public final class WebUtils {

    public static String getLastUrlComponent(String url) {
        if (url.endsWith("/")) {
            return getLastUrlComponent(url.substring(0, url.length() - 1));
        }
        final int lastSlash = url.lastIndexOf("/");
        if (lastSlash == -1) {
            return url;
        }
        return url.substring(lastSlash + 1);
    }

    private WebUtils() {
    }
}
