package org.archive.wayback.resourceindex.cdxserver;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.URIException;
import org.archive.cdxserver.CDXQuery;
import org.archive.cdxserver.CDXServer;
import org.archive.cdxserver.auth.AuthToken;
import org.archive.cdxserver.auth.PrivTokenAuthChecker;
import org.archive.cdxserver.writer.CDXWriter;
import org.archive.cdxserver.writer.HttpCDXWriter;
import org.archive.cdxserver.writer.JsonWriter;
import org.archive.cdxserver.writer.MementoLinkWriter;
import org.archive.cdxserver.writer.PlainTextWriter;
import org.archive.format.cdx.CDXInputSource;
import org.archive.format.cdx.CDXLine;
import org.archive.format.cdx.MultiCDXInputSource;
import org.archive.format.cdx.StandardCDXLineFactory;
import org.archive.format.gzip.zipnum.ZipNumParams;
import org.archive.url.UrlSurtRangeComputer.MatchType;
import org.archive.util.binsearch.SeekableLineReaderIterator;
import org.archive.util.binsearch.impl.HTTPSeekableLineReader;
import org.archive.util.binsearch.impl.HTTPSeekableLineReaderFactory;
import org.archive.util.binsearch.impl.http.ApacheHttp31SLRFactory;
import org.archive.util.io.RuntimeIOException;
import org.archive.util.iterator.CloseableIterator;
import org.archive.util.iterator.SortedCompositeIterator;
import org.archive.wayback.ResourceIndex;
import org.archive.wayback.UrlCanonicalizer;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.SearchResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.AccessControlException;
import org.archive.wayback.exception.BadQueryException;
import org.archive.wayback.exception.ResourceIndexNotAvailableException;
import org.archive.wayback.exception.ResourceNotInArchiveException;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.memento.MementoConstants;
import org.archive.wayback.memento.MementoHandler;
import org.archive.wayback.memento.MementoUtils;
import org.archive.wayback.resourceindex.filters.SelfRedirectFilter;
import org.archive.wayback.util.webapp.AbstractRequestHandler;
import org.archive.wayback.util.webapp.RequestHandler;
import org.archive.wayback.webapp.PerfStats;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.web.bind.ServletRequestBindingException;

/**
 * {@link ResourceIndex} on top of {@link CDXServer}. Also a {@link RequestHandler}
 * for CDX server queries.
 *
 */
public class EmbeddedCDXServerIndex extends AbstractRequestHandler implements MementoHandler, ResourceIndex {

	private static final Logger LOGGER = Logger.getLogger(
			EmbeddedCDXServerIndex.class.getName());

	protected CDXServer cdxServer;
	protected int timestampDedupLength = 0;
	protected int limit = 0;

	protected UrlCanonicalizer canonicalizer = null;
	protected SelfRedirectFilter selfRedirFilter;

	protected String remoteCdxPath;

	private HTTPSeekableLineReaderFactory remoteCdxHttp = new ApacheHttp31SLRFactory();
	private StandardCDXLineFactory cdxLineFactory = new StandardCDXLineFactory("cdx11");

	private String remoteAuthCookie;
	private String remoteAuthCookieIgnoreRobots;

	protected CDXInputSource extraSource;

	protected String preferContains;

	protected boolean tryFuzzyMatch = false;

	protected List<String> ignoreRobotPaths;

	protected String baseStatusRegexp;
	protected String baseStatusFilter;
	{
		setBaseStatusRegexp("!(500|502|504)");
	}

	enum PerfStat {
		IndexLoad;
	}

	// transitional - default impl sentinel
	private static final CDXToCaptureSearchResultsWriter NOT_OVERRIDDEN = new CDXToCaptureSearchResultsWriter();

	/**
	 * {@link WaybackRequest} parameter name for telling
	 * {@code EmbeddedCDXServerIndex} that it's looking up a specific single
	 * capture needed for replaying URL-agnostic revisit.
	 * <p>
	 * Defined here, without setter/getter, because this is an experimental
	 * parameter supporting soft-block feature. It's very likely to change.
	 * </p>
	 */
	public final static String REQUEST_REVISIT_LOOKUP = "EmbeddedCDXServerIndex.revisit-lookup";

