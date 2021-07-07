package org.archive.wayback.replay;

import org.archive.wayback.ReplayDispatcher;
import org.archive.wayback.archivalurl.requestparser.DatelessReplayRequestParser;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.util.Timestamp;

/**
 * Default implementation of {@link ReplayCaptureSelector}.
 * <p>
 * This is what used to be embedded in {@code AccessPoint hadleReplay} method.
 * It compares previous and next capture of the current one, picks closer capture
 * time-wise. Captures are removed as it traverses the captures list, so that
 * the same capture will not be returned twice.
 * </p>
 * <p>
 * It also favors successful (200) captures over closer redirects and errors for
 * embeds and <em>bestLatestReplayRequest</em> (see {@link DatelessReplayRequestParser}).
 * </p>
 * <p>
 * New feature 2014-00-18: It also skips captures with any of ROBOT_FLAGS_SKIPPED
 * in {@code robotflags} field. This is for new <em>soft-blocked captures</em>
 * feature.
 * </p>
 * <p>
 * For backward compatibility, this implementation delegates closest-selection (i.e.
 * selecting the first capture to return) to {@link ReplayDispatcher} passed to
 * its constructor. {@code ReplayCaptureSelector} is not ready for factory-based
 * customization because of this. When {@code ReplayCaptureSelector}
 * takes over closest-selection functionality from {@code ReplayDispatcher},
 * dependency on {@code replayDispatcher} will be removed.
 * </p>
 */
public class DefaultReplayCaptureSelector implements ReplayCaptureSelector {
	/**
	 * captures with these flags in {@code robotflags} field are skipped.
	 * (TODO: make this customizable?)
	 */
	public static final String ROBOT_FLAGS_SKIPPED = "X";

	private WaybackRequest wbRequest;
	private CaptureSearchResults captures;
	private long requestMS;

	// Current implementation delegates closest-selection part to
	// ReplayDispatcher. It will eventually be removed.
	private ReplayDispatcher replayDispatcher;

	private CaptureSearchResult currentClosest;

	public DefaultReplayCaptureSelector() {
	}	
	
	/**
	 * Initialize object with {@link ReplayDispatcher}, to which
	 * closest-selection is delegated.
	 * @param replayDispatcher {@code ReplayDispatcher}, cannot be null.
	 */
	public DefaultReplayCaptureSelector(ReplayDispatcher replayDispatcher) {
		this.replayDispatcher = replayDispatcher;
	}

	@Override
	public void setRequest(WaybackRequest wbRequest) {
		this.wbRequest = wbRequest;
		requestMS = Timestamp
				.parseBefore(wbRequest.getReplayTimestamp()).getDate()
				.getTime();
	}

	@Override
	public void setCaptures(CaptureSearchResults captures) {
		this.captures = captures;
		currentClosest = null;
	}

	/**
	 * set {@link ReplayDispatcher} for selecting the best capture.
	 * @param replayDispatcher
	 */
	public void setReplayDispatcher(ReplayDispatcher replayDispatcher) {
		this.replayDispatcher = replayDispatcher;
	}

	protected static boolean hasAnyRobotFlags(CaptureSearchResult capture, String flags) {
		// most capture have no robot flag - do a shortcut.
		if (capture.getRobotFlags() != null) {
			for (int i = 0; i < flags.length(); i++) {
				if (capture.isRobotFlagSet(flags.charAt(i)))
					return true;
			}
		}
		return false;
	}

	@Override
	public CaptureSearchResult next() {
		if (currentClosest == null)
			currentClosest = replayDispatcher.getClosest(wbRequest,
				captures);
		else
			currentClosest = findNextClosest();
		while (currentClosest != null) {
			// Attempt to resolve any not-found embedded content with next-best.
			// For "best last" capture, skip not-founds and redirects, hoping to
			// find the best 200 response.
			if (wbRequest.isAnyEmbeddedContext() &&
					currentClosest.isHttpError() ||
					wbRequest.isBestLatestReplayRequest() &&
					!currentClosest.isHttpSuccess()) {
				CaptureSearchResult capture;
				while ((capture = findNextClosest()) != null) {
					if (capture.isHttpRedirect()) {
						// save redirects, but keep looking; it'll be used if no
						// better capture is found (caveat: picks the last, i.e. farthest,
						// redirect capture.)
						currentClosest = capture;
					} else if (capture.isHttpSuccess()) {
						currentClosest = capture;
						break;
					}
				}
			}
			break;
		}
		return currentClosest;
	}

	protected CaptureSearchResult findNextClosest() {
		CaptureSearchResult prev = currentClosest.getPrevResult();
		CaptureSearchResult next = currentClosest.getNextResult();

		currentClosest.removeFromList();

		if (prev == null) {
			return next;
		} else if (next == null) {
			return prev;
		}

		long prevMS = prev.getCaptureDate().getTime();
		long nextMS = next.getCaptureDate().getTime();
		long prevDiff = Math.abs(prevMS - requestMS);
		long nextDiff = Math.abs(requestMS - nextMS);

		if (prevDiff == 0) {
			return prev;
		} else if (nextDiff == 0) {
			return next;
		}

		String currHash = currentClosest.getDigest();
		String prevHash = prev.getDigest();
		String nextHash = next.getDigest();
		boolean prevSameHash = (prevHash.equals(currHash));
		boolean nextSameHash = (nextHash.equals(currHash));

		if (prevSameHash != nextSameHash) {
			return prevSameHash ? prev : next;
		}

		String prevStatus = prev.getHttpCode();
		String nextStatus = next.getHttpCode();
		boolean prev200 = (prevStatus != null) && prevStatus.equals("200");
		boolean next200 = (nextStatus != null) && nextStatus.equals("200");

		// If only one is a 200, prefer the entry with the 200
		if (prev200 != next200) {
			return (prev200 ? prev : next);
		}

		if (prevDiff < nextDiff) {
			return prev;
		} else {
			return next;
		}
	}
	
	public CaptureSearchResult getCurrentClosest() {
		return currentClosest;
	}
	
	public long getRequestMS() {
		return requestMS;
	}	
}