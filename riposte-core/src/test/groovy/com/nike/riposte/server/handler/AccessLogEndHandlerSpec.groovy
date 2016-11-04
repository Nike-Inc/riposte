package com.nike.riposte.server.handler

import com.nike.riposte.server.channelpipeline.ChannelAttributes
import com.nike.riposte.server.channelpipeline.message.LastOutboundMessage
import com.nike.riposte.server.handler.base.PipelineContinuationBehavior
import com.nike.riposte.server.http.HttpProcessingState
import com.nike.riposte.server.http.RequestInfo
import com.nike.riposte.server.http.ResponseInfo
import com.nike.riposte.server.logging.AccessLogger
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.Attribute
import io.netty.util.AttributeKey
import io.netty.util.concurrent.GenericFutureListener
import spock.lang.Specification
import uk.org.lidalia.slf4jext.Level
import uk.org.lidalia.slf4jtest.TestLogger
import uk.org.lidalia.slf4jtest.TestLoggerFactory

import java.time.Instant

class AccessLogEndHandlerSpec extends Specification {

  def "doChannelRead - with msg as random object"() {
    given: "we have a AccessLogEndHandler"
      AccessLogger accessLogger = Mock(AccessLogger)
      0 * accessLogger.log(_ as RequestInfo, _ as HttpResponse, _ as ResponseInfo, _ as Long)
      AccessLogEndHandler handler = new AccessLogEndHandler(accessLogger)
      def (ChannelHandlerContext mockContext, HttpProcessingState state) = mockContext()
    when: "we call doChannelRead()"
      PipelineContinuationBehavior result = handler.doChannelRead(mockContext, Mock(Object))
    then:
      result == PipelineContinuationBehavior.CONTINUE
  }

  def "doChannelRead - with null HttpProcessingState"() {
    given: "we have a AccessLogEndHandler"
      AccessLogger accessLogger = Mock(AccessLogger)
      0 * accessLogger.log(_ as RequestInfo, _ as HttpResponse, _ as ResponseInfo, _ as Long)
      AccessLogEndHandler handler = new AccessLogEndHandler(accessLogger)
      TestLogger logger = TestLoggerFactory.getTestLogger(handler.getClass())
      logger.clearAll()

      HttpProcessingState state = Mock(HttpProcessingState)
      state.getRequestStartTime() >> Instant.EPOCH
      state.getResponseInfo() >> Mock(ResponseInfo)
      ChannelAttributes.getHttpProcessingStateForChannel(_) >> state
      ChannelHandlerContext mockContext = Mock(ChannelHandlerContext)
      Attribute<HttpProcessingState> mockAttribute = Mock(Attribute)
      mockAttribute.get() >> null
      Channel mockChannel = Mock(Channel)
      mockChannel.attr(_ as AttributeKey) >> mockAttribute
      mockContext.channel() >> mockChannel
    when: "we call doChannelRead()"
      PipelineContinuationBehavior result = handler.doChannelRead(mockContext, Mock(LastOutboundMessage))
    then:
      result == PipelineContinuationBehavior.CONTINUE
      logger.getAllLoggingEvents().size() == 1
      logger.getAllLoggingEvents().get(0).level == Level.WARN
      logger.getAllLoggingEvents().get(0).message.contains("HttpProcessingState is null")
  }

  def "doChannelRead() should add listener to response writer channel future if available"() {
    given:
        AccessLogger accessLogger = Mock(AccessLogger)
        AccessLogEndHandler handler = new AccessLogEndHandler(accessLogger)
        def (ChannelHandlerContext mockContext, HttpProcessingState state) = mockContext()
        ChannelFuture channelFutureMock = Mock(ChannelFuture)
        RequestInfo requestInfoMock = Mock(RequestInfo)
        state.getResponseWriterFinalChunkChannelFuture() >> channelFutureMock
        state.isResponseSent() >> true
        state.getRequestInfo() >> requestInfoMock
        state.isResponseSendingLastChunkSent() >> true
        requestInfoMock.getProtocolVersion() >> HttpVersion.HTTP_1_1
        requestInfoMock.getMethod() >> HttpMethod.GET
        requestInfoMock.getUri() >> "/some/uri"
        requestInfoMock.getHeaders() >> new DefaultHttpHeaders()

        GenericFutureListener<ChannelFuture> operation = null
        1 * channelFutureMock.addListener(_) >> {
          operation = it[0]
          return null
        }
        HttpResponse actualResponseObjectMock = state.getActualResponseObject()
        ResponseInfo responseInfoMock = state.getResponseInfo()
        boolean accessLoggerCalled = false
        accessLogger.log(_ as RequestInfo, actualResponseObjectMock, responseInfoMock, _ as Long) >> {
          accessLoggerCalled = true
          return null
        }
    when:
        handler.doChannelRead(mockContext, Mock(LastOutboundMessage))
    then:
        operation != null
        accessLoggerCalled == false
        operation.operationComplete(Mock(ChannelFuture))
        accessLoggerCalled == true
  }


  protected List mockContext() {
    HttpProcessingState state = Mock(HttpProcessingState)
    state.getRequestStartTime() >> Instant.EPOCH
    state.getResponseInfo() >> Mock(ResponseInfo)
    state.getActualResponseObject() >> Mock(HttpResponse)
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