	@Override
    public SearchResults query(WaybackRequest wbRequest)
            throws ResourceIndexNotAvailableException,
            ResourceNotInArchiveException, BadQueryException,
            AccessControlException {
		// TODO: AccessPoint.queryIndex has PerfStats code immediately around
		// a call to this method. Remove this PerfStats thing.
		try {
			PerfStats.timeStart(PerfStat.IndexLoad);
			return doQuery(wbRequest);
		} finally {
			PerfStats.timeEnd(PerfStat.IndexLoad);
		}
	}

	/**
	 * return {@link AuthToken}, representing user's privileges on {@code urlkey}, for
	 * CDX query through Wayback UI. Not meant for CDX server API.
	 * <ul>
	 * <li>robots.txt may be ignored for embedded resources (CSS, images, javascripts)</li>
	 * <li>robots.txt may be ignored if {@code urlkey} starts with any of {@code ignoreRobotPaths}</li>
	 * </ul>
	 */
	protected AuthToken createAuthToken(WaybackRequest wbRequest, String urlkey) {
		AuthToken waybackAuthToken = new APContextAuthToken(
				wbRequest.getAccessPoint());
		waybackAuthToken.setAllCdxFieldsAllow();

		boolean ignoreRobots = wbRequest.isCSSContext() ||
				wbRequest.isIMGContext() || wbRequest.isJSContext();

		if (ignoreRobots) {
			waybackAuthToken.setIgnoreRobots(true);
		}

		if (ignoreRobotPaths != null) {
			for (String path : ignoreRobotPaths) {
				if (urlkey.startsWith(path)) {
					waybackAuthToken.setIgnoreRobots(true);
					break;
				}
			}
		}

		return waybackAuthToken;
	}
	
	/**
	 * Common processing for AccessPoint-aware (i.e. passing AccessPoint-bearing
	 * AuthToken to CDXServer so that AuthChecker implementations can pull
	 * information from AccessPoint).
	 * @param wbRequest request
	 * @param resultWriter CDXWriter receiving query result
	 * @param fuzzyMatch {@code fuzzy} flag for {@link #loadWaybackCdx(String, WaybackRequest, CDXQuery, AuthToken, CDXWriter, boolean)}
	 * @throws BadQueryException
	 * @throws IOException 
	 * @throws AccessControlException 
	 */
	protected void doAccessPointAwareQuery(WaybackRequest wbRequest,
			CDXQuery query, CDXWriter resultWriter, boolean fuzzyMatch)
			throws BadQueryException, AccessControlException, IOException {
    	//Compute url key (surt)
		String urlkey = null;

		// If no canonicalizer is set, use selfRedirFilter's canonicalizer
		// Either selfRedirFilter or a canonicalizer must be set

		UrlCanonicalizer canon = getCanonicalizer();

		if (canon == null && selfRedirFilter != null) {
			canon = selfRedirFilter.getCanonicalizer();
		}

		if (canon == null) {
			throw new IllegalArgumentException(
					"Unable to find canonicalizer, canonicalizer property or selfRedirFilter property must be set");
		}

		try {
			urlkey = canon.urlStringToKey(wbRequest.getRequestUrl());
		} catch (URIException ue) {
			throw new BadQueryException(ue.toString());
		}

		AuthToken waybackAuthToken = createAuthToken(wbRequest, urlkey);

		loadWaybackCdx(urlkey, wbRequest, query,
			waybackAuthToken, resultWriter, fuzzyMatch);
	}

