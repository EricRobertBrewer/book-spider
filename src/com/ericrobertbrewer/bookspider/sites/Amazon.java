package com.ericrobertbrewer.bookspider.sites;


import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.SiteScraper;
import com.ericrobertbrewer.web.WebDriverFactory;
import org.openqa.selenium.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Amazon extends SiteScraper {

    private final List<String> bookIds;
    private final List<String> urls;

    Amazon(Logger logger, List<String> bookIds, List<String> urls) {
        super(logger);
        this.bookIds = bookIds;
        this.urls = urls;
    }

    @Override
    public void scrape(WebDriverFactory factory, File contentFolder, boolean force, Launcher.Callback callback) {
        // Create driver.
        final WebDriver driver = factory.newChromeDriver();
        scrapeBookTexts(driver, contentFolder, force);
        driver.quit();
        // Close resources.
        callback.onComplete();
    }

    private void scrapeBookTexts(WebDriver driver, File contentFolder, boolean force) {
        for (int i = 0; i < bookIds.size(); i++) {
            final String bookId = bookIds.get(i);
            final String url = urls.get(i);
            int retries = 3;
            while (retries > 0) {
                try {
                    scrapeBookText(bookId, url, driver, contentFolder, force);
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

    private void scrapeBookText(String bookId, String url, WebDriver driver, File contentFolder, boolean force) throws IOException, NoSuchElementException {
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
            writeBookContents(driver, sitbReaderFrame, bookFolder);
        } catch (NoSuchElementException e) {
            // This page does not have an `iframe` element.
            // That is OK. The book contents are simply embedded in the same page.
            writeBookContents(driver, sitbReaderKindleSampleDiv, bookFolder);
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

    private void writeBookContents(WebDriver driver, WebElement rootElement, File bookFolder) throws IOException {
        // Create the book text file.
        final File textFile = new File(bookFolder, "text.txt");
        if (!textFile.createNewFile()) {
            getLogger().log(Level.SEVERE, "Unable to create book text file `" + textFile.getPath() + "`.");
            return;
        }
        if (!textFile.canWrite() && !textFile.setWritable(true)) {
            getLogger().log(Level.SEVERE, "Unable to write to book text file `" + textFile.getPath() + "`.");
            return;
        }
        final PrintStream out = new PrintStream(textFile);
        writeElementContents(driver, rootElement, bookFolder, out);
        out.close();
    }

    private void writeElementContents(WebDriver driver, WebElement element, File bookFolder, PrintStream out) {
        // Search recursively for matching children.
        final List<WebElement> children = element.findElements(By.xpath("./*"));
        if (children.size() == 0 || areAllFormatting(children)) {
            final String tag = element.getTagName();
            // Handle certain tags specially.
            if ("style".equals(tag)) {
                // Ignore.
                return;
            } else if ("iframe".equals(tag)) {
                driver.switchTo().frame(element);  // Does this need to be undone?
                final WebElement frameBody = driver.findElement(By.tagName("body"));
                writeElementContents(driver, frameBody, bookFolder, out);
                return;
            }
            final String text = element.getAttribute("innerHTML")
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
                writeElementContents(driver, child, bookFolder, out);
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
}
