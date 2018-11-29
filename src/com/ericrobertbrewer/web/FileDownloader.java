package com.ericrobertbrewer.web;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

    public final AtomicBoolean isDownloadingFiles = new AtomicBoolean(false);

    public FileDownloader() {
    }

    public void downloadFilesThreaded(Queue<FileDownloadInfo> filesQueue, boolean force, Logger logger, Callback callback) {
        final Thread thread = new Thread(() -> {
            final OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            isDownloadingFiles.set(true);
            downloadFiles(client, filesQueue, force, logger, callback);
            isDownloadingFiles.set(false);
            callback.onComplete();
        });
        thread.start();
    }

    private void downloadFiles(OkHttpClient client, Queue<FileDownloadInfo> filesQueue, boolean force, Logger logger, Callback callback) {
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
                        logOrPrint(logger, Level.SEVERE, "Unable to delete file `" + file.getPath() + "`.");
                        continue;
                    }
                } else {
                    continue;
                }
            }
            int retries = 3;
            while (retries > 0) {
                try {
                    downloadFile(client, fileInfo, file, logger);
                    break;
                } catch (IOException e) {
                    logOrPrint(logger, Level.WARNING, "Encountered IOException while downloading file `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.", e);
                } catch (Throwable t) {
                    logOrPrint(logger, Level.WARNING, "Encountered unknown error while downloading file `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.", t);
                }
                retries--;
            }
        }
    }

    private static void downloadFile(OkHttpClient client, FileDownloadInfo fileInfo, File file, Logger logger) throws IOException {
        logOrPrint(logger, Level.INFO, "Downloading file `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.");
        final Request request = new Request.Builder()
                .url(fileInfo.url)
                .build();
        final Call call = client.newCall(request);
        final Response response = call.execute();
        if (response.body() == null) {
            logOrPrint(logger, Level.WARNING, "Failed to retrieve response from `" + fileInfo.url + "` to folder `" + fileInfo.folder.getName() + "`.");
            return;
        }
        // Find a proper file extension.
        final InputStream byteStream = response.body().byteStream();
        final File newFile;
        if (file.getName().contains(".")) {
            // The file already has a proper extension.
            newFile = file;
        } else {
            // Look for a known `Content-Type` in the response header.
            final String contentType = response.header("Content-Type");
            if ("image/jpeg".equalsIgnoreCase(contentType)) {
                newFile = new File(fileInfo.folder, file.getName() + ".jpg");
            } else if ("image/png".equalsIgnoreCase(contentType)) {
                newFile = new File(fileInfo.folder, file.getName() + ".png");
            } else if ("image/gif".equalsIgnoreCase(contentType)) {
                newFile = new File(fileInfo.folder, file.getName() + ".gif");
            } else if ("image/svg+xml".equalsIgnoreCase(contentType)) {
                newFile = new File(fileInfo.folder, file.getName() + ".svg");
            } else if ("image/bmp".equalsIgnoreCase(contentType)) {
                newFile = new File(fileInfo.folder, file.getName() + ".bmp");
            } else {
                if (contentType != null) {
                    logOrPrint(logger, Level.WARNING, "Found unknown Content-Type `" + contentType + "` while downloading file to folder `" + fileInfo.folder.getName() + "`.");
                }
                // "Sniff" the first few bytes of the file contents.
                // See `https://tools.ietf.org/id/draft-abarth-mime-sniff-06.html#rfc.section.6`.
                final byte[] bytes = new byte[8];
                if (byteStream.read(bytes) == bytes.length) {
                    // Two's complement: A Java `byte` is shifted 1 place to the right from its
                    // unsigned hexadecimal pattern in order to represent negative values.
                    if (bytes[0] == (0xFF >> 1) && bytes[1] == (0xD8 >> 1) && bytes[2] == (0xFF >> 1)) {
                        newFile = new File(fileInfo.folder, file.getName() + ".jpg");
                    } else if (bytes[0] == (0x89 >> 1) && bytes[1] == (0x50 >> 1) && bytes[2] == (0x4E >> 1) && bytes[3] == (0x47 >> 1) &&
                            bytes[4] == (0x0D >> 1) && bytes[5] == (0x0A >> 1) && bytes[6] == (0x1A >> 1) && bytes[7] == (0x0A >> 1)) {
                        newFile = new File(fileInfo.folder, file.getName() + ".png");
                    } else if (bytes[0] == (0x47 >> 1) && bytes[1] == (0x49 >> 1) && bytes[2] == (0x46 >> 1) && bytes[3] == (0x38 >> 1) &&
                            (bytes[4] == (0x37 >> 1) || bytes[4] == (0x39 >> 1)) &&
                            bytes[5] == (0x61 >> 1)) {
                        // 'GIF87a' or 'GIF89a'.
                        newFile = new File(fileInfo.folder, file.getName() + ".gif");
                    } else if (bytes[0] == (0x42 >> 1) && bytes[1] == (0x4D >> 1)) {
                        // 'BM'.
                        newFile = new File(fileInfo.folder, file.getName() + ".bmp");
                    } else {
                        // No luck.
                        final StringBuilder bytePattern = new StringBuilder();
                        for (byte b : bytes) {
                            if (bytePattern.length() != 0) {
                                bytePattern.append(" ");
                            }
                            bytePattern.append(String.format("%02X", b));
                        }
                        logOrPrint(logger, Level.WARNING, "Found unknown bit pattern `" + bytePattern + "` in file without extension for folder `" + fileInfo.folder.getName() + "`.");
                        newFile = file;
                    }
                } else {
                    newFile = file;
                }
            }
        }
        Files.copy(byteStream, newFile.toPath());
    }

    private static String getFileName(String url) {
        // Chop off parameters, if they exist.
        final int parametersIndex = url.lastIndexOf("?");
        final String parameters;
        if (parametersIndex != -1) {
            parameters = url.substring(parametersIndex + 1);
            url = url.substring(0, parametersIndex);
        } else {
            parameters = null;
        }
        // Check for a file extension.
        String extension = null;
        if (url.endsWith(".jpeg") || url.endsWith(".jpg")) {
            extension = ".jpg";
        } else if (url.endsWith(".png")) {
            extension = ".png";
        } else if (url.endsWith(".gif")) {
            extension = ".gif";
        } else if (url.endsWith(".svg")) {
            extension = ".svg";
        } else if (url.endsWith(".bmp")) {
            // See cover image of Kindle preview of `https://www.amazon.com/dp/B000FBJAJ6`.
            extension = ".bmp";
        }
        if (extension != null) {
            // Chop off the extension. It will be added later.
            url = url.substring(0, url.length() - extension.length());
        } else if (parameters != null) {
            // When no extension exists, check the parameters for a MIME type.
            if (parameters.contains("mime=image/jpeg") || parameters.contains("mime=image/jpg")) {
                extension = ".jpg";
            } else if (parameters.contains("mime=image/png")) {
                extension = ".png";
            } else if (parameters.contains("mime=image/gif")) {
                extension = ".gif";
            } else if (parameters.contains("mime=image/svg+xml")) {
                extension = ".svg";
            } else if (parameters.contains("mime=image/bmp")) {
                extension = ".bmp";
            }
        }
        // Replace illegal characters.
        url = url.replaceAll("[:/\\\\.$<>]+", "_");
        // Add the extension, if it exists.
        if (extension != null) {
            url = url + extension;
        }
        return url.trim();
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

    private static void logOrPrint(Logger logger, Level level, String msg) {
        logOrPrint(logger, level, msg, null);
    }

    private static void logOrPrint(Logger logger, Level level, String msg, Throwable t) {
        if (logger != null) {
            logger.log(level, msg, t);
        } else if (level == Level.SEVERE || level == Level.WARNING) {
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
}