    public SearchResults doQuery(WaybackRequest wbRequest)
            throws ResourceIndexNotAvailableException,
            ResourceNotInArchiveException, BadQueryException,
            AccessControlException {

    	CDXQuery query;
		CDXToSearchResultWriter resultWriter = null;
		if (wbRequest.isReplayRequest() || wbRequest.isCaptureQueryRequest()) {
			// TRANSITIONAL - default getCaptureSearchWriter(WaybackRequest, AuthToken, boolean)
			// returns sentinel NOT_OVERRIDDEN. If it returns different value, it is overridden.
			// Note we no longer pass AuthToken.
			resultWriter = getCaptureSearchWriter(wbRequest, null, false);
			if (resultWriter != NOT_OVERRIDDEN) {
				query = resultWriter != null ? resultWriter.getQuery() : null;
			} else {
				query = createQuery(wbRequest, false);
				resultWriter = getCaptureSearchWriter(wbRequest);
			}
		} else if (wbRequest.isUrlQueryRequest()) {
			query = createUrlSearchQuery(wbRequest);
			resultWriter = new CDXToUrlSearchResultWriter(); //getUrlSearchWriter();
		} else {
			throw new BadQueryException("Unknown Query Type");
		}

		try {
			doAccessPointAwareQuery(wbRequest, query, resultWriter, false);
			if (resultWriter.getErrorMsg() != null) {
				throw new BadQueryException(resultWriter.getErrorMsg());
			}

			SearchResults searchResults = resultWriter.getSearchResults();

			if ((searchResults.getReturnedCount() == 0) &&
					(wbRequest.isReplayRequest() || wbRequest
							.isCaptureQueryRequest()) && tryFuzzyMatch) {
				// transitional (see above) - no longer passing AuthToken
				resultWriter = getCaptureSearchWriter(wbRequest, null, true);
				if (resultWriter != NOT_OVERRIDDEN) {
					if (resultWriter != null)
						query = resultWriter.getQuery();
				} else {
					query = createQuery(wbRequest, true);
					resultWriter = query != null ? getCaptureSearchWriter(wbRequest) : null;
				}

				if (resultWriter != null) {
					doAccessPointAwareQuery(wbRequest, query, resultWriter, true);
					searchResults = resultWriter.getSearchResults();
				}
			}

			if (searchResults.getReturnedCount() == 0) {
				throw new ResourceNotInArchiveException(
						wbRequest.getRequestUrl() + " was not found");
			}

			return searchResults;

		} catch (IOException e) {
			throw new ResourceIndexNotAvailableException(e.toString());
		} catch (RuntimeException rte) {
			Throwable cause = rte.getCause();

			if (cause instanceof AccessControlException) {
				throw (AccessControlException)cause;
			}

			if (cause instanceof IOException) {
				throw new ResourceIndexNotAvailableException(cause.toString());
			}

			rte.printStackTrace(); // for now, for better debugging
			throw new ResourceIndexNotAvailableException(rte.toString());
		}
	}

	protected void loadWaybackCdx(String urlkey, WaybackRequest wbRequest,
			CDXQuery query, AuthToken waybackAuthToken,
			CDXWriter resultWriter, boolean fuzzy)
			throws IOException, AccessControlException {

		if ((remoteCdxPath != null) && !wbRequest.isUrlQueryRequest()) {
			try {
				// Not supported for remote requests, caching the entire cdx
				wbRequest.setTimestampSearchKey(false);
				remoteCdxServerQuery(urlkey, query, waybackAuthToken,
					(CDXToSearchResultWriter)resultWriter);
				return;
			} catch (IOException io) {
				// Try again below
			} catch (RuntimeIOException rte) {
				Throwable cause = rte.getCause();

				if (cause instanceof AccessControlException) {
					throw (AccessControlException)cause;
				} else {
					LOGGER.warning(rte.toString());
				}
			}
		}

		cdxServer.getCdx(query, waybackAuthToken, resultWriter);
	}

