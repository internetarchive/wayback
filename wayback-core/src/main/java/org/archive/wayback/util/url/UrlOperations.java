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
package org.archive.wayback.util.url;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.URIException;
import org.archive.url.UsableURI;
import org.archive.url.UsableURIFactory;
import org.archive.wayback.archivalurl.ArchivalUrl;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.webapp.AccessPoint;

/**
 * Class containing common static URL methods. Primarily resolveUrl() and 
 * the (currently) unused isAuthority().
 *
 * @author brad
 * @version $Date$, $Revision$
 */
public class UrlOperations {
	private static final Logger LOGGER = Logger.getLogger(
			UrlOperations.class.getName());
	
	/**
	 * ARC/WARC specific DNS resolution record.
	 */
	public final static String DNS_SCHEME = "dns:";
	/**
	 * HTTP
	 */
	public final static String HTTP_SCHEME = "http://";
	/**
	 * HTTPS
	 */
	public final static String HTTPS_SCHEME = "https://";
	/**
	 * FTP
	 */
	public final static String FTP_SCHEME = "ftp://";
	/**
	 * MMS
	 */
	public final static String MMS_SCHEME = "mms://";
	/**
	 * RTSP
	 */
	public final static String RTSP_SCHEME = "rtsp://";
	
	/**
	 * Default scheme to assume if unspecified. No context implied...
	 */
	public final static String DEFAULT_SCHEME = HTTP_SCHEME;	
	
	/**
	 * go brewster
	 */
	public final static String WAIS_SCHEME = "wais://";
	
	/**
	 * array of static Strings for all "known" schemes
	 */
	public final static String ALL_SCHEMES[] = { 
		HTTP_SCHEME,
		HTTPS_SCHEME,
		FTP_SCHEME,
		MMS_SCHEME,
		RTSP_SCHEME,
		WAIS_SCHEME
	};
	
	
	/**
	 * character separating host from port within a URL authority
	 */
	public final static char PORT_SEPARATOR = ':';
	/**
	 * character which delimits the path from the authority in a... in some 
	 * URLs.
	 */
	public final static char PATH_START = '/';
	
	private static final String ALL_TLDS = "ac|ad|ae|aero|af|ag|ai|al|am|an" +
			"|ao|aq|ar|arpa|as|asia|at|au|aw|ax|az|ba|bb|bd|be|bf|bg|bh|bi" +
			"|biz|bj|bm|bn|bo|br|bs|bt|bv|bw|by|bz|ca|cat|cc|cd|cf|cg|ch|ci" +
			"|ck|cl|cm|cn|co|com|coop|cr|cu|cv|cx|cy|cz|de|dj|dk|dm|do|dz|ec" +
			"|edu|ee|eg|er|es|et|eu|fi|fj|fk|fm|fo|fr|ga|gb|gd|ge|gf|gg|gh" +
			"|gi|gl|gm|gn|gov|gp|gq|gr|gs|gt|gu|gw|gy|hk|hm|hn|hr|ht|hu|id" +
			"|ie|il|im|in|info|int|io|iq|ir|is|it|je|jm|jo|jobs|jp|ke|kg|kh" +
			"|ki|km|kn|kp|kr|kw|ky|kz|la|lb|lc|li|lk|lr|ls|lt|lu|lv|ly|ma|mc" +
			"|md|me|mg|mh|mil|mk|ml|mm|mn|mo|mobi|mp|mq|mr|ms|mt|mu|museum" +
			"|mv|mw|mx|my|mz|na|name|nc|ne|net|nf|ng|ni|nl|no|np|nr|nu|nz" +
			"|om|org|pa|pe|pf|pg|ph|pk|pl|pm|pn|pr|pro|ps|pt|pw|py|qa|re|ro" +
			"|rs|ru|rw|sa|sb|sc|sd|se|sg|sh|si|sj|sk|sl|sm|sn|so|sr|st|su|sv" +
			"|sy|sz|tc|td|tel|tf|tg|th|tj|tk|tl|tm|tn|to|tp|tr|travel|tt|tv" +
			"|tw|tz|ua|ug|uk|us|uy|uz|va|vc|ve|vg|vi|vn|vu|wf|ws|xn--0zwm56d" +
			"|xn--11b5bs3a9aj6g|xn--80akhbyknj4f|xn--9t4b11yi5a|xn--deba0ad" +
			"|xn--g6w251d|xn--hgbk6aj7f53bba|xn--hlcj6aya9esc7a|xn--jxalpdlp" +
			"|xn--kgbechtv|xn--mgbaam7a8h|xn--mgberp4a5d4ar|xn--p1ai" +
			"|xn--wgbh1c|xn--zckzah|ye|yt|za|zm|zw";

