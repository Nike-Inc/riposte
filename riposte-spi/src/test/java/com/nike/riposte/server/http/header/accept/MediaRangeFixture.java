package com.nike.riposte.server.http.header.accept;

import com.nike.riposte.server.http.mimetype.MimeType;

import java.util.Collections;
import java.util.Optional;

/**
 * Created by dpet22 on 8/2/16.
 */
public class MediaRangeFixture {
    public final String mediaRangeString;
    public final MediaRange expectedMediaRange;

    public MediaRangeFixture(final String mediaRangeString, final MediaRange expectedMediaRange) {
        this.mediaRangeString = mediaRangeString;
        this.expectedMediaRange = expectedMediaRange;
    }

    public String getMediaRangeString() {
        return mediaRangeString;
    }

    public MediaRange getExpectedMediaRange() {
        return expectedMediaRange;
    }

    public Object[] toObjectArray() {
        return new Object[] {
                this.mediaRangeString,
                this.expectedMediaRange
        };
    }

    public static final MediaRangeFixture nikeRunningCoach = new MediaRangeFixture(
            "application/vnd.nike.runningcoach-v3.1+json;charset=utf-8",
            new MediaRange(
                    new MimeMediaRangeType(MimeType.Type.APPLICATION),
                    new MimeMediaRangeSubType(MimeType.SubType.of(MimeType.Facet.VENDOR,"nike.runningcoach-v3.1", Optional.of("json"))),
                    1.0f,
                    Collections.singletonMap("charset", "utf-8"),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture wildcardSubType = new MediaRangeFixture(
            "text/*;q=0.3",
            new MediaRange(
                    new MimeMediaRangeType(MimeType.Type.TEXT),
                    MediaRange.WILDCARD_SUBTYPE,
                    0.3f,
                    Collections.<String,String>emptyMap(),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture specificTypeSubTypeWithQuality = new MediaRangeFixture(
            "text/html;q=0.7",
            new MediaRange(
                    new MimeMediaRangeType(MimeType.Type.TEXT),
                    new MimeMediaRangeSubType(MimeType.SubType.of(MimeType.Facet.STANDARD,"html", Optional.empty())),
                    0.7f,
                    Collections.<String,String>emptyMap(),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture specificTypeSubTypeWithImplicitQuality = new MediaRangeFixture(
            "text/html",
            new MediaRange(
                    new MimeMediaRangeType(MimeType.Type.TEXT),
                    new MimeMediaRangeSubType(MimeType.SubType.of(MimeType.Facet.STANDARD,"html", Optional.empty())),
                    1.0f,
                    Collections.<String,String>emptyMap(),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture specificTypeSubTypeWithImplicitQualityAndMediaParam = new MediaRangeFixture(
            "text/html;charset=utf-8",
            new MediaRange(
                    new MimeMediaRangeType(MimeType.Type.TEXT),
                    new MimeMediaRangeSubType(MimeType.SubType.of(MimeType.Facet.STANDARD,"html", Optional.empty())),
                    1.0f,
                    Collections.singletonMap("charset", "utf-8"),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture wildcardTypeSubType = new MediaRangeFixture(
            "*/*",
            new MediaRange(
                    MediaRange.WILDCARD_TYPE,
                    MediaRange.WILDCARD_SUBTYPE,
                    1.0f,
                    Collections.<String,String>emptyMap(),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture wildcardTypeSubTypeWithQuality = new MediaRangeFixture(
            "*/*;q=0.2",
            new MediaRange(
                    MediaRange.WILDCARD_TYPE,
                    MediaRange.WILDCARD_SUBTYPE,
                    0.2f,
                    Collections.<String,String>emptyMap(),
                    Collections.<String,String>emptyMap()
            )
    );

    public static final MediaRangeFixture wildcardTypeSubTypeWithQualityAndAcceptParam = new MediaRangeFixture(
            "*/*;q=0.2;level=1",
            new MediaRange(
                    MediaRange.WILDCARD_TYPE,
                    MediaRange.WILDCARD_SUBTYPE,
                    0.2f,
                    Collections.<String,String>emptyMap(),
                    Collections.singletonMap("level", "1")
            )
    );


    public static final MediaRangeFixture[] fixtures = {
            wildcardSubType,
            specificTypeSubTypeWithQuality,
            specificTypeSubTypeWithImplicitQuality,
            specificTypeSubTypeWithImplicitQualityAndMediaParam,
            wildcardTypeSubType,
            wildcardTypeSubTypeWithQuality,
            wildcardTypeSubTypeWithQualityAndAcceptParam,
            nikeRunningCoach
    };
}