	/**
	 * Create {@link CDXQuery} that is sent to {@link CDXServer}.
	 *
	 * The query specifies standard CDX server params described at:
	 * https://github.com/internetarchive/wayback/tree/master/wayback-cdx-server
	 *
	 * Note: this method adds extra filters meant for interactive (Wayback UI)
	 * use. CDXServer web API should not use this method.  this method is used
	 * for replay and capture-search requests only.
	 *
	 * TODO: move this to {@link CDXQuery} as static method.
	 *
	 * @param wbRequest {@link WaybackRequest} either replay or capture-query
	 * @param isFuzzy unused (?)
	 * @return
	 */
	protected CDXQuery createQuery(WaybackRequest wbRequest, boolean isFuzzy) {
		CDXQuery query = new CDXQuery(wbRequest.getRequestUrl());

		query.setLimit(limit);

		String statusFilter = baseStatusFilter;

		if (wbRequest.isReplayRequest()) {
			if (wbRequest.isBestLatestReplayRequest()) {
				statusFilter = "statuscode:[23]..";
			}

			if (wbRequest.isTimestampSearchKey()) {
				query.setClosest(wbRequest.getReplayTimestamp());
			}

			// set explicit matchType, or CDXServer will run prefix
			// query when URL ends with "*".
			query.setMatchType(MatchType.exact);
		} else if (wbRequest.isCaptureQueryRequest()) {
			// Add support for range calendar queries:
			// eg: /2005-2007*/
			// by mapping request start and end timestamp
			// to cdx server from= and to= params
			String start = wbRequest.getStartTimestamp();
			if (start != null) {
				query.setFrom(start);
			}
			String end = wbRequest.getEndTimestamp();
			if (end != null) {
				query.setTo(end);
			}
		}

		// CDXServer#writeCdxResponse sets up timestamp collapsing filter
		// if collapseTime > 0
		int collapseTime = wbRequest.getCollapseTime();
		if (collapseTime < 0) {
			// unspecified - default to timestampDedupLength
			// (note: even zero is considered "specified")
			collapseTime = timestampDedupLength;
		}
		query.setCollapseTime(collapseTime);

		// CDXServer#writeCdxResponse translates this into FieldRegexFilter
		if (statusFilter != null && !statusFilter.isEmpty())
			query.setFilter(new String[] { statusFilter });

		return query;
	}
	
	// TODO: move this method to its own class for remote CDX server access.
	// It might be better to model remote CDX Server as a CDXInputSource.
	protected void remoteCdxServerQuery(String urlkey, CDXQuery query,
			AuthToken authToken, CDXToSearchResultWriter resultWriter)
			throws IOException, AccessControlException {
		HTTPSeekableLineReader reader = null;

		// This will throw AccessControlException if blocked
		// (in fact, it throws RuntimeIOException wrapping
		// AccessControlException)
		cdxServer.getAuthChecker().createAccessFilter(authToken)
				.includeUrl(urlkey, query.getUrl());

		CloseableIterator<String> iter = null;

		try {
			StringBuilder sb = new StringBuilder(remoteCdxPath);

			sb.append("?url=");
			sb.append(URLEncoder.encode(query.getUrl(), "UTF-8"));
			//sb.append(query.getUrl());
//			sb.append("&limit=");
//			sb.append(query.getLimit());
			sb.append("&filter=");
			sb.append(URLEncoder.encode(query.getFilter()[0], "UTF-8"));

//			if (!query.getClosest().isEmpty()) {
//				sb.append("&closest=");
//				sb.append(query.getClosest().substring(0, 4));
//			}

			if (query.getCollapseTime() > 0) {
				sb.append("&collapseTime=");
				sb.append(query.getCollapseTime());
			}

			sb.append("&gzip=true");

			String finalUrl = sb.toString();

			reader = this.remoteCdxHttp.get(finalUrl);

			if (remoteAuthCookie != null) {

				String cookie;

				if (authToken.isIgnoreRobots() &&
						(remoteAuthCookieIgnoreRobots != null)) {
					cookie = remoteAuthCookieIgnoreRobots;
				} else {
					cookie = remoteAuthCookie;
				}

				reader.setCookie(PrivTokenAuthChecker.CDX_AUTH_TOKEN + "=" + cookie);
			}

			reader.setSaveErrHeader(HttpCDXWriter.RUNTIME_ERROR_HEADER);

			reader.seekWithMaxRead(0, true, -1);

			iter = createRemoteIter(urlkey, reader);

			resultWriter.begin();

			while (iter.hasNext() && !resultWriter.isAborted()) {
				String rawLine = iter.next();
				CDXLine line = cdxLineFactory.createStandardCDXLine(rawLine,
						StandardCDXLineFactory.cdx11);
				resultWriter.writeLine(line);
			}

			resultWriter.end();
			iter.close();

		} finally {
			if (reader != null) {
				reader.close();
			}
		}
	}

