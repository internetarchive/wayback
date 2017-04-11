package org.archive.wayback.archivalurl;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import org.archive.wayback.ResultCollectionURIConverter;
import org.archive.wayback.ResultURIConverter;
import org.archive.wayback.core.CaptureSearchResult;
import org.archive.wayback.core.CaptureSearchResults;
import org.archive.wayback.core.Resource;
import org.archive.wayback.core.WaybackRequest;
import org.archive.wayback.replay.HttpHeaderProcessor;
import org.archive.wayback.replay.TextDocument;
import org.archive.wayback.util.Timestamp;
import org.archive.wayback.webapp.AccessPoint;

public class ArchivalUrlXMLReplayRenderer extends ArchivalUrlJSReplayRenderer {

    private final static String DEFAULT_URL_PATTERN = "((?:https?:)?\\\\?/\\\\?/[A-Za-z0-9:_@-]+\\.)";
    private final static String RSS_LINK_ELEMENT = "<link>";
    private final static Pattern IS_RSS_PATTERN = Pattern.compile(".*<rss.*</rss>.*", Pattern.DOTALL);
    private final static Pattern RSS_LINK_URL_PATTERN = Pattern
            .compile(RSS_LINK_ELEMENT + DEFAULT_URL_PATTERN);

    private Pattern isRssPattern = IS_RSS_PATTERN;
    private Pattern rssLinkPattern = RSS_LINK_URL_PATTERN;
    private ResultCollectionURIConverter rssUriConverter = null;
    private String waybackHost = null;
    
    public ArchivalUrlXMLReplayRenderer(
            HttpHeaderProcessor httpHeaderProcessor) {
        
        super(httpHeaderProcessor);
    }
    
    public String getWaybackHost() {
        return waybackHost;
    }

    public void setWaybackHost(String waybackHost) {
        this.waybackHost = waybackHost;
    }
    
    public void setIsRssRegex(String regex)
    {
        isRssPattern = Pattern.compile(regex);
    }
    
    public String getIsRssRegex()
    {
        return isRssPattern.pattern();
    }
    
    public void setRssLinkUrlRegex(String regex)
    {
        rssLinkPattern = Pattern.compile(regex);
    }
    
    public String getRssLinkUrlRegex()
    {
        return rssLinkPattern.pattern();
    }
    
    public ResultCollectionURIConverter getRssUriConverter() {
        return this.rssUriConverter;
    }
    
    public void setRssUriConverter(
            ResultCollectionURIConverter rssUriConverter) {
        this.rssUriConverter = rssUriConverter;
    }
    
    protected void replaceLinkUrls(String source, StringBuffer target, String resourceTimestamp, 
            String captureTimestamp, ResultURIConverter uriConverter, WaybackRequest wbRequest) {
        
        Matcher m = rssLinkPattern.matcher(source);
        
        // If at least 2 groups, prepend before 2nd group and include 1st group. Allows for more sophisticated matching.
        // Otherwise, insert before 1st group
        if (m.groupCount() > 1) {
            while (m.find()) {
                String beforeHost = m.group(1);
                String host = m.group(2);
                
                StringBuffer replacement = new StringBuffer();
                
                replacement.append(RSS_LINK_ELEMENT);
                
                replacement.append(rssUriConverter.makeReplayURI(
                        captureTimestamp, wbRequest.getAccessPoint().getConfigs().getProperty("collId"),
                            host));
                
                m.appendReplacement(target, beforeHost + replacement.toString());
            }   
        } else {
            while (m.find()) {
                String host = m.group(1);
                StringBuffer replacement = new StringBuffer();
                replacement.append(RSS_LINK_ELEMENT);
                
                replacement.append(rssUriConverter.makeReplayURI(captureTimestamp, 
                        wbRequest.getAccessPoint().getConfigs().getProperty("collId"),
                        host));
                
                m.appendReplacement(target, replacement.toString());
            }           
        }
        
        m.appendTail(target);
    }
    
    protected void replaceNonLinkUrls(String source, StringBuffer target, String resourceTimestamp, String captureTimestamp, ResultURIConverter uriConverter) {
        
        Matcher m = getPattern().matcher(source);
        
        // If at least 2 groups, prepend before 2nd group and include 1st group. Allows for more sophisticated matching.
        // Otherwise, insert before 1st group
        if (m.groupCount() > 1) {
            while (m.find()) {
                String beforeHost = m.group(1);
                String host = m.group(2);
                
                if(host.indexOf(getWaybackHost()) != -1) {
                    continue;
                }
                
                String replacement = uriConverter.makeReplayURI(captureTimestamp, host);
                
                m.appendReplacement(target, beforeHost + replacement);
            }   
        } else {
            while (m.find()) {
                String host = m.group(1);

                if(host.indexOf(getWaybackHost()) != -1) {
                    continue;
                }
                
                
                
                String replacement = uriConverter.makeReplayURI(captureTimestamp, host);
                
                m.appendReplacement(target, replacement);
            }           
        }
        
        m.appendTail(target);
    }    
    
    protected void updatePage(TextDocument page,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse,
            WaybackRequest wbRequest, CaptureSearchResult result,
            Resource resource, ResultURIConverter uriConverter,
            CaptureSearchResults results) throws ServletException, IOException {
        
        Matcher isRssMatcher = isRssPattern.matcher(page.sb);
        
        if (isRssMatcher.matches()) {
            
            String resourceTS = result.getCaptureTimestamp();
            String captureTS = Timestamp.parseBefore(resourceTS).getDateStr();

            StringBuilder sb = page.sb;
            StringBuffer replaced = new StringBuffer(sb.length());
            
            replaceLinkUrls(sb.toString(), replaced, resourceTS, captureTS, uriConverter, wbRequest);
            
            StringBuffer replacedAgain = new StringBuffer(replaced.length());
            
            replaceNonLinkUrls(replaced.toString(), replacedAgain, resourceTS, captureTS, uriConverter);

            // blasted StringBuilder/StringBuffer... gotta convert again...
            page.sb.setLength(0);
            page.sb.ensureCapacity(replacedAgain.length());
            page.sb.append(replacedAgain);

            // if any JS-specific jsp inserts are configured, run and insert...
            List<String> jspInserts = getJspInserts();

            StringBuilder toInsert = new StringBuilder(300);

            if (jspInserts != null) {
                Iterator<String> itr = jspInserts.iterator();
                while (itr.hasNext()) {
                    toInsert.append(page.includeJspString(itr.next(), httpRequest,
                            httpResponse, wbRequest, results, result, resource));
                }
            }

            page.insertAtStartOfDocument(toInsert.toString());
        }
        else {
            //not rss, just do default xml behavior
            super.updatePage(page, httpRequest, httpResponse, wbRequest, result, resource, uriConverter, results);
            
        }

    }    
     
}

