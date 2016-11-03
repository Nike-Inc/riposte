package com.nike.riposte.typesafeconfig.util;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigMergeable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains static helper methods for working with Typesafe Config.
 *
 * @author Nic Munroe
 */
public class TypesafeConfigUtil {

    private static final Logger logger = LoggerFactory.getLogger(TypesafeConfigUtil.class);

    // Intentionally private - use the static methods
    private TypesafeConfigUtil() { /* Do nothing*/ }

    /**
     * Creates a {@link Config} from loading two files based on the given app ID and environment arguments. The base
     * file that will be loaded must be named {@code [appId].[conf/json/properties]}, e.g. if the appId was {@code foo}
     * then the base file loaded must be named foo.conf, foo.json, or foo.properties as per Typesafe Config's
     * specifications. Then the environment file is loaded, and it must be named {@code
     * [appId]-[environment].[conf/json/properties]}. Continuing the example, if appId was {@code foo} and environment
     * was {@code bar}, then the environment loaded must be named foo-bar.conf, foo-bar.json, or foo-bar.properties. The
     * returned {@link Config} object is just the environment file's {@link Config} setup with the base file's {@link
     * Config} used as a fallback via {@link Config#withFallback(ConfigMergeable)}. In other words, the environment file
     * and base file's properties are merged, with the environment file's properties winning any conflicts where a
     * property exists in both files.
     * <p/>
     * NOTE: {@link ConfigFactory#load(String)} is used to load the files, so System properties will override anything
     * specified in either the base or environment file. See that method for more detailed information about the file
     * loading process.
     */
    public static Config loadConfigForAppIdAndEnvironment(String appId, String environment) {
        logger.info("Loading properties file: {}.[conf/json/properties]", appId);
        Config baseConfig = ConfigFactory.load(appId);
        String environmentFilenameNoExtension = appId + "-" + environment;
        logger.info("Loading properties file: {}.[conf/json/properties]", environmentFilenameNoExtension);
        Config environmentConfig = ConfigFactory.load(environmentFilenameNoExtension);
        return environmentConfig.withFallback(baseConfig);
    }

}
