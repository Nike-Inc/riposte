package com.nike.riposte.metrics.codahale.contrib;

import com.nike.riposte.server.config.ServerConfig;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author pevans
 */
public class RiposteGraphiteReporterFactory extends DefaultGraphiteReporterFactory {

    @SuppressWarnings("unused")
    public RiposteGraphiteReporterFactory(ServerConfig config, String graphiteURL, Integer graphitePort)
        throws ExecutionException, InterruptedException {

        this(
            config.appInfo().get().appId() + "." + config.appInfo().get().dataCenter() + "."
            + config.appInfo().get().environment() + "." + config.appInfo().get().instanceId(),
            graphiteURL,
            graphitePort
        );
    }

    public RiposteGraphiteReporterFactory(String prefix, String graphiteURL, Integer graphitePort) {
        super(prefix, graphiteURL, graphitePort);
    }

    @Override
    public Long getInterval() {
        return 10L;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.SECONDS;
    }


}
