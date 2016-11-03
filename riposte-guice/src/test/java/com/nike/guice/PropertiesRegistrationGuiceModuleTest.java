package com.nike.guice;

import com.nike.internal.util.MapBuilder;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the functionality of {@link PropertiesRegistrationGuiceModule}.
 */
public class PropertiesRegistrationGuiceModuleTest {

    @Inject
    @Named("stringkey")
    private String injectedString;

    @Inject
    @Named("intkey")
    private int injectedInt;

    @Test
    public void verify_registration_works() {
        String stringKey = "stringkey";
        String stringVal = UUID.randomUUID().toString();
        String intKey = "intkey";
        int intVal = 42;

        Injector injector = Guice.createInjector(new PropertiesRegistrationGuiceModule() {
            @Override
            protected Map<String, String> getPropertiesMap() {
                return MapBuilder.builder(stringKey, stringVal)
                                 .put(intKey, String.valueOf(intVal))
                                 .build();
            }
        });

        injector.injectMembers(this);

        assertThat(injectedString).isEqualTo(stringVal);
        assertThat(injectedInt).isEqualTo(intVal);
    }

}