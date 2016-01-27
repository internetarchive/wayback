package org.archive.cdxserver;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.cdxserver.auth.AuthToken;
import org.archive.cdxserver.filter.CDXAccessFilter;
import org.archive.format.gzip.zipnum.ZipNumCluster;
import org.archive.url.UrlSurtRangeComputer.MatchType;
import org.archive.util.ArchiveUtils;
import org.archive.util.binsearch.SortedTextFile.CachedStringIterator;
import org.archive.util.iterator.CloseableIterator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HowManyController extends BaseCDXServer {

    protected ZipNumCluster mainCluster;

    protected Map<String, ArrayList<ZipNumCluster>> allClusters;

    final static String PART_PREFIX = "part-a-";
    
    @Override
    public void afterPropertiesSet() throws Exception {
        // This controller only works with SURT-sorted CDXs!
        this.setSurtMode(true);
        super.afterPropertiesSet();
    }

    protected long countLines(ZipNumCluster cluster, String start, String end,
            String[] dates) throws IOException {
        long numLines = 0;

        if (cluster.isDisabled()) {
            return 0;
        }

        // optimize for full range lookup
        if (start.isEmpty() && end.isEmpty()) {
            numLines = cluster.getTotalLines();
            if (numLines > 0) {
                return numLines;
            }
        }

        // String[] splits = mainCluster.getSummary().getNthSplit(start, end,
        // split, numSplits)
        String[] startAndEndBlocks = cluster.getSummary().getRange(start, end);

        numLines = cluster.getNumLines(startAndEndBlocks);

        if (numLines <= 1) {
            numLines = 0;
        } else {
            numLines--;
        }

        long adjustment = 0;

        // If less than one block in size, just count the one block
        if (startAndEndBlocks[0].equals(startAndEndBlocks[1])) {
            numLines = 0;
            startAndEndBlocks[1] = null;
        } else {
            String startTokens[] = startAndEndBlocks[0].split("\t");
            String endTokens[] = startAndEndBlocks[1].split("\t");

            int startPart = Integer.parseInt(startTokens[1]
                    .substring(PART_PREFIX.length()));
            int endPart = Integer.parseInt(endTokens[1].substring(PART_PREFIX
                    .length()));

            if (startPart < endPart) {
                adjustment = cluster.getLastBlockDiff(startTokens[0],
                        startPart, endPart);
            }
        }

        int count = 0;
        CloseableIterator<String> blocklines = null;

        String firstline = null;
        String lastline = null;

        try {
            blocklines = cluster.getCDXIterator(new CachedStringIterator(
                    startAndEndBlocks[0], startAndEndBlocks[1]), start, end, 0,
                    1);

            if (blocklines.hasNext()) {
                count++;
                lastline = firstline = blocklines.next();
            }

            while (blocklines.hasNext()) {
                count++;
                lastline = blocklines.next();
            }
        } finally {
            if (blocklines != null) {
                blocklines.close();
            }
        }

        numLines *= cluster.getCdxLinesPerBlock();
        numLines += count;
        numLines += adjustment;

        if (dates != null) {
            if (firstline != null) {
                dates[0] = firstline.split(" ")[1];
            }
            if (lastline != null) {
                dates[1] = lastline.split(" ")[1];
            }
        }

        return numLines;
    }

    public static class FormCommand {
        private MatchType matchType = MatchType.domain;

        public FormCommand() {

        }

        public FormCommand(MatchType matchType) {
            this.matchType = matchType;
        }

        public MatchType getMatchType() {
            return matchType;
        }

        public void setMatchType(MatchType matchType) {
            this.matchType = matchType;
        }
    }

    @RequestMapping(value = { "/howmany/{clusterId}" })
    public String getHowManyCluster(
            HttpServletRequest request,
            HttpServletResponse response,            
            @RequestParam(value = "url", defaultValue = "") String url,
            @RequestParam(value = "from", defaultValue = "") String from,
            @RequestParam(value = "to", defaultValue = "") String to,
            @RequestParam(value = "matchType", defaultValue = "domain") MatchType matchType,
            @RequestParam(value = "format", defaultValue = "") String format,
            @PathVariable String clusterId,
            ModelMap model)
            throws URISyntaxException, IOException {
        return getHowMany(request, response, url, from, to, matchType, clusterId, format, model);
    }

    @RequestMapping(value = { "/howmany" })
    public String getHowMany(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(value = "url", defaultValue = "") String url,
            @RequestParam(value = "from", defaultValue = "") String from,
            @RequestParam(value = "to", defaultValue = "") String to,
            @RequestParam(value = "matchType", defaultValue = "domain") MatchType matchType,
            @RequestParam(value = "clusterId", defaultValue = "all") String clusterId,
            @RequestParam(value = "format", defaultValue = "") String format,
            ModelMap model) throws URISyntaxException, IOException {
        String start, end;
        String host;
        long numLines = 0;
        boolean restricted = false;
        
        handleAjax(request, response);

        if (url.isEmpty()) {
            start = url;
            end = url;
            host = "*";
        } else {
            AuthToken authToken = super.createAuthToken(request);
            
			CDXAccessFilter accessChecker = authChecker
				.createAccessFilter(authToken);
            
            String[] startEnd = urlSurtRangeComputer.determineRange(url, matchType, from, to);
            start = startEnd[0];
            end = startEnd[1];
            host = startEnd[2];
            
            if (accessChecker != null && !accessChecker.includeUrl(start, url)) {
                restricted = true;
            } 
        }

        String[] firstLastDate = null;

        if (matchType == MatchType.exact) {
            firstLastDate = new String[2];
        }
        
        if (!restricted) {
            numLines = countAllClusters(clusterId, start, end, firstLastDate);
        }
        
        model.addAttribute("count", Long.valueOf(numLines));
        
        if (format.equals("count")) {            
            return "count";
        }

        FormCommand fcmd = new FormCommand(matchType);
        model.addAttribute("command", fcmd);

        model.addAttribute("url", url);
        model.addAttribute("start", start);
        model.addAttribute("end", end);

        model.addAttribute("from", from);
        model.addAttribute("to", to);

        if ((matchType == MatchType.exact) && (firstLastDate != null)) {
            model.addAttribute("first",
                    ArchiveUtils.getDate(firstLastDate[0], new Date()));
            model.addAttribute("last",
                    ArchiveUtils.getDate(firstLastDate[1], new Date()));
        }

        model.addAttribute("host", host);
        model.addAttribute("matchType", matchType);

        return "howmany";
    }

    protected long countAllClusters(String clusterId, String start, String end,
            String[] firstLastDate) throws IOException {
        List<ZipNumCluster> clusters = null;

        if (!clusterId.isEmpty() && (allClusters != null)) {
            clusters = allClusters.get(clusterId);
        }

        long numLines;

        if (clusters == null) {
            numLines = this.countLines(mainCluster, start, end, firstLastDate);
        } else {
            numLines = 0;

            for (ZipNumCluster cluster : clusters) {
                long clusterLines = this.countLines(cluster, start, end,
                        firstLastDate);
                numLines += clusterLines;
            }
        }

        return numLines;
    }

    public ZipNumCluster getMainCluster() {
        return mainCluster;
    }

    public void setMainCluster(ZipNumCluster mainCluster) {
        this.mainCluster = mainCluster;
    }

    public Map<String, ArrayList<ZipNumCluster>> getAllClusters() {
        return allClusters;
    }

    public void setAllClusters(Map<String, ArrayList<ZipNumCluster>> allClusters) {
        this.allClusters = allClusters;
    }
}
