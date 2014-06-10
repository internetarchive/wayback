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

package org.archive.wayback.accesspoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.servlet.ServletContext;

import org.archive.wayback.ExceptionRenderer;
import org.archive.wayback.QueryRenderer;
import org.archive.wayback.ReplayDispatcher;
import org.archive.wayback.RequestParser;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.UrlCanonicalizer;
import org.archive.wayback.accesscontrol.CompositeExclusionFilterFactory;
import org.archive.wayback.accesscontrol.ExclusionFilterFactory;
import org.archive.wayback.accesscontrol.oracleclient.CustomPolicyOracleFilter;
import org.archive.wayback.accesspoint.proxy.ProxyAccessPoint;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.replay.html.ContextResultURIConverterFactory;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;
import org.archive.wayback.util.operator.BooleanOperator;
import org.archive.wayback.webapp.AccessPoint;
import org.archive.wayback.webapp.CustomResultFilterFactory;
import org.archive.wayback.webapp.LiveWebRedirector;
import org.archive.wayback.webapp.WaybackCollection;

/**
 * Sub-AccessPoint managed by {@link CompositeAccessPoint}.
 *
 * TODO: Strictly speaking this is not an <i>Adapter</i>. It is an
 * {@link AccessPoint} extended with a capability to inherit/override
 * parent's configuration.
 *
 */
public class AccessPointAdapter extends AccessPoint {
	
	private CompositeAccessPoint composite;
	private AccessPointConfig config;
	private ExclusionFilterFactory exclusionFactory;
	private ResultURIConverter cacheUriConverter;
	private Properties props = null;
	
	private boolean switchable = false;
	
	private class DynamicExclusionFactory implements ExclusionFilterFactory {
		public ExclusionFilter get() {
			return new CustomPolicyOracleFilter(composite.getOracleUrl(),
					config.getBeanName(), null);
		}

		public void shutdown() {
		}
	}
	
	public AccessPointAdapter(CompositeAccessPoint baseAccessPoint,
			AccessPointConfig config) {
		this.composite = baseAccessPoint;
		this.config = config;
		this.exclusionFactory = null;
		
		this.switchable = true;
		initMergedProps();
	}
	
	public AccessPointAdapter(String accessPointName,
			CompositeAccessPoint baseAccessPoint) {
		this.composite = baseAccessPoint;
		this.exclusionFactory = null;
		this.config = baseAccessPoint.getAccessPointConfigs().getAccessPointConfigs().get(accessPointName);
		
		this.switchable = false;
		initMergedProps();
	}
	
	protected void initMergedProps() {
		this.props = new Properties();

		// First put the generic ones
		if (composite.getConfigs() != null) {
			props.putAll(composite.getConfigs());
		}

		// Now, the custom ones for this config
		if (config.getConfigs() != null) {
			props.putAll(config.getConfigs());
		}
	}
	
	public CompositeAccessPoint getBaseAccessPoint() {
		return composite;
	}

	public boolean isProxyMode() {
		return composite.isProxyEnabled();
	}

	public boolean isProxySwitchable() {
		return switchable && isProxyMode();
	}

	public String getSwitchCollPath() {
		return ProxyAccessPoint.SWITCH_COLLECTION_PATH;
	}

	public AccessPointConfig getAccessPointConfig() {
		return config;
	}

	@Override
	public List<String> getFileIncludePrefixes() {
		return config.getFileIncludePrefixes();
	}

	@Override
	public List<String> getFileExcludePrefixes() {
		return config.getFileExcludePrefixes();
	}

	@Override
	public Properties getConfigs() {
		return props;
	}

	@Override
	public String getAccessPointPath() {
		return config.getBeanName();
	}

	public boolean hasExclusions() {
		return (composite.getStaticExclusions() != null) ||
				(composite.getOracleUrl() != null);
	}

	@Override
	public ExclusionFilterFactory getExclusionFactory() {

		if (!hasExclusions()) {
			return null;
		}

		if (exclusionFactory == null) {
			exclusionFactory = buildExclusionFactory();
		}

		return exclusionFactory;
	}

	protected ExclusionFilterFactory buildExclusionFactory() {
		ArrayList<ExclusionFilterFactory> staticExclusions = composite
				.getStaticExclusions();

		if (staticExclusions == null) {
			return new DynamicExclusionFactory();
		} else {
			CompositeExclusionFilterFactory factory = new CompositeExclusionFilterFactory();
			ArrayList<ExclusionFilterFactory> allExclusions = new ArrayList<ExclusionFilterFactory>();
			allExclusions.addAll(staticExclusions);
			if (composite.getOracleUrl() != null) {
				allExclusions.add(new DynamicExclusionFactory());
			}
			factory.setFactories(allExclusions);
			return factory;
		}
	}

