package edu.uci.ics.crawler4j.fetcher.devtoolsfetcher;

import edu.uci.ics.crawler4j.crawler.exceptions.PageBiggerThanMaxSizeException;
import edu.uci.ics.crawler4j.crawler.exceptions.ParseException;
import edu.uci.ics.crawler4j.fetcher.PageFetchResult;
import edu.uci.ics.crawler4j.url.WebURL;

import java.io.IOException;

public interface Fetcher {

    PageFetchResult fetchPage(WebURL webUrl) throws IOException, InterruptedException, ParseException, PageBiggerThanMaxSizeException;

    void shutDown();
}
