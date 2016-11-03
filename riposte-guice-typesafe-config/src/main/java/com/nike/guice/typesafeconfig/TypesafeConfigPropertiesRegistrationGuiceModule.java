package com.nike.guice.typesafeconfig;

import com.nike.guice.PropertiesRegistrationGuiceModule;
import com.nike.internal.util.Pair;

import com.typesafe.config.Config;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * An implementation of {@link PropertiesRegistrationGuiceModule} that gets its properties map from Typesafe Config.
 *
 * @author Nic Munroe
 */
@SuppressWarnings("WeakerAccess")
public class TypesafeConfigPropertiesRegistrationGuiceModule extends PropertiesRegistrationGuiceModule {

    private final Config config;

    public TypesafeConfigPropertiesRegistrationGuiceModule(Config config) {
        this.config = config;
    }

    @Override
    protected Map<String, String> getPropertiesMap() {
        return config.entrySet().stream()
                     .map((entry) -> Pair.of(entry.getKey(), entry.getValue().unwrapped().toString()))
                     .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

}
