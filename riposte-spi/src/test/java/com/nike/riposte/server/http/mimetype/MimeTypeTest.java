package com.nike.riposte.server.http.mimetype;

import org.junit.Test;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

import com.nike.riposte.server.http.mimetype.MimeType.Facet;

public class MimeTypeTest {


    @Test
    public void test_mimetype_compares_two_indentical_instances_correctly () {
        final MimeType one = MimeType.of(MimeType.Type.APPLICATION, MimeType.SubType.of("json"));
        final MimeType two = MimeType.of(MimeType.Type.APPLICATION, MimeType.SubType.of("json"));
        assertThat(one).isEqualTo(two);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
    }

    @Test
    public void test_mimetype_compares_two_different_instances_correctly () {
        final MimeType one = MimeType.of(MimeType.Type.APPLICATION, MimeType.SubType.of("json"));
        final MimeType two = MimeType.of(MimeType.Type.APPLICATION, MimeType.SubType.of(MimeType.Facet.STANDARD, "schema", Optional.of("json")));
        assertThat(one).isNotEqualTo(two);
        assertThat(one.hashCode()).isNotEqualTo(two.hashCode());
    }

    @Test
    public void test_mimetype_to_string_works () {
        final Map<String,String> parameters = new HashMap<>();
        parameters.put("key","value");
        final MimeType complex = MimeType.of(
                MimeType.Type.EXAMPLE,
                MimeType.SubType.of(MimeType.Facet.PERSONAL, "niketest", Optional.of("json")),
                parameters
        );
        assertThat(complex.toString()).isEqualTo("example/prs.niketest+json;key=value");
    }


    @Test
    public void test_facet_lookup_works () {
        final Optional<Facet> vendorFacet = Facet.forRegistrationTreeName("vnd");
        assertThat(vendorFacet).isNotNull();
        assertThat(vendorFacet.isPresent()).isTrue();
        assertThat(vendorFacet.get()).isNotNull();
        assertThat(vendorFacet.get()).isEqualTo(Facet.VENDOR);

    }


    @Test
    public void test_facet_lookup_with_null_name_fails () {
        final Optional<Facet> vendorFacet = Facet.forRegistrationTreeName(null);
        assertThat(vendorFacet).isNotNull();
        assertThat(vendorFacet.isPresent()).isFalse();
    }


    @Test
    public void test_facet_lookup_with_unknown_name_fails () {
        final Optional<Facet> vendorFacet = Facet.forRegistrationTreeName("tacos");
        assertThat(vendorFacet).isNotNull();
        assertThat(vendorFacet.isPresent()).isFalse();
    }
}
