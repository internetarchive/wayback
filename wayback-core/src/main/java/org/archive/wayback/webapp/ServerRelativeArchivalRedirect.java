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
package org.archive.wayback.webapp;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.url.UsableURI;
import org.archive.url.UsableURIFactory;
import org.archive.util.ArchiveUtils;
import org.archive.wayback.util.Timestamp;
import org.archive.wayback.util.url.UrlOperations;
import org.archive.wayback.util.webapp.AbstractRequestHandler;
import org.archive.wayback.util.webapp.RequestHandler;

/**
 * {@code ServerRelativeArchivalRedirect} is a {@link RequestHandler}
 * that redirects <i>leaked</i> server-relative URL back to replay request
 * URL.
 * <p>For example, assuming {@code Referer} is
 * {@code http://web.archive.org/web/20010203040506/http://example.com/index.html},
 * it redirects request {@code http://web.archive.org/js/foo.js}
 * to {@code http://web.archive.org/web/20010203040506/http://example.com/js/foo.js}.</p>
 * <p>It is typically set up as catch-all {@code RequestHandler}</p>
 * @author brad
 */
public class ServerRelativeArchivalRedirect extends AbstractRequestHandler {
	private static final Logger LOGGER = Logger
		.getLogger(ServerRelativeArchivalRedirect.class.getName());

	boolean useCollection = false;
	private String matchHost = null;
	private int matchPort = -1;
	private String replayPrefix;

	private String handleRequestWithCollection(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws ServletException,
			IOException {
		// TODO: check if this works with non-empty context path.
		// I believe requestURI starts with context path. So non-empty
		// context path will break collection and timestamp extraction
		// code below. It'd be desirable for this class to get a pattern
		// of replay URL from somewhere - replayPreifx?

		// hope that it's a server relative request, with a valid referrer:
		String referer = httpRequest.getHeader("Referer");
		if (referer == null) return null;
		
		final UsableURI refuri = UsableURIFactory.getInstance(referer);

		// Check that the Referer is our current wayback path
		// before attempting to use referer as base archival url

		if ((matchHost != null && !matchHost.equals(refuri.getHost())) ||
				(matchPort != -1 && refuri.getPort() != -1 && matchPort != refuri
				.getPort())) {
			LOGGER.info("Server-Relative-Redirect: Skipping, Referer " +
					refuri.getHost() + ":" + refuri.getPort() +
					" not from matching wayback host:port\t");
			return null;
		}

		String path = refuri.getPath();
		int secondSlash = path.indexOf('/', 1);
		if (secondSlash == -1) return null;
		
		String collection = path.substring(0, secondSlash);
		collection = modifyCollection(collection); 

		//determine if collection is not numeric. if not numeric, get the collection from the cookie (if it exists)
		if (!collection.isEmpty() && collection.length() > 1 &&
				!Character.isDigit(collection.charAt(1))) {
			
			if (httpRequest.getCookies() != null) {
				for (Cookie cookie: httpRequest.getCookies()) {
					if ("wayback.collectionid".equals(cookie.getName())) {
						collection = "/" + cookie.getValue();
						break;
					}
				}
			}
			
		}
		
		String remainder = path.substring(secondSlash + 1);
		int thirdSlash = remainder.indexOf('/');
		if (thirdSlash == -1) return null;

		String datespec = remainder.substring(0, thirdSlash);
		if (!datespec.isEmpty() &&
				!Character.isDigit(datespec.charAt(0))) {
			
			datespec = null;
			
			if (httpRequest.getCookies() != null) {
				for (Cookie cookie: httpRequest.getCookies()) {
					if ("wayback.timestamp".equals(cookie.getName())) {
						datespec = cookie.getValue();
						break;
					}
				}
				
			}
		}

		String url = remainder.substring(thirdSlash + 1);

		boolean urlHasBeenFixed = false;
		
		if (httpRequest.getCookies() != null) {
			for (Cookie cookie : httpRequest.getCookies()) {
				if ("wayback.archivalhost".equals(cookie.getName())) {
					url = URLDecoder.decode(cookie.getValue(), "UTF-8");
					urlHasBeenFixed = true;
					break;
				}
			}
		}

		if (!urlHasBeenFixed) {
			url = UrlOperations.fixupScheme(url);
			url = ArchiveUtils.addImpliedHttpIfNecessary(url);
		}

		String thisPath = httpRequest.getRequestURI();
		String queryString = httpRequest.getQueryString();
		if (queryString != null) {
			thisPath += "?" + queryString;
		}
		String resolved = UrlOperations.resolveUrl(url, thisPath);

		String contextPath = httpRequest.getContextPath();
		StringBuilder sb = new StringBuilder(refuri.getScheme());
		sb.append("://");
		sb.append(refuri.getAuthority());
		sb.append(contextPath);
		sb.append(collection);
		sb.append("/");
		if (datespec != null) {
			sb.append(datespec);
			sb.append("/");
		}
		sb.append(resolved);
		
		return sb.toString();
	}

	/**
	 * modify collection if necessary.
	 * <p>default implementation simply return {@code collection}.</p>
	 * @param collection (the first path component of Referer URL.)
	 * note value has leading slash, which must be retained.
	 * @return possibly modified collection.
	 */
	protected String modifyCollection(String collection) {
		return collection;
	}

	private String handleRequestWithoutCollection(
			HttpServletRequest httpRequest, HttpServletResponse httpResponse)
			throws ServletException, IOException {

		// hope that it's a server relative request, with a valid referrer:
		String referer = httpRequest.getHeader("Referer");
		if (referer == null) return null;
		
		LOGGER.fine("referer:" + referer);
		UsableURI uri = UsableURIFactory.getInstance(referer);
		String path = uri.getPath();

		String remainder = path.substring(1);
		int thirdSlash = remainder.indexOf('/');
		LOGGER.fine("referer:(" + referer + ") remain(" + remainder +
			") 3rd(" + thirdSlash + ")");
		if (thirdSlash == -1) return null;

		String datespec = remainder.substring(0, thirdSlash);
		String url = remainder.substring(thirdSlash + 1);
		url = UrlOperations.fixupScheme(url);
		url = ArchiveUtils.addImpliedHttpIfNecessary(url);
		String thisPath = httpRequest.getRequestURI();
		String queryString = httpRequest.getQueryString();
		if (queryString != null) {
			thisPath += "?" + queryString;
		}

		String resolved = UrlOperations.resolveUrl(url, thisPath);
		String contextPath = httpRequest.getContextPath();
		String finalUrl = uri.getScheme() + "://" + uri.getAuthority() +
				contextPath + "/" + datespec + "/" + resolved;
		// cross your fingers!!!
		LOGGER.info("Server-Relative-Redirect:\t" + referer + "\t" +
				thisPath + "\t" + finalUrl);
		return finalUrl;
	}

	@Override
	public boolean handleRequest(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws ServletException,
			IOException {
		if (matchHost != null) {
			if (!matchHost.equals(httpRequest.getServerName())) {
				LOGGER.fine("Wrong host for ServerRelativeRed(" +
						httpRequest.getServerName() + ")");
				return false;
			}
		}
		if (matchPort != -1) {
			if (matchPort != httpRequest.getLocalPort()) {
				LOGGER.fine("Wrong port for ServerRealtiveRed(" +
						httpRequest.getServerName() + ")(" +
						httpRequest.getLocalPort() + ") :" +
						httpRequest.getRequestURI());
				return false;
			}
		}
		String replayUrl = (useCollection ?
				handleRequestWithCollection(httpRequest, httpResponse) :
					handleRequestWithoutCollection(httpRequest, httpResponse));
		if (replayUrl == null && replayPrefix != null) {
			String thisPath = httpRequest.getRequestURI();
			String queryString = httpRequest.getQueryString();
			if (queryString != null) {
				thisPath += "?" + queryString;
			}
			// TODO: rethink this fallback, for now adding https support as
			// well
			if (thisPath.startsWith("/http://") ||
					thisPath.startsWith("/https://")) {
				// assume a replay request:
				StringBuilder sb = new StringBuilder(thisPath.length() +
					replayPrefix.length() + 16);
				sb.append(replayPrefix);
				sb.append(Timestamp.currentTimestamp().getDateStr());
				sb.append(thisPath);
				replayUrl = sb.toString();
			}
		}
		if (replayUrl != null) {
			// Gotta make sure this is properly cached, or
			// weird things happen:
			httpResponse.addHeader("Vary", "Referer");
			httpResponse.sendRedirect(replayUrl);
			return true;
		}
		return false;
	}

	/**
	 * @return the useCollection
	 */
	public boolean isUseCollection() {
		return useCollection;
	}

	/**
	 * whether replay URL has <i>collection</i> part.
	 * <p>set this to {@code true} if replay URL has <i>collection</i>
	 * part, path component between context path and timestamp (although
	 * it's called <i>collection</i> based on the common usage of this part,
	 * there's no need to have particular semantics.)</p>
	 * <p>collection part will be passed to {@link #modifyCollection(String)}
	 * before constructing final replay URL to redirect to.<p>
	 * @param useCollection the useCollection to set
	 */
	public void setUseCollection(boolean useCollection) {
		this.useCollection = useCollection;
	}

	/**
	 * @return the matchHost
	 */
	public String getMatchHost() {
		return matchHost;
	}

	/**
	 * optional host name {@code Referer} URL should match.
	 * @param matchHost the matchHost to set
	 */
	public void setMatchHost(String matchHost) {
		this.matchHost = matchHost;
	}

	/**
	 * @return the matchPort
	 */
	public int getMatchPort() {
		return matchPort;
	}

	/**
	 * optional port number {@code Referer} URL should match.
	 * @param matchPort the matchPort to set
	 */
	public void setMatchPort(int matchPort) {
		this.matchPort = matchPort;
	}

	/**
	 * @return the replayPrefix
	 */
	public String getReplayPrefix() {
		return replayPrefix;
	}

	/**
	 * optional replay URL prefix used by fallback method.
	 * @param replayPrefix the replayPrefix to set
	 */
	public void setReplayPrefix(String replayPrefix) {
		this.replayPrefix = replayPrefix;
	}
}
