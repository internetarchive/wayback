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

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.cdxserver.auth.AuthToken;
import org.archive.format.gzip.zipnum.ZipNumBlockLoader;
import org.archive.wayback.ExceptionRenderer;
import org.archive.wayback.QueryRenderer;
import org.archive.wayback.ReplayDispatcher;
import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.ReplayURIConverter;
import org.archive.wayback.ReplayURIConverter.URLStyle;
import org.archive.wayback.RequestParser;
import org.archive.wayback.ResourceStore;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.UrlCanonicalizer;
import org.archive.wayback.accesscontrol.AuthContextExclusionFilterFactory;
import org.archive.wayback.accesscontrol.CollectionContext;
import org.archive.wayback.accesscontrol.ContextExclusionFilterFactory;
import org.archive.wayback.accesscontrol.ExclusionFilterFactory;
import org.archive.wayback.archivalurl.ArchivalUrl;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.SearchResults;
import org.archive.wayback.core.UIResults;
import org.archive.wayback.core.UrlSearchResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.AccessControlException;
import org.archive.wayback.exception.AdministrativeAccessControlException;
import org.archive.wayback.exception.AnchorWindowTooSmallException;
import org.archive.wayback.exception.AuthenticationControlException;
import org.archive.wayback.exception.BadQueryException;
import org.archive.wayback.exception.BaseExceptionRenderer;
import org.archive.wayback.exception.BetterReplayRequestException;
import org.archive.wayback.exception.BetterRequestException;
import org.archive.wayback.exception.ConfigurationException;
import org.archive.wayback.exception.ResourceIndexNotAvailableException;
import org.archive.wayback.exception.ResourceNotAvailableException;
import org.archive.wayback.exception.ResourceNotInArchiveException;
import org.archive.wayback.exception.SpecificCaptureReplayException;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.memento.DefaultMementoHandler;
import org.archive.wayback.memento.MementoHandler;
import org.archive.wayback.memento.MementoUtils;
import org.archive.wayback.replay.DefaultReplayCaptureSelector;
import org.archive.wayback.replay.ReplayCaptureSelector;
import org.archive.wayback.replay.html.RewriteDirector;
import org.archive.wayback.resourceindex.cdxserver.EmbeddedCDXServerIndex;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;
import org.archive.wayback.resourceindex.filters.WARCRevisitAnnotationFilter;
import org.archive.wayback.util.operator.BooleanOperator;
import org.archive.wayback.util.url.UrlOperations;
import org.archive.wayback.util.webapp.AbstractRequestHandler;
import org.archive.wayback.util.webapp.ShutdownListener;
import org.archive.wayback.webapp.LiveWebRedirector.LiveWebState;

/**
 * Retains all information about a particular Wayback configuration
 * within a ServletContext, including holding references to the
 * implementation instances of the primary Wayback classes:
 * 
 * 		RequestParser
 *		ResourceIndex(via WaybackCollection)
 *		ResourceStore(via WaybackCollection)
 *		QueryRenderer
 *		ReplayDispatcher
 *		ExceptionRenderer
 *		ResultURIConverter
 *
 *
 * @author brad
 */
