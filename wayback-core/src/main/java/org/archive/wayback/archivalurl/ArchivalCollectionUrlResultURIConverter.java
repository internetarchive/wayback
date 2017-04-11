package org.archive.wayback.archivalurl;

import org.archive.wayback.ResultCollectionURIConverter;
import org.archive.wayback.util.url.UrlOperations;

public class ArchivalCollectionUrlResultURIConverter extends
        ArchivalUrlResultURIConverter implements
        ResultCollectionURIConverter {

    @Override
    public String makeReplayURI(String datespec, String collectionId, String url) {
        StringBuilder sb = null;

        if(getReplayURIPrefix() == null) {
            sb = new StringBuilder(url.length() + collectionId.length() + datespec.length());
            sb.append(collectionId);
            sb.append("/");
            sb.append(datespec);
            sb.append("/");
            sb.append(UrlOperations.stripDefaultPortFromUrl(url));
            return sb.toString();
        }
        if(url.startsWith(getReplayURIPrefix())) {
            return url;
        }
        sb = new StringBuilder(url.length() + collectionId.length() + datespec.length());
        
        sb.append(getReplayURIPrefix());
        sb.append(collectionId);
        sb.append("/");
        sb.append(datespec);
        sb.append("/");
        sb.append(UrlOperations.stripDefaultPortFromUrl(url));
        return sb.toString();
    }

}
