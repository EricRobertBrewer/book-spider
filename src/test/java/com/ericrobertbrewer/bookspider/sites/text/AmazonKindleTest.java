package com.ericrobertbrewer.bookspider.sites.text;

import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.sites.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.bookspider.sites.SiteScraperTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AmazonKindleTest extends SiteScraperTest<AmazonKindle> {

    private static final SiteScraper.Provider<AmazonKindle> TEST_PROVIDER = new SiteScraper.Provider<>() {
        @Override
        public AmazonKindle newInstance(Logger logger) {
            final List<BookScrapeInfo> bookScrapeInfos = new ArrayList<>();
            // Add individual books here.
//            bookScrapeInfos.add(new BookScrapeInfo("my-snowman-paul", new String[]{"https://mybookcave.com/t/?u=0&b=69733&r=86&sid=mybookcave&utm_campaign=MBR+site&utm_source=direct&utm_medium=website"}, null));
//            bookScrapeInfos.add(new BookScrapeInfo("errol-amberdane", new String[]{"https://mybookcave.com/t/?u=0&b=73560&r=86&sid=mybookcave&utm_campaign=MBR+site&utm_source=direct&utm_medium=website"}, null));
            bookScrapeInfos.add(new BookScrapeInfo("underdogs", new String[]{"https://mybookcave.com/t/?u=0&b=32294&r=86&sid=mybookcave&utm_campaign=MBR+site&utm_source=direct&utm_medium=website"}, null));
            return new AmazonKindle(logger, bookScrapeInfos);
        }

        @Override
        public String getId() {
            return Folders.ID_AMAZON_KINDLE;
        }

        @Override
        public void onComplete(AmazonKindle instance) {
        }
    };

    private static final String EMAIL = "EMAIL";
    private static final String PASSWORD = "PASSWORD";
    private static final String FIRST_NAME = "FIRST_NAME";
    private static final boolean REMEMBER_ME = false;

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
    }

    @Test
    void scrapeTest() {
        getScraper().scrapeBooks(new LinkedList<>(getScraper().bookScrapeInfos), driver, "kindle", getContentFolder(), null, EMAIL, PASSWORD, FIRST_NAME, REMEMBER_ME, 4, false);
    }

    @Test
    void returnKindleUnlimitedBookTest() {
        // 'Gate 76' in Amazon Kindle Store: `https://www.amazon.com/dp/B07BD35VN6`.
        assertTrue(getScraper().returnKindleUnlimitedBook(driver, "Gate 76", EMAIL, PASSWORD, REMEMBER_ME));
    }

    @Override
    @AfterEach
    public void tearDown() {
        driver.quit();
        super.tearDown();
    }
}