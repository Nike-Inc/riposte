package com.nike.riposte.server.handler.base;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.channelpipeline.ChannelAttributes;
import com.nike.riposte.server.handler.base.BaseInboundHandlerWithTracingAndMdcSupport.HandlerMethodToExecute;
import com.nike.riposte.server.http.HttpProcessingState;
import com.nike.riposte.testutils.Whitebox;
import com.nike.wingtips.Span;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.Tracer.SpanFieldForLoggerMdc;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

import uk.org.lidalia.slf4jtest.LoggingEvent;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;

import static com.nike.wingtips.Span.SpanPurpose.LOCAL_ONLY;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the functionality of {@link BaseInboundHandlerWithTracingAndMdcSupport}
 */
public class BaseInboundHandlerWithTracingAndMdcSupportTest {

    private BaseInboundHandlerWithTracingAndMdcSupport handler;
    @SuppressWarnings("FieldCanBeLocal")
    private Channel channelMock;
    private ChannelHandlerContext ctxMock;
    private Attribute<HttpProcessingState> stateAttributeMock;
    private HttpProcessingState state;

    private void resetTracingAndMdc() {
        MDC.clear();
        Tracer.getInstance().completeRequestSpan();
        Tracer.getInstance().removeAllSpanLifecycleListeners();
        Tracer.getInstance().setSpanFieldsForLoggerMdc(SpanFieldForLoggerMdc.TRACE_ID);
    }

    @Before
    public void beforeMethod() {
        handler = new BaseInboundHandlerWithTracingAndMdcSupport() { };
        channelMock = mock(Channel.class);
        ctxMock = mock(ChannelHandlerContext.class);
        //noinspection unchecked
        stateAttributeMock = mock(Attribute.class);
        state = new HttpProcessingState();
        doReturn(channelMock).when(ctxMock).channel();
        doReturn(stateAttributeMock).when(channelMock).attr(ChannelAttributes.HTTP_PROCESSING_STATE_ATTRIBUTE_KEY);
        doReturn(state).when(stateAttributeMock).get();
        resetTracingAndMdc();
    }

    @After
    public void afterMethod() {
        resetTracingAndMdc();
    }

    @Test
    public void linkTracingAndMdcToCurrentThread_should_clear_tracing_and_mdc_if_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();
        MDC.put("foo", "bar");
        Tracer.getInstance().startRequestWithRootSpan("blahtrace");
        assertThat(MDC.getCopyOfContextMap().isEmpty(), is(false));
        assertThat(Tracer.getInstance().getCurrentSpan(), notNullValue());

        // when
        handler.linkTracingAndMdcToCurrentThread(ctxMock);

        // then
        assertThat(MDC.getCopyOfContextMap().isEmpty(), is(true));
        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());
    }

    @Test
    public void linkTracingAndMdcToCurrentThread_should_set_tracing_and_mdc_to_state_values_if_available() {
        // given
        Map<String, String> stateMdcInfo = new HashMap<>();
        stateMdcInfo.put("foo", "bar");
        Deque<Span> stateTraceStack = new LinkedList<>();
        Span span = Span.generateRootSpanForNewTrace("fooSpanName", LOCAL_ONLY).withTraceId("fooTraceId").build();
        stateTraceStack.add(span);
        state.setLoggerMdcContextMap(stateMdcInfo);
        state.setDistributedTraceStack(stateTraceStack);

        assertThat(MDC.getCopyOfContextMap().isEmpty(), is(true));
        assertThat(Tracer.getInstance().getCurrentSpan(), nullValue());

        // when
        handler.linkTracingAndMdcToCurrentThread(ctxMock);

        // then
        // Tracer adds some stuff to the MDC
        stateMdcInfo.put(SpanFieldForLoggerMdc.TRACE_ID.mdcKey, span.getTraceId());
        assertThat(MDC.getCopyOfContextMap(), is(stateMdcInfo));
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy(), is(stateTraceStack));
    }

    @Test
    public void linkTracingAndMdcToCurrentThread_should_clear_mdc_if_state_is_available_but_state_mdc_info_is_null() {
        // given
        MDC.put("foo", "bar");
        Tracer.getInstance().startRequestWithRootSpan("blahtrace");
        assertThat(MDC.getCopyOfContextMap().isEmpty(), is(false));
        assertThat(Tracer.getInstance().getCurrentSpan(), notNullValue());

        // when
        handler.linkTracingAndMdcToCurrentThread(ctxMock);

        // then
        assertThat(MDC.getCopyOfContextMap().isEmpty(), is(true));
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy(), nullValue());
    }

    @Test
    public void unlinkTracingAndMdcFromCurrentThread_should_reset_tracing_and_mdc_to_originalThreadInfo_if_state_is_null() {
        // given
        doReturn(null).when(stateAttributeMock).get();
        MDC.put("foo", "bar");
        Tracer.getInstance().startRequestWithRootSpan("blahtrace");
        assertThat(MDC.getCopyOfContextMap().isEmpty(), is(false));
        assertThat(Tracer.getInstance().getCurrentSpan(), notNullValue());

        Deque<Span> origTraceStack = new LinkedList<>();
        Span origSpan = Span.newBuilder(UUID.randomUUID().toString(), LOCAL_ONLY).withTraceId(UUID.randomUUID().toString()).build();
        origTraceStack.add(origSpan);
        Map<String, String> origMdcInfo = new HashMap<>();
        origMdcInfo.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        origMdcInfo.put(SpanFieldForLoggerMdc.TRACE_ID.mdcKey, origSpan.getTraceId());
        Pair<Deque<Span>, Map<String, String>> origThreadInfo = Pair.of(origTraceStack, origMdcInfo);

        // when
        handler.unlinkTracingAndMdcFromCurrentThread(ctxMock, origThreadInfo);

        // then
        assertThat(MDC.getCopyOfContextMap(), is(origMdcInfo));
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy(), is(origTraceStack));
    }

    @Test
    public void unlinkTracingAndMdcFromCurrentThread_should_populate_state_and_reset_tracing_and_mdc_to_originalThreadInfo_if_state_is_not_null() {
        // given
        // Orig thread info
        Deque<Span> origTraceStack = new LinkedList<>();
        Span origSpan = Span.newBuilder(UUID.randomUUID().toString(), LOCAL_ONLY).withTraceId(UUID.randomUUID().toString()).build();
        origTraceStack.add(origSpan);
        Map<String, String> origMdcInfo = new HashMap<>();
        origMdcInfo.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        origMdcInfo.put(SpanFieldForLoggerMdc.TRACE_ID.mdcKey, origSpan.getTraceId());
        Pair<Deque<Span>, Map<String, String>> origThreadInfo = Pair.of(origTraceStack, origMdcInfo);

        // "Current" state
        MDC.put("foo", "bar");
        Tracer.getInstance().startRequestWithRootSpan(UUID.randomUUID().toString());
        Deque<Span> currentTraceStackBeforeUnlinkCall = Tracer.getInstance().getCurrentSpanStackCopy();
        Map<String, String> currentMdcInfoBeforeUnlinkCall = MDC.getCopyOfContextMap();

        // Verify we've set up our "given" section correctly
        assertThat(MDC.getCopyOfContextMap(), is(currentMdcInfoBeforeUnlinkCall));
        assertThat(MDC.get("foo"), is("bar"));
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy(), is(currentTraceStackBeforeUnlinkCall));
        assertThat(state.getLoggerMdcContextMap(), nullValue());
        assertThat(state.getDistributedTraceStack(), nullValue());
        assertThat(origMdcInfo, not(currentMdcInfoBeforeUnlinkCall));
        assertThat(origTraceStack, not(currentTraceStackBeforeUnlinkCall));

        // when
        handler.unlinkTracingAndMdcFromCurrentThread(ctxMock, origThreadInfo);

        // then
        // The state should have the expected values from the time when the unlink method was called
        assertThat(state.getLoggerMdcContextMap(), is(currentMdcInfoBeforeUnlinkCall));
        assertThat(state.getDistributedTraceStack(), is(currentTraceStackBeforeUnlinkCall));

        // The "current" thread state after the unlink call should match the original thread info
        assertThat(MDC.getCopyOfContextMap(), is(origMdcInfo));
        assertThat(Tracer.getInstance().getCurrentSpanStackCopy(), is(origTraceStack));
    }

    @Test
    public void channelRegistered_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelRegistered", ctxMock);
    }

    @Test
    public void channelUnregistered_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelUnregistered", ctxMock);
    }

    @Test
    public void channelActive_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelActive", ctxMock);
    }

    @Test
    public void channelInactive_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelInactive", ctxMock);
    }

    @Test
    public void channelRead_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelRead", ctxMock, null);
    }

    @Test
    public void channelReadComplete_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelReadComplete", ctxMock);
    }

    @Test
    public void userEventTriggered_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("userEventTriggered", ctxMock, null);
    }

    @Test
    public void channelWritabilityChanged_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("channelWritabilityChanged", ctxMock);
    }

    @Test
    public void exceptionCaught_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("exceptionCaught", ctxMock, null);
    }

    @Test
    public void handlerAdded_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("handlerAdded", ctxMock);
    }

    @Test
    public void handlerRemoved_should_perform_as_expected() throws Exception {
        verifyMethodBehavior("handlerRemoved", ctxMock);
    }

    @Test
    public void argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo_returns_true_by_default() {
        assertThat(handler.argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(null, null, null, null), is(true));
    }

    @Test(expected = IllegalStateException.class)
    public void isDefaultMethodImpl_throws_IllegalStateException_if_method_cannot_be_found_in_map() {
        BaseInboundHandlerWithTracingAndMdcSupport.isDefaultMethodImpl(UUID.randomUUID().toString(), Collections.emptyMap());
    }

    private void verifyMethodBehavior(String methodName, Object... methodArgs) throws InvocationTargetException, IllegalAccessException {
        String doMethodName = "do" + methodName.substring(0, 1).toUpperCase() + methodName.substring(1);
        Method doMethod = findMethodWithName(BaseInboundHandlerWithTracingAndMdcSupport.class, doMethodName);
        PipelineContinuationBehavior doMethodDeafultReturnVal = (PipelineContinuationBehavior)doMethod.invoke(handler, methodArgs);
        assertThat(doMethodDeafultReturnVal, is(PipelineContinuationBehavior.CONTINUE));

        verifyMethodBehaviorDetails(methodName, methodArgs, null, false);
        verifyMethodBehaviorDetails(methodName, methodArgs, null, true);

        verifyMethodBehaviorDetails(methodName, methodArgs, PipelineContinuationBehavior.CONTINUE, false);
        verifyMethodBehaviorDetails(methodName, methodArgs, PipelineContinuationBehavior.CONTINUE, true);

        verifyMethodBehaviorDetails(methodName, methodArgs, PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT, false);
        verifyMethodBehaviorDetails(methodName, methodArgs, PipelineContinuationBehavior.DO_NOT_FIRE_CONTINUE_EVENT, true);
    }

    private HandlerMethodToExecute constructHandlerMethodToExecuteFromMethodName(String methodName) {
        StringBuilder sb = new StringBuilder();
        sb.append("DO_");
        for (char c : methodName.toCharArray()) {
            if (Character.isUpperCase(c))
                sb.append("_");
            sb.append(Character.toUpperCase(c));
        }

        return HandlerMethodToExecute.valueOf(sb.toString());
    }

    private void verifyMethodBehaviorDetails(
        String methodName, Object[] methodArgs, PipelineContinuationBehavior doMethodReturnValue, boolean shouldExplodeInDoMethod
    ) throws InvocationTargetException, IllegalAccessException {
        // Verify all the various sub-combinations that could affect behavior in this case.

        // Check with debug logging on or off
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, true,  false, false, true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, true,  false, false, false, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, false, shouldExplodeInDoMethod);

        // Check with force-dtrace on or off
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, true,  false, true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, true,  false, false, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, false, shouldExplodeInDoMethod);

        // Check with isDefaultMethodImpl on or off
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, true,  true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, true,  false, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, false, shouldExplodeInDoMethod);

        // Check with argsEligibleForLinkUnlink on or off
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, true, shouldExplodeInDoMethod);
        verifyMethodBehaviorDetails(methodName, methodArgs, doMethodReturnValue, false, false, false, false, shouldExplodeInDoMethod);
    }

    private void verifyMethodBehaviorDetails(
        String methodName, Object[] methodArgs, PipelineContinuationBehavior doMethodReturnValue,
        boolean shouldPerformDebugLogging, boolean forceEnableDTraceOnAllMethods, boolean isDefaultMethodImpl,
        boolean argsEligibleForLinkUnlink, boolean shouldExplodeInDoMethod
    ) throws InvocationTargetException, IllegalAccessException {
        // Setup all the things!
        BaseInboundHandlerWithTracingAndMdcSupport handlerSpy = spy(handler);

        Method mainMethod = findMethodWithName(BaseInboundHandlerWithTracingAndMdcSupport.class, methodName);
        String methodNameWithFirstCharCapitalized = methodName.substring(0, 1).toUpperCase() + methodName.substring(1);
        String doMethodName = "do" + methodNameWithFirstCharCapitalized;
        Method doMethod = findMethodWithName(BaseInboundHandlerWithTracingAndMdcSupport.class, doMethodName);
        String fireEventMethodName = "fire" + methodName.substring(0, 1).toUpperCase() + methodName.substring(1);
        Method fireEventMethod = ("handlerAdded".equals(methodName) || "handlerRemoved".equals(methodName))
                                 ? null
                                 : findMethodWithName(ChannelHandlerContext.class, fireEventMethodName);

        Whitebox.setInternalState(handlerSpy, "debugHandlerMethodCalls", shouldPerformDebugLogging);
        Whitebox.setInternalState(handlerSpy, "forceEnableDTraceOnAllMethods", forceEnableDTraceOnAllMethods);
        Whitebox.setInternalState(handlerSpy, "isDefaultDo" + methodNameWithFirstCharCapitalized + "Impl", isDefaultMethodImpl);

        HandlerMethodToExecute expectedHandlerMethodToExecute = constructHandlerMethodToExecuteFromMethodName(methodName);
        doReturn(argsEligibleForLinkUnlink).when(handlerSpy).argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
            eq(expectedHandlerMethodToExecute), eq(ctxMock), any(), any()
        );

        TestLogger handlerLogger = TestLoggerFactory.getTestLogger(((Logger) Whitebox.getInternalState(handlerSpy, "logger")).getName());
        handlerLogger.clear();

        ObjectHolder<Boolean> calledLinkMethod = new ObjectHolder<>(false);
        ObjectHolder<Boolean> calledDoMethod = new ObjectHolder<>(false);
        ObjectHolder<Boolean> calledUnlinkMethod = new ObjectHolder<>(false);
        ObjectHolder<Boolean> calledSuperMethod = new ObjectHolder<>(false);
        @SuppressWarnings("unchecked")
        Pair<Deque<Span>, Map<String, String>> linkTracingAndMdcReturnVal = mock(Pair.class);

        boolean shouldCallLinkAndUnlinkMethods = (!isDefaultMethodImpl && argsEligibleForLinkUnlink)
                                                       || forceEnableDTraceOnAllMethods
                                                       || shouldPerformDebugLogging;

        // Configure the linkTracingAndMdcToCurrentThread method to:
        //      1. Indicate that the link method was called (for future assertions).
        //      2. Assert that the do* method has not yet been called.
        //      3. Assert that the unlink method has not yet been called.
        //      4. Assert that the super method has not yet been called.
        //      5. Return linkTracingAndMdcReturnVal (for future assertions).
        doAnswer(invocation -> {
            calledLinkMethod.heldObject = true;
            assertThat(calledDoMethod.heldObject, is(false));
            assertThat(calledUnlinkMethod.heldObject, is(false));
            assertThat(calledSuperMethod.heldObject, is(false));
            return linkTracingAndMdcReturnVal;
        }).when(handlerSpy).linkTracingAndMdcToCurrentThread(ctxMock);

        // Configure the do* method depending on whether it's supposed to explode with a IntentionalDoMethodException or not.
        if (shouldExplodeInDoMethod) {
            // The do* method is supposed to explode. Make it so.
            when(doMethod.invoke(handlerSpy, methodArgs)).thenThrow(new IntentionalDoMethodException("intentional exception"));
        }
        else {
            // The do* method is not supposed to explode. Configure it to:
            //      1. Indicate that the do* method was called (for future assertions).
            //      2. Assert that the link method was called previously if and only if shouldCallLinkAndUnlinkMethods is true.
            //      3. Assert that the unlink method has not yet been called.
            //      4. Assert that the super method has not yet been called.
            //      5. Return doMethodReturnValue (for future assertions).
            when(doMethod.invoke(handlerSpy, methodArgs)).thenAnswer(invocation -> {
                calledDoMethod.heldObject = true;
                assertThat(calledLinkMethod.heldObject, is(shouldCallLinkAndUnlinkMethods));
                assertThat(calledUnlinkMethod.heldObject, is(false));
                assertThat(calledSuperMethod.heldObject, is(false));
                return doMethodReturnValue;
            });
        }

        // Configure the unlinkTracingAndMdcFromCurrentThread method to:
        //      1. Indicate that the unlink method was called (for future assertions).
        //      2. Assert that the link method was called previously if and only if shouldCallLinkAndUnlinkMethods is true.
        //      3. Assert that the do* method was called (if it was setup to *not* explode).
        //      4. Assert that the super method has not yet been called.
        //      5. Assert that the tracing info arg passed in was the same data that was returned by the link method.
        doAnswer(invocation -> {
            calledUnlinkMethod.heldObject = true;
            assertThat(calledLinkMethod.heldObject, is(shouldCallLinkAndUnlinkMethods));
            if (shouldExplodeInDoMethod)
                assertThat(calledDoMethod.heldObject, is(false));
            else
                assertThat(calledDoMethod.heldObject, is(true));
            assertThat(calledSuperMethod.heldObject, is(false));
            assertThat(invocation.getArguments()[1], is(linkTracingAndMdcReturnVal));
            return null;
        }).when(handlerSpy).unlinkTracingAndMdcFromCurrentThread(ctxMock, linkTracingAndMdcReturnVal);

        // If the ChannelHandlerContext has a respective fire* method associated with the method we're testing, then
        //      we can use that as a proxy to verify that the super method was called.
        if (fireEventMethod != null) {
            Object[] fireEventArgs = new Object[fireEventMethod.getParameterCount()];
            // Configure the ctxMock's fire* method to:
            //      1. Indicate that the super method was called (for future assertions).
            //      2. Assert that the link method was called previously if and only if shouldCallLinkAndUnlinkMethods is true.
            //      3. Assert that the do* method was called previously.
            //      4. Assert that the unlink method was called previously if and only if shouldCallLinkAndUnlinkMethods is true.
            when(fireEventMethod.invoke(ctxMock, fireEventArgs)).thenAnswer(invocation -> {
                calledSuperMethod.heldObject = true;
                assertThat(calledLinkMethod.heldObject, is(shouldCallLinkAndUnlinkMethods));
                assertThat(calledDoMethod.heldObject, is(true));
                assertThat(calledUnlinkMethod.heldObject, is(shouldCallLinkAndUnlinkMethods));
                return ctxMock;
            });
        }

        try {
            // Execute the method in question.
            mainMethod.invoke(handlerSpy, methodArgs);
        }
        catch(InvocationTargetException ex) {
            // An exception occurred during method execution.
            if (ex.getCause() instanceof AssertionError) {
                // It was an AssertionError, so something unexpected happened. Throw a RuntimeException with instructions
                //      on how to interpret what went wrong.
                throw new RuntimeException(
                    "An AssertionError was experienced while running the method-to-be-tested. This either means a bug in "
                    + "BaseInboundHandlerWithTracingAndMdcSupport for the method in question, or this unit test is "
                    + "out-of-date and needs to be fixed. See the AssertionError cause for the true cause of the error.",
                    ex.getCause());
            }
            // Not an AssertionError, so this should be the expected IntentionalDoMethodException.
            assertThat(ex.getCause(), instanceOf(IntentionalDoMethodException.class));
            // Make sure we were supposed to throw a IntentionalDoMethodException.
            assertThat(shouldExplodeInDoMethod, is(true));
            // The do* method was called, so mark it that way (for future assertions).
            calledDoMethod.heldObject = true;
        }

        assertThat(calledLinkMethod.heldObject, is(shouldCallLinkAndUnlinkMethods));
        assertThat(calledDoMethod.heldObject, is(true)); // The do* method should always be called no matter what.
        assertThat(calledUnlinkMethod.heldObject, is(shouldCallLinkAndUnlinkMethods));
        if (fireEventMethod != null) {
            boolean shouldHaveCalledSuperMethod = !shouldExplodeInDoMethod && (doMethodReturnValue == null || PipelineContinuationBehavior.CONTINUE.equals(doMethodReturnValue));
            assertThat(calledSuperMethod.heldObject, is(shouldHaveCalledSuperMethod));
        }

        if (shouldPerformDebugLogging) {
            List<LoggingEvent> loggingEvents = handlerLogger.getLoggingEvents();
            assertThat(loggingEvents.size(), is(1));
            assertThat(loggingEvents.get(0).getMessage(), containsString(" " + methodName + " for "));
        }

        boolean argsEligibleMethodShouldHaveBeenCalled = !isDefaultMethodImpl;
        int numExpectedArgsEligibleMethodCalls = (argsEligibleMethodShouldHaveBeenCalled) ? 1 : 0;

        verify(handlerSpy, times(numExpectedArgsEligibleMethodCalls)).argsAreEligibleForLinkingAndUnlinkingDistributedTracingInfo(
            eq(expectedHandlerMethodToExecute), eq(ctxMock), any(), any()
        );
    }

    private static class ObjectHolder<T> {
        public T heldObject;

        public ObjectHolder(T heldObject) {
            this.heldObject = heldObject;
        }
    }

    private static class IntentionalDoMethodException extends RuntimeException {
        public IntentionalDoMethodException(String message) {
            super(message);
        }
    }

    private Method findMethodWithName(Class clazz, String methodName) {
        Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().equals(methodName))
                return method;
        }

        throw new RuntimeException("Unable to find method on " + clazz.getName() + " named: " + methodName);
    }

}