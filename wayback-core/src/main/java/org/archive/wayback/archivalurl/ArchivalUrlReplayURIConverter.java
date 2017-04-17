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
package org.archive.wayback.archivalurl;

import java.net.URISyntaxException;
import java.util.logging.Logger;

import org.archive.url.HandyURL;
import org.archive.url.URLParser;
import org.archive.wayback.ReplayURIConverter;
import org.archive.wayback.memento.MementoUtils;
import org.archive.wayback.replay.ReplayContext;
import org.archive.wayback.replay.ReplayRewriteContext;
import org.archive.wayback.replay.ReplayURLTransformer;
import org.archive.wayback.util.url.UrlOperations;
import org.archive.wayback.webapp.AccessPoint;
import org.archive.wayback.webapp.AccessPointAware;

/**
 * ResultURIConverter for Archival-URL replay scheme.
 * This class also provides default URL-rewriting for Archival-URL. It keeps the URL style
 * of the original URL whenever possible, so as not to break JavaScript code expecting
 * particular style of URL.  For this to work, configure {@code replayURIPrefix} with full
 * URL (including protocol and hostname).
 * For backward compatibility, it complements relative {@code replayURIPrefix} with
 * {@link MementoUtils#getMementoPrefix(AccessPoint)} if configured.
 * <p>
 * Replay URL is <i>replayURIPrefix</i>+<i>datespec</i>[<i>context</i>]+{@code "/"}+<i>URL</i>.
 * </p>
 */
