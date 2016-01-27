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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.accesscontrol.AccessControlClient;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.accesscontrol.CompositeExclusionFilterFactory;
import org.archive.wayback.accesscontrol.ExclusionFilterFactory;
import org.archive.wayback.accesscontrol.oracleclient.OracleExclusionFilterFactory;
import org.archive.wayback.accesscontrol.oracleclient.OraclePolicyService;
import org.archive.wayback.replay.html.ContextResultURIConverterFactory;
import org.archive.wayback.webapp.AccessPoint;

/**
 *
 * <p>
 * 2014-11-06: {@code oracleUrl} property is removed. Use
 * {@link OracleExclusionFilterFactory} instead.
 * </p>
 * <p>
 * 2014-11-06: {@code staticExclusions} property is removed. Configure
 * {@code exclusionFactory} property in Spring configuration with
 * {@link CompositeExclusionFilterFactory}.
 * </p>
 */
public class CompositeAccessPoint extends AccessPoint {

	protected final static String REQUEST_CONTEXT_PREFIX = "webapp-request-context-path-prefix";

	protected enum Status {
		ConfigNotFound, ConfigHandled, ConfigNotHandled,
	}

	private HashMap<String, AccessPointAdapter> accessPointCache;

	public CompositeAccessPoint() {
		accessPointCache = new HashMap<String, AccessPointAdapter>();
	}

	@Override
	public boolean handleRequest(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {

		String configName = this.translateRequestPath(request);

		if (!configName.isEmpty() && (configName.charAt(0) == '/')) {
			configName = configName.substring(1);
		}

		int slash = configName.indexOf('/');

		if (slash >= 0) {
			configName = configName.substring(0, slash);
		}

		Object existingPrefixObj = request.getAttribute(REQUEST_CONTEXT_PREFIX);

		String existingPrefix = (existingPrefixObj != null) ? existingPrefixObj
			.toString() : "/";

		request.setAttribute(REQUEST_CONTEXT_PREFIX, existingPrefix +
				configName + "/");

		Status status = handleRequest(configName, request, response);
		return (status == Status.ConfigHandled);
	}

	protected AccessPointAdapter getNewAccessPointAdapter(CompositeAccessPoint baseAccessPoint, AccessPointConfig config) {
		return new AccessPointAdapter(baseAccessPoint, config);
	}
	
	protected Status handleRequest(String realAccessPoint,
			HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// First, check cached accessPoint
		AccessPointAdapter adapter = accessPointCache.get(realAccessPoint);

		if ((adapter == null) && (accessPointConfigs != null)) {
			AccessPointConfig config = accessPointConfigs
				.getAccessPointConfigs().get(realAccessPoint);

			if (config != null) {
				adapter = getNewAccessPointAdapter(this, config);
				accessPointCache.put(realAccessPoint, adapter);
			}
		}

		if (adapter == null) {
			return Status.ConfigNotFound;
		}

		boolean handled = adapter.handleRequest(request, response);
		return (handled ? Status.ConfigHandled : Status.ConfigNotHandled);
	}

	// Refactoring: move uriConverterFactory and getUriConverterFactory() to
	// AccessPoint, so that ResultURIConverter can be bootstrapped in a
	// consistent manner.

	private ContextResultURIConverterFactory uriConverterFactory;

	public ContextResultURIConverterFactory getUriConverterFactory() {
		return uriConverterFactory;
	}

	/**
	 * set {@link ContextResultURIConverterFactory} used for creating
	 * {@link ResultURIConverter} for each sub-{@code AccessPoint}.
	 * <p>
	 * it will receive default {@code replayURIPrefix} for sub-AccessPoint as
	 * {@code flags} argument.
	 * </p>
	 * @param uriConverterFactory
	 */
	public void setUriConverterFactory(
			ContextResultURIConverterFactory uriConverterFactory) {
		this.uriConverterFactory = uriConverterFactory;
	}

	private AccessPointConfigs accessPointConfigs;

	public AccessPointConfigs getAccessPointConfigs() {
		return accessPointConfigs;
	}

	public void setAccessPointConfigs(AccessPointConfigs accessPointConfigs) {
		this.accessPointConfigs = accessPointConfigs;
	}

	public AccessPointConfig findConfigForFile(String file) {
		for (AccessPointConfig config : accessPointConfigs
			.getAccessPointConfigs().values()) {
			for (String prefix : config.getFileIncludePrefixes()) {
				if (file.startsWith(prefix)) {
					return config;
				}
			}
		}

		return null;
	}

	// Overriden in ProxyAccessPoint when using proxy mode
	public boolean isProxyEnabled() {
		return false;
	}

	// deprecated members

	private String oracleUrl;
	private ArrayList<ExclusionFilterFactory> staticExclusions;

	@Deprecated
	public String getOracleUrl() {
		return oracleUrl;
	}

	/**
	 * Service point URL for {@link AccessControlClient}
	 * @param oracleUrl URL
	 * @deprecated 2014-11-06 Use {@link OraclePolicyService}
	 */
	public void setOracleUrl(String oracleUrl) {
		this.oracleUrl = oracleUrl;
	}

	@Deprecated
	public ArrayList<ExclusionFilterFactory> getStaticExclusions() {
		return staticExclusions;
	}

	/**
	 * Factories for static (collection independent) exclusion filters.
	 * @param staticExclusions list of exclusion filter factories
	 * @deprecated 2014-11-06 configure {@link AccessPoint#setExclusionFactory}
	 * using {@link CompositeExclusionFilterFactory}
	 */
	public void setStaticExclusions(
			ArrayList<ExclusionFilterFactory> staticExclusions) {
		this.staticExclusions = staticExclusions;
	}
}
