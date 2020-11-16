package edu.uci.ics.crawler4j.chrome;

import com.github.kklisura.cdt.launch.ChromeLauncher;
import com.github.kklisura.cdt.protocol.commands.Emulation;
import com.github.kklisura.cdt.protocol.commands.Network;
import com.github.kklisura.cdt.protocol.commands.Page;
import com.github.kklisura.cdt.protocol.commands.Security;
import com.github.kklisura.cdt.protocol.events.network.*;
import com.github.kklisura.cdt.protocol.types.network.ResponseBody;
import com.github.kklisura.cdt.protocol.types.page.SetDownloadBehaviorBehavior;
import com.github.kklisura.cdt.services.ChromeDevToolsService;
import com.github.kklisura.cdt.services.ChromeService;
import com.github.kklisura.cdt.services.types.ChromeTab;
import edu.uci.ics.crawler4j.chrome.exceptions.FailedToLaunchChromeException;
import edu.uci.ics.crawler4j.fetcher.pojo.HeaderImpl;
import edu.uci.ics.crawler4j.fetcher.pojo.HttpEntityImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class Chrome {


    public static void main(String[] args) {
    }

    protected static final Logger LOG = LoggerFactory.getLogger(Chrome.class);

    public ChromeLauncher chromeLauncher = new ChromeLauncher();
    private ChromeService chromeService = chromeLauncher.launch(true);
    private final Object mutex = new Object();
    private int chromeStarts = 0;
    private List<String> greyListedMimeTypes = new ArrayList<String>();

    public Chrome() {
//        System.setProperty(
//                DefaultWebSocketContainerFactory.WEBSOCKET_INCOMING_BUFFER_PROPERTY,
//                Long.toString((long) DefaultWebSocketContainerFactory.MB * 24));
    }

    public void launchCrome() {
        synchronized (mutex) {
            if (!chromeLauncher.isAlive()) {
                chromeLauncher = new ChromeLauncher();
                chromeService = chromeLauncher.launch(true);
                if (!chromeLauncher.isAlive()) {
                    chromeStarts++;
                }
            }
        }
    }

    public HttpEntityImpl fetchPage(String webUrl) {
        {
            launchCrome();
            if (!chromeLauncher.isAlive()) {
                LOG.error("Chrome crashed or would not start");
                throw new FailedToLaunchChromeException(String.format("Failed to launch chrome on %s after launching/restarting chrome %s times", System.getenv("HOSTNAME"), chromeStarts));
            }
            HttpEntityImpl httpEntity = new HttpEntityImpl();
            Page page = null;
            ChromeDevToolsService devToolsService = null;
            try {
                CountDownLatch countDownLatch = new CountDownLatch(1);
                ChromeTab tab = chromeService.createTab();
                devToolsService = chromeService.createDevToolsService(tab);
                page = devToolsService.getPage();
                Network network = devToolsService.getNetwork();
                network.setCacheDisabled(true);
                Emulation emulation = devToolsService.getEmulation();
                emulation.setUserAgentOverride("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.111 Safari/537.36");
                Security security = devToolsService.getSecurity();
                security.enable();
                security.setIgnoreCertificateErrors(true);
                network.enable();
                AtomicInteger atomicInteger = new AtomicInteger(1);
                addOnRequestWillBeSentExtraInfoEvent(network);
                addOnRequestWillBeSentEvent(network, httpEntity, atomicInteger);
                addOnLoadFailedEvent(network, httpEntity, countDownLatch);
                addOnLoadFinishedEvent(network, httpEntity, countDownLatch);
                addOnResponseReceiveEvent(network, httpEntity, countDownLatch, page);
                addOnResponseReceivedExtraInfoEvent(network, httpEntity);
                page.setDownloadBehavior(SetDownloadBehaviorBehavior.DENY);
                page.enable();
                page.navigate(webUrl);
                countDownLatch.await(120, TimeUnit.SECONDS);
                if (!httpEntity.isRedirect) {
                    if (httpEntity.contentType.getValue().trim().toLowerCase().contains("application/pdf") ||
                        httpEntity.contentType.getValue().trim().toLowerCase().contains("application/octet-stream")) {
                        //TODO replace with chromeDevTool Page protocol downloadProgressEvent
                        sendRequestWithJsoup(webUrl, httpEntity);
                    } else {
                        sendRequestWithChrome(network, httpEntity);
                    }
                }
                page.close();
                devToolsService.close();
            } catch (Exception e) {
                LOG.error(e.getLocalizedMessage());
                try {
                    page.close();
                } catch (Exception e1) {
                    LOG.error(e1.getLocalizedMessage());
                }
                try {
                    if (!devToolsService.isClosed()) {
                        devToolsService.close();
                    }
                } catch (Exception e3) {
                    LOG.error(e3.getLocalizedMessage());
                }
            }
            return httpEntity;
        }
    }

    public void sendRequestWithChrome(Network network, HttpEntityImpl httpEntity) {
        try {
            ResponseBody responseBody = network.getResponseBody(httpEntity.requestId);
            InputStream content = new ByteArrayInputStream(responseBody.getBody().getBytes());
            httpEntity.content = content;
        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage() + "\n " + httpEntity.requestUrl);
        }
    }

    public void sendRequestWithJsoup(String url, HttpEntityImpl httpEntity) {
        try {
            //TODO set max body size and user agent as in in crawlConfig
            Connection.Response execute = Jsoup.connect(url).ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(Integer.valueOf(Long.valueOf(Duration.ofMinutes(2).toMillis()).toString()))
                .maxBodySize(25_000_000)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.111 Safari/537.36")
                .followRedirects(false).execute();
            String charset = execute.charset();
            if (charset != null) {
                httpEntity.contentCharset = Charset.forName(charset);
            }
            httpEntity.content = execute.bodyStream();
            httpEntity.isSuccess = true;
        } catch (Exception e) {
            LOG.debug(e.getLocalizedMessage());
        }
    }


    private void addOnResponseReceiveEvent(Network network, HttpEntityImpl httpEntity, CountDownLatch countDownLatch, Page page) {
        network.onResponseReceived((ResponseReceived responseReceived) -> {
            if (httpEntity.requestId.equals(responseReceived.getRequestId()) &&
                httpEntity.requestUrl.equals(responseReceived.getResponse().getUrl())) {
                LOG.debug("Response received");
                httpEntity.contentType = new HeaderImpl(HttpHeaders.CONTENT_TYPE, responseReceived.getResponse().getMimeType());
                for (String greyListedMimeType : greyListedMimeTypes) {
                    if (httpEntity.contentType.getValue().toLowerCase().contains(greyListedMimeType)) {
                        LOG.info("Greylisted type {} at url {}", httpEntity.contentType.getValue(), httpEntity.requestUrl);
                        httpEntity.status = 500;
                        httpEntity.isSuccess = false;
                        countDownLatch.countDown();
                        page.stopLoading();
                        return;
                    }
                }
                Integer status = responseReceived.getResponse().getStatus();
                httpEntity.status = status;
                Map<String, Object> responseHeaders = responseReceived.getResponse().getHeaders();
                Map<String, Object> responseHeadersLowercase = new HashMap<>();
                responseHeaders.forEach((k, v) -> {
                    responseHeadersLowercase.put(k.toLowerCase(), v);
                });
                Object contentType = responseHeadersLowercase.get("content-type");
                if (contentType != null) {
                    if (contentType.toString().toLowerCase().contains("charset")) {
                        String[] contentTypeStringSplit = contentType.toString().split(";");
                        for (String partialContentTypeString : contentTypeStringSplit) {
                            if (partialContentTypeString.trim().toLowerCase().contains("charset")) {
                                String charset = StringUtils.substringAfter(partialContentTypeString.trim().toLowerCase(), "=".toUpperCase());
                                if (charset != null) {
                                    httpEntity.contentCharset = Charset.forName(charset.toString());
                                }
                            }
                        }
                    }
                }
                Object contentLength = responseHeadersLowercase.get(HttpHeaders.CONTENT_LENGTH.toLowerCase());
                if (contentLength != null) {
                    httpEntity.contentLength = Long.valueOf(contentLength.toString());
                }
                Object contentEncoding = responseHeadersLowercase.get(HttpHeaders.CONTENT_ENCODING.toLowerCase());
                if (contentEncoding != null) {
                    httpEntity.contentEncoding = new HeaderImpl(HttpHeaders.CONTENT_ENCODING, contentEncoding.toString());
                }
                int headerSize = responseReceived.getResponse().getHeaders().size();
                Header[] headers = new HeaderImpl[headerSize + 1];
                AtomicInteger counter = new AtomicInteger(0);
                responseReceived.getResponse().getHeaders().forEach((k, v) -> {
                        HeaderImpl header = new HeaderImpl(k, v.toString());
                        headers[counter.intValue()] = header;
                        counter.addAndGet(1);
                    }
                );
                httpEntity.responseHeaders = headers;
                httpEntity.requestUrl = responseReceived.getResponse().getUrl();
                LOG.debug(responseReceived.getResponse().getStatus().toString());
                LOG.debug(responseReceived.getResponse().getUrl());
                LOG.debug(responseReceived.getType().name());
                LOG.debug(responseReceived.getRequestId());
                LOG.debug(responseReceived.getResponse().getHeaders().toString());
                LOG.debug("Response received end");
            }
        });
    }

    public void addOnResponseReceivedExtraInfoEvent(Network network, HttpEntityImpl httpEntity) {
        network.onResponseReceivedExtraInfo((ResponseReceivedExtraInfo responseReceived) -> {
            if (httpEntity.requestId.equals(responseReceived.getRequestId()) &&
                !httpEntity.isRedirect &&
                !httpEntity.isSuccess) {
                LOG.debug("Extra info received");
                //TODO(Need to address html and javascript redirects)
                if (responseReceived.getHeaders().containsKey("Location")) {
                    LOG.debug("Redirect: " + responseReceived.getHeaders().get("Location").toString());
                    httpEntity.isRedirect = true;
                    httpEntity.redirectUrl = responseReceived.getHeaders().get("Location").toString();
                } else if (responseReceived.getHeaders().containsKey("location")) {
                    LOG.debug("Redirect: " + responseReceived.getHeaders().get("location").toString());
                    httpEntity.isRedirect = true;
                    httpEntity.redirectUrl = responseReceived.getHeaders().get("location").toString();
                    LOG.debug("Extra info received end");
                }
            }
        });
    }

    private void addOnLoadFinishedEvent(Network network, HttpEntityImpl httpEntity, CountDownLatch countDownLatch) {
        network.onLoadingFinished((LoadingFinished loadingFinished) -> {
            if (httpEntity.requestId.equals(loadingFinished.getRequestId())) {
                LOG.debug("Success Loaded request id " + loadingFinished.getRequestId());
                if (countDownLatch.getCount() == 1) {
                    httpEntity.isSuccess = true;
                    countDownLatch.countDown();
                }
            }
        });
    }

    private void addOnLoadFailedEvent(Network network, HttpEntityImpl httpEntity, CountDownLatch countDownLatch) {
        network.onLoadingFailed((LoadingFailed loadingFailed) -> {
            if (httpEntity.requestId.equals(loadingFailed.getRequestId())) {
                LOG.debug("error text " + loadingFailed.getErrorText());
                LOG.debug("is cancelled " + loadingFailed.getCanceled());
                LOG.debug("type text " + loadingFailed.getType());
                httpEntity.isSuccess = false;
                countDownLatch.countDown();
            }
        });
    }

    public void addOnRequestWillBeSentEvent(Network network, HttpEntityImpl httpEntity, AtomicInteger atomicInteger) {
        network.onRequestWillBeSent((RequestWillBeSent requestWillBeSent) -> {
//                        LOG.info(requestWillBeSent.getRequest().getHeaders());
//                        LOG.info(requestWillBeSent.getType());
            if (atomicInteger.get() == 1) {
                LOG.debug("Request will be send: Document URL " + requestWillBeSent.getDocumentURL());
                LOG.debug("Request will be send: ID URL " + requestWillBeSent.getRequestId());
                atomicInteger.incrementAndGet();
                httpEntity.requestId = requestWillBeSent.getRequestId().trim();
                httpEntity.requestUrl = requestWillBeSent.getDocumentURL().trim();
            }
        });
    }

    private void addOnRequestWillBeSentExtraInfoEvent(Network network) {
        network.onRequestWillBeSentExtraInfo((RequestWillBeSentExtraInfo requestWillBeSentExtraInfo) -> {
            LOG.debug("Request extra info " + requestWillBeSentExtraInfo.getHeaders().toString());
            LOG.debug("Request extra info " + requestWillBeSentExtraInfo.getRequestId());
        });
    }
}
