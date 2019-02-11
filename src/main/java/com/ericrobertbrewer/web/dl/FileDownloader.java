package com.ericrobertbrewer.web.dl;

import com.ericrobertbrewer.web.WebUtils;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.*;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileDownloader {

    public interface Callback {
        boolean doStayAlive();

        void onComplete();
    }

    private final Logger logger;
    public final AtomicBoolean isDownloadingFiles = new AtomicBoolean(false);

    public FileDownloader(Logger logger) {
        this.logger = logger;
    }

    public void downloadFilesThreaded(Queue<FileDownloadInfo> filesQueue, boolean force, Callback callback) {
        final Thread thread = new Thread(() -> {
            final OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            isDownloadingFiles.set(true);
            downloadFiles(client, filesQueue, force, callback);
            isDownloadingFiles.set(false);
            callback.onComplete();
        });
        thread.start();
    }

    private void downloadFiles(OkHttpClient client, Queue<FileDownloadInfo> filesQueue, boolean force, Callback callback) {
        while (callback.doStayAlive() || !filesQueue.isEmpty()) {
            // Wait for file queue to fill.
            if (filesQueue.isEmpty()) {
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }
            // Download the file.
            final FileDownloadInfo fileInfo = filesQueue.poll();
            // Check for an existing file with the same name, optionally with a file extension.
            final String fileNameCandidate = getFileName(fileInfo.url);
            final File file;
            final File similarFile = findSimilarFile(fileInfo.folder, fileNameCandidate);
            file = Objects.requireNonNullElseGet(similarFile, () -> new File(fileInfo.folder, fileNameCandidate));
            // Process `force` flag.
            if (file.exists()) {
                if (force) {
                    if (!file.delete()) {
                        logOrPrint(Level.SEVERE, "Unable to delete file `" + file.getPath() + "`.");
                        continue;
                    }
                } else {
                    continue;
                }
            }
            int retries = 3;
            while (retries > 0) {
                try {
                    downloadFile(client, fileInfo, file);
                    break;
                } catch (IOException e) {
                    logOrPrint(Level.WARNING, "Encountered IOException while downloading file `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.", e);
                } catch (Throwable t) {
                    logOrPrint(Level.WARNING, "Encountered unknown error while downloading file `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.", t);
                }
                retries--;
            }
        }
    }

    private void downloadFile(OkHttpClient client, FileDownloadInfo fileInfo, File file) throws IOException {
        logOrPrint(Level.INFO, "Downloading file `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.");
        final Request request = new Request.Builder()
                .url(fileInfo.url)
                .build();
        final Call call = client.newCall(request);
        final Response response = call.execute();
        if (response.body() == null) {
            logOrPrint(Level.WARNING, "Failed to retrieve response from `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.");
            return;
        }
        // Find a proper file extension.
        final byte[] bytes = response.body().bytes();
        final File newFile;
        if (file.getName().contains(".")) {
            // The file already has a proper extension.
            newFile = file;
        } else {
            // Look for a known `Content-Type` in the response header,
            // or "sniff" the first few bytes of the file contents.
            final String contentType = response.header("Content-Type");
            if ("image/jpeg".equalsIgnoreCase(contentType) || bytesMatch(bytes, BYTES_JPEG)) {
                newFile = new File(fileInfo.folder, file.getName() + ".jpg");
            } else if ("image/png".equalsIgnoreCase(contentType) || bytesMatch(bytes, BYTES_PNG)) {
                newFile = new File(fileInfo.folder, file.getName() + ".png");
            } else if ("image/gif".equalsIgnoreCase(contentType) || bytesMatch(bytes, BYTES_GIF87A) || bytesMatch(bytes, BYTES_GIF89A)) {
                newFile = new File(fileInfo.folder, file.getName() + ".gif");
            } else if ("image/svg+xml".equalsIgnoreCase(contentType)) {
                newFile = new File(fileInfo.folder, file.getName() + ".svg");
            } else if ("image/bmp".equalsIgnoreCase(contentType) || bytesMatch(bytes, BYTES_BM)) {
                newFile = new File(fileInfo.folder, file.getName() + ".bmp");
            } else {
                // No luck.
                if (contentType != null) {
                    logOrPrint(Level.WARNING, "Found unknown Content-Type `" + contentType + "` while downloading file to folder `" + fileInfo.folder.getName() + "`.");
                }
                logOrPrint(Level.WARNING, "Unable to find file extension for file `" + file.getName() + "` downloaded from `" + fileInfo.url + "`.");
                newFile = file;
            }
        }
        try (OutputStream out = new FileOutputStream(newFile)) {
            out.write(bytes);
        }
    }

    private void logOrPrint(Level level, String msg) {
        logOrPrint(level, msg, null);
    }

    private void logOrPrint(Level level, String msg, Throwable t) {
        if (logger != null) {
            logger.log(level, msg, t);
        } else if (level == Level.SEVERE) {
            System.err.println(msg);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        } else {
            System.out.println(msg);
            if (t != null) {
                t.printStackTrace(System.out);
            }
        }
    }

    private static String getFileName(String url) {
        // Split the parameters from the URL.
        final int parametersIndex = url.indexOf("?");
        final String parameters;
        if (parametersIndex != -1) {
            parameters = url.substring(parametersIndex + 1);
            url = url.substring(0, parametersIndex);
        } else {
            parameters = null;
        }
        final String nameBase = WebUtils.getLastUrlComponent(url);

        // When no file extension exists, check the parameters for a MIME type.
        if (!nameBase.contains(".") && parameters != null) {
            if (parameters.contains("mime=image/jpeg") || parameters.contains("mime=image/jpg")) {
                return nameBase + ".jpg";
            } else if (parameters.contains("mime=image/png")) {
                return nameBase + ".png";
            } else if (parameters.contains("mime=image/gif")) {
                return nameBase + ".gif";
            } else if (parameters.contains("mime=image/svg+xml")) {
                return nameBase + ".svg";
            } else if (parameters.contains("mime=image/bmp")) {
                return nameBase + ".bmp";
            }
        }

        // We did our best.
        return nameBase;
    }

    private static File findSimilarFile(File folder, String candidateName) {
        final File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                final String fileName = file.getName();
                if (fileName.startsWith(candidateName)) {
                    return file;
                }
            }
        }
        return null;
    }

    private static final int[] BYTES_JPEG = {0xFF, 0xD8, 0xFF};
    private static final int[] BYTES_PNG = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int[] BYTES_GIF87A = {0x47, 0x49, 0x46, 0x38, 0x37, 0x61};
    private static final int[] BYTES_GIF89A = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
    private static final int[] BYTES_BM = {0x42, 0x4D};

    private static boolean bytesMatch(byte[] bytes, int[] toMatch) {
        for (int i = 0; i < Math.min(bytes.length, toMatch.length); i++) {
            if ((bytes[i] & 0xFF) != toMatch[i]) {
                return false;
            }
        }
        return true;
    }
}
