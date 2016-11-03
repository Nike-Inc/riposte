package com.nike.riposte.typesafeconfig.util;

import com.typesafe.config.Config;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the functionality of {@link TypesafeConfigUtil}.
 *
 * @author Nic Munroe
 */
public class TypesafeConfigUtilTest {

    @Test
    public void exercise_private_constructor_for_code_coverage() throws NoSuchMethodException, IllegalAccessException,
                                                                        InvocationTargetException, InstantiationException {
        // given
        Constructor<TypesafeConfigUtil> constructor = TypesafeConfigUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        // when
        TypesafeConfigUtil instance = constructor.newInstance();

        // expect
        assertThat(instance).isNotNull();
    }

    @Test
    public void loadConfigForAppIdAndEnvironment_works_as_expected() {
        // when
        Config config = TypesafeConfigUtil.loadConfigForAppIdAndEnvironment("typesafeconfigserver", "compiletimetest");

        // then
        assertThat(config.getString("typesafeConfigServer.foo")).isEqualTo("overridevalue");
    }

}