package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.bookspider.sites.SiteScraperTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonKindleTest extends SiteScraperTest<AmazonKindle> {

    private static final SiteScraper.Provider<AmazonKindle> TEST_PROVIDER = new SiteScraper.Provider<>() {
        @Override
        public AmazonKindle newInstance(Logger logger) {
            final List<BookScrapeInfo> bookScrapeInfos = new ArrayList<>();
            return new AmazonKindle(logger, bookScrapeInfos);
        }

        @Override
        public String getId() {
            return Folders.ID_BOOKCAVE_AMAZON_KINDLE;
        }

        @Override
        public void onComplete(AmazonKindle instance) {
        }
    };

    private static final String EMAIL = "EMAIL"; //"eric.r.brewer@gmail.com";
    private static final String PASSWORD = "PASSWORD";

    private WebDriver driver;

    @Override
    protected SiteScraper.Provider<AmazonKindle> getTestProvider() {
        return TEST_PROVIDER;
    }

    @Override
    @BeforeEach
    public void setUp() throws IOException {
        super.setUp();
        driver = getFactory().newInstance();
        getScraper().setIsWindowSingleColumn(driver);
    }

    @Test
    void returnKindleUnlimitedBookTest() {
        // 'Gate 76' in Amazon Kindle Store: `https://www.amazon.com/dp/B07BD35VN6`.
        assertTrue(getScraper().returnKindleUnlimitedBook(driver, "Gate 76", EMAIL, PASSWORD));
    }

    @Override
    @AfterEach
    public void tearDown() {
        driver.quit();
        super.tearDown();
    }
}