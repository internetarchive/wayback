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
import org.archive.wayback.replay.RedirectRewritingHttpHeaderProcessor;
import org.archive.wayback.replay.TextReplayRenderer;
import org.archive.wayback.replay.TransparentReplayRendererTest.TestServletOutputStream;
import org.archive.wayback.resourcestore.resourcefile.WarcResource;
import org.easymock.EasyMock;

/**
 * unit test for {@link ArchivalCSSReplayRenderer}.
 * <p>Uses a mock for {@link ResultURIConverter}. ResultURIConverter
 * must be test separately. ArchivalCSSReplayRenderer does not use
 * BlockCSSStringTransformer, but relies on TextDocument for extracting
 * URLs in CSS. So this unit test does not verify those
 * <code>StringTransformer</code>s are working correctly.</p>
 * <p>ArchivalCSSReplayRenderer is still a primary ReplayRenderer
 * for CSS resources (vs. CSS embedded in HTML) for both archival-URL
 * and proxy mode.</p>
 *
 * @author kenji
 *
 */
public class ArchivalUrlCSSReplayRendererTest extends TestCase {

    ResultURIConverter uriConverter;
    HttpServletResponse response;
    WaybackRequest wbRequest;
    CaptureSearchResult result;
    TestServletOutputStream servletOutput = new TestServletOutputStream();
    
    ArchivalUrlCSSReplayRenderer cut;
        
    /* (non-Javadoc)
     * @see junit.framework.TestCase#setUp()
     */
    protected void setUp() throws Exception {
        super.setUp();
        RedirectRewritingHttpHeaderProcessor httpHeaderProcessor = new RedirectRewritingHttpHeaderProcessor();
        httpHeaderProcessor.setPrefix("X-Archive-Orig-");
        
        cut = new ArchivalUrlCSSReplayRenderer(httpHeaderProcessor);
        
        uriConverter = EasyMock.createMock(ResultURIConverter.class);
        
        response = EasyMock.createMock(HttpServletResponse.class);
        EasyMock.expect(response.getOutputStream()).andReturn(servletOutput);
        
        wbRequest = new WaybackRequest();
        wbRequest.setFrameWrapperContext(false);
        
        result = new CaptureSearchResult();
        result.setOriginalUrl("http://www.example.com/");
        result.setCaptureTimestamp("20100101123456");
    }
    
    public static Resource createTestHtmlResource(byte[] payloadBytes) throws IOException {
        WARCRecordInfo recinfo = TestWARCRecordInfo.createCompressedHttpResponse("text/html", payloadBytes);
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
     * <li>calls HttpServletResponse.setHeader() for Content-Type, Content-Length and 
     *   {@link TextReplayRenderer#getGuessedCharsetHeader()}.</li>
     * <li>calls HttpServletResponse.setCharsetEncoding() with value "utf-8"</li>
     * </ul>
     * @throws Exception
     */
    public void testBasicBehavior() throws Exception {
        final String payload = 
                "@import \"style1.css\";\n" +
        		"@import 'style2.css';\n" +
                "@import 'http://archive.org/common.css';\n" +
        		"BODY {\n" +
        		"  color: #fff;\n" +
        		"  background: transparent url(bg.gif);\n" +
        		"}\n";
        final byte[] payloadBytes = payload.getBytes("UTF-8");
        Resource payloadResource = createTestHtmlResource(payloadBytes);
        
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(EasyMock.eq("Content-Length"), EasyMock.<String>notNull());
        response.setHeader(cut.getGuessedCharsetHeader(), "UTF-8");
        response.setHeader("Content-Type", "text/html");
        response.setHeader(EasyMock.matches("X-Archive-Orig-.*"), EasyMock.<String>notNull());
        EasyMock.expectLastCall().anyTimes();
        
        final String ts = result.getCaptureTimestamp();
        final String url1 = result.getOriginalUrl() + "style1.css";
        EasyMock.expect(uriConverter.makeReplayURI(ts, url1)).andReturn(
                "/web/" + ts + "/" + url1);
        final String url2 = result.getOriginalUrl() + "style2.css";
        EasyMock.expect(uriConverter.makeReplayURI(ts, url2)).andReturn(
                "/web/" + ts + "/" + url2);
        final String url3 = result.getOriginalUrl() + "bg.gif";
        EasyMock.expect(uriConverter.makeReplayURI(ts, url3)).andReturn(
                "/web/" + ts + "/" + url3);
        EasyMock.expect(uriConverter.makeReplayURI(ts, "http://archive.org/common.css")).andReturn(
                "http://archive.org/common.css");
        
        EasyMock.replay(response, uriConverter);
        
        cut.renderResource(null, response, wbRequest, result, payloadResource, uriConverter, null);
        
        EasyMock.verify(response, uriConverter);
        
        final String expected = 
                "@import \"/web/20100101123456/http://www.example.com/style1.css\";\n" +
                "@import '/web/20100101123456/http://www.example.com/style2.css';\n" +
                "@import 'http://archive.org/common.css';\n" +
                "BODY {\n" +
                "  color: #fff;\n" +
                "  background: transparent url(/web/20100101123456/http://www.example.com/bg.gif);\n" +
                "}\n";
        String out = servletOutput.getString();
        assertEquals("servlet output", expected, out);        
    }
    
    // TODO: more tests
    // - jspInserts
        
}
