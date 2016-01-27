package org.archive.cdxserver;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.cdxserver.auth.AllAccessAuth;
import org.archive.cdxserver.auth.AuthChecker;
import org.archive.cdxserver.auth.AuthToken;
import org.archive.url.UrlSurtRangeComputer;
import org.archive.url.WaybackURLKeyMaker;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Controller;

@Controller
public class BaseCDXServer implements InitializingBean {
	
	protected UrlSurtRangeComputer urlSurtRangeComputer;
	protected WaybackURLKeyMaker canonicalizer = null;
	protected AuthChecker authChecker;
	protected String ajaxAccessControl;
	
	protected boolean surtMode = false;

	public boolean isSurtMode() {
		return surtMode;
	}

	public void setSurtMode(boolean surtMode) {
		this.surtMode = surtMode;
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		if (authChecker == null) {
			authChecker = new AllAccessAuth();
		}
		
		canonicalizer = new WaybackURLKeyMaker(surtMode);
		urlSurtRangeComputer = new UrlSurtRangeComputer(surtMode);
	}
	
	public String canonicalize(String url, boolean surt) throws UnsupportedEncodingException, URISyntaxException
	{
		if ((canonicalizer == null) || (url == null) || url.isEmpty()) {
			return url;
		}
		
		url = java.net.URLDecoder.decode(url, "UTF-8");
		
		if (surt) {
			return url;
		}
		
		int slashIndex = url.indexOf('/');
		// If true, assume this is already a SURT and skip
		if ((slashIndex > 0) && url.charAt(slashIndex - 1) == ')') {
			return url;
		}
						
		return canonicalizer.makeKey(url);
	}
	
	protected void prepareResponse(HttpServletResponse response)
	{
		response.setContentType("text/plain; charset=\"UTF-8\"");
	}
	
	protected void handleAjax(HttpServletRequest request, HttpServletResponse response)
	{
	    String origin = request.getHeader("Origin");
	    
	    if (origin == null) {
	        return;
	    }
	    
	    response.setHeader("Access-Control-Allow-Credentials", "true");
	    response.setHeader("Access-Control-Allow-Origin", origin);
	}
		
	public AuthChecker getAuthChecker() {
		return authChecker;
	}

	public void setAuthChecker(AuthChecker authChecker) {
		this.authChecker = authChecker;
	}

    public String getAjaxAccessControl() {
        return ajaxAccessControl;
    }

    public void setAjaxAccessControl(String ajaxAccessControl) {
        this.ajaxAccessControl = ajaxAccessControl;
    }
    
    /**
     * Create a subject object based on {@code request}.
     * <p>This method now delegates to {@link AuthChecker}.</p>
     * @param request HTTP request
     * @return subject object
     * @deprecated 2016-01-12 Use {@link AuthChecker#authenticate(HttpServletRequest, AuthToken)}
     */
    protected AuthToken createAuthToken(HttpServletRequest request)
    {
		AuthToken subject = new AuthToken();
		authChecker.authenticate(request, subject);
		return subject;
    }
    
}
