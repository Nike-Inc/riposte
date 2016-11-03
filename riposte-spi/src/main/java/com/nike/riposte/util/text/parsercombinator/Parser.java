package com.nike.riposte.util.text.parsercombinator;

import com.nike.internal.util.ImmutablePair;
import com.nike.internal.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A ultra-lightweight parser combinator library with one dependency ({@link Pair} from Nike's internal utils library).
 * parse sequencing is done by returning left nested pairs that are mapped & matched into complex types.
 *
 * Inspired by Haskell's Parsec library
 * This Java8 interface is influenced by Joakim Ahnfelt-Rønne's 'A Parser Combinator for Java 8'
 * Additional interface inspired from the the jparsec project.
 *
 * see: https://en.wikipedia.org/wiki/Parser_combinator
 * see: https://hackage.haskell.org/package/parsec
 * see: http://www.ahnfelt.net/a-parser-combinator-for-java-8-2/
 * see: https://github.com/jparsec/jparsec
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public interface Parser<T> {

    /**
     * The primary interface to parsing an input to a output of type T.
     *
     * @param parserInput
     *     The input for the parser to ingress.
     *
     * @return the output of this parser.
     *
     * @throws ParserFailure
     *     if this parser is unable to sucessfully parse the input into an output of type T.
     */
    T parse(ParserInput parserInput) throws ParserFailure;


    /**
     * Performs this parser's operation on a given sequence of text characters.
     *
     * @param text
     *     the text input to this parser
     *
     * @return the output of this parser.
     *
     * @throws ParserFailure
     *     if this parser is unable to sucessfully parse the input text into an output of type T.
     */
    default T parse(final CharSequence text) throws ParserFailure {
        return parse(new ParserInput(text, 0));
    }

    /**
     * Attempts to parse the given input into an output of type T. If the attempt fails, the input's offset is reset
     * back to it's starting offset value.
     *
     * @param parserInput
     *     the input for the parser to ingress.
     *
     * @return An Optional with an instance of T, if the parse operation was successful, otherwise Optional.empty().
     */
    default Optional<T> tryParse(final ParserInput parserInput) {
        final int offset = parserInput.getOffset();
        try {
            return Optional.of(parse(parserInput));
        }
        catch (ParserFailure failure) {
            parserInput.setOffset(offset);
            return Optional.empty();
        }
    }

    /**
     * Attempts to parse the given text into an output of type T. If the attempt fails, the input's offset is reset
     * back to it's starting offset value.
     *
     * @param text
     *     the text input to this parser
     *
     * @return An Optional with an instance of T, if the parse operation was successful, otherwise Optional.empty().
     */
    default Optional<T> tryParse(final CharSequence text) {
        return tryParse(new ParserInput(text, 0));
    }


    /**
     * Returns a parser that will attempt to first parse its own result,
     * subsequently will parse to the given parser's result, returning both as a
     * Pair, with this parsers result to the Pair's left, and the given parser's result
     * to the Pair's right.
     *
     * @param parser
     *     parser that will attempt to first parse its own result, subsequently will parse to the given parser's
     *     result.
     *
     * @return a parser that will attempt to first parse its own result, subsequently will parse to the given parser's
     * result.
     */
    default <R> Parser<Pair<T, R>> thenParse(final Parser<R> parser) {
        return parserInput -> ImmutablePair.of(parse(parserInput), parser.parse(parserInput));
    }

    /**
     * Returns a parser that will attempt to first parse its own result,
     * subsequently will parse to the given result of the parser produced from the given supplier,
     * returning both as a  Pair, with this parsers result to the Pair's left, and the given's result
     * to the Pair's right.
     *
     * @param supplier
     *     the supplier of a parser whos result is to be discarded.
     *
     * @return a parser that will attempt to first parse its own result, subsequently will parse to the given result of
     * the parser produced from the given supplier.
     */
    default <R> Parser<Pair<T, R>> thenParse(final Supplier<Parser<R>> supplier) {
        return parserInput -> thenParse(supplier.get()).parse(parserInput);
    }

    /**
     * Constructs a parser that will first parse this parser's result,
     * subsequently will parse and discard the given parser's result, returning only this
     * parser's result.
     *
     * @param parser
     *     the parser whos result is to be discarded.
     *
     * @return a parser that will first parse this parser's result, subsequently will parse and discard the given
     * parser's result.
     */
    default Parser<T> thenSkip(final Parser<?> parser) {
        return parserInput -> {
            final T result = parse(parserInput);
            parser.parse(parserInput);
            return result;
        };
    }

    /**
     * Constructs a parser that will first parse this parser's result,
     * subsequently will parse and discard the parser returned from the given supplier, returning only this
     * parser's result.
     *
     * @param supplier
     *     the supplier of a parser whos result is to be discarded.
     *
     * @return a parser that will first parse this parser's result, subsequently will parse and discard the given
     * parser's result.
     */
    default Parser<T> thenSkip(final Supplier<Parser<?>> supplier) {
        return parserInput -> thenSkip(supplier.get()).parse(parserInput);
    }

    /**
     * Constructs a parser that will first parse this parser's result, if unsuccessful the given parser will attempt to
     * parser its result.
     *
     * The first successfull result will be returned.
     *
     * @param parser
     *     the parser to try, if this parser fails.
     *
     * @return a parser that will first parse this parser's result, if unsuccessful the given parser will attempt to
     * parser its result.
     */
    default Parser<T> or(final Parser<T> parser) {
        return parserInput -> {
            final int offset = parserInput.getOffset();
            try {
                return Parser.this.parse(parserInput);
            }
            catch (ParserFailure failure) {
                parserInput.setOffset(offset);
                return parser.parse(parserInput);
            }
        };
    }

    /**
     * Constructs a parser that will first parse this parser's result, if unsuccessful the given supplier's parser will
     * attempt to parser its result.
     *
     * @param supplier
     *     provides a parser to try if this parser fails.
     *
     * @return a parser that will first parse this parser's result, if unsuccessful the given supplier's parser will
     * attempt to parser its result.
     */
    default Parser<T> or(final Supplier<Parser<T>> supplier) {
        return parserInput -> or(supplier.get()).parse(parserInput);
    }

    /**
     * Constructs a parser that will parse this parser's result, returning an Optiona with the result from the parse.
     *
     * @return Parser that will result in an optional. If this parser fails the returned parser will return
     * Optional.empty().
     */
    default Parser<Optional<T>> optional() {
        return parserInput -> {
            try {
                return Optional.of(parse(parserInput));
            }
            catch (ParserFailure failure) {
                return Optional.empty();
            }
        };
    }

    /**
     * Constructs a parser that will repeatedly parse this parsers result in until it fails.
     *
     * @return A list of this parser's results. If no matches were found initially, an empty list will be returned.
     */
    default Parser<List<T>> zeroOrMore() {
        return parserInput -> Parsers.zeroOrMore(Parser.this).parse(parserInput);
    }

    /**
     * Constructs a parser that will repeatedly parse this parsers result in until it or the given seperater-parser
     * fails. After each successful parse of this parser's result, an attempt is made to parse the given
     * seperater-parser's result.
     *
     * @param separator
     *     the parser that seperates this parser's results. the seperater-parser's result is discarded.
     *
     * @return A list of this parser's results. If no matches were found initially, an empty list will be returned.
     */
    default Parser<List<T>> zeroOrMore(final Parser<?> separator) {
        return parserInput -> Parsers.zeroOrMore(Parser.this, separator).parse(parserInput);
    }

    /**
     * Constructs a parser that will repeatedly parse this parsers result in until it fails.
     * This parser fails if it could not parse at least once successfully.
     *
     * @return A list of this parser's results.
     */
    default Parser<List<T>> oneOrMore() {
        return parserInput -> Parsers.oneOrMore(Parser.this).parse(parserInput);
    }

    /**
     * Constructs a parser that will repeatedly parse this parsers result in until it or the given seperater-parser
     * fails. After each successful parse of this parser's result, an attempt is made to parse the given
     * seperater-parser's result. This parser fails if it could not parse at least once successfully.
     *
     * @param separator
     *     the parser that seperates this parser's results. the seperater-parser's result is discarded.
     *
     * @return A list of this parser's results.
     */
    default Parser<List<T>> oneOrMore(final Parser<?> separator) {
        return parserInput -> Parsers.oneOrMore(Parser.this, separator).parse(parserInput);
    }

    /**
     * Constructs a parser that will parse this parser's result then test the result with the given predicate.
     *
     * @param predicate
     *     the funciton to test the success or failure of this parser's result with.
     *
     * @return a parser that will parse this parser's result then test the result with the given predicate, returning
     * the result if the test is a success, failure if the test fails.
     */
    default Parser<T> filter(final Predicate<T> predicate) {
        return parserInput -> Parsers.filter(Parser.this, predicate).parse(parserInput);
    }

    /**
     * Constructs a parser that converts the this parser's result type to another type, using the given function.
     *
     * @param function
     *     the function to convert this parser's result to
     *
     * @return a parser that converts the this parser's result type to another type, using the given function.
     */
    default <R> Parser<R> map(final Function<T, R> function) {
        return parserInput -> function.apply(parse(parserInput));
    }

    /**
     * Constructs a parser that converts the this parser's result type to another parser, using the given function.
     *
     * @param function
     *     the function to convert this parser's result to
     *
     * @return a parser that converts the this parser's result type to another parser, using the given function.
     */
    default <R> Parser<R> flatMap(final Function<T, Parser<R>> function) {
        return parserInput -> function.apply(parse(parserInput)).parse(parserInput);
    }

    /* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

    Object UNIT = new Object();

    /**
     * A collection of parsers that implement common tasks.
     * Most are useful for creating an initial parser intances.
     */
    final class Parsers {

        public static final Parser<?> EMPTY_PARSER = success(UNIT);
        public static final Parser<?> BEGIN_PARSER = regex("^").map(m -> UNIT);
        public static final Parser<?> END_PARSER = regex("$").map(m -> UNIT);

        /**
         * Lifts a value into a parser of the same type.
         *
         * @param value
         *     the item to return
         *
         * @return The item given.
         */
        public static <R> Parser<R> success(final R value) {
            return parserInput -> value;
        }

        /**
         * Constructs a parser that will always fail.
         *
         * @param cause
         *     The message to report to the user regarding the failure.
         *
         * @return a parser that will always fail, reporting the given message.
         */
        public static <R> Parser<R> failure(final String cause) {
            return parserInput -> {
                throw new ParserFailure(cause, parserInput);
            };
        }

        /**
         * Constructs a parser that parsers to UNIT result.
         */
        public static Parser<?> empty() {
            return EMPTY_PARSER;
        }


        public static Parser<?> begin() {
            return BEGIN_PARSER;
        }

        public static Parser<?> end() {
            return END_PARSER;
        }


        /**
         * Constructs a parser that will return the first successful parser result, from the given stream of parsers.
         *
         * @param parsers
         *     the stream of parsers to attempt parsing
         *
         * @return the first successful parser result.
         */
        public static <R> Parser<R> oneOf(final Stream<Parser<R>> parsers) {
            return parsers.reduce(Parser::or).orElse(failure("No parsers given to choice parser."));
        }

        /**
         * Constructs a parser that will return the first successful parser result, from the given list of parsers.
         *
         * @param parsers
         *     the list of parsers to attempt parsing
         *
         * @return the first successful parser result.
         */
        public static <R> Parser<R> oneOf(final List<Parser<R>> parsers) {
            return oneOf(parsers.stream());
        }

        /**
         * Constructs a parser that will return the first successful parser result, from the given array of parsers.
         *
         * @param parsers
         *     the array of parsers to attempt parsing
         *
         * @return the first successful parser result.
         */
        @SafeVarargs
        public static <R> Parser<R> oneOf(final Parser<R>... parsers) {
            return oneOf(Arrays.stream(parsers));
        }

        /**
         * Constructs a Parser that will repeatedly attempt to parse the given parser sperated by the given separator
         * parser, until failure, returning a list of all successful parse results that occurred before the failure.
         *
         * @param parser
         *     the parser to attempt repeatedly parsing an input string with.
         * @param separator
         *     the parser that seperates the given parser's results. the seperater-parser's result is discarded.
         * @param <R>
         *     The type of the returned list.
         *
         * @return A list of the given parser's results. If no matches were found, an empty list will be returned.
         */
        public static <R> Parser<List<R>> zeroOrMore(final Parser<R> parser, final Parser<?> separator) {
            return parserInput -> {
                final List<R> result = new ArrayList<>();
                Optional<R> element;
                do {
                    element = peek(parser).tryParse(parserInput);
                    if (element.isPresent()) {
                        result.add(element.get());
                    }
                } while (peek(separator).tryParse(parserInput).isPresent());
                return result;
            };
        }

        /**
         * Constructs a Parser that will repeatedly attempt to parse the given parser until failure,
         * returning a list of all successful parse results that occurred before the failure.
         *
         * @param parser
         *     the parser to attempt repeatedly parsing an input string with.
         * @param <R>
         *     The type of the returned list.
         *
         * @return A list of the given parser's results. If no matches were found, an empty list will be returned.
         */
        public static <R> Parser<List<R>> zeroOrMore(final Parser<R> parser) {
            return parserInput -> {
                final List<R> result = new ArrayList<R>();
                Optional<R> element;
                while ((element = peek(parser).tryParse(parserInput)).isPresent()) {
                    result.add(element.get());
                }
                return result;
            };
        }

        /**
         * Constructs a parser that will repeatedly parse the given parsers result in until it fails.
         * This parser fails if it could not parse at least once successfully from the given parser.
         *
         * @param parser
         *     the parser to attempt repeatedly parsing an input string with.
         * @param <R>
         *     The type of the returned list.
         *
         * @return A list of the given parser's results.
         */
        public static <R> Parser<List<R>> oneOrMore(final Parser<R> parser) {
            return parserInput -> {
                final List<R> result = new ArrayList<>();
                result.add(peek(parser).parse(parserInput));
                Optional<R> element;
                while ((element = peek(parser).tryParse(parserInput)).isPresent()) {
                    result.add(element.get());
                }
                return result;
            };
        }

        /**
         * Constructs a parser that will repeatedly parse the given parsers result in until it or the given
         * seperater-parser fails. After each successful parse of the given parser's result, an attempt is made to parse
         * the given seperater-parser's result. This parser fails if it could not parse at least once successfully from
         * the given parser.
         *
         * @param parser
         *     the parser to attempt repeatedly parsing an input string with.
         * @param separator
         *     the parser that seperates this parser's results. the seperater-parser's result is discarded.
         * @param <R>
         *     The type of the returned list.
         *
         * @return A list of the given parser's results.
         */
        public static <R> Parser<List<R>> oneOrMore(final Parser<R> parser, final Parser<?> separator) {
            return parserInput -> {
                final List<R> result = new ArrayList<>();
                result.add(peek(parser).parse(parserInput));
                try {
                    while (peek(separator).tryParse(parserInput).isPresent()) {
                        result.add(peek(parser).parse(parserInput));
                    }
                }
                finally {
                    return result;
                }
            };
        }

        /**
         * Constructs a parser that will discard the result of the given parser.
         *
         * @param parser
         *     the parser to consume.
         *
         * @return a parser that will discard the result of the given parser.
         */
        public static SkipParser skip(final Parser<?> parser) {
            return new SkipParser(parser);
        }

        /**
         * Convenience wrapper for constructing a new instance of a FloarParser.
         */
        public static Parser<Float> floatNumber() {
            return FloatParser.singleton;
        }


        /**
         * Attempts to parse the given Parser, resetting the parser input state if the parse fails.
         * to the text and offset settings that were set prior to parsing.
         *
         * @param parser
         *     the parser to attempt parsing from.
         * @param <R>
         *     The return type of a successful parse
         *
         * @return the result of the given parser.
         */
        public static <R> Parser<R> peek(final Parser<R> parser) {
            return parserInput -> {
                final CharSequence text = parserInput.getText();
                final int offset = parserInput.getOffset();

                try {
                    return parser.parse(parserInput);
                }
                catch (ParserFailure e) {
                    parserInput.setText(text);
                    parserInput.setOffset(offset);
                    throw e;
                }
            };
        }

        /**
         * Constructsa a Parser that will parse the given parser and feed it's result to the given predicate.
         * If the predicate returns true, then the result is returned.
         * If parsing with the given parser fails, or the predicate returns false, then this parser will fail.
         */
        public static <R> Parser<R> filter(final Parser<R> parser, Predicate<R> predicate) {
            return parserInput -> {
                final CharSequence text = parserInput.getText();
                final int offset = parserInput.getOffset();

                final R result = peek(parser).parse(parserInput);

                if (predicate.test(result)) {
                    return result;
                }

                parserInput.setText(text);
                parserInput.setOffset(offset);
                throw new ParserFailure(parserInput);
            };
        }

        /**
         * Lifts a supplier into a parser that will and forward any input to the supplier's parser.
         *
         * @param parser
         *     the parser to receive
         */
        public static <R> Parser<R> then(final Supplier<Parser<R>> parser) {
            return parserInput -> parser.get().parse(parserInput);
        }

        /**
         * Constructs a parser that will parse the given string from its input.
         *
         * @return the string parsed from an input, that exactly matches the given string.
         */
        public static Parser<String> string(final String string) {
            return regex(Pattern.quote(string)).map(MatchResult::group);
        }

        /**
         * Constructs a parser that will compile the given string into a java regular expression pattern,
         * and match its input to it.
         *
         * @param regex
         *     the java regex string to compile into a pattern and match input against.
         *
         * @return the MatchResult of a successful Matcher.lookingAt() test.
         */
        public static Parser<MatchResult> regex(final String regex) {
            return regex(Pattern.compile(regex));
        }

        /**
         * Constructs a parser that matches its input to the given regular expression pattern.
         *
         * @param pattern
         *     the java regex pattern to match input with.
         *
         * @return a parser that matches its input to the given regular expression pattern.
         */
        public static Parser<MatchResult> regex(final Pattern pattern) {
            return new RegexParser(pattern);
        }

    }

    /**
     * A set of functions for simplifying the evaluation of anonmyous closures.
     * Useful when combined with .map() to reduse the result of several successful parses into a single result.
     */
    final class Apply {

        /**
         * Returns the given funciton.
         * e.g. string("A").map(match( (a) -> ...))
         */
        public static <A, R> Function<A, R> match(final Function<A, R> function) {
            return function;
        }

        /**
         * Constructs a function that takes a nested-left pair, two deep, and applies it to the given two-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").map(match( (a,b) -> ...))
         */
        public static <A, B, R> Function<Pair<A, B>, R> match(final BiFunction<A, B, R> function) {
            return pair -> function.apply(
                pair.getLeft(),
                pair.getRight()
            );
        }

        /**
         * Constructs a function that takes a nested-left pair, three deep, and applies it to the given three-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").map(match( (a,b,c) -> ...))
         */
        public static <A, B, C, R> Function<Pair<Pair<A, B>, C>, R> match(final Function3<A, B, C, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft(),
                pair.getLeft().getRight(),
                pair.getRight()
            );

        }

        /**
         * Constructs a function that takes a nested-left pair, four deep, and applies it to the given four-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").string("D").map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D, R> Function<Pair<Pair<Pair<A, B>, C>, D>, R> match(
            final Function4<A, B, C, D, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );

        }

        /**
         * Constructs a function that takes a nested-left pair, five deep, and applies it to the given five-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").string("D").string("E").map(match( (a,b,c,d,e) -> ...))
         */
        public static <A, B, C, D, E, R> Function<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, R> match(
            final Function5<A, B, C, D, E, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a function that takes a nested-left pair, six deep, and applies it to the given six-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").string("D").string("E").string("F").map(match( (a,b,c,d,e,f) -> ...))
         */
        public static <A, B, C, D, E, F, R> Function<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, R> match(
            final Function6<A, B, C, D, E, F, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a function that takes a nested-left pair, seven deep, and applies it to the given seven-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").string("D").string("E").string("F").string("G").map(match(
         * (a,b,c,d,e,f,g) -> ...))
         */
        public static <A, B, C, D, E, F, G, R> Function<Pair<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, G>, R> match(
            final Function7<A, B, C, D, E, F, G, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a function that takes a nested-left pair, eight deep, and applies it to the given eight-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").string("D").string("E").string("F").string("G").string("H").map(match(
         * (a,b,c,d,e,f,g,h) -> ...))
         */
        public static <A, B, C, D, E, F, G, H, R> Function<Pair<Pair<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, G>, H>, R> match(
            final Function8<A, B, C, D, E, F, G, H, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );

        }

        /**
         * Constructs a function that takes a nested-left pair, nine deep, and applies it to the given nine-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use match() in conjunction with
         * parser.map() to construct useful structures while maintaining type safety. e.g.
         * string("A").string("B").string("C").string("D").string("E").string("F").string("G").string("H").string("I").map(match(
         * (a,b,c,d,e,f,g,h,i) -> ...))
         */
        public static <A, B, C, D, E, F, G, H, I, R> Function<Pair<Pair<Pair<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, G>, H>, I>, R> match(
            final Function9<A, B, C, D, E, F, G, H, I, R> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }


        public static <A> Predicate<A> test(final Function<A, Boolean> function) {
            return function::apply;
        }


        /**
         * Constructs a predicate that takes a nested-left pair, two deep, and applies it to the given two-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B> Predicate<Pair<A, B>> test(final BiFunction<A, B, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft(),
                pair.getRight()
            );
        }

        /**
         * Constructs a predicate that takes a nested-left pair, three deep, and applies it to the given three-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C> Predicate<Pair<Pair<A, B>, C>> test(final Function3<A, B, C, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a predicate that takes a nested-left pair, four deep, and applies it to the given four-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D> Predicate<Pair<Pair<Pair<A, B>, C>, D>> test(
            final Function4<A, B, C, D, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a predicate that takes a nested-left pair, five deep, and applies it to the given five-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D, E> Predicate<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>> test(
            final Function5<A, B, C, D, E, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a predicate that takes a nested-left pair, six deep, and applies it to the given six-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D, E, F> Predicate<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>> test(
            final Function6<A, B, C, D, E, F, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a predicate that takes a nested-left pair, seven deep, and applies it to the given seven-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D, E, F, G> Predicate<Pair<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, G>> test(
            final Function7<A, B, C, D, E, F, G, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Constructs a predicate that takes a nested-left pair, eight deep, and applies it to the given eight-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to pass/fail a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D, E, F, G, H> Predicate<Pair<Pair<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, G>, H>> test(
            final Function8<A, B, C, D, E, F, G, H, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );

        }

        /**
         * Constructs a predicate that takes a nested-left pair, nine deep, and applies it to the given nine-arity
         * function. Chains of thenParse() calls will produce nested-left pairs, use test() in conjunction with
         * parser.filter() to test a parser's result. e.g. string("A").string("B").string("C").string("D").filter(test(
         * (a,b,c,d) -> a+b != c+d)).map(match( (a,b,c,d) -> ...))
         */
        public static <A, B, C, D, E, F, G, H, I> Predicate<Pair<Pair<Pair<Pair<Pair<Pair<Pair<Pair<A, B>, C>, D>, E>, F>, G>, H>, I>> test(
            final Function9<A, B, C, D, E, F, G, H, I, Boolean> function) {
            return pair -> function.apply(
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getLeft().getRight(),
                pair.getLeft().getLeft().getRight(),
                pair.getLeft().getRight(),
                pair.getRight()
            );
        }

        /**
         * Represents a function that accepts three arguments and produces a result.
         * This is the three-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function3<A, B, C, R> {

            R apply(A a, B b, C c);
        }

        /**
         * Represents a function that accepts four arguments and produces a result.
         * This is the four-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <D>
         *     the type of the fourth argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function4<A, B, C, D, R> {

            R apply(A a, B b, C c, D d);
        }

        /**
         * Represents a function that accepts five arguments and produces a result.
         * This is the five-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <D>
         *     the type of the fourth argument to the function
         * @param <E>
         *     the type of the fith argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function5<A, B, C, D, E, R> {

            R apply(A a, B b, C c, D d, E e);
        }

        /**
         * Represents a function that accepts six arguments and produces a result.
         * This is the six-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <D>
         *     the type of the fourth argument to the function
         * @param <E>
         *     the type of the fith argument to the function
         * @param <F>
         *     the type of the sixth argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function6<A, B, C, D, E, F, R> {

            R apply(A a, B b, C c, D d, E e, F f);
        }

        /**
         * Represents a function that accepts seven arguments and produces a result.
         * This is the seven-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <D>
         *     the type of the fourth argument to the function
         * @param <E>
         *     the type of the fith argument to the function
         * @param <F>
         *     the type of the sixth argument to the function
         * @param <G>
         *     the type of the seventh argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function7<A, B, C, D, E, F, G, R> {

            R apply(A a, B b, C c, D d, E e, F f, G g);
        }

        /**
         * Represents a function that accepts eight arguments and produces a result.
         * This is the eight-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <D>
         *     the type of the fourth argument to the function
         * @param <E>
         *     the type of the fith argument to the function
         * @param <F>
         *     the type of the sixth argument to the function
         * @param <G>
         *     the type of the seventh argument to the function
         * @param <H>
         *     the type of the eighth argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function8<A, B, C, D, E, F, G, H, R> {

            R apply(A a, B b, C c, D d, E e, F f, G g, H h);
        }

        /**
         * Represents a function that accepts nine arguments and produces a result.
         * This is the nine-arity specialization of {@link Function}.
         *
         * @param <A>
         *     the type of the first argument to the function
         * @param <B>
         *     the type of the second argument to the function
         * @param <C>
         *     the type of the third argument to the function
         * @param <D>
         *     the type of the fourth argument to the function
         * @param <E>
         *     the type of the fith argument to the function
         * @param <F>
         *     the type of the sixth argument to the function
         * @param <G>
         *     the type of the seventh argument to the function
         * @param <H>
         *     the type of the eighth argument to the function
         * @param <I>
         *     the type of the nineth argument to the function
         * @param <R>
         *     the type of the result of the function
         */
        @FunctionalInterface
        public interface Function9<A, B, C, D, E, F, G, H, I, R> {

            R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i);
        }
    }

    /* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

    /**
     * A parser that will consume from the input, but omit returning the result of the parse.
     * Useful when structure-defining tokens exist within the input, but are not needed as data in the parser output.
     */
    class SkipParser {

        private final Parser<?> parser;

        public SkipParser(Parser<?> parser) {
            this.parser = parser;
        }

        public SkipParser skip(final Parser<?> that) {
            return new SkipParser(parserInput -> {
                parser.parse(parserInput);
                that.parse(parserInput);
                return UNIT;
            });
        }

        public <U> Parser<U> thenParse(final Parser<U> that) {
            return parserInput -> {
                parser.parse(parserInput);
                return that.parse(parserInput);
            };
        }
    }

    /**
     * A parser that attempts to match the input against a Java regular expression. Useful for building simple text
     * matching primatives, when the Parser Combinater interface can then compose into more complex matchers.
     */
    class RegexParser implements Parser<MatchResult> {

        private final Pattern pattern;

        public RegexParser(final Pattern pattern) {
            this.pattern = pattern;
        }

        public MatchResult parse(final ParserInput parserInput) throws ParserFailure {
            final Matcher matcher = pattern.matcher(
                parserInput.getText().subSequence(parserInput.getOffset(),
                                                  parserInput.getText().length())
            );

            if (matcher.lookingAt()) {
                parserInput.setOffset(parserInput.getOffset() + matcher.end());
                return matcher.toMatchResult();
            }
            else {
                throw new ParserFailure("Regular Expression parser with pattern '" + pattern.pattern()
                                        + "' did not match the input text ( using matcher.lookingAt() )", parserInput);
            }
        }
    }

    /**
     * A Parser that attempts to parse a Float value.
     */
    class FloatParser implements Parser<Float> {

        private static final Pattern pattern = Pattern.compile("([-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?)");

        public static final Parser<Float> singleton = new FloatParser();

        /**
         * Marked private to encurage the use of singleton.
         */
        private FloatParser() {
            super();
        }

        public Float parse(final ParserInput parserInput) throws ParserFailure {
            final Matcher matcher = pattern.matcher(
                parserInput.getText().subSequence(parserInput.getOffset(),
                                                  parserInput.getText().length())
            );

            if (matcher.lookingAt()) {
                parserInput.setOffset(parserInput.getOffset() + matcher.end());
                final String regexResult = matcher.toMatchResult().group(1);
                try {
                    return Float.parseFloat(regexResult);
                }
                catch (NullPointerException e) {
                    throw new ParserFailure("Float parser parsed null float value from input.");
                }
                catch (NumberFormatException e) {
                    throw new ParserFailure(
                        "Float parser parsed invalid float value from input text of '" + regexResult + "'.");
                }
            }
            else {
                throw new ParserFailure("Float parser using regex pattern '" + pattern.pattern()
                                        + "' did not match the input text ( using matcher.lookingAt() )", parserInput);
            }
        }
    }

    /* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

    /**
     * A simple marker class for representing parse failures.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    class ParserFailure extends Exception {

        public ParserFailure() {
            super();
        }

        public ParserFailure(final String message) {
            super(message);
        }

        public ParserFailure(final ParserInput parserInput) {
            super(formatParserInput(parserInput, Optional.empty()));
        }

        public ParserFailure(final String cause, final ParserInput parserInput) {
            super(formatParserInput(parserInput, Optional.of(cause)));
        }

        private static String formatParserInput(final ParserInput parserInput, Optional<String> cause) {
            final CharSequence text = parserInput.getText();
            final int offset = parserInput.getOffset();
            final int length = (text.length() > (offset + 20)) ? offset + 20 : text.length();
            final StringBuilder s = new StringBuilder("Failed on input at '");
            if (text.length() > (offset + 20)) {
                s.append(text.subSequence(offset, offset + 20));
                s.append("...'");
            }
            else {
                s.append(text.subSequence(offset, text.length()));
                s.append("'");
            }

            if (cause.isPresent()) {
                s.append(" cause: ");
                s.append(cause.get());
            }

            return s.toString();
        }
    }

    /**
     * A simple state for holding a text source and an offset into the given text source.
     */
    class ParserInput {

        protected CharSequence text;
        protected int offset;

        public ParserInput(final CharSequence text, final int offset) {
            this.text = text;
            this.offset = offset;
        }

        public CharSequence getText() {
            return text;
        }

        public void setText(final CharSequence text) {
            this.text = text;
        }

        public int getOffset() {
            return offset;
        }

        public void setOffset(final int offset) {
            this.offset = offset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ParserInput that = (ParserInput) o;

            return Objects.equals(offset, that.offset) &&
                   Objects.equals(text, that.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(offset, text);
        }
    }


}