	protected CloseableIterator<String> createRemoteIter(String urlkey,
			HTTPSeekableLineReader reader) throws IOException {

		CloseableIterator<String> iter = new SeekableLineReaderIterator(reader);

		String cacheInfo = reader.getHeaderValue("X-Page-Cache");

		if ((cacheInfo != null) && cacheInfo.equals("HIT")) {
			if (LOGGER.isLoggable(Level.FINE)) {
				LOGGER.fine("CACHED");
			}
		}

		if (extraSource != null) {
			ZipNumParams params = new ZipNumParams();
			CloseableIterator<String> extraIter = extraSource.getCDXIterator(
					urlkey, urlkey, urlkey, params);

			if (extraIter.hasNext()) {
				SortedCompositeIterator<String> sortedIter = new SortedCompositeIterator<String>(
						MultiCDXInputSource.defaultComparator);
				sortedIter.addIterator(iter);
				sortedIter.addIterator(extraIter);
				return sortedIter;
			}
		}

		return iter;
	}

	/**
	 * create {@link CDXWriter} for writing capture search result.
	 * <p>possible future changes:
	 * <ul>
	 * <li>drop unused argument {@code waybackAuthToken}</li>
	 * <li>change return type to super class (as far up as appropriate)</li>
	 * </ul>
	 * </p>
	 * @param wbRequest {@link WaybackRequest} for configuring {@link CDXQuery}
	 * @param waybackAuthToken unused (always {@code null})
	 * @param isFuzzy {@code true} to enable fuzzy query
	 * @return CDXCaptureSearchResultWriter
	 * @deprecated 2015-09-04 use {@link #getCaptureSearchWriter(WaybackRequest)}
	 */
	protected CDXToCaptureSearchResultsWriter getCaptureSearchWriter(
			WaybackRequest wbRequest, AuthToken waybackAuthToken,
			boolean isFuzzy) {
		// return a sentinel object that signifies this method is not overridden.
		return NOT_OVERRIDDEN;
	}
	
	/**
	 * Create {@link CDXWriter} for capturing query result as CaptureSearchResults.
	 * @param wbRequest Wayback request
	 * @return initialized CDXToCaptureSearchResultsWriter
	 */
	protected CDXToCaptureSearchResultsWriter getCaptureSearchWriter(
			WaybackRequest wbRequest) {
		boolean resolveRevisits = wbRequest.isReplayRequest();

		// For now, not using seek single capture to allow for run time checking of additional records  
		//boolean seekSingleCapture = resolveRevisits && wbRequest.isTimestampSearchKey();
		boolean seekSingleCapture = false;
		//boolean seekSingleCapture = resolveRevisits && (wbRequest.isTimestampSearchKey() || (wbRequest.isBestLatestReplayRequest() && !wbRequest.hasMementoAcceptDatetime()));

		CDXToCaptureSearchResultsWriter captureWriter = new CDXToCaptureSearchResultsWriter();
		captureWriter.setResolveRevisits(resolveRevisits);
		captureWriter.setSeekSingleCapture(seekSingleCapture);
		captureWriter.setPreferContains(preferContains);

		captureWriter.setTargetTimestamp(wbRequest.getReplayTimestamp());

		captureWriter.setSelfRedirFilter(selfRedirFilter);

		if ("true".equals(wbRequest.get(REQUEST_REVISIT_LOOKUP))) {
			captureWriter.setIncludeBlockedCaptures(true);
		}

		return captureWriter;
	}
	
	protected CDXQuery createUrlSearchQuery(WaybackRequest wbRequest) {
		final CDXQuery query = new CDXQuery(wbRequest.getRequestUrl());

		query.setCollapse(new String[] { CDXLine.urlkey });
		query.setMatchType(MatchType.prefix);
		query.setShowGroupCount(true);
		query.setShowUniqCount(true);
		query.setLastSkipTimestamp(true);
		query.setFl("urlkey,original,timestamp,endtimestamp,groupcount,uniqcount");

		return query;
	}

	@Deprecated
	protected CDXToSearchResultWriter getUrlSearchWriter(
			WaybackRequest wbRequest) {
		CDXQuery query = createUrlSearchQuery(wbRequest);
		return new CDXToUrlSearchResultWriter(query);
	}
	
	protected static boolean determineGzip(HttpServletRequest request, CDXQuery query) {
		Boolean isGzip = query.isGzip();
		if (isGzip != null) {
			return isGzip;
		}
		String encoding = request.getHeader("Accept-Encoding");
		if (encoding == null) {
			return false;
		}
		return encoding.contains("gzip");
	}
	
	/**
	 * Return CDXWriter for generating textual servlet response (i.e. CDXServer API).
	 * This implementation supports following {@code output} names:
	 * <ul>
	 * <li>{@code link}</li>
	 * <li>{@code memento} (same as {@code link}, but uses different code, currently).</li>
	 * <li>{@code json}</li>
	 * <li>other: generates CDX format response.</li>
	 * </ul>
	 * @param wbRequest Wayback request
	 * @param query CDX query
	 * @return CDXWriter
	 */
	protected CDXWriter getTextCDXWriter(final WaybackRequest wbRequest,
			CDXQuery query, HttpServletRequest request,
			final HttpServletResponse response) throws IOException {
		String format = wbRequest.getMementoTimemapFormat();
		boolean gzip = determineGzip(request, query);
		if (MementoConstants.FORMAT_LINK.equals(format)) {
			boolean resolveRevisits = wbRequest.isReplayRequest();
			boolean seekSingleCapture = false;

			CDXToCaptureSearchResultsWriter captureWriter = new CDXToCaptureSearchResultsWriter() {
				public void end() {
					try {
						MementoUtils.printTimemapResponse(results, wbRequest, response);
					} catch(IOException ex) {
						// probably client hanging up. no need to warn.
						LOGGER.log(Level.INFO, "Error writing memento response.", ex);
					}
				}
			};
			captureWriter.setResolveRevisits(resolveRevisits);
			captureWriter.setSeekSingleCapture(seekSingleCapture);
			captureWriter.setPreferContains(preferContains);
			captureWriter.setTargetTimestamp(wbRequest.getReplayTimestamp());
			captureWriter.setSelfRedirFilter(selfRedirFilter);
			if ("true".equals(wbRequest.get(REQUEST_REVISIT_LOOKUP))) {
				captureWriter.setIncludeBlockedCaptures(true);
			}
			return captureWriter;
		} else if ("memento".equals(format)) {
			return new MementoLinkWriter(request, response, query, gzip);
		} else if ("json".equals(format)) {
			return new JsonWriter(response, gzip);
		} else {
			return new PlainTextWriter(response, gzip);
		}
	}

	@Override
	public boolean renderMementoTimemap(WaybackRequest wbRequest,
			HttpServletRequest request, HttpServletResponse response)
			throws WaybackException, IOException {
		try {
			PerfStats.timeStart(PerfStat.IndexLoad);

			CDXQuery query = new CDXQuery(wbRequest.getRequestUrl());
			query.setOutput(wbRequest.getMementoTimemapFormat());

			String from = wbRequest.get(MementoConstants.PAGE_STARTS);
			if (from != null) {
				query.setFrom(from);
			}

			try {
				query.fill(request);
			} catch (ServletRequestBindingException e1) {
				// Ignore
			}

			CDXWriter cdxWriter = getTextCDXWriter(wbRequest, query, request, response);

			// TODO: need to support the same access control as CDXServer API.
			// (See BaseCDXServer#createAuthToken(). Further refactoring is necessary.
			AuthToken authToken = new APContextAuthToken(wbRequest.getAccessPoint());
			cdxServer.getAuthChecker().authenticate(request, authToken);
			try {
				cdxServer.getCdx(query, authToken, cdxWriter);
			} catch (Exception ex) {
				cdxWriter.serverError(ex);
			}
			cdxWriter.close();
		} finally {
			PerfStats.timeEnd(PerfStat.IndexLoad);
		}

		return true;
	}
	
	// TODO: move this method to separate RequestHandler class
	@Override
	public boolean handleRequest(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) throws ServletException,
			IOException {
		CDXQuery query = new CDXQuery(httpRequest);
		cdxServer.getCdx(httpRequest, httpResponse, query);
		return true;
	}
	
	@Override
	public void shutdown() throws IOException {
		// TODO Auto-generated method stub
	}

	public CDXServer getCdxServer() {
		return cdxServer;
	}

	public void setCdxServer(CDXServer cdxServer) {
		this.cdxServer = cdxServer;
	}

	public int getTimestampDedupLength() {
		return timestampDedupLength;
	}

	/**
	 * The number of digits of timestamp used for culling (<em>deduplicating</em>)
	 * captures in CDX query result.
	 * <p>For example, with this property set to 11, {#query} will
	 * return at most only one captures within each 10 minutes span.</p>
	 * <p>Non-positive value or 14 disables deduplication.</p>
	 * <p>Note: deduplication is done by {@link CDXServer}.
	 * {@code ZipNumIndex} also implements timestamp-deduplication, which
	 * can be turned on by setting positive value to {@code defaultParams.timestampDedupLength}.
	 * It is recommended to leave this off and use this parameter only,
	 * for several reasons:
	 * <ul>
	 * <li>ZipNumIndex's collapsing works at ZipNum block level. It works
	 * only when captures of single URL spans multiple ZipNum blocks, and
	 * can produce unexpected result if ever worked.</li>
	 * <li>ZipNumIndex's collapsing is closed to single index cluster.
	 * it can produce confusing result when multiple index clusters are
	 * combined.</li>
	 * <li>There's no point running timestamp-collapsing more than once
	 * (although the intent of ZipNumIndex's collapsing may be to quickly
	 * cut down on the number of results from URL with many many captures.)</li>
	 * </ul>
	 * <p>Note now it is possible to pass {@code collapseTime} parameter to
	 * {@code EmbeddedCDXServerIndex#query}, and this {@code timestampDedupLength}
	 * parameter serves as a default, used only when {@code collapseTime}
	 * is unspecified.
	 * See {@link WaybackRequest#setCollapseTime(int)}.</p>
	 * @param timestampDedupLength the number of digits of timestamp
	 * used for deduplication.
	 * @see ZipNumParams#setTimestampDedupLength(int)
	 * @see CDXServer#writeCdxResponse
	 * @see WaybackRequest#setCollapseTime(int)
	 */
	public void setTimestampDedupLength(int timestampDedupLength) {
		this.timestampDedupLength = timestampDedupLength;
	}

	public SelfRedirectFilter getSelfRedirFilter() {
		return selfRedirFilter;
	}

	public void setSelfRedirFilter(SelfRedirectFilter selfRedirFilter) {
		this.selfRedirFilter = selfRedirFilter;
	}

	public UrlCanonicalizer getCanonicalizer() {
		return canonicalizer;
	}

	public void setCanonicalizer(UrlCanonicalizer canonicalizer) {
		this.canonicalizer = canonicalizer;
	}

	public int getLimit() {
		return limit;
	}

	public void setLimit(int limit) {
		this.limit = limit;
	}

	@Override
    public void addTimegateHeaders(
    		HttpServletResponse response,
            CaptureSearchResults results,
            WaybackRequest wbRequest,
            boolean includeOriginal) {

		MementoUtils.addTimegateHeaders(response, results, wbRequest, includeOriginal);

		// Add custom JSON header
		CaptureSearchResult result = results.getClosest();

		JSONObject obj = new JSONObject();

		JSONObject closestSnapshot = new JSONObject();

		try {
			obj.put("wb_url", MementoUtils.getMementoPrefix(wbRequest.getAccessPoint()) + wbRequest.getAccessPoint().getUriConverter().makeReplayURI(result.getCaptureTimestamp(), wbRequest.getRequestUrl()));
			obj.put("timestamp", result.getCaptureTimestamp());
			obj.put("status", result.getHttpCode());
			closestSnapshot.put("closest", obj);
		} catch (JSONException je) {

		}
		String json = closestSnapshot.toString();
		json = json.replace("\\/", "/");
		response.setHeader("X-Link-JSON", json);
    }

	public String getRemoteCdxPath() {
		return remoteCdxPath;
	}

	public void setRemoteCdxPath(String remoteCdxPath) {
		this.remoteCdxPath = remoteCdxPath;
	}

	public String getRemoteAuthCookie() {
		return remoteAuthCookie;
	}

	public void setRemoteAuthCookie(String remoteAuthCookie) {
		this.remoteAuthCookie = remoteAuthCookie;
	}
	
	public String getRemoteAuthCookieIgnoreRobots() {
		return remoteAuthCookieIgnoreRobots;
	}

	public void setRemoteAuthCookieIgnoreRobots(String remoteAuthCookieIgnoreRobots) {
		this.remoteAuthCookieIgnoreRobots = remoteAuthCookieIgnoreRobots;
	}

	public HTTPSeekableLineReaderFactory getRemoteCdxHttp() {
		return remoteCdxHttp;
	}

	public void setRemoteCdxHttp(HTTPSeekableLineReaderFactory remoteCdxHttp) {
		this.remoteCdxHttp = remoteCdxHttp;
	}

	public CDXInputSource getExtraSource() {
		return extraSource;
	}

	public void setExtraSource(CDXInputSource extraSource) {
		this.extraSource = extraSource;
	}

	public String getPreferContains() {
		return preferContains;
	}
	/**
	 * substring of {@code filename} field identifying preferred
	 * archive among multiple copies of the same capture.
	 * @param preferContains
	 * @see CDXToCaptureSearchResultsWriter
	 */
	public void setPreferContains(String preferContains) {
		this.preferContains = preferContains;
	}

	public List<String> getIgnoreRobotPaths() {
		return ignoreRobotPaths;
	}

	public void setIgnoreRobotPaths(List<String> ignoreRobotPaths) {
		this.ignoreRobotPaths = ignoreRobotPaths;
	}

	public boolean isTryFuzzyMatch() {
		return tryFuzzyMatch;
	}

	public void setTryFuzzyMatch(boolean tryFuzzyMatch) {
		this.tryFuzzyMatch = tryFuzzyMatch;
	}

	public String getBaseStatusRegexp() {
		return baseStatusRegexp;
	}

	/**
	 * filter on {@code statuscode} field applied by default for <em>interactive</em>
	 * CDX lookup (i.e. from Wayback UI, not via CDX Server API).
	 * <p>Value is a regular expression for status code field. Only those CDXes
	 * with matching statuscode field will be returned. Leading/Trailing spaces are stripped off.
	 * If value starts with "{@code !}",
	 * only CDXes with <em>unmatching</em> statuscode field will be returned
	 * (<em>exception</em>: value "!" is treated as empty string, i.e. no filtering).</p>
	 * <p>Value will be ignored if WybackRequest.isBestLatestReplayRequest is set, for which
	 * hard-coded value "{@code [23]..}" is used.</p>
	 * <p>Default value is "{@code !(500|502|504)}".</p>
	 * <p><strong>NOTE</strong>: this is a quick hack to allow for customizing replay/listing of 5xx captures.
	 * it may be replaced by different customization method, or moved to other class in the
	 * future.</p>
	 * @param baseStatusRegexp regular expression for status code.
	 */
	public void setBaseStatusRegexp(String baseStatusRegexp) {
		this.baseStatusRegexp = baseStatusRegexp;
		this.baseStatusFilter = buildStatusFilter(baseStatusRegexp);
	}

	protected static String buildStatusFilter(String regexp) {
		if (regexp == null)
			return "";
		String re = regexp.trim();
		if (re.isEmpty())
			return "";
		else {
			if (re.charAt(0) == '!') {
				re = re.substring(1).trim();
				if (re.isEmpty())
					return "";
				else
					return "!statuscode:" + re;
			} else {
				return "statuscode:" + re;
			}
		}
	}

}
