package com.nike.riposte.server.http.header.accept;

import com.nike.riposte.server.http.mimetype.MimeType;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Created by dpet22 on 8/3/16.
 */
public class MimeMediaRangeTypeTest {
    @Test
    public void test_media_range_type_constructor_throws_exceptin_from_null_value () {
        // when
        Throwable ex = catchThrowable(() -> new MimeMediaRangeType(null));

        // then
        assertThat(ex)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("type instance cannot be null.");
    }

    @Test
    public void equals_returns_true_for_same_instance() {
        // given
        MimeMediaRangeType instance = new MimeMediaRangeType(MimeType.Type.of("foo"));

        // expect
        assertThat(instance.equals(instance)).isTrue();
    }

    @Test
    public void hashCode_equals_subtype_hashcode() {
        // given
        MimeType.Type type = MimeType.Type.of("foo");
        MimeMediaRangeType instance = new MimeMediaRangeType(type);

        // expect
        assertThat(instance.hashCode()).isEqualTo(type.hashCode());
    }
}
