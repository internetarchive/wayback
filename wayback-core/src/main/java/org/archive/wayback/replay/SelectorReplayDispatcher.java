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
package org.archive.wayback.replay;

import java.util.List;

import org.archive.wayback.ReplayDispatcher;
import org.archive.wayback.ReplayRenderer;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.replay.mimetype.MimeTypeDetector;
import org.archive.wayback.resourcestore.indexer.IndexWorker;
import org.archive.wayback.webapp.AccessPoint;

/**
 * ReplayDispatcher instance which uses a configurable ClosestResultSelector
 * to find the best result to show from a given set, and a list of
 * ReplayRendererSelector to determine how best to replay that result to a user.
 *
 * <p>Optionally it can be configured with {@link MimeTypeDetector}s used for
 * overriding unknown ({@code "unk"}) or often-misused ({@code "text/html"})
 * value of {@link CaptureSearchResult#getMimeType()}.</p>
 *
 * @author brad
 */
public class SelectorReplayDispatcher implements ReplayDispatcher {
	private List<ReplayRendererSelector> selectors = null;
	private List<MimeTypeDetector> mimeTypeDetectors = null;
	private ClosestResultSelector closestSelector = null;

	public static final String DEFAULT_MISSING_MIMETYPE = "unk";
	private String missingMimeType = DEFAULT_MISSING_MIMETYPE;

	/**
	 * default value for {@link #untrustfulMimeTypes}
	 */
	public static final String[] DEFAULT_UNTRUSTFUL_MIMETYPES = {
		// Found many occurrence of "www/unknown" and "*/*" in IA's archive.
		"text/html", "www/unknown", "*/", "application/http"
	};
	private String[] untrustfulMimeTypes = DEFAULT_UNTRUSTFUL_MIMETYPES;

	/**
	 * A list of {@code mimetype} values that cannot be fully trusted.
	 * For captures whose {@code mimetype} prefix-matches any of these,
	 * SelectorReplayDispatcher will attempt to detect actual mime-type
	 * with {@code mimeTypeDetector} (if configured).
	 * <p>Value set to {@link #missingMimeType} is always considered
	 * <i>untrustful</i>. You don't need to include it in this list.</p>
	 * <p>If passed {@code null}, default {@link #DEFAULT_UNTRUSTFUL_MIMETYPES}
	 * will be used. If set to an empty array, detection is applied only to
	 * captures without {@code Content-Type} header.</p>
	 * @param untrustfulMimeTypes list of mime-type prefixes
	 */
	public void setUntrustfulMimeTypes(List<String> untrustfulMimeTypes) {
		if (untrustfulMimeTypes == null)
			this.untrustfulMimeTypes = DEFAULT_UNTRUSTFUL_MIMETYPES;
		else
			this.untrustfulMimeTypes = untrustfulMimeTypes
				.toArray(new String[untrustfulMimeTypes.size()]);
	}

	/**
	 * Value of {@code mimetype} field indicating {@code Content-Type}
	 * is unavailable in the response.
	 * Default is {@code unk} (compatible with CDX-Writer).
	 * {@link IndexWorker} puts {@code application/http}, apparently.
	 * @param missingMimeType
	 */
	public void setMissingMimeType(String missingMimeType) {
		if (missingMimeType == null || missingMimeType.isEmpty())
			this.missingMimeType = DEFAULT_MISSING_MIMETYPE;
		else
			this.missingMimeType = missingMimeType;
	}

	public String getMissingMimeType() {
		return missingMimeType;
	}

	/**
	 * check if mime-type detection is suggested for mimeType.
	 * @param mimeType mime-type to test (must not be null/empty/"unk")
	 * @return {@code true} if mime-type should be determined
	 * by looking into Resource.
	 */
	protected boolean shouldDetectMimeType(String mimeType) {
		for (String prefix : untrustfulMimeTypes) {
			if (mimeType.startsWith(prefix)) return true;
		}
		return false;
	}

	private String getCaptureMimeType(CaptureSearchResult result, Resource resource) {
		String mimeType = result.getMimeType();
		// TODO: this code should be encapsulated in CaptureSearchResult.getMimeType()
		if (AccessPoint.REVISIT_STR.equals(mimeType)) {
			if (result.getDuplicatePayload() != null) {
				mimeType = result.getDuplicatePayload().getMimeType();
			} else {
				// let following code get it from resource
				mimeType = null;
			}
		}
		// Many old ARCs have "unk" or "no-type" in ARC header even though
		// HTTP response has valid Content-Type header. CDX writer does not fix
		// it (although it's capable of fixing it internally). If CaptureSearchResult
		// says mimeType is "unk", try reading Content-Type header from the resource.
		if (mimeType == null || mimeType.isEmpty() || missingMimeType.equals(mimeType)) {
			mimeType = resource.getHeader("Content-Type");
		}
		if (mimeType != null && shouldDetectMimeType(mimeType)) {
			mimeType = null;
		}
		return mimeType;
	}

	protected ReplayRenderer getReplayRendererInternal(
			WaybackRequest wbRequest, CaptureSearchResult result,
			Resource resource) {
		if (selectors != null) {
			for (ReplayRendererSelector selector : selectors) {
				if (selector.canHandle(wbRequest, result, resource, resource)) {
					return selector.getRenderer();
				}
			}
		}
		return null;
	}

	@Override
	public ReplayRenderer getRenderer(WaybackRequest wbRequest,
			CaptureSearchResult result, Resource resource) {
		// Revised logic: forcedContentType is set for cs_ and js_ flags. While this is
		// useful for the most cases, it is still possible to convey false information if,
		// for example, JavaScript constructs image URL from style/@src value.
		String suggestedMimeType = wbRequest.getForcedContentType();
		String mimeType = getCaptureMimeType(result, resource);
		if (mimeType == null) {
			mimeType = suggestedMimeType;
		} else {
			if (suggestedMimeType != null && !mimeType.equals(suggestedMimeType)) {
				// if suggestedMimeType and mimeType selects different renderer, run detection.
				// mimeType != suggestedMimeType check is not enough, because there are aliasing
				// cases like "text/javascript" and "application/javascript". This is ugly.
				ReplayRenderer suggestedRenderer = getReplayRendererInternal(wbRequest, result, resource);
				wbRequest.setForcedContentType(mimeType);
				ReplayRenderer contentTypeRenderer = getReplayRendererInternal(wbRequest, result, resource);
				wbRequest.setForcedContentType(suggestedMimeType);
				if (suggestedRenderer == contentTypeRenderer) {
					return suggestedRenderer;
				}
				mimeType = null;
			}
		}
		if (mimeType == null) {
			if (mimeTypeDetectors != null) {
				for (MimeTypeDetector detector : mimeTypeDetectors) {
					String detected = detector.sniff(resource);
					if (detected != null) {
						// detected mimeType is communicated to Selectors
						// through forcedContentType. better way? replace
						// CaptureSearchResult.mimeType?
						mimeType = detected;
						break;
					}
				}
			}
		}
		// hmm, now CaptureSearchResult.mimeType can be set to
		// forcedContentType - it should work, but this may
		// be a bad design.
		if (mimeType != null)
			wbRequest.setForcedContentType(mimeType);
		return getReplayRendererInternal(wbRequest, result, resource);
	}

	@Override
	public ReplayRenderer getRenderer(WaybackRequest wbRequest,
			CaptureSearchResult result, Resource httpHeadersResource,
			Resource payloadResource) {
		if (httpHeadersResource == payloadResource)
			return getRenderer(wbRequest, result, httpHeadersResource);
		else {
			Resource resource = new CompositeResource(httpHeadersResource, payloadResource);
			return getRenderer(wbRequest, result, resource);
		}
	}

	public CaptureSearchResult getClosest(WaybackRequest wbRequest,
			CaptureSearchResults results) {
		return closestSelector.getClosest(wbRequest, results);
	}

	/**
	 * @return the List of ReplayRendererSelector objects configured
	 */
	public List<ReplayRendererSelector> getSelectors() {
		return selectors;
	}

	/**
	 * @param selectors the List of ReplayRendererSelector to use
	 */
	public void setSelectors(List<ReplayRendererSelector> selectors) {
		this.selectors = selectors;
	}

	public List<MimeTypeDetector> getMimeTypeDetectors() {
		return mimeTypeDetectors;
	}

	public void setMimeTypeDetectors(List<MimeTypeDetector> sniffers) {
		this.mimeTypeDetectors = sniffers;
	}

	/**
	 * @param closestSelector the closestSelector to set
	 */
	public void setClosestSelector(ClosestResultSelector closestSelector) {
		this.closestSelector = closestSelector;
	}
	/**
	 * @return the closestSelector
	 */
	public ClosestResultSelector getClosestSelector() {
		return closestSelector;
	}
}