	private static final String IP_PATTERN = "[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+";
	
    private static final Pattern AUTHORITY_REGEX =
        Pattern.compile("(([0-9a-z_.-]+)\\.(" + ALL_TLDS + "))|" +
        		"(" + IP_PATTERN + ")");

//    private static final Pattern AUTHORITY_REGEX_SIMPLE =
//        Pattern.compile("([0-9a-z_.-]++)");
    private static final Pattern HOST_REGEX_SIMPLE =
        Pattern.compile("(?:[0-9a-z_.:-]+@)?([0-9a-z_.-]++)");
    private static final Pattern USERINFO_REGEX_SIMPLE =
        Pattern.compile("^([0-9a-z_.:-]+)(?:@[0-9a-z_.-]++)");
    
    /**
     * Tests if the String argument looks like it could be a legitimate 
     * authority fragment of a URL, that is, is it an IP address, or, are the
     * characters legal in an authority, and does the string end with a legal
     * TLD.
     * 
	 * @param authString String representation of a fragment of a URL 
	 * @return boolean indicating whether urlPart might be an Authority.
	 */
	public static boolean isAuthority(String authString) {
		Matcher m = AUTHORITY_REGEX.matcher(authString);
		
		return (m != null) && m.matches();
	}
	
	/** Resolve URL, but return a minimally escaped version in case of
	 *  error
	 * @param baseUrl
	 * @param url
	 * @return
	 */
	
	public static String resolveUrl(String baseUrl, String url) {
		String resolvedUrl = resolveUrl(baseUrl, url, null);
		if (resolvedUrl == null) {
			resolvedUrl = url.replace(" ", "%20");
			resolvedUrl = resolvedUrl.replace("\r", "%0D");
		}
		return resolvedUrl;
	}
	
	/**
	 * Resolve a possibly relative url argument against a base URL.
	 * @param baseUrl the base URL against which the url should be resolved
	 * @param url the URL, possibly relative, to make absolute.
	 * @return url resolved against baseUrl, unless it is absolute already, and
	 * further transformed by whatever escaping normally takes place with a 
	 * UsableURI.
	 * In case of error, return the defaultValue
	 */
	public static String resolveUrl(String baseUrl, String url, String defaultValue) {
				
		for(final String scheme : ALL_SCHEMES) {
			if(url.startsWith(scheme)) {
				try {
					return UsableURIFactory.getInstance(url).getEscapedURI();
				} catch (URIException e) {
					LOGGER.warning(e.getLocalizedMessage() + ": " + url);
					// can't let a space exist... send back close to whatever came
					// in...
					return defaultValue;
				}
			}
		}
		UsableURI absBaseURI;
		UsableURI resolvedURI = null;
		try {
			absBaseURI = UsableURIFactory.getInstance(baseUrl);
			resolvedURI = UsableURIFactory.getInstance(absBaseURI, url);
		} catch (URIException e) {
			LOGGER.warning(e.getLocalizedMessage() + ": " + url);
			return defaultValue;
		}
		
		return resolvedURI.getEscapedURI();
	}
	
	/**
	 * Attempt to find the scheme (http://, https://, etc) from a given URL.
	 * @param url URL String to parse for a scheme.
	 * @return the scheme, including trailing "://" if known, null otherwise.
	 */
	public static String urlToScheme(final String url) {
		for(final String scheme : ALL_SCHEMES) {
			if(url.startsWith(scheme)) {
				return scheme;
			}
		}
		return null;
	}
	
