/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.crawler4j.fetcher.devtoolsfetcher;


import edu.uci.ics.crawler4j.chrome.Chrome;
import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.exceptions.PageBiggerThanMaxSizeException;
import edu.uci.ics.crawler4j.fetcher.PageFetchResult;
import edu.uci.ics.crawler4j.fetcher.pojo.HttpEntityImpl;
import edu.uci.ics.crawler4j.url.URLCanonicalizer;
import edu.uci.ics.crawler4j.url.WebURL;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Yasser Ganjisaffar
 */
public class ChromePageFetcher implements Fetcher {


    protected static final Logger logger = LoggerFactory.getLogger(ChromePageFetcher.class);
    protected Chrome chrome = new Chrome();
    /**
     * This field is protected for retro compatibility. Please use the getter method: getConfig() to
     * read this field;
     */
    protected final CrawlConfig config;
    protected Map<String, LocalDateTime> lastFetchTimeByTopLevelDomain = new ConcurrentHashMap<>();

    //TODO(Take Chrome instance as an argument in constructor instead of hard coding it)
    public ChromePageFetcher(CrawlConfig config) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        this.config = config;
    }


    @Override
    public PageFetchResult fetchPage(WebURL webUrl)
        throws InterruptedException, IOException, PageBiggerThanMaxSizeException {
        // Getting URL, setting headers & content
        PageFetchResult fetchResult = new PageFetchResult(config.isHaltOnError());
        String toFetchURL = webUrl.getURL();
        if (config.getPolitenessDelay() > 0) {
            // Applying Politeness delay
            applyPolitenessDelay(webUrl);
        }
        HttpEntityImpl response = chrome.fetchPage(webUrl.getURL());
        fetchResult.setEntity(response);
        fetchResult.setResponseHeaders(response.responseHeaders);
        int statusCode;
        if (!response.isSuccess) {
            statusCode = 500;
        } else if (response.isRedirect) {
            statusCode = 301;
        } else {
            statusCode = response.status;
        }
        logger.debug(webUrl + " --> " + statusCode + " ");
        if (statusCode > 499) {
            logger.info("----------- retry... why this works?");
            //Todo find a case like this
        }
//         If Redirect ( 3xx )
        if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY ||
            statusCode == HttpStatus.SC_MOVED_TEMPORARILY ||
            statusCode == HttpStatus.SC_MULTIPLE_CHOICES ||
            statusCode == HttpStatus.SC_SEE_OTHER ||
            statusCode == HttpStatus.SC_TEMPORARY_REDIRECT ||
            statusCode == 308) { // todo follow
            // https://issues.apache.org/jira/browse/HTTPCORE-389
            String movedToUrl = URLCanonicalizer.getCanonicalURL(response.redirectUrl, toFetchURL);
            fetchResult.setMovedToUrl(movedToUrl);
        } else if (statusCode > 399) {
            if (!toFetchURL.replaceAll("(https?://[^/]+).+", "").equals(toFetchURL)) {
                // redirecting to the root of the domain
                String movedToUrl = URLCanonicalizer.getCanonicalURL(toFetchURL.replaceAll("(https?://[^/]+).+", "$1"));
                fetchResult.setMovedToUrl(movedToUrl);
                statusCode = 301;
            }
        } else if (statusCode >= 200 && statusCode <= 299) { // is 2XX, everything looks ok
            fetchResult.setFetchedUrl(toFetchURL);
            String uri = response.requestUrl;
            if (!uri.equals(toFetchURL)) {
                if (!URLCanonicalizer.getCanonicalURL(uri).equals(toFetchURL)) {
                    fetchResult.setFetchedUrl(uri);
                }
            }
            // Checking maximum size
            if (fetchResult.getEntity() != null) {
                long size = fetchResult.getEntity().getContentLength();
                if (size == -1) {
                    size = response.contentLength;
                }
                if (size > config.getMaxDownloadSize()) {
                    //fix issue #52 - consume entity
                    throw new PageBiggerThanMaxSizeException(size);
                }
            }
        }
        fetchResult.setStatusCode(statusCode);
        return fetchResult;
    }

    private void applyPolitenessDelay(WebURL webUrl) throws InterruptedException {
        if (webUrl.getDomain() == null) {
            try {
                throw new RuntimeException("Domain must not be null");
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
            System.exit(-1);
        }
        if (lastFetchTimeByTopLevelDomain.containsKey(webUrl.getDomain())) {
            LocalDateTime lastFetchTime = lastFetchTimeByTopLevelDomain.get(webUrl.getDomain());
            LocalDateTime now = LocalDateTime.now();
            long timeElapsed = ChronoUnit.MILLIS.between(lastFetchTime, now);
            if (timeElapsed < config.getPolitenessDelay()) {
                long delay = config.getPolitenessDelay() - timeElapsed;
                Thread.sleep(delay);
            }
        }
        lastFetchTimeByTopLevelDomain.put(webUrl.getDomain(), LocalDateTime.now());
    }

    @Override
    public synchronized void shutDown() {
        chrome.shutdown();
    }

    public static void main(String[] args) {
        HttpEntityImpl httpEntity = new Chrome().fetchPage("https://www.england.nhs.uk/greenernhs/wp-content/uploads/sites/51/2020/10/delivering-a-net-zero-national-health-service.pdf");
    }
}