public class AccessPoint extends AbstractRequestHandler implements
		ShutdownListener, CollectionContext {
	/** webapp relative location of Interstitial.jsp */
	public final static String INTERSTITIAL_JSP = "jsp/Interstitial.jsp";
	/** argument for Interstitial.jsp target URL */
	public final static String INTERSTITIAL_TARGET = "target";
	/** argument for Interstitial.jsp seconds to delay */
	public final static String INTERSTITIAL_SECONDS = "seconds";

	/** argument for Interstitial.jsp msse for replay date */
	public final static String INTERSTITIAL_DATE = "date";
	/** argument for Interstitial.jsp URL being loaded */
	public final static String INTERSTITIAL_URL = "url";

	public final static String REVISIT_STR = "warc/revisit";
	public final static String EMPTY_VALUE = "-";

	public final static String RUNTIME_ERROR_HEADER = "X-Archive-Wayback-Runtime-Error";
	private final static int MAX_ERR_HEADER_LEN = 300;

	//public final static String NOTFOUND_ERROR_HEADER = "X-Archive-Wayback-Not-Found";

	private static final Logger LOGGER = Logger.getLogger(
		AccessPoint.class.getName());

	private boolean exactHostMatch = false;
	private boolean exactSchemeMatch = false;
	private boolean useAnchorWindow = false;
	private boolean useServerName = false;
	private boolean serveStatic = true;
	private boolean bounceToReplayPrefix = false;
	private boolean bounceToQueryPrefix = false;
	private boolean forceCleanQueries = true;

	private boolean timestampSearch = false;

	public static enum PerfStat {
		IndexQueryTotal,
		WArcResource,
		Total,
	}

	private String errorMsgHeader = RUNTIME_ERROR_HEADER;
	private String perfStatsHeader = "X-Archive-Wayback-Perf";
	private String warcFileHeader = "x-archive-src";

	private boolean enableErrorMsgHeader = false;
	private boolean enablePerfStatsHeader = false;
	private boolean enableWarcFileHeader = false;
	private boolean enableMemento = true;

	private PerfStats.OutputFormat perfStatsHeaderFormat = PerfStats.OutputFormat.BRACKET;

	private LiveWebRedirector liveWebRedirector;

	private String staticPrefix = null;
	private String queryPrefix = null;
	private String replayPrefix = null;

	private String interstitialJsp = INTERSTITIAL_JSP;

	private String refererAuth = null;

	private Locale locale = null;

	private Properties configs = null;

	private List<String> filePatterns = null;
	private List<String> fileIncludePrefixes = null;
	private List<String> fileExcludePrefixes = null;

	private WaybackCollection collection = null;
	private ExceptionRenderer exception = new BaseExceptionRenderer();
	private QueryRenderer query = null;
	private RequestParser parser = null;
	private ReplayDispatcher replay = null;
	private ResultURIConverter uriConverter = null;

	private MementoHandler mementoHandler = new DefaultMementoHandler();

	private ExclusionFilterFactory exclusionFactory = null;
	private RewriteDirector rewriteDirector;

	private BooleanOperator<WaybackRequest> authentication = null;
	private boolean requestAuth = true;

	private long embargoMS = 0;
	private CustomResultFilterFactory filterFactory = null;

	private UrlCanonicalizer selfRedirectCanonicalizer = null;

	private int maxRedirectAttempts = 0;

	private boolean fixedEmbeds = false;

	public void init() {
		checkAccessPointAware(collection,exception,query,parser,replay,
			uriConverter,exclusionFactory, authentication, filterFactory);
	}

	protected boolean dispatchLocal(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws ServletException,
			IOException {
		if (LOGGER.isLoggable(Level.FINE)) {
			LOGGER.fine("Local dispatch /" + translateRequestPath(httpRequest));
		}
		if (!serveStatic) {
			return false;
		}
//		String contextRelativePath = httpRequest.getServletPath();
		String translatedNoQuery = "/" + translateRequestPath(httpRequest);
//		String absPath = getServletContext().getRealPath(contextRelativePath);
		String absPath = getServletContext().getRealPath(translatedNoQuery);

		if (this.isEnableMemento()) {
			MementoUtils.addDoNotNegotiateHeader(httpResponse);
		}

		//IK: added null check for absPath, it may be null (ex. on jetty)
		if (absPath != null) {
			File test = new File(absPath);
			if((test != null) && !test.exists()) {
				return false;
			}
		}

		String translatedQ = "/" + translateRequestPathQuery(httpRequest);

		WaybackRequest wbRequest = new WaybackRequest();
//			wbRequest.setContextPrefix(getUrlRoot());
		wbRequest.setAccessPoint(this);
		wbRequest.extractHttpRequestInfo(httpRequest);
		UIResults uiResults = new UIResults(wbRequest,uriConverter);
		try {
			uiResults.forward(httpRequest, httpResponse, translatedQ);
			return true;
		} catch(IOException e) {
			// TODO: figure out if we got IO because of a missing dispatcher
		}

		return false;
	}

	/**
	 * @param httpRequest HttpServletRequest which is being handled
	 * @param httpResponse HttpServletResponse which is being handled
	 * @return true if the request was actually handled
	 * @throws ServletException per usual
	 * @throws IOException per usual
	 */
	public boolean handleRequest(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws ServletException,
			IOException {

		WaybackRequest wbRequest = null;
		boolean handled = false;

		try {
			PerfStats.clearAll();

			if (this.isEnablePerfStatsHeader() && (perfStatsHeader != null)) {
				PerfStats.timeStart(PerfStat.Total);
				httpResponse = new PerfWritingHttpServletResponse(httpRequest,
					httpResponse, PerfStat.Total, perfStatsHeader,
					perfStatsHeaderFormat);
			}

			String inputPath = translateRequestPathQuery(httpRequest);
			Thread.currentThread().setName("Thread " +
					Thread.currentThread().getId() + " " + getBeanName() +
					" handling: " + inputPath);
			LOGGER.fine("Handling translated: " + inputPath);
			wbRequest = getParser().parse(httpRequest, this);

			if (wbRequest != null) {
				handled = true;

				// TODO: refactor this code into RequestParser implementations
				wbRequest.setAccessPoint(this);
//				wbRequest.setContextPrefix(getAbsoluteLocalPrefix(httpRequest));
//				wbRequest.setContextPrefix(getUrlRoot());
				wbRequest.extractHttpRequestInfo(httpRequest);
				// end of refactor

				if (getAuthentication() != null) {
					if (!getAuthentication().isTrue(wbRequest)) {
						throw new AuthenticationControlException(
							"Unauthorized", isRequestAuth());
					}
				}

				// remove this line by having all call AccessPoint.createExclusionFilter()
				// directly.
				wbRequest.setExclusionFilter(createExclusionFilter());

				// TODO: refactor this into RequestParser implementations, so a
				// user could alter requests to change the behavior within a
				// single AccessPoint. For now, this is a simple way to expose
				// the feature to configuration.g
				wbRequest.setExactScheme(isExactSchemeMatch());

				if (wbRequest.isReplayRequest()) {
					if (bounceToReplayPrefix) {
						// we don't accept replay requests on this AccessPoint
						// bounce the user to the right place:
						String suffix = translateRequestPathQuery(httpRequest);
						String replayUrl = replayPrefix + suffix;
						httpResponse.sendRedirect(replayUrl);
						return true;
					}
					handleReplay(wbRequest, httpRequest, httpResponse);
				} else {
					if (bounceToQueryPrefix) {
						// we don't accept replay requests on this AccessPoint
						// bounce the user to the right place:
						String suffix = translateRequestPathQuery(httpRequest);
						String replayUrl = queryPrefix + suffix;
						httpResponse.sendRedirect(replayUrl);
						return true;
					}
					wbRequest.setExactHost(isExactHostMatch());
					handleQuery(wbRequest, httpRequest, httpResponse);
				}
			} else {
				handled = dispatchLocal(httpRequest, httpResponse);
			}
		} catch (BetterRequestException e) {
			e.generateResponse(httpResponse, wbRequest);
			httpResponse.getWriter(); // cause perf headers to be committed
			handled = true;
		} catch (WaybackException e) {
			if (httpResponse.isCommitted()) {
				return true;
			}

			if (wbRequest == null) {
				wbRequest = new WaybackRequest();
				wbRequest.setAccessPoint(this);
			}

			logError(httpResponse, errorMsgHeader, e, wbRequest);

			LiveWebState liveWebState = LiveWebState.NOT_FOUND;

			if ((getLiveWebRedirector() != null) &&
					!wbRequest.hasMementoAcceptDatetime() && !wbRequest.isMementoTimemapRequest()) {
				liveWebState = getLiveWebRedirector().handleRedirect(e, wbRequest, httpRequest, httpResponse);
			}

			// If not liveweb redirected, then render current exception
			if (liveWebState != LiveWebState.REDIRECTED) {
				e.setLiveWebAvailable(liveWebState == LiveWebState.FOUND);
				getException().renderException(httpRequest, httpResponse, wbRequest, e, getUriConverter());
			}

			handled = true;

		} catch (Exception other) {
			logError(httpResponse, errorMsgHeader, other, wbRequest);
		} finally {
			//Slightly hacky, but ensures that all block loaders are closed
			ZipNumBlockLoader.closeAllReaders();
		}

		return handled;
	}

	/**
	 * Return new instance of {@link ExclusionFilter} instance for this AccessPoint.
	 * @param subject client information (may be {@code null})
	 * @throws AccessControlException If it cannot instantiate ExclusionFilter when
	 * it's supposed to (i.e. configured but failed to complete because of network
	 * error etc.)
	 */
	public ExclusionFilter createExclusionFilter(AuthToken subject) throws AccessControlException {
		ExclusionFilterFactory factory = getExclusionFactory();
		if (factory != null) {
			ExclusionFilter exclusionFilter = null;
			if (factory instanceof AuthContextExclusionFilterFactory) {
				// allows null return value
				return ((AuthContextExclusionFilterFactory)factory).getExclusionFilter(this, subject);
			} else if (factory instanceof ContextExclusionFilterFactory) {
				exclusionFilter = ((ContextExclusionFilterFactory)factory).getExclusionFilter(this);
			} else {
				exclusionFilter = factory.get();
			}
			if (exclusionFilter == null) {
				throw new AdministrativeAccessControlException(
						"AccessControl list unavailable");
			}
			return exclusionFilter;
		}
		return null;
	}
	
	public ExclusionFilter createExclusionFilter() throws AccessControlException {
		return createExclusionFilter(null);
	}

	public RewriteDirector getRewriteDirector() {
		return rewriteDirector;
	}

	public void setRewriteDirector(RewriteDirector rewriteDirector) {
		this.rewriteDirector = rewriteDirector;
	}

	/**
	 * Return rewrite directive for {@code capture}.
	 * @param capture
	 * @return string representing rewrite rules
	 */
	public String getRewriteDirective(CaptureSearchResult capture) {
		String directive = null;
		// use getter, as it may be overridden in sub-classes.
		RewriteDirector rd = getRewriteDirector();
		if (rd != null) {
			directive = rd.getRewriteDirective(this, capture);
		}
		return directive;
	}

	/**
	 * Default implementation returns {@code null}.
	 */
	@Override
	public String getCollectionContextName() {
		return null;
	}

	public void logError(HttpServletResponse httpResponse, String header,
			Exception e, WaybackRequest request) {
		if (e instanceof ResourceNotInArchiveException) {
			if (LOGGER.isLoggable(Level.INFO)) {
				this.logNotInArchive((ResourceNotInArchiveException)e, request);
			}
		} else if (e instanceof AccessControlException) {
			// While StaticMapExclusionFilter#isExcluded(String) reports
			// exclusion at INFO level, RobotExclusionFilter logs exclusion
			// at FINE level only. I believe here is the better place to log
			// exclusion. Unfortunately, AccessControlException has no
			// detailed info (TODO). we don't need a stack trace.
			if (LOGGER.isLoggable(Level.INFO)) {
				LOGGER.log(Level.INFO, "Access Blocked:" + request.getRequestUrl() + ": "+ e.getMessage());
			}
		} else {
			if (LOGGER.isLoggable(Level.WARNING)) {
				LOGGER.log(Level.WARNING, "Runtime Error", e);
			}
		}

		if (!this.isEnableErrorMsgHeader()) {
			return;
		}

		String message = (e != null ? e.toString() : "");

		if (message == null) {
			message = "";
		} else {
			// Get substring from exception name
			int index = message.indexOf(':');
			if (index > 0) {
				index = message.lastIndexOf('.', index);
				if (index > 0) {
					message = message.substring(index + 1);
				}
			}

			if (message.length() > MAX_ERR_HEADER_LEN) {
				message = message.substring(0, MAX_ERR_HEADER_LEN);
			}
			message = message.replace('\n', ' ');
		}

		httpResponse.setHeader(header, message);
	}

	private void logNotInArchive(ResourceNotInArchiveException e, WaybackRequest r) {
		// TODO: move this into ResourceNotInArchiveException constructor
		String url = r.getRequestUrl();
		StringBuilder sb = new StringBuilder(100);
		sb.append("NotInArchive\t");
		sb.append(getBeanName()).append("\t");
		sb.append(url);
		LOGGER.info(sb.toString());
	}

	protected void checkAccessPointAware(Object... os) {
		if (os != null) {
			for (Object o : os) {
				if (o instanceof AccessPointAware) {
					AccessPointAware apa = (AccessPointAware)o;
					apa.setAccessPoint(this);
				}
			}
		}
	}

	private void checkInterstitialRedirect(HttpServletRequest httpRequest,
			WaybackRequest wbRequest) throws BetterRequestException {
		if ((refererAuth != null) && (refererAuth.length() > 0) &&
				!wbRequest.hasMementoAcceptDatetime()) {
			String referer = httpRequest.getHeader("Referer");
			if ((referer != null) && (referer.length() > 0) &&
					(!referer.contains(refererAuth))) {
				StringBuffer sb = httpRequest.getRequestURL();
				if (httpRequest.getQueryString() != null) {
					sb.append("?").append(httpRequest.getQueryString());
				}
				StringBuilder u = new StringBuilder();
				u.append(getQueryPrefix());
				u.append(interstitialJsp);
				u.append("?");
				u.append(INTERSTITIAL_SECONDS).append("=").append(5);
				u.append("&");
				u.append(INTERSTITIAL_DATE).append("=")
					.append(wbRequest.getReplayDate().getTime());
				u.append("&");
				u.append(INTERSTITIAL_URL).append("=");
				try {
					u.append(URLEncoder.encode(wbRequest.getRequestUrl(),
						"UTF-8"));
				} catch (UnsupportedEncodingException e) {
					// not gonna happen...
					u.append(wbRequest.getRequestUrl());
				}
				u.append("&");
				u.append(INTERSTITIAL_TARGET).append("=");
				try {
					u.append(URLEncoder.encode(sb.toString(), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					// not gonna happen...
					u.append(sb.toString());
				}

				throw new BetterRequestException(u.toString());
			}
		}
	}

	protected boolean isSelfRedirect(Resource resource,
			CaptureSearchResult closest, WaybackRequest wbRequest,
			String canonRequestURL) {
		int status = resource.getStatusCode();

		// Only applies to redirects
		if ((status < 300) || (status >= 400)) {
			return false;
		}

		String location = resource.getHeader("Location");

		if (location == null) {
			return false;
		}

//		if (!closest.getCaptureTimestamp().equals(wbRequest.getReplayTimestamp())) {
//			return false;
//		}

		String redirScheme = UrlOperations.urlToScheme(location);

		try {
			if (redirScheme == null && isExactSchemeMatch()) {
				location = UrlOperations.resolveUrl(closest.getOriginalUrl(), location);
				redirScheme = UrlOperations.urlToScheme(location);
			} else if (location.startsWith("/")) {
				location = UrlOperations.resolveUrl(closest.getOriginalUrl(), location);
			}

			if (getSelfRedirectCanonicalizer() != null) {
				location = getSelfRedirectCanonicalizer().urlStringToKey(location);
			}
		} catch (IOException e) {
			return false;
		}

		if (location.equals(canonRequestURL)) {
			// if not exact scheme, don't do scheme compare, must be equal
			if (!isExactSchemeMatch()) {
				return true;
			}

			String origScheme = UrlOperations.urlToScheme(wbRequest
				.getRequestUrl());

			if ((origScheme != null) && (redirScheme != null) &&
					(origScheme.compareTo(redirScheme) == 0)) {
				return true;
			}
		}

		return false;
	}

	public SearchResults queryIndex(WaybackRequest wbRequest)
			throws ResourceIndexNotAvailableException,
			ResourceNotInArchiveException, BadQueryException,
			AccessControlException, ConfigurationException {
		try {
			PerfStats.timeStart(PerfStat.IndexQueryTotal);
			return getCollection().getResourceIndex().query(wbRequest);
		} finally {
			PerfStats.timeEnd(PerfStat.IndexQueryTotal);
		}
	}

	/**
	 * Per-request, decorating ResourceStore that throws
	 * {@link ResourceNotAvailableException} if retrieval of the
	 * archive has failed previously within the session.
	 * It helps AccessPoint quickly scan through {@code CaptureSearchResult}s
	 * pointing the same archive as their revisit original.
	 * It also collects performance stats. 
	 */
	protected static class SingleLoadResourceStore implements ResourceStore {
		private Set<String> skipFiles;
		private ResourceStore resourceStore;
		public SingleLoadResourceStore(ResourceStore realResourceStore) {
			this.resourceStore = realResourceStore;
		}

		protected void addSkip(String filename) {
			if (filename == null) return;
			if (skipFiles == null)
				skipFiles = new HashSet<String>();
			skipFiles.add(filename);
		}
		protected boolean isSkipped(String filename) {
			return filename != null && skipFiles != null && skipFiles.contains(filename);
		}

		@Override
		public Resource retrieveResource(CaptureSearchResult result)
				throws ResourceNotAvailableException {
			try {
				PerfStats.timeStart(PerfStat.WArcResource);
				if (isSkipped(result.getFile())) {
					throw new ResourceNotAvailableException(
						"Revisit: Skipping already failed " + result.getFile());
				}
				try {
					return resourceStore.retrieveResource(result);
				} catch (ResourceNotAvailableException ex) {
					// Old code obtained archive filename via getDtails() method of
					// exception object, in the code handling SepcificCaptureReplayException.
					// Of two subclasses of SpecificCaptureReplayException, BadContentException
					// (only thrown from HttpHeaderOperation.copyHTTPMessageHeader()) never had
					// non-null details. So, this covers all cases, and more robust.
					addSkip(result.getFile());
					throw ex;
				}
 			} finally {
 				PerfStats.timeEnd(PerfStat.WArcResource);
 			}
		}

		@Override
		public void shutdown() throws IOException {
		}
	}

	public boolean isWaybackReferer(WaybackRequest wbRequest, String path) {
		return isWaybackReferer(wbRequest.getRefererUrl(), path);
	}

	public boolean isWaybackReferer(String referer, String path) {
		if (referer == null) {
			return false;
		}

		Object value = this.getConfigs().get("fullPathPrefix");
		String fullPathPrefix = (value != null ? value.toString() : null);

		if (fullPathPrefix != null && !fullPathPrefix.isEmpty()) {
			return referer.contains(fullPathPrefix + path);
		} else {
			return referer.contains(path);
		}
	}

	/**
	 * if capture {@code closest} is of timestamp different from the one requested,
	 * redirect to exact Archival-URL for {@code closest}.
	 * Memento Timegate request is always redirected regardless of timestamp.
	 * Needs better method name.
	 * @param wbRequest
	 * @param httpResponse
	 * @param captureResults
	 * @param closest
	 * @throws BetterRequestException
	 */
	protected void handleReplayRedirect(WaybackRequest wbRequest,
			HttpServletResponse httpResponse,
			CaptureSearchResults captureResults, CaptureSearchResult closest)
			throws BetterRequestException {
		if (wbRequest.getReplayTimestamp().startsWith(closest.getCaptureTimestamp()) && !wbRequest.isMementoTimegate()) {
			// Matching
			return;
		}

		captureResults.setClosest(closest);

		//TODO: better detection of non-redirect proxy mode?
		// For now, checking if the betterURI does not contain the timestamp, then we're not doing a redirect
		String datespec = ArchivalUrl.getDateSpec(wbRequest, closest.getCaptureTimestamp());
		String betterURI = getUriConverter().makeReplayURI(datespec, closest.getOriginalUrl());

		// if spare-redirect-for-embeds is on, render embedded resource in-place with Content-Location header pointing
		// exact replay URL (it is disabled for timegate requests)
		// XXX set Content-Location header somewhere else.
		if (fixedEmbeds && !wbRequest.isMementoTimegate() && isWaybackReferer(wbRequest, this.getReplayPrefix())) {
			httpResponse.setHeader("Content-Location", betterURI);
			return;
		}

		boolean isNonRedirectProxy = !betterURI.contains(closest.getCaptureTimestamp());

		if (!isNonRedirectProxy) {
			throw new BetterReplayRequestException(closest, captureResults);
		}
	}

	/**
	 * return {@code true} if capture's timestamp matches exactly what's requested.
	 * If requested timestamp is less specific (i.e. less digits) than capture's
	 * timestamp, it is considered non-matching. On the other hand, capture's
	 * timestamp being prefix of requested timestamp is considered a match (this is
	 * to support captures with timestamp shorter than 14-digits. this may change).
	 * @param closest capture to check
	 * @param wbRequest request object
	 * @return {@code true} if match
	 */
	private static boolean timestampMatch(CaptureSearchResult closest, WaybackRequest wbRequest) {
		String replayTimestamp = wbRequest.getReplayTimestamp();
		String captureTimestamp = closest.getCaptureTimestamp();

		if (replayTimestamp.length() < captureTimestamp.length())
			return false;
		if (replayTimestamp.startsWith(captureTimestamp))
			return true;
		// if looking for latest date, consider it a tentative match, until
		// checking if it's replay-able.
		if (wbRequest.isBestLatestReplayRequest())
			return true;
		return false;
	}

	protected void handleReplay(WaybackRequest wbRequest,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse)
					throws IOException, ServletException, WaybackException {

		checkInterstitialRedirect(httpRequest,wbRequest);

		String requestURL = wbRequest.getRequestUrl();

		if (getSelfRedirectCanonicalizer() != null) {
			try {
				requestURL = getSelfRedirectCanonicalizer().urlStringToKey(requestURL);
			} catch (IOException io) {

			}
		}

		PerformanceLogger p = new PerformanceLogger("replay");

		// If optimized url+timestamp search is supported, mark the request
		if (this.isTimestampSearch()) {
			if (wbRequest.isAnyEmbeddedContext() || wbRequest.isIdentityContext()) {
				wbRequest.setTimestampSearchKey(true);
			}
		}

		CaptureSearchResults captureResults;
		try {
			captureResults = searchCaptures(wbRequest);
		} finally {
			p.queried();
		}

		ReplayCaptureSelector captureSelector = new DefaultReplayCaptureSelector(getReplay());
		captureSelector.setRequest(wbRequest);
		captureSelector.setCaptures(captureResults);

		CaptureSearchResult closest = captureSelector.next();

		int counter = 0;

		//TODO: parameterize
		//int maxTimeouts = 2;
		//int maxMissingRevisits = 2;

		SingleLoadResourceStore resourceStore = new SingleLoadResourceStore(getCollection().getResourceStore());
		//Set<String> skipFiles = null;

		while (true) {
			// Support for redirect from the CDX redirectUrl field
			// This was the intended use of the redirect field, but has not actually be tested
			// To enable this functionality, uncomment the lines below
			// This is an optimization that allows for redirects to be handled without loading the original content
			//
			//String redir = closest.getRedirectUrl();
			//if ((redir != null) && !redir.equals("-")) {
			//  String fullRedirect = getUriConverter().makeReplayURI(closest.getCaptureTimestamp(), redir);
			//  throw new BetterRequestException(fullRedirect, Integer.valueOf(closest.getHttpCode()));
			//}

			Resource httpHeadersResource = null;
			Resource payloadResource = null;
			boolean isRevisit = false;

			try {
				counter++;

				if (closest == null) {
					throw new ResourceNotAvailableException("Self-Redirect: No Closest Match Found", 404);
				}

				closest.setClosest(true);
				checkAnchorWindow(wbRequest, closest);

				// Redirect to url for the actual closest capture, if not a retry
				if (counter == 1) {
					handleReplayRedirect(wbRequest, httpResponse, captureResults, closest);
				}

				// If revisit, may load two resources separately
				if (closest.isRevisitDigest()) {
					isRevisit = true;

					// If the payload record is known and it failed before with this payload, don't try
					// loading the header resource even.. outcome will likely be same
					if (resourceStore.isSkipped(closest.getDuplicatePayloadFile())) {
						// (XXX cannot simply call SessionResourceStore#retrieveResource() because of this
						// counter thing - is there a better way?)
						counter--; //don't really count this as we're not even checking the file anymore
						throw new ResourceNotAvailableException(
							"Revisit: Skipping already failed " +
									closest.getDuplicatePayloadFile());
					}
					if ((closest.getDuplicatePayloadFile() == null) && wbRequest.isTimestampSearchKey()) {
						// If a missing revisit and loaded optimized, try loading the entire timeline again

						wbRequest.setTimestampSearchKey(false);

						captureResults = searchCaptures(wbRequest);

						closest = captureSelector.next();
						//originalClosest = closest;
						//maxTimeouts *= 2;
						//maxMissingRevisits *= 2;

						continue;
					}

					// If old-style arc revisit (no mimetype, filename is '-'), then don't load
					// headersResource = payloadResource
					if (EMPTY_VALUE.equals(closest.getFile())) {
						closest.setFile(closest.getDuplicatePayloadFile());
						closest.setOffset(closest.getDuplicatePayloadOffset());

						// See that this is successful
						httpHeadersResource = resourceStore.retrieveResource(closest);

						// Hmm, since this is a revisit it should not redirect -- was: if both headers and payload are from a different timestamp, redirect to that timestamp
//						if (!closest.getCaptureTimestamp().equals(closest.getDuplicateDigestStoredTimestamp())) {
//							throwRedirect(wbRequest, httpResponse, captureResults, closest.getDuplicateDigestStoredTimestamp(), closest.getOriginalUrl(), closest.getHttpCode());
//						}

						payloadResource = httpHeadersResource;

					} else {
						httpHeadersResource = resourceStore.retrieveResource(closest);

						CaptureSearchResult payloadLocation = retrievePayloadForIdenticalContentRevisit(wbRequest, httpHeadersResource, closest);

						if (payloadLocation == null) {
							throw new ResourceNotAvailableException("Revisit: Missing original for revisit record " + closest.toString(), 404);
						}

						payloadResource = resourceStore.retrieveResource(payloadLocation);

						// If zero length old-style revisit with no headers, then must use payloadResource as headersResource
						if (httpHeadersResource.getRecordLength() <= 0) {
							httpHeadersResource.close();
							httpHeadersResource = payloadResource;
						}
					}
				} else {
					httpHeadersResource = resourceStore.retrieveResource(closest);
					payloadResource = httpHeadersResource;
				}

				// Ensure that we are not self-redirecting!
				// If the status is a redirect, check that the location or url date's are different from the current request
				// Otherwise, replay the previous matched capture.
				// This chain is unlikely to go past one previous capture, but is possible
				if (isSelfRedirect(httpHeadersResource, closest, wbRequest, requestURL)) {
					LOGGER.info("Self-Redirect: Skipping " + closest.getCaptureTimestamp() + "/" + closest.getOriginalUrl());
					//closest = findNextClosest(closest, captureResults, requestMS);
					closest = captureSelector.next();
					continue;
				}

				if (counter > 1) {
					handleReplayRedirect(wbRequest, httpResponse, captureResults, closest);
				}

				p.retrieved();

				ReplayRenderer renderer =
						getReplay().getRenderer(wbRequest, closest, httpHeadersResource, payloadResource);

				if (this.isEnableWarcFileHeader() && (warcFileHeader != null)) {
					if (isRevisit && (closest.getDuplicatePayloadFile() != null)) {
						httpResponse.addHeader(warcFileHeader, closest.getDuplicatePayloadFile());
					} else {
						httpResponse.addHeader(warcFileHeader, closest.getFile());
					}
				}

				if (this.isEnableMemento()) {
					MementoUtils.addMementoDatetimeHeader(httpResponse, closest);
					if (wbRequest.isMementoTimegate()) {
						// URL-G in non-redirect proxy mode (archival-url URL-G
						// always redirects in handleReplayRedirect()).
						if (getMementoHandler() != null) {
							getMementoHandler().addTimegateHeaders(
								httpResponse, captureResults, wbRequest, true);
						} else {
							// bare minimum required for URL-G response [sic]
							// XXX this lacks Vary: accept-datetime header required for URL-G
							MementoUtils.addOrigHeader(httpResponse, closest.getOriginalUrl());
							// Probably this is better - same as DefaultMementoHandler
							//MementoUtils.addTimegateHeaders(httpResponse, captureResults, wbRequest, true);
						}
					} else {
						// Memento URL-M response (can't be an intermediate resource)
						MementoUtils.addLinkHeader(httpResponse, captureResults, wbRequest, true, true);
					}
				}

				ResultURIConverter uriConverter = getUriConverter();
				// allow sub-class to add custom request-sensitive behavior to base ReplayURIConverter.
				if (uriConverter instanceof ReplayURIConverter) {
					uriConverter = decorateURIConverter((ReplayURIConverter)uriConverter, httpRequest, wbRequest);
				}

				renderer.renderResource(httpRequest, httpResponse, wbRequest,
					closest, httpHeadersResource, payloadResource, uriConverter, captureResults);

				p.rendered();
				p.write(wbRequest.getReplayTimestamp() + " " +
						wbRequest.getRequestUrl());

				break;

			} catch (SpecificCaptureReplayException scre) {
				// Primarily ResourceNotAvailableException from ResourceStore,
				// but renderer.renderResource(...) above can throw
				// BadContentException (very rare).

				//final String SOCKET_TIMEOUT_MSG = "java.net.SocketTimeoutException: Read timed out";

				CaptureSearchResult nextClosest = null;

				// if exceed maxRedirectAttempts, stop
				if ((counter > maxRedirectAttempts) && ((this.getLiveWebPrefix() == null) || !isWaybackReferer(wbRequest, this.getLiveWebPrefix()))) {
					LOGGER.info("LOADFAIL: Timeout: Too many retries, limited to " + maxRedirectAttempts);
				} else if ((closest != null) && !wbRequest.isIdentityContext()) {
					//nextClosest = findNextClosest(closest, captureResults, requestMS);
					nextClosest = captureSelector.next();
				}

				// Skip any nextClosest that has the same exact filename?
				// Removing in case skip something that works..
				// while ((nextClosest != null) && closest.getFile().equals(nextClosest.getFile())) {
				//	nextClosest = findNextClosest(nextClosest, captureResults, requestMS);
				//}

				String msg = null;

				if (closest != null) {
					msg = scre.getMessage() + " /" + closest.getCaptureTimestamp() + "/" + closest.getOriginalUrl();
				} else {
					msg = scre.getMessage() + " /" + wbRequest.getReplayTimestamp() + "/" + wbRequest.getRequestUrl();
				}

				if (nextClosest != null) {
					if (msg.startsWith("Self-Redirect")) {
						LOGGER.info("(" + counter + ")LOADFAIL-> " + msg + " -> " + nextClosest.getCaptureTimestamp());
					} else {
						LOGGER.warning("(" + counter + ")LOADFAIL-> " + msg + " -> " + nextClosest.getCaptureTimestamp());
					}

					closest = nextClosest;
				} else if (wbRequest.isTimestampSearchKey()) {
					wbRequest.setTimestampSearchKey(false);

					captureResults = searchCaptures(wbRequest);

					captureSelector.setCaptures(captureResults);
					closest = captureSelector.next();

					//originalClosest = closest;

					//maxTimeouts *= 2;
					//maxMissingRevisits *= 2;

					continue;
				} else {
					LOGGER.warning("(" + counter + ")LOADFAIL: " + msg);
					scre.setCaptureContext(captureResults, closest);
					throw scre;
				}
			} finally {
				closeResources(payloadResource, httpHeadersResource);
			}
		}
	}

	protected CaptureSearchResults searchCaptures(WaybackRequest wbr)
			throws ResourceIndexNotAvailableException,
			ResourceNotInArchiveException, BadQueryException,
			AccessControlException, ConfigurationException, ResourceNotAvailableException {
		SearchResults results = queryIndex(wbr);
		if (!(results instanceof CaptureSearchResults)) {
			throw new ResourceNotAvailableException(
				"Bad results looking up " + wbr.getReplayTimestamp() + " " +
						wbr.getRequestUrl());
		}
		return (CaptureSearchResults)results;
	}

	// method isWarcRevisitNotModified(Resource) method has been moved to
	// WarcResource#isRevisitNotModified().

	/**
	 * If closest
	 * @param revisitRecord
	 * @param captureResults
	 * @param closest
	 * @return the payload resource
	 * @throws ResourceNotAvailableException
	 * @throws ConfigurationException
	 * @throws AccessControlException
	 * @throws BadQueryException
	 * @throws ResourceNotInArchiveException
	 * @throws ResourceIndexNotAvailableException
	 * @see WARCRevisitAnnotationFilter
	 */
	protected CaptureSearchResult retrievePayloadForIdenticalContentRevisit(
			WaybackRequest currRequest,
			Resource revisitRecord,
			CaptureSearchResult closest)
			throws ResourceIndexNotAvailableException,
			ResourceNotInArchiveException, BadQueryException,
			AccessControlException, ConfigurationException,
			ResourceNotAvailableException {

		if (!closest.isRevisitDigest()) {
			LOGGER.warning("Revisit: record is not a revisit by identical content digest " + closest.getCaptureTimestamp() + " " + closest.getOriginalUrl());
			return null;
		}

		CaptureSearchResult payloadLocation = null;

		// Revisit from same url -- should have been found by the loader

		if (closest.getDuplicatePayloadFile() != null && closest.getDuplicatePayloadOffset() != null) {
			payloadLocation = new CaptureSearchResult();
			payloadLocation.setFile(closest.getDuplicatePayloadFile());
			payloadLocation.setOffset(closest.getDuplicatePayloadOffset());
			payloadLocation.setCompressedLength(closest.getDuplicatePayloadCompressedLength());
			return payloadLocation;
		}

		// Url Agnostic Revisit with target-uri and refers-to-date

		String payloadUri = revisitRecord.getRefersToTargetURI();
		String payloadTimestamp = revisitRecord.getRefersToDate();

		if (payloadUri != null && payloadTimestamp != null) {
			WaybackRequest wbr = currRequest.clone();
			wbr.setReplayTimestamp(payloadTimestamp);
			wbr.setAnchorTimestamp(payloadTimestamp);
			wbr.setTimestampSearchKey(true);
			wbr.setRequestUrl(payloadUri);
			// experimental parameter to tell EmbeddedCDXServerIndex
			// that it's looking up the payload of URL-agnostic revisit.
			// EmbeddedCDXServerIndex will include soft-blocked captures
			// in the result.
			wbr.put(EmbeddedCDXServerIndex.REQUEST_REVISIT_LOOKUP, "true");

			CaptureSearchResults payloadCaptureResults = searchCaptures(wbr);
			// closest may not be the one pointed by payloadTimestamp
			ReplayCaptureSelector captureSelector = new DefaultReplayCaptureSelector(getReplay());
			captureSelector.setRequest(wbr);
			captureSelector.setCaptures(payloadCaptureResults);
			payloadLocation = captureSelector.next();
			// closest will not be the one pointed by payloadTimestamp if revisited
			// capture is missing (can happen for many reasons; not indexed yet, archive
			// has gone missing, for example).
			// TODO: this is pretty inefficient. should have a method for searching
			// just one capture at specific timestamp. Perhaps timestampSearchKey
			// is meant for this purpose, but it's not working as expected, apparently.
			if (payloadLocation != null) {
				String captureTimestamp = payloadLocation.getCaptureTimestamp();
				// not supporting captureTimestamp less than 14 digits.
				if (!captureTimestamp.equals(payloadTimestamp))
					payloadLocation = null;
			}
		}

//		if (payloadLocation != null) {
//			return payloadLocation;
//		}
//
		// Less common less recommended revisit with specific warc/filename
//		WarcResource wr = (WarcResource) revisitRecord;
//		warcHeaders = wr.getWarcHeaders().getHeaderFields();
//		String payloadWarcFile = (String) warcHeaders.get("WARC-Refers-To-Filename");
//		String offsetStr = (String) warcHeaders.get("WARC-Refers-To-File-Offset");
//		if (payloadWarcFile != null && offsetStr != null) {
//			payloadLocation = new CaptureSearchResult();
//			payloadLocation.setFile(payloadWarcFile);
//			payloadLocation.setOffset(Long.parseLong(offsetStr));
//		}

		return payloadLocation;
	}

	private void checkAnchorWindow(WaybackRequest wbRequest,
			CaptureSearchResult result) throws AnchorWindowTooSmallException {
		if (isUseAnchorWindow()) {
			String anchorDate = wbRequest.getAnchorTimestamp();
			if (anchorDate != null) {
				long wantTime = wbRequest.getReplayDate().getTime();
				long maxWindow = wbRequest.getAnchorWindow() * 1000;
				if (maxWindow > 0) {
					long closestDistance = Math.abs(wantTime -
							result.getCaptureDate().getTime());

					if (closestDistance > maxWindow) {
						throw new AnchorWindowTooSmallException("Closest is " +
								closestDistance + " seconds away, Window is " +
								maxWindow);
					}
				}
			}
		}
	}
	
	private int queryCollapseTime = -1;

	/**
	 * CDXServer {@code collapseTime} parameter for capture query.
	 * @param queryCollapseTime integer, negative value instructs
	 * CDXServer to use the default value.
	 */
	public void setQueryCollapseTime(int queryCollapseTime) {
		this.queryCollapseTime = queryCollapseTime;
	}
	public int getQueryCollapseTime() {
		return queryCollapseTime;
	}

	protected void handleQuery(WaybackRequest wbRequest,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse)
			throws ServletException, IOException, WaybackException {

		PerformanceLogger p = new PerformanceLogger("query");

		// TODO: move this Memento code out of this method.
		// Memento: render timemap
		if ((this.getMementoHandler() != null) &&
				(wbRequest.isMementoTimemapRequest())) {
			if (this.getMementoHandler().renderMementoTimemap(wbRequest,
				httpRequest, httpResponse)) {
				return;
			}
		}

		// TODO: should this be applied to Memento Timemap as well?

		// Must call getQueryCollapseTime() because AccessPointAdapter
		// needs to read parent's value, not the unused field of its
		// own.
		wbRequest.setCollapseTime(getQueryCollapseTime());
		
		SearchResults results = queryIndex(wbRequest);

		p.queried();

		if (results instanceof CaptureSearchResults) {
			CaptureSearchResults cResults = (CaptureSearchResults)results;

			// The Firefox proxy plugin maks an XML request to populate the
			// list of available captures, and needs the closest result to
			// the one being replayed to be flagged as such:
			CaptureSearchResult closest = cResults.getClosest();
			if (closest != null) {
				closest.setClosest(true);
			}

			getQuery().renderCaptureResults(httpRequest, httpResponse,
				wbRequest, cResults, getUriConverter());

		} else if (results instanceof UrlSearchResults) {
			UrlSearchResults uResults = (UrlSearchResults)results;
			getQuery().renderUrlResults(httpRequest, httpResponse, wbRequest,
				uResults, getUriConverter());
		} else {
			throw new WaybackException("Unknown index format");
		}
		p.rendered();
		p.write(wbRequest.getRequestUrl());
	}

	/**
	 * Release any resources associated with this AccessPoint, including
	 * stopping any background processing threads
	 */
	public void shutdown() {
		if (collection != null) {
			try {
				collection.shutdown();
			} catch (IOException e) {
				LOGGER.severe("FAILED collection shutdown" + e.getMessage());
			}
		}
		if (exclusionFactory != null) {
			exclusionFactory.shutdown();
		}
	}

	protected void closeResources(Resource payloadResource,
			Resource httpHeadersResource) {
		if ((payloadResource != null) &&
				(payloadResource != httpHeadersResource)) {
			try {
				payloadResource.close();
			} catch (IOException e) {
				LOGGER.warning(e.toString());
			}
		}

		if (httpHeadersResource != null) {
			try {
				httpHeadersResource.close();
			} catch (IOException e) {
				LOGGER.warning(e.toString());
			}
		}
	}

	private String getBestPrefix(String best, String next, String last) {
		if (best != null) {
			return best;
		}
		if (next != null) {
			return next;
		}
		return last;
	}

	/*
	 * *******************************************************************
	 * *******************************************************************
	 * 
	 *    ALL GETTER/SETTER BELOW HERE
	 * 
	 * *******************************************************************
	 * *******************************************************************
	 */

	/**
	 * @return the exactHostMatch
	 */
	public boolean isExactHostMatch() {
		return exactHostMatch;
	}

	/**
	 * @param exactHostMatch if true, then only SearchResults exactly matching
	 * 		the requested hostname will be returned from this AccessPoint. If
	 * 		false, then hosts which canonicalize to the same host as requested
	 * 		hostname will be returned (www.)
	 */
	public void setExactHostMatch(boolean exactHostMatch) {
		this.exactHostMatch = exactHostMatch;
	}

	/**
	 * @return the exactSchemeMatch
	 */
	public boolean isExactSchemeMatch() {
		return exactSchemeMatch;
	}

	/**
	 * @param exactSchemeMatch the exactSchemeMatch to set
	 */
	public void setExactSchemeMatch(boolean exactSchemeMatch) {
		this.exactSchemeMatch = exactSchemeMatch;
	}

	/**
	 * @return true if this AccessPoint is configured to useAnchorWindow, that
	 * is, to replay documents only if they are within a certain proximity to
	 * the users requested AnchorDate
	 */
	public boolean isUseAnchorWindow() {
		return useAnchorWindow;
	}

	/**
	 * @param useAnchorWindow , when set to true, causes this AccessPoint to
	 * only replay documents if they are within a certain proximity to
	 * the users requested AnchorDate
	 */
	public void setUseAnchorWindow(boolean useAnchorWindow) {
		this.useAnchorWindow = useAnchorWindow;
	}

	/**
	 * @return the useServerName
	 * @deprecated no longer used, use {replay,query,static}Prefix
	 */
	public boolean isUseServerName() {
		return useServerName;
	}

	/**
	 * @param useServerName the useServerName to set
	 * @deprecated no longer used, use {replay,query,static}Prefix
	 */
	public void setUseServerName(boolean useServerName) {
		this.useServerName = useServerName;
	}

	/**
	 * @return true if this AccessPoint serves static content
	 */
	public boolean isServeStatic() {
		return serveStatic;
	}

	/**
	 * @param serveStatic if set to true, this AccessPoint will serve static
	 * content, and .jsp files
	 */
	public void setServeStatic(boolean serveStatic) {
		this.serveStatic = serveStatic;
	}

	/**
	 * @return the livewebRedirector which determines if custom loading or handling
	 * is done for resources that are not successfully loaded,
	 */
	public LiveWebRedirector getLiveWebRedirector() {
		return liveWebRedirector;
	}

	/**
	 * @param liveWebPrefix the String URL prefix to use to attempt to retrieve
	 * documents missing from the collection from the live web, on demand.
	 */
	public void setLiveWebRedirector(LiveWebRedirector liveWebRedirector) {
		this.liveWebRedirector = liveWebRedirector;
	}

	// Set standard liveweb redirector
	public void setLiveWebPrefix(String liveWebPrefix) {
		if (liveWebPrefix == null || liveWebPrefix.isEmpty()) {
			this.liveWebRedirector = null;
		}

		this.liveWebRedirector = new DefaultLiveWebRedirector(liveWebPrefix);
	}

	public String getLiveWebPrefix() {
		if (this.liveWebRedirector == null) {
			return null;
		}

		return this.liveWebRedirector.getLiveWebPrefix();
	}

	/**
	 * @return the String url prefix to use when generating self referencing
	 * 			static URLs
	 */
	public String getStaticPrefix() {
		return getBestPrefix(staticPrefix,queryPrefix,replayPrefix);
	}

	/**
	 * @param staticPrefix explicit URL prefix to use when creating self referencing
	 * 		static URLs
	 */
	public void setStaticPrefix(String staticPrefix) {
		this.staticPrefix = staticPrefix;
	}

	/**
	 * @return the String url prefix to use when generating self referencing
	 * 			replay URLs
	 */
	public String getReplayPrefix() {
		return getBestPrefix(replayPrefix, queryPrefix, staticPrefix);
	}

	/**
	 * @param replayPrefix explicit URL prefix to use when creating self referencing
	 * 		replay URLs
	 */
	public void setReplayPrefix(String replayPrefix) {
		this.replayPrefix = replayPrefix;
	}

	/**
	 * @param queryPrefix explicit URL prefix to use when creating self referencing
	 * 		query URLs
	 */
	public void setQueryPrefix(String queryPrefix) {
		this.queryPrefix = queryPrefix;
	}

	/**
	 * @return the String url prefix to use when generating self referencing
	 * 			replay URLs
	 */
	public String getQueryPrefix() {
		return getBestPrefix(queryPrefix, staticPrefix, replayPrefix);
	}

	/**
	 * Build a self-referencing URL that will perform a query for all copies
	 * of URL {@code url}.
	 * @param url URL to search for copies of
	 * @param startdate start of date range in DT14 format (may be {@code null}
	 * for no date range.
	 * @param enddate end of date range in DT14 format (may be {@code null}, ignored
	 * if {@code startdate} is {@code null})
	 * @return String URL that will make a query for all captures of {@code url}.
	 */
	public String makeCaptureQueryUrl(String url, String startdate, String enddate) {
		// XXX assumes particular style of query URL, which may not be compatible
		// with RequestParsers in use. TODO: refactor.
		if (startdate != null) {
			if (enddate != null) {
				return getQueryPrefix() + startdate + "-" + enddate + "*/" + url;
			} else {
				return getQueryPrefix() + startdate + "*/" + url;
			}
		} else {
			return getQueryPrefix() + "*/" + url;
		}
	}

	/**
	 * @param interstitialJsp the interstitialJsp to set
	 */
	public void setInterstitialJsp(String interstitialJsp) {
		this.interstitialJsp = interstitialJsp;
	}

	/**
	 * @return the interstitialJsp
	 */
	public String getInterstitialJsp() {
		return interstitialJsp;
	}

	/**
	 * @param urlRoot explicit URL prefix to use when creating ANY self
	 * referencing URLs
	 * @deprecated use setQueryPrefix, setReplayPrefix, setStaticPrefix
	 */
	public void setUrlRoot(String urlRoot) {
		this.queryPrefix = urlRoot;
		this.replayPrefix = urlRoot;
		this.staticPrefix = urlRoot;
	}

	/**
	 * @return the String url prefix used when generating self referencing
	 * 			URLs
	 * @deprecated use getQueryPrefix, getReplayPrefix, getStaticPrefix
	 */
	public String getUrlRoot() {
		return getBestPrefix(queryPrefix,staticPrefix,replayPrefix);
	}

	/**
	 * @return explicit Locale to use within this AccessPoint.
	 */
	public Locale getLocale() {
		return locale;
	}

	/**
	 * @param locale explicit Locale to use for requests within this
	 * 		AccessPoint. If not set, will attempt to use the one specified by
	 * 		each requests User Agent via HTTP headers
	 */
	public void setLocale(Locale locale) {
		this.locale = locale;
	}

	/**
	 * @return the generic customization Properties used with this AccessPoint,
	 * generally to tune the UI
	 */
	public Properties getConfigs() {
		return configs;
	}

	/**
	 * @param configs the generic customization Properties to use with this
	 * AccessPoint, generally used to tune the UI
	 */

	public void setConfigs(Properties configs) {
		this.configs = configs;
	}

	/**
	 * @return List of file patterns that will be matched when querying the
	 * ResourceIndex
	 */
	public List<String> getFilePatterns() {
		return filePatterns;
	}

	/**
	 * @param filePatterns List of file Patterns (regular expressions) that
	 * 		will be matched when querying the ResourceIndex - only SearchResults
	 *      matching one of these patterns will be returned.
	 */
	public void setFilePatterns(List<String> filePatterns) {
		this.filePatterns = filePatterns;
	}

	/**
	 * @return List of file String prefixes that will be matched when querying
	 * 		the ResourceIndex
	 */
	public List<String> getFileIncludePrefixes() {
		return fileIncludePrefixes;
	}

	/**
	 * @param fileIncludePrefixes List of String file prefixes that will be matched
	 * 		when querying the ResourceIndex - only SearchResults from files
	 * 		with a prefix matching one of those in this List will be returned.
	 */
	public void setFileIncludePrefixes(List<String> fileIncludePrefixes) {
		this.fileIncludePrefixes = fileIncludePrefixes;
	}

	/**
	 * @return List of file String prefixes that will be matched when querying
	 * 		the ResourceIndex
	 */
	public List<String> getFileExcludePrefixes() {
		return fileExcludePrefixes;
	}

	/**
	 * @param fileExcludePrefixes List of String file prefixes that will be matched
	 * 		when querying the ResourceIndex - only SearchResults from files
	 * 		with a prefix matching one of those in this List will be returned.
	 */
	public void setFileExcludePrefixes(List<String> fileExcludePrefixes) {
		this.fileExcludePrefixes = fileExcludePrefixes;
	}

	/**
	 * @return the WaybackCollection used by this AccessPoint
	 */
	public WaybackCollection getCollection() {
		return collection;
	}

	/**
	 * @param collection the WaybackCollection to use with this AccessPoint
	 */
	public void setCollection(WaybackCollection collection) {
		this.collection = collection;
	}

	/**
	 * @return the ExceptionRenderer in use with this AccessPoint
	 */
	public ExceptionRenderer getException() {
		return exception;
	}

	/**
	 * @param exception the ExceptionRender to use with this AccessPoint
	 */
	public void setException(ExceptionRenderer exception) {
		this.exception = exception;
	}

	/**
	 * @return the QueryRenderer to use with this AccessPoint
	 */
	public QueryRenderer getQuery() {
		return query;
	}

	/**
	 * @param query the QueryRenderer responsible for returning query data to
	 * clients.
	 */
	public void setQuery(QueryRenderer query) {
		this.query = query;
	}

	/**
	 * @return the RequestParser used by this AccessPoint to attempt to
	 * translate incoming HttpServletRequest objects into WaybackRequest
	 * objects
	 */
	public RequestParser getParser() {
		return parser;
	}

	/**
	 * @param parser the RequestParser to use with this AccessPoint
	 */
	public void setParser(RequestParser parser) {
		this.parser = parser;
	}

	/**
	 * @return the ReplayDispatcher to use with this AccessPoint, responsible
	 * for returning an appropriate ReplayRenderer given the user request and
	 * the returned document type.
	 */
	public ReplayDispatcher getReplay() {
		return replay;
	}

	/**
	 * @param replay the ReplayDispatcher to use with this AccessPoint.
	 */
	public void setReplay(ReplayDispatcher replay) {
		this.replay = replay;
	}

	/**
	 * @return the ResultURIConverter used to construct Replay URLs within this
	 * AccessPoint
	 */
	public ResultURIConverter getUriConverter() {
		return uriConverter;
	}

	/**
	 * <p>Refactoring: remove this method. let {@link #getUriConverter()} create
	 * ResultURIConverter with factory (like AccessPointAdapter does).</p>
	 * @param uriConverter the ResultURIConverter to use with this AccessPoint
	 * to construct Replay URLs
	 */
	public void setUriConverter(ResultURIConverter uriConverter) {
		this.uriConverter = uriConverter;
	}

	/**
	 * Return replay URL builder decorated with request-sensitive behavior if necessary.
	 * Base implementation returns {@code uriConverter} as it is.
	 * @param uriConverter base, request-agnostic, replay URL builder (return value of
	 * {@link #getUriConverter()}.
	 * @param httpRequest current servlet request
	 * @param wbRequest current wayback request
	 * @return possibly decorated replay URL builder
	 */
	public ReplayURIConverter decorateURIConverter(
			ReplayURIConverter uriConverter, HttpServletRequest httpRequest,
			WaybackRequest wbRequest) {
		return uriConverter;
	}

	/**
	 * Shortcut for calling {@link ReplayURIConverter#makeReplayURI(String, String, String, URLStyle)}.
	 * Current implementation also have backward-compatibility code supporting ResultURIConveretr.
	 * @param datespec replay timestamp (do not include context flags)
	 * @param url replay target URL
	 * @param flags context flags (ex. "{@code cs_}")
	 * @param urlStyle desired URL style
	 * @return replay URL for this access point
	 */
	public String makeReplayURI(String datespec, String url, String flags,
			URLStyle urlStyle) {
		ResultURIConverter uriConverter = getUriConverter();
		if (uriConverter instanceof ReplayURIConverter) {
			return ((ReplayURIConverter)uriConverter).makeReplayURI(datespec,
				url, flags, urlStyle);
		} else {
			return uriConverter.makeReplayURI(flags != null ? datespec + flags
					: datespec, url);
		}
	}

	/**
	 * @return the ExclusionFilterFactory in use with this AccessPoint
	 */
	public ExclusionFilterFactory getExclusionFactory() {
		return exclusionFactory;
	}

	/**
	 * @param exclusionFactory all requests to this AccessPoint will create an
	 * 		exclusionFilter from this factory when handling requests
	 */
	public void setExclusionFactory(ExclusionFilterFactory exclusionFactory) {
		this.exclusionFactory = exclusionFactory;
	}

	/**
	 * @return the configured AuthenticationControl BooleanOperator in use with
	 *      this AccessPoint.
	 */
	public BooleanOperator<WaybackRequest> getAuthentication() {
		return authentication;
	}

	/**
	 * @param auth the BooleanOperator which determines if incoming
	 * 		requests are allowed to connect to this AccessPoint.
	 */
	public void setAuthentication(BooleanOperator<WaybackRequest> auth) {
		this.authentication = auth;
	}

	public boolean isRequestAuth() {
		return requestAuth;
	}

	public void setRequestAuth(boolean requestAuth) {
		this.requestAuth = requestAuth;
	}

	/**
	 * @return the refererAuth
	 */
	public String getRefererAuth() {
		return refererAuth;
	}

	/**
	 * @param refererAuth the refererAuth to set
	 */
	public void setRefererAuth(String refererAuth) {
		this.refererAuth = refererAuth;
	}

	/**
	 * @return the bounceToReplayPrefix
	 */
	public boolean isBounceToReplayPrefix() {
		return bounceToReplayPrefix;
	}

	/**
	 * @param bounceToReplayPrefix the bounceToReplayPrefix to set
	 */
	public void setBounceToReplayPrefix(boolean bounceToReplayPrefix) {
		this.bounceToReplayPrefix = bounceToReplayPrefix;
	}
	/**
	 * @return the bounceToQueryPrefix
	 */
	public boolean isBounceToQueryPrefix() {
		return bounceToQueryPrefix;
	}

	/**
	 * @param bounceToQueryPrefix the bounceToQueryPrefix to set
	 */
	public void setBounceToQueryPrefix(boolean bounceToQueryPrefix) {
		this.bounceToQueryPrefix = bounceToQueryPrefix;
	}

	/**
	 * @return the configured number of MS for min age to return from the index
	 */
	public long getEmbargoMS() {
		return embargoMS;
	}
	/**
	 * @param ms minimum number of MS age for content to be served from the index
	 */
	public void setEmbargoMS(long ms) {
		this.embargoMS = ms;
	}

	/**
	 * @return the forceCleanQueries
	 */
	public boolean isForceCleanQueries() {
		return forceCleanQueries;
	}

	/**
	 * @param forceCleanQueries the forceCleanQueries to set
	 */
	public void setForceCleanQueries(boolean forceCleanQueries) {
		this.forceCleanQueries = forceCleanQueries;
	}

	/**
	 * {@link CustomResultFilterFactory} to be applied on CDX query result.
	 * <p>AccessPoint itself does not use this object.
	 * {@link org.archive.wayback.ResourceIndex} implementation needs to implement filtering
	 * using this property. {@code ClusterResourceIndex} is the only
	 * implementation known at this moment.</p>
	 * <p>Note: this property will likely be removed in the future.</p>
	 * @param filterFactory the filterFactory to set
	 */
	public void setFilterFactory(CustomResultFilterFactory filterFactory) {
		this.filterFactory = filterFactory;
	}

	/**
	 * @return the filterFactory
	 */
	public CustomResultFilterFactory getFilterFactory() {
		return filterFactory;
	}

	/**
	 * Optional
	 * @param selfRedirectCanonicalizer
	 */
	public void setSelfRedirectCanonicalizer(
			UrlCanonicalizer selfRedirectCanonicalizer) {
		this.selfRedirectCanonicalizer = selfRedirectCanonicalizer;
	}

	/**
	 * URL canonicalizer for testing self-redirect.
	 * @return UrlCanonicalizer
	 */
	public UrlCanonicalizer getSelfRedirectCanonicalizer() {
		return this.selfRedirectCanonicalizer;
	}

	public int getMaxRedirectAttempts() {
		return maxRedirectAttempts;
	}

	public void setMaxRedirectAttempts(int maxRedirectAttempts) {
		this.maxRedirectAttempts = maxRedirectAttempts;
	}

	public boolean isFixedEmbeds() {
		return fixedEmbeds;
	}

	public void setFixedEmbeds(boolean fixedEmbeds) {
		this.fixedEmbeds = fixedEmbeds;
	}

	public boolean isTimestampSearch() {
		return timestampSearch;
	}

	public void setTimestampSearch(boolean timestampSearch) {
		this.timestampSearch = timestampSearch;
	}

	public String getPerfStatsHeader() {
		return perfStatsHeader;
	}

	public void setPerfStatsHeader(String perfStatsHeader) {
		this.perfStatsHeader = perfStatsHeader;
	}

	public String getWarcFileHeader() {
		return warcFileHeader;
	}

	public void setWarcFileHeader(String warcFileHeader) {
		this.warcFileHeader = warcFileHeader;
	}

	public String getErrorMsgHeader() {
		return errorMsgHeader;
	}

	public void setErrorMsgHeader(String errorMsgHeader) {
		this.errorMsgHeader = errorMsgHeader;
	}

	public boolean isEnableErrorMsgHeader() {
		return enableErrorMsgHeader;
	}

	public void setEnableErrorMsgHeader(boolean enableErrorMsgHeader) {
		this.enableErrorMsgHeader = enableErrorMsgHeader;
	}

	public boolean isEnablePerfStatsHeader() {
		return enablePerfStatsHeader;
	}

	public void setEnablePerfStatsHeader(boolean enablePerfStatsHeader) {
		this.enablePerfStatsHeader = enablePerfStatsHeader;
	}

	public boolean isEnableWarcFileHeader() {
		return enableWarcFileHeader;
	}

	public void setEnableWarcFileHeader(boolean enableWarcFileHeader) {
		this.enableWarcFileHeader = enableWarcFileHeader;
	}

	public boolean isEnableMemento() {
		return enableMemento;
	}

	public void setEnableMemento(boolean enableMemento) {
		this.enableMemento = enableMemento;
	}

	public MementoHandler getMementoHandler() {
		return mementoHandler;
	}

	public void setMementoHandler(MementoHandler mementoHandler) {
		this.mementoHandler = mementoHandler;
	}

	/**
	 * Format of profiling header field.
	 * @param perfStatsHeaderFormat
	 * @see PerfStats.OutputFormat
	 */
	public void setPerfStatsHeaderFormat(
			PerfStats.OutputFormat perfStatsHeaderFormat) {
		this.perfStatsHeaderFormat = perfStatsHeaderFormat;
	}
}