	/**
	 * Return the default port for the scheme String argument, if known.
	 * @param scheme String scheme, including '://', as in, "http://", "ftp://"
	 * @return the default port for the scheme, or -1 if the scheme isn't known.
	 */
	public static int schemeToDefaultPort(final String scheme) {
		if(scheme.equals(HTTP_SCHEME)) {
			return 80;
		}
		if(scheme.equals(HTTPS_SCHEME)) {
			return 443;
		}
		if(scheme.equals(FTP_SCHEME)) {
			return 21;
		}
		if(scheme.equals(RTSP_SCHEME)) {
			return 554;
		}
		if(scheme.equals(MMS_SCHEME)) {
			return 1755;
		}
		return -1;
	}

	/**
	 * Attempt to extract the path component of a url String argument.
	 * @param url the URL which may contain a path, sans scheme.
	 * @return the path component of the URL, or "" if it contains no path.
	 */
	public static String getURLPath(String url) {
		url = stripURLScheme(url);
		int pathIdx = url.indexOf(UrlOperations.PATH_START);
		if(pathIdx == -1) {
			return "/";
		}
		return url.substring(pathIdx);
	}
	/**
	 * Attempt to extract the path component of a url String argument.
	 * @param url the URL which may contain a path, sans scheme.
	 * @return the path component of the URL, or "" if it contains no path.
	 */
	public static String stripURLScheme(String url) {
		String lcUrl = url.toLowerCase();
		for(String scheme : ALL_SCHEMES) {
			if(lcUrl.startsWith(scheme)) {
				return url.substring(scheme.length());
			}
		}
		return url;
	}	
	/**
	 * Attempt to strip default ports out of URL strings.
	 * @param url the original URL possibly including a port
	 * @return the URL sans port, if the scheme was recognized and the default
	 * port was supplied, otherwise, the original URL.
	 */
	public static String stripDefaultPortFromUrl(String url) {
		String scheme = urlToScheme(url);
		if(scheme == null) {
			return url;
		}
		int defaultPort = schemeToDefaultPort(scheme);
		if(defaultPort == -1) {
			return url;
		}

		String portStr = null;
		// is there a slash after the scheme?
		int slashIdx = url.indexOf('/', scheme.length());
		if(slashIdx == -1) {
			portStr = String.format(":%d", defaultPort);
			if(url.endsWith(portStr)) {
				return url.substring(0,url.length() - portStr.length());
			}
		}
		portStr = String.format(":%d/", defaultPort);
		int idx = url.indexOf(portStr);
		if(idx == -1) {
			return url;
		}
		// if that occurred before the first / (after the scheme) then strip it:
		if(slashIdx < idx) {
			return url;
		}
		// we want to strip out the portStr:
		StringBuilder sb = new StringBuilder(url.length());
		sb.append(url.substring(0,idx));
		sb.append(url.substring(idx + (portStr.length()-1)));
		return sb.toString();
	}

	/**
	 * @param orig String containing a URL, possibly beginning with "http:/".
	 * @return original string if orig begins with "http://", or a new String
	 * with the extra slash, if orig only had one slash.
	 * @see #fixupScheme
	 */
	public static String fixupHTTPUrlWithOneSlash(String orig) {
		if(orig.startsWith("http:/") && ! orig.startsWith(HTTP_SCHEME)) {
			// very likely the IE "you must have meant 1 slash, not 2 bug:
			StringBuilder sb = new StringBuilder(orig.length()+1);
			sb.append(HTTP_SCHEME);
			return sb.append(orig.substring(6)).toString();
		}
		return orig;
	}
		
