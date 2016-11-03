package com.nike;

import com.nike.helloworld.HelloWorldEndpoint;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.logging.AccessLogger;

import java.util.Collection;
import java.util.Collections;

/**
 * Typically trivial sample to demonstrate the use of the Riposte core framework. After this application starts up
 * you can hit http://localhost:8080/ to receive a "Hello, world" response from {@link HelloWorldEndpoint}.
 */
public class Main {

    public static class AppServerConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = Collections.singleton(new HelloWorldEndpoint());
        private final AccessLogger accessLogger = new AccessLogger();

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public AccessLogger accessLogger() {
            return accessLogger;
        }
    }

    public static void main(String[] args) throws Exception {
        Server server = new Server(new AppServerConfig());
        server.startup();
    }
}
