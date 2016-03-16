/**
 *
 */
package org.archive.wayback.webapp;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.archive.format.ArchiveFileConstants;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.warc.TestWARCReader;
import org.archive.io.warc.TestWARCRecordInfo;
import org.archive.io.warc.WARCRecord;
import org.archive.wayback.QueryRenderer;
import org.archive.wayback.ReplayDispatcher;
import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.ReplayURIConverter;
import org.archive.wayback.RequestParser;
import org.archive.wayback.ResourceIndex;
import org.archive.wayback.ResourceStore;
import org.archive.wayback.archivalurl.ArchivalUrlReplayURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.FastCaptureSearchResult;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.UrlSearchResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.ResourceNotAvailableException;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.memento.MementoHandler;
import org.archive.wayback.memento.MementoUtils;
import org.archive.wayback.replay.CompositeResource;
import org.archive.wayback.resourcestore.resourcefile.ArcResource;
import org.archive.wayback.resourcestore.resourcefile.WarcResource;
import org.archive.wayback.util.url.KeyMakerUrlCanonicalizer;
import org.archive.wayback.util.webapp.RequestMapper;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.easymock.IArgumentMatcher;

/**
 * unit test for {@link AccessPoint}.
 *
 * TODO: this unit test is too complex. it is because AccessPoint class has too
 * much responsibility and many execution paths. some good refactoring of
 * AccessPoint class would help.
 *
 * @author Kenji Nagahashi
 *
 */
public class AccessPointTest extends TestCase {

	AccessPoint cut;

	// AccessPoint public interface
	// init()
	// handleRequest()
	// queryIndex(WaybackRequest) - actually it's only used internally. it's
	// public just because
	// LiveWebAccessPoint is reusing it. LiveWebAccessPoint also calls
	// getReplay() for rendering
	// resource in accordance with rendering mode configured.
	// shutdown()

	// dependencies
	// selfRedirectCanonicalizer: UrlCanonicalizer
	// filterFactory: CustomResultFilterFactory
	// exclusionFactory: ExclusionFilterFactory
	// uriConverter: ResultURIConverter
	// replay: ReplayDispatcher
	// parser: RequestParser
	// query: QueryRenderer
	// exception: ExceptionRenderer
	// collection: WaybackCollection
	// - resourceStore: ResourceStore
	// - resourceIndex: ResourceIndex
	// configs: Properties
	// liveWebRedirector: LiveWebRedirector
	WaybackCollection collection;
	ResourceStore resourceStore;
	ResourceIndex resourceIndex;

	HttpServletRequest httpRequest;
	HttpServletResponse httpResponse;
	RequestDispatcher requestDispatcher;

	RequestParser parser;
	// ResultURIConverter uriConverter;
	QueryRenderer query;
	ReplayDispatcher replay;

	WaybackRequest wbRequest;

	ReplayRenderer replayRenderer;

	/**
	 * setup HttpServletRequest stubs
	 * @param contextPath servlet context path. typically {@code "/"}
	 * @param uri servlet URI. typically {@code "/"}
	 * @param contextPathPrefix
	 */
	protected void setupRequestStub(String contextPath, String uri,
			String contextPathPrefix) {
		EasyMock.expect(httpRequest.getRequestURI()).andStubReturn(uri);
		EasyMock.expect(httpRequest.getRequestURL()).andStubReturn(
			new StringBuffer(uri));
		// EasyMock.expect(httpRequest.getQueryString()).andReturn(null);
		// remote address test
		// EasyMock.expect(httpRequest.getHeader("X-Forwarded-For")).andReturn(null);
		// Ajax mode test
		// EasyMock.expect(httpRequest.getHeader("X-Requested-With")).andReturn("XMLHttpRequest");

		// used by RequestMapper#getRequestPathPrefix(HttpServletRequest)
		// typical value found in
		// ia-wayback-projects/projects/global-wayback/configs/local/wayback.properties
		// TODO: RequestMapper#getRequestContextPath(HttpServletRequest) assumes
		// value of this
		// attribute ends with "/". RequestMapper has constant declaration for
		// "webapp-request-context-path-prefix", but it's private.
		EasyMock.expect(
			httpRequest.getAttribute("webapp-request-context-path-prefix"))
			.andStubReturn(contextPathPrefix);

		EasyMock.expect(httpRequest.getLocalName()).andStubReturn("localhost");
		// commented out because these are default behavior for stub ("Nice")
		// mock.
		// EasyMock.expect(httpRequest.getAuthType()).andReturn(null).anyTimes();
		// EasyMock.expect(httpRequest.getRemoteUser()).andReturn(null).anyTimes();
		// EasyMock.expect(httpRequest.getHeader(WaybackRequest.REQUEST_AUTHORIZATION)).andReturn(null);
		EasyMock.expect(httpRequest.getLocalPort()).andStubReturn(8080);
		EasyMock.expect(httpRequest.getContextPath()).andStubReturn("/static");
		EasyMock.expect(httpRequest.getLocale()).andStubReturn(
			Locale.CANADA_FRENCH);
		EasyMock.expect(
			httpRequest.getRequestDispatcher(EasyMock.<String>notNull()))
			.andStubReturn(requestDispatcher);
		// EasyMock.expect(httpRequest.getCookies()).andReturn(null).anyTimes();
	}

	// values used in global wayback configuration.
	public static final String WEB_PREFIX = "http://web.archive.org/web/";
	public static final String STATIC_PREFIX = "/static/";

