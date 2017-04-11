package org.archive.wayback;

public interface ResultCollectionURIConverter extends ResultURIConverter {

    /**
     * return an absolute URL that will replay URL url at time datespec.
     * 
     * @param datespec 14-digit timestamp for the desired Resource
     * @param url for the desired Resource
     * @return absolute replay URL
     */
    public String makeReplayURI(final String datespec, final String collectionId, final String url);    
}
