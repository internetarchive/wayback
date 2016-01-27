package org.archive.cdxserver.auth;

/**
 * AuthToken represents client.
 * It has (currently) single identity (or no identity), and
 * set of permissions granted to the client.
 */
public class AuthToken {
	
	String authToken;

	boolean allUrlAccessAllowed = false;
	boolean allCdxFieldAccessAllowed = false;
//	Boolean cachedAllUrlAllow = null;
//	Boolean cachedAllCdxAllow = null;
	boolean ignoreRobots = false;
	
	public AuthToken()
	{
		this.authToken = null;
	}
	
	public AuthToken(String authToken)
	{
		this.authToken = authToken;
	}

	public String getAuthToken() {
		return authToken;
	}

	protected void setAuthToken(String authToken) {
		this.authToken = authToken;
	}

	public boolean isAllUrlAccessAllowed() {
		return allUrlAccessAllowed;
	}

	public void setAllUrlAccessAllowed(boolean allUrlAccessAllowed) {
		this.allUrlAccessAllowed = allUrlAccessAllowed;
	}

	/**
	 * @deprecated 2016-01-12 call {@link #setAllCdxFieldAccessAllowed(boolean)} with {@code true}
	 */
	public void setAllCdxFieldsAllow() {
		setAllCdxFieldAccessAllowed(true);
	}
	
	public void setAllCdxFieldAccessAllowed(boolean allCdxFieldAccessAllowed) {
		this.allCdxFieldAccessAllowed = allCdxFieldAccessAllowed;
	}

	public boolean isAllCdxFieldAccessAllowed() {
		return allCdxFieldAccessAllowed;
	}

	public boolean isIgnoreRobots() {
		return ignoreRobots;
	}

	public void setIgnoreRobots(boolean ignoreRobots) {
		this.ignoreRobots = ignoreRobots;
	}
}
