package org.archive.wayback.resourceindex.cdxserver;

import org.archive.cdxserver.auth.AuthToken;
import org.archive.cdxserver.auth.PrivTokenAuthChecker;
import org.archive.cdxserver.filter.CDXAccessFilter;
import org.archive.cdxserver.filter.CDXFilter;
import org.archive.wayback.accesscontrol.ExclusionFilterFactory;
import org.archive.wayback.accesscontrol.robotstxt.redis.RedisRobotExclusionFilterFactory;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;

public class WaybackAuthChecker extends PrivTokenAuthChecker {

	protected ExclusionFilterFactory adminExclusions;
	protected RedisRobotExclusionFilterFactory robotsExclusions;

	protected CDXFilter prefixFilter = null;

	public CDXAccessFilter createAccessFilter(AuthToken token) {
		if (token.isAllUrlAccessAllowed())
			return null;

		ExclusionFilter adminFilter = null;
		if (adminExclusions != null) {
			adminFilter = adminExclusions.get();
		}

		ExclusionFilter robotsFilter = null;
		if (robotsExclusions != null && !token.isIgnoreRobots()) {
			robotsFilter = robotsExclusions.get();
		}

		return new AccessCheckFilter(token, adminFilter, robotsFilter,
			prefixFilter, null);
	}

	public ExclusionFilterFactory getAdminExclusions() {
		return adminExclusions;
	}

	public void setAdminExclusions(ExclusionFilterFactory adminExclusions) {
		this.adminExclusions = adminExclusions;
	}

	public RedisRobotExclusionFilterFactory getRobotsExclusions() {
		return robotsExclusions;
	}

	public void setRobotsExclusions(
			RedisRobotExclusionFilterFactory robotsExclusions) {
		this.robotsExclusions = robotsExclusions;
	}

	public CDXFilter getPrefixFilter() {
		return prefixFilter;
	}

	public void setPrefixFilter(CDXFilter prefixFilter) {
		this.prefixFilter = prefixFilter;
	}
}
