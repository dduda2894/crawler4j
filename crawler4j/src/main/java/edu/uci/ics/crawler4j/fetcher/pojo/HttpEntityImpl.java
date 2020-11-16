package edu.uci.ics.crawler4j.fetcher.pojo;

import org.apache.http.Header;
import org.apache.http.HttpEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class HttpEntityImpl implements HttpEntity {


    public String requestId;
    public String requestUrl;
    public String redirectUrl;
    public Header[] responseHeaders;
    public Header contentType;
    public Header contentEncoding;
    public Charset contentCharset;
    public boolean isSuccess;
    public boolean isRedirect;
    public long contentLength;
    public InputStream content;
    public int status;

    @Override
    public boolean isRepeatable() {
        return false;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public Header getContentType() {
        return contentType;
    }

    @Override
    public Header getContentEncoding() {
        return contentEncoding;
    }

    @Override
    public InputStream getContent() throws IOException, UnsupportedOperationException {
        return content;
    }

    @Override
    public void writeTo(OutputStream outStream) throws IOException {
    }

    @Override
    public boolean isStreaming() {
        return false;
    }

    @Override
    public void consumeContent() throws IOException {
    }
}