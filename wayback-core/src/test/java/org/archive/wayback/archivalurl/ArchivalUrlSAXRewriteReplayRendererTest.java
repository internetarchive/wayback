/**
 *
 */
package org.archive.wayback.archivalurl;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.archive.io.warc.TestWARCReader;
import org.archive.io.warc.TestWARCRecordInfo;
import org.archive.io.warc.WARCRecord;
import org.archive.io.warc.WARCRecordInfo;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.replay.CompositeResource;
import org.archive.wayback.replay.RedirectRewritingHttpHeaderProcessor;
import org.archive.wayback.replay.TextReplayRenderer;
import org.archive.wayback.replay.TransparentReplayRendererTest.TestServletOutputStream;
import org.archive.wayback.replay.charset.CharsetDetector;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.resourcestore.resourcefile.WarcResource;
import org.archive.wayback.util.htmllex.ParseContext;
import org.archive.wayback.util.htmllex.ParseEventHandler;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.htmlparser.Node;

/**
 * unit test for {@link ArchivalUrlSAXRewriteReplayRenderer}.
 * @author kenji
 *
 */
public class ArchivalUrlSAXRewriteReplayRendererTest extends TestCase {

    ResultURIConverter uriConverter;
    HttpServletResponse response;
    ParseEventHandler nodeHandler;
    WaybackRequest wbRequest;
    CaptureSearchResult result;
    TestServletOutputStream servletOutput = new TestServletOutputStream();

    ArchivalUrlSAXRewriteReplayRenderer cut;

    public static class TestParseEventHandler implements ParseEventHandler {
        @Override
        public void handleParseStart(ParseContext context) {
        }
        @Override
        public void handleNode(ParseContext context, Node node)
                throws IOException {
            String html = node.toHtml();
            //System.out.print(html);
            ((ReplayParseContext) context).getOutputStream().write(
                    html.getBytes("UTF-8"));
        }
        @Override
        public void handleParseComplete(ParseContext context) {
        }
    }

    /* (non-Javadoc)
     * @see junit.framework.TestCase#setUp()
     */
    protected void setUp() throws Exception {
        super.setUp();
        RedirectRewritingHttpHeaderProcessor httpHeaderProcessor = new RedirectRewritingHttpHeaderProcessor();
        httpHeaderProcessor.setPrefix("X-Archive-Orig-");

        cut = new ArchivalUrlSAXRewriteReplayRenderer(httpHeaderProcessor);

        uriConverter = EasyMock.createMock(ResultURIConverter.class);

        response = EasyMock.createMock(HttpServletResponse.class);
        EasyMock.expect(response.getOutputStream()).andReturn(servletOutput);

        nodeHandler = EasyMock.createMock(ParseEventHandler.class);
        cut.setDelegator(nodeHandler);

        wbRequest = new WaybackRequest();
        wbRequest.setFrameWrapperContext(false);

        // replace default CharsetDetector (StandardCharsetDetector) with a stub
        // so as not to depend on its behavior.
        cut.setCharsetDetector(new CharsetDetector() {
            @Override
            public String getCharset(Resource httpHeadersResource,
                    Resource payloadResource, WaybackRequest wbRequest) {
                return "UTF-8";
            }
        });

        result = new CaptureSearchResult();
        result.setCaptureTimestamp("20010203040506");
        result.setOriginalUrl("http://www.example.com/");
    }

    public static Resource createTestHtmlResource(byte[] payloadBytes) throws IOException {
        WARCRecordInfo recinfo = TestWARCRecordInfo.createCompressedHttpResponse("text/html", payloadBytes);
        TestWARCReader ar = new TestWARCReader(recinfo);
        WARCRecord rec = ar.get(0);
        WarcResource resource = new WarcResource(rec, ar);
        resource.parseHeaders();
        return resource;
    }
    public static Resource createTestRevisitResource(byte[] payloadBytes, boolean withHeader, boolean gzipContent) throws IOException {
        WARCRecordInfo recinfo = TestWARCRecordInfo.createRevisitHttpResponse(
                "text/html", payloadBytes.length, withHeader, gzipContent);
        TestWARCReader ar = new TestWARCReader(recinfo);
        WARCRecord rec = ar.get(0);
        WarcResource resource = new WarcResource(rec, ar);
        resource.parseHeaders();
        return resource;
    }

    /**
     * test basic behavior with simple input.
     * expectations:
     * <ul>
     * <li>reads <em>decoded (uncompressed)</em> contents from archive record.</li>
     * <li>calls delegator.handleParseStart() and handleParseComplete() just once, respectively.</li>
     * <li>calls HttpServletResponse.setHeader() for Content-Type, Content-Length and
     *   {@link TextReplayRenderer#GUESSED_CHARSET_HEADER} (not configurable as with TextReplayRenderer
     *   subclasses.</li>
     * <li>calls HttpServletResponse.setCharsetEncoding() with value "utf-8"</li>
     * <li>passes CaptureSearchResult.originalUrl to ParseContext.baseUrl</li>
     * </ul>
     * URL translation is not tested here because it is not a responsibility of this class.
     * it should be tested in a test case for {@link ParseEventHandler} implementations.
     * @throws Exception
     */
    public void testBasicBehavior() throws Exception {
        String payload = "<HTML></HTML>\n";
        final byte[] payloadBytes = payload.getBytes("UTF-8");
        Resource payloadResource = createTestHtmlResource(payloadBytes);

        Capture<ReplayParseContext> parseContextCapture = new Capture<ReplayParseContext>();
        Capture<Node> nodeCapture = new Capture<Node>();
        nodeHandler.handleParseStart(EasyMock.<ReplayParseContext>anyObject());
        nodeHandler.handleParseComplete(EasyMock.<ReplayParseContext>anyObject());
        TestParseEventHandler delegate = new TestParseEventHandler();
        nodeHandler.handleNode(EasyMock.capture(parseContextCapture), EasyMock.capture(nodeCapture));
        EasyMock.expectLastCall().andDelegateTo(delegate).atLeastOnce();

        response.setStatus(200);
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Length", Integer.toString(payloadBytes.length));
        response.setHeader(TextReplayRenderer.GUESSED_CHARSET_HEADER, "UTF-8");
        response.setHeader("Content-Type", "text/html");
        response.setHeader(EasyMock.matches("X-Archive-Orig-.*"), EasyMock.<String>notNull());
        EasyMock.expectLastCall().anyTimes();

        EasyMock.replay(nodeHandler, response, uriConverter);

        cut.renderResource(null, response, wbRequest, result, payloadResource, uriConverter, null);

        EasyMock.verify(nodeHandler, response, uriConverter);

        // NOTE: this compares output of Node.toHtml() with the original input.
        // there's a good chance of Node.toHtml() producing different text than original HTML.
        String out = servletOutput.getString();
        assertEquals("servlet output", payload, out);

        ReplayParseContext context = parseContextCapture.getValue();
        // testing indirectly because ReplayParseContext has no method returning baseUrl.
        assertEquals("baseUrl is correctly set up", "http://www.example.com/a.html", context.resolve("a.html"));
    }

    /**
     * test revisit record (in new format with HTTP headers).
     * @throws Exception
     */
    public void testRevisit() throws Exception {
        final String payload = "<HTML></HTML>\n";
        final byte[] payloadBytes = payload.getBytes("UTF-8");
        Resource payloadResource = createTestHtmlResource(payloadBytes);
        // payloadResource is Content-Encoding: gzip, revisit must be gzipped, too.
        Resource headerResource = createTestRevisitResource(payloadBytes, true, true);

        Capture<ReplayParseContext> parseContextCapture = new Capture<ReplayParseContext>();
        Capture<Node> nodeCapture = new Capture<Node>();
        nodeHandler.handleParseStart(EasyMock.<ReplayParseContext>anyObject());
        nodeHandler.handleParseComplete(EasyMock.<ReplayParseContext>anyObject());
        TestParseEventHandler delegate = new TestParseEventHandler();
        nodeHandler.handleNode(EasyMock.capture(parseContextCapture), EasyMock.capture(nodeCapture));
        EasyMock.expectLastCall().andDelegateTo(delegate).atLeastOnce();

        response.setStatus(200);
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Length", Integer.toString(payloadBytes.length));
        response.setHeader(TextReplayRenderer.GUESSED_CHARSET_HEADER, "UTF-8");
        response.setHeader("Content-Type", "text/html");
        response.setHeader(EasyMock.matches("X-Archive-Orig-.*"), EasyMock.<String>notNull());
        EasyMock.expectLastCall().anyTimes();

        EasyMock.replay(nodeHandler, response, uriConverter);

        Resource compResource = new CompositeResource(headerResource, payloadResource);
        cut.renderResource(null, response, wbRequest, result, compResource, uriConverter, null);

        EasyMock.verify(nodeHandler, response, uriConverter);

        // NOTE: this compares output of Node.toHtml() with the original input.
        // there's a good chance of Node.toHtml() producing different text than original HTML.
        String out = servletOutput.getString();
        assertEquals("servlet output", payload, out);

        ReplayParseContext context = parseContextCapture.getValue();
        // testing indirectly because ReplayParseContext has no method returning baseUrl.
        assertEquals("baseUrl is correctly set up", "http://www.example.com/a.html", context.resolve("a.html"));
    }

    // no test for old-style revisit record as headerResource, because it is caller's responsibility to
    // set headerResource = payloadResource in this case.

//    /**
//     * test revisit record (in old format without HTTP headers).
//     * @throws Exception
//     */
//    public void testOldRevisit() throws Exception {
//        final String payload = "<HTML></HTML>\n";
//        final byte[] payloadBytes = payload.getBytes("UTF-8");
//        Resource payloadResource = createTestHtmlResource(payloadBytes);
//        Resource headerResource = createTestRevisitResource(payloadBytes, false);
//
//        Capture<ReplayParseContext> parseContextCapture = new Capture<ReplayParseContext>();
//        Capture<Node> nodeCapture = new Capture<Node>();
//        nodeHandler.handleParseStart(EasyMock.<ReplayParseContext>anyObject());
//        nodeHandler.handleParseComplete(EasyMock.<ReplayParseContext>anyObject());
//        TestParseEventHandler delegate = new TestParseEventHandler();
//        nodeHandler.handleNode(EasyMock.capture(parseContextCapture), EasyMock.capture(nodeCapture));
//        EasyMock.expectLastCall().andDelegateTo(delegate).atLeastOnce();
//
//        response.setStatus(200);
//        response.setCharacterEncoding("utf-8");
//        response.setHeader("Content-Length", Integer.toString(payloadBytes.length));
//        response.setHeader(TextReplayRenderer.GUESSED_CHARSET_HEADER, "UTF-8");
//        response.setHeader("Content-Type", "text/html");
//        response.setHeader(EasyMock.matches("X-Archive-Orig-.*"), EasyMock.<String>notNull());
//        EasyMock.expectLastCall().anyTimes();
//
//        EasyMock.replay(nodeHandler, response, uriConverter);
//
//        cut.renderResource(null, response, wbRequest, result, headerResource, payloadResource, uriConverter, null);
//
//        EasyMock.verify(nodeHandler, response, uriConverter);
//
//        // NOTE: this compares output of Node.toHtml() with the original input.
//        // there's a good chance of Node.toHtml() producing different text than original HTML.
//        String out = servletOutput.getString();
//        assertEquals("servlet output", payload, out);
//
//        ReplayParseContext context = parseContextCapture.getValue();
//        // testing indirectly because ReplayParseContext has no method returning baseUrl.
//        assertEquals("baseUrl is correctly set up", "http://www.example.com/a.html", context.resolve("a.html"));
//    }

    public void testDoneFlagSetForFrameset() throws Exception {
        String payload = "<frameset cols=\"25%,*,25%\">\n" +
                "  <frame src=\"top.html\">\n" +
                "  <frame src=\"center.html\">\n" +
                "  <frame src=\"bottom.html\">\n" +
                "</frameset>\n";
        byte[] payloadBytes = payload.getBytes("UTF-8");
        Resource payloadResource = createTestHtmlResource(payloadBytes);

        Capture<ReplayParseContext> parseContextCapture = new Capture<ReplayParseContext>();
        nodeHandler.handleParseStart(EasyMock.capture(parseContextCapture));
        nodeHandler.handleParseComplete(EasyMock.<ReplayParseContext>anyObject());
        nodeHandler.handleNode(EasyMock.<ParseContext>anyObject(), EasyMock.<Node>anyObject());
        EasyMock.expectLastCall().anyTimes();

        // do not care about these expectations in this test.
        response.setStatus(200);
        response.setCharacterEncoding("utf-8");
        response.setHeader(EasyMock.<String>notNull(), EasyMock.<String>notNull());
        EasyMock.expectLastCall().anyTimes();

        EasyMock.replay(nodeHandler, response, uriConverter);

        cut.renderResource(null, response, wbRequest, result, payloadResource, uriConverter, null);

        EasyMock.verify(nodeHandler, response, uriConverter);

        ReplayParseContext context = parseContextCapture.getValue();
        assertNotNull(context);
        // it's a kind of odd to use this constant defined in FastArchivalUrlReplayParseEventHandler.
        // ArchivalUrlSAXRewriteReplayRenderer is supposed not to be tied with particular ParseEventHandler
        // implementation.
        assertNotNull("FERRET_DONE flag is set",
                context.getData(FastArchivalUrlReplayParseEventHandler.FERRET_DONE_KEY));
    }

    public void testDoneFlagNotSetForFrameWrapperContext() throws Exception {
        String payload = "<frameset cols=\"25%,*,25%\">\n" +
                "  <frame src=\"top.html\">\n" +
                "  <frame src=\"center.html\">\n" +
                "  <frame src=\"bottom.html\">\n" +
                "</frameset>\n";
        byte[] payloadBytes = payload.getBytes("UTF-8");
        Resource payloadResource = createTestHtmlResource(payloadBytes);

        Capture<ReplayParseContext> parseContextCapture = new Capture<ReplayParseContext>();
        nodeHandler.handleParseStart(EasyMock.capture(parseContextCapture));
        nodeHandler.handleParseComplete(EasyMock.<ReplayParseContext>anyObject());
        nodeHandler.handleNode(EasyMock.<ParseContext>anyObject(), EasyMock.<Node>anyObject());
        EasyMock.expectLastCall().anyTimes();

        // do not care about these expectations in this test.
        response.setStatus(200);
        response.setCharacterEncoding("utf-8");
        response.setHeader(EasyMock.<String>notNull(), EasyMock.<String>notNull());
        EasyMock.expectLastCall().anyTimes();

        EasyMock.replay(nodeHandler, response, uriConverter);

        // !!! KEY SETTING OF THIS TEST !!!
        wbRequest.setFrameWrapperContext(true);

        cut.renderResource(null, response, wbRequest, result, payloadResource, uriConverter, null);

        EasyMock.verify(nodeHandler, response, uriConverter);

        ReplayParseContext context = parseContextCapture.getValue();
        assertNotNull(context);
        // it's kind of odd to use this constant defined in FastArchivalUrlReplayParseEventHandler.
        // ArchivalUrlSAXRewriteReplayRenderer is supposed not to be tied with particular ParseEventHandler
        // implementation.
        assertNull("FERRET_DONE flag is NOT set",
                context.getData(FastArchivalUrlReplayParseEventHandler.FERRET_DONE_KEY));
    }

    /**
     * URL-rewriting backward compatibility test
     * @throws Exception
     */
    public void testURIResultConverterCompatibility() throws Exception {
    	byte[] payloadBytes = ("<html>" +
    			"<body><script src=\"http://example.com/\"></script></body>" +
    			"</html>").getBytes("UTF-8");
		Resource resource = createTestHtmlResource(payloadBytes);

		response.setStatus(200);
		response.setCharacterEncoding("utf-8");
		EasyMock.expect(response.getCharacterEncoding()).andStubReturn("UTF-8");
		response.setHeader(EasyMock.<String>notNull(), EasyMock.<String>notNull());
		EasyMock.expectLastCall().anyTimes();

		final String datespec = result.getCaptureTimestamp() + "cs_";
		ArchivalUrlResultURIConverter uriConverter = new ArchivalUrlResultURIConverter();
		uriConverter.setReplayURIPrefix("http://web.archive.org/web/");
		
		FastArchivalUrlReplayParseEventHandler handler = new FastArchivalUrlReplayParseEventHandler();
		handler.setJspInsertPath(null);
		handler.setEndJsp(null);
		handler.init();
		cut.setDelegator(handler);

		EasyMock.replay(response);

		cut.renderResource(null, response, wbRequest, result, resource,
			uriConverter, null);

		EasyMock.verify();
    }

    // TODO: more tests
    // handles unescaped HTML entities in <script> element correctly (what exactly does "correctly" mean?)
    // HttpServletResponse gets output in UTF-8, no matter what original encoding might be.

}
