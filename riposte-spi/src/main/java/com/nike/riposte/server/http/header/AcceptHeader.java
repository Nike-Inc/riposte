package com.nike.riposte.server.http.header;

import com.nike.riposte.server.http.header.accept.MediaRange;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Models an RFC-2616 14.1 Accept Header, which is a list of MediaRange instances, sorted most-significant-first.
 *
 * @author Kirk Peterson
 */
@SuppressWarnings("WeakerAccess")
public class AcceptHeader implements Iterable<MediaRange> {

    /**
     * the MediaRanges found in this AcceptHeader, sorted by most-significant-first order.
     */
    public final List<MediaRange> mediaRanges;

    /**
     * Constructs an AcceptHeader using the given list of MediaRanges, sorting them in most-significant-first order.
     * Note: the given list will have Collections.sort(mediaRanges) performed on it,  ordering of the given list
     * could be modified. If you need to reuse the given list, you should pass in a copy instead.
     */
    public AcceptHeader(final List<MediaRange> mediaRanges) {
        Collections.sort(mediaRanges);
        this.mediaRanges = Collections.unmodifiableList(mediaRanges);
    }

    private String toStringCache = null;

    @Override
    public String toString() {
        if (toStringCache == null) {
            toStringCache = mediaRanges.stream().map(MediaRange::toString).collect(Collectors.joining(","));
        }
        return toStringCache;
    }

    @Override
    public Iterator<MediaRange> iterator() {
        return mediaRanges.iterator();
    }
}