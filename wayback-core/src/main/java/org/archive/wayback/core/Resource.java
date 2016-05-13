/*
 *  This file is part of the Wayback archival access software
 *   (http://archive-access.sourceforge.net/projects/wayback/).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual
 *  contributors.
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.wayback.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.httpclient.ChunkedInputStream;

/**
 * Abstraction on top of a document stored in a WaybackCollection.
 *
 * TODO: This implementation needs some pretty drastic refactoring.. May have to wait
 * for 2.0. This should be a byte-oriented record, and allow wrapping the
 * interior byte-stream in on the more full featured HTTP libraries
 * (jetty/apache-http-client/w3c-http-reference).
 *
 * For now, it is a system-wide assumption that all resources are HTTP based.
 *
 * TODO: Some code downcasts Resource to its sub-classes to gain access to
 * methods only available in specific implementation.  Consider adding more methods
 * to make downcast unnecessary.  More sub-classes are expected, for encapsulating
 * revisit-original pair of Resources as single Resource, for example.
 *
 * @see org.archive.wayback.ResourceStore#retrieveResource(CaptureSearchResult)
 *
 * @author Brad Tofel
 */
public abstract class Resource extends InputStream {
	
	/**
	 * Upper-case header field name for {@code Content-Encoding}.
	 * For use with {@link #getHeader(String)}
	 */
	public static final String HTTP_CONTENT_ENCODING = "CONTENT-ENCODING";

	private InputStream is;

    public abstract void close() throws IOException;
	/**
	 * Assumes an HTTP resource - return the HTTP response code
	 * @return the HTTP response code from the HTTP message
	 */
	public abstract int getStatusCode();
	/**
	 * @return the size in bytes of the record payload, including HTTP header
	 */
	public abstract long getRecordLength();
	/**
	 * Assumes an HTTP response - return the HTTP headers, not including the
	 * HTTP Message header
	 * @return key-value Map of HTTP headers
	 */
	public abstract Map<String,String> getHttpHeaders();

	// URL-Agnostic Revisit Support

	/**
	 * return {@code WARC-Refer-To-Target-URI} WARC record header value or
	 * equivalent.
	 * Default implementation returns {@code null}.
	 * @return header value (URI)
	 */
	public String getRefersToTargetURI() {
		return null;
	}

	/**
	 * return {@code WARC-Refers-To-Date} WARC record header value or
	 * equivalent.
	 * Default implementation returns {@code null}
	 * @return 14-digit timestamp string ({@code yyyyMMddHHmmss})
	 */
	public String getRefersToDate() {
		return null;
	}

	public void parseHeaders() throws IOException {
		//Implemented in warc/arc reader
	}

	public String getHeader(String headerName) {
		Map<String, String> httpHeaders = getHttpHeaders();

		if (httpHeaders == null) {
			return null;
		}

		Iterator<String> keys = httpHeaders.keySet().iterator();

		String headerUp = headerName.toUpperCase();

		while (keys.hasNext()) {
			String key = keys.next();

			if (key.toUpperCase().equals(headerUp)) {
				return httpHeaders.get(key);
			}
		}

		return null;
	}

	/**
	 * Return <i>content-encoding</i> of the payload.
	 * <p>
	 * Currently meaningful for HTTP response resource only,
	 * and returns the value of HTTP {@code Content-Encoding}.
	 * Composite resource would override this method to return
	 * a value from appropriate member Resource.
	 * </p>
	 * @return content-encoding header field value
	 */
	public String getContentEncoding() {
		return getHeader(HTTP_CONTENT_ENCODING);
	}

	private void validate() throws IOException {
		if(is == null) {
			throw new IOException("No InputStream");
		}
	}

	protected void setInputStream(InputStream is) {
		if(is.markSupported()) {
			this.is = is;
		} else {
			this.is = new BufferedInputStream(is);
		}
	}

	/**
	 * indicate that there is a {@code Transfer-Encoding: chunked} header, so the input
	 *   data should be dechunked as it is read. This method actually peeks
	 *   ahead to verify that there is a hex-encoded chunk length before
	 *   assuming the data is chunked.
	 * @throws IOException for usual reasons
	 */
	public void setChunkedEncoding() throws IOException {
		validate();
		// peek ahead and make sure we have a line with hex numbers:
		int max = 50;
		is.mark(max + 2);
		int cur = 0;
		int hexFound = 0;
		boolean isChunked = false;
		while (cur < max) {
			int nextC = is.read();
			// allow CRLF and plain ole LF:
			if ((nextC == 13) || (nextC == 10)) {
				// must have read at least 1 hex char:
				if (hexFound > 0) {
					if (nextC == 10) {
						isChunked = true;
						break;
					}
					nextC = is.read();
					if (nextC == 10) {
						isChunked = true;
						break;
					}
				}
				// keep looking to allow some blank lines.
			} else {
				// better be a hex character:
				if (isHex(nextC)) {
					hexFound++;
				} else if (nextC != ' ') {
					// allow whitespace before or after chunk...
					// not a hex digit: not a chunked stream.
					break;
				}
			}
			cur++;
		}
		is.reset();
		if (isChunked) {
			setInputStream(new ChunkedInputStream(is));
		}
	}

	private boolean isHex(int c) {
		if ((c >= '0') && (c <= '9')) {
			return true;
		}
		if ((c >= 'a') && (c <= 'f')) {
			return true;
		}
		if ((c >= 'A') && (c <= 'F')) {
			return true;
		}
		return false;
	}

	public int available() throws IOException {
		validate();
		return is.available();
	}

	public void mark(int readlimit) {
		if (is != null) {
			is.mark(readlimit);
		}
	}

	public boolean markSupported() {
		if (is == null) {
			return false;
		}
		return is.markSupported();
	}

	public int read() throws IOException {
		validate();
		return is.read();
	}

	public int read(byte[] b, int off, int len) throws IOException {
		validate();
		return is.read(b, off, len);
	}

	public int read(byte[] b) throws IOException {
		validate();
		return is.read(b);
	}

	public void reset() throws IOException {
		validate();
		is.reset();
	}

	public long skip(long n) throws IOException {
		validate();
		return is.skip(n);
	}
}
