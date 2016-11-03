package com.nike.riposte.server.http.mimetype;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.mimetype.MimeType.Facet;
import com.nike.riposte.server.http.mimetype.MimeType.SubType;
import com.nike.riposte.server.http.mimetype.MimeType.Type;
import com.nike.riposte.util.text.parsercombinator.Parser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.nike.riposte.util.text.parsercombinator.Parser.Apply.match;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.failure;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.oneOf;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.regex;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.skip;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.string;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.success;

/**
 * A class that implements a Parser for RFC-6838 MimeTypes.
 *
 * <p>parse() is a convenience method around the underlyining parser-combinator parsers that this parser in implemented
 * on, providing an easy text in, optional mime out interface, where a secuess results in a MimeType instance, and a
 * failure results in Optional.empty().
 *
 * <p>example uses are:
 * <ul>
 *      <li>MimeTypeParser.parse("application/json")</li>
 *      <li>MimeTypeParser.parse("application/vnd.nike.runningcoach-v3.1+json; charset=UTF-8")</li>
 * </ul>
 *
 * <p>Because additional detail in how the mimetype was parsed may be desired, all of the constituent parsers are also
 * made public, allowing a developer compose or/or alter mimetype parsing, or compose exiting mimetype parsing into even
 * more sophistcated parsers, for example,  RFC-2616 14.1 Accept headers, where mimetypes are combined with optional
 * quality rates.
 *
 * <p>e.g. Accept: text/*;q=0.3, text/html;q=0.7, text/html;level=1, text/html;level=2;q=0.4
 *
 * <p>This parser is implemented using the following from  RFC-6838:
 * <blockquote>
 * <pre>
 * 4.2.  Naming Requirements
 *
 * All registered media types MUST be assigned top-level type and
 * subtype names.  The combination of these names serves to uniquely
 * identify the media type, and the subtype name facet (or the absence
 * of one) identifies the registration tree.  Both top-level type and
 * subtype names are case-insensitive.
 *
 * Type and subtype names MUST conform to the following ABNF:
 *
 * type-name = restricted-name
 * subtype-name = restricted-name
 *
 * restricted-name = restricted-name-first *126restricted-name-chars
 * restricted-name-first  = ALPHA / DIGIT
 * restricted-name-chars  = ALPHA / DIGIT / "!" / "#" /  "$" / "&" / "-" / "^" / "_"
 * restricted-name-chars =/ "." ; Characters before first dot always specify a facet name
 * restricted-name-chars =/ "+" ; Characters after last plus always specify a structured syntax suffix
 * </pre>
 * </blockquote>
 *
 * Provides a set of Parser instances for parsing a complete MimeType instance.
 * <pre>
 * example use:
 *   MimeParser.parse("application/vnd.nike.runningcoach-v3.1+json; charset=UTF-8")
 *
 *   results in an Optional<MimeType>, where in:
 *    - the Type is Type. APPLICATION
 *    - the SubType's Facet is Facet.VENDOR
 *    - the SubType's Suffix is json
 *    - the SubType's Name is nike.runningcoach-v3.1
 *    - the Parameter Map contains one parameter if 'charset' with a value of 'UTF-8'
 * </pre>
 */
@SuppressWarnings("WeakerAccess")
public class MimeTypeParser {

    public static final Pattern tokenPattern = Pattern.compile("([a-zA-Z0-9][a-zA-Z0-9\\!\\#\\$\\&\\-\\^_\\.]*)");

    /**
     * A Parser that will match the names defined in RFC-6838 4.2
     */
    public static final Parser<String> token = regex(tokenPattern).map(matchResult -> matchResult.group(1));

    /**
     * A Parser of a key value pair, seperated by an equals character.
     */
    public static final Parser<Pair<String, String>> parameterParser =
        token.thenSkip(string("=")).thenParse(token);

    /**
     * A parser for a semicolon, with optional leading or trailing whitespace.
     */
    public static final Parser<MatchResult> semicolon = regex("\\s*;\\s*");

    /**
     * A parser of key value pairs. Order is not preserved in the returned map.
     */
    public static final Parser<Map<String, String>> parametersParser =
        skip(semicolon)
            .thenParse(parameterParser.oneOrMore(semicolon))
            .map(list -> {
                Map<String, String> parameters = new HashMap<>();
                list.forEach(pair -> parameters.put(pair.getLeft(), pair.getRight()));
                return parameters;
            });

    /**
     * A parser for the top-level types of a mimetype.
     */
    public static final Parser<Type> typeParser =
        oneOf(Type.values.stream().map(type -> string(type.getName()))).or(token).map(Type::of);

    /**
     * A parser for the facet of a subtype.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public static final Parser<Facet> facetParser =
        oneOf(
            Stream.of(Facet.values())
                  .filter(facet -> facet.getRegistrationTreeName().isPresent())
                  .map(facet -> string(facet.getRegistrationTreeName().get()))
        )
            .map(Facet::forRegistrationTreeName)
            .flatMap(oFacet -> oFacet.isPresent() ? success(oFacet.get()) : failure("unrecognized facet"));

    /**
     * A parser for the sub-type of a mimetype.
     */
    public static final Parser<SubType> subTypeParser =
        oneOf(
            // Match facet.name+suffix
            facetParser.thenSkip(string(".")).thenParse(token).thenSkip(string("+")).thenParse(token)
                       .map(match((facet, name, suffix) -> SubType.of(facet, name, Optional.of(suffix)))),

            // Match facet.name
            facetParser.thenSkip(string(".")).thenParse(token)
                       .map(match((facet, name) -> SubType.of(facet, name, Optional.empty()))),

            // Match name+suffix
            token.thenSkip(string("+")).thenParse(token)
                 .map(match((name, suffix) -> SubType.of(Facet.STANDARD, name, Optional.of(suffix)))),

            // Match name
            token.map(match(name -> SubType.of(Facet.STANDARD, name, Optional.empty())))
        );

    /**
     * A parser for a mimetype.
     */
    @SuppressWarnings("Convert2MethodRef")
    public static final Parser<MimeType> mimeTypeParser =
        typeParser
            .thenSkip(string("/"))
            .thenParse(subTypeParser)
            .thenParse(
                parametersParser.optional().map(oParams -> oParams.orElse(Collections.<String, String>emptyMap())))
            .map(match((type, subType, parameters) -> MimeType.of(type, subType, parameters)));

    /**
     * Attempts to parse a mimetype from the given text.
     *
     * @param text
     *     the text to parse into a MimeType instace.
     *
     * @return An Optional instace containing the MimeType represented from the given text, or empty, if the given text
     * failed to parse into a valid MimeType.
     */
    public static Optional<MimeType> parse(CharSequence text) {
        return mimeTypeParser.tryParse(text);
    }
}
