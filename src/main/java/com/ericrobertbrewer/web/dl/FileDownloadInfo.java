package com.ericrobertbrewer.web.dl;

import java.io.File;

public class FileDownloadInfo {

    public final String url;
    public final File folder;

    public FileDownloadInfo(String url, File folder) {
        this.url = url;
        this.folder = folder;
    }
}
