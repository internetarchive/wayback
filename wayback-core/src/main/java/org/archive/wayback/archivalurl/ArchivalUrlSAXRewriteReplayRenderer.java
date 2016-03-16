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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.UIResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.proxy.ProxyHttpsResultURIConverter;
import org.archive.wayback.replay.CompositeResource;
import org.archive.wayback.replay.HttpHeaderOperation;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.JSPExecutor;
import org.archive.wayback.replay.ReplayURLTransformer;
import org.archive.wayback.replay.TagMagix;
import org.archive.wayback.replay.TextReplayRenderer;
import org.archive.wayback.replay.charset.CharsetDetector;
import org.archive.wayback.replay.charset.StandardCharsetDetector;
import org.archive.wayback.replay.html.ContextResultURIConverterFactory;
import org.archive.wayback.replay.html.IdentityResultURIConverterFactory;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.util.htmllex.ContextAwareLexer;
import org.archive.wayback.util.htmllex.ParseEventHandler;
import org.archive.wayback.webapp.AccessPoint;
import org.htmlparser.Node;
import org.htmlparser.lexer.Lexer;
import org.htmlparser.lexer.Page;
import org.htmlparser.util.ParserException;

/**
 * ReplayRenderer which attempts to rewrite text/html documents so URLs 
 * references within the document load from the correct ArchivalURL AccessPoint.
 * 
 * @author brad
 *
 */
public class ArchivalUrlSAXRewriteReplayRenderer implements ReplayRenderer {
	private ParseEventHandler delegator = null;
	private HttpHeaderProcessor httpHeaderProcessor;
	private CharsetDetector charsetDetector = new StandardCharsetDetector();
	@Deprecated
	private ContextResultURIConverterFactory converterFactory = null;
	@Deprecated
	private boolean rewriteHttpsOnly;
	
	private final static String OUTPUT_CHARSET = "utf-8";
	private static int FRAMESET_SCAN_BUFFER_SIZE = 16 * 1024;
	private static ReplayRenderer frameWrappingRenderer = null;
	public static ReplayRenderer getFrameWrappingRenderer() {
		return frameWrappingRenderer;
	}

	public static void setFrameWrappingRenderer(ReplayRenderer frameWrappingRenderer) {
		ArchivalUrlSAXRewriteReplayRenderer.frameWrappingRenderer = frameWrappingRenderer;
	}

	/**
	 * @param httpHeaderProcessor which should process HTTP headers
	 */
	public ArchivalUrlSAXRewriteReplayRenderer(HttpHeaderProcessor httpHeaderProcessor) {
		this.httpHeaderProcessor = httpHeaderProcessor;
	}

	@Override
	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource httpHeadersResource,
			Resource payloadResource, ResultURIConverter uriConverter,
			CaptureSearchResults results) throws ServletException, IOException,
			WaybackException {
		final Resource resource = httpHeadersResource == payloadResource ? payloadResource
				: new CompositeResource(httpHeadersResource, payloadResource);
		renderResource(httpRequest, httpResponse, wbRequest, result, resource,
			uriConverter, results);
	}

	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource resource,
			ResultURIConverter uriConverter, CaptureSearchResults results)
					throws ServletException, IOException, WaybackException {
		// TODO: wrong
		Resource decodedResource = TextReplayRenderer.decodeResource(resource);

		// The URL of the page, for resolving in-page relative URLs: 
//		URL url = null;
//		try {
//			url = new URL(result.getOriginalUrl());
//		} catch (MalformedURLException e1) {
//			// TODO: this shouldn't happen...
//			e1.printStackTrace();
//			throw new IOException(e1.getMessage());
//		}
		// determine the character set used to encode the document bytes:
		String charSet = charsetDetector.getCharset(resource, decodedResource, wbRequest);
		
		// set up the context:
		final ReplayParseContext context = ReplayParseContext.create(
			uriConverter, wbRequest,
			createConverterFactory(uriConverter, httpRequest, wbRequest), result, rewriteHttpsOnly);

		// XXX same code in ArchivalUrlJSStringReplayRenderer
		String policy = result.getOraclePolicy();
		if (policy == null) {
			AccessPoint accessPoint = wbRequest.getAccessPoint();
			if (accessPoint != null) {
				policy = accessPoint.getRewriteDirective(result);
			}
		}
		if (policy != null) {
			context.setOraclePolicy(policy);
		}

		if (!wbRequest.isFrameWrapperContext()) {
			// in case this is an HTML page with FRAMEs, peek ahead an look:
			// TODO: make ThreadLocal:
			byte buffer[] = new byte[FRAMESET_SCAN_BUFFER_SIZE];

			decodedResource.mark(FRAMESET_SCAN_BUFFER_SIZE);
			int amtRead = decodedResource.read(buffer);
			decodedResource.reset();

			if(amtRead > 0) {
				StringBuilder foo = new StringBuilder(new String(buffer,charSet));
				int frameIdx = TagMagix.getEndOfFirstTag(foo, "FRAMESET");
				if(frameIdx != -1) {
					// insert flag so we don't add FRAMESET:
					context.putData(FastArchivalUrlReplayParseEventHandler.FERRET_DONE_KEY,"");

//					// top-level Frameset: Draw the frame wrapper thingy:
//					frameWrappingRenderer.renderResource(httpRequest, 
//							httpResponse, wbRequest, result, resource, 
//							uriConverter, results);
//					return;
				}
			}
		}


		// copy the HTTP response code:
		HttpHeaderOperation.copyHTTPMessageHeader(resource, httpResponse);

		// transform the original headers according to our headerProcessor:
		Map<String,String> headers = HttpHeaderOperation.processHeaders(
				resource, context, httpHeaderProcessor);

		// prepare several objects for the parse:

		// a JSPExecutor:
//		JSPExecutor jspExec = new JSPExecutor(uriConverter, httpRequest,
//				httpResponse, wbRequest, results, result, decodedResource);
		UIResults uiResults = new UIResults(wbRequest, uriConverter, results, result, decodedResource);
		JSPExecutor jspExec = new JSPExecutor(httpRequest, httpResponse, uiResults);

		// To make sure we get the length, we have to buffer it all up...
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		context.setOutputCharset(OUTPUT_CHARSET);
		context.setOutputStream(baos);
		context.setJspExec(jspExec);


		// and finally, parse, using the special lexer that knows how to
		// handle javascript blocks containing unescaped HTML entities:
		Page lexPage = new Page(decodedResource,charSet);
		Lexer lexer = new Lexer(lexPage);
		Lexer.STRICT_REMARKS = false;
		ContextAwareLexer lex = new ContextAwareLexer(lexer, context);
		Node node;
		try {
			delegator.handleParseStart(context);
			while((node = lex.nextNode()) != null) {
				delegator.handleNode(context, node);
			}
			delegator.handleParseComplete(context);
		} catch (ParserException e) {
			e.printStackTrace();
			throw new IOException(e.getMessage());
		}

		// At this point, baos contains the utf-8 encoded bytes of our result:
		byte[] utf8Bytes = baos.toByteArray();
		// set the corrected length:
		headers.put(HttpHeaderOperation.HTTP_LENGTH_HEADER, 
				String.valueOf(utf8Bytes.length));
		headers.put(TextReplayRenderer.GUESSED_CHARSET_HEADER, charSet);

		// send back the headers:
		HttpHeaderOperation.sendHeaders(headers, httpResponse);
		// Tomcat will always send a charset... It's trying to be smarter than
		// we are. If the original page didn't include a "charset" as part of
		// the "Content-Type" HTTP header, then Tomcat will use the default..
		// who knows what that is, or what that will do to the page..
		// let's try explicitly setting it to what we used:
		httpResponse.setCharacterEncoding(OUTPUT_CHARSET);
		httpResponse.getOutputStream().write(utf8Bytes);
	}

	// Cannot get rid of this method for backward-compatibility. There's at least
	// one class known to override this method. Wait until next major release.
	/**
	 * @param uriConverter
	 * @param httpRequest
	 * @param wbRequest
	 * @return ContextResultURIConverterFactory
	 * @deprecated 2015-02-10 no replacement
	 */
	protected ContextResultURIConverterFactory createConverterFactory(
			ResultURIConverter uriConverter, HttpServletRequest httpRequest,
			WaybackRequest wbRequest) {
		return converterFactory;
	}

	/**
	 * @return the charsetDetector
	 */
	public CharsetDetector getCharsetDetector() {
		return charsetDetector;
	}

	/**
	 * @param charsetDetector the charsetDetector to set
	 */
	public void setCharsetDetector(CharsetDetector charsetDetector) {
		this.charsetDetector = charsetDetector;
	}

	/**
	 * @return the delegator
	 */
	public ParseEventHandler getDelegator() {
		return delegator;
	}

	/**
	 * @param delegator the delegator to set
	 */
	public void setDelegator(ParseEventHandler delegator) {
		this.delegator = delegator;
	}

	/**
	 * @return ResultURIConverter factory object
	 * @deprecated 2015-02-10 See {@link ReplayURLTransformer}
	 */
	public ContextResultURIConverterFactory getConverterFactory() {
		return converterFactory;
	}

	/**
	 * Set a factory to be used for constructing contextualized
	 * {@link ResultURIConverter}.
	 * <p>
	 * If set, it will be used even when
	 * base ResultURIConverter implements {@link ContextResultURIConverterFactory}
	 * interface. Usually ResultURIConverter's own factory implementation is
	 * sufficient, and slightly more efficient. This property may be dropped in the
	 * near future.
	 * </p>
	 * @param converterFactory factory object
	 * @deprecated 2015-02-10 See {@link ReplayURLTransformer}
	 */
	public void setConverterFactory(
			ContextResultURIConverterFactory converterFactory) {
		this.converterFactory = converterFactory;
	}

	/**
	 * @return HTTPS rewriting flag
	 * @deprecated 2015-02-09 Use {@link ProxyHttpsReplayURIConverter#isRewriteHttps}
	 */
	public boolean isRewriteHttpsOnly() {
		return rewriteHttpsOnly;
	}

	/**
	 * Turn on/off HTTPS rewriting.
	 * @param rewriteHttpsOnly {@code true} for rewriting {@code https://}
	 * @deprecated 2015-02-09 Use {@link ProxyHttpsReplayURIConverter#setRewriteHttps}
	 */
	public void setRewriteHttpsOnly(boolean rewriteHttpsOnly) {
		this.rewriteHttpsOnly = rewriteHttpsOnly;
	}
}
