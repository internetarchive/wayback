
package org.archive.cdxserver;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.URIException;
import org.archive.cdxserver.CDXQuery.SortType;
import org.archive.cdxserver.auth.AuthToken;
import org.archive.cdxserver.filter.CDXAccessFilter;
import org.archive.cdxserver.filter.CollapseFieldFilter;
import org.archive.cdxserver.filter.FieldRegexFilter;
import org.archive.cdxserver.processor.BaseProcessor;
import org.archive.cdxserver.processor.ClosestTimestampSorted;
import org.archive.cdxserver.processor.DupeCountProcessor;
import org.archive.cdxserver.processor.DupeTimestampBestStatusFilter;
import org.archive.cdxserver.processor.DupeTimestampLastBestStatusFilter;
import org.archive.cdxserver.processor.ForwardRevisitResolver;
import org.archive.cdxserver.processor.GroupCountProcessor;
import org.archive.cdxserver.processor.LastNLineProcessor;
import org.archive.cdxserver.processor.ReverseRevisitResolver;
import org.archive.cdxserver.writer.CDXListWriter;
import org.archive.cdxserver.writer.CDXWriter;
import org.archive.cdxserver.writer.JsonWriter;
import org.archive.cdxserver.writer.MementoLinkWriter;
import org.archive.cdxserver.writer.PlainTextWriter;
import org.archive.format.cdx.CDXFieldConstants;
import org.archive.format.cdx.CDXInputSource;
import org.archive.format.cdx.CDXLine;
import org.archive.format.cdx.CDXLineFactory;
import org.archive.format.cdx.FieldSplitFormat;
import org.archive.format.cdx.StandardCDXLineFactory;
import org.archive.format.gzip.zipnum.LineBufferingIterator;
import org.archive.format.gzip.zipnum.ZipNumCluster;
import org.archive.format.gzip.zipnum.ZipNumIndex.PageResult;
import org.archive.format.gzip.zipnum.ZipNumParams;
import org.archive.url.UrlSurtRangeComputer;
import org.archive.url.UrlSurtRangeComputer.MatchType;
import org.archive.util.iterator.CloseableIterator;
import org.springframework.web.bind.annotation.RequestMapping;

public class CDXServer extends BaseCDXServer {

	protected ZipNumCluster zipnumSource;
	protected CDXInputSource cdxSource;

	protected String cdxFormat = null;

	protected CDXLineFactory cdxLineFactory;

	//protected FieldSplitFormat defaultCdxFormat;
	//protected FieldSplitFormat publicCdxFields;

	@Override
	public void afterPropertiesSet() throws Exception {
		if (cdxSource == null) {
			cdxSource = zipnumSource;
		}

		cdxLineFactory = new StandardCDXLineFactory(cdxFormat);
		// defaultCdxFormat = cdxLineFactory.getParseFormat();

		// if (authChecker != null && authChecker.getPublicCdxFields() != null)
		// {
		// publicCdxFields = new
		// FieldSplitFormat(authChecker.getPublicCdxFields());
		// }

		if (defaultParams == null) {
			defaultParams = new ZipNumParams(maxPageSize, maxPageSize, 0, false);
		}

		super.afterPropertiesSet();
	}

	protected int maxPageSize = 1;
	protected int queryMaxLimit = Integer.MAX_VALUE;

	protected String[] noCollapsePrefix = null;
	protected boolean collapseToLast = false;

	protected ZipNumParams defaultParams;

	public ZipNumCluster getZipnumSource() {
		return zipnumSource;
	}

	public void setZipnumSource(ZipNumCluster zipnumSource) {
		this.zipnumSource = zipnumSource;
	}

	public int getPageSize() {
		return maxPageSize;
	}

	public void setPageSize(int pageSize) {
		this.maxPageSize = pageSize;
	}

	public ZipNumParams getDefaultParams() {
		return defaultParams;
	}

	public void setDefaultParams(ZipNumParams defaultParams) {
		this.defaultParams = defaultParams;
	}

	public String getCdxFormat() {
		return cdxFormat;
	}

	public void setCdxFormat(String cdxFormat) {
		this.cdxFormat = cdxFormat;
	}

	public int getQueryMaxLimit() {
		return queryMaxLimit;
	}

	public void setQueryMaxLimit(int queryMaxLimit) {
		this.queryMaxLimit = queryMaxLimit;
	}

	public String[] getNoCollapsePrefix() {
		return noCollapsePrefix;
	}

	public void setNoCollapsePrefix(String[] noCollapsePrefix) {
		this.noCollapsePrefix = noCollapsePrefix;
	}

	/**
	 * @return the collapseToLast
	 */
	public boolean isCollapseToLast() {
		return collapseToLast;
	}

	/**
	 * If set to {@code true}, timestamp-collapsing writes out the last best
	 * capture in the collapse group, instead of the first.
	 * <p>Initial value is false.</p>
	 * @param collapseToLast the collapseToLast to set
	 * @see DupeTimestampLastBestStatusFilter
	 */
	public void setCollapseToLast(boolean collapseToLast) {
		this.collapseToLast = collapseToLast;
	}

	public CDXInputSource getCdxSource() {
		return cdxSource;
	}

	public void setCdxSource(CDXInputSource cdxSource) {
		this.cdxSource = cdxSource;
	}

	protected boolean determineGzip(HttpServletRequest request, CDXQuery query) {
		Boolean isGzip = query.isGzip();
		if (isGzip != null) {
			return isGzip;
		}

		String encoding = request.getHeader("Accept-Encoding");
		if (encoding == null) {
			return false;
		}

		return encoding.contains("gzip");
	}

	@RequestMapping(value = { "/cdx" })
	public void getCdx(HttpServletRequest request,
			HttpServletResponse response, CDXQuery query) {
		handleAjax(request, response);

		CDXWriter responseWriter = null;

		boolean gzip = determineGzip(request, query);

		try {

			if (query.output.equals("json")) {
				responseWriter = new JsonWriter(response, gzip);
			} else if (query.output.equals("memento")) {
				responseWriter = new MementoLinkWriter(request, response,
						query, gzip);
			} else {
				responseWriter = new PlainTextWriter(response, gzip);
			}

			//AuthToken authToken = super.createAuthToken(request);
			AuthToken authToken = new AuthToken();
			authChecker.authenticate(request, authToken);

			getCdx(query, authToken, responseWriter);

		} catch (IOException io) {
			responseWriter.serverError(io);
		} catch (RuntimeException rte) {
			responseWriter.serverError(rte);
		} finally {
			if (responseWriter != null) {
				responseWriter.close();
			}
		}
	}

	public void getCdx(CDXQuery query, AuthToken authToken,
			CDXWriter responseWriter) throws IOException {
		CloseableIterator<String> iter = null;

		try {
			// Check for wildcards as shortcuts for matchType
			if (query.matchType == null) {
				if (query.url.startsWith("*.")) {
					query.matchType = MatchType.domain;
					query.url = query.url.substring(2);
				} else if (query.url.endsWith("*")) {
					query.matchType = MatchType.prefix;
					query.url = query.url.substring(0, query.url.length() - 1);
				} else {
					query.matchType = MatchType.exact;
				}
			}

			CDXAccessFilter accessChecker = authChecker
				.createAccessFilter(authToken);

//			// For now, don't support domain or host output w/o key as access check is too slow
//			if (query.matchType == MatchType.domain || query.matchType == MatchType.host) {
//				if (!authChecker.isAllUrlAccessAllowed(authToken)) {
//					return;
//				}
//			}

			String startEndUrl[] = urlSurtRangeComputer.determineRange(
					query.url, query.matchType, "", "");

			if (startEndUrl == null) {
				responseWriter.printError("Sorry, matchType=" +
						query.matchType.name() +
						" is not supported by this server");
				return;
			}

			if ((accessChecker != null) &&
					!accessChecker.includeUrl(startEndUrl[0], query.url)) {
				if (query.showNumPages) {
					// Default to 1 page even if no results
					responseWriter.printNumPages(1, false);
				}
				return;
			}

			if (query.last || query.limit == -1) {
				query.limit = 1;
				query.setSort(SortType.reverse);
			}

			int maxLimit;

			if (query.fastLatest == null) {
				// Optimize: default fastLatest to true for last line or closest
				// sorted results
				if ((query.limit == -1) ||
						(!query.closest.isEmpty() && (query.limit > 0))) {
					query.fastLatest = true;
				} else {
					query.fastLatest = false;
				}
			}

			// Paged query
			if (query.page >= 0 || query.showNumPages) {
				iter = createPagedCdxIterator(startEndUrl, query, authToken,
						responseWriter);

				if (iter == null) {
					return;
				}

				// Page size determines the max limit here
				maxLimit = Integer.MAX_VALUE;

			} else {
				// Non-Paged Merged query
				iter = createBoundedCdxIterator(startEndUrl, query, null, null);

				// TODO: apply collection-view filtering here. It should happen separately
				// from exclusion check. We'd need to parse CDX lines into CDXLine object
				// before passing it to writeCdxResponse(). Pass CDXFilter to getCdx()?
				// Pass CDX source object that escapsulates collection-view filtering?

				maxLimit = this.queryMaxLimit;
			}

			writeCdxResponse(responseWriter, iter, maxLimit, query, authToken,
					accessChecker);

		} catch (URIException e) {
			responseWriter.printError(e.toString());
		} catch (URISyntaxException e) {
			responseWriter.printError(e.toString());
		} finally {
			if (iter != null) {
				iter.close();
			}
		}
	}
	
	protected CloseableIterator<String> createPagedCdxIterator(
			String[] startEndUrl, CDXQuery query, AuthToken authToken,
			CDXWriter responseWriter) throws IOException {
		if (zipnumSource == null) {
			responseWriter
					.printError("Sorry, this server is not configured to support paged query. Remove page= param and try again.");
			return null;
		}

		//boolean allAccess = authChecker.isAllUrlAccessAllowed(authToken);
		boolean allAccess = authToken.isAllUrlAccessAllowed();

		if ((query.pageSize <= 0) ||
				((query.pageSize > maxPageSize) && !allAccess)) {
			query.pageSize = maxPageSize;
		}

		PageResult pageResult = zipnumSource.getNthPage(startEndUrl,
				query.page, query.pageSize, query.showNumPages);

		if (query.showNumPages) {
			responseWriter.printNumPages(pageResult.numPages, true);
			return null;
		} else {
			responseWriter.printNumPages(pageResult.numPages, false);
		}

		CloseableIterator<String> iter = pageResult.iter;

		if (iter == null) {
			return null;
		}

		if (query.isReverse()) {
			iter = new LineBufferingIterator(iter, query.pageSize, true);
		}

		String zipnumClusterUri = zipnumSource.getLocRoot();

		if (query.showPagedIndex && allAccess) {
			responseWriter.setMaxLines(query.pageSize, zipnumClusterUri);
			writeIdxResponse(responseWriter, iter);
			return null;
		} else {
			responseWriter.setMaxLines(
					query.pageSize * zipnumSource.getCdxLinesPerBlock(),
					zipnumClusterUri);
		}

		iter = createBoundedCdxIterator(startEndUrl, query, pageResult, iter);

		return iter;
	}

    protected CloseableIterator<String> createBoundedCdxIterator(
            String[] startEndUrl, CDXQuery query, PageResult pageResult,
            CloseableIterator<String> idx) throws IOException {
        String searchKey = null;

		ZipNumParams params = new ZipNumParams(defaultParams);

        // Opt: testing out sequential load!
		if (Math.abs(query.limit) == 1) {
			params.setSequential(true);
		}

		params.setReverse(query.isReverse());

		if (!query.resumeKey.isEmpty()) {
			searchKey = URLDecoder.decode(query.resumeKey, "UTF-8");
            startEndUrl[0] = searchKey;
//            int lastSpace = startEndUrl[0].lastIndexOf(' ');
//            if (lastSpace > 0) {
//            	startEndUrl[0] = searchKey.substring(0, lastSpace);
//            }
        } else if (!query.from.isEmpty()) {
            searchKey = startEndUrl[0] + " " + query.from;
        } else if (query.isReverse() && !query.closest.isEmpty()) {
            searchKey = startEndUrl[0];
            startEndUrl[1] = startEndUrl[0] + " " + query.closest;
        } else if (query.fastLatest) {
            String endkey = (query.closest.isEmpty() ? "!" : " "
                    + query.closest);
            params.setMaxAggregateBlocks(1);
            searchKey = startEndUrl[0] + endkey;
        } else {
            searchKey = startEndUrl[0];
        }

        if (pageResult != null) {
            params.setTimestampDedupLength(0);
            return zipnumSource.getCDXIterator(idx, searchKey, startEndUrl[1],
                    query.page, pageResult.numPages, params);
        } else {
            return cdxSource.getCDXIterator(searchKey, startEndUrl[0],
                    startEndUrl[1], params);
        }
    }

	// TODO: Support idx/summary in json?
    protected void writeIdxResponse(CDXWriter responseWriter,
            CloseableIterator<String> iter) {
        responseWriter.begin();

        while (iter.hasNext()) {
            responseWriter.writeMiscLine(iter.next());
        }

        responseWriter.end();
    }

	protected void writeCdxResponse(CDXWriter responseWriter,
			CloseableIterator<String> cdx, int readLimit,

			CDXQuery query, AuthToken authToken, CDXAccessFilter accessChecker) {

		BaseProcessor outputProcessor = responseWriter;

		if (query.limit < 0) {
			query.limit = Math.min(-query.limit, readLimit);
			outputProcessor = new LastNLineProcessor(outputProcessor,
					query.limit);
		} else if (query.limit == 0) {
			query.limit = readLimit;
		} else {
			query.limit = Math.min(query.limit, readLimit);
		}

		if (!query.closest.isEmpty() && query.isSortClosest()) {
			outputProcessor = new ClosestTimestampSorted(outputProcessor,
					query.closest, query.limit);
		}

		// Experimental
		if (query.resolveRevisits) {
			if (query.isReverse()) {
				outputProcessor = new ReverseRevisitResolver(outputProcessor,
						query.showDupeCount);
			} else {
				outputProcessor = new ForwardRevisitResolver(outputProcessor,
						query.showDupeCount);
			}
		} else if (query.showDupeCount) {
			outputProcessor = new DupeCountProcessor(outputProcessor, true);
		}

		if (query.showGroupCount || query.showUniqCount) {
			outputProcessor = new GroupCountProcessor(outputProcessor,
					query.lastSkipTimestamp, query.showUniqCount);
		}

		if (query.collapseTime > 0) {
			if (collapseToLast) {
				outputProcessor = new DupeTimestampLastBestStatusFilter(
					outputProcessor, query.collapseTime, noCollapsePrefix);
			} else {
				outputProcessor = new DupeTimestampBestStatusFilter(
					outputProcessor, query.collapseTime, noCollapsePrefix);
			}
		}

		FieldSplitFormat parseFormat = outputProcessor
				.modifyOutputFormat(cdxLineFactory.getParseFormat());

		FieldRegexFilter filterMatcher = null;

		if (query.filter != null && (query.filter.length > 0)) {
			filterMatcher = new FieldRegexFilter(query.filter, parseFormat);
		}

		CollapseFieldFilter collapser = null;

		if (query.collapse != null && (query.collapse.length > 0)) {
			collapser = new CollapseFieldFilter(query.collapse, parseFormat);
		}

		// CDXLine prev = null;
		CDXLine line = null;

		// boolean prevUrlAllowed = true;

		FieldSplitFormat outputFields = null;

		if (!authToken.isAllCdxFieldAccessAllowed()) {
			outputFields = this.authChecker.getPublicCdxFormat();
		}

		if (!query.fl.isEmpty()) {
			if (outputFields == null) {
				outputFields = parseFormat;
			}
			try {
				outputFields = outputFields.createSubset(URLDecoder.decode(
						query.fl, "UTF-8"));
			} catch (UnsupportedEncodingException e) {

			}
		} else if (outputFields != null) {
			outputFields = parseFormat.createSubset(outputFields);
		}

		outputProcessor.begin();

		int writeCount = 0;
		long allCount = 0;

		int writeLimit = query.limit;

		while (cdx.hasNext() &&
				((writeLimit == 0) || (writeCount < writeLimit)) &&
				(allCount < readLimit) && !responseWriter.isAborted()) {

			String rawLine = cdx.next();
			allCount++;

			if (query.offset > 0) {
				--query.offset;
				continue;
			}

			// prev = line;

			// line = new CDXLine(rawLine, parseFormat);
			line = this.cdxLineFactory.createStandardCDXLine(rawLine,
					parseFormat);

			// TODO: better way to handle this special case?
			if (line.getMimeType().equals("alexa/dat")) {
				continue;
			}
            
            		// Timestamp Range Filtering
            		String timestamp = line.getTimestamp();
            
            		if (!query.from.isEmpty() && (timestamp.compareTo(query.from) < 0)) {
                		continue;
            		}
            
            		if (!query.to.isEmpty() && (timestamp.compareTo(query.to) > 0)
                    		&& !timestamp.startsWith(query.to)) {
                		if (query.matchType == MatchType.exact) {
                    			break;
                		} else {
                    			continue;
                		}
            		}

			// Additional access check, per capture
			if (accessChecker != null) {
				if (!accessChecker.includeCapture(line)) {
					continue;
				}
			}

			//			if (!authChecker.isAllUrlAccessAllowed(authToken)) {
//				if ((query.matchType != MatchType.exact) && ((prev == null) || !line.getUrlKey().equals(prev.getUrlKey()))) {
//					prevUrlAllowed = authChecker.isUrlAllowed(line.getOriginalUrl(), authToken);
//				}
//
//				if (!prevUrlAllowed) {
//					continue;
//				}
//			}
//			
//			if (!authChecker.isCaptureAllowed(line, authToken)) {
//				continue;
//			}
//			
			outputProcessor.trackLine(line);


			// Check regex matcher if it exists
			if ((filterMatcher != null) && !filterMatcher.include(line)) {
				continue;
			}

			// Check collapser
			if ((collapser != null) && !collapser.include(line)) {
				continue;
			}

			// Filter to only include output fields
			if (outputFields != null) {
				line = new CDXLine(line, outputFields);
			}

			writeCount += outputProcessor.writeLine(line);

			if (Thread.interrupted()) {
				break;
			}
		}

		if (query.showResumeKey && (line != null) && (writeLimit > 0) &&
				(writeCount >= writeLimit)) {
			StringBuilder sb = new StringBuilder();
			sb.append(line.getUrlKey());
			sb.append(' ');
			sb.append(UrlSurtRangeComputer.incLastChar(line.getTimestamp()));
			String resumeKey;
			try {
				resumeKey = URLEncoder.encode(sb.toString(), "UTF-8");
				outputProcessor.writeResumeKey(resumeKey);
			} catch (UnsupportedEncodingException e) {

			}
		}

		outputProcessor.end();
	}

	/**
	 * Look up the latest (non-revisit) capture of {@code url} in the
	 * CDX database.
	 * If {@code digest} is non-{@code null}, return only a capture with
	 * identical digest.
	 * @param url URL (in regular form) to look for
	 * @param digest content digest in the same format as CDX database,
	 * or {@code null} if any version qualifies.
	 * @param ignoreRobots whether robots.txt-excluded captures qualify
	 * @return CDXLine found
	 */
	public CDXLine findLastCapture(String url, String digest, boolean ignoreRobots) {
		final String WARC_REVISIT = "warc/revisit";
		final String REVISIT_FILTER = "!mimetype:" + WARC_REVISIT;

		CDXListWriter listWriter = new CDXListWriter();

		CDXQuery query = new CDXQuery(url);
		query.setFilter(new String[] {
			CDXFieldConstants.digest + ":" + digest,
			REVISIT_FILTER
		});
		query.setLimit(-1);

		AuthToken auth = new AuthToken();
		auth.setIgnoreRobots(ignoreRobots);

		try {
			getCdx(query, auth, listWriter);
		} catch (IOException e) {
			// No dedup info
			return null;
		} catch (RuntimeException re) {
			// Keeping the original code as comment.
			// Cannot throw AccessControlException from CDXServer
			// because it is currently defined in wayback-core, on
			// which wayback-cdxserver cannot depend.
			// As AccessControlException is thrown when entire url
			// is excluded (by robots.txt exclusion or some other rules),
			// it should be okay to consider it as"non-existent".
//			Throwable cause = re.getCause();
//
//			// Propagate AccessControlException
//			if (cause instanceof AccessControlException) {
//				throw (AccessControlException)cause;
//			}

			return null;
		}

		if (!listWriter.getCDXLines().isEmpty()) {
			CDXLine line = listWriter.getCDXLines().get(0);
			// Just check the last line for the digest
			if (digest == null || line.getDigest().equals(digest)) {
				return line;
			}
		}

		return null;
	}
}
