package org.archive.cdxserver.auth;

import javax.servlet.http.HttpServletRequest;

import org.archive.cdxserver.filter.CDXAccessFilter;
import org.archive.format.cdx.FieldSplitFormat;

public class AllAccessAuth implements AuthChecker {

	@Override
	public void authenticate(HttpServletRequest request, AuthToken authToken) {
		authToken.setAllUrlAccessAllowed(true);
		authToken.setAllCdxFieldAccessAllowed(true);
		authToken.setIgnoreRobots(true);
	}

//	@Override
//    public boolean isAllUrlAccessAllowed(AuthToken auth) {
//		return true;
//    }
//
//	@Override
//    public boolean isAllCdxFieldAccessAllowed(AuthToken auth) {
//		return true;
//    }

	@Override
    public String getPublicCdxFields() {
		return null;
    }
	
	@Override
    public FieldSplitFormat getPublicCdxFormat() {
		return null;
    }

	@Override
    public CDXAccessFilter createAccessFilter(AuthToken auth) {
		return null;
    }
}
