package com.nike.riposte.metrics.codahale;

import com.codahale.metrics.MetricRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.Callable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author pevans
 */
public class CodahaleMetricsCollectorTest {

    CodahaleMetricsCollector mc;

    /**
     * @throws java.lang.Exception
     */
    @Before
    public void setUp() throws Exception {
        mc = new CodahaleMetricsCollector();
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#getMetricRegistry()}.
     */
    @Test
    public void testGetMetricRegistry() {
        MetricRegistry registry = mc.getMetricRegistry();
        assertNotNull(registry);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#timed(java.util.function.Function,
     * java.lang.Object, java.lang.String)}.
     */
    @Test
    public void testTimedFunctionOfTRTString() {
        final String testName = "timedFunctionTest";
        String ret = mc.timed((arg) -> {
            return new Long(Math.abs(arg)).toString();
        }, -42, testName);
        assertTrue("42".equals(ret));
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 1);
        ret = mc.timed(this::functionMethod, -42, testName);
        assertTrue("42".equals(ret));
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#timed(java.lang.Runnable,
     * java.lang.String)}.
     */
    @Test
    public void testTimedRunnableString() {
        final String testName = "timedRunnableTest";
        mc.timed(() -> {
            Math.abs(-42);
        }, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 1);
        mc.timed(new Runnable() {

            @Override
            public void run() {
                Math.abs(-42);

            }
        }, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#timed(java.util.concurrent.Callable,
     * java.lang.String)}.
     */
    @Test
    public void testTimedCallableOfVString() {
        final String testName = "timedCallableTest";
        try {
            String ret = mc.timed(new Callable<String>() {

                @Override
                public String call() throws Exception {
                    return "ran";
                }
            }, "timedCallableTest");
            assertTrue("ran".equals(ret));
            assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 1);
        }
        catch (Exception ex) {
            ex.printStackTrace(System.out);
            fail("Unexpected Exception");
        }
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#timed(java.util.function.Consumer,
     * java.lang.Object, java.lang.String)}.
     */
    @Test
    public void testTimedConsumerOfTTString() {
        final String testName = "timedConsumerTest";
        mc.timed((arg) -> {
            Math.abs(arg);
        }, -42, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 1);
        mc.timed(this::consumerMethod, -42, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 2);

    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#timed(java.util.function.BiConsumer,
     * java.lang.Object, java.lang.Object, java.lang.String)}.
     */
    @Test
    public void testTimedBiConsumerOfTUTUString() {
        final String testName = "timedBiConsumerTest";
        mc.timed((arg1, arg2) -> {
            Math.abs(arg1 * arg2);
        }, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 1);
        mc.timed(this::biConsumerMethod, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#timed(java.util.function.BiFunction,
     * java.lang.Object, java.lang.Object, java.lang.String)}.
     */
    @Test
    public void testTimedBiFunctionOfTURTUString() {
        final String testName = "timedBiFunctionTest";
        mc.timed((arg1, arg2) -> {
            return new Double(Math.abs(arg1 * arg2)).toString();
        }, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().timer(testName).getCount() == 1);
        mc.timed(this::biFunctionMethod, -42, 3.14159, testName);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#metered(java.lang.Runnable,
     * java.lang.String, long)}.
     */
    @Test
    public void testMeteredRunnableStringLong() {
        final String testName = "meteredRunnableTest";
        mc.metered(() -> {
            Math.abs(-42);
        }, testName);
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 1);
        mc.metered(new Runnable() {

            @Override
            public void run() {
                Math.abs(-42);
            }
        }, testName);
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#metered(java.util.concurrent.Callable,
     * java.lang.String, long)}.
     */
    @Test
    public void testMeteredCallableOfVStringLong() {
        final String testName = "meteredCallableTest";
        try {
            String ret = mc.metered(new Callable<String>() {

                @Override
                public String call() throws Exception {
                    return new Long(Math.abs(-42)).toString();
                }

            }, testName);
            assertTrue("42".equals(ret));
            assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 1);
        }
        catch (Exception ex) {
            ex.printStackTrace(System.out);
            fail("Unexpected Exception");
        }
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#metered(java.util.function.Function,
     * java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testMeteredFunctionOfTRTStringLong() {
        final String testName = "meteredFunctionTest";
        String ret = mc.metered((arg) -> {
            return new Long(Math.abs(arg)).toString();
        }, -42, testName);
        assertTrue("42".equals(ret));
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 1);
        ret = mc.metered(this::functionMethod, -42, testName);
        assertTrue("42".equals(ret));
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#metered(java.util.function.Consumer,
     * java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testMeteredConsumerOfTTStringLong() {
        final String testName = "meteredConsumerTest";
        mc.metered((arg) -> {
            new Long(Math.abs(arg)).toString();
        }, -42, testName);
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 1);
        mc.metered(this::consumerMethod, -42, testName);
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#metered(java.util.function.BiConsumer,
     * java.lang.Object, java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testMeteredBiConsumerOfTUTUStringLong() {
        final String testName = "meteredBiConsumerTest";
        mc.metered((arg1, arg2) -> {
            new Double(Math.abs(arg1 * arg2)).toString();
        }, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 1);
        mc.metered(this::biConsumerMethod, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#metered(java.util.function.BiFunction,
     * java.lang.Object, java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testMeteredBiFunctionOfTURTUStringLong() {
        final String testName = "meteredBiFunctionTest";
        String ret = mc.metered((arg1, arg2) -> {
            return new Double(Math.abs(arg1 * arg2)).toString();
        }, -42, 3.14159, testName);
        assertNotNull(ret);
        assertFalse("".equals(ret));
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 1);
        ret = mc.metered(this::biFunctionMethod, -42, 3.14159, testName);
        assertNotNull(ret);
        assertFalse("".equals(ret));
        assertTrue(mc.getMetricRegistry().meter(testName).getCount() == 2);
    }


    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#counted(java.lang.Runnable,
     * java.lang.String, long)}.
     */
    @Test
    public void testCountedRunnableStringLong() {
        final String testName = "countedRunnableTest";
        mc.counted(() -> {
            Math.abs(-42);
        }, testName);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 1);
        mc.counted(new Runnable() {

            @Override
            public void run() {
                Math.abs(-42);

            }
        }, testName);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 2);
        long value = mc.getMetricRegistry().counter(testName).getCount();
        mc.counted(() -> {
            Math.abs(-42);
        }, testName, -1);
        assertTrue(value - 1 == mc.getMetricRegistry().counter(testName).getCount());
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#counted(java.util.concurrent.Callable,
     * java.lang.String, long)}.
     */
    @Test
    public void testCountedCallableOfVStringLong() {
        final String testName = "countedCallableTest";
        try {
            String ret = mc.counted(new Callable<String>() {

                @Override
                public String call() throws Exception {
                    return new Long(Math.abs(-42)).toString();
                }
            }, testName);
            assertTrue("42".equals(ret));
            assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 1);
        }
        catch (Exception ex) {
            ex.printStackTrace(System.out);
            fail("Unexpected Exception");
        }
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#counted(java.util.function.Function,
     * java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testCountedFunctionOfTRTStringLong() {
        final String testName = "countedFunctionTest";
        String ret = mc.counted((arg) -> {
            return new Long(Math.abs(arg)).toString();
        }, -42, testName);
        assertTrue("42".equals(ret));
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 1);
        ret = mc.counted(this::functionMethod, -42, testName);
        assertTrue("42".equals(ret));
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#counted(java.util.function.Consumer,
     * java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testCountedConsumerOfTTStringLong() {
        final String testName = "countedConsumerTest";
        mc.counted((arg) -> {
            new Long(Math.abs(arg)).toString();
        }, -42, testName);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 1);
        mc.counted(this::consumerMethod, -42, testName);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#counted(java.util.function.BiConsumer,
     * java.lang.Object, java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testCountedBiConsumerOfTUTUStringLong() {
        final String testName = "countedBiConsumerTest";
        mc.counted((arg1, arg2) -> {
            new Double(Math.abs(arg1 * arg2)).toString();
        }, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 1);
        mc.counted(this::biConsumerMethod, -42, 3.14159, testName);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 2);
    }

    /**
     * Test method for {@link com.nike.riposte.metrics.codahale.CodahaleMetricsCollector#counted(java.util.function.BiFunction,
     * java.lang.Object, java.lang.Object, java.lang.String, long)}.
     */
    @Test
    public void testCountedBiFunctionOfTURTUStringLong() {
        final String testName = "countedBiFunctionTest";
        String ret = mc.counted((arg1, arg2) -> {
            return new Double(Math.abs(arg1 * arg2)).toString();
        }, -42, 3.14159, testName);
        assertNotNull(ret);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 1);
        ret = mc.counted(this::biFunctionMethod, -42, 3.14159, testName);
        assertNotNull(ret);
        assertTrue(mc.getMetricRegistry().counter(testName).getCount() == 2);
    }

    private String functionMethod(Integer arg) {
        return new Long(Math.abs(arg)).toString();
    }

    private void consumerMethod(Integer arg) {
        Math.abs(arg);
    }

    private void biConsumerMethod(Integer arg1, Double arg2) {
        Math.abs(arg1 * arg2);
    }

    private String biFunctionMethod(Integer arg1, Double arg2) {
        return new Double(Math.abs(arg1 * arg2)).toString();
    }

}
