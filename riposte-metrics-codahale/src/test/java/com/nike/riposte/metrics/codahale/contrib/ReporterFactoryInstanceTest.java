package com.nike.riposte.metrics.codahale.contrib;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class ReporterFactoryInstanceTest {

    @Before
    public void setUp() throws Exception {
    }

    @Test
    public void test() {
        MetricRegistry registry = new MetricRegistry();
        Reporter r = new DefaultConsoleReporterFactory().getReporter(registry);
        assertNotNull(r);
        r = new DefaultJMXReporterFactory().getReporter(registry);
        assertNotNull(r);
        r = new DefaultSLF4jReporterFactory().getReporter(registry);
        assertNotNull(r);
        r = new DefaultGraphiteReporterFactory("test", "fakeurl.com", 4242).getReporter(registry);
        assertNotNull(r);
        r = new RiposteGraphiteReporterFactory("test", "fakeurl.com", 4242).getReporter(registry);
    }

}
