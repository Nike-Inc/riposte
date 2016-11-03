package com.nike.riposte.server.http.header.accept;

import com.nike.riposte.server.http.mimetype.MimeType;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Each media-range MAY be followed by one or more accept-params, beginning with the "q" parameter for indicating
 * a relative quality factor.  The first "q" parameter (if any) separates the media-range parameter(s) from the
 * accept-params.
 * Quality factors allow the user or user agent to indicate the relative degree of preference for that media-range,
 * using the qvalue scale from 0 to 1 (RFC-2616 section 3.9). The default value is q=1.
 */
@SuppressWarnings("WeakerAccess")
public class MediaRange implements Comparable<MediaRange> {

    public final MediaRangeType type;
    public final MediaRangeSubType subType;
    public final Map<String, String> mediaRangeParameters;
    public final Map<String, String> acceptParameters;
    public final Float qualityFactor;

    /**
     * Constructs a MediaRange instance that represents superset of a MimeType, where like a MimeType there is a type,
     * subtype, and a map of parameters. However, the type may either be a valid mime type or a wildcard '*' type.
     * Likewise a subtype may also be either be a valid mime subtype or a wildcard '*'. Finally the list of optional
     * parameters are partitioned into media-range parameters, which preceed a quality-factor paramater, which is
     * followed by accept-parameters. For more information see RFC-2616 14.1
     *
     * @param type
     *     the MediaRange type, either a Mime or Wildcard value.
     * @param subType
     *     the MediaRange subtype, either a Mime or Wildcard value.
     * @param qualityFactor
     *     the quality-factor value, between 0 and 1.
     * @param mediaRangeParameters
     *     the media key-value parameters for this media-range, which preceeded the quality-factor parameter. NOTE: the
     *     given mediaRangeParameters map is not copied.
     * @param acceptParameters
     *     the accept key-valie parameters for this media-range, which succeeded the quality-factor parameter. NOTE: the
     *     given acceptParameters map is not copied.
     *
     * @throws IllegalArgumentException
     *     If the given  type, subType, or qualityFactor are null.
     * @throws IllegalArgumentException
     *     If the given qualityFactor less than 0 or greater than 1.
     */
    public MediaRange(
        final MediaRangeType type,
        final MediaRangeSubType subType,
        final Float qualityFactor,
        final Map<String, String> mediaRangeParameters,
        final Map<String, String> acceptParameters) {
        if (type == null) {
            throw new IllegalArgumentException("type instance cannot be null.");
        }
        this.type = type;

        if (subType == null) {
            throw new IllegalArgumentException("subType instance cannot be null.");
        }
        this.subType = subType;

        if (qualityFactor == null) {
            throw new IllegalArgumentException("qualityFactor instance cannot be null.");
        }
        if (qualityFactor > 1.0f) {
            throw new IllegalArgumentException("qualityFactor can not be greater than 1.");
        }
        if (qualityFactor < 0) {
            throw new IllegalArgumentException("qualityFactor can not be less than 0.");
        }
        this.qualityFactor = qualityFactor;

        this.mediaRangeParameters = mediaRangeParameters != null
                                    ? mediaRangeParameters
                                    : Collections.<String, String>emptyMap();

        this.acceptParameters = acceptParameters != null
                                ? acceptParameters
                                : Collections.<String, String>emptyMap();
    }

    /**
     * A Singleton instance that models a media-range's top level type being valid for all types.
     */
    public static final MediaRangeType WILDCARD_TYPE = new MediaRangeType() {
        @Override
        public String toString() {
            return "*";
        }
    };

    /**
     * A Singleton instance that models a media-range's sub-type being valid for all sub-types.
     */
    public static final MediaRangeSubType WILDCARD_SUBTYPE = new MediaRangeSubType() {
        @Override
        public String toString() {
            return "*";
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof MediaRange)) {
            return false;
        }

        MediaRange that = (MediaRange) o;

