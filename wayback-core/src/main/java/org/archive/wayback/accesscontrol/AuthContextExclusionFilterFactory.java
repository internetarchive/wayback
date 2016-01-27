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

package org.archive.wayback.accesscontrol;

import org.archive.cdxserver.auth.AuthToken;
import org.archive.wayback.exception.AccessControlException;
import org.archive.wayback.resourceindex.filters.ExclusionFilter;

/**
 * AuthContextExclusionFilterFactory extends ContextExclusionFilterFactory
 * by adding {@link AuthToken} argument to {@code getExclusionFilter} method
 * to enable exclusion filter configuration based on client information.
 * <p>
 * This will the new interface for exclusion filter factory
 * in future versions of Wayback. This is currently defined as
 * an extension of old interface for backward-compatibility.
 * {@link ContextExclusionFilterFactory#getExclusionFilter(CollectionContext)}
 * can be simply implemented as:
 * <pre>
 * public ExclusionFilter getExclusionFilter(CollectionContext context) {
 *     try {
 *         return getExclusionFilter(context, null);
 *     } catch (AccessControlException ex) {
 *         return null;
 *     }
 * }
 * </pre>
 * </p>
 */
public interface AuthContextExclusionFilterFactory extends
		ContextExclusionFilterFactory {
	/**
	 * Return {@link ExclusionFilter} for use in replay or search
	 * session, based on collection context {@context}, and
	 * client information in {@code subject}.
	 * <p>This method can return {@code null} if exclusion filter is
	 * unnecessary for the context given.</p>
	 * @param context collection context
	 * @param subject client information (may be {@code null})
	 * @return per-request exclusion instance, or {@code null}
	 * @throws AccessControlException for critical error in access
	 * control, that prevents normal operation.
	 */
	public ExclusionFilter getExclusionFilter(CollectionContext context,
			AuthToken subject) throws AccessControlException;
}
