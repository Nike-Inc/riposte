package com.nike.riposte.server.http.header.accept;

import com.nike.internal.util.MapBuilder;
import com.nike.riposte.server.http.mimetype.MimeType;
import com.nike.riposte.util.text.parsercombinator.Parser;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nike.riposte.server.http.header.AcceptHeaderParser.mediaRangeParamatersParser;
import static com.nike.riposte.server.http.header.AcceptHeaderParser.mediaRangeParser;
import static com.nike.riposte.server.http.header.AcceptHeaderParser.mediaRangesParser;
import static com.nike.riposte.server.http.header.AcceptHeaderParser.qualityFactorParser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Created by dpet22 on 8/2/16.
 */
@RunWith(DataProviderRunner.class)
public class MediaRangeTest {



    @DataProvider
    public static Object[][] mediaRangeTestSet () {
        final int size = MediaRangeFixture.fixtures.length;
        Object[][] result = new Object[size][2];
        for (int i = 0; i < size; i++) {
            result[i] = new Object[] {MediaRangeFixture.fixtures[i].mediaRangeString, MediaRangeFixture.fixtures[i].expectedMediaRange};
        }
        return result;
    }



    @Test
    @DataProvider(value = {
            ";q=0.24|0.24",
            ";q =0.24|0.24",
            ";q= 0.24|0.24",
            ";q =.24|0.24",
            ";q= .24|0.24",
            "; q=.24|0.24",
            "; q=1|1.0"
    }, splitBy = "\\|")
    public void test_quality_factor_parser_works (final String testQualityFactorString, final String expectedFloatString) throws Parser.ParserFailure {
        Optional<Float> oResult;
        oResult = qualityFactorParser.tryParse(testQualityFactorString);
        assertThat(oResult).isNotNull();
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isEqualTo(Float.parseFloat(expectedFloatString));
    }

    @Test
    @DataProvider(value = {
            ";q=-0.1",
            ";q= 1.1",
    })
    public void test_quality_factor_parser_fails_values_GT_1_and_LT_0 (final String testQualityFactorString) throws Parser.ParserFailure {
        Optional<Float> oResult;
        oResult = qualityFactorParser.tryParse(testQualityFactorString);
        assertThat(oResult).isNotNull();
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_mediaRange_paramaters_parser_works () throws Parser.ParserFailure {
        mediaRangeParamatersParser.parse("; media=param; q=1.0; accept=param");
        Optional<Map<String,String>> oResult = mediaRangeParamatersParser.tryParse("; media=param; q=1.0; accept=param");

        assertThat(oResult).isNotNull();
        assertThat(oResult.isPresent()).isTrue();

        final Map<String,String> mediaParams = oResult.get();

        assertThat(mediaParams).isNotNull();
        assertThat(mediaParams.size()).isEqualTo(1);
        assertThat(mediaParams.containsKey("media")).isTrue();
        assertThat(mediaParams.get("media")).isNotNull();
        assertThat(mediaParams.get("media")).isEqualTo("param");

    }


    @Test
    @UseDataProvider("mediaRangeTestSet")
    public void test_media_range_parser_works (final String testMediaRangeString, final MediaRange expectedMediaRange)throws Parser.ParserFailure {
        MediaRange result = mediaRangeParser.parse(testMediaRangeString);
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(expectedMediaRange);
    }


    @Test
    public void test_mediaRangesParser_works () throws Parser.ParserFailure {
        final String acceptHeaderValue = Arrays.stream(MediaRangeFixture.fixtures).map(MediaRangeFixture::getMediaRangeString).collect(Collectors.joining(","));
        final List<MediaRange> parsedRanges = mediaRangesParser.parse(acceptHeaderValue);
        final List<MediaRange> expectedRanges = Arrays.stream(MediaRangeFixture.fixtures).map(MediaRangeFixture::getExpectedMediaRange).collect(Collectors.toList());
        assertThat(parsedRanges).isNotNull();
        assertThat(parsedRanges.size()).isEqualTo(MediaRangeFixture.fixtures.length);
        expectedRanges.forEach( expectedRange ->  assertThat(parsedRanges.contains(expectedRange)).isTrue() );
    }

    @DataProvider
    public static Object[][] nullMediaRangeConstructorTestSet () {
        return new Object[][] {
                {
                        "type instance cannot be null.",
                        new ThrowingCallable() {
                            public void call() throws Throwable {
                                new MediaRange(
                                        null,
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                                        1.0f,
                                        null,
                                        null
                                );
                            }
                        }
                },
                {
                        "subType instance cannot be null.",
                        new ThrowingCallable() {
                            public void call() throws Throwable {
                                new MediaRange(
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                                        null,
                                        1.0f,
                                        null,
                                        null
                                );
                            }
                        }
                },
                {
                        "qualityFactor instance cannot be null.",
                        new ThrowingCallable() {
                            public void call() throws Throwable {
                                new MediaRange(
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                                        null,
                                        null,
                                        null
                                );
                            }
                        }
                },
                {
                        "qualityFactor can not be less than 0.",
                        new ThrowingCallable() {
                            public void call() throws Throwable {
                                new MediaRange(
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                                        -0.1f,
                                        null,
                                        null
                                );
                            }
                        }
                },
                {
                        "qualityFactor can not be greater than 1.",
                        new ThrowingCallable() {
                            public void call() throws Throwable {
                                new MediaRange(
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                                        MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                                        1.1f,
                                        null,
                                        null
                                );
                            }
                        }
                }

        };
    }

    /**
     * MediaRange constructor should throw an IllegalArgumentException when the type, subtype, or qualityFactor properties are null.
     * Additionally, the constructor should throw an IllegalArgumentException when the quality factor value is less than 0 or greater than 1.
     */
    @Test
    @UseDataProvider("nullMediaRangeConstructorTestSet")
    public void test_media_range_constructor_throws_exception_with_null_values (final String expectedErrorMessage, final ThrowingCallable callable) {
        // when
        Throwable ex = catchThrowable(callable);

        // then
        assertThat(ex)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(expectedErrorMessage);
    }

    /**
     * MediaRange constructor should accept null values for the mediaRangeParameters or acceptParameters properties,
     * and assign an empty Map each null property.
     */
    @Test
    public void test_media_range_constructor_accepts_null_map_values () {
        MediaRange mediaRange = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                1.0f,
                null,
                null
        );
        assertThat(mediaRange).isNotNull();
        assertThat(mediaRange.acceptParameters).isNotNull();
        assertThat(mediaRange.mediaRangeParameters).isNotNull();
        assertThat(mediaRange.acceptParameters.size()).isZero();
        assertThat(mediaRange.mediaRangeParameters.size()).isZero();
    }


    /**
     * MediaRanges with higher quality factors  should have gretor precedence (lower compareTo result) than wildcard subtypes.
     */
    @Test
    public void test_media_range_compareTo_works_for_quality_factors () {
        MediaRange higherQualityFactor = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                1.0f,
                null,
                null
        );
        MediaRange lowerQualityFactor = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                0.99f,
                null,
                null
        );

        assertThat(higherQualityFactor.compareTo(lowerQualityFactor)).isNotEqualTo(0);
        assertThat(higherQualityFactor.compareTo(lowerQualityFactor)).isLessThan(0);
        assertThat(lowerQualityFactor.compareTo(higherQualityFactor)).isGreaterThan(0);
    }

    /**
     * Wildcard type mediaranges should compareTo zero.
     */
    @Test
    public void test_media_range_compareTo_works_for_wildcard_types () {
        MediaRange wildcardTypeInstanceOne = new MediaRange(
                MediaRange.WILDCARD_TYPE,
                MediaRange.WILDCARD_SUBTYPE,
                1.0f,
                null,
                null
        );
        MediaRange wildcardTypeInstanceTwo = new MediaRange(
                MediaRange.WILDCARD_TYPE,
                MediaRange.WILDCARD_SUBTYPE,
                1.0f,
                null,
                null
        );

        assertThat(wildcardTypeInstanceOne.compareTo(wildcardTypeInstanceTwo)).isEqualTo(0);
    }

    /**
     * specific subtype should have gretor precedence (lower compareTo result) than wildcard subtypes.
     */
    @Test
    public void test_media_range_compareTo_works_for_wildcard_subtypes () {
        MediaRange higher = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                1.0f,
                null,
                null
        );
        MediaRange lower = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRange.WILDCARD_SUBTYPE,
                1.0f,
                null,
                null
        );

        assertThat(higher.compareTo(lower)).isNotEqualTo(0);
        assertThat(higher.compareTo(lower)).isLessThan(0);
        assertThat(lower.compareTo(higher)).isGreaterThan(0);
    }

    /**
     * A specific MediaRange with more parameters should have gretor precedence (lower compareTo result) than
     * MediaRange
     */
    @Test
    public void test_media_range_compareTo_works_for_parameter_lengths () {
        MediaRange higher = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                1.0f,
                Collections.singletonMap("key","value"),
                null
        );
        MediaRange lower = new MediaRange(
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
                MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
                1.0f,
                null,
                null
        );

        assertThat(higher.compareTo(lower)).isNotEqualTo(0);
        assertThat(higher.compareTo(lower)).isLessThan(0);
        assertThat(lower.compareTo(higher)).isGreaterThan(0);
    }

    private MediaRangeType mrType(String typeString) {
        if (MediaRange.WILDCARD_TYPE.toString().equals(typeString))
            return MediaRange.WILDCARD_TYPE;

        return new MimeMediaRangeType(MimeType.Type.of(typeString));
    }

    private MediaRangeSubType mrSubType(String typeString) {
        if (MediaRange.WILDCARD_SUBTYPE.toString().equals(typeString))
            return MediaRange.WILDCARD_SUBTYPE;

        MimeType.Facet facet = MimeType.Facet.STANDARD;
        if (typeString.startsWith(MimeType.Facet.VENDOR.getRegistrationTreeName().get()))
            facet = MimeType.Facet.VENDOR;

        Optional<String> suffix = Optional.empty();
        if (typeString.endsWith("+json"))
            suffix = Optional.of("json");

        return new MimeMediaRangeSubType(MimeType.SubType.of(facet, typeString, suffix));
    }

    private Map<String, String> generateRandomMapOfSize(int size) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            map.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }

        return map;
    }

    @DataProvider(value = {
        // Base case - everything equal, passes all checks, returns 0
        "0  |   0   |   foo |   foo |   bar |   bar |   0   |   0   |   0   |   0   |   0",
        // Quality factor wins out over everything
        "0.9|   0.8 |   *   |   *   |   *   |   *   |   0   |   0   |   0   |   0   |   -1",
        "0.8|   0.9 |   *   |   *   |   *   |   *   |   0   |   0   |   0   |   0   |   1",
        // Next is wildcard comparisons for type
        "0  |   0   |   *   |   *   |   bar |   bar |   0   |   0   |   0   |   0   |   0",
        "0  |   0   |   *   |   foo |   bar |   bar |   0   |   0   |   0   |   0   |   1",
        "0  |   0   |   foo |   *   |   bar |   bar |   0   |   0   |   0   |   0   |   -1",
        // Then wildcard comparisons for subtype
        "0  |   0   |   foo |   foo |   *   |   *   |   0   |   0   |   0   |   0   |   0",
        "0  |   0   |   abc |   xyz |   *   |   *   |   0   |   0   |   0   |   0   |   -23",
        "0  |   0   |   xyz |   abc |   *   |   *   |   0   |   0   |   0   |   0   |   23",
        "0  |   0   |   foo |   foo |   *   |   bar |   0   |   0   |   0   |   0   |   1",
        "0  |   0   |   foo |   foo |   bar |   *   |   0   |   0   |   0   |   0   |   -1",
        // Then param count differences
        "0  |   0   |   foo |   foo |   bar |   bar |   1   |   0   |   2   |   0   |   -3",
        "0  |   0   |   foo |   foo |   bar |   bar |   1   |   1   |   2   |   0   |   -2",
        "0  |   0   |   foo |   foo |   bar |   bar |   1   |   0   |   2   |   2   |   -1",
        "0  |   0   |   foo |   foo |   bar |   bar |   0   |   1   |   0   |   2   |   3",
        "0  |   0   |   foo |   foo |   bar |   bar |   1   |   1   |   0   |   2   |   2",
        "0  |   0   |   foo |   foo |   bar |   bar |   0   |   1   |   2   |   2   |   1",
        // Then subtype facet differences
        "0  |   0   |   foo |   foo |   vnd.bar |   bar     |   0   |   0   |   0   |   0   |   -1",
        "0  |   0   |   foo |   foo |   bar     |   vnd.bar |   0   |   0   |   0   |   0   |   1",
        "0  |   0   |   foo |   foo |   vnd.bar |   vnd.bar |   0   |   0   |   0   |   0   |   0",
        // Then subtype suffix differences
        "0  |   0   |   foo |   foo |   bar+json    |   bar         |   0   |   0   |   0   |   0   |   -1",
        "0  |   0   |   foo |   foo |   bar         |   bar+json    |   0   |   0   |   0   |   0   |   1",
        "0  |   0   |   foo |   foo |   bar+json    |   bar+json    |   0   |   0   |   0   |   0   |   0",
        // Then type name comparison
        "0  |   0   |   abc |   xyz |   bar |   bar |   0   |   0   |   0   |   0   |   -23",
        "0  |   0   |   xyz |   abc |   bar |   bar |   0   |   0   |   0   |   0   |   23",
        // And finally subtype name comparison
        "0  |   0   |   foo |   foo |   abc |   xyz |   0   |   0   |   0   |   0   |   -23",
        "0  |   0   |   foo |   foo |   xyz |   abc |   0   |   0   |   0   |   0   |   23",
    }, splitBy = "\\|")
    @Test
    public void compareTo_works_as_expected(
        float thisQf, float otherQf, String thisType, String otherType, String thisSubtype, String otherSubtype,
        int thisMediaRangeParamsSize, int otherMediaRangeParamsSize, int thisAcceptParamsSize,
        int otherAcceptParamsSize, int expectedResult
    ) {
        // given
        MediaRange thisMr = new MediaRange(mrType(thisType), mrSubType(thisSubtype), thisQf, 
                                           generateRandomMapOfSize(thisMediaRangeParamsSize), 
                                           generateRandomMapOfSize(thisAcceptParamsSize));
        MediaRange otherMr = new MediaRange(mrType(otherType), mrSubType(otherSubtype), otherQf,
                                           generateRandomMapOfSize(otherMediaRangeParamsSize),
                                           generateRandomMapOfSize(otherAcceptParamsSize));

        // when
        int result = thisMr.compareTo(otherMr);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    public void equals_returns_true_for_same_instance() {
        // given
        MediaRange instance = new MediaRange(
            MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
            MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
            1.0f, null, null
        );

        // expect
        assertThat(instance.equals(instance)).isTrue();
    }

    @Test
    public void equals_returns_false_when_compared_against_object_that_is_not_a_MediaRange() {
        // given
        MediaRange instance = new MediaRange(
            MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
            MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
            1.0f, null, null
        );

        // expect
        assertThat(instance.equals(new Object())).isFalse();
    }

    @Test
    public void hashCode_is_equal_to_Objects_dot_hash_of_all_fields() {
        // given
        MediaRange instance = new MediaRange(
            MediaRangeFixture.nikeRunningCoach.expectedMediaRange.type,
            MediaRangeFixture.nikeRunningCoach.expectedMediaRange.subType,
            1.0f,
            MapBuilder.<String, String>builder().put("foo", UUID.randomUUID().toString()).build(),
            MapBuilder.<String, String>builder().put("bar", UUID.randomUUID().toString()).build()
        );

        // expect
        assertThat(instance.hashCode())
            .isEqualTo(Objects.hash(instance.type, instance.subType, instance.qualityFactor,
                                    instance.mediaRangeParameters, instance.acceptParameters)
            );
    }
}