	/*
	 * (non-Javadoc)
	 *
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		super.setUp();
		cut = new AccessPoint();
		setUpAccessPoint();
	}

	protected void setUpAccessPoint() throws Exception {
		cut.setEnablePerfStatsHeader(false);
		cut.setEnableMemento(false);
		cut.setExclusionFactory(null);
		cut.setExactSchemeMatch(false); // default
		cut.setExactHostMatch(false); // default
		cut.setEnableWarcFileHeader(false);
		cut.setReplayPrefix(WEB_PREFIX);
		cut.setQueryPrefix(WEB_PREFIX);
		cut.setStaticPrefix(STATIC_PREFIX);

		KeyMakerUrlCanonicalizer canonicalizer = new KeyMakerUrlCanonicalizer();
		cut.setSelfRedirectCanonicalizer(canonicalizer);

		resourceStore = EasyMock.createMock(ResourceStore.class);
		resourceIndex = EasyMock.createMock(ResourceIndex.class);
		collection = new WaybackCollection();
		collection.setResourceIndex(resourceIndex);
		collection.setResourceStore(resourceStore);
		cut.setCollection(collection);

		// behavior returning null are commented out because EasyMock provides
		// them by default.
		httpRequest = EasyMock.createNiceMock(HttpServletRequest.class);
		httpResponse = EasyMock.createMock(HttpServletResponse.class);
		// AccessPoint calls getWriter() just for committing response headers.
		// Return value
		// does not matter.
		EasyMock.expect(httpResponse.getWriter()).andStubReturn(null);

		// RequestDispatcher - setup expectations, call replay() and verify() if
		// method calls are expected.
		requestDispatcher = EasyMock.createMock(RequestDispatcher.class);
		// Memento mode - only called when enableMemento==true.
		// EasyMock.expect(httpRequest.getHeader(MementoUtils.ACCEPT_DATETIME)).andReturn(null);
		setupRequestStub("/", "/", null);

		// as we mock-ify RequestParser, WaybackRequest can be independent of
		// httpRequest.
		// it suggests HttpServletRequest method calls in setupRequestStub are
		// better be made through
		// RequestParser (TODO)
		// wbRequest = new WaybackRequest();
		parser = EasyMock.createMock(RequestParser.class);
		cut.setParser(parser);
		EasyMock.expect(parser.parse(httpRequest, cut)).andAnswer(
			new IAnswer<WaybackRequest>() {
				@Override
				public WaybackRequest answer() throws Throwable {
					return wbRequest;
				}
			});
		EasyMock.replay(parser);

		query = EasyMock.createMock(QueryRenderer.class);
		cut.setQuery(query);

		replay = EasyMock.createMock(ReplayDispatcher.class);
		cut.setReplay(replay);

		replayRenderer = EasyMock.createMock(ReplayRenderer.class);

		{
			ArchivalUrlReplayURIConverter uc = new ArchivalUrlReplayURIConverter();
			//uc.setReplayURIPrefix("/web/");
			cut.setUriConverter(uc);
			// let it configure uriConverter.
			// assuming init() has no other side effects.
			cut.init();
			assertEquals(WEB_PREFIX, uc.getReplayURIPrefix());
		}

		// disable logging
		Logger.getLogger(ArchivalUrlReplayURIConverter.class.getName())
			.setLevel(Level.WARNING);
		Logger.getLogger(PerfStats.class.getName()).setLevel(Level.WARNING);
	}

	public static Resource createTestHtmlResource(String uri, String timestamp,
			byte[] payloadBytes) throws IOException {
		// default compresssed=true - it often reveals bugs.
		return createTestHtmlResource(uri, timestamp, payloadBytes, true);
	}

	public static Resource createTestHtmlResource(String uri, String timestamp,
			byte[] payloadBytes, boolean compressed) throws IOException {
		TestWARCRecordInfo recinfo = compressed ? TestWARCRecordInfo
			.createCompressedHttpResponse("text/html", payloadBytes)
				: TestWARCRecordInfo.createHttpResponse("text/html",
					payloadBytes);
		recinfo.setCreate14DigitDateFromDT14(timestamp);
		if (uri != null)
			recinfo.setUrl(uri);
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	public static Resource createTestHtmlResource(String timestamp,
			byte[] payloadBytes) throws IOException {
		// by passing null to uri, default "http://test.example.com/" will be
		// used.
		return createTestHtmlResource(null, timestamp, payloadBytes);
	}

	/**
	 * Create a test revisit record referring unknown capture of content-length
	 * {@code len}. This is meant for pathological case. Use
	 * {@link #createTestRevisitResource(String, int, boolean)} for regular case.
	 * @param timestamp 14-digit timestamp of capture
	 * @param len original content-length
	 * @param withHeader {@code false} for omitting HTTP header (simulates old
	 * revisit record).
	 * @return new Resource object.
	 * @throws IOException for unexpected I/O failure while buiding payload
	 */
	public static Resource createTestRevisitResource(String timestamp, int len,
			boolean withHeader) throws IOException {
		TestWARCRecordInfo recinfo = TestWARCRecordInfo
			.createRevisitHttpResponse("text/html", len, withHeader);
		recinfo.setCreate14DigitDateFromDT14(timestamp);
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	/**
	 * Create a test revisit record referring Resource {@code revisited}.
	 * @param timestamp CDX-style 14digit timestamp
	 * @param revisited Capture being revisited (must be a {@link WarcResource}
	 * or {@code ClassCastException} will be the result)
	 * @param withHeader {@code true} unless you want to emulate old implementation
	 * where revisit record had no HTTP headers.
	 * @return new Resource object
	 * @throws IOException for unexpected I/O error building payload
	 */
	public static Resource createTestRevisitResource(String timestamp,
			Resource revisited, boolean withHeader) throws IOException {
		String clen = revisited.getHttpHeaders().get("Content-Length");
		int len = clen != null ? Integer.parseInt(clen) : -1;
		TestWARCRecordInfo recinfo = TestWARCRecordInfo
			.createRevisitHttpResponse("text/html", len, withHeader);
		recinfo.setCreate14DigitDateFromDT14(timestamp);
		ArchiveRecordHeader warcHeader = ((WarcResource)revisited).getWarcHeaders();
		recinfo.addExtraHeader("WARC-Refers-To-Target-URI",
			warcHeader.getUrl());
		recinfo.addExtraHeader("WARC-Refers-To-Date",
			warcHeader.getDate());
		recinfo.setUrl(warcHeader.getUrl());
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	/**
	 * checks if {@code ts} has expected format (YYYYmmddHHMMSS)
	 * @param ts timestamp string to check
	 * @return true if ok, false otherwise
	 */
	protected static boolean validTimestamp(String ts) {
		return ts != null && Pattern.matches("\\d{14}", ts);
	}

	/**
	 * Transform input date to 14-digit timestamp: 2007-08-29T18:00:26Z =>
	 * 20070829180026 (stolen from WARCRecordToSearchResultAdapter - move that
	 * method to ArchiveUtils!)
	 * @param input date text in ISOZ format.
	 * @return date text in DT14 format.
	 */
	private static String transformWARCDate(final String input) {
		StringBuilder output = new StringBuilder(14);
		output.append(input.substring(0, 4));
		output.append(input.substring(5, 7));
		output.append(input.substring(8, 10));
		output.append(input.substring(11, 13));
		output.append(input.substring(14, 16));
		output.append(input.substring(17, 19));
		return output.toString();
	}
	/**
	 * setup mocks with {@code resources}.
	 * <ul>
	 * <li>Call {@link #setupCaptures(ResourceIndex, ResourceStore, int, Resource...)}</li>
	 * <li>Set up {@code replay} so as to return closest from {@code getClosest}.</li>
	 * <li>Set up {@code resourceIndex} so as to return results from {@code query(wbRequest)}</li>
	 * </ul>
	 * <p>
	 * Note: {@link #wbRequest} must be set up before calling this method, or
	 * {@code ResourceIndex} will not return expected search result set.
	 * </p>
	 * @param closestIndex zero-based index into {@code resources}
	 * @param resources resources
	 * @return CaptureSearchResults populated with CaptureSearchResult objects.
	 * @throws Exception
	 */
	protected CaptureSearchResults setupCaptures(int closestIndex,
			Resource... resources) throws Exception {
		CaptureSearchResults results = setupCaptures(resourceIndex, resourceStore, closestIndex, resources);
		CaptureSearchResult closest = results.getClosest();
		if (closest != null) {
			EasyMock.expect(replay.getClosest(wbRequest, results)).andReturn(closest);
		}
		EasyMock.expect(resourceIndex.query(wbRequest)).andReturn(results);
		return results;
	}

	/**
	 * given a sequence of {@link WarcResource}s,
	 * <ul>
	 * <li>build CaptureSearchResults, filled with CaptureSearchResult
	 * instances, which have auto-generated unique filename and offset of 0.
	 * this is necessary for equality (see {@link CaptureSearchResultMatcher}).</li>
	 * <li>setup ResourceStore mock to return Resource for each
	 * CaptureSearchResult.
	 * <li>
	 * <li>if {@code closestIndex} {@code >= 0}, set corresponding
	 * CaptureSearchResult's closest flag.</li>
	 * </ul>
	 * <p>
	 * It's left as caller's responsibility to setup {@link ResourceIndex#query(WaybackRequest)} mock
	 * to return {@code CaptureSearchResults} returned by this method.
	 * </p>
	 * @param resourceIndex ResourceIndex mock
	 * @param resourceStore ResourceStore mock, can be {@code null}
	 * @param closestIndex 0-based index of resource to be marked as
	 *        <i>closest</i>
	 * @param resources sequence of WarcResources
	 * @return CaptureSearchResults populated with CaptureSearchResult objects.
	 * @throws Exception
	 */
	public static CaptureSearchResults setupCaptures(
			ResourceIndex resourceIndex, ResourceStore resourceStore,
			int closestIndex, Resource... resources) throws Exception {
		CaptureSearchResults results = new CaptureSearchResults();
		CaptureSearchResult prev = null;
		for (Resource res : resources) {
			CaptureSearchResult result = new FastCaptureSearchResult();
			if (prev != null) {
				prev.setNextResult(result);
				result.setPrevResult(prev);
			}
			// TODO: Resource should have methods for accessing URI and date
			if (res instanceof WarcResource) {
				// TODO: want to use WARCRecordToSearchResultAdapter?
				// WarcResource
				// has no method to retrieve underlining WARCRecord.
				ArchiveRecordHeader h = ((WarcResource)res).getWarcHeaders();
				String originalUrl = h.getUrl();
				String ts = (String)h.getHeaderValue("WARC-Date");
				// WARC-Date is in ISOZ format.
				ts = transformWARCDate(ts);
				result.setOriginalUrl(originalUrl);
				result.setCaptureTimestamp(ts);
				result.setOffset(0);
				// this is (W)ARC file name in real practice. here we use
				// DT14 timestamp as pseudo filename (.warc.gz suffix is not
				// essential).
				result.setFile(ts + ".warc.gz");
				if (res.getRefersToDate() != null) {
					// getRefersToDate() is supposed to return "yyyyMMddHHmmss"
					String refTimestamp = res.getRefersToDate();
					for (CaptureSearchResult r : results.getResults()) {
						if (r.getCaptureTimestamp().equals(refTimestamp)) {
							result.flagDuplicateDigest(r);
							refTimestamp = null;
							break;
						}
					}
					if (refTimestamp != null) {
						// no original capture found - just flag it
						result.flagDuplicateDigest();
					}
				}
			} else if (res instanceof ArcResource) {
				// TODO: should use ARCRecordToSearchResultAdapter? ArcResource
				// has getArcRecord() methods whose result may be cast to ARCRecord.
				// NB: ArcResource#getARCMetadata() creates a new Map object.
				Map<String, String> meta = ((ArcResource)res).getARCMetadata();
				String originalUrl = meta
					.get(ArchiveFileConstants.URL_FIELD_KEY);
				String ts = meta.get(ArchiveFileConstants.DATE_FIELD_KEY);
				result.setOriginalUrl(originalUrl);
				result.setCaptureTimestamp(ts);
			} else {
				throw new AssertionError("unexpected Resource type: " +
						res.getClass());
			}
			result.setHttpCode(Integer.toString(res.getStatusCode()));
			// CaptureSearchResultMatcher fails without this, but actual value
			// does not matter. so set it to 0.
			result.setOffset(0);
			assertTrue("invalid timestamp " + result.getCaptureTimestamp(),
				validTimestamp(result.getCaptureTimestamp()));
			if (closestIndex == 0) {
				result.setClosest(true);
				results.setClosest(result);
			}

			if (resourceStore != null) {
				// Note AccessPoint passes a copy of CaptureSearchResult in some
				// case (ex. Replay_Revisit() test).
				// so we need to use custom argument matcher.
				EasyMock
					.expect(
						resourceStore
							.retrieveResource(eqCaptureSearchResult(result)))
					.andReturn(res).anyTimes();
			}

			results.addSearchResult(result);
			--closestIndex;
		}
		return results;
	}

	// REFACTORING THOUGHTS: WaybackRequest.setReplayRequest() could take
	// requestUrl and replayTimestamp it is semantically more clear.
	/**
	 * create new WaybackRequest set up as replay request for {@code requestUrl}
	 * at {@code replayTimestamp}. created object is set to #wbRequest.
	 * @param wbRequest
	 * @param requestUrl
	 * @param replayTimestamp
	 */
	public void setReplayRequest(String requestUrl, String replayTimestamp) {
		wbRequest = new WaybackRequest();
		wbRequest.setReplayRequest();
		wbRequest.setRequestUrl(requestUrl);
		wbRequest.setReplayTimestamp(replayTimestamp);
	}

	/**
	 * Setup expectation that {@code capture} is rendered.
	 * <p>
	 * @param capture CaptureSearchResult to be rendered
	 * @param headersResource Resource from which HTTP headers are read
	 *        (revisit)
	 * @param payloadResource Resource from which HTTP payload is read
	 *        (revisited)
	 * @param results capture search results from which {@code capture} is
	 *        picked
	 * @throws WaybackException
	 * @throws IOException
	 * @throws ServletException
	 */
	protected void expectRendering(CaptureSearchResult capture,
			Resource headersResource, Resource payloadResource,
			CaptureSearchResults results) throws ServletException, IOException,
			WaybackException {
		if (headersResource == payloadResource) {
			EasyMock.expect(
				replay.getRenderer(wbRequest, capture, headersResource))
				.andReturn(replayRenderer);
			replayRenderer.renderResource(httpRequest, httpResponse, wbRequest,
				capture, headersResource, cut.getUriConverter(), results);
		} else {
			EasyMock.expect(
				replay.getRenderer(EasyMock.same(wbRequest),
					EasyMock.same(capture),
					EasyMock.isA(CompositeResource.class))).andReturn(
				replayRenderer);
			replayRenderer.renderResource(EasyMock.same(httpRequest),
				EasyMock.same(httpResponse), EasyMock.same(wbRequest),
				EasyMock.same(capture), EasyMock.isA(CompositeResource.class),
				EasyMock.same(cut.getUriConverter()), EasyMock.same(results));
		}
	}

	/**
	 * Setup expectation that {@code handleReplay} redirects to
	 * {@code expectedRedirectURI}.
	 * @param expectedRedirectURI
	 */
	protected void expectRedirect(String expectedRedirectURI) {
		httpResponse.setHeader("Location", expectedRedirectURI);
		httpResponse.setStatus(302);
	}

	/**
	 * setup expected call to {@link ResourceIndex#query(WaybackRequest)},
	 * returning empty {@link UrlSearchResults}.
	 * <p>
	 * This is sufficient for most cases, as AccessPoint is not concerned with
	 * UrlSearchResult, but simply passes it to query renderer.
	 * </p>
	 * <p>
	 * Note: set up {@link #wbRequest} before calling this method.
	 * </p>
	 * @throws Exception declared, but will never be thrown
	 */
	protected void expectUrlIndexQuery() throws Exception {
		UrlSearchResults results = new UrlSearchResults();
		EasyMock.expect(resourceIndex.query(wbRequest)).andReturn(results);
		query.renderUrlResults(httpRequest, httpResponse, wbRequest, results,
			cut.getUriConverter());
	}

	/**
	 * test basic behavior 1.
	 * <ul>
	 * <li>no authorization</li>
	 * <li>no exclusion factory</li>
	 * </ul>
	 * and,
	 * <ul>
	 * <li>
	 * <li>there's no capture on date matching the request.</li>
	 * <li>closest capture is not a revisit</li>
	 * </li> this shall result in redirect (302) response to the URL with
	 * closest capture date in date component.
	 *
	 * alternative path: {@link #testBounceToReplayPrefix()}
	 * @throws Exception
	 */
	public void testHandleRequest_Replay_1() throws Exception {
		// make sure wbRequesat.requestUrl, replayTimestamp are set up.
		setReplayRequest("http://www.example.com/", "20100601123456");

		// TODO: originalUrl can be different from wbRequst.requestUrl, and it will
		// be reflected to redirect URL (worth testing).
		// CaptureSearchResults results = createCaptureSearchResults(
		// "20100601000000", "http://www.example.com/", "200");
		// CaptureSearchResult closest = results.getClosest();
		// TODO: this can be different from wbRequst.requestUrl, and it will be
		// reflected to redirect URL.
		// closest.setOriginalUrl("http://www.example.com/");
		// closest.setHttpCode("200");
		// closest.captureTimestamp != wbRequest.replayTimestamp
		// closest.setCaptureTimestamp("20100601000000");
		// Resource below has originalUrl="http://test.example.com/", which is
		// different from
		// wbRequest.requestUrl above. originalUrl shall be reflected to
		// resultant redirect URL.
		@SuppressWarnings("unused")
		CaptureSearchResults results = setupCaptures(
			0,
			createTestHtmlResource("20100601000000",
				"hogheogehoge\n".getBytes("UTF-8")));
		// handleRequest()
		// calls handleReplay()
		// - calls checkInterstitialRedirect()
		// - calls selfRedirectCanonicalizer.urlStringToKey(requestURL) if
		//   non-null
		// - calls queryIndex(), which calls
		//   collection.resourceIndex.query(wbRequest)
		// which in turn returns results above (setup in setupCaptures(...))
		// - calls replay.getClosest()
		// - calls checkAnchorWindow()
		// - calls getResource(closest, skipFiles), which
		// - first checks if closest is in skipFiles (and throws
		// ResourceNotAvailableException if it is),
		// - then calls collection.resourceStore.retrieveResource(closest),
		// which returns Resource above.

		// when closest's timestamp is different from replay requests's
		// timestamp, it redirects to closest's timestamp.
		expectRedirect("/web/20100601000000/http://test.example.com/");

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);
	}

	/**
	 * basic replay test part 2. there's a capture whose capture date matches
	 * the request.
	 * @throws Exception
	 */
	public void testHandleRequest_Replay_2() throws Exception {
		// make sure wbRequesat.requestUrl, replayTimestamp are set up.
		setReplayRequest("http://test.example.com/", "20100601000000");

		// there's capture with timestamp exactly requested for.
		Resource payloadResource = createTestHtmlResource("20100601000000",
			"hogheogehoge\n".getBytes("UTF-8"));
		CaptureSearchResults results = setupCaptures(0, payloadResource);
		CaptureSearchResult closest = results.getClosest();

		expectRendering(closest, payloadResource, payloadResource, results);

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);
	}

	/**
	 * test CaptureSearchResult equality by file and offset.
	 */
	public static class CaptureSearchResultMatcher implements IArgumentMatcher {
		private CaptureSearchResult expected;

		public CaptureSearchResultMatcher(CaptureSearchResult expected) {
			this.expected = expected;
		}

		@Override
		public boolean matches(Object actual) {
			// CaptureSearchResult is compared by file name and offset. this is
			// how AccessPoint#retrievePayloadForIdenticalContentRevisit(...)
			// retrieves previous capture.
			// TODO: this could be defined as CaptureSearchResult#equals(Object).
			if (!(actual instanceof CaptureSearchResult))
				return false;
			String file = ((CaptureSearchResult)actual).getFile();
			long offset = ((CaptureSearchResult)actual).getOffset();
			if (expected.getOffset() != offset)
				return false;
			return file == null ? expected.getFile() == null : file
				.equals(expected.getFile());
		}

		@Override
		public void appendTo(StringBuffer buffer) {
			buffer.append("eqCaptureSearchResult(");
			buffer.append(expected.getFile());
			buffer.append(",");
			buffer.append(expected.getOffset());
			buffer.append(")");
		}

	}

	public static CaptureSearchResult eqCaptureSearchResult(
			CaptureSearchResult expected) {
		EasyMock.reportMatcher(new CaptureSearchResultMatcher(expected));
		return null;
	}

	public static class CaptureSearchMatcher implements IArgumentMatcher {
		private String url;
		private String replayTimestamp;

		public CaptureSearchMatcher(String url, String replayTimestamp) {
			this.url = url;
			this.replayTimestamp = replayTimestamp;
		}

		@Override
		public boolean matches(Object actual) {
			if (!(actual instanceof WaybackRequest))
				return false;
			WaybackRequest wbRequest = (WaybackRequest)actual;
			String replayTimestamp = wbRequest.getReplayTimestamp();
			String url = wbRequest.getRequestUrl();
			if (url == null || replayTimestamp == null)
				return false;
			if (this.url == null || this.replayTimestamp == null)
				return false;
			// Only exact match is supported. i.e. http://example.com/ and
			// http://example.com are different even though they typically
			// get canonicalized into the same string.
			// Also not checking if wbRequest is in fact a capture search
			// request.
			return this.url.equals(url) &&
					this.replayTimestamp.equals(replayTimestamp);
		}

		@Override
		public void appendTo(StringBuffer buffer) {
			buffer.append("eqCaptureSearchRequest(");
			buffer.append(url).append(",").append(replayTimestamp);
			buffer.append(")");
		}
	}

	public static WaybackRequest eqCaptureSearchRequest(String url,
			String replayTimestamp) {
		EasyMock.reportMatcher(new CaptureSearchMatcher(url, replayTimestamp));
		return null;
	}

	/**
	 * test of revisit. closest capture is a revisit.
	 * @throws Exception
	 */
	public void testHandleRequest_Replay_Revisit() throws Exception {
		setReplayRequest("http://www.example.com/", "20100601000000");
		// closest SearchResult has isDuplicateDigest() == true.
		byte[] payload = "hogehogehogehoge\n".getBytes("UTF-8");
		Resource payloadResource = createTestHtmlResource("20100501000001",
			payload);
		Resource headerResource = createTestRevisitResource("20100601000000",
			payloadResource, true);
		CaptureSearchResults results = setupCaptures(1, payloadResource,
			headerResource);

		CaptureSearchResult previous = results.getResults().get(0);
		CaptureSearchResult closest = results.getClosest();
		assertTrue(closest.isDuplicateDigest());
		assertTrue(closest.getDuplicatePayloadFile() != null);
		assertTrue(closest.getDuplicatePayloadOffset() != null);

		expectRendering(closest, headerResource, payloadResource, results);

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);

