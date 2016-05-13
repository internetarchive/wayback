/**
 * 
 */
package org.archive.wayback.archivalurl;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
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
import org.archive.wayback.proxy.ProxyHttpsReplayURIConverter;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.IdentityHttpHeaderProcessor;
import org.archive.wayback.replay.RedirectRewritingHttpHeaderProcessor;
import org.archive.wayback.replay.TransparentReplayRendererTest.TestServletOutputStream;
import org.archive.wayback.replay.html.ContextResultURIConverterFactory;
import org.archive.wayback.replay.html.IdentityResultURIConverterFactory;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.replay.html.StringTransformer;
import org.archive.wayback.replay.html.transformer.JSStringTransformer;
import org.archive.wayback.resourcestore.resourcefile.WarcResource;
import org.archive.wayback.webapp.AccessPoint;
import org.easymock.EasyMock;

/**
 * Test case for {@link ArchivalURLJSStringTransformerReplayRenderer}.
 * 
 * @author kenji
 *
 */
public class ArchivalURLJSStringTransformerReplayRendererTest extends TestCase {

	/* (non-Javadoc)
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		super.setUp();
	}
	
	public static Resource createTestJSResource(byte[] payloadBytes) throws IOException {
		WARCRecordInfo recinfo = TestWARCRecordInfo.createHttpResponse("text/javascript", payloadBytes);
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	/**
	 * Rewrite directive shall be passed from {@link AccessPoint#getRewriteDirective(CaptureSearchResult)}
	 * to {@link ReplayParseContext}.
	 * @throws Exception
	 */
	public void testRewriteDirectiveIsPassedToParseContext() throws Exception {
		HttpHeaderProcessor httpHeaderProcessor = new IdentityHttpHeaderProcessor();
		ArchivalURLJSStringTransformerReplayRenderer renderer = new ArchivalURLJSStringTransformerReplayRenderer(httpHeaderProcessor);

		final String rewriteDirective = "rewrite-it";

		StringTransformer transformer = new StringTransformer() {
			@Override
			public String transform(ReplayParseContext context, String input) {
				String directive = context.getOraclePolicy();
				assertEquals(rewriteDirective, directive);
				return input;
			}
		};
		renderer.setTransformer(transformer);

		AccessPoint accessPoint = new AccessPoint() {
			public String getRewriteDirective(CaptureSearchResult capture) {
				return rewriteDirective;
			}
		};
		WaybackRequest wbRequest = WaybackRequest.createReplayRequest(
			"http://example.com/", "20140101000000", null, null);
		wbRequest.setAccessPoint(accessPoint);

		Resource payloadResource = createTestJSResource("aaa".getBytes("UTF-8"));

		// ResultURIConverter argument is passed down from AccessPoint#getUriConverter().
		// it is typically ProxyHttpsResultURIConverter(), the same class for converterFactory
		// (but a separate instance) - we reuse proxyURIConverter above.
		HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
		TestServletOutputStream servletOutput = new TestServletOutputStream();
		EasyMock.expect(response.getOutputStream()).andStubReturn(servletOutput);

		HttpServletRequest request = null; // assuming unused
		CaptureSearchResult result = new CaptureSearchResult();
		result.setOriginalUrl("http://example.com/");

		ResultURIConverter uriConverter = new ArchivalUrlReplayURIConverter();

		EasyMock.replay(response);

		renderer.renderResource(request, response, wbRequest, result, payloadResource, uriConverter, null);
	}

	/**
	 * multi-component test with typical usage pattern:
	 * rewriting {@code https://} to {@code http://} in proxy-mode.
	 * (ArchivalURLJSStringTransformerReplayRenderer is also used in
	 * proxy-mode despite its name.)
	 * 
	 * @see org.archive.wayback.replay.html.transformer.JSStringTransformerTest
	 */
	public void testProxyHttpsTranslation() throws Exception {
		HttpHeaderProcessor httpHeaderProcessor = new RedirectRewritingHttpHeaderProcessor();
		ArchivalURLJSStringTransformerReplayRenderer renderer = new ArchivalURLJSStringTransformerReplayRenderer(
				httpHeaderProcessor);
		// not testing jspInserts - TODO
		
		// in production transformer is a MultiRegexReplaceStringTransformer
		// running other rewrites besides JSStringTransformer.  We are
		// only testing JSStringTransformer here.
		JSStringTransformer transformer = new JSStringTransformer();
		renderer.setTransformer(transformer);
		
		ProxyHttpsReplayURIConverter proxyURIConverter = new ProxyHttpsReplayURIConverter();
		proxyURIConverter.setRewriteHttps(true);
		
		final String payload =
				"var img1 = 'https://home.archive.org/~hstern/ARI-3745/happy_face.jpg';\n" +
				"var el1 = document.createElement('img');\n" +
				"el1.src = img;\n" +
				"document.getElementById('imgdiv').appendChild(el1)\n";
		final String expected =
				"var img1 = 'http://home.archive.org/~hstern/ARI-3745/happy_face.jpg';\n" +
				"var el1 = document.createElement('img');\n" +
				"el1.src = img;\n" +
				"document.getElementById('imgdiv').appendChild(el1)\n";
		
		final byte[] payloadBytes = payload.getBytes("UTF-8");
		Resource payloadResource = createTestJSResource(payloadBytes);
		
		// ResultURIConverter argument is passed down from AccessPoint#getUriConverter().
		// it is typically ProxyHttpsResultURIConverter(), the same class for converterFactory
		// (but a separate instance) - we reuse proxyURIConverter above.
		HttpServletResponse response = EasyMock.createNiceMock(HttpServletResponse.class);
		TestServletOutputStream servletOutput = new TestServletOutputStream();
		EasyMock.expect(response.getOutputStream()).andStubReturn(servletOutput);
		
		HttpServletRequest request = null; // assuming unused
		WaybackRequest wbRequest = new WaybackRequest();
		CaptureSearchResult result = new CaptureSearchResult();
		result.setOriginalUrl("http://home.archive.org/~hstern/ARI-3745/");
		
		EasyMock.replay(response);
		
		renderer.renderResource(request, response, wbRequest, result, payloadResource, proxyURIConverter, null);
		
		String out = servletOutput.getString();
		assertEquals("servlet output", expected, out);
	}

}
