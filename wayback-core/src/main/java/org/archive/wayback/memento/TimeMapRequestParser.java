package org.archive.wayback.memento;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.exception.BadQueryException;
import org.archive.wayback.exception.BetterRequestException;
import org.archive.wayback.requestparser.BaseRequestParser;
import org.archive.wayback.requestparser.WrappedRequestParser;
import org.archive.wayback.webapp.AccessPoint;
import org.archive.wayback.util.Timestamp;

/**
 * 
 * Class which parses TimeMap requests (/timemap/FORMAT/URL)
 * 
 * @author brad
 * 
 */

public class TimeMapRequestParser extends WrappedRequestParser implements
		MementoConstants {

	private static final Logger LOGGER = Logger
			.getLogger(TimeMapRequestParser.class.getName());
	// ludab nov30 2012
	public final static Pattern WB_REQUEST_REGEX = Pattern
			.compile("^(\\d{1,14})(([a-z]{2}[0-9]*_)*)/(.*)$");

	/**
	 * @param wrapped
	 *            BaseRequestParser holding config
	 */
	public TimeMapRequestParser(BaseRequestParser wrapped) {
		super(wrapped);
	}

	@Override
	public WaybackRequest parse(HttpServletRequest httpRequest,
			AccessPoint accessPoint) throws BadQueryException,
			BetterRequestException {
		
		if (!accessPoint.isEnableMemento()) {
			return null;
		}

		String requestPath = accessPoint.translateRequestPathQuery(httpRequest);
		LOGGER.fine("requestpath:" + requestPath);

		if (requestPath.startsWith(TIMEMAP)) {

			String urlStrplus = null, format = null, urlStr = null;
			int index = requestPath.indexOf("/");
			
			if (index >= 0) {
				urlStrplus = requestPath.substring(index + 1);
				
				index = urlStrplus.indexOf("/");
				
				if (index >= 0) {
					format = urlStrplus.substring(0, index);
					urlStr = urlStrplus.substring(index + 1);
				} else {
					format = urlStrplus;
				}
			}
			
			if (urlStr == null) {
				//Support CDX server query
				urlStr = httpRequest.getParameter("url");
			}
			
			if (format == null) {
				format = httpRequest.getParameter("output");
			}
			
			if (urlStr == null) {
				return null;
			}			
			
			LOGGER.fine(String.format("Parsed format(%s) URL(%s)", format,
					urlStr));

			WaybackRequest wbRequest = new WaybackRequest();
			// ludab changes nov 30 2012 to add timemap paging

			Matcher matcher = WB_REQUEST_REGEX.matcher(urlStr);
			String startDate = getEarliestTimestamp();

			if (matcher != null && matcher.matches()) {

				String dateStr = matcher.group(1);
				urlStr = matcher.group(4);
				wbRequest.put(PAGE_STARTS, dateStr);

				if (dateStr.length() == 0) {
					startDate = getEarliestTimestamp();

				} else {
					startDate = Timestamp.parseAfter(dateStr).getDateStr();

				}

			}

			if (wbRequest.getStartTimestamp() == null) {
				// ludab nov30 timemap paging
				wbRequest.setStartTimestamp(startDate);

				// wbRequest.setStartTimestamp(getEarliestTimestamp());
			}
			wbRequest.setAnchorTimestamp(getLatestTimestamp());
			if (wbRequest.getEndTimestamp() == null) {
				wbRequest.setEndTimestamp(getLatestTimestamp());
			}
			wbRequest.setCaptureQueryRequest();
			wbRequest.setMementoTimemapFormat(format);
			wbRequest.setRequestUrl(urlStr);
			int pagemax = MementoUtils.getPageMaxRecord(accessPoint);
			if (pagemax == 0) {
				wbRequest.setResultsPerPage(getMaxRecords());
			} else {
				wbRequest.setResultsPerPage(pagemax);
			}
			return wbRequest;
		}
		return null;
	}
}
