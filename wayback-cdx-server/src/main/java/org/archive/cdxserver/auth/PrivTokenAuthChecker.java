package org.archive.cdxserver.auth;

import java.util.List;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.archive.format.cdx.FieldSplitFormat;

/**
 * Simple checking of permissions for cdx server actions Permissions include:
 * -Ability to see blocked urls -Ability to see full cdx line
 * 
 * The checkAccess() for each url is implemented in the subclasses
 * 
 * @author ilya
 * 
 */
public abstract class PrivTokenAuthChecker implements AuthChecker {
	protected String publicCdxFields = null;
	protected FieldSplitFormat publicCdxFormat = null;

	protected List<String> allUrlAccessTokens;
	protected List<String> ignoreRobotsAccessTokens;
	protected List<String> allCdxFieldsAccessTokens;

	public final static String CDX_AUTH_TOKEN = "cdx_auth_token";

	protected String cookieAuthToken = CDX_AUTH_TOKEN;

	@Override
	public void authenticate(HttpServletRequest request, AuthToken subject) {
	    String token = extractAuthToken(request);
	    if (token != null) {
			subject.setAuthToken(token);
			subject.setAllUrlAccessAllowed(isAllowed(subject, allUrlAccessTokens));
			subject.setAllCdxFieldAccessAllowed(isAllowed(subject, allCdxFieldsAccessTokens));
			subject.setIgnoreRobots(isAllowed(subject, ignoreRobotsAccessTokens));
	    }
	}

	public String getCookieAuthToken() {
		return cookieAuthToken;
	}

	/**
	 * Name of the authentication cookie.
	 * @param cookieAuthToken
	 */
	public void setCookieAuthToken(String cookieAuthToken) {
		this.cookieAuthToken = cookieAuthToken;
	}

    protected String extractAuthToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();

		if (cookies == null) {
			return null;
		}

		for (Cookie cookie : cookies) {
			if (cookie.getName().equals(cookieAuthToken)) {
				return cookie.getValue();
			}
		}

		return null;
	}

	protected boolean isAllowed(AuthToken auth, List<String> allowVector) {
		if (auth == null || auth.authToken == null || allowVector == null) {
			return false;
		}

		if (allowVector.contains(auth.authToken)) {
			return true;
		}

		return false;
	}

//	@Override
//	public boolean isAllUrlAccessAllowed(AuthToken auth) {
//		if (auth.cachedAllUrlAllow == null) {
//			auth.cachedAllUrlAllow = isAllowed(auth, allUrlAccessTokens);
//		}
//		return auth.cachedAllUrlAllow;
//	}
//
//	@Override
//	public boolean isAllCdxFieldAccessAllowed(AuthToken auth) {
//		if (auth.cachedAllCdxAllow == null) {
//			auth.cachedAllCdxAllow = isAllowed(auth, allCdxFieldsAccessTokens);
//		}
//		return auth.cachedAllCdxAllow;
//	}

	@Override
	public String getPublicCdxFields() {
		return publicCdxFields;
	}

	@Override
	public FieldSplitFormat getPublicCdxFormat() {
		return publicCdxFormat;
	}

	public void setPublicCdxFields(String publicCdxFields) {
		this.publicCdxFields = publicCdxFields;
		this.publicCdxFormat = new FieldSplitFormat(publicCdxFields);
	}

	public List<String> getAllUrlAccessTokens() {
		return allUrlAccessTokens;
	}

	public void setAllUrlAccessTokens(List<String> allUrlAccessTokens) {
		this.allUrlAccessTokens = allUrlAccessTokens;
	}

	public List<String> getAllCdxFieldsAccessTokens() {
		return allCdxFieldsAccessTokens;
	}

	public void setAllCdxFieldsAccessTokens(
			List<String> allCdxFieldsAccessTokens) {
		this.allCdxFieldsAccessTokens = allCdxFieldsAccessTokens;
	}

	public List<String> getIgnoreRobotsAccessTokens() {
		return ignoreRobotsAccessTokens;
	}

	public void setIgnoreRobotsAccessTokens(
			List<String> ignoreRobotsAccessTokens) {
		this.ignoreRobotsAccessTokens = ignoreRobotsAccessTokens;
	}
}
