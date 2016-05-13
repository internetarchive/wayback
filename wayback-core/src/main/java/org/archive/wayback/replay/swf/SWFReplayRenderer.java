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
package org.archive.wayback.replay.swf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.URIException;
import org.archive.url.UsableURI;
import org.archive.url.UsableURIFactory;
import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.BadContentException;
import org.archive.wayback.exception.BetterRequestException;
import org.archive.wayback.exception.WaybackException;
import org.archive.wayback.replay.CompositeResource;
import org.archive.wayback.replay.HttpHeaderOperation;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.html.ReplayParseContext;
import org.archive.wayback.util.url.UrlOperations;

import com.flagstone.transform.DoAction;
import com.flagstone.transform.EventHandler;
import com.flagstone.transform.Movie;
import com.flagstone.transform.MovieTag;
import com.flagstone.transform.action.Action;
import com.flagstone.transform.action.GetUrl;
import com.flagstone.transform.action.Push;
import com.flagstone.transform.action.Table;
import com.flagstone.transform.button.DefineButton2;
import com.flagstone.transform.coder.DecoderRegistry;

/**
 * ReplayRenderer which passes embedded URLs inside flash (SWF) format content
 * through a ResultURIConverter, allowing them to be rewritten.
 * 
 * @author brad
 * 
 */
public class SWFReplayRenderer implements ReplayRenderer {
	private HttpHeaderProcessor httpHeaderProcessor;

	/**
	 * @param httpHeaderProcessor
	 *            to use for rewriting original HTTP headers
	 */
	public SWFReplayRenderer(HttpHeaderProcessor httpHeaderProcessor) {
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
		try {

			// copy HTTP response code:
			HttpHeaderOperation.copyHTTPMessageHeader(resource, httpResponse);

			ReplayParseContext context = ReplayParseContext.create(uriConverter, wbRequest, null, result, false);

			// load and process original headers:
			Map<String, String> headers = HttpHeaderOperation.processHeaders(
					resource, context, httpHeaderProcessor);

			// The URL of the resource, for resolving embedded relative URLs:
			URL url = null;
			try {
				url = new URL(result.getOriginalUrl());
			} catch (MalformedURLException e1) {
				e1.printStackTrace();
				throw new IOException(e1.getMessage());
			}
			// the date to associate with the embedded, rewritten URLs:
			String datespec = result.getCaptureTimestamp();
			SWFUrlRewriter rw = new SWFUrlRewriter(uriConverter, url, datespec);

			// OK, try to read the input movie:
			Movie movie = getRobustMovie(RobustMovieDecoder.DECODE_RULE_NULLS);

			try {
				movie.decodeFromStream(resource);
			} catch (DataFormatException e1) {
				throw new BadContentException(e1.getLocalizedMessage());
			}
			Movie outMovie = new Movie(movie);

			List<MovieTag> inTags = movie.getObjects();
			ArrayList<MovieTag> outTags = new ArrayList<MovieTag>();
			for (MovieTag tag : inTags) {
				outTags.add(rewriteTag(rw, tag));
			}
			outMovie.setObjects(outTags);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try {
				outMovie.encodeToStream(baos);
			} catch (DataFormatException e) {
				throw new BadContentException(e.getLocalizedMessage());
			}

			// put the new corrected length:
			headers.put(HttpHeaderOperation.HTTP_LENGTH_HEADER,
					String.valueOf(baos.size()));

			// send the new headers:
			HttpHeaderOperation.sendHeaders(headers, httpResponse);

			// and copy the stored up byte-stream:
			baos.writeTo(httpResponse.getOutputStream());
		} catch (Exception e) {

			// Redirect to identity if there are any issues
			throw new BetterRequestException(UrlOperations.computeIdentityUrl(wbRequest));
		}
	}

	private Movie getRobustMovie(int decodeRule) {
		Movie movie = new Movie();

		DecoderRegistry registry = DecoderRegistry.getDefault();
		RobustMovieDecoder decoder = new RobustMovieDecoder();
		decoder.setDelegate(registry.getMovieDecoder());
		decoder.setDecodeRule(decodeRule);
		registry.setMovieDecoder(decoder);
		movie.setRegistry(registry);

		return movie;
	}

	private MovieTag rewriteTag(SWFUrlRewriter rw, MovieTag tag) {
		if (tag instanceof DoAction) {
			DoAction doAction = (DoAction) tag;
			doAction.setActions(rewriteActions(rw, doAction.getActions()));
		} else if (tag instanceof DefineButton2) {

			DefineButton2 defButton2 = (DefineButton2) tag;
			defButton2.setEvents(rewriteEventHandlers(rw,
					defButton2.getEvents()));
		}
		return tag;
	}

	private List<EventHandler> rewriteEventHandlers(SWFUrlRewriter rw,
			List<EventHandler> handlers) {
		ArrayList<EventHandler> newActions = new ArrayList<EventHandler>();
		for (EventHandler handler : handlers) {
			handler.setActions(rewriteActions(rw, handler.getActions()));
			newActions.add(handler);
		}
		return newActions;
	}

	private List<Action> rewriteActions(SWFUrlRewriter rw, List<Action> actions) {
		ArrayList<Action> newActions = new ArrayList<Action>();
		for (Action action : actions) {
			if (action instanceof Table) {

				Table table = (Table) action;
				table.setValues(rewriteStringValues(rw, table.getValues()));
				newActions.add(table);

			} else if (action instanceof Push) {

				Push push = (Push) action;

				newActions.add(new Push(rewriteObjectValues(rw,
						push.getValues())));

			} else if (action instanceof GetUrl) {

				GetUrl getUrl = (GetUrl) action;
				newActions.add(new GetUrl(rewriteString(rw, getUrl.getUrl()),
						getUrl.getTarget()));

			} else {
				newActions.add(action);
			}
		}
		return newActions;
	}

	private List<Object> rewriteObjectValues(SWFUrlRewriter rw,
			List<Object> values) {

		ArrayList<Object> nvals = new ArrayList<Object>();
		for (int i = 0; i < values.size(); i++) {
			Object orig = values.get(i);
			if (orig instanceof String) {
				nvals.add(rewriteString(rw, (String) orig));
			} else {
				nvals.add(orig);
			}
		}
		return nvals;
	}

	private List<String> rewriteStringValues(SWFUrlRewriter rw,
			List<String> values) {

		ArrayList<String> nvals = new ArrayList<String>();
		for (int i = 0; i < values.size(); i++) {
			nvals.add(rewriteString(rw, values.get(i)));
		}
		return nvals;
	}

	private String rewriteString(SWFUrlRewriter rw, String original) {
		if (original.startsWith("http://")) {
			// System.err.format("Rewrite(%s)\n",original);
			return rw.rewrite(original);
		}
		return original;
	}

	private class SWFUrlRewriter {
		UsableURI baseUrl = null;
		ResultURIConverter converter;
		String datespec;

		public SWFUrlRewriter(ResultURIConverter converter, URL baseUrl,
				String datespec) {
			this.datespec = datespec;
			this.converter = converter;
			try {
				this.baseUrl = UsableURIFactory
						.getInstance(baseUrl.toExternalForm());
			} catch (URIException e) {
				e.printStackTrace();
			}

		}

		public String rewrite(String url) {
			try {
				String resolved = url;
				if (baseUrl != null) {
					resolved = UsableURIFactory.getInstance(baseUrl, url).toString();
				}
				return converter.makeReplayURI(datespec, resolved);
			} catch (URIException e) {
				e.printStackTrace();
			}
			return url;
		}
	}
}
