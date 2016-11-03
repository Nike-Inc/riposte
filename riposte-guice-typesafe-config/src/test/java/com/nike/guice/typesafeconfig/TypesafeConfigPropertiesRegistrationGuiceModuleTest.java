package com.nike.guice.typesafeconfig;

import com.nike.internal.util.Pair;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.junit.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the functionality of {@link TypesafeConfigPropertiesRegistrationGuiceModule}.
 *
 * @author Nic Munroe
 */
public class TypesafeConfigPropertiesRegistrationGuiceModuleTest {

    @Test
    public void getPropertiesMap_returns_map_based_on_config_values() {
        // given
        Config config = ConfigFactory.load("testconfig");
        TypesafeConfigPropertiesRegistrationGuiceModule module = new TypesafeConfigPropertiesRegistrationGuiceModule(config);
        Map<String, String> expectedResult = Stream
            .of(Pair.of("foo", "bar"), Pair.of("someList", "[foo, bar]"))
            .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

        // when
        Map<String, String> result = module.getPropertiesMap();

        // then
        assertThat(result).containsAllEntriesOf(expectedResult);
    }

}