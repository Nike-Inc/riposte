package com.nike.riposte.server.http.header;

import com.nike.riposte.server.http.header.accept.MediaRange;
import com.nike.riposte.server.http.header.accept.MediaRangeFixture;
import com.nike.riposte.util.text.parsercombinator.Parser;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by dpet22 on 8/2/16.
 */
public class AcceptHeaderParserTest {

    @Test
    public void test_accept_parser_works () throws Parser.ParserFailure {
        final String acceptHeaderValue = Arrays.stream(MediaRangeFixture.fixtures).map(MediaRangeFixture::getMediaRangeString).collect(Collectors.joining(","));
        final List<MediaRange> expectedRanges = Arrays.stream(MediaRangeFixture.fixtures).map(MediaRangeFixture::getExpectedMediaRange).collect(Collectors.toList());

        Optional<AcceptHeader> oAcceptHeader = AcceptHeaderParser.parse(acceptHeaderValue);

        assertThat(oAcceptHeader).isNotNull();
        assertThat(oAcceptHeader.isPresent()).isTrue();

        final List<MediaRange> parsedRanges = oAcceptHeader.get().mediaRanges;
        assertThat(parsedRanges).isNotNull();
        assertThat(parsedRanges.size()).isEqualTo(expectedRanges.size());
        expectedRanges.forEach( expectedRange ->  assertThat(parsedRanges.contains(expectedRange)).isTrue() );
    }

}
