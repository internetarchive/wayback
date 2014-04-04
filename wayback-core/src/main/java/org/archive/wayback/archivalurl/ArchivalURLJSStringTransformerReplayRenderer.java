package org.archive.wayback.archivalurl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.JSPExecutor;
import org.archive.wayback.replay.TextDocument;
import org.archive.wayback.replay.TextReplayRenderer;
import org.archive.wayback.replay.html.ContextResultURIConverterFactory;
import org.archive.wayback.replay.html.IdentityResultURIConverterFactory;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.replay.html.StringTransformer;

public class ArchivalURLJSStringTransformerReplayRenderer extends TextReplayRenderer {
	
	public ArchivalURLJSStringTransformerReplayRenderer(HttpHeaderProcessor httpHeaderProcessor) {
		super(httpHeaderProcessor);
	}


	private StringTransformer transformer;
	private ContextResultURIConverterFactory converterFactory = null;
	private boolean rewriteHttpsOnly;

	
	public StringTransformer getTransformer() {
		return transformer;
	}


	public void setTransformer(StringTransformer transformer) {
		this.transformer = transformer;
	}
	

	public boolean isRewriteHttpsOnly() {
		return rewriteHttpsOnly;
	}


	public void setRewriteHttpsOnly(boolean rewriteHttpsOnly) {
		this.rewriteHttpsOnly = rewriteHttpsOnly;
	}


	@Override
	protected void updatePage(TextDocument page,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			WaybackRequest wbRequest, CaptureSearchResult result,
			Resource resource, ResultURIConverter uriConverter,
			CaptureSearchResults results) throws ServletException, IOException {
		
		
		// The URL of the page, for resolving in-page relative URLs: 
    	URL url = null;
		try {
			url = new URL(result.getOriginalUrl());
		} catch (MalformedURLException e1) {
			// TODO: this shouldn't happen...
			e1.printStackTrace();
			throw new IOException(e1.getMessage());
		}

		ContextResultURIConverterFactory fact = null;
		
		if (uriConverter instanceof ArchivalUrlResultURIConverter) {
			fact = new ArchivalUrlContextResultURIConverterFactory(
					(ArchivalUrlResultURIConverter) uriConverter);
		} else if (converterFactory != null) {
			fact = converterFactory;
		} else {
			fact = new IdentityResultURIConverterFactory(uriConverter);			
		}		
		
		// set up the context:
		ReplayParseContext context = 
			new ReplayParseContext(fact,url,result.getCaptureTimestamp());
		
		context.setRewriteHttpsOnly(rewriteHttpsOnly);
		
		JSPExecutor jspExec = new JSPExecutor(uriConverter, httpRequest, 
				httpResponse, wbRequest, results, result, resource);
		

		// To make sure we get the length, we have to buffer it all up...
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		context.setOutputCharset("utf-8");
		context.setOutputStream(baos);
		context.setJspExec(jspExec);
		context.setInJS(true); //for https://webarchive.jira.com/browse/ARI-3762
		
		String policy = result.getOraclePolicy();
		
		if (policy != null) {
			context.putData(CaptureSearchResult.CAPTURE_ORACLE_POLICY, policy);
		}
		
		//RewriteReplayParseEventHandler.addRewriteParseContext(context);
		
		
		String replaced = transformer.transform(context, page.sb.toString());
		
		page.sb.setLength(0);
		page.sb.ensureCapacity(replaced.length());
		page.sb.append(replaced);
		
		// if any JS-specific jsp inserts are configured, run and insert...
		List<String> jspInserts = getJspInserts();

		StringBuilder toInsert = new StringBuilder(300);

		if (jspInserts != null) {
			Iterator<String> itr = jspInserts.iterator();
			while (itr.hasNext()) {
				toInsert.append(page.includeJspString(itr.next(), httpRequest,
						httpResponse, wbRequest, results, result, resource));
			}
		}

		page.insertAtStartOfDocument(toInsert.toString());
	}


	public ContextResultURIConverterFactory getConverterFactory() {
		return converterFactory;
	}


	public void setConverterFactory(
			ContextResultURIConverterFactory converterFactory) {
		this.converterFactory = converterFactory;
	}

}
