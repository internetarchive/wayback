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
package org.archive.wayback;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.WaybackException;

/**
 * {@code ReplayRenderer} generates response that replays archived content.
 * It may apply some transformations (often called <em>rewrite</em>) to the content
 * so that:
 * <ul>
 * <li>replay page has navigation user interface etc.</li>
 * <li>clicking hyperlinks makes another replay requests rather than jumping to
 * the live web.</li>
 * </ul>
 * @author brad
 * @see QueryRenderer
 */
public interface ReplayRenderer {

	/**
	 * Generate response that replays capture archive {@code resource}.
	 * 
	 * @param httpRequest the HttpServletRequest
	 * @param httpResponse the HttpServletResponse
	 * @param wbRequest the WaybackRequest that returned the results
	 * @param result actual {@code CaptureSearchResult} that maps to resource to replay
	 * @param resource resource to replay
	 * @param uriConverter the URI converter to use to translate matching
	 *        results into replayable URLs
	 * @param results all CaptureSearchResults that were returned from the
	 *        {@link ResourceIndex}, probably including other capture dates of the same
	 *        URL.
	 * @throws ServletException per usual
	 * @throws IOException per usual
	 * @throws WaybackException if Wayback data specific, anticipated exceptions
	 *         occur
	 */
	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource resource,
			ResultURIConverter uriConverter, CaptureSearchResults results)
					throws ServletException, IOException, WaybackException;

	/**
	 * Generate response that replays capture archive
	 * {@code httpHeadersResource}, reading archived content (HTTP entity) from
	 * {@code payloadResource}.
	 * 
	 * @param httpRequest the HttpServletRequest
	 * @param httpResponse the HttpServletResponse
	 * @param wbRequest the WaybackRequest that returned the results
	 * @param result {@code CaptureSearchResult} that maps to resource to replay
	 * @param httpHeadersResource resource with HTTP headers to replay
	 *        (typically a <em>revisit</em> record.)
	 * @param payloadResource resource with payload to replay
	 * @param uriConverter the URI converter to use to translate matching
	 *        results into replayable URLs
	 * @param results all CaptureSearchResults that were returned from the
	 *        {@link ResourceIndex}, probably including other capture dates of
	 *        the same URL.
	 * @throws ServletException per usual
	 * @throws IOException per usual
	 * @throws WaybackException if Wayback data specific, anticipated exceptions
	 *         occur
	 * @deprecated 2016-03-08 Use one-Resource version above with CompositeResource.
	 */
	public void renderResource(HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, WaybackRequest wbRequest,
			CaptureSearchResult result, Resource httpHeadersResource,
			Resource payloadResource, ResultURIConverter uriConverter,
			CaptureSearchResults results) throws ServletException,
			IOException, WaybackException;
}
