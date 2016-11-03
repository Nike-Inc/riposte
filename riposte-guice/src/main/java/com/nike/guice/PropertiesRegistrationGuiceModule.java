package com.nike.guice;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;

import java.util.Map;

/**
 * A Guice Module that registers all properties returned by {@link #getPropertiesMap()} as injectable values. This makes
 * bridging properties specified in properties files (*.properties, *.conf, etc) into Guice fairly trivial. Just load
 * the properties files and return the values from {@link #getPropertiesMap()}. Once this module runs, any properties
 * returned by that map are eligible for injection. You can then inject the values of those properties into your objects
 * like so:
 * <pre>
 *      &#64;Inject
 *      &#64;Named("some.integer.property.key.from.props.files")
 *      private Integer someIntProp;
 *
 *      &#64;Inject
 *      &#64;Named("some.string.property.key.from.props.files")
 *      String someStringProp;
 * </pre>
 * You can also access them within your other Guice modules' @Provides methods, for example:
 * <pre>
 *      &#64;Provides
 *      public CustomWidget customWidget(@Named("interesting.property.from.props.file") String propFromPropsFile) {
 *          return new CustomWidget(propFromPropsFile);
 *      }
 * </pre>
 * <p/>
 * <h3>USING THIS IN TESTS</h3> Part of the benefit of this class is that you can use it in your unit tests to inject
 * properties in the same way the code works in production. Just create a custom {@code
 * PropertiesRegistrationGuiceModuleForTesting} module that extends your production class, but overrides any properties
 * you need for testing, and have Guice use the testing module instead of the production module when unit testing.
 *
 * @author Nic Munroe
 */
public abstract class PropertiesRegistrationGuiceModule extends AbstractModule {

    /**
     * @return The map of property key/value pairs you want associated with Guice so that they can be {@code @Inject}-ed
     * simply by putting the property key into an {@code @Named} annotation. This method is usually implemented by just
     * loading your application's properties files and returning the data as a map, but there's nothing stopping you
     * from using a different mechanism to load your properties if you want.
     */
    protected abstract Map<String, String> getPropertiesMap();

    @Override
    protected void configure() {
        /*
            Grab all the string properties and register them with Guice so they can be @Inject-ed
            without any further configuration.
         */
        Map<String, String> props = getPropertiesMap();
        Names.bindProperties(binder(), props);
    }

}