		// TODO: failure case: closest.duplicatePayloadFile != null ->
		// ResourceNotAvailableException
		// TODO: failure case: self-redirecting -> calls finxNextClosest() and
		// fails if there's no more closest.
		// wbRequest.timestampSearchKey == true -> calls queryIndex() once
		// again.
	}

	/**
	 * Test of internal behavior. If loading recording from an archive failed,
	 * AccessPoint shall not attempt to load the same archive again within the
	 * request for performance reasons.
	 * @throws Exception
	 */
	public void testHandleReplay_noMultipleErrors() throws Exception {
		setReplayRequest("http://www.example.com/", "20100601000000");
		byte[] payload = "payload".getBytes("UTF-8");
		Resource resource0 = createTestHtmlResource("20100428000000", payload);
		Resource resource1 = createTestHtmlResource("20100501000000", payload);
		Resource revisit1 = createTestRevisitResource("20100515000000",
			resource1, true);
		Resource revisit2 = createTestRevisitResource("20100601000000",
			resource1, true);
		CaptureSearchResults results = setupCaptures(3, resource0, resource1,
			revisit1, revisit2);
		List<CaptureSearchResult> captures = results.getResults();
		// replace ResourceStore mock with a strict one that throws exception
		// for 20100501000000 capture.
		collection.setResourceStore(resourceStore = EasyMock
			.createMock(ResourceStore.class));
		CaptureSearchResult capture1 = captures.get(1);
		// details == filename is a requirement of old code.
		ResourceNotAvailableException rnae = new ResourceNotAvailableException(
			"mocked load failure", capture1.getFile());
		// point is, retrieveResource() shall not be called while checking
		// captures 20100515 and 20100501
		EasyMock
			.expect(
				resourceStore.retrieveResource(eqCaptureSearchResult(capture1)))
			.andThrow(rnae).once();
		EasyMock.expect(
			resourceStore.retrieveResource(eqCaptureSearchResult(captures
				.get(0)))).andReturn(resource0);
		// whether retrieveResource() is called for these revisit captures are
		// non-essential (they will be,
		// but it may change)
		captures.get(2).flagDuplicateDigest(capture1);
		EasyMock.expect(
			resourceStore.retrieveResource(eqCaptureSearchResult(captures
				.get(2)))).andStubReturn(revisit1);
		captures.get(3).flagDuplicateDigest(capture1);
		EasyMock.expect(
			resourceStore.retrieveResource(eqCaptureSearchResult(captures
				.get(3)))).andStubReturn(revisit2);

		final String expectedRedirectURI = "/web/20100428000000/http://test.example.com/";
		expectRedirect(expectedRedirectURI);

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);
		// default 0 makes AccessPoint give up on the first
		// ResourceNotAvailableException on
		// 20100501000000 capture. here we want it to try 20100428000000 and
		// succeed.
		cut.setMaxRedirectAttempts(10);
		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);
	}

	public static Resource createSelfRedirectResource(String url,
			String timestamp) throws IOException {
		assert !url.endsWith("/");
		// typical redirect: http://example.com to http://example.com/
		String location = url + "/";
		TestWARCRecordInfo recinfo = new TestWARCRecordInfo(
			TestWARCRecordInfo.buildHttpRedirectResponseBlock(
				"301 Moved Permanently", location));
		recinfo.setUrl(url);
		recinfo.setCreate14DigitDateFromDT14(timestamp);
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	/**
	 * {@code handleReplay()} is supposed to throw
	 * {@code ResourceNotAvailableException} when it cannot find a replay-able
	 * capture for a request. This is a test for one of such "capture not found"
	 * case: revisited record cannot found in the capture search results, and
	 * only other non-revisit resource available is a self-redirect. This is
	 * rather a corner case. handlReplay() shall throw
	 * {@code ResourceNotAvailableException}.
	 * @throws Exception
	 */
	public void testHandleRequest_MissingRevisitPayload() throws Exception {
		setReplayRequest("http://example.com", "20140619004054");
		// resource revisited, but missing in capture search result
		Resource revisited = createSelfRedirectResource("http://example.com",
			"20140619015411");
		CaptureSearchResults results = setupCaptures(0,
			createSelfRedirectResource("http://example.com", "20140619004054"),
			createTestRevisitResource("20140619016511", revisited, true));
		CaptureSearchResult revisit = results.getResults().get(1);
		revisit.flagDuplicateDigest(); // revisit, but original is not found.

		// expectation:
		// 1. first capture is skipped because it is self-redirect. selects the
		//    second.
		// 2. second capture is a revisit, calls resourceIndex.query() for the
		//    revisited, but original capture is not found in the result.
		// 3. ResourceNotAvailableException is thrown
		// 4. exception captured, skip to the next capture and finds none.
		// 5. ResourceNotAvailableException is thrown out of handleReplay
		// 6. ExceptionRenderer.renderException is called (in handleQuery)

		// XXX setting these up manually feels very fragile - perhaps we need a
		// test ResourceIndex + ReplayDispatcher.
		EasyMock.expect(
			resourceIndex.query(eqCaptureSearchRequest("http://example.com",
				"20140619015411"))).andReturn(results);
		EasyMock.expect(
			replay.getClosest(
				eqCaptureSearchRequest("http://example.com", "20140619015411"),
				EasyMock.same(results))).andReturn(results.getResults().get(0));

		// for this test, it is easier to test handleReplay, not handleQuery

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		try {
			cut.handleReplay(wbRequest, httpRequest, httpResponse);
			fail("handleReplay did not throw ResourceNotAvailableException");
		} catch (ResourceNotAvailableException ex) {
			// expected.
		}

		EasyMock.verify(resourceIndex, resourceStore, replay);
	}

	/**
	 * old-style WARC revisit (no HTTP status line and header, Content-Length in
	 * WARC header is zero). it shall replay HTTP status line, headers and
	 * content from previous matching capture.
	 * @throws Exception
	 */
	public void testHandleRequest_Replay_OldWARCRevisit() throws Exception {
		// TODO - it'd be better to define an interface in Resource class so
		// that AccessPoint needs not to have separate execution path for this.
		// that's easier to test.
	}

	/**
	 * old-style ARC revisit (no mimetype, filename is '-')
	 * @throws Exception
	 */
	public void testHandleRequest_Replay_OldARCRevisit() throws Exception {
		// ditto - see TODO comment above.
	}

	public static final Resource createTest502Resource() throws IOException {
		byte[] failPayload = "failed\n".getBytes("UTF-8");
		byte[] content = TestWARCRecordInfo.buildHttpResponseBlock(
			"502 Bad Gateway", "text/plain", failPayload);

		TestWARCRecordInfo recinfo = new TestWARCRecordInfo(content);
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	/**
	 * if closest is not HTTP-success AND replaying embedded context (CSS,
	 * JavaScript, images, etc.), use next closest with successful response, or
	 * for lower priority, a redirect, instead. unless such capture is of the
	 * same timestamp as the replay request, redirect to the capture found.
	 * @throws Exception
	 */
	public void testHandleRequest_Replay_Embedded() throws Exception {
		// request timestamp is different from 'previous' below. it makes
		// handleRequest return redirect. in this case, Resource for 'previous'
		// will not be retrieved.
		setReplayRequest("http://test.example.com/style.css", "20100601000000");
		// if closest is not HTTP-success,
		// to have isAnyEmbeddedContext() return true - any of cSSContext,
		// iMGContext, jSContext
		// frameWrapperContext, iFrameWrapperContext, objectEmbedContext has the
		// same effect.
		wbRequest.setCSSContext(true);
		assertTrue(wbRequest.isAnyEmbeddedContext());

		CaptureSearchResults results = setupCaptures(
			1,
			createTestHtmlResource("http://test.example.com/style.css",
				"20100501000000", "hogheogehoge\n".getBytes("UTF-8")),
			createTest502Resource());
		CaptureSearchResult closest = results.getClosest();
		assertTrue(closest.isHttpError());

		// or wbRequest.setBestLatestReplayRequest();
		final String expectedRedirectURI = "/web/20100501000000cs_/http://test.example.com/style.css";
		expectRedirect(expectedRedirectURI);
		// TODO: extraHeaders expectations?

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, query, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);
		// handleReplay throws BetterRequestException and handled inside
		// handleRequest(). exception will not be thrown out of handleRequest().
		EasyMock.verify(httpResponse, resourceIndex, resourceStore, query,
			replay);
		assertTrue("handleRequest return value", r);
	}

	// REFACTORING THOUGHTS: WaybackRequet.setUrlCaptureQueryRequest() could
	// take requestUrl and replayTimestamp.
	/**
	 * create new WaybackRequest set up as capture query request for URL
	 * {@code requestUrl}, at time {@ode replayTimestamp}.
	 * @param requestUrl
	 * @param replayTimestamp
	 */
	public final void setCaptureQueryRequest(String requestUrl,
			String replayTimestamp) {
		wbRequest = new WaybackRequest();
		wbRequest.setCaptureQueryRequest();
		wbRequest.setRequestUrl(requestUrl);
		wbRequest.setReplayTimestamp(replayTimestamp);
	}

	// REFACTORING THOUGHTS: query rendering could be done in the same mechanism
	// as replay rendering.
	// there's no particular reason CaptureSearchResults rendering and
	// UrlSearchResults rendering must be implemented in the same class.
	// they share nothing.
	// ReplayRenderer and QueryRenderer may be unified by passing UIResults
	// instead of (CaptureSearchResult, Resource, CaptureSearchResults) for
	// ReplayRenderer, and (CaptureSearchResults / UrlSearchResults) for
	// QueryRenderer.
	// this way, query.Renderer could be replaced by generic "variant dispatcher"
	// class that dispatches rendering to different JSPs depending on the type of
	// output (HTML or XML).

	public void testHandleRequest_CaptureSearchResults() throws Exception {
		setCaptureQueryRequest("http://www.example.com/", "20100601123456");

		// handleRequest()
		// redirect to queryPrefix + translateRequestPathQuery(httpRequest)
		// if bounceToQueryPrefix is true (not tested here)
		// copies exactHostMatch to wbRequest.exactHost (TODO: should be done by
		// parser?)
		// calls handleQuery()
		// - calls queryIndex(), which calls collection.resourceIndex.query(),
		// which returns CaptureSearchResults
		// (unexpected object from queryIndex() results in
		// WaybackException("Unknown index format").
		// this is considered to be a programming/configuration error. not
		// tested.)
		CaptureSearchResults results = new CaptureSearchResults();
		CaptureSearchResult result = new CaptureSearchResult();
		results.setClosest(result);
		EasyMock.expect(resourceIndex.query(wbRequest)).andReturn(results);
		// - calls MementoUtils.printTimemapResponse(results, wbRequest,
		// httpResponse) instead
		// if wbRequst.isMementoTimemapRequest() (N/A here) (TODO: can we move
		// this to QueryRenderer implementation?)
		// - calls query.renderCaptureResults(...)
		query.renderCaptureResults(httpRequest, httpResponse, wbRequest,
			results, cut.getUriConverter());

		EasyMock.replay(httpRequest, httpResponse, resourceIndex, query);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(query);

		// result shall have closest flag set (FIrefox proxy plugin expects
		// this)
		assertTrue("closest flag", result.isClosest());

		assertTrue("handleRequest return value", r);

	}

	// REFACTORING THOUGHTS: WaybackRequet.setUrlQueryRequest() could take
	// requestUrl and replayTimestamp.
	/**
	 * create new WaybackRequest set up as URL query request for URL
	 * {@code requestUrl} at time {@code replayTimestamp}. created object is set
	 * to {@link #wbRequest}.
	 * @param requestUrl
	 * @param replayTimestamp
	 */
	public void setUrlQueryRequest(String requestUrl, String replayTimestamp) {
		wbRequest = new WaybackRequest();
		wbRequest.setUrlQueryRequest();
		wbRequest.setRequestUrl(requestUrl);
		wbRequest.setReplayTimestamp(replayTimestamp);
	}

	public void testHandleRequest_UrlSearchResults() throws Exception {
		setUrlQueryRequest("http://www.example.com/", "20100601123456");

		// AccessPoint is not concerned of the details of UrlSearchResults. it
		// just forwards the request to QueryRenderer. so we leave it uninitialized
		// here.
		UrlSearchResults results = new UrlSearchResults();
		EasyMock.expect(resourceIndex.query(wbRequest)).andReturn(results);

		// EXPECTATION: AccessPoint.handleQuery() calls
		// query.renderUrlResults().
		query.renderUrlResults(httpRequest, httpResponse, wbRequest, results,
			cut.getUriConverter());

		EasyMock.replay(httpRequest, httpResponse, query, resourceIndex);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(query, resourceIndex);
		assertTrue("handleRequest return value", r);
	}

	// tests for collapseTime parameter. for query requests (both capture and
	// URL),
	// AccessPoint passes its collapseTime parameter to ResourceIndex#query via
	// WaybackRequest.collapseTime. for replay request, it doesn't.

	public void testHandleRequest_queryCollapseTimeUnspecified()
			throws Exception {
		cut.setQueryCollapseTime(-1);
		setUrlQueryRequest("http://www.example.com/", "20100601123456");

		expectUrlIndexQuery();

		EasyMock.replay(httpRequest, httpResponse, query, resourceIndex);

		cut.init();
		boolean handled = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(query, resourceIndex);
		assertTrue("handleRequest return value", handled);

		assertEquals(-1, wbRequest.getCollapseTime());
	}

	public void testHandleRequest_queryCollapseTimeSpecified() throws Exception {
		cut.setQueryCollapseTime(10);
		setUrlQueryRequest("http://www.example.com/", "20100601123456");

		expectUrlIndexQuery();

		EasyMock.replay(httpRequest, httpResponse, query, resourceIndex);

		cut.init();
		boolean handled = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(query, resourceIndex);
		assertTrue("handleRequest return value", handled);

		assertEquals(10, wbRequest.getCollapseTime());
	}

	public void testHandlerRequest_queryCollapseTimeForReplayQuery()
			throws Exception {
		cut.setQueryCollapseTime(10);
		// query parameters and CaptureSeachResults details are irrelevant to
		// this test.
		setReplayRequest("http://test.example.com/", "20100601123456");
		setupCaptures(
			0,
			createTestHtmlResource("20100601000000",
				"hogheogehoge\n".getBytes("UTF-8")));

		expectRedirect("/web/20100601000000/http://test.example.com/");

		EasyMock.replay(httpRequest, httpResponse, query, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean handled = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(query, resourceIndex);
		assertTrue("handleRequest return value", handled);

		assertEquals(-1, wbRequest.getCollapseTime());

	}

	/**
	 * if bounceToReplayPrefix is true, replay request is redirected to other
	 * access point.
	 * @throws Exception
	 */
	public void testBounceToReplayPrefix() throws Exception {
		final String URL = "http://www.example.com/";
		final String TIMESTAMP = "20100601123456";

		setReplayRequest(URL, TIMESTAMP);

		EasyMock.reset(httpRequest);
		EasyMock.expect(httpRequest.getRequestURI()).andStubReturn(
			"/" + TIMESTAMP + "/" + URL);
		EasyMock.expect(httpRequest.getLocalName()).andStubReturn("localhost");
		EasyMock.expect(httpRequest.getLocalPort()).andStubReturn(8080);
		EasyMock.expect(httpRequest.getContextPath()).andStubReturn("/");
		EasyMock.expect(httpRequest.getLocale()).andStubReturn(
			Locale.CANADA_FRENCH);

		final String replayPrefix = "http://test.archive.org/";
		cut.setBounceToReplayPrefix(true);
		cut.setReplayPrefix(replayPrefix);

		final String suffix = "/" + TIMESTAMP + "/" + URL;
		httpResponse.sendRedirect(replayPrefix + suffix);

		EasyMock.replay(httpRequest, httpResponse);

		cut.handleRequest(httpRequest, httpResponse);

	}

	// TODO: the way AccessPoint is reused for rendering static resource looks
	// inefficient.
	// bounceToReplayPrefix and bounceToQueryPrefix are always configured in
	// pair, and they are set to true only for static resource AccessPoint.

	/**
	 * static AccessPoint - configured with
	 * <ul>
	 * <li>accessPointPath=<code>${wayback.staticPrefix}</code></li>
	 * <li>serveStatic=true</li>
	 * <li>bounceToReplayPrefix=true</li>
	 * <li>bounceToQueryPrefix=true</li>
	 * </ul>
	 * when {@link RequestParser#parse(HttpServletRequest, AccessPoint)} returns
	 * null, request is forwarded to dispatchLocal() for rendering static
	 * resources.
	 * @throws Exception
	 */
	public void testDispatchLocal() throws Exception {
		// first reset the mock for overriding getAttribute(), getRequestURI(),
		// and getRequestURL()
		EasyMock.reset(httpRequest);
		EasyMock.expect(httpRequest.getLocalName()).andStubReturn("localhost");
		EasyMock.expect(httpRequest.getLocalPort()).andStubReturn(8080);
		EasyMock.expect(httpRequest.getContextPath()).andStubReturn("/static");
		EasyMock.expect(httpRequest.getLocale()).andStubReturn(
			Locale.CANADA_FRENCH);
		EasyMock.expect(
			httpRequest.getRequestDispatcher(EasyMock.<String>notNull()))
			.andStubReturn(requestDispatcher);

		// used by RequestMapper#getRequestPathPrefix(HttpServletRequest)
		// typical value found in
		// ia-wayback-projects/projects/global-wayback/configs/local/wayback.properties
		// TODO: RequestMapper#getRequestContextPath(HttpServletRequest) assumes
		// value of this
		// attribute ends with "/". RequestMapper has constant declaration for
		// "webapp-request-context-path-prefix", but it's private.
		EasyMock.expect(
			httpRequest.getAttribute("webapp-request-context-path-prefix"))
			.andStubReturn("/static/");
		// override getRequestURI() behavior
		EasyMock.expect(httpRequest.getRequestURI()).andStubReturn(
			"/static/aaa.css");
		EasyMock.expect(httpRequest.getRequestURL()).andStubReturn(
			new StringBuffer("/static/aaa.css"));

		// reconfigure RequestParser to return null, which signifies that
		// there's no dynamic handler and the request shall be mapped to local
		// static resource. (AccessPoint#dispatchLocal(HttpServletRequest))
		EasyMock.reset(parser);
		EasyMock.expect(parser.parse(httpRequest, cut)).andReturn(null);

		// AccessPoint#dispatchLocal() checks existence of the file if
		// ServletContext#getRealPath()
		// returns non-null value for translated request path. have it skip the
		// test by returning null. otherwise dispatchLocal() will fail.
		ServletContext servletContext = EasyMock
			.createMock(ServletContext.class);
		EasyMock.expect(servletContext.getRealPath(EasyMock.<String>notNull()))
			.andStubReturn(null);
		cut.setServletContext(servletContext);

		// Expectation: AccessPoint#dispatchLocal() eventually calls
		// RequestDispatcher#forward(...)
		requestDispatcher.forward(httpRequest, httpResponse);

		EasyMock.replay(httpRequest, parser, servletContext, requestDispatcher);

		assertEquals("aaa.css",
			RequestMapper.getRequestContextPath(httpRequest));

		// AccessPoint#dispatchLocal() returns immediately if serveStatis is
		// false.
		cut.setServeStatic(true);
		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(parser, requestDispatcher);

		assertTrue("handleRequest return value", r);

	}

	// *** Memento Tests ***

	// REFACTORING THOUGHTS: Memento annotations (adding response headers) could
	// be implemented as decorator of replay renderer. because of this possibility,
	// I separated out tests for memento headers here.

	/**
	 * test of Memento-furnished response to URL-M (Memento). Memento
	 * Specification states that URL-M response
	 * <ul>
	 * <li>MUST NOT have "Vary: accept-datetime"</li>
	 * <li>MUST have "Memento-Datetime"</li>
	 * <li>MUST have "Link" header with at least a URI-R as "original" relation.
	 * </li>
	 * </ul>
	 * @throws Exception
	 */
	public void testMemento_replay_exactCapture() throws Exception {
		final String AGGREGATION_PREFIX = "";//"http://web.archive.org";

		cut.setEnableMemento(true);
		cut.setConfigs(new Properties());
//		cut.getConfigs().setProperty(MementoUtils.AGGREGATION_PREFIX_CONFIG,
//			AGGREGATION_PREFIX);

		// make sure wbRequesat.requestUrl, replayTimestamp are set up.
		setReplayRequest("http://www.example.com/", "20100601000000");
		assertFalse(wbRequest.isMementoTimegate());

		Resource payloadResource = createTestHtmlResource("20100601000000",
			"hogehogehogehoge\n".getBytes("UTF-8"));
		CaptureSearchResults results = setupCaptures(0, payloadResource);
		CaptureSearchResult closest = results.getClosest();

		// when closest's timestamp == request's timestamp,
		// it gets ReplayRenderer with replay.getRenderer(wbRequest, closest,
		// httpHeaderResource, payloadResource),
		// and calls renderResource() on it.
		expectRendering(closest, payloadResource, payloadResource, results);

		// key expectations of this test
		// called through MementoUtils.addMementoHeaders(...)
		// NO Vary: accept-datetime header.
		final String expectedMementoDateTime = "Tue, 01 Jun 2010 00:00:00 GMT";
		httpResponse.setHeader(MementoUtils.MEMENTO_DATETIME,
			expectedMementoDateTime);
		// Wayback include timemap, timegate, first and last memento links in
		// addition to mandatory "original" link.
		// TODO: actually it is acceptable to have various rels in different
		// order. It'd take custom argument matcher.
		final String expectedMementoLink = String
			.format(
				"<%1$s>; rel=\"original\", "
						+ "<%2$s%3$stimemap/link/%1$s>; rel=\"timemap\"; type=\"application/link-format\", "
						+ "<%2$s%3$s%1$s>; rel=\"timegate\", "
						+ "<%2$s%3$s%4$s/%1$s>; rel=\"first last memento\"; datetime=\"%5$s\"",
				"http://www.example.com/", AGGREGATION_PREFIX, WEB_PREFIX,
				"20100601000000", expectedMementoDateTime);
		httpResponse.setHeader(MementoUtils.LINK, expectedMementoLink);

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);
	}

	/**
	 * Test of custom request-sensitive replay URL construction.
	 * {@code decorateURIConverter} method is overridden in AccessPoint subclass,
	 * whose return value is passed to {@code renderResource}.
	 * @throws Exception
	 */
	public void testDecorateURIConverter() throws Exception {
		// No method will be invoked as ReplayRenderer is mocked. We just check if
		// replayRendeer.renderResource method is called with this instance as uriConverter.
		final ReplayURIConverter decorated = EasyMock.createMock(ReplayURIConverter.class);
		cut = new AccessPoint() {
			@Override
			public ReplayURIConverter decorateURIConverter(
					ReplayURIConverter uriConverter,
					HttpServletRequest httpRequest, WaybackRequest wbRequest) {
				return decorated;
			}
		};
		setUpAccessPoint();
		final String timestamp = "20140505101010";
		setReplayRequest("http://example.com/", timestamp);

		Resource payloadResource = createTestHtmlResource(timestamp,
			"hogehogehogehoge\n".getBytes("UTF-8"));
		CaptureSearchResults results = setupCaptures(0, payloadResource);
		CaptureSearchResult closest = results.getClosest();

		EasyMock
			.expect(replay.getRenderer(wbRequest, closest, payloadResource))
			.andReturn(replayRenderer);
		replayRenderer.renderResource(httpRequest, httpResponse, wbRequest,
			closest, payloadResource, decorated, results);

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay, replayRenderer, decorated);

		cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(replayRenderer);
	}

	/**
	 * Test of Memento-furnished response to replay request for non-archived
	 * timestamp. This is not strictly a URL-M (as far as I understand). Wayback
	 * returns <i>intermediate resource</i> to URI-M. Memento Specification
	 * states the response
	 * <ul>
	 * <li>MUST NOT have "Vary: accept-datetime" header (this is the key
	 * difference from redirect from URI-G; see below)</li>
	 * <li>MUST NOT have "Memento-Datetime" header</li>
	 * <li>MUST have "Link" header, which MUST have at least "original" relation
	 * link. "timegate", "timemap" and "memento" relation type links MAY be
	 * provided.</li>
	 * </ul>
	 * @throws Exception
	 */
	public void testMemento_replay_nearbyCapture() throws Exception {
		cut.setEnableMemento(true);
		// make sure wbRequesat.requestUrl, replayTimestamp are set up.
		// As this is a URI-M, not URI-G, mementoTimegate flag must be false.
		setReplayRequest("http://test.example.com/", "20100601123456");
		assertFalse(wbRequest.isMementoTimegate());

		Resource payloadResource = createTestHtmlResource("20100601000000",
			"hogehogehogehoge\n".getBytes("UTF-8"));
		@SuppressWarnings("unused")
		CaptureSearchResults results = setupCaptures(0, payloadResource);
		// handleRequest()
		// calls handleReplay()
		// - calls checkInterstitialRedirect()
		// - calls selfRedirectCanonicalizer.urlStringToKey(requestURL) if
		//   non-null
		// - calls queryIndex(), which calls collection.resourceIndex.query(wbRequest)

		// redirects to URL for closest capture.
		expectRedirect("/web/20100601000000/http://test.example.com/");
		// also has a Link header with just "original" relation.
		httpResponse
			.setHeader("Link", String.format("<%s>; rel=\"original\"",
				"http://test.example.com/"));

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(httpResponse, resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);
	}

	/**
	 * Test of Memento-furnished response to Timegate (URI-G). Memento
	 * Specification states the response
	 * <ul>
	 * <li>MUST have "Vary: accept-datetime" header</li>
	 * <li>MUST NOT have "Memento-Datetime" header</li>
	 * <li>MUST have "Link" header, which MUST have at least "original" relation
	 * link. "timegate", "timemap" and "memento" relation type links MAY be
	 * provided.</li>
	 * </ul>
	 * @throws Exception
	 */
	public void testMementoTimegate() throws Exception {
		final String AGGREGATION_PREFIX = "";//"http://web.archive.org";
		cut.setEnableMemento(true);
		cut.setConfigs(new Properties());
//		cut.getConfigs().setProperty(MementoUtils.AGGREGATION_PREFIX_CONFIG,
//			AGGREGATION_PREFIX);

		// Wayback Timegate is mapped to date-less replay URL (/web/<URI-R>)
		// with Accept-Datetime header, but it is irrelevant here (it's a
		// RequestParser matter.) What's relevant here is that WaybackRequest
		// is a replay request with mementoTimegate property set to true.
		setReplayRequest("http://test.example.com/", "20100601123456");
		wbRequest.setMementoTimegate();

		Resource payloadResource = createTestHtmlResource("20100601000000",
			"hogehogehogehoge\n".getBytes("UTF-8"));
		@SuppressWarnings("unused")
		CaptureSearchResults results = setupCaptures(0, payloadResource);

		final String expectedMementoDateTime = "Tue, 01 Jun 2010 00:00:00 GMT";
		// redirects to URL for closest capture.
		expectRedirect("/web/20100601000000/http://test.example.com/");
		// MUST have "Vary: accept-datetime" header
		httpResponse.setHeader("Vary", "accept-datetime");
		// also has Link header with mandatory "original" link, optional
		// "timemap", and "first/last memento" (combined here) links. It
		// can also include "prev/next memento" links, which is not
		// applicable here.
		final String expectedMementoLink = String
			.format(
				"<%1$s>; rel=\"original\", "
						+ "<%2$s%3$stimemap/link/%1$s>; rel=\"timemap\"; type=\"application/link-format\", "
						+ "<%2$s%3$s%4$s/%1$s>; rel=\"first last memento\"; datetime=\"%5$s\"",
				"http://test.example.com/", AGGREGATION_PREFIX, WEB_PREFIX,
				"20100601000000", expectedMementoDateTime);
		httpResponse.setHeader(MementoUtils.LINK, expectedMementoLink);

		EasyMock.replay(httpRequest, httpResponse, resourceIndex,
			resourceStore, replay);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(httpResponse, resourceIndex, resourceStore, replay);

		assertTrue("handleRequest return value", r);
	}

	/**
	 * test of Memento Timemap request.
	 * <p>
	 * Actual rendering is done by a separate bean implementing
	 * {@link MementoHandler}. So what we test here is that AccessPoint is
	 * correctly routing Timemap request to MementoHandler.
	 */
	public void testMementoTimemap() throws Exception {
		cut.setEnableMemento(true);
		setCaptureQueryRequest("http://www.example.com/", "20100601000000");
		// this make WaybackRequest.isMementoTimemapRequest() return true,
		// which shall direct AccessPoint to call MementHandler.renderMementoTimemap().
		wbRequest.setMementoTimemapFormat("link");

		MementoHandler mementoHandler = EasyMock
			.createMock(MementoHandler.class);
		EasyMock.expect(
			mementoHandler.renderMementoTimemap(wbRequest, httpRequest,
				httpResponse)).andReturn(true);
		cut.setMementoHandler(mementoHandler);

		EasyMock.replay(httpRequest, httpResponse, mementoHandler);

		cut.init();
		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(mementoHandler);

		assertTrue("handleRequest return value", r);
	}

	/**
	 * test for local static resource when enableMemento=true. expectations:
	 * <ul>
	 * <li>do-not-negotiate Link header is set.
	 * </ul>
	 * </ul>
	 * @throws Exception
	 */
	public void testMemento_dispatchLocal() throws Exception {
		cut.setEnableMemento(true);
		cut.setServeStatic(true);

		// first reset the mock for overriding getAttribute(), getRequestURI(),
// and getRequestURL()
		EasyMock.reset(httpRequest);
		setupRequestStub("/static", "/static/aaa.css", "/static/");

		EasyMock.expect(
			httpRequest.getAttribute("webapp-request-context-path-prefix"))
			.andStubReturn("/static/");

		// reconfigure RequestParser to return null, which signifies that
		// there's no dynamic handler and the request shall be mapped to local
		// static resource. (AccessPoint#dispatchLocal(HttpServletRequest))
		EasyMock.reset(parser);
		EasyMock.expect(parser.parse(httpRequest, cut)).andReturn(null);

		// AccessPoint#dispatchLocal() checks existence of the file if
		// ServletContext#getRealPath() returns non-null value for translated
		// request path. have it skip the test by returning
		// null. otherwise dispatchLocal() will fail.
		ServletContext servletContext = EasyMock
			.createMock(ServletContext.class);
		EasyMock.expect(servletContext.getRealPath(EasyMock.<String>notNull()))
			.andStubReturn(null);
		cut.setServletContext(servletContext);

		// Expectation: AccessPoint#dispatchLocal() eventually calls
		// RequestDispatcher#forward(...)
		requestDispatcher.forward(httpRequest, httpResponse);

		// key expectation in this test
		httpResponse.setHeader(MementoUtils.LINK,
			"<http://mementoweb.org/terms/donotnegotiate>; rel=\"type\"");

		EasyMock.replay(httpRequest, parser, servletContext, requestDispatcher);

		cut.init();

		boolean r = cut.handleRequest(httpRequest, httpResponse);

		EasyMock.verify(parser, requestDispatcher);

		assertTrue("handleRequest return value", r);
	}

	// TODO: tests of live-web redirector
}
