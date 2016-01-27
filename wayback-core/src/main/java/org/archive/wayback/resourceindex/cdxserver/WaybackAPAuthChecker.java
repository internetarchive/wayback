package org.archive.wayback.resourceindex.cdxserver;

import org.archive.cdxserver.auth.AuthToken;
import org.archive.cdxserver.filter.CDXAccessFilter;
import org.archive.cdxserver.filter.FilenamePrefixFilter;
import org.archive.wayback.accesspoint.CompositeAccessPoint;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;
import org.archive.wayback.webapp.AccessPoint;

/**
 * {@link WaybackAuthChecker} modified to set up {@link FilenamePrefixFilter}
 * from {@code fileIncludePrefixes} and {@code fileExcludePrefixes} of
 * {@link AccessPoint}. This is meant to be used with {@link CompositeAccessPoint}
 * to use different prefix filters for different collections.
 *
 * Needs {@link APContextAuthToken} to be passed to {@link #createAccessFilter(AuthToken)}.
 * @see EmbeddedCDXServerIndex#createAuthToken(org.archive.wayback.core.WaybackRequest, String)
 */
public class WaybackAPAuthChecker extends WaybackAuthChecker {

	@Override
	public CDXAccessFilter createAccessFilter(AuthToken token) {
		APContextAuthToken apToken = null;

		if (!(token instanceof APContextAuthToken)) {
			return super.createAccessFilter(apToken);
		}

		apToken = (APContextAuthToken)token;

		AccessPoint ap = apToken.ap;

		FilenamePrefixFilter include = null, exclude = null;

		if (ap.getFileIncludePrefixes() != null) {
			include = new FilenamePrefixFilter();
			include.setExclusion(false);
			include.setPrefixList(ap.getFileIncludePrefixes());
		}

		if (ap.getFileExcludePrefixes() != null) {
			exclude = new FilenamePrefixFilter();
			exclude.setExclusion(true);
			exclude.setPrefixList(ap.getFileExcludePrefixes());
		}

		ExclusionFilter adminFilter = null;
		if (adminExclusions != null) {
			adminFilter = adminExclusions.get();
		}

		ExclusionFilter robotsFilter = null;
		if (robotsExclusions != null &&
				!token.isIgnoreRobots()) {
			robotsFilter = robotsExclusions.get();
		}

		return new AccessCheckFilter(token, adminFilter, robotsFilter, include,
			exclude);
	}
}
