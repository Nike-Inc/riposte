package com.nike.riposte.server.handler

import com.nike.riposte.server.channelpipeline.ChannelAttributes
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior
import com.nike.riposte.server.http.HttpProcessingState
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.Attribute
import io.netty.util.AttributeKey
import spock.lang.Specification
import spock.lang.Unroll
import uk.org.lidalia.slf4jext.Level
import uk.org.lidalia.slf4jtest.TestLogger
import uk.org.lidalia.slf4jtest.TestLoggerFactory

import java.time.Instant

class AccessLogStartHandlerSpec extends Specification {

  def "doChannelRead() - message is random object"() {
    given: "an AccessLogStartHandler"
      AccessLogStartHandler handler = new AccessLogStartHandler()
      def (ChannelHandlerContext mockContext, HttpProcessingState state) = mockContext()
    when: "we call doChannelRead()"
      PipelineContinuationBehavior response = handler.doChannelRead(mockContext, Mock(Object))
    then:
      response == PipelineContinuationBehavior.CONTINUE
      state.getRequestStartTime() == null
  }

  def "doChannelRead() - message is HttpRequest"() {
    given: "an AccessLogStartHandler"
      AccessLogStartHandler handler = new AccessLogStartHandler()
      def (ChannelHandlerContext mockContext, HttpProcessingState state) = mockContext()
      assert state.getRequestStartTime() == null
      assert state.getRequestStartTimeNanos() == null
    when: "we call doChannelRead()"
      PipelineContinuationBehavior response = handler.doChannelRead(mockContext, Mock(HttpRequest))
    then:
      response == PipelineContinuationBehavior.CONTINUE
      state.getRequestStartTime() != null
      state.getRequestStartTimeNanos() != null
  }

  def "doChannelRead() - message is LastHttpContent"() {
    given: "an AccessLogStartHandler"
      AccessLogStartHandler handler = new AccessLogStartHandler()
      def (ChannelHandlerContext mockContext, HttpProcessingState state) = mockContext()
      assert state.getRequestLastChunkArrivedTimeNanos() == null
    when: "we call doChannelRead()"
      PipelineContinuationBehavior response = handler.doChannelRead(mockContext, Mock(LastHttpContent))
    then:
      response == PipelineContinuationBehavior.CONTINUE
      state.getRequestLastChunkArrivedTimeNanos() != null
  }

  @Unroll
  def "doChannelRead() - null HttpProcessingState - message type: #message"(Object message) {
    given: "an AccessLogStartHandler"
      AccessLogStartHandler handler = new AccessLogStartHandler()
      HttpProcessingState state = new HttpProcessingState()
      TestLogger logger = TestLoggerFactory.getTestLogger(handler.getClass())
      logger.clearAll()

      ChannelAttributes.getHttpProcessingStateForChannel(_) >> state
      ChannelHandlerContext mockContext = Mock(ChannelHandlerContext)
      Attribute<HttpProcessingState> mockAttribute = Mock(Attribute)
      mockAttribute.get() >> null
      Channel mockChannel = Mock(Channel)
      mockChannel.attr(_ as AttributeKey) >> mockAttribute
      mockContext.channel() >> mockChannel

    when: "we call doChannelRead()"
      PipelineContinuationBehavior response = handler.doChannelRead(mockContext, message)
    then:
      response == PipelineContinuationBehavior.CONTINUE
      logger.getAllLoggingEvents().size() == 1
      logger.getAllLoggingEvents().get(0).level == Level.WARN
      logger.getAllLoggingEvents().get(0).message.contains("HttpProcessingState is null")

    where:
      message << [Mock(HttpRequest), Mock(LastHttpContent)]
  }

  protected List mockContext() {
    HttpProcessingState state = new HttpProcessingState()
    ChannelAttributes.getHttpProcessingStateForChannel(_) >> state
    ChannelHandlerContext mockContext = Mock(ChannelHandlerContext)
    Attribute<HttpProcessingState> mockAttribute = Mock(Attribute)
    mockAttribute.get() >> state
    Channel mockChannel = Mock(Channel)
    mockChannel.attr(_ as AttributeKey) >> mockAttribute
    mockContext.channel() >> mockChannel
    [mockContext, state]
  }
}
