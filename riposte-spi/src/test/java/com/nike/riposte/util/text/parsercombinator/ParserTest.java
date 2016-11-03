package com.nike.riposte.util.text.parsercombinator;

import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.Optional;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import com.nike.riposte.util.text.parsercombinator.Parser.ParserFailure;

import static com.nike.riposte.util.text.parsercombinator.Parser.ParserInput;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.regex;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.begin;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.skip;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.then;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.string;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.oneOf;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.zeroOrMore;
import static com.nike.riposte.util.text.parsercombinator.Parser.Parsers.oneOrMore;
import static com.nike.riposte.util.text.parsercombinator.Parser.Apply.match;
import static com.nike.riposte.util.text.parsercombinator.Parser.Apply.test;


import com.nike.internal.util.Pair;


public class ParserTest {

    @Test
    public void test_parser_input_compares_two_indentical_instances_correctly () {
        ParserInput inputOne = new ParserInput("test", 0);
        ParserInput inputTwo = new ParserInput("test", 0);
        assertThat(inputOne).isEqualTo(inputTwo);
        assertThat(inputOne.hashCode()).isEqualTo(inputTwo.hashCode());
    }

    @Test
    public void test_parser_input_compares_two_different_instances_correctly () {
        ParserInput inputOne = new ParserInput("one", 0);
        ParserInput inputTwo = new ParserInput("two", 0);
        assertThat(inputOne).isNotEqualTo(inputTwo);
        assertThat(inputOne.hashCode()).isNotEqualTo(inputTwo.hashCode());
    }

    @Test
    public void test_regex_parser_parse_works () throws ParserFailure {
        Parser<MatchResult> parser = regex("^.*(test).*$");
        MatchResult successResult = parser.parse("this test should work");
        assertThat(successResult.groupCount()).isEqualTo(1);
        assertThat(successResult.group(1)).isEqualTo("test");
    }

    @Test
    public void test_regex_parser_parse_fails () throws ParserFailure {
        Parser<MatchResult> parser = regex("^.*(test).*$");

        Throwable ex = catchThrowable(() -> parser.parse("this should not work"));
        assertThat(ex).isInstanceOf(ParserFailure.class);
    }

    @Test
    public void test_regex_parser_try_parse_works () throws ParserFailure {
        Parser<MatchResult> parser = regex("^.*(test).*$");
        Optional<MatchResult> successResult = parser.tryParse("this test should work");
        assertThat(successResult.isPresent()).isTrue();
        assertThat(successResult.get()).isNotNull();
        assertThat(successResult.get().groupCount()).isEqualTo(1);
        assertThat(successResult.get().group(1)).isEqualTo("test");
    }

    @Test
    public void test_regex_parser_try_parse_fails () throws ParserFailure {
        Parser<MatchResult> parser = regex("^.*(test).*$");

        parser.tryParse("this should not work");
        Optional<MatchResult> failureResult = parser.tryParse("this should not work");
        assertThat(failureResult.isPresent()).isFalse();

    }

    @Test
    public void test_skip_parser_parse_works () throws ParserFailure {
        Parser<String> token =  regex("([a-z][a-z0-9]*)(\\s+|$)").map( matchResult -> matchResult.group(1));
        Parser<String> over = token.filter( t -> "over".equals(t) );

        Parser<Pair<String, String>> parser = token.thenSkip(over).thenParse(token);

        Pair<String, String> result = parser.parse("pass over this");
        assertThat(result.getLeft()).isEqualTo("pass");
        assertThat(result.getRight()).isEqualTo("this");
    }

    @Test
    public void test_default_then_parser_invokes_parse_method () throws ParserFailure {
        String testText = "this should work";

        Parser<String> testParser = new Parser<String> () {
            @Override
            public String parse(ParserInput parserInput) throws ParserFailure {
                return "win";
            }
        };

        Optional<String> oResult = skip(begin()).thenParse(testParser).tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("win");
    }

    @Test
    public void test_default_then_supplier_invokes_parse_method () throws ParserFailure {
        String testText = "this should work";

        Supplier<Parser<String>> testParser = new Supplier<Parser<String>>() {
            @Override
            public Parser<String> get() {
                return new Parser<String> () {
                    @Override
                    public String parse(ParserInput parserInput) throws ParserFailure {
                        return "win";
                    }
                };
            }
        };

        Optional<String> oResult = then(testParser).tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("win");
    }

    @Test
    public void test_default_skip_parser_works () throws ParserFailure {
        String testText = "should skip this";

        Parser<String> parser =
            string("should")
                .thenSkip(string(" skip "))
                .thenParse(string("this"))
                .map(match((one,two) -> one + " " + two));


        Optional<String> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("should this");
    }

    @Test
    public void test_default_or_parser_works () throws ParserFailure {
        String testText = "should find this";

        Parser<String> parser =
            string("could")
                .or(string("would"))
                .or(string("should"));

        Optional<String> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("should");
    }

    @Test
    public void test_default_0orMore_parser_works () throws ParserFailure {
        String testText = "0123456789";

        Parser<List<String>> parser =
                regex("([0-9])").map(m -> m.group(1)).zeroOrMore();

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).contains("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    public void test_default_0orMore_parser_returns_empty_list () throws ParserFailure {
        String testText = "";

        Parser<List<String>> parser =
                regex("([0-9])").map(m -> m.group(1)).zeroOrMore();

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEmpty();
    }

    @Test
    public void test_default_1orMore_parser_works () throws ParserFailure {
        String testText = "0123456789";

        Parser<List<String>> parser =
                regex("([0-9])").map(m -> m.group(1)).oneOrMore();

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).contains("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    public void test_default_1orMore_parser_fails_empty_match () throws ParserFailure {
        String testText = "";

        Parser<List<String>> parser =
                regex("([0-9])").map(m -> m.group(1)).oneOrMore();

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_default_filter_works () throws ParserFailure {
        String testText = "123";

        Parser<List<String>> parser =
            regex("([0-9])")
                .map(m -> m.group(1))
                .filter(s -> "1".equals(s))
                .oneOrMore();

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get().size()).isEqualTo(1);
        assertThat(oResult.get().get(0)).isEqualTo("1");
    }

    @Test
    public void test_match2_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));

        Parser<String> parser =
                number
                    .thenParse(string("A"))
                    .map(match( (first, second) -> first.toString() + "+" + second ));


        Optional<String> oResult = parser.tryParse("1A");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A");
    }

    @Test
    public void test_match3_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Parser<Boolean> bool   = regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .map(match((first, second, third) -> first.toString() + "+" + second + "+" + third.toString() ));


        Optional<String> oResult = parser.tryParse("1Atrue");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true");
    }

    @Test
    public void test_match4_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Supplier<Parser<Integer>> number = () -> regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Supplier<Parser<Boolean>> bool   = () -> regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number.get()
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .thenParse(number)
                    .map(match( (first, second, third, fourth) -> {
                        return first.toString() + "+"
                                + second.toString() + "+"
                                + third.toString() + "+"
                                + fourth.toString();
                    }));


        Optional<String> oResult = parser.tryParse("1Atrue2");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true+2");
    }

    @Test
    public void test_match5_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Supplier<Parser<Integer>> number = () -> regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Supplier<Parser<Boolean>> bool   = () -> regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number.get()
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("B"))
                    .map(match( (first, second, third, fourth, fifth ) -> {
                        return first.toString() + "+"
                                + second.toString() + "+"
                                + third.toString() + "+"
                                + fourth.toString() + "+"
                                + fifth.toString();
                    }));


        Optional<String> oResult = parser.tryParse("1Atrue2B");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true+2+B");
    }

    @Test
    public void test_match6_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Supplier<Parser<Integer>> number = () -> regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Supplier<Parser<Boolean>> bool   = () -> regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number.get()
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("B"))
                    .thenParse(bool)
                    .map(match( (first, second, third, fourth, fifth, sixth ) -> {
                        return first.toString() + "+"
                                + second.toString() + "+"
                                + third.toString() + "+"
                                + fourth.toString() + "+"
                                + fifth.toString() + "+"
                                + sixth.toString();
                    }));


        Optional<String> oResult = parser.tryParse("1Atrue2Bfalse");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true+2+B+false");
    }

    @Test
    public void test_match7_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Supplier<Parser<Integer>> number = () -> regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Supplier<Parser<Boolean>> bool   = () -> regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number.get()
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("B"))
                    .thenParse(bool)
                    .thenParse(number)
                    .map(match( (first, second, third, fourth, fifth, sixth, seventh) -> {
                        return first.toString() + "+"
                                + second.toString() + "+"
                                + third.toString() + "+"
                                + fourth.toString() + "+"
                                + fifth.toString() + "+"
                                + sixth.toString() + "+"
                                + seventh.toString();
                    }));


        Optional<String> oResult = parser.tryParse("1Atrue2Bfalse3");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true+2+B+false+3");
    }

    @Test
    public void test_match8_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Supplier<Parser<Integer>> number = () -> regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Supplier<Parser<Boolean>> bool   = () -> regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number.get()
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("B"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("C"))
                    .map(match( (first, second, third, fourth, fifth, sixth, seventh, eighth) -> {
                        return first.toString() + "+"
                                + second.toString() + "+"
                                + third.toString() + "+"
                                + fourth.toString() + "+"
                                + fifth.toString() + "+"
                                + sixth.toString() + "+"
                                + seventh.toString() + "+"
                                + eighth.toString();
                    }));


        Optional<String> oResult = parser.tryParse("1Atrue2Bfalse3C");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true+2+B+false+3+C");
    }

    @Test
    public void test_match9_works () throws ParserFailure {
        final Pattern numberPattern  = Pattern.compile("([0-9])");
        final Pattern booleanPattern = Pattern.compile("(true|false)");
        final Supplier<Parser<Integer>> number = () -> regex(numberPattern).map(m -> new Integer(m.group(1)));
        final Supplier<Parser<Boolean>> bool   = () -> regex(booleanPattern).map(m -> new Boolean(m.group(1)));

        Parser<String> parser =
                number.get()
                    .thenParse(string("A"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("B"))
                    .thenParse(bool)
                    .thenParse(number)
                    .thenParse(string("C"))
                    .thenParse(bool)
                    .map(match( (first, second, third, fourth, fifth, sixth, seventh, eighth, nineth) -> {
                        return first.toString() + "+"
                                + second.toString() + "+"
                                + third.toString() + "+"
                                + fourth.toString() + "+"
                                + fifth.toString() + "+"
                                + sixth.toString() + "+"
                                + seventh.toString() + "+"
                                + eighth.toString() + "+"
                                + nineth.toString();
                    }));


        Optional<String> oResult = parser.tryParse("1Atrue2Bfalse3Ctrue");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo("1+A+true+2+B+false+3+C+true");
    }


    @Test
    public void test_parsers_0orMore_parser_works () throws ParserFailure {
        String testText = "0123456789";

        Parser<List<String>> parser =
                zeroOrMore(regex("([0-9])").map(m -> m.group(1)));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).contains("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }


    @Test
    public void test_parsers_0orMore_parser_returns_empty_list () throws ParserFailure {
        String testText = "";

        Parser<List<String>> parser =
                zeroOrMore(regex("([0-9])").map(m -> m.group(1)));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEmpty();
    }

    @Test
    public void test_parsers_0orMore_with_seperator_parser_returns_empty_list () throws ParserFailure {
        String testText = "";

        Parser<List<String>> parser =
                zeroOrMore(regex("([0-9])").map(m -> m.group(1)), string(","));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEmpty();
    }

    @Test
    public void test_parsers_0orMore_with_seperator_parser_works () throws ParserFailure {
        String testText = "0,1,2,3,4,5,6,7,8,9";

        Parser<List<String>> parser =
                zeroOrMore(regex("([0-9])").map(m -> m.group(1)), string(","));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).contains("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    public void test_parsers_1orMore_parser_works () throws ParserFailure {
        String testText = "0123456789";

        Parser<List<String>> parser =
                oneOrMore(regex("([0-9])").map(m -> m.group(1)));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).contains("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }


    @Test
    public void test_parsers_1orMore_parser_returns_empty_list () throws ParserFailure {
        String testText = "";

        Parser<List<String>> parser =
                oneOrMore(regex("([0-9])").map(m -> m.group(1)));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_parsers_1orMore_with_seperator_parser_returns_empty_list () throws ParserFailure {
        String testText = "";

        Parser<List<String>> parser =
                oneOrMore(regex("([0-9])").map(m -> m.group(1)), string(","));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_parsers_1orMore_with_seperator_parser_works () throws ParserFailure {
        String testText = "0,1,2,3,4,5,6,7,8,9";

        Parser<List<String>> parser =
                oneOrMore(regex("([0-9])").map(m -> m.group(1)), string(","));

        Optional<List<String>> oResult = parser.tryParse(testText);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).contains("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }


    @Test
    public void test_test2_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .filter(test( (one, two) -> false ))
                        .map(match( (one, two ) -> 0 ));


        oResult = parser.tryParse("12");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .filter(test( (one, two) -> true ))
                        .map(match( (one, two ) -> one+two ));


        oResult = parser.tryParse("12");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(3);

        oResult = parser.tryParse("1");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_test3_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three ) -> false ))
                        .map(match( (one, two, three ) -> 0 ));


        oResult = parser.tryParse("123");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three) -> true ))
                        .map(match( (one, two, three ) -> one+two+three ));


        oResult = parser.tryParse("123");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(6);

        oResult = parser.tryParse("12");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_test4_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three,four) -> false ))
                        .map(match( (one, two, three,four) -> 0 ));


        oResult = parser.tryParse("1234");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three,four) -> true ))
                        .map(match( (one, two, three, four) -> one+two+three+four ));


        oResult = parser.tryParse("1234");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(10);

        oResult = parser.tryParse("123");
        assertThat(oResult.isPresent()).isFalse();

    }

    @Test
    public void test_test5_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five ) -> false ))
                        .map(match( (one, two, three, four, five ) -> 0 ));


        oResult = parser.tryParse("12345");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three,four, five) -> true ))
                        .map(match( (one, two, three, four, five) -> one+two+three+four+five ));


        oResult = parser.tryParse("12345");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(15);

        oResult = parser.tryParse("1234");
        assertThat(oResult.isPresent()).isFalse();

    }

    @Test
    public void test_test6_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six) -> false ))
                        .map(match( (one, two, three, four, five, six) -> 0 ));


        oResult = parser.tryParse("123456");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six) -> true ))
                        .map(match( (one, two, three, four, five, six) -> one+two+three+four+five+six ));


        oResult = parser.tryParse("123456");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(21);

        oResult = parser.tryParse("12345");
        assertThat(oResult.isPresent()).isFalse();

    }

    @Test
    public void test_test7_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six, seven) -> false ))
                        .map(match( (one, two, three, four, five, six, seven) -> 0 ));


        oResult = parser.tryParse("1234567");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six, seven) -> true ))
                        .map(match( (one, two, three, four, five, six, seven) -> one+two+three+four+five+six+seven ));


        oResult = parser.tryParse("1234567");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(28);

        oResult = parser.tryParse("123456");
        assertThat(oResult.isPresent()).isFalse();

    }

    @Test
    public void test_test8_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six, seven, eight) -> false ))
                        .map(match( (one, two, three, four, five, six, seven, eight) -> 0 ));


        oResult = parser.tryParse("12345678");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six, seven, eight) -> true ))
                        .map(match( (one, two, three, four, five, six, seven, eight) -> one+two+three+four+five+six+seven+eight));


        oResult = parser.tryParse("12345678");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(36);

        oResult = parser.tryParse("1234567");
        assertThat(oResult.isPresent()).isFalse();

    }

    @Test
    public void test_test9_works () throws ParserFailure {
        final Pattern numberPattern = Pattern.compile("([0-9])");
        final Parser<Integer> number = regex(numberPattern).map(m -> new Integer(m.group(1)));


        Parser<Number> parser;
        Optional<Number> oResult;

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six, seven, eight, nine) -> false ))
                        .map(match( (one, two, three, four, five, six, seven, eight, nine) -> 0 ));


        oResult = parser.tryParse("123456789");
        assertThat(oResult.isPresent()).isFalse();

        parser =
                number
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .thenParse(number)
                        .filter(test( (one, two, three, four, five, six, seven, eight, nine) -> true ))
                        .map(match( (one, two, three, four, five, six, seven, eight, nine) -> one+two+three+four+five+six+seven+eight+nine));


        oResult = parser.tryParse("123456789");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();
        assertThat(oResult.get()).isEqualTo(45);

        oResult = parser.tryParse("12345678");
        assertThat(oResult.isPresent()).isFalse();

    }
}
