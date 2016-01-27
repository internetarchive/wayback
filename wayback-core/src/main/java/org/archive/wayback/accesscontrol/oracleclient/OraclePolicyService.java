/**
 *
 */
package org.archive.wayback.accesscontrol.oracleclient;

import java.util.Date;
import java.util.logging.Logger;

import org.archive.accesscontrol.AccessControlClient;
import org.archive.accesscontrol.AccessControlException;
import org.archive.accesscontrol.RobotsUnavailableException;
import org.archive.accesscontrol.RuleOracleUnavailableException;
import org.archive.util.ArchiveUtils;
import org.archive.wayback.accesscontrol.ContextExclusionFilterFactory;
import org.archive.wayback.accesscontrol.CollectionContext;
import org.archive.wayback.accesscontrol.oracleclient.CustomPolicyOracleFilter.Policy;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.replay.html.RewriteDirector;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;

/**
 * Implementation of {@link ContextExclusionFilterFactory} and {@link RewriteDirectorFactory}
 * on top of {@link AccessControlClient}.
 *
 */
public class OraclePolicyService implements ContextExclusionFilterFactory, RewriteDirector {

	private static final Logger LOGGER = Logger.getLogger(OraclePolicyService.class.getName());

	private String oracleUrl;
	private String proxyHostPort;

	private String fallbackAccessGroup;

	// Now AccessControlClient is shared among multiple ExclusionFilter and RewriteDirector instances.
	// Is AccessControlClient really thread-safe?
	protected AccessControlClient client;

	public void setOracleUrl(String oracleUrl) {
		this.oracleUrl = oracleUrl;
	}
	
	public String getOracleUrl() {
		return this.oracleUrl;
	}	

	public void setProxyHostPort(String proxyHostPort) {
		this.proxyHostPort = proxyHostPort;
	}
	
	public String getProxyHostPort() {
		return this.proxyHostPort;
	}

	/**
	 * Fallback accessGroup used when ExclusionFilter is created with {@link #get()}.
	 * @param fallbackAccessGroup
	 */
	public void setFallbackAccessGroup(String fallbackAccessGroup) {
		this.fallbackAccessGroup = fallbackAccessGroup;
	}

	/**
	 * Inject {@link AccessControlClient}.
	 * @param client AccessControlClient initialized externally.
	 */
	public void setClient(AccessControlClient client) {
		this.client = client;
	}

	/**
	 * call this method after initializing properties.
	 */
	public void init() {
		if (client == null) {
			initializeClient();
		}
	}

	protected void initializeClient() {
		client = new AccessControlClient(oracleUrl);
		if (proxyHostPort != null) {
			int colonIdx = proxyHostPort.indexOf(':');
			if (colonIdx > 0) {
				String host = proxyHostPort.substring(0, colonIdx);
				int port = Integer.valueOf(proxyHostPort
					.substring(colonIdx + 1));
				client.setRobotProxy(host, port);
			}
		}
	}

	protected String getRawPolicy(String accessGroup,
			CaptureSearchResult capture) throws RobotsUnavailableException,
			RuleOracleUnavailableException {
		String url = capture.getOriginalUrl();
		Date captureDate = capture.getCaptureDate();
		Date retrievalDate = new Date();

		return client.getPolicy(ArchiveUtils.addImpliedHttpIfNecessary(url),
			captureDate, retrievalDate, accessGroup);
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.accesscontrol.ExclusionFilterFactory#get()
	 */
	@Override
	public ExclusionFilter get() {
		return getExclusionFilter(fallbackAccessGroup);
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.accesscontrol.ExclusionFilterFactory#shutdown()
	 */
	@Override
	public void shutdown() {
	}

	/* (non-Javadoc)
	 * @see org.archive.wayback.accesscontrol.ContextExclusionFilterFactory#get(org.archive.wayback.accesscontrol.ExclusionContext)
	 */
	@Override
	public ExclusionFilter getExclusionFilter(CollectionContext context) {
		return getExclusionFilter(context.getCollectionContextName());
	}

	protected ExclusionFilter getExclusionFilter(String accessGroup) {
		CustomPolicyOracleFilter filter = new CustomPolicyOracleFilter(client, accessGroup);
		return filter;
	}

	@Override
	public String getRewriteDirective(CollectionContext context, CaptureSearchResult capture) {
		String accessGroup = context.getCollectionContextName();
		try {
			String policy = getRawPolicy(accessGroup, capture);
			// exclusion policies are not rewrite directives. map them to null.
			// (Danger: assumes Policy enum has exclusion values only).
			for (Policy handler : Policy.values()) {
				if (handler.matches(policy)) {
					return null;
				}
			}
			return policy;
		} catch (AccessControlException ex) {
			// TODO: If retrieval of rewrite directive fails due to an error in
			// underlining service, replay can suffer. It would be better to let
			// user know of this transient problem.
			LOGGER.warning(
				"Oracle Unavailable/not running, default to allow all until it responds. Details: " +
						ex.toString());
			return null;
		}
	}

//	@Override
//	public RewriteDirector getRewriteDirector(ExclusionContext context) {
//		return new ContextRewriteDirector(context.getExclusionContextName());
//	}
}
