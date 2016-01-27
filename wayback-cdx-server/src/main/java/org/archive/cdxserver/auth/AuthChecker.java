package org.archive.cdxserver.auth;

import javax.servlet.http.HttpServletRequest;

import org.archive.cdxserver.filter.CDXAccessFilter;
import org.archive.format.cdx.FieldSplitFormat;

/**
 * AuthChecker is responsible for authenticating client and
 * instantiate {@link AuthToken} with permission properties.
 * <p>
 * It is a simplified version of JAAS {@code LoginContext}.
 * </p>
 */
public interface AuthChecker {
	
	/**
	 * Authenticate client based on information available in
	 * {@code request} and updates {@link AuthToken} with identity
	 * and permissions granted to the client.
	 * @param subject subject object to be updated
	 */
	public void authenticate(HttpServletRequest request, AuthToken subject);

	/**
	 * Create per-session CDX exclusion filter.
	 * <p>This method can return {@code null}, if no filtering
	 * is necessary.</p>
	 * @param auth subject
	 * @return per-session filter
	 */
	public CDXAccessFilter createAccessFilter(AuthToken auth);

//	public boolean isAllUrlAccessAllowed(AuthToken auth);
//	//public boolean isUrlAllowed(String url, AuthToken auth);
//
//	public boolean isAllCdxFieldAccessAllowed(AuthToken auth);
	public FieldSplitFormat getPublicCdxFormat();
	public String getPublicCdxFields();
	
	//public boolean isCaptureAllowed(CDXLine line, AuthToken auth);
}
