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

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.TextDocument;
import org.archive.wayback.replay.TextReplayRenderer;
import org.archive.wayback.util.Timestamp;

/**
 * {@link TextReplayRenderer} that rewrites URLs in JavaScript resource for
 * replay in ArchivalURL mode, and inserts {@code jspInserts} at the top of the
 * document.
 * <p>This class looks up URLs in JavaScript by single regular expression set to
 * its {@code regex} property, and rewrite them with {@link ResultURIConverter}
 * passed to {@code updatePage} method (does not use the one set to {@link TextDocument}.)</p>
 * <p>Regular expression shall match single URL. Be sure to enclose URL in capture group
 * (i.e. {@code (}...{@code )}), or no rewrite will happen.  Regular expression can have
 * optional capture group before URL capture group, which may be necessary for doing
 * more complicated match.  Text captured in the first group will be copied to the
 * output.</p>
 * <p>There's an alternative implementation
 * {@link ArchivalURLJSStringTransformerReplayRenderer}, which supports
 * multiple rewrite patterns through <code>StringTransformer</code>.</p>
 *
 * @see ResultURIConverter
 * @see ArchivalURLJSStringTransformerReplayRenderer
 * @author brad
 */

public class ArchivalUrlJSReplayRenderer extends TextReplayRenderer {
	/**
	 * @param httpHeaderProcessor which should process HTTP headers
	 */
	public ArchivalUrlJSReplayRenderer(
			HttpHeaderProcessor httpHeaderProcessor) {
		super(httpHeaderProcessor);
	}

	private final static Pattern defaultHttpPattern = Pattern
			.compile("(https?://[A-Za-z0-9:_@.-]+)");
	
	private Pattern pattern = defaultHttpPattern;
	
	/**
	 * regular expression for matching URLs.
	 * @param regex
	 */
	public void setRegex(String regex)
	{
		pattern = Pattern.compile(regex);
	}
	
	public String getRegex()
	{
		return pattern.pattern();
	}

	public Pattern getPattern() {
        	return pattern;
    	}

	protected void updatePage(TextDocument page,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			WaybackRequest wbRequest, CaptureSearchResult result,
			Resource resource, ResultURIConverter uriConverter,
			CaptureSearchResults results) throws ServletException, IOException {
		String resourceTS = result.getCaptureTimestamp();
		String captureTS = Timestamp.parseBefore(resourceTS).getDateStr();

		StringBuilder sb = page.sb;
		StringBuffer replaced = new StringBuffer(sb.length());
		Matcher m = pattern.matcher(sb);
		
		// If at least 2 groups, prepend before 2nd group and include 1st group. Allows for more sophisticated matching.
		// Otherwise, insert before 1st group
		if (m.groupCount() > 1) {
			while (m.find()) {
				String beforeHost = m.group(1);
				String host = m.group(2);
				String replacement = uriConverter.makeReplayURI(captureTS, host);
				m.appendReplacement(replaced, beforeHost + replacement);
			}	
		} else {
			while (m.find()) {
				String host = m.group(1);
				String replacement = uriConverter.makeReplayURI(captureTS, host);
				m.appendReplacement(replaced, replacement);
			}			
		}
		
		m.appendTail(replaced);
		// blasted StringBuilder/StringBuffer... gotta convert again...
		page.sb.setLength(0);
		page.sb.ensureCapacity(replaced.length());
		page.sb.append(replaced);

		// if any JS-specific jsp inserts are configured, run and insert...
		page.insertAtStartOfDocument(buildInsertText(page, httpRequest,
				httpResponse, wbRequest, results, result, resource));
	}
}
