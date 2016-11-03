package com.nike.riposte.server.http.header.accept;

import com.nike.riposte.server.http.mimetype.MimeType.SubType;

import java.util.Objects;

/**
 * Models a subtype that is a specific mime subtype, rather than a wildcard subtype.
 *
 * @author Kirk Peterson
 */
@SuppressWarnings("WeakerAccess")
public class MimeMediaRangeSubType implements MediaRangeSubType {

    public final SubType subType;

    /**
     * Creates a MediaRangeSubType instace using the given Mime SubType.
     *
     * @param subType
     *     the sub type to construct a MimeMediaRangeSubType from.
     *
     * @throws IllegalArgumentException
     *     If the given subType is null.
     */
    public MimeMediaRangeSubType(SubType subType) {
        if (subType == null) {
            throw new IllegalArgumentException("subType instance cannot be null.");
        }
        this.subType = subType;
    }

    /**
     * returns the sub type used to construct this MimeMediaRangeSubType instance from.
     *
     * @return the sub type used to construct this MimeMediaRangeSubType instance from.
     */
    public SubType getSubType() {
        return subType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MimeMediaRangeSubType)) {
            return false;
        }

        MimeMediaRangeSubType that = (MimeMediaRangeSubType) o;

        return Objects.equals(subType, that.subType);
    }

    @Override
    public int hashCode() {
        return subType.hashCode();
    }

    private String toStringCache = null;

    @Override
    public String toString() {
        if (toStringCache == null) {
            toStringCache = subType.getFacet().getRegistrationTreeName().map(tree -> tree + ".").orElse("") +
                            subType.getName() +
                            subType.getSuffix().map(suffix -> "+" + suffix).orElse("");
        }
        return toStringCache;
    }
}