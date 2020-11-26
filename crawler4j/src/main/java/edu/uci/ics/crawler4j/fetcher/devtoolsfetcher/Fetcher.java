package edu.uci.ics.crawler4j.fetcher.devtoolsfetcher;

import edu.uci.ics.crawler4j.crawler.exceptions.PageBiggerThanMaxSizeException;
import edu.uci.ics.crawler4j.fetcher.pojo.PageFetchResult;
import edu.uci.ics.crawler4j.url.WebURL;

import java.io.IOException;

public interface Fetcher {

    PageFetchResult fetchPage(WebURL webUrl) throws IOException, InterruptedException,PageBiggerThanMaxSizeException;

    void shutDown();
}
