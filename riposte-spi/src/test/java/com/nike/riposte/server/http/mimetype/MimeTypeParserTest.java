package com.nike.riposte.server.http.mimetype;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.mimetype.MimeType.Facet;
import com.nike.riposte.server.http.mimetype.MimeType.SubType;
import com.nike.riposte.server.http.mimetype.MimeType.Type;
import com.nike.riposte.util.text.parsercombinator.Parser.ParserFailure;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.regex.MatchResult;

import static org.assertj.core.api.Assertions.assertThat;


@RunWith(DataProviderRunner.class)
public class MimeTypeParserTest {

    public static final String VALID_CHARS =
            "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "1234567890"+
            "!#$&-^_.";


    @Test
    public void test_token_parser_should_parse_valid_string () throws ParserFailure {
        final Optional<String> oResult = MimeTypeParser.token.tryParse(VALID_CHARS);
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isEqualTo(VALID_CHARS);
    }

    @Test
    public void test_token_parser_should_not_parse_invalid_string () throws ParserFailure {
        final Optional<String> oResult = MimeTypeParser.token.tryParse("application@");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isEqualTo("application");
    }

    @Test
    public void test_semicolon_parser_should_parse_valid_semicolon_when_not_surrounded_by_whitespace () throws ParserFailure {
        final Optional<MatchResult> oResult = MimeTypeParser.semicolon.tryParse(";");
        assertThat(oResult.isPresent()).isTrue();
    }

    @Test
    public void test_semicolon_parser_should_parse_valid_semicolon_when_surrounded_by_leading_whitespace () throws ParserFailure {
        Optional<MatchResult> oResult = MimeTypeParser.semicolon.tryParse("  ;");
        assertThat(oResult.isPresent()).isTrue();
    }

    @Test
    public void test_semicolon_parser_should_parse_valid_semicolon_when_surrounded_by_trailing_whitespace () throws ParserFailure {
        final Optional<MatchResult> oResult = MimeTypeParser.semicolon.tryParse(";  ");
        assertThat(oResult.isPresent()).isTrue();
    }

    @Test
    public void test_semicolon_parser_should_not_parse_a_non_semicolon_value () throws ParserFailure {
        final Optional<MatchResult> oResult = MimeTypeParser.semicolon.tryParse(".");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_parameter_parser_parses_valid_pair () throws ParserFailure {
        final Optional<Pair<String, String>> oResult = MimeTypeParser.parameterParser.tryParse("key=value");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get().getLeft()).isNotNull();
        assertThat(oResult.get().getLeft()).isEqualTo("key");
        assertThat(oResult.get().getRight()).isNotNull();
        assertThat(oResult.get().getRight()).isEqualTo("value");
    }

    @Test
    public void test_parameter_parser_does_not_parse_invalid_pair_with_missing_equals_char () throws ParserFailure {
        final Optional<Pair<String, String>> oResult = MimeTypeParser.parameterParser.tryParse("key");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_parameter_parser_does_not_parse_invalid_pair_with_missing_key () throws ParserFailure {
        final Optional<Pair<String, String>> oResult = MimeTypeParser.parameterParser.tryParse("=value");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_parameter_parser_does_not_parse_invalid_pair_with_missing_value () throws ParserFailure {
        final Optional<Pair<String, String>> oResult = MimeTypeParser.parameterParser.tryParse("key=");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_parameters_parser_parses_valid_pairs () throws ParserFailure {
        final Optional<Map<String, String>> oResult = MimeTypeParser.parametersParser.tryParse("; key1=value1; key2=value2");
        assertThat(oResult.isPresent()).isTrue();

        final Map<String,String> map = oResult.get();
        assertThat(map.size()).isEqualTo(2);

        assertThat(map.get("key1")).isNotNull();
        assertThat(map.get("key1")).isEqualTo("value1");
        assertThat(map.get("key2")).isNotNull();
        assertThat(map.get("key2")).isEqualTo("value2");
    }

    @Test
    public void test_parameters_parser_does_not_parse_invalid_pair_with_missing_equals_char () throws ParserFailure {
        final Optional<Map<String, String>> oResult = MimeTypeParser.parametersParser.tryParse("; key1=value1; key2");
        assertThat(oResult.isPresent()).isTrue();
        final Map<String,String> map = oResult.get();
        assertThat(map.size()).isEqualTo(1);

        assertThat(map.containsKey("key1")).isTrue();
        assertThat(map.get("key1")).isEqualTo("value1");
        assertThat(map.containsKey("key2")).isFalse();
    }

    @Test
    public void test_parameters_parser_does_not_parse_invalid_pair_with_missing_key () throws ParserFailure {
        final Optional<Map<String, String>> oResult = MimeTypeParser.parametersParser.tryParse("; key1=value1; =value2");
        assertThat(oResult.isPresent()).isTrue();
        final Map<String,String> map = oResult.get();
        assertThat(map.size()).isEqualTo(1);

        assertThat(map.containsKey("key1")).isTrue();
        assertThat(map.get("key1")).isEqualTo("value1");
        assertThat(map.containsValue("value2")).isFalse();

    }

    @Test
    public void test_parameters_parser_does_not_parse_invalid_pair_with_missing_value () throws ParserFailure {
        final Optional<Map<String, String>> oResult = MimeTypeParser.parametersParser.tryParse("; key1=value1; key2=");
        assertThat(oResult.isPresent()).isTrue();
        final Map<String,String> map = oResult.get();
        assertThat(map.size()).isEqualTo(1);

        assertThat(map.containsKey("key1")).isTrue();
        assertThat(map.get("key1")).isEqualTo("value1");
        assertThat(map.containsKey("key2")).isFalse();
    }

    @Test
    public void test_type_parser_does_parse_valid_type () throws ParserFailure {
        Optional<Type> oResult = MimeTypeParser.typeParser.tryParse("application");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isSameAs(MimeType.Type.APPLICATION);
    }

    @Test
    public void test_type_parser_does_parse_unknown_type () throws ParserFailure {
        Optional<Type> oResult = MimeTypeParser.typeParser.tryParse("unknown");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotSameAs(Type.of("unknown"));
        assertThat(oResult.get().getName()).isEqualTo("unknown");
    }

    @Test
    public void test_facet_parser_does_parse_valid_vendor_facet () throws ParserFailure {
        Optional<Facet> oResult = MimeTypeParser.facetParser.tryParse("vnd");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isEqualTo(Facet.VENDOR);
    }

    @Test
    public void test_facet_parser_does_parse_valid_personal_facet () throws ParserFailure {
        Optional<Facet> oResult = MimeTypeParser.facetParser.tryParse("prs");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isEqualTo(Facet.PERSONAL);
    }

    @Test
    public void test_facet_parser_does_parse_valid_unregistered_facet () throws ParserFailure {
        Optional<Facet> oResult = MimeTypeParser.facetParser.tryParse("x");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isEqualTo(Facet.UNREGISTERED);
    }

    @Test
    public void test_facet_parser_does_not_parse_invalid_facet () throws ParserFailure {
        Optional<Facet> oResult = MimeTypeParser.facetParser.tryParse("tacos");
        assertThat(oResult.isPresent()).isFalse();
    }

    @Test
    public void test_subtype_parser_does_parse_valid_subtype_in_the_standard_facet_without_a_suffix () throws ParserFailure {
        Optional<SubType> oResult = MimeTypeParser.subTypeParser.tryParse("json");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get().getFacet()).isNotNull();
        assertThat(oResult.get().getFacet()).isEqualTo(Facet.STANDARD);
        assertThat(oResult.get().getSuffix()).isNotNull();
        assertThat(oResult.get().getSuffix()).isEqualTo(Optional.empty());
        assertThat(oResult.get().getName()).isNotNull();
        assertThat(oResult.get().getName()).isEqualTo("json");
    }

    @Test
    public void test_subtype_parser_does_parse_valid_subtype_in_the_vendor_facet_without_a_suffix () throws ParserFailure {
        Optional<SubType> oResult = MimeTypeParser.subTypeParser.tryParse("vnd.json");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get().getFacet()).isNotNull();
        assertThat(oResult.get().getFacet()).isEqualTo(Facet.VENDOR);
        assertThat(oResult.get().getSuffix()).isNotNull();
        assertThat(oResult.get().getSuffix()).isEqualTo(Optional.empty());
        assertThat(oResult.get().getName()).isNotNull();
        assertThat(oResult.get().getName()).isEqualTo("json");
    }


    @Test
    public void test_subtype_parser_does_parse_valid_subtype_in_the_vendor_facet_with_a_suffix () throws ParserFailure {
        Optional<SubType> oResult = MimeTypeParser.subTypeParser.tryParse("vnd.nike.runningcoach-v3.1+json");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get().getFacet()).isNotNull();
        assertThat(oResult.get().getFacet()).isEqualTo(Facet.VENDOR);
        assertThat(oResult.get().getSuffix()).isNotNull();
        assertThat(oResult.get().getSuffix().get()).isNotNull();
        assertThat(oResult.get().getSuffix().get()).isEqualTo("json");
        assertThat(oResult.get().getName()).isNotNull();
        assertThat(oResult.get().getName()).isEqualTo("nike.runningcoach-v3.1");
    }

    @Test
    public void test_mimetype_parser_does_parse_valid_mimetype_in_the_standard_facet_without_a_suffix () throws ParserFailure {
        Optional<MimeType> oResult;
        MimeType mime;

        oResult = MimeTypeParser.parse("application/json");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();

        mime = oResult.get();
        assertThat(mime.getType()).isEqualTo(MimeType.Type.APPLICATION);
        assertThat(mime.getSubType().getName()).isEqualTo("json");
    }

    @Test
    public void test_mimetype_parser_does_parse_valid_mimetype_in_the_vendor_facet_with_a_json_suffix () throws ParserFailure {
        Optional<MimeType> oResult;
        MimeType mime;

        oResult = MimeTypeParser.parse("application/vnd.nike.runningcoach-v3.1+json");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();

        mime = oResult.get();
        assertThat(mime.getType()).isEqualTo(MimeType.Type.APPLICATION);
        assertThat(mime.getSubType().getName()).isEqualTo("nike.runningcoach-v3.1");
        assertThat(mime.getSubType().getFacet()).isEqualTo(MimeType.Facet.VENDOR);
        assertThat(mime.getSubType().getSuffix()).isNotNull();
        assertThat(mime.getSubType().getSuffix().get()).isNotNull();
        assertThat(mime.getSubType().getSuffix().get()).isEqualTo("json");
        assertThat(mime.getParameters()).isNotNull();
        assertThat(mime.getParameters()).isEmpty();

    }

    @Test
    public void test_mimetype_parser_does_parse_valid_mimetype_in_the_vendor_facet_with_a_json_suffix_and_one_parameter () throws ParserFailure {
        Optional<MimeType> oResult;
        MimeType mime;

        oResult = MimeTypeParser.parse("application/vnd.nike.runningcoach-v3.1+json;charset=UTF-8");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();

        mime = oResult.get();
        assertThat(mime.getType()).isEqualTo(MimeType.Type.APPLICATION);
        assertThat(mime.getSubType().getName()).isEqualTo("nike.runningcoach-v3.1");
        assertThat(mime.getSubType().getFacet()).isEqualTo(MimeType.Facet.VENDOR);
        assertThat(mime.getSubType().getSuffix()).isNotNull();
        assertThat(mime.getSubType().getSuffix().get()).isNotNull();
        assertThat(mime.getSubType().getSuffix().get()).isEqualTo("json");
        assertThat(mime.getParameters()).isNotNull();
        assertThat(mime.getParameters()).isNotEmpty();
        assertThat(mime.getParameters().get("charset")).isNotNull();
        assertThat(mime.getParameters().get("charset")).isEqualTo("UTF-8");
    }

    @Test
    public void test_mimetype_parser_does_parse_valid_mimetype_in_the_standard_facet_without_a_suffix_and_two_parameters () throws ParserFailure {
        Optional<MimeType> oResult;
        MimeType mime;

        oResult = MimeTypeParser.parse("application/json; param1=one; param2=two");
        assertThat(oResult.isPresent()).isTrue();
        assertThat(oResult.get()).isNotNull();

        mime = oResult.get();
        assertThat(mime.getType()).isEqualTo(MimeType.Type.APPLICATION);
        assertThat(mime.getSubType().getName()).isEqualTo("json");
        assertThat(mime.getParameters()).isNotNull();
        assertThat(mime.getParameters()).isNotEmpty();
        assertThat(mime.getParameters().size()).isEqualTo(2);
        assertThat(mime.getParameters().get("param1")).isNotNull();
        assertThat(mime.getParameters().get("param1")).isEqualTo("one");
        assertThat(mime.getParameters().get("param2")).isNotNull();
        assertThat(mime.getParameters().get("param2")).isEqualTo("two");
    }

