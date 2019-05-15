package com.ericrobertbrewer.bookspider.sites.meta;

import com.ericrobertbrewer.bookspider.Folders;
import com.ericrobertbrewer.bookspider.Launcher;
import com.ericrobertbrewer.bookspider.sites.BookScrapeInfo;
import com.ericrobertbrewer.bookspider.sites.SiteScraper;
import com.ericrobertbrewer.bookspider.sites.text.AmazonKindle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class Personal {

    public static class AmazonKindleProvider implements SiteScraper.Provider<AmazonKindle>, AmazonKindle.Listener {

        public static void main(String[] args) throws IOException {
            Launcher.launch(args, new AmazonKindleProvider());
        }

        @Override
        public AmazonKindle newInstance(Logger logger) {
            final List<BookScrapeInfo> bookScrapeInfos = new ArrayList<>();
            bookScrapeInfos.add(new BookScrapeInfo("B07RHQ2ZBH", new String[] {"https://www.amazon.com/dp/B07RHQ2ZBH"}, "B07RHQ2ZBH"));
            final AmazonKindle amazonKindle = new AmazonKindle(logger, bookScrapeInfos);
            amazonKindle.setListener(this);
            return amazonKindle;
        }

        @Override
        public String getId() {
            return Folders.ID_AMAZON_KINDLE;
        }

        @Override
        public void onComplete(AmazonKindle instance) {
            instance.setListener(null);
        }

        @Override
        public void onUpdateBook(String bookId, String asin) {
        }
    }
}
