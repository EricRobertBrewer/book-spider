package com.ericrobertbrewer.bookspider.sites;


import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.SiteScraper;
import com.ericrobertbrewer.web.WebDriverFactory;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class Amazon extends SiteScraper {

    private final List<String> bookIds;
    private final List<String> urls;

    private final AtomicBoolean isScrapingBooks = new AtomicBoolean(false);
    private final AtomicBoolean isDownloadingImages = new AtomicBoolean(false);

    Amazon(Logger logger, List<String> bookIds, List<String> urls) {
        super(logger);
        this.bookIds = bookIds;
        this.urls = urls;
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force, Launcher.Callback callback) {
        final Queue<ImageInfo> imagesQueue = new LinkedList<>();
        // Create scraping thread.
        final Thread scrapeThread = new Thread(() -> {
            isScrapingBooks.set(true);
            final WebDriver driver = factory.newChromeDriver();
            scrapeBookTexts(driver, contentFolder, imagesQueue, force);
            driver.quit();
            isScrapingBooks.set(false);
            if (!isDownloadingImages.get()) {
                callback.onComplete();
            }
        });
        scrapeThread.start();
        // Create image download thread.
        final Thread imagesThread = new Thread(() -> {
            isDownloadingImages.set(true);
            final OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            downloadImages(client, imagesQueue, force);
            isDownloadingImages.set(false);
            if (!isScrapingBooks.get()) {
                callback.onComplete();
            }
        });
        imagesThread.start();
    }

    private void scrapeBookTexts(WebDriver driver, File contentFolder, Queue<ImageInfo> imagesQueue, boolean force) {
        for (int i = 0; i < bookIds.size(); i++) {
            final String bookId = bookIds.get(i);
            final String url = urls.get(i);
            int retries = 3;
            while (retries > 0) {
                try {
                    scrapeBookText(bookId, url, driver, contentFolder, imagesQueue, force);
                    break;
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Encountered IOException while scraping book `" + bookId + "`.", e);
                } catch (NoSuchElementException e) {
                    getLogger().log(Level.WARNING, "Unable to find element while scraping book `" + bookId + "`.", e);
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Encountered unknown error while scraping book `" + bookId + "`.", t);
                }
                retries--;
            }
        }
    }

    private void scrapeBookText(String bookId, String url, WebDriver driver, File contentFolder, Queue<ImageInfo> imagesQueue, boolean force) throws IOException, NoSuchElementException {
        // Check if contents for this book already exist.
        final File bookFolder = new File(contentFolder, bookId);
        if (bookFolder.exists()) {
            if (force) {
                // `force`==`true`. Delete the existing folder and its contents.
                // Delete contents of folder.
                final File[] files = bookFolder.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.delete()) {
                            throw new IOException("Unable to delete content file `" + file.getPath() + "`.");
                        }
                    }
                }
                // Delete folder itself.
                if (!bookFolder.delete()) {
                    throw new IOException("Unable to delete existing book folder for `" + bookId + "` when `force`==`true`.");
                }
            } else {
                // `force`==`false`. Quit.
                return;
            }
        }
        if (!bookFolder.mkdirs()) {
            throw new IOException("Unable to create book folder for `" + bookId + "`.");
        }
        // Scrape the book contents.
        getLogger().log(Level.INFO, "Scraping text for book `" + bookId + "`.");
        driver.navigate().to(url);
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // Close the "Read this book for free with Kindle Unlimited" popover, if it appears.
        // See `https://www.amazon.com/dp/1980537615`.
        try {
            final WebElement aModalScrollerDiv = driver.findElement(By.className("a-modal-scroller"));
            final WebElement noButton = aModalScrollerDiv.findElement(By.id("p2dPopoverID-no-button"));
            noButton.click();
        } catch (NoSuchElementException ignored) {
            // It usually doesn't appear.
        }
        final WebElement aPageDiv;
        try {
            aPageDiv = driver.findElement(By.id("a-page"));
        } catch (NoSuchElementException e) {
            // See `https://mybookcave.com/mybookratings/rated-book/forbidden-2/`.
            throw new NoSuchElementException("The Amazon page for book `" + bookId + "` may no longer exist. Retrying.");
        }
        final WebElement dpDiv = aPageDiv.findElement(By.id("dp"));
        final WebElement dpContainerDiv = dpDiv.findElement(By.id("dp-container"));
        final WebElement lookInsideLogoImg = findLookInsideLogoImg(dpContainerDiv);
        if (lookInsideLogoImg == null) {
            // This book does not have a 'Look Inside' element.
            // Therefore, there is no preview for this book.
            getLogger().log(Level.INFO, "Unable to find 'Look Inside' element for book `" + bookId + "`. Skipping.");
            return;
        }
        try {
            lookInsideLogoImg.click();
        } catch (WebDriverException ignored) {
            // The 'Look Inside' (background) image is not clickable.
            // Since it at least exists, try to click the cover image to open the preview window.
            // See `https://www.amazon.com/dp/1523813342`.
            final WebElement imgBlkFrontImg = dpContainerDiv.findElement(By.id("imgBlkFront"));
            imgBlkFrontImg.click();
        }
        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        final WebElement sitbReaderPlaceholderDiv = aPageDiv.findElement(By.id("sitbReaderPlaceholder"));
        final WebElement sitbLightboxDiv;
        try {
            sitbLightboxDiv = sitbReaderPlaceholderDiv.findElement(By.id("sitbLightbox"));
        } catch (NoSuchElementException e) {
            // "Feature Unavailable"
            // "We're sorry, but this feature is currently unavailable. Please try again later."
            // See `https://www.amazon.com/dp/B01MYH403A`.
            throw new NoSuchElementException("Kindle sample feature may be unavailable for book `" + bookId + "`. Retrying.");
        }
        final WebElement sitbLBHeaderDiv = sitbLightboxDiv.findElement(By.id("sitbLBHeader"));
        final WebElement sitbReaderModeDiv = sitbLBHeaderDiv.findElement(By.id("sitbReaderMode"));
        // Prefer the 'Kindle Book' view, but accept the 'Print Book' view.
        try {
            final WebElement readerModeTabKindleDiv = sitbReaderModeDiv.findElement(By.id("readerModeTabKindle"));
            try {
                readerModeTabKindleDiv.click();
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (ElementNotVisibleException e) {
                // The 'Kindle Book' `div` may not be clickable.
                getLogger().log(Level.INFO, "Unable to click 'Kindle Book' tab element. Continuing.");
            }
        } catch (NoSuchElementException e) {
            getLogger().log(Level.INFO, "Page for book `" + bookId + "` does not contain 'Kindle Book' reader mode.");
        }
        // Zoom out. This may prevent having to scroll the page a lot further.
        final WebElement sitbReaderZoomToolbarDiv = sitbLBHeaderDiv.findElement(By.id("sitbReaderZoomToolbar"));
        final WebElement sitbReaderTitlebarZoomOutButton = sitbReaderZoomToolbarDiv.findElement(By.id("sitbReaderTitlebarZoomOut"));
        sitbReaderTitlebarZoomOutButton.click();
        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        final WebElement sitbReaderMiddleDiv = sitbLightboxDiv.findElement(By.id("sitbReaderMiddle"));
        final WebElement sitbReaderPageareaDiv = sitbReaderMiddleDiv.findElement(By.id("sitbReader-pagearea"));
        final WebElement sitbReaderPageContainerDiv = sitbReaderPageareaDiv.findElement(By.id("sitbReaderPageContainer"));
        final WebElement sitbReaderPageScrollDiv = sitbReaderPageContainerDiv.findElement(By.id("sitbReaderPageScroll"));
        final WebElement sitbReaderKindleSampleDiv;
        try {
            sitbReaderKindleSampleDiv = sitbReaderPageScrollDiv.findElement(By.id("sitbReaderKindleSample"));
        } catch (NoSuchElementException e) {
            // This book does not have a Kindle sample.
            // Though it has a print sample, it is an `img` which loads lazily, which would require both
            // scrolling the page while waiting for loads and an image-to-text engine.
            // TODO: Do this.
            getLogger().log(Level.INFO, "Book `" + bookId + "` does not have a Kindle sample. Skipping.");
            return;
        }
        try {
            final WebElement sitbReaderFrame = sitbReaderKindleSampleDiv.findElement(By.id("sitbReaderFrame"));
            writeBookText(driver, sitbReaderFrame, bookFolder, imagesQueue);
        } catch (NoSuchElementException e) {
            // This page does not have an `iframe` element.
            // That is OK. The book contents are simply embedded in the same page.
            writeBookText(driver, sitbReaderKindleSampleDiv, bookFolder, imagesQueue);
        }
    }

    private WebElement findLookInsideLogoImg(WebElement dpContainerDiv) {
        try {
            return dpContainerDiv.findElement(By.id("ebooksSitbLogoImg"));
        } catch (NoSuchElementException e) {
            // This page may be a slightly different format than most.
            // See `https://www.amazon.com/dp/B01I39Y1UY`.
            try {
                return dpContainerDiv.findElement(By.id("sitbLogoImg"));
            } catch (NoSuchElementException ignored) {
            }
        }
        return null;
    }

    private void writeBookText(WebDriver driver, WebElement rootElement, File bookFolder, Queue<ImageInfo> imagesQueue) throws IOException {
        // Create the book text file.
        final File textFile = new File(bookFolder, "book.txt");
        if (!textFile.createNewFile()) {
            getLogger().log(Level.SEVERE, "Unable to create book text file `" + textFile.getPath() + "`.");
            return;
        }
        if (!textFile.canWrite() && !textFile.setWritable(true)) {
            getLogger().log(Level.SEVERE, "Unable to write to book text file `" + textFile.getPath() + "`.");
            return;
        }
        final PrintStream out = new PrintStream(textFile);
        writeElementText(driver, rootElement, bookFolder, out, imagesQueue);
        out.close();
    }

    private void writeElementText(WebDriver driver, WebElement element, File bookFolder, PrintStream out, Queue<ImageInfo> imagesQueue) {
        // Search recursively for matching children.
        final List<WebElement> children = element.findElements(By.xpath("./*"));
        if (children.size() == 0 || areAllFormatting(children)) {
            // This element is considered relevant. Write its contents.
            final String tag = element.getTagName();
            // Handle certain tags specially.
            if ("style".equals(tag)) {
                // Ignore.
                return;
            } else if ("iframe".equals(tag)) {
                driver.switchTo().frame(element);  // Does this need to be undone?
                final WebElement frameBody = driver.findElement(By.tagName("body"));
                writeElementText(driver, frameBody, bookFolder, out, imagesQueue);
                return;
            }
            // Extract the raw inner HTML.
            final String html = element.getAttribute("innerHTML");
            // Check for and download any images within image (`img`) tags.
            final String[] imageUrls = getImageUrls(html);
            for (String url : imageUrls) {
                imagesQueue.offer(new ImageInfo(url, bookFolder));
            }
            // On every relevant LEAF-ELEMENT, check for a `background-image` CSS attribute.
            final String backgroundImageValue = element.getCssValue("background-image");
            if (backgroundImageValue != null && !backgroundImageValue.isEmpty() && !"none".equals(backgroundImageValue)) {
                final String url;
                if (backgroundImageValue.startsWith("url(\"") && backgroundImageValue.endsWith("\")")) {
                    url = backgroundImageValue.substring(5, backgroundImageValue.length() - 2).trim();
                } else {
                    url = backgroundImageValue.trim();
                }
                imagesQueue.offer(new ImageInfo(url, bookFolder));
            }
            // Convert HTML to human-readable text.
            final String text = html
                    // Extract `img` `alt` text.
                    // An image is used frequently as a "drop cap" (https://graphicdesign.stackexchange.com/questions/85715/first-letter-of-a-book-or-chapter).
                    // See `https://www.amazon.com/dp/B078WY9W7K`.
                    .replaceAll("<img[^>]*? alt=\"([^\"]+)\"[^>]*?>", "$1")
                    // Break lines.
                    // Though the `br` tag is considered a "formatting" tag, this can prevent unwanted
                    // concatenation of texts that are really on separate lines.
                    .replaceAll("<br.*?>", "\n")
                    // Ignore other HTML formatting tags, e.g. links, italics.
                    .replaceAll("<([-a-zA-Z0-9]+).*?>(.*?)</\\1>", "$2")
                    // Ignore self-closing tags.
                    .replaceAll("<[^>]+?/?>", "")
                    // Decode the most common HTML character entity references.
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&quot;", "\"")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .trim();
            if (text.isEmpty()) {
                return;
            }
            out.println(text);
        } else {
            for (WebElement child : children) {
                writeElementText(driver, child, bookFolder, out, imagesQueue);
            }
        }
    }

    /**
     * A set of HTML tag names which are NOT to be considered "containers" of relevant text,
     * where a "container" would typically be `div`, `p`, `h1`, etc. elements.
     * Used as a blacklisting method when capturing text.
     */
    private static final Set<String> FORMATTING_TAGS = new HashSet<>();
    static {
        FORMATTING_TAGS.add("em");
        FORMATTING_TAGS.add("i");
        FORMATTING_TAGS.add("strong");
        FORMATTING_TAGS.add("b");
        FORMATTING_TAGS.add("u");
        FORMATTING_TAGS.add("tt");
        FORMATTING_TAGS.add("strike");
        FORMATTING_TAGS.add("s");
        FORMATTING_TAGS.add("big");
        FORMATTING_TAGS.add("small");
        FORMATTING_TAGS.add("mark");
        FORMATTING_TAGS.add("del");
        FORMATTING_TAGS.add("ins");
        FORMATTING_TAGS.add("sub");
        FORMATTING_TAGS.add("sup");
        FORMATTING_TAGS.add("br");
        // For our purposes, `span`s should probably be ignored.
        // Text in descriptions seems to only be contained in `p`s and `div`s.
        FORMATTING_TAGS.add("span");
        FORMATTING_TAGS.add("style");
        FORMATTING_TAGS.add("img");  // Allows capturing of Drop Caps.
        FORMATTING_TAGS.add("hr");
        FORMATTING_TAGS.add("a");
        FORMATTING_TAGS.add("font");
    }

    private static boolean areAllFormatting(List<WebElement> elements) {
        for (WebElement element : elements) {
            final String tag = element.getTagName();
            if (!FORMATTING_TAGS.contains(tag)) {
                return false;
            }
        }
        return true;
    }

    private static String[] getImageUrls(String html) {
        // See `https://stackoverflow.com/questions/6020384/create-array-of-regex-matches`.
        return Pattern.compile("<img[^>]*? src=\"([^\"]+)\"[^>]*?>")
                .matcher(html)
                .results()
                .map(matchResult -> matchResult.group(1))
                .toArray(String[]::new);
    }

    private static class ImageInfo {
        final String url;
        final File bookFolder;

        ImageInfo(String url, File bookFolder) {
            this.url = url;
            this.bookFolder = bookFolder;
        }
    }

    private void downloadImages(OkHttpClient client, Queue<ImageInfo> imagesQueue, boolean force) {
        getLogger().log(Level.INFO, "Downloading images...");
        while (isScrapingBooks.get() || !imagesQueue.isEmpty()) {
            // Wait for image queue to fill.
            if (imagesQueue.isEmpty()) {
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }
            // Download the image.
            final ImageInfo imageInfo = imagesQueue.poll();
            getLogger().log(Level.INFO, "Downloading image `" + imageInfo.url + "` for book `" + imageInfo.bookFolder.getName() + "`.");
            // Check for an existing file with the same name, optionally with a file extension.
            final String imageFileNameCandidate = getImageFileName(imageInfo.url);
            final File imageFile;
            final File similarImageFile = findSimilarFile(imageInfo.bookFolder, imageFileNameCandidate);
            imageFile = Objects.requireNonNullElseGet(similarImageFile, () -> new File(imageInfo.bookFolder, imageFileNameCandidate));
            // Process `force` flag.
            if (imageFile.exists()) {
                if (force) {
                    if (!imageFile.delete()) {
                        getLogger().log(Level.SEVERE, "Unable to delete image file `" + imageFile.getPath() + "`.");
                        continue;
                    }
                } else {
                    continue;
                }
            }
            int retries = 3;
            while (retries > 0) {
                try {
                    downloadImage(client, imageInfo, imageFile, getLogger());
                    break;
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "Encountered IOException while downloading image `" + imageInfo.url + "` for book `" + imageInfo.bookFolder.getName() + "`.", e);
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Encountered unknown error while downloading image `" + imageInfo.url + "` for book `" + imageInfo.bookFolder.getName() + "`.", t);
                }
                retries--;
            }
        }
        getLogger().log(Level.INFO, "Finished downloading images.");
    }

    private static void downloadImage(OkHttpClient client, ImageInfo imageInfo, File imageFile, Logger logger) throws IOException {
        final Request request = new Request.Builder()
                .url(imageInfo.url)
                .build();
        final Call call = client.newCall(request);
        final Response response = call.execute();
        if (response.body() == null) {
            return;
        }
        if (imageFile.getName().contains(".")) {
            Files.copy(response.body().byteStream(), imageFile.toPath());
        } else {
            // Look for a `Content-Type`.
            final String contentType = response.header("Content-Type");
            final File newImageFile;
            if ("image/jpeg".equalsIgnoreCase(contentType)) {
                newImageFile = new File(imageInfo.bookFolder, imageFile.getName() + ".jpg");
            } else if ("image/png".equalsIgnoreCase(contentType)) {
                newImageFile = new File(imageInfo.bookFolder, imageFile.getName() + ".png");
            } else if ("image/gif".equalsIgnoreCase(contentType)) {
                newImageFile = new File(imageInfo.bookFolder, imageFile.getName() + ".gif");
            } else if ("image/svg+xml".equalsIgnoreCase(contentType)) {
                newImageFile = new File(imageInfo.bookFolder, imageFile.getName() + ".svg");
            } else if ("image/bmp".equalsIgnoreCase(contentType)) {
                newImageFile = new File(imageInfo.bookFolder, imageFile.getName() + ".bmp");
            } else {
                // No luck.
                if (contentType != null) {
                    final String msg = "Found unknown Content-Type `" + contentType + "` while downloading image for book `" + imageInfo.bookFolder.getName() + "`.";
                    if (logger != null) {
                        logger.log(Level.WARNING, msg);
                    } else {
                        System.err.println(msg);
                    }
                }
                newImageFile = imageFile;
            }
            Files.copy(response.body().byteStream(), newImageFile.toPath());
        }
    }

    private static String getImageFileName(String url) {
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
            if (parameters.contains("mime=image/jpeg")) {
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

    private static File findSimilarFile(File bookFolder, String fileName) {
        final File[] files = bookFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(fileName)) {
                    return file;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static class MoveFiles {

        public static void main(String[] args) throws IOException {
            if (args.length < 2 || args.length > 2) {
                throw new IllegalArgumentException("Usage: <content-source-folder> <content-destination-folder>");
            }
            final File sourceFolder = new File(args[0]);
            if (!sourceFolder.isDirectory()) {
                throw new IllegalArgumentException("`" + args[0] + "` is not a directory.");
            }
            final File contentDestinationFolder = new File(args[1]);
            if (!contentDestinationFolder.exists() && !contentDestinationFolder.mkdirs()) {
                throw new IOException("Unable to create directory `" + args[1] + "`.");
            }
            final File[] sourceFiles = sourceFolder.listFiles();
            if (sourceFiles == null || sourceFiles.length == 0) {
                throw new IllegalArgumentException("Source directory `" + args[0] + "` is empty.");
            }
            for (File sourceFile : sourceFiles) {
                final String fullName = sourceFile.getName();
                final String baseName = fullName.substring(0, fullName.length() - 4);
                final File destinationFolder = new File(contentDestinationFolder, baseName);
                if (!destinationFolder.exists() && !destinationFolder.mkdirs()) {
                    throw new IOException("Unable to create destination folder `" + destinationFolder.getName() + "`.");
                }
                final File destinationFile = new File(destinationFolder, "text.txt");
                if (destinationFile.exists()) {
                    continue;
                }
                if (!sourceFile.renameTo(destinationFile)) {
                    throw new IOException("Unable to rename source file `" + sourceFile.getName() + "`.");
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private static class DownloadBackgroundImages {

        public static void main(String[] args) throws IOException {
            if (args.length < 1 || args.length > 2) {
                throw new IllegalArgumentException("Usage: <log-file-name> [force]");
            }
            // Get log file name.
            final String logFileName = args[0];
            final File logFile = new File(Folders.getLogsFolder(Folders.ID_BOOK_CAVE_AMAZON), logFileName);
            if (!logFile.exists()) {
                throw new IllegalArgumentException("Cannot find log file `" + logFile.getPath() + "`.");
            }
            // Check `force` flag.
            final boolean force;
            if (args.length > 1) {
                force = Boolean.parseBoolean(args[1]);
            } else {
                force = false;
            }
            final Scanner scanner = new Scanner(logFile);
            final File contentFolder = Folders.getContentFolder(Folders.ID_BOOK_CAVE_AMAZON);
            final OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            final String unknownErrorUrlStart = "WARNING: Encountered unknown error while downloading image `url(\"";
            while (scanner.hasNextLine()) {
                final String line = scanner.nextLine();
                if (!line.startsWith(unknownErrorUrlStart)) {
                    continue;
                }
                final int bookIdIndexEnd = line.lastIndexOf("`");
                final int bookIdIndexStart = line.lastIndexOf("`", bookIdIndexEnd - 1);
                final String bookId = line.substring(bookIdIndexStart + 1, bookIdIndexEnd);
                final File bookFolder = new File(contentFolder, bookId);
                if (!bookFolder.exists()) {
                    System.err.println("Cannot find book folder `" + bookFolder.getName() + "`.");
                    continue;
                }
                final int urlIndexEnd = line.lastIndexOf("\"", bookIdIndexStart - 1);
                final String url = line.substring(unknownErrorUrlStart.length(), urlIndexEnd);
                final String imageFileNameCandidate = getImageFileName(url);
                final ImageInfo imageInfo = new ImageInfo(url, bookFolder);
                final File imageFile;
                final File similarImageFile = findSimilarFile(imageInfo.bookFolder, imageFileNameCandidate);
                imageFile = Objects.requireNonNullElseGet(similarImageFile, () -> new File(imageInfo.bookFolder, imageFileNameCandidate));
                // Process `force` flag.
                if (imageFile.exists()) {
                    if (force) {
                        if (!imageFile.delete()) {
                            System.err.println("Unable to delete image file `" + imageFile.getPath() + "`.");
                            continue;
                        }
                    } else {
                        continue;
                    }
                }
                System.out.println("Downloading image `" + imageInfo.url + "` for book `" + imageInfo.bookFolder.getName() + "`.");
                int retries = 3;
                while (retries > 0) {
                    try {
                        downloadImage(client, imageInfo, imageFile, null);
                        break;
                    } catch (IOException e) {
                        System.err.println("Encountered IOException while downloading image `" + imageInfo.url + "` for book `" + imageInfo.bookFolder.getName() + "`.");
                    } catch (Throwable t) {
                        System.err.println("Encountered unknown error while downloading image `" + imageInfo.url + "` for book `" + imageInfo.bookFolder.getName() + "`.");
                    }
                    retries--;
                }
            }
            scanner.close();
        }
    }
}