	protected String getPrefix(String basePrefix) {
		if (isProxyMode()) {
			return basePrefix;
		} else {
			return basePrefix + config.getBeanName() + "/";
		}
	}

	@Override
	public String getStaticPrefix() {
		return composite.getStaticPrefix();
	}

	@Override
	public String getReplayPrefix() {
		return getPrefix(composite.getReplayPrefix());
	}

	@Override
	public String getQueryPrefix() {
		return getPrefix(composite.getQueryPrefix());
	}

	@Override
	public boolean isExactHostMatch() {
		return composite.isExactHostMatch();
	}

	@Override
	public boolean isExactSchemeMatch() {
		return composite.isExactSchemeMatch();
	}

	@Override
	public boolean isUseAnchorWindow() {
		return composite.isUseAnchorWindow();
	}

	@Override
	public boolean isServeStatic() {
		return composite.isServeStatic();
	}

	@Override
	public ServletContext getServletContext() {
		return composite.getServletContext();
	}

	@Override
	public LiveWebRedirector getLiveWebRedirector() {
		return composite.getLiveWebRedirector();
	}

	@Override
	public String getLiveWebPrefix() {
		return composite.getLiveWebPrefix();
	}

	@Override
	public String getInterstitialJsp() {
		return composite.getInterstitialJsp();
	}

	@Override
	public Locale getLocale() {
		return composite.getLocale();
	}

	@Override
	public List<String> getFilePatterns() {
		return composite.getFilePatterns();
	}

	@Override
	public WaybackCollection getCollection() {
		if (config.getCollection() != null) {
			return config.getCollection();
		} else {
			return composite.getCollection();
		}
	}

	@Override
	public ExceptionRenderer getException() {
		return composite.getException();
	}

	@Override
	public QueryRenderer getQuery() {
		return composite.getQuery();
	}

	@Override
	public RequestParser getParser() {
		RequestParser requestParser = config.getRequestParser();

		if (requestParser != null) {
			return requestParser;
		} else {
			return composite.getParser();
		}
	}

	@Override
	public ReplayDispatcher getReplay() {
		return composite.getReplay();
	}

	@Override
	public ResultURIConverter getUriConverter() {
		
		if (cacheUriConverter == null) {		
			ContextResultURIConverterFactory factory = composite.getUriConverterFactory();
			
			if (factory != null) {
				cacheUriConverter = factory.getContextConverter(getReplayPrefix());
			} else {
				cacheUriConverter = composite.getUriConverter();
			}
		}
		
		return cacheUriConverter;
	}

	@Override
	public BooleanOperator<WaybackRequest> getAuthentication() {
		return composite.getAuthentication();
	}

	@Override
	public String getRefererAuth() {
		return composite.getRefererAuth();
	}

	@Override
	public boolean isBounceToReplayPrefix() {
		return composite.isBounceToReplayPrefix();
	}

	@Override
	public boolean isBounceToQueryPrefix() {
		return composite.isBounceToQueryPrefix();
	}

	@Override
	public long getEmbargoMS() {
		return composite.getEmbargoMS();
	}

	@Override
	public boolean isForceCleanQueries() {
		// Setting this to false to allow custom handling of adapter access
		// points
		return false;
	}

	@Override
	public CustomResultFilterFactory getFilterFactory() {
		return composite.getFilterFactory();
	}

	@Override
	public UrlCanonicalizer getSelfRedirectCanonicalizer() {
		return composite.getSelfRedirectCanonicalizer();
	}

	@Override
	public boolean isRequestAuth() {
		return composite.isRequestAuth();
	}

	@Override
	public int getMaxRedirectAttempts() {
		return composite.getMaxRedirectAttempts();
	}

	@Override
	public boolean isTimestampSearch() {
		return composite.isTimestampSearch();
	}

	@Override
	public String getPerfStatsHeader() {
		return composite.getPerfStatsHeader();
	}

	@Override
	public String getWarcFileHeader() {
		return composite.getWarcFileHeader();
	}
	
	@Override
	public String getWaybackUrl() {
		return composite.getWaybackUrl();
	}
	
}