public class ArchivalUrlReplayURIConverter implements ReplayURIConverter, ReplayURLTransformer,
		AccessPointAware /*, ContextResultURIConverterFactory*/ {
	private static final Logger LOGGER = Logger
		.getLogger(ArchivalUrlReplayURIConverter.class.getName());

	private static final String MAILTO_PREFIX = "mailto:";
	public static final String ANCHOR_PREFIX = "#";

	/**
	 * configuration name for URL prefix of replay server
	 */
	private String replayURIPrefix = null;

	protected static HandyURL parseURL(String url) throws URISyntaxException {
		if (url == null || url.isEmpty()) return null;
		// URLParser.parse() interprets "/web/" as "http://web/".
		// To workaround this behavior, bypass URLParser.parse() if url
		// starts with "/" (and not "//").
		if (url.startsWith("/") && !url.startsWith("//")) {
			return new HandyURL("http", null, null, null, -1, url, null, null);
		}
		return URLParser.parse(url);
	}

	protected final String getStylePrefix(URLStyle style) {
		if (replayURIPrefix == null || replayURIPrefix.isEmpty())
			return replayURIPrefix;
		if (style == URLStyle.ABSOLUTE)
			return getReplayURIPrefix();

		HandyURL url = null;
		try {
			url = parseURL(replayURIPrefix);
		} catch (URISyntaxException ex) {
			// malformed replayURIPrefix is ignored. log?
			return null;
		}
		if (style == URLStyle.SERVER_RELATIVE) {
			return url.getPath();
		} else if (style == URLStyle.PROTOCOL_RELATIVE) {
			// if replayURIPrefix has no host part, cannot do PROTOCOL_RELATIVE.
			if (url.getHost() == null)
				return url.getPath();
			if (url.getPort() > 0) {
				return "//" + url.getHost() + ":" + url.getPort() + url.getPath();
			} else {
				return "//" + url.getHost() + url.getPath();
			}
		} else {
			// Assuming style == FULL
			return getReplayURIPrefix();
		}
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.ReplayURIConverter#makeReplayURI(java.lang.String, java.lang.String, java.lang.String, org.archive.wayback.ReplayURIConverter.URLStyle)
	 */
	@Override
	public String makeReplayURI(String datespec, String url, String flags,
			URLStyle urlStyle) {
		if (flags == null) flags = "";

		String prefix = getStylePrefix(urlStyle);

		if (prefix == null) {
			// Assuming replay URL starts with datespec. Need to start
			// with "/", or link gets screwed.
			return "/" + datespec + flags + "/" +
					UrlOperations.stripDefaultPortFromUrl(url);
		}

		if (url.startsWith(prefix))
			return url;

		// Assuming replayURIPrefix ends with "/"
		return prefix + datespec + flags + "/" +
				UrlOperations.stripDefaultPortFromUrl(url);
	}

	@Override
	public ReplayURLTransformer getURLTransformer() {
		return this;
	}

	public static boolean isProtocolRelative(String url) {
		if (url.startsWith("//")) return true;
		// for URLs in query parameters (do we really want this?)
		if (url.startsWith("%2F%2F")) return true;
		if (url.startsWith("%2f%2f")) return true;
		// <-- TODO: delete (see JS escape support in JSStringTransformer)
		if (url.startsWith("\\/\\/")) return true;
		if (url.startsWith("\\\\/\\\\/")) return true;
		if (url.startsWith("\\\\u00252F\\\\u00252F")) return true;
		// -->
		return false;
	}

	public static boolean isAbsoluteURL(String url) {
		if (url.startsWith("http")) {
			String s = url.substring(4);
			if (s.startsWith("s"))
				s = s.substring(1);
			if (s.startsWith(":"))
				return isProtocolRelative(s.substring(1));
			if (s.startsWith("%3A") || s.startsWith("%3a"))
				return isProtocolRelative(s.substring(3));	
			// <-- TODO: delete (see JS escape support in JSStringTransformer)
			if (s.startsWith("\\\\u00253A"))
				return isProtocolRelative(s.substring(9));
			// -->
		}
		return false;
	}

	@Override
	public String transform(ReplayContext replayContext, String url,
			String flags) {
		// if we get an empty string, just return it:
		if (url.length() == 0) {
			return url;
		}

		if (url.startsWith(UrlOperations.JAVASCRIPT_PREFIX) ||
				url.startsWith(MAILTO_PREFIX) ||
				url.startsWith(UrlOperations.DATA_PREFIX) ||
				url.startsWith(ANCHOR_PREFIX)) {
			return url;
		}

		// don't rewrite full and path-relative URLs. For
		// https://webarchive.jira.com/browse/ARI-3985.
		// Keep the style of URL (ARI-4033).
		String trimmedUrl = url.trim();
		URLStyle urlStyle;
		if (isAbsoluteURL(trimmedUrl))
			urlStyle = URLStyle.ABSOLUTE;
		else if (isProtocolRelative(trimmedUrl))
			urlStyle = URLStyle.PROTOCOL_RELATIVE;
		else if (trimmedUrl.startsWith("/"))
			urlStyle = URLStyle.SERVER_RELATIVE;
		else if (trimmedUrl.startsWith("../"))
			// if path-relative looking backward,
			// rewrite as server relative so we
			// don't inadvertently erase the
			// hostname from an archival url
			urlStyle = URLStyle.SERVER_RELATIVE;
		else
			return url;

		// first make url into absolute, taking BASE into account.
		// (this also removes escaping: ex. "https:\/\/" -> "https://")
		// (now url shall be escaping-free; See JSStringTransformer)
		String absurl = null;
		try {
			absurl = replayContext.resolve(url);
		} catch (URISyntaxException ex) {
			return url;
		}
		// if flags is a special value identifying HTTP header field
		// context, replace flags with request context flags. This way,
		// rewritten Location header field will inherit context flags
		// from the request.
		if (ReplayRewriteContext.HEADER_CONTEXT.equals(flags)) {
			flags = replayContext.getContextFlags();
		}
		// IMPORTANT: call makeReplayURI through replayContext.
		// This is to allow for decorating ReplayURIConverter with
		// custom per-request behavior. See AccessPoint#decorateURIConverter.
		return replayContext.makeReplayURI(absurl, flags, urlStyle);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.archive.wayback.ResultURIConverter#makeReplayURI(java.lang.String,
	 * java.lang.String)
	 */
	public String makeReplayURI(String datespec, String url) {
		return makeReplayURI(datespec, url, null, URLStyle.ABSOLUTE);
	}

	/**
	 * @param replayURIPrefix the replayURIPrefix to set
	 */
	public void setReplayURIPrefix(String replayURIPrefix) {
		this.replayURIPrefix = replayURIPrefix;
	}

	/**
	 * @return the replayURIPrefix
	 */
	public String getReplayURIPrefix() {
		return replayURIPrefix;
	}

	public void setAccessPoint(AccessPoint accessPoint) {
		if (replayURIPrefix == null) {
			String apReplayPrefix = accessPoint.getReplayPrefix();
			LOGGER.warning(
				"No replayURIPrefix configured - using accessPoint:" +
						apReplayPrefix);
			replayURIPrefix = apReplayPrefix;
		} else {
			LOGGER.info("replayURIPrefix is configured: " + replayURIPrefix);
		}
		// Since it is a common practice to set server-relative path to
		// replayURIPrefix and configure "aggregationPrefix" property with
		// protocol and hostname part necessary for Memento Link generation,
		// we need to complement protocol and hostname part from the property
		// if replayURIPrefix is server-relative.
		String mementoPrefix = MementoUtils.getMementoPrefix(accessPoint);
		if (mementoPrefix != null && !mementoPrefix.isEmpty()) {
			String resolved = UrlOperations.resolveUrl(mementoPrefix,
				replayURIPrefix);
			if (!replayURIPrefix.equals(resolved)) {
				replayURIPrefix = resolved;
				LOGGER.warning("Resolved replayURIPrefix with mementoPrefix: " +
						replayURIPrefix +
						" for backward-compatibility. Please configure replayURIPrefix with absolute URL");
			}
		} else if (replayURIPrefix.startsWith("//")) {
			// replayURIPrefix is protocol relative, and no mementpPrefix.
			// It was combined with custom code building absolute URL with
			// the protocol of incoming request.
			replayURIPrefix = "http:" + replayURIPrefix;
			LOGGER.warning("Prepended \"http:\" to replayURIPrefix for backward-compatibility. " +
					"Please configure replayURLPrefix with absolute URL");
		}
	}
}
