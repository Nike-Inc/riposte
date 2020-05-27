package com.nike.riposte.server.http.header.accept;

import com.nike.riposte.server.http.mimetype.MimeType;

import org.junit.Test;
import com.nike.riposte.testutils.Whitebox;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Created by dpet22 on 8/3/16.
 */
public class MimeMediaRangeSubTypeTest {

    @Test
    public void test_media_range_subtype_constructor_throws_exceptin_from_null_value () {
        // when
        Throwable ex = catchThrowable(() -> new MimeMediaRangeSubType(null));

        // then
        assertThat(ex)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("subType instance cannot be null.");
    }

    @Test
    public void equals_returns_true_for_same_instance() {
        // given
        MimeMediaRangeSubType instance = new MimeMediaRangeSubType(MimeType.SubType.of("foo"));

        // expect
        assertThat(instance.equals(instance)).isTrue();
    }

    @Test
    public void hashCode_equals_subtype_hashcode() {
        // given
        MimeType.SubType subtype = MimeType.SubType.of("foo");
        MimeMediaRangeSubType instance = new MimeMediaRangeSubType(subtype);

        // expect
        assertThat(instance.hashCode()).isEqualTo(subtype.hashCode());
    }

    private String getToStringCache(MimeMediaRangeSubType instance) {
        return (String) Whitebox.getInternalState(instance, "toStringCache");
    }

    @Test
    public void toString_sets_and_uses_cache() {
        // given
        MimeMediaRangeSubType instance = new MimeMediaRangeSubType(MimeType.SubType.of("foo"));
        assertThat(getToStringCache(instance)).isNull();

        // when
        String toStringVal = instance.toString();

        // then
        assertThat(getToStringCache(instance)).isEqualTo(toStringVal);

        // and when
        String newCustomVal = UUID.randomUUID().toString();
        Whitebox.setInternalState(instance, "toStringCache", newCustomVal);

        // then
        assertThat(instance.toString()).isEqualTo(newCustomVal);
    }
}
