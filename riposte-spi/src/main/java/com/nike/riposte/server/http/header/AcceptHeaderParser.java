package com.nike.riposte.server.http.header;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.header.accept.MediaRange;
import com.nike.riposte.server.http.header.accept.MediaRangeSubType;
import com.nike.riposte.server.http.header.accept.MediaRangeType;
import com.nike.riposte.server.http.header.accept.MimeMediaRangeSubType;
import com.nike.riposte.server.http.header.accept.MimeMediaRangeType;
import com.nike.riposte.util.text.parsercombinator.Parser;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.nike.riposte.server.http.mimetype.MimeTypeParser.parameterParser;
import static com.nike.riposte.server.http.mimetype.MimeTypeParser.semicolon;
import static com.nike.riposte.server.http.mimetype.MimeTypeParser.subTypeParser;
import static com.nike.riposte.server.http.mimetype.MimeTypeParser.token;
import static com.nike.riposte.server.http.mimetype.MimeTypeParser.typeParser;
import static com.nike.riposte.util.text.parsercombinator.Parser.Apply.match;
import static com.nike.riposte.util.text.parsercombinator.Parser.Apply.test;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.filter;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.floatNumber;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.oneOf;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.regex;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.skip;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.string;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.zeroOrMore;

/**
 * Offers a set of Parser instances for parsing the components of an RFC-2616 14.1 Accept Header.
 */
@SuppressWarnings("WeakerAccess")
public class AcceptHeaderParser {

    /**
     * A Parser that extracts the quality-factor from a key-value pair.
     */
    public static Parser<Float> qualityFactorParser =
        skip(regex("\\s*;?\\s*q\\s*=\\s*")).thenParse(filter(floatNumber(), q -> q > 0.0f && q <= 1.0f));

    /**
     * A Parser that extracts a key-value pair that preceed the quality-factor header.
     */
    public static Parser<Pair<String, String>> mediaRangeParamaterParser =
        filter(token, t -> !"q".equals(t)).thenSkip(regex("\\s*q?\\s*=\\s*")).thenParse(token);

    /**
     * A Parser that extracts a list of  key-value pairs which preceed the quality-factor header, named media-params.
     */
    public static final Parser<Map<String, String>> mediaRangeParamatersParser =
        (Parser<Map<String, String>>) skip(
            regex("\\s*;?\\s*")) //explicitly cast due to Eclipse compiler not being able to infer type correctly
                                 .thenParse(zeroOrMore(mediaRangeParamaterParser, semicolon))
                                 .map(list -> list.stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight)));

    /**
     * A Parser for extracting the key-value pairs that follow the quality-factor header, named accept-params.
     */
    public static final Parser<Map<String, String>> acceptParamatersParser =
        (Parser<Map<String, String>>) skip(
            regex("\\s*;?\\s*"))  //explicitly cast due to Eclipse compiler not being able to infer type correctly
                                  .thenParse(zeroOrMore(parameterParser, semicolon))
                                  .map(list -> list.stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight)));

    /**
     * A Parser for extracting a MediaRange's top-level type.
     */
    public static final Parser<MediaRangeType> mediaRangeTypeParser = oneOf(
        string("*").map(s -> MediaRange.WILDCARD_TYPE),
        typeParser.map(MimeMediaRangeType::new)
    );

    /**
     * A Parser for extracting a MediaRange's subtype.
     */
    public static final Parser<MediaRangeSubType> mediaRangeSubTypeParser = oneOf(
        string("*").map(s -> MediaRange.WILDCARD_SUBTYPE),
        subTypeParser.map(MimeMediaRangeSubType::new)
    );

    /**
     * A Parser for extracting a MediaRange instance.
     */
    @SuppressWarnings("SimplifiableConditionalExpression")
    public static final Parser<MediaRange> mediaRangeParser =
        mediaRangeTypeParser
            .thenSkip(string("/"))
            .thenParse(mediaRangeSubTypeParser)
            // media-params preceed the quality-factor param
            .thenParse(mediaRangeParamatersParser)
            // optional quality-factor
            .thenParse(qualityFactorParser.optional())
            // accept-params preceed the quality-factor param
            .thenParse(acceptParamatersParser)
            // If the type is a wildcard, then the subtype must be a wildcard
            .filter(test(
                (type, subType, mediaRangeParamaters, qualityFactor, acceptParamaters) ->
                    (MediaRange.WILDCARD_TYPE.equals(type))
                    ? (MediaRange.WILDCARD_SUBTYPE.equals(subType))
                    : true
            ))
            .map(match((type, subType, mediaRangeParamaters, qualityFactor, acceptParamaters) ->
                           new MediaRange(type,
                                          subType,
                                          qualityFactor.orElse(1.0f),
                                          mediaRangeParamaters,
                                          acceptParamaters
                           )
            ));

    /**
     * A Parser for extracting a list of MediaRangeInstances
     */
    public static final Parser<List<MediaRange>> mediaRangesParser = zeroOrMore(mediaRangeParser, regex("\\s*,\\s*"));


    /**
     * Attempts to parse a sorted list of MediaRage instances from the given input string.
     *
     * @param acceptHeaderString
     *     the string to parse one or more MediaRanges from.
     *
     * @return a List of successfully parsed MediaRanges, sorted by highest precedence first.
     */
    public static Optional<AcceptHeader> parse(final String acceptHeaderString) {
        return mediaRangesParser.tryParse(acceptHeaderString).map(AcceptHeader::new);
    }
}