    @Test
    @DataProvider(value = {
            "application/vnd.hzn-3d-crossword|application|vnd|hzn-3d-crossword|null",
            "video/3gpp|video|null|3gpp|null",
            "video/3gpp2|video|null|3gpp2|null",
            "application/vnd.mseq|application|vnd|mseq|null",
            "application/vnd.3m.post-it-notes|application|vnd|3m.post-it-notes|null",
            "application/vnd.3gpp.pic-bw-large|application|vnd|3gpp.pic-bw-large|null",
            "application/vnd.3gpp.pic-bw-small|application|vnd|3gpp.pic-bw-small|null",
            "application/vnd.3gpp.pic-bw-var|application|vnd|3gpp.pic-bw-var|null",
            "application/vnd.3gpp2.tcap|application|vnd|3gpp2.tcap|null",
            "application/x-7z-compressed|application|null|x-7z-compressed|null",
            "application/x-abiword|application|null|x-abiword|null",
            "application/x-ace-compressed|application|null|x-ace-compressed|null",
            "application/vnd.americandynamics.acc|application|vnd|americandynamics.acc|null",
            "application/vnd.acucobol|application|vnd|acucobol|null",
            "application/vnd.acucorp|application|vnd|acucorp|null",
            "audio/adpcm|audio|null|adpcm|null",
            "application/x-authorware-bin|application|null|x-authorware-bin|null",
            "application/x-authorware-map|application|null|x-authorware-map|null",
            "application/x-authorware-seg|application|null|x-authorware-seg|null",
            "application/vnd.adobe.air-application-installer-package+zip|application|vnd|adobe.air-application-installer-package|zip",
            "application/x-shockwave-flash|application|null|x-shockwave-flash|null",
            "application/vnd.adobe.fxp|application|vnd|adobe.fxp|null",
            "application/pdf|application|null|pdf|null",
            "application/vnd.cups-ppd|application|vnd|cups-ppd|null",
            "application/x-director|application|null|x-director|null",
            "application/vnd.adobe.xdp+xml|application|vnd|adobe.xdp|xml",
            "application/vnd.adobe.xfdf|application|vnd|adobe.xfdf|null",
            "audio/x-aac|audio|null|x-aac|null",
            "application/vnd.ahead.space|application|vnd|ahead.space|null",
            "application/vnd.airzip.filesecure.azf|application|vnd|airzip.filesecure.azf|null",
            "application/vnd.airzip.filesecure.azs|application|vnd|airzip.filesecure.azs|null",
            "application/vnd.amazon.ebook|application|vnd|amazon.ebook|null",
            "application/vnd.amiga.ami|application|vnd|amiga.ami|null",
            "application/andrew-inset|application|null|andrew-inset|null",
            "application/vnd.android.package-archive|application|vnd|android.package-archive|null",
            "application/vnd.anser-web-certificate-issue-initiation|application|vnd|anser-web-certificate-issue-initiation|null",
            "application/vnd.anser-web-funds-transfer-initiation|application|vnd|anser-web-funds-transfer-initiation|null",
            "application/vnd.antix.game-component|application|vnd|antix.game-component|null",
            "application/vnd.apple.installer+xml|application|vnd|apple.installer|xml",
            "application/applixware|application|null|applixware|null",
            "application/vnd.hhe.lesson-player|application|vnd|hhe.lesson-player|null",
            "application/vnd.aristanetworks.swi|application|vnd|aristanetworks.swi|null",
            "text/x-asm|text|null|x-asm|null",
            "application/atomcat+xml|application|null|atomcat|xml",
            "application/atomsvc+xml|application|null|atomsvc|xml",
            "application/atom+xml|application|null|atom|xml",
            "application/pkix-attr-cert|application|null|pkix-attr-cert|null",
            "audio/x-aiff|audio|null|x-aiff|null",
            "video/x-msvideo|video|null|x-msvideo|null",
            "application/vnd.audiograph|application|vnd|audiograph|null",
            "image/vnd.dxf|image|vnd|dxf|null",
            "model/vnd.dwf|model|vnd|dwf|null",
            "text/plain-bas|text|null|plain-bas|null",
            "application/x-bcpio|application|null|x-bcpio|null",
            "application/octet-stream|application|null|octet-stream|null",
            "image/bmp|image|null|bmp|null",
            "application/x-bittorrent|application|null|x-bittorrent|null",
            "application/vnd.rim.cod|application|vnd|rim.cod|null",
            "application/vnd.blueice.multipass|application|vnd|blueice.multipass|null",
            "application/vnd.bmi|application|vnd|bmi|null",
            "application/x-sh|application|null|x-sh|null",
            "image/prs.btif|image|prs|btif|null",
            "application/vnd.businessobjects|application|vnd|businessobjects|null",
            "application/x-bzip|application|null|x-bzip|null",
            "application/x-bzip2|application|null|x-bzip2|null",
            "application/x-csh|application|null|x-csh|null",
            "text/x-c|text|null|x-c|null",
            "application/vnd.chemdraw+xml|application|vnd|chemdraw|xml",
            "text/css|text|null|css|null",
            "chemical/x-cdx|chemical|null|x-cdx|null",
            "chemical/x-cml|chemical|null|x-cml|null",
            "chemical/x-csml|chemical|null|x-csml|null",
            "application/vnd.contact.cmsg|application|vnd|contact.cmsg|null",
            "application/vnd.claymore|application|vnd|claymore|null",
            "application/vnd.clonk.c4group|application|vnd|clonk.c4group|null",
            "image/vnd.dvb.subtitle|image|vnd|dvb.subtitle|null",
            "application/cdmi-capability|application|null|cdmi-capability|null",
            "application/cdmi-container|application|null|cdmi-container|null",
            "application/cdmi-domain|application|null|cdmi-domain|null",
            "application/cdmi-object|application|null|cdmi-object|null",
            "application/cdmi-queue|application|null|cdmi-queue|null",
            "application/vnd.cluetrust.cartomobile-config|application|vnd|cluetrust.cartomobile-config|null",
            "application/vnd.cluetrust.cartomobile-config-pkg|application|vnd|cluetrust.cartomobile-config-pkg|null",
            "image/x-cmu-raster|image|null|x-cmu-raster|null",
            "model/vnd.collada+xml|model|vnd|collada|xml",
            "text/csv|text|null|csv|null",
            "application/mac-compactpro|application|null|mac-compactpro|null",
            "application/vnd.wap.wmlc|application|vnd|wap.wmlc|null",
            "image/cgm|image|null|cgm|null",
            "x-conference/x-cooltalk|x-conference|null|x-cooltalk|null",
            "image/x-cmx|image|null|x-cmx|null",
            "application/vnd.xara|application|vnd|xara|null",
            "application/vnd.cosmocaller|application|vnd|cosmocaller|null",
            "application/x-cpio|application|null|x-cpio|null",
            "application/vnd.crick.clicker|application|vnd|crick.clicker|null",
            "application/vnd.crick.clicker.keyboard|application|vnd|crick.clicker.keyboard|null",
            "application/vnd.crick.clicker.palette|application|vnd|crick.clicker.palette|null",
            "application/vnd.crick.clicker.template|application|vnd|crick.clicker.template|null",
            "application/vnd.crick.clicker.wordbank|application|vnd|crick.clicker.wordbank|null",
            "application/vnd.criticaltools.wbs+xml|application|vnd|criticaltools.wbs|xml",
            "application/vnd.rig.cryptonote|application|vnd|rig.cryptonote|null",
            "chemical/x-cif|chemical|null|x-cif|null",
            "chemical/x-cmdf|chemical|null|x-cmdf|null",
            "application/cu-seeme|application|null|cu-seeme|null",
            "application/prs.cww|application|prs|cww|null",
            "text/vnd.curl|text|vnd|curl|null",
            "text/vnd.curl.dcurl|text|vnd|curl.dcurl|null",
            "text/vnd.curl.mcurl|text|vnd|curl.mcurl|null",
            "text/vnd.curl.scurl|text|vnd|curl.scurl|null",
            "application/vnd.curl.car|application|vnd|curl.car|null",
            "application/vnd.curl.pcurl|application|vnd|curl.pcurl|null",
            "application/vnd.yellowriver-custom-menu|application|vnd|yellowriver-custom-menu|null",
            "application/dssc+der|application|null|dssc|der",
            "application/dssc+xml|application|null|dssc|xml",
            "application/x-debian-package|application|null|x-debian-package|null",
            "audio/vnd.dece.audio|audio|vnd|dece.audio|null",
            "image/vnd.dece.graphic|image|vnd|dece.graphic|null",
            "video/vnd.dece.hd|video|vnd|dece.hd|null",
            "video/vnd.dece.mobile|video|vnd|dece.mobile|null",
            "video/vnd.uvvu.mp4|video|vnd|uvvu.mp4|null",
            "video/vnd.dece.pd|video|vnd|dece.pd|null",
            "video/vnd.dece.sd|video|vnd|dece.sd|null",
            "video/vnd.dece.video|video|vnd|dece.video|null",
            "application/x-dvi|application|null|x-dvi|null",
            "application/vnd.fdsn.seed|application|vnd|fdsn.seed|null",
            "application/x-dtbook+xml|application|null|x-dtbook|xml",
            "application/x-dtbresource+xml|application|null|x-dtbresource|xml",
            "application/vnd.dvb.ait|application|vnd|dvb.ait|null",
            "application/vnd.dvb.service|application|vnd|dvb.service|null",
            "audio/vnd.digital-winds|audio|vnd|digital-winds|null",
            "image/vnd.djvu|image|vnd|djvu|null",
            "application/xml-dtd|application|null|xml-dtd|null",
            "application/vnd.dolby.mlp|application|vnd|dolby.mlp|null",
            "application/x-doom|application|null|x-doom|null",
            "application/vnd.dpgraph|application|vnd|dpgraph|null",
            "audio/vnd.dra|audio|vnd|dra|null",
            "application/vnd.dreamfactory|application|vnd|dreamfactory|null",
            "audio/vnd.dts|audio|vnd|dts|null",
            "audio/vnd.dts.hd|audio|vnd|dts.hd|null",
            "image/vnd.dwg|image|vnd|dwg|null",
            "application/vnd.dynageo|application|vnd|dynageo|null",
            "application/ecmascript|application|null|ecmascript|null",
            "application/vnd.ecowin.chart|application|vnd|ecowin.chart|null",
            "image/vnd.fujixerox.edmics-mmr|image|vnd|fujixerox.edmics-mmr|null",
            "image/vnd.fujixerox.edmics-rlc|image|vnd|fujixerox.edmics-rlc|null",
            "application/exi|application|null|exi|null",
            "application/vnd.proteus.magazine|application|vnd|proteus.magazine|null",
            "application/epub+zip|application|null|epub|zip",
            "message/rfc822|message|null|rfc822|null",
            "application/vnd.enliven|application|vnd|enliven|null",
            "application/vnd.is-xpr|application|vnd|is-xpr|null",
            "image/vnd.xiff|image|vnd|xiff|null",
            "application/vnd.xfdl|application|vnd|xfdl|null",
            "application/emma+xml|application|null|emma|xml",
            "application/vnd.ezpix-album|application|vnd|ezpix-album|null",
            "application/vnd.ezpix-package|application|vnd|ezpix-package|null",
            "image/vnd.fst|image|vnd|fst|null",
            "video/vnd.fvt|video|vnd|fvt|null",
            "image/vnd.fastbidsheet|image|vnd|fastbidsheet|null",
            "application/vnd.denovo.fcselayout-link|application|vnd|denovo.fcselayout-link|null",
            "video/x-f4v|video|null|x-f4v|null",
            "video/x-flv|video|null|x-flv|null",
            "image/vnd.fpx|image|vnd|fpx|null",
            "image/vnd.net-fpx|image|vnd|net-fpx|null",
            "text/vnd.fmi.flexstor|text|vnd|fmi.flexstor|null",
            "video/x-fli|video|null|x-fli|null",
            "application/vnd.fluxtime.clip|application|vnd|fluxtime.clip|null",
            "application/vnd.fdf|application|vnd|fdf|null",
            "text/x-fortran|text|null|x-fortran|null",
            "application/vnd.mif|application|vnd|mif|null",
            "application/vnd.framemaker|application|vnd|framemaker|null",
            "image/x-freehand|image|null|x-freehand|null",
            "application/vnd.fsc.weblaunch|application|vnd|fsc.weblaunch|null",
            "application/vnd.frogans.fnc|application|vnd|frogans.fnc|null",
            "application/vnd.frogans.ltf|application|vnd|frogans.ltf|null",
            "application/vnd.fujixerox.ddd|application|vnd|fujixerox.ddd|null",
            "application/vnd.fujixerox.docuworks|application|vnd|fujixerox.docuworks|null",
            "application/vnd.fujixerox.docuworks.binder|application|vnd|fujixerox.docuworks.binder|null",
            "application/vnd.fujitsu.oasys|application|vnd|fujitsu.oasys|null",
            "application/vnd.fujitsu.oasys2|application|vnd|fujitsu.oasys2|null",
            "application/vnd.fujitsu.oasys3|application|vnd|fujitsu.oasys3|null",
            "application/vnd.fujitsu.oasysgp|application|vnd|fujitsu.oasysgp|null",
            "application/vnd.fujitsu.oasysprs|application|vnd|fujitsu.oasysprs|null",
            "application/x-futuresplash|application|null|x-futuresplash|null",
            "application/vnd.fuzzysheet|application|vnd|fuzzysheet|null",
            "image/g3fax|image|null|g3fax|null",
            "application/vnd.gmx|application|vnd|gmx|null",
            "model/vnd.gtw|model|vnd|gtw|null",
            "application/vnd.genomatix.tuxedo|application|vnd|genomatix.tuxedo|null",
            "application/vnd.geogebra.file|application|vnd|geogebra.file|null",
            "application/vnd.geogebra.tool|application|vnd|geogebra.tool|null",
            "model/vnd.gdl|model|vnd|gdl|null",
            "application/vnd.geometry-explorer|application|vnd|geometry-explorer|null",
            "application/vnd.geonext|application|vnd|geonext|null",
            "application/vnd.geoplan|application|vnd|geoplan|null",
            "application/vnd.geospace|application|vnd|geospace|null",
            "application/x-font-ghostscript|application|null|x-font-ghostscript|null",
            "application/x-font-bdf|application|null|x-font-bdf|null",
            "application/x-gtar|application|null|x-gtar|null",
            "application/x-texinfo|application|null|x-texinfo|null",
            "application/x-gnumeric|application|null|x-gnumeric|null",
            "application/vnd.google-earth.kml+xml|application|vnd|google-earth.kml|xml",
            "application/vnd.google-earth.kmz|application|vnd|google-earth.kmz|null",
            "application/vnd.grafeq|application|vnd|grafeq|null",
            "image/gif|image|null|gif|null",
            "text/vnd.graphviz|text|vnd|graphviz|null",
            "application/vnd.groove-account|application|vnd|groove-account|null",
            "application/vnd.groove-help|application|vnd|groove-help|null",
            "application/vnd.groove-identity-message|application|vnd|groove-identity-message|null",
            "application/vnd.groove-injector|application|vnd|groove-injector|null",
            "application/vnd.groove-tool-message|application|vnd|groove-tool-message|null",
            "application/vnd.groove-tool-template|application|vnd|groove-tool-template|null",
            "application/vnd.groove-vcard|application|vnd|groove-vcard|null",
            "video/h261|video|null|h261|null",
            "video/h263|video|null|h263|null",
            "video/h264|video|null|h264|null",
            "application/vnd.hp-hpid|application|vnd|hp-hpid|null",
            "application/vnd.hp-hps|application|vnd|hp-hps|null",
            "application/x-hdf|application|null|x-hdf|null",
            "audio/vnd.rip|audio|vnd|rip|null",
            "application/vnd.hbci|application|vnd|hbci|null",
            "application/vnd.hp-jlyt|application|vnd|hp-jlyt|null",
            "application/vnd.hp-pcl|application|vnd|hp-pcl|null",
            "application/vnd.hp-hpgl|application|vnd|hp-hpgl|null",
            "application/vnd.yamaha.hv-script|application|vnd|yamaha.hv-script|null",
            "application/vnd.yamaha.hv-dic|application|vnd|yamaha.hv-dic|null",
            "application/vnd.yamaha.hv-voice|application|vnd|yamaha.hv-voice|null",
            "application/vnd.hydrostatix.sof-data|application|vnd|hydrostatix.sof-data|null",
            "application/hyperstudio|application|null|hyperstudio|null",
            "application/vnd.hal+xml|application|vnd|hal|xml",
            "text/html|text|null|html|null",
            "application/vnd.ibm.rights-management|application|vnd|ibm.rights-management|null",
            "application/vnd.ibm.secure-container|application|vnd|ibm.secure-container|null",
            "text/calendar|text|null|calendar|null",
            "application/vnd.iccprofile|application|vnd|iccprofile|null",
            "image/x-icon|image|null|x-icon|null",
            "application/vnd.igloader|application|vnd|igloader|null",
            "image/ief|image|null|ief|null",
            "application/vnd.immervision-ivp|application|vnd|immervision-ivp|null",
            "application/vnd.immervision-ivu|application|vnd|immervision-ivu|null",
            "application/reginfo+xml|application|null|reginfo|xml",
            "text/vnd.in3d.3dml|text|vnd|in3d.3dml|null",
            "text/vnd.in3d.spot|text|vnd|in3d.spot|null",
            "model/iges|model|null|iges|null",
            "application/vnd.intergeo|application|vnd|intergeo|null",
            "application/vnd.cinderella|application|vnd|cinderella|null",
            "application/vnd.intercon.formnet|application|vnd|intercon.formnet|null",
            "application/vnd.isac.fcs|application|vnd|isac.fcs|null",
            "application/ipfix|application|null|ipfix|null",
            "application/pkix-cert|application|null|pkix-cert|null",
            "application/pkixcmp|application|null|pkixcmp|null",
            "application/pkix-crl|application|null|pkix-crl|null",
            "application/pkix-pkipath|application|null|pkix-pkipath|null",
            "application/vnd.insors.igm|application|vnd|insors.igm|null",
            "application/vnd.ipunplugged.rcprofile|application|vnd|ipunplugged.rcprofile|null",
            "application/vnd.irepository.package+xml|application|vnd|irepository.package|xml",
            "text/vnd.sun.j2me.app-descriptor|text|vnd|sun.j2me.app-descriptor|null",
            "application/java-archive|application|null|java-archive|null",
            "application/java-vm|application|null|java-vm|null",
            "application/x-java-jnlp-file|application|null|x-java-jnlp-file|null",
            "application/java-serialized-object|application|null|java-serialized-object|null",
//            "text/x-java-source,java", // TODO: commas are not listed in RFC-6838 4.2, investigate how this mime type should handled.
            "application/javascript|application|null|javascript|null",
            "application/json|application|null|json|null",
            "application/vnd.joost.joda-archive|application|vnd|joost.joda-archive|null",
            "video/jpm|video|null|jpm|null",
            "image/jpeg|image|null|jpeg|null",
            "video/jpeg|video|null|jpeg|null",
            "application/vnd.kahootz|application|vnd|kahootz|null",
            "application/vnd.chipnuts.karaoke-mmd|application|vnd|chipnuts.karaoke-mmd|null",
            "application/vnd.kde.karbon|application|vnd|kde.karbon|null",
            "application/vnd.kde.kchart|application|vnd|kde.kchart|null",
            "application/vnd.kde.kformula|application|vnd|kde.kformula|null",
            "application/vnd.kde.kivio|application|vnd|kde.kivio|null",
            "application/vnd.kde.kontour|application|vnd|kde.kontour|null",
            "application/vnd.kde.kpresenter|application|vnd|kde.kpresenter|null",
            "application/vnd.kde.kspread|application|vnd|kde.kspread|null",
            "application/vnd.kde.kword|application|vnd|kde.kword|null",
            "application/vnd.kenameaapp|application|vnd|kenameaapp|null",
            "application/vnd.kidspiration|application|vnd|kidspiration|null",
            "application/vnd.kinar|application|vnd|kinar|null",
            "application/vnd.kodak-descriptor|application|vnd|kodak-descriptor|null",
            "application/vnd.las.las+xml|application|vnd|las.las|xml",
            "application/x-latex|application|null|x-latex|null",
            "application/vnd.llamagraphics.life-balance.desktop|application|vnd|llamagraphics.life-balance.desktop|null",
            "application/vnd.llamagraphics.life-balance.exchange+xml|application|vnd|llamagraphics.life-balance.exchange|xml",
            "application/vnd.jam|application|vnd|jam|null",
            "application/vnd.lotus-1-2-3|application|vnd|lotus-1-2-3|null",
            "application/vnd.lotus-approach|application|vnd|lotus-approach|null",
            "application/vnd.lotus-freelance|application|vnd|lotus-freelance|null",
            "application/vnd.lotus-notes|application|vnd|lotus-notes|null",
            "application/vnd.lotus-organizer|application|vnd|lotus-organizer|null",
            "application/vnd.lotus-screencam|application|vnd|lotus-screencam|null",
            "application/vnd.lotus-wordpro|application|vnd|lotus-wordpro|null",
            "audio/vnd.lucent.voice|audio|vnd|lucent.voice|null",
            "audio/x-mpegurl|audio|null|x-mpegurl|null",
            "video/x-m4v|video|null|x-m4v|null",
            "application/mac-binhex40|application|null|mac-binhex40|null",
            "application/vnd.macports.portpkg|application|vnd|macports.portpkg|null",
            "application/vnd.osgeo.mapguide.package|application|vnd|osgeo.mapguide.package|null",
            "application/marc|application|null|marc|null",
            "application/marcxml+xml|application|null|marcxml|xml",
            "application/mxf|application|null|mxf|null",
            "application/vnd.wolfram.player|application|vnd|wolfram.player|null",
            "application/mathematica|application|null|mathematica|null",
            "application/mathml+xml|application|null|mathml|xml",
            "application/mbox|application|null|mbox|null",
            "application/vnd.medcalcdata|application|vnd|medcalcdata|null",
            "application/mediaservercontrol+xml|application|null|mediaservercontrol|xml",
            "application/vnd.mediastation.cdkey|application|vnd|mediastation.cdkey|null",
            "application/vnd.mfer|application|vnd|mfer|null",
            "application/vnd.mfmp|application|vnd|mfmp|null",
            "model/mesh|model|null|mesh|null",
            "application/mads+xml|application|null|mads|xml",
            "application/mets+xml|application|null|mets|xml",
            "application/mods+xml|application|null|mods|xml",
            "application/metalink4+xml|application|null|metalink4|xml",
            "application/vnd.ms-powerpoint.template.macroenabled.12|application|vnd|ms-powerpoint.template.macroenabled.12|null",
            "application/vnd.ms-word.document.macroenabled.12|application|vnd|ms-word.document.macroenabled.12|null",
            "application/vnd.ms-word.template.macroenabled.12|application|vnd|ms-word.template.macroenabled.12|null",
            "application/vnd.mcd|application|vnd|mcd|null",
            "application/vnd.micrografx.flo|application|vnd|micrografx.flo|null",
            "application/vnd.micrografx.igx|application|vnd|micrografx.igx|null",
            "application/vnd.eszigno3+xml|application|vnd|eszigno3|xml",
            "application/x-msaccess|application|null|x-msaccess|null",
            "video/x-ms-asf|video|null|x-ms-asf|null",
            "application/x-msdownload|application|null|x-msdownload|null",
            "application/vnd.ms-artgalry|application|vnd|ms-artgalry|null",
            "application/vnd.ms-cab-compressed|application|vnd|ms-cab-compressed|null",
            "application/vnd.ms-ims|application|vnd|ms-ims|null",
            "application/x-ms-application|application|null|x-ms-application|null",
            "application/x-msclip|application|null|x-msclip|null",
            "image/vnd.ms-modi|image|vnd|ms-modi|null",
            "application/vnd.ms-fontobject|application|vnd|ms-fontobject|null",
            "application/vnd.ms-excel|application|vnd|ms-excel|null",
            "application/vnd.ms-excel.addin.macroenabled.12|application|vnd|ms-excel.addin.macroenabled.12|null",
            "application/vnd.ms-excel.sheet.binary.macroenabled.12|application|vnd|ms-excel.sheet.binary.macroenabled.12|null",
            "application/vnd.ms-excel.template.macroenabled.12|application|vnd|ms-excel.template.macroenabled.12|null",
            "application/vnd.ms-excel.sheet.macroenabled.12|application|vnd|ms-excel.sheet.macroenabled.12|null",
            "application/vnd.ms-htmlhelp|application|vnd|ms-htmlhelp|null",
            "application/x-mscardfile|application|null|x-mscardfile|null",
            "application/vnd.ms-lrm|application|vnd|ms-lrm|null",
            "application/x-msmediaview|application|null|x-msmediaview|null",
            "application/x-msmoney|application|null|x-msmoney|null",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation|application|vnd|openxmlformats-officedocument.presentationml.presentation|null",
            "application/vnd.openxmlformats-officedocument.presentationml.slide|application|vnd|openxmlformats-officedocument.presentationml.slide|null",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow|application|vnd|openxmlformats-officedocument.presentationml.slideshow|null",
            "application/vnd.openxmlformats-officedocument.presentationml.template|application|vnd|openxmlformats-officedocument.presentationml.template|null",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet|application|vnd|openxmlformats-officedocument.spreadsheetml.sheet|null",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template|application|vnd|openxmlformats-officedocument.spreadsheetml.template|null",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document|application|vnd|openxmlformats-officedocument.wordprocessingml.document|null",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template|application|vnd|openxmlformats-officedocument.wordprocessingml.template|null",
            "application/x-msbinder|application|null|x-msbinder|null",
            "application/vnd.ms-officetheme|application|vnd|ms-officetheme|null",
            "application/onenote|application|null|onenote|null",
            "audio/vnd.ms-playready.media.pya|audio|vnd|ms-playready.media.pya|null",
            "video/vnd.ms-playready.media.pyv|video|vnd|ms-playready.media.pyv|null",
            "application/vnd.ms-powerpoint|application|vnd|ms-powerpoint|null",
            "application/vnd.ms-powerpoint.addin.macroenabled.12|application|vnd|ms-powerpoint.addin.macroenabled.12|null",
            "application/vnd.ms-powerpoint.slide.macroenabled.12|application|vnd|ms-powerpoint.slide.macroenabled.12|null",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12|application|vnd|ms-powerpoint.presentation.macroenabled.12|null",
            "application/vnd.ms-powerpoint.slideshow.macroenabled.12|application|vnd|ms-powerpoint.slideshow.macroenabled.12|null",
            "application/vnd.ms-project|application|vnd|ms-project|null",
            "application/x-mspublisher|application|null|x-mspublisher|null",
            "application/x-msschedule|application|null|x-msschedule|null",
            "application/x-silverlight-app|application|null|x-silverlight-app|null",
            "application/vnd.ms-pki.stl|application|vnd|ms-pki.stl|null",
            "application/vnd.ms-pki.seccat|application|vnd|ms-pki.seccat|null",
            "application/vnd.visio|application|vnd|visio|null",
            "video/x-ms-wm|video|null|x-ms-wm|null",
            "audio/x-ms-wma|audio|null|x-ms-wma|null",
            "audio/x-ms-wax|audio|null|x-ms-wax|null",
            "video/x-ms-wmx|video|null|x-ms-wmx|null",
            "application/x-ms-wmd|application|null|x-ms-wmd|null",
            "application/vnd.ms-wpl|application|vnd|ms-wpl|null",
            "application/x-ms-wmz|application|null|x-ms-wmz|null",
            "video/x-ms-wmv|video|null|x-ms-wmv|null",
            "video/x-ms-wvx|video|null|x-ms-wvx|null",
            "application/x-msmetafile|application|null|x-msmetafile|null",
            "application/x-msterminal|application|null|x-msterminal|null",
            "application/msword|application|null|msword|null",
            "application/x-mswrite|application|null|x-mswrite|null",
            "application/vnd.ms-works|application|vnd|ms-works|null",
            "application/x-ms-xbap|application|null|x-ms-xbap|null",
            "application/vnd.ms-xpsdocument|application|vnd|ms-xpsdocument|null",
            "audio/midi|audio|null|midi|null",
            "application/vnd.ibm.minipay|application|vnd|ibm.minipay|null",
            "application/vnd.ibm.modcap|application|vnd|ibm.modcap|null",
            "application/vnd.jcp.javame.midlet-rms|application|vnd|jcp.javame.midlet-rms|null",
            "application/vnd.tmobile-livetv|application|vnd|tmobile-livetv|null",
            "application/x-mobipocket-ebook|application|null|x-mobipocket-ebook|null",
            "application/vnd.mobius.mbk|application|vnd|mobius.mbk|null",
            "application/vnd.mobius.dis|application|vnd|mobius.dis|null",
            "application/vnd.mobius.plc|application|vnd|mobius.plc|null",
            "application/vnd.mobius.mqy|application|vnd|mobius.mqy|null",
            "application/vnd.mobius.msl|application|vnd|mobius.msl|null",
            "application/vnd.mobius.txf|application|vnd|mobius.txf|null",
            "application/vnd.mobius.daf|application|vnd|mobius.daf|null",
            "text/vnd.fly|text|vnd|fly|null",
            "application/vnd.mophun.certificate|application|vnd|mophun.certificate|null",
            "application/vnd.mophun.application|application|vnd|mophun.application|null",
            "video/mj2|video|null|mj2|null",
            "audio/mpeg|audio|null|mpeg|null",
            "video/vnd.mpegurl|video|vnd|mpegurl|null",
            "video/mpeg|video|null|mpeg|null",
            "application/mp21|application|null|mp21|null",
            "audio/mp4|audio|null|mp4|null",
            "video/mp4|video|null|mp4|null",
            "application/mp4|application|null|mp4|null",
            "application/vnd.apple.mpegurl|application|vnd|apple.mpegurl|null",
            "application/vnd.musician|application|vnd|musician|null",
            "application/vnd.muvee.style|application|vnd|muvee.style|null",
            "application/xv+xml|application|null|xv|xml",
            "application/vnd.nokia.n-gage.data|application|vnd|nokia.n-gage.data|null",
            "application/vnd.nokia.n-gage.symbian.install|application|vnd|nokia.n-gage.symbian.install|null",
            "application/x-dtbncx+xml|application|null|x-dtbncx|xml",
            "application/x-netcdf|application|null|x-netcdf|null",
            "application/vnd.neurolanguage.nlu|application|vnd|neurolanguage.nlu|null",
            "application/vnd.dna|application|vnd|dna|null",
            "application/vnd.noblenet-directory|application|vnd|noblenet-directory|null",
            "application/vnd.noblenet-sealer|application|vnd|noblenet-sealer|null",
            "application/vnd.noblenet-web|application|vnd|noblenet-web|null",
            "application/vnd.nokia.radio-preset|application|vnd|nokia.radio-preset|null",
            "application/vnd.nokia.radio-presets|application|vnd|nokia.radio-presets|null",
            "text/n3|text|null|n3|null",
            "application/vnd.novadigm.edm|application|vnd|novadigm.edm|null",
            "application/vnd.novadigm.edx|application|vnd|novadigm.edx|null",
            "application/vnd.novadigm.ext|application|vnd|novadigm.ext|null",
            "application/vnd.flographit|application|vnd|flographit|null",
            "audio/vnd.nuera.ecelp4800|audio|vnd|nuera.ecelp4800|null",
            "audio/vnd.nuera.ecelp7470|audio|vnd|nuera.ecelp7470|null",
            "audio/vnd.nuera.ecelp9600|audio|vnd|nuera.ecelp9600|null",
            "application/oda|application|null|oda|null",
            "application/ogg|application|null|ogg|null",
            "audio/ogg|audio|null|ogg|null",
            "video/ogg|video|null|ogg|null",
            "application/vnd.oma.dd2+xml|application|vnd|oma.dd2|xml",
            "application/vnd.oasis.opendocument.text-web|application|vnd|oasis.opendocument.text-web|null",
            "application/oebps-package+xml|application|null|oebps-package|xml",
            "application/vnd.intu.qbo|application|vnd|intu.qbo|null",
            "application/vnd.openofficeorg.extension|application|vnd|openofficeorg.extension|null",
            "application/vnd.yamaha.openscoreformat|application|vnd|yamaha.openscoreformat|null",
            "audio/webm|audio|null|webm|null",
            "video/webm|video|null|webm|null",
            "application/vnd.oasis.opendocument.chart|application|vnd|oasis.opendocument.chart|null",
            "application/vnd.oasis.opendocument.chart-template|application|vnd|oasis.opendocument.chart-template|null",
            "application/vnd.oasis.opendocument.database|application|vnd|oasis.opendocument.database|null",
            "application/vnd.oasis.opendocument.formula|application|vnd|oasis.opendocument.formula|null",
            "application/vnd.oasis.opendocument.formula-template|application|vnd|oasis.opendocument.formula-template|null",
            "application/vnd.oasis.opendocument.graphics|application|vnd|oasis.opendocument.graphics|null",
            "application/vnd.oasis.opendocument.graphics-template|application|vnd|oasis.opendocument.graphics-template|null",
            "application/vnd.oasis.opendocument.image|application|vnd|oasis.opendocument.image|null",
            "application/vnd.oasis.opendocument.image-template|application|vnd|oasis.opendocument.image-template|null",
            "application/vnd.oasis.opendocument.presentation|application|vnd|oasis.opendocument.presentation|null",
            "application/vnd.oasis.opendocument.presentation-template|application|vnd|oasis.opendocument.presentation-template|null",
            "application/vnd.oasis.opendocument.spreadsheet|application|vnd|oasis.opendocument.spreadsheet|null",
            "application/vnd.oasis.opendocument.spreadsheet-template|application|vnd|oasis.opendocument.spreadsheet-template|null",
            "application/vnd.oasis.opendocument.text|application|vnd|oasis.opendocument.text|null",
            "application/vnd.oasis.opendocument.text-master|application|vnd|oasis.opendocument.text-master|null",
            "application/vnd.oasis.opendocument.text-template|application|vnd|oasis.opendocument.text-template|null",
            "image/ktx|image|null|ktx|null",
            "application/vnd.sun.xml.calc|application|vnd|sun.xml.calc|null",
            "application/vnd.sun.xml.calc.template|application|vnd|sun.xml.calc.template|null",
            "application/vnd.sun.xml.draw|application|vnd|sun.xml.draw|null",
            "application/vnd.sun.xml.draw.template|application|vnd|sun.xml.draw.template|null",
            "application/vnd.sun.xml.impress|application|vnd|sun.xml.impress|null",
            "application/vnd.sun.xml.impress.template|application|vnd|sun.xml.impress.template|null",
            "application/vnd.sun.xml.math|application|vnd|sun.xml.math|null",
            "application/vnd.sun.xml.writer|application|vnd|sun.xml.writer|null",
            "application/vnd.sun.xml.writer.global|application|vnd|sun.xml.writer.global|null",
            "application/vnd.sun.xml.writer.template|application|vnd|sun.xml.writer.template|null",
            "application/x-font-otf|application|null|x-font-otf|null",
            "application/vnd.yamaha.openscoreformat.osfpvg+xml|application|vnd|yamaha.openscoreformat.osfpvg|xml",
            "application/vnd.osgi.dp|application|vnd|osgi.dp|null",
            "application/vnd.palm|application|vnd|palm|null",
            "text/x-pascal|text|null|x-pascal|null",
            "application/vnd.pawaafile|application|vnd|pawaafile|null",
            "application/vnd.hp-pclxl|application|vnd|hp-pclxl|null",
            "application/vnd.picsel|application|vnd|picsel|null",
            "image/x-pcx|image|null|x-pcx|null",
            "image/vnd.adobe.photoshop|image|vnd|adobe.photoshop|null",
            "application/pics-rules|application|null|pics-rules|null",
            "image/x-pict|image|null|x-pict|null",
            "application/x-chat|application|null|x-chat|null",
            "application/pkcs10|application|null|pkcs10|null",
            "application/x-pkcs12|application|null|x-pkcs12|null",
            "application/pkcs7-mime|application|null|pkcs7-mime|null",
            "application/pkcs7-signature|application|null|pkcs7-signature|null",
            "application/x-pkcs7-certreqresp|application|null|x-pkcs7-certreqresp|null",
            "application/x-pkcs7-certificates|application|null|x-pkcs7-certificates|null",
            "application/pkcs8|application|null|pkcs8|null",
            "application/vnd.pocketlearn|application|vnd|pocketlearn|null",
            "image/x-portable-anymap|image|null|x-portable-anymap|null",
            "image/x-portable-bitmap|image|null|x-portable-bitmap|null",
            "application/x-font-pcf|application|null|x-font-pcf|null",
            "application/font-tdpfr|application|null|font-tdpfr|null",
            "application/x-chess-pgn|application|null|x-chess-pgn|null",
            "image/x-portable-graymap|image|null|x-portable-graymap|null",
            "image/png|image|null|png|null",
            "image/x-portable-pixmap|image|null|x-portable-pixmap|null",
            "application/pskc+xml|application|null|pskc|xml",
            "application/vnd.ctc-posml|application|vnd|ctc-posml|null",
            "application/postscript|application|null|postscript|null",
            "application/x-font-type1|application|null|x-font-type1|null",
            "application/vnd.powerbuilder6|application|vnd|powerbuilder6|null",
            "application/pgp-encrypted|application|null|pgp-encrypted|null",
            "application/pgp-signature|application|null|pgp-signature|null",
            "application/vnd.previewsystems.box|application|vnd|previewsystems.box|null",
            "application/vnd.pvi.ptid1|application|vnd|pvi.ptid1|null",
            "application/pls+xml|application|null|pls|xml",
            "application/vnd.pg.format|application|vnd|pg.format|null",
            "application/vnd.pg.osasli|application|vnd|pg.osasli|null",
            "text/prs.lines.tag|text|prs|lines.tag|null",
            "application/x-font-linux-psf|application|null|x-font-linux-psf|null",
            "application/vnd.publishare-delta-tree|application|vnd|publishare-delta-tree|null",
            "application/vnd.pmi.widget|application|vnd|pmi.widget|null",
            "application/vnd.quark.quarkxpress|application|vnd|quark.quarkxpress|null",
            "application/vnd.epson.esf|application|vnd|epson.esf|null",
            "application/vnd.epson.msf|application|vnd|epson.msf|null",
            "application/vnd.epson.ssf|application|vnd|epson.ssf|null",
            "application/vnd.epson.quickanime|application|vnd|epson.quickanime|null",
            "application/vnd.intu.qfx|application|vnd|intu.qfx|null",
            "video/quicktime|video|null|quicktime|null",
            "application/x-rar-compressed|application|null|x-rar-compressed|null",
            "audio/x-pn-realaudio|audio|null|x-pn-realaudio|null",
            "audio/x-pn-realaudio-plugin|audio|null|x-pn-realaudio-plugin|null",
            "application/rsd+xml|application|null|rsd|xml",
            "application/vnd.rn-realmedia|application|vnd|rn-realmedia|null",
            "application/vnd.realvnc.bed|application|vnd|realvnc.bed|null",
            "application/vnd.recordare.musicxml|application|vnd|recordare.musicxml|null",
            "application/vnd.recordare.musicxml+xml|application|vnd|recordare.musicxml|xml",
            "application/relax-ng-compact-syntax|application|null|relax-ng-compact-syntax|null",
            "application/vnd.data-vision.rdz|application|vnd|data-vision.rdz|null",
            "application/rdf+xml|application|null|rdf|xml",
            "application/vnd.cloanto.rp9|application|vnd|cloanto.rp9|null",
            "application/vnd.jisp|application|vnd|jisp|null",
            "application/rtf|application|null|rtf|null",
            "text/richtext|text|null|richtext|null",
            "application/vnd.route66.link66+xml|application|vnd|route66.link66|xml",
            "application/rss+xml|application|null|rss|xml",
            "application/shf+xml|application|null|shf|xml",
            "application/vnd.sailingtracker.track|application|vnd|sailingtracker.track|null",
            "image/svg+xml|image|null|svg|xml",
            "application/vnd.sus-calendar|application|vnd|sus-calendar|null",
            "application/sru+xml|application|null|sru|xml",
            "application/set-payment-initiation|application|null|set-payment-initiation|null",
            "application/set-registration-initiation|application|null|set-registration-initiation|null",
            "application/vnd.sema|application|vnd|sema|null",
            "application/vnd.semd|application|vnd|semd|null",
            "application/vnd.semf|application|vnd|semf|null",
            "application/vnd.seemail|application|vnd|seemail|null",
            "application/x-font-snf|application|null|x-font-snf|null",
            "application/scvp-vp-request|application|null|scvp-vp-request|null",
            "application/scvp-vp-response|application|null|scvp-vp-response|null",
            "application/scvp-cv-request|application|null|scvp-cv-request|null",
            "application/scvp-cv-response|application|null|scvp-cv-response|null",
            "application/sdp|application|null|sdp|null",
            "text/x-setext|text|null|x-setext|null",
            "video/x-sgi-movie|video|null|x-sgi-movie|null",
            "application/vnd.shana.informed.formdata|application|vnd|shana.informed.formdata|null",
            "application/vnd.shana.informed.formtemplate|application|vnd|shana.informed.formtemplate|null",
            "application/vnd.shana.informed.interchange|application|vnd|shana.informed.interchange|null",
            "application/vnd.shana.informed.package|application|vnd|shana.informed.package|null",
            "application/thraud+xml|application|null|thraud|xml",
            "application/x-shar|application|null|x-shar|null",
            "image/x-rgb|image|null|x-rgb|null",
            "application/vnd.epson.salt|application|vnd|epson.salt|null",
            "application/vnd.accpac.simply.aso|application|vnd|accpac.simply.aso|null",
            "application/vnd.accpac.simply.imp|application|vnd|accpac.simply.imp|null",
            "application/vnd.simtech-mindmapper|application|vnd|simtech-mindmapper|null",
            "application/vnd.commonspace|application|vnd|commonspace|null",
            "application/vnd.yamaha.smaf-audio|application|vnd|yamaha.smaf-audio|null",
            "application/vnd.smaf|application|vnd|smaf|null",
            "application/vnd.yamaha.smaf-phrase|application|vnd|yamaha.smaf-phrase|null",
            "application/vnd.smart.teacher|application|vnd|smart.teacher|null",
            "application/vnd.svd|application|vnd|svd|null",
            "application/sparql-query|application|null|sparql-query|null",
            "application/sparql-results+xml|application|null|sparql-results|xml",
            "application/srgs|application|null|srgs|null",
            "application/srgs+xml|application|null|srgs|xml",
            "application/ssml+xml|application|null|ssml|xml",
            "application/vnd.koan|application|vnd|koan|null",
            "text/sgml|text|null|sgml|null",
            "application/vnd.stardivision.calc|application|vnd|stardivision.calc|null",
            "application/vnd.stardivision.draw|application|vnd|stardivision.draw|null",
            "application/vnd.stardivision.impress|application|vnd|stardivision.impress|null",
            "application/vnd.stardivision.math|application|vnd|stardivision.math|null",
            "application/vnd.stardivision.writer|application|vnd|stardivision.writer|null",
            "application/vnd.stardivision.writer-global|application|vnd|stardivision.writer-global|null",
            "application/vnd.stepmania.stepchart|application|vnd|stepmania.stepchart|null",
            "application/x-stuffit|application|null|x-stuffit|null",
            "application/x-stuffitx|application|null|x-stuffitx|null",
            "application/vnd.solent.sdkm+xml|application|vnd|solent.sdkm|xml",
            "application/vnd.olpc-sugar|application|vnd|olpc-sugar|null",
            "audio/basic|audio|null|basic|null",
            "application/vnd.wqd|application|vnd|wqd|null",
            "application/vnd.symbian.install|application|vnd|symbian.install|null",
            "application/smil+xml|application|null|smil|xml",
            "application/vnd.syncml+xml|application|vnd|syncml|xml",
            "application/vnd.syncml.dm+wbxml|application|vnd|syncml.dm|wbxml",
            "application/vnd.syncml.dm+xml|application|vnd|syncml.dm|xml",
            "application/x-sv4cpio|application|null|x-sv4cpio|null",
            "application/x-sv4crc|application|null|x-sv4crc|null",
            "application/sbml+xml|application|null|sbml|xml",
            "text/tab-separated-values|text|null|tab-separated-values|null",
            "image/tiff|image|null|tiff|null",
            "application/vnd.tao.intent-module-archive|application|vnd|tao.intent-module-archive|null",
            "application/x-tar|application|null|x-tar|null",
            "application/x-tcl|application|null|x-tcl|null",
            "application/x-tex|application|null|x-tex|null",
            "application/x-tex-tfm|application|null|x-tex-tfm|null",
            "application/tei+xml|application|null|tei|xml",
            "text/plain|text|null|plain|null",
            "application/vnd.spotfire.dxp|application|vnd|spotfire.dxp|null",
            "application/vnd.spotfire.sfs|application|vnd|spotfire.sfs|null",
            "application/timestamped-data|application|null|timestamped-data|null",
            "application/vnd.trid.tpt|application|vnd|trid.tpt|null",
            "application/vnd.triscape.mxs|application|vnd|triscape.mxs|null",
            "text/troff|text|null|troff|null",
            "application/vnd.trueapp|application|vnd|trueapp|null",
            "application/x-font-ttf|application|null|x-font-ttf|null",
            "text/turtle|text|null|turtle|null",
            "application/vnd.umajin|application|vnd|umajin|null",
            "application/vnd.uoml+xml|application|vnd|uoml|xml",
            "application/vnd.unity|application|vnd|unity|null",
            "application/vnd.ufdl|application|vnd|ufdl|null",
            "text/uri-list|text|null|uri-list|null",
            "application/vnd.uiq.theme|application|vnd|uiq.theme|null",
            "application/x-ustar|application|null|x-ustar|null",
            "text/x-uuencode|text|null|x-uuencode|null",
            "text/x-vcalendar|text|null|x-vcalendar|null",
            "text/x-vcard|text|null|x-vcard|null",
            "application/x-cdlink|application|null|x-cdlink|null",
            "application/vnd.vsf|application|vnd|vsf|null",
            "model/vrml|model|null|vrml|null",
            "application/vnd.vcx|application|vnd|vcx|null",
            "model/vnd.mts|model|vnd|mts|null",
            "model/vnd.vtu|model|vnd|vtu|null",
            "application/vnd.visionary|application|vnd|visionary|null",
            "video/vnd.vivo|video|vnd|vivo|null",
//            "application/ccxml+xml,",  // TODO: commas are not listed in RFC-6838 4.2, investigate how this mime type should handled.
            "application/voicexml+xml|application|null|voicexml|xml",
            "application/x-wais-source|application|null|x-wais-source|null",
            "application/vnd.wap.wbxml|application|vnd|wap.wbxml|null",
            "image/vnd.wap.wbmp|image|vnd|wap.wbmp|null",
            "audio/x-wav|audio|null|x-wav|null",
            "application/davmount+xml|application|null|davmount|xml",
            "application/x-font-woff|application|null|x-font-woff|null",
            "application/wspolicy+xml|application|null|wspolicy|xml",
            "image/webp|image|null|webp|null",
            "application/vnd.webturbo|application|vnd|webturbo|null",
            "application/widget|application|null|widget|null",
            "application/winhlp|application|null|winhlp|null",
            "text/vnd.wap.wml|text|vnd|wap.wml|null",
            "text/vnd.wap.wmlscript|text|vnd|wap.wmlscript|null",
            "application/vnd.wap.wmlscriptc|application|vnd|wap.wmlscriptc|null",
            "application/vnd.wordperfect|application|vnd|wordperfect|null",
            "application/vnd.wt.stf|application|vnd|wt.stf|null",
            "application/wsdl+xml|application|null|wsdl|xml",
            "image/x-xbitmap|image|null|x-xbitmap|null",
            "image/x-xpixmap|image|null|x-xpixmap|null",
            "image/x-xwindowdump|image|null|x-xwindowdump|null",
            "application/x-x509-ca-cert|application|null|x-x509-ca-cert|null",
            "application/x-xfig|application|null|x-xfig|null",
            "application/xhtml+xml|application|null|xhtml|xml",
            "application/xml|application|null|xml|null",
            "application/xcap-diff+xml|application|null|xcap-diff|xml",
            "application/xenc+xml|application|null|xenc|xml",
            "application/patch-ops-error+xml|application|null|patch-ops-error|xml",
            "application/resource-lists+xml|application|null|resource-lists|xml",
            "application/rls-services+xml|application|null|rls-services|xml",
            "application/resource-lists-diff+xml|application|null|resource-lists-diff|xml",
            "application/xslt+xml|application|null|xslt|xml",
            "application/xop+xml|application|null|xop|xml",
            "application/x-xpinstall|application|null|x-xpinstall|null",
            "application/xspf+xml|application|null|xspf|xml",
            "application/vnd.mozilla.xul+xml|application|vnd|mozilla.xul|xml",
            "chemical/x-xyz|chemical|null|x-xyz|null",
            "text/yaml|text|null|yaml|null",
            "application/yang|application|null|yang|null",
            "application/yin+xml|application|null|yin|xml",
            "application/vnd.zul|application|vnd|zul|null",
            "application/zip|application|null|zip|null",
            "application/vnd.handheld-entertainment+xml|application|vnd|handheld-entertainment|xml",
            "application/vnd.zzazz.deck+xml|application|vnd|zzazz.deck|xml"
    }, splitBy = "\\|")
    public void test_sampleset_of_known_mimetypes_works (String mimetypeString, String toplLevelTypeString, String facetString, String subTypeName, String suffixString) throws ParserFailure {

        MimeType parseResult = MimeTypeParser.mimeTypeParser.parse(mimetypeString);

        Type  givenType  = Type.of(toplLevelTypeString);
        Facet givenFacet = Facet.forRegistrationTreeName(facetString).orElse(Facet.STANDARD);
        Optional<String> givenSuffix =  (suffixString == null || suffixString.isEmpty()) ?  Optional.empty() : Optional.of(suffixString);

        SubType givenSubType = SubType.of(givenFacet, subTypeName, givenSuffix);
        MimeType givenMimeType = MimeType.of(givenType, givenSubType, Collections.emptyMap());

        assertThat(parseResult).isNotNull();
        assertThat(parseResult).isEqualTo(givenMimeType);

    }
}
