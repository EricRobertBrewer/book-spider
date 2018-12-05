package com.ericrobertbrewer.web.dl;

import java.io.File;

public class FileDownloadInfo {

    final String url;
    final File folder;

    public FileDownloadInfo(String url, File folder) {
        this.url = url;
        this.folder = folder;
    }
}