	/**
	 * fixes up malformed scheme part.
	 * <p>currently supports fixing missing second slash for protocols
	 * {@code http}, {@code https}, {@code ftp}, {@code rtsp} and
	 * {@code mms}. For example fixing {@code http:/} to {@code https://}</p>
	 * @param url URL to be checked and fixed
	 * @return new String, or {@code url} if not fix is required.
	 * @version 1.8.1
	 */
	public static String fixupScheme(String url) {
		final String[] SCHEMES = {
			"http:/", "https:/", "ftp:/", "rtsp:/", "mms:/"
		};
		int ul = url.length();
		for (String scheme : SCHEMES) {
			int sl = scheme.length();
			if (url.startsWith(scheme) && (ul == sl || url.charAt(sl) != '/')) {
				return scheme + "/" + url.substring(sl);
			}
		}
		return url;
	}

	/**
	 * Attempt to extract the hostname component of an absolute URL argument.
	 * @param url the url String from which to extract the hostname
	 * @return the hostname within the URL, or the url argument if the host 
	 * cannot be found.
	 */
	public static String urlToHost(String url) {
		String lcUrl = url.toLowerCase();
		if(lcUrl.startsWith(DNS_SCHEME)) {
			return lcUrl.substring(DNS_SCHEME.length());
		}
		for(String scheme : ALL_SCHEMES) {
			if(lcUrl.startsWith(scheme)) {
				int authorityIdx = scheme.length();

				Matcher m = 
					HOST_REGEX_SIMPLE.matcher(lcUrl.substring(authorityIdx));
				if(m.find()) {
					return m.group(1);
				}
			}
		}
		return url;
	}
	
	/**
	 * Extract userinfo from the absolute URL argument, that is, "username@", or
	 * "username:password@" if present.
	 * @param url the URL from which to extract the userinfo
	 * @return the userinfo found, not including the "@", or null if no userinfo
	 * is found
	 */
	public static String urlToUserInfo(String url) {
		String lcUrl = url.toLowerCase();
		if(lcUrl.startsWith(DNS_SCHEME)) {
			return null;
		}
		for(String scheme : ALL_SCHEMES) {
			if(lcUrl.startsWith(scheme)) {
				int authorityIdx = scheme.length();

				Matcher m = 
					USERINFO_REGEX_SIMPLE.matcher(lcUrl.substring(authorityIdx));
				if(m.find()) {
					return m.group(1);
				}
			}
		}
		return null;
	}
	
	/**
	 * Find and return the parent directory of the URL argument
	 * @param url to find the parent directory of
	 * @return parent directory of URL, or null, if either the url argument is
	 * invalid, or if the url is the root of the authority.
	 */
	public static String getUrlParentDir(String url) {
		
		try {
			UsableURI uri = UsableURIFactory.getInstance(url);
			String path = uri.getPath();
			if(path.length() > 1) {
				int startIdx = path.length()-1;
				if(path.charAt(path.length()-1) == '/') {
					startIdx--;
				}
				int idx = path.lastIndexOf('/',startIdx);
				if(idx >= 0) {
					uri.setPath(path.substring(0,idx+1));
					uri.setQuery(null);
					return uri.toString();
				}
			}
		} catch (URIException e) {
			LOGGER.warning(e.getLocalizedMessage() + ": " + url);
		}
		return null;
	}
	
	/**
	 * build replay Archival-URL for the same capture as request
	 * {@code wbRequest}, with identity-context ({@code id_}) flag on.
	 * <p>
	 * REFACTOR: move this method to {@link ArchivalUrl}.
	 * </p>
	 * @param wbRequest requested capture and URL scheme info.
	 * @return URL string
	 */
	public static String computeIdentityUrl(WaybackRequest wbRequest) {
		AccessPoint accessPoint = wbRequest.getAccessPoint();

		boolean origIdentity = wbRequest.isIdentityContext();
		wbRequest.setIdentityContext(true);
		
		ArchivalUrl aUrl = new ArchivalUrl(wbRequest);
		String bestPath = aUrl.toString();
		String betterURI = accessPoint.getReplayPrefix() + bestPath;

		//reset the isIdentity flag just in case
		wbRequest.setIdentityContext(origIdentity);
		
		return betterURI;
	}
}