        return Objects.equals(type, that.type) &&
               Objects.equals(subType, that.subType) &&
               Objects.equals(qualityFactor, that.qualityFactor) &&
               Objects.equals(mediaRangeParameters, that.mediaRangeParameters) &&
               Objects.equals(acceptParameters, that.acceptParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, subType, qualityFactor, mediaRangeParameters, acceptParameters);
    }

    private String toStringCache = null;

    @Override
    public String toString() {
        if (toStringCache == null) {
            StringBuilder sb =
                new StringBuilder(type.toString())
                    .append("/")
                    .append(subType.toString());

            sb.append(
                mediaRangeParameters.entrySet().stream().map(entry -> ";" + entry.getKey() + "=" + entry.getValue())
                                    .collect(Collectors.joining())
            );

            if (1.0f > qualityFactor) {
                sb.append(";q=");
                sb.append(qualityFactor);
            }

            sb.append(
                acceptParameters.entrySet().stream().map(entry -> ";" + entry.getKey() + "=" + entry.getValue())
                                .collect(Collectors.joining())
            );
            toStringCache = sb.toString();
        }
        return toStringCache;
    }

    @Override
    public int compareTo(MediaRange that) {
        // quality-factor is the highest precedence.
        if (this.qualityFactor > that.qualityFactor)
            return -1; // This has a higher quality-factor than the other
        if (this.qualityFactor < that.qualityFactor)
            return 1;  // This has a lower  quality-factor than the other

        // quality-factors are equal.
        // the most most-specific type/subtype takes next precedence.

        // filter out if either instance's type property is a wildcard,
        if (WILDCARD_TYPE.equals(this.type) && WILDCARD_TYPE.equals(that.type))
            return 0;
        else if (WILDCARD_TYPE.equals(this.type))
            return 1;
        else if (WILDCARD_TYPE.equals(that.type))
            return -1;

        if (WILDCARD_SUBTYPE.equals(this.subType) && WILDCARD_SUBTYPE.equals(that.subType)) {
            // Both types are *not* wildcards, and both subtypes *are* wildcards. The RFC does not mention how to
            //      resolve order at this point. We'll just do a deterministic string comparison of the type names.
            return this.type.toString().compareTo(that.type.toString());
        }
        else if (WILDCARD_SUBTYPE.equals(this.subType))
            return 1;
        else if (WILDCARD_SUBTYPE.equals(that.subType))
            return -1;

        final int thisParamsCount = this.mediaRangeParameters.size() + this.acceptParameters.size();
        final int thatParamsCount = that.mediaRangeParameters.size() + that.acceptParameters.size();

        final int paramsDelta = thatParamsCount - thisParamsCount;

        if (paramsDelta != 0)
            return paramsDelta;

        // at this point we have two media ranges that have explicit type definitions.
        // the RFC does not mention how to resolve order at this point.
        // this implementation will give ord preference to non-standard faceted subtypes, under the assumption
        // that they are "more specific" than those faceted into the standard tree.

        final MimeType.Type thisType = ((MimeMediaRangeType) this.type).getType();
        final MimeType.Type thatType = ((MimeMediaRangeType) that.type).getType();

        final MimeType.SubType thisSubType = ((MimeMediaRangeSubType) this.subType).getSubType();
        final MimeType.SubType thatSubType = ((MimeMediaRangeSubType) that.subType).getSubType();

        if (thisSubType.facet == MimeType.Facet.STANDARD && !(thatSubType.facet == MimeType.Facet.STANDARD))
            return 1;
        if (!(thisSubType.facet == MimeType.Facet.STANDARD) && thatSubType.facet == MimeType.Facet.STANDARD)
            return -1;

        // next give preference on which range has specified a suffix.
        // Again, this is an implementation preference, not an RFC defined behavior.

        if (thisSubType.getSuffix().isPresent() && !(thatSubType.getSuffix().isPresent()))
            return -1;
        if (!(thisSubType.getSuffix().isPresent()) && thatSubType.getSuffix().isPresent())
            return 1;

        // at this point were comparing the type name and the subtype name.

        final int typeCompare = thisType.getName().compareTo(thatType.getName());
        if (typeCompare != 0)
            return typeCompare;

        final int subTypeCompare = thisSubType.getName().compareTo(thatSubType.getName());
        if (subTypeCompare != 0)
            return subTypeCompare;

        return 0;
    }
}