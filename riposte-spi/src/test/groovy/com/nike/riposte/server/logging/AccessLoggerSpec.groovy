package com.nike.riposte.server.logging

import com.nike.internal.util.Pair
import com.nike.riposte.server.http.RequestInfo
import com.nike.riposte.server.http.ResponseInfo
import com.nike.riposte.server.http.impl.FullResponseInfo
import io.netty.handler.codec.http.*
import spock.lang.Specification
import spock.lang.Unroll
import uk.org.lidalia.slf4jext.Level
import uk.org.lidalia.slf4jtest.TestLogger
import uk.org.lidalia.slf4jtest.TestLoggerFactory

import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING

class AccessLoggerSpec extends Specification {

  def "log(): logs the request"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
      TestLogger logger = TestLoggerFactory.getTestLogger("ACCESS_LOG")
      logger.clearAll()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getHeaders().get("Referer") >> "myReferer"
      requestMock.getHeaders().get("User-Agent") >> "myUserAgent"
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getProtocolVersion() >> HttpVersion.HTTP_1_1
      requestMock.getRawContentLengthInBytes() >> 19
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, 210, Mock(HttpHeaders), null, null, null, false)
      responseMock.uncompressedRawContentLength = 21
      responseMock.finalContentLength = 20
    when: "we call logMessageAdditions()"
      CompletableFuture<?> cf = accessLogger.log(requestMock, null, responseMock, 20L)
      cf.join()
    then:
      logger.getAllLoggingEvents().size() == 1
      logger.getAllLoggingEvents().get(0).level == Level.INFO
      logger.getAllLoggingEvents().get(0).message.contains("\"GET /test HTTP/1.1\" 210 20 \"myReferer\" \"myUserAgent\" accept-Req=- content-type-Req=- content-length-Res=- transfer_encoding-Res=- http_status_code-Res=210 error_uid-Res=- X-B3-Sampled-Req=- X-B3-SpanId-Req=- X-B3-TraceId-Req=- X-B3-TraceId-Res=- raw_content_length-Req=19 raw_content_length-Res=21 final_content_length-Res=20 elapsed_time_millis=20")
  }

    def "log(): throws IllegalArgumentException if passed null request"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            ResponseInfo<?> responseInfo = Mock(ResponseInfo)
        when:
            accessLogger.log(null, null, responseInfo, 42L)
        then:
            thrown(IllegalArgumentException)
    }

  def "logMessageAdditions(): contains all expected headers including custom app ones"() {
    given: "the AccessLogger object that also returns some custom log message extras"
      AccessLogger accessLogger = new AccessLogger() {
          @Override
          protected List<Pair<String, String>> customApplicationLogMessageExtras(RequestInfo<?> request, HttpResponse finalResponseObject, ResponseInfo responseInfo, Long elapsedTimeMillis) {
              return Arrays.asList(Pair.of("foo", "bar"), Pair.of("whee", "yay"))
          }
      }
    and: "we've mocked the request object"
      HttpHeaders headersMock = Mock(DefaultHttpHeaders)
      headersMock.get("Accept") >> "application/json"
      headersMock.get("Content-Type") >> "application/json;charset=utf-8"
      headersMock.get("Referer") >> "test"
      headersMock.get("X-B3-Sampled") >> "X-B3-SampledMock"
      headersMock.get("X-B3-SpanId") >> "X-B3-SpanIdMock"
      headersMock.get("X-B3-SpanName") >> "X-B3-SpanNameMock"
      headersMock.get("X-B3-TraceId") >> "X-B3-TraceId-ReqMock"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> headersMock
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getRawContentLengthInBytes() >> 19
    and: "we've mocked the response object"
      HttpHeaders responseHeadersMock = Mock(HttpHeaders)
      responseHeadersMock.get("Content-Length") >> "Content-LengthMock"
      responseHeadersMock.get("Transfer-Encoding") >> "Transfer-EncodingMock"
      responseHeadersMock.get("X-B3-TraceId") >> "X-B3-TraceId-ResMock"
      responseHeadersMock.get("error_uid") >> "error_uidMock"
      ResponseInfo responseMock = new FullResponseInfo(null, 210, responseHeadersMock, null, null, null, false)
      responseMock.uncompressedRawContentLength = 21
      responseMock.finalContentLength = 20

    when: "we call logMessageAdditions()"
      List<Pair<String, String>> result = accessLogger.logMessageAdditions(requestMock, null, responseMock, 20L)
    then:
      result.size() == 16
      result.get(0) == Pair.of("accept-Req", "application/json")
      result.get(1) == Pair.of("content-type-Req", "application/json;charset=utf-8")
      result.get(2) == Pair.of("content-length-Res", "Content-LengthMock")
      result.get(3) == Pair.of("transfer_encoding-Res", "Transfer-EncodingMock")
      result.get(4) == Pair.of("http_status_code-Res", "210")
      result.get(5) == Pair.of("error_uid-Res", "error_uidMock")
      result.get(6) == Pair.of("X-B3-Sampled-Req", "X-B3-SampledMock")
      result.get(7) == Pair.of("X-B3-SpanId-Req", "X-B3-SpanIdMock")
      result.get(8) == Pair.of("X-B3-TraceId-Req", "X-B3-TraceId-ReqMock")
      result.get(9) == Pair.of("X-B3-TraceId-Res", "X-B3-TraceId-ResMock")
      result.get(10) == Pair.of("raw_content_length-Req", "19")
      result.get(11) == Pair.of("raw_content_length-Res", "21")
      result.get(12) == Pair.of("final_content_length-Res", "20")
      result.get(13) == Pair.of("elapsed_time_millis", "20")
      result.get(14) == Pair.of("foo", "bar")
      result.get(15) == Pair.of("whee", "yay")
  }
    
  def "logMessageAdditions(): includes missing headers but gives them null values"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getRawContentLengthInBytes() >> 19
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, 210, Mock(HttpHeaders), null, null, null, false)

    when: "we call logMessageAdditions()"
      List<Pair<String, String>> result = accessLogger.logMessageAdditions(requestMock, null, responseMock, 20L)
    then:
      result.forEach(new Consumer<Pair<String, String>>() {
          @Override
          void accept(Pair<String, String> pair) {
              String key = pair.getKey()
              if ("raw_content_length-Req".equals(key))
                  assert pair.getValue() == "19"
              else if ("http_status_code-Res".equals(key))
                  assert pair.getValue() == "210"
              else if ("elapsed_time_millis".equals(key))
                  assert pair.getValue() == "20"
              else
                  assert pair.getValue() == null
          }
      })
  }
  def "logMessageAdditions(): handles null response object"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getRawContentLengthInBytes() >> 19
    when: "we call logMessageAdditions()"
      List<Pair<String, String>> result = accessLogger.logMessageAdditions(requestMock, null, null, 20L)
    then:
        result.forEach(new Consumer<Pair<String, String>>() {
            @Override
            void accept(Pair<String, String> pair) {
                String key = pair.getKey()
                if ("raw_content_length-Req".equals(key))
                    assert pair.getValue() == "19"
                else if ("elapsed_time_millis".equals(key))
                    assert pair.getValue() == "20"
                else
                    assert pair.getValue() == null
            }
        })
  }
  def "logMessageAdditions(): uses null status code if none is given"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getRawContentLengthInBytes() >> 19
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, null, Mock(HttpHeaders), null, null, null, false)

    when: "we call logMessageAdditions()"
      List<Pair<String, String>> result = accessLogger.logMessageAdditions(requestMock, null, responseMock, 20L)
    then:
        result.forEach(new Consumer<Pair<String, String>>() {
            @Override
            void accept(Pair<String, String> pair) {
                String key = pair.getKey()
                if ("raw_content_length-Req".equals(key))
                    assert pair.getValue() == "19"
                else if ("elapsed_time_millis".equals(key))
                    assert pair.getValue() == "20"
                else
                    assert pair.getValue() == null
            }
        })
  }

    private void populateHeadersWithRandomResponseInfoForLogMessageAdditions(HttpHeaders headers) {
        headers.set(CONTENT_LENGTH, UUID.randomUUID().toString())
        headers.set(TRANSFER_ENCODING, UUID.randomUUID().toString())
        headers.set("error_uid", UUID.randomUUID().toString())
        headers.set(AccessLogger.TRACE_ID, UUID.randomUUID().toString())
    }

    @Unroll
    def "logMessageAdditions(): uses correct response headers based on available data"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            RequestInfo requestMock = Mock(RequestInfo)
            requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
            HttpResponse finalResponseObj = null
            HttpHeaders finalResponseObjHeaders = new DefaultHttpHeaders()
            populateHeadersWithRandomResponseInfoForLogMessageAdditions(finalResponseObjHeaders)
            if (!finalResponseObjectIsNull) {
                finalResponseObj = Mock(HttpResponse)
                if (!finalResponseObjectHeadersIsNull)
                    finalResponseObj.headers() >> finalResponseObjHeaders
            }
            ResponseInfo responseInfo = null
            HttpHeaders responseInfoHeaders = new DefaultHttpHeaders()
            populateHeadersWithRandomResponseInfoForLogMessageAdditions(responseInfoHeaders)
            if (!responseInfoIsNull)
                responseInfo = new FullResponseInfo(null, null, responseInfoHeaders, null, null, null, false)

            HttpHeaders expectedHeaders = null
            if (!expectNullResponseHeaders) {
                if (expectFinalResponseObjectHeadersAreUsed)
                    expectedHeaders = finalResponseObjHeaders
                else
                    expectedHeaders = responseInfoHeaders
            }
        when:
            List<Pair<String, String>> result = accessLogger.logMessageAdditions(requestMock, finalResponseObj, responseInfo, 1000)
        then:
            if (expectedHeaders == null) {
                assert result.contains(Pair.of("content-length-Res", null))
                assert result.contains(Pair.of("transfer_encoding-Res", null))
                assert result.contains(Pair.of("error_uid-Res", null))
                assert result.contains(Pair.of(AccessLogger.TRACE_ID + "-Res", null))
            }
            else {
                assert result.contains(Pair.of("content-length-Res", expectedHeaders.get(CONTENT_LENGTH)))
                assert result.contains(Pair.of("transfer_encoding-Res", expectedHeaders.get(TRANSFER_ENCODING)))
                assert result.contains(Pair.of("error_uid-Res", expectedHeaders.get("error_uid")))
                assert result.contains(Pair.of(AccessLogger.TRACE_ID + "-Res", expectedHeaders.get(AccessLogger.TRACE_ID)))
            }
        where:
            finalResponseObjectIsNull   |   finalResponseObjectHeadersIsNull    |   responseInfoIsNull  |   expectNullResponseHeaders   |   expectFinalResponseObjectHeadersAreUsed
            true                        |   true                                |   true                |   true                        |   false
            false                       |   true                                |   true                |   true                        |   false
            true                        |   true                                |   false               |   false                       |   false
            false                       |   true                                |   false               |   false                       |   false
            false                       |   false                               |   false               |   false                       |   true
    }

    @Unroll
    def "logMessageAdditions(): uses correct HTTP status code based on available data"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            RequestInfo requestMock = Mock(RequestInfo)
            requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
            HttpResponse finalResponseObj = null
            if (!finalResponseObjectIsNull) {
                finalResponseObj = Mock(HttpResponse)
                finalResponseObj.getStatus() >> finalResponseObjectGetStatus
            }
            ResponseInfo responseInfo = null
            if (!responseInfoIsNull)
                responseInfo = new FullResponseInfo(null, responseInfoHttpStatusCode, null, null, null, null, false)
        when:
            List<Pair<String, String>> result = accessLogger.logMessageAdditions(requestMock, finalResponseObj, responseInfo, 1000)
        then:
            result.contains(Pair.of("http_status_code-Res", expectedResultStatusCode))
        where:
            finalResponseObjectIsNull   |   finalResponseObjectGetStatus    |   responseInfoIsNull  |   responseInfoHttpStatusCode  |   expectedResultStatusCode
            true                        |   null                            |   true                |   null                        |   null
            true                        |   null                            |   false               |   null                        |   null
            true                        |   null                            |   false               |   4242                        |   "4242"
            false                       |   null                            |   false               |   4242                        |   "4242"
            false                       |   HttpResponseStatus.valueOf(42)  |   false               |   4242                        |   "42"
    }

  def "combinedLogFormatPrefix(): happy path"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getHeaders().get("Referer") >> "myReferer"
      requestMock.getHeaders().get("User-Agent") >> "myUserAgent"
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getProtocolVersion() >> HttpVersion.HTTP_1_1
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, 209, Mock(HttpHeaders), null, null, null, false)
      responseMock.finalContentLength = 20

    when: "we call combinedLogFormatPrefix()"
      String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseMock)
    then:
      result.contains("\"GET /test HTTP/1.1\" 209 20 \"myReferer\" \"myUserAgent\"")
  }

  def "combinedLogFormatPrefix(): handles null response"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getHeaders().get("Referer") >> "myReferer"
      requestMock.getHeaders().get("User-Agent") >> "myUserAgent"
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getProtocolVersion() >> HttpVersion.HTTP_1_1

    when: "we call combinedLogFormatPrefix()"
      String result = accessLogger.combinedLogFormatPrefix(requestMock, null, null)
    then:
      result.contains("\"GET /test HTTP/1.1\" - - \"myReferer\" \"myUserAgent\"")
  }

  def "combinedLogFormatPrefix(): null request method does not throw exception"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getHeaders().get("Referer") >> "myReferer"
      requestMock.getHeaders().get("User-Agent") >> "myUserAgent"
      requestMock.getMethod() >> null
      requestMock.getUri() >> "/test"
      requestMock.getProtocolVersion() >> HttpVersion.HTTP_1_1
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, 209, Mock(HttpHeaders), null, null, null, false)
      responseMock.finalContentLength = 20
    when: "we call combinedLogFormatPrefix()"
      String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseMock)
    then:
      result.contains("\"- /test HTTP/1.1\" 209 20 \"myReferer\" \"myUserAgent\"")
  }

  def "combinedLogFormatPrefix(): null request protocol does not throw exception"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
      requestMock.getHeaders().get("Referer") >> "myReferer"
      requestMock.getHeaders().get("User-Agent") >> "myUserAgent"
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getProtocolVersion() >> null
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, 209, Mock(HttpHeaders), null, null, null, false)
      responseMock.finalContentLength = 20
    when: "we call combinedLogFormatPrefix()"
      String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseMock)
    then:
      result.contains("\"GET /test -\" 209 20 \"myReferer\" \"myUserAgent\"")
  }

  def "combinedLogFormatPrefix(): null request headers does not throw exception"() {
    given: "the AccessLogger object"
      AccessLogger accessLogger = new AccessLogger()
    and: "we've mocked the request object"
      RequestInfo requestMock = Mock(RequestInfo)
      requestMock.getHeaders() >> null
      requestMock.getMethod() >> HttpMethod.GET
      requestMock.getUri() >> "/test"
      requestMock.getProtocolVersion() >> HttpVersion.HTTP_1_1
    and: "we've mocked the response object"
      ResponseInfo responseMock = new FullResponseInfo(null, 209, Mock(HttpHeaders), null, null, null, false)
      responseMock.finalContentLength = 20
    when: "we call combinedLogFormatPrefix()"
      String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseMock)
    then:
      result.contains("\"GET /test HTTP/1.1\" 209 20 \"-\" \"-\"")
  }

    def "combinedLogFormatPrefix(): handles null request and response info appropriately"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            RequestInfo requestMock = Mock(RequestInfo)
            requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
            requestMock.getHeaders().get("Referer") >> null
            requestMock.getHeaders().get("User-Agent") >> null
            ResponseInfo responseMock = new FullResponseInfo(null, null, null, null, null, null, false)
        when:
            String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseMock)
        then:
            result.endsWith("\"- - -\" - - \"-\" \"-\"")
    }

    def "combinedLogFormatPrefix(): uses <unknown> for local IP address if getLocalIpAddress() throws UnknownHostException"() {
        given:
            AccessLogger accessLogger = Spy(AccessLogger)
            accessLogger.getLocalIpAddress() >> { throw new UnknownHostException() }
            RequestInfo requestMock = Mock(RequestInfo)
            requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
            requestMock.getHeaders().get("Referer") >> null
            requestMock.getHeaders().get("User-Agent") >> null
            ResponseInfo responseMock = new FullResponseInfo(null, null, null, null, null, null, false)
        when:
            String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseMock)
        then:
            result.startsWith("<unknown>")
    }

    @Unroll
    def "combinedLogFormatPrefix(): sets HTTP status code correctly based on final response object and response info values"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            RequestInfo requestMock = Mock(RequestInfo)
            requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
            HttpResponse finalResponseObj = null
            if (!finalResponseObjectIsNull) {
                finalResponseObj = Mock(HttpResponse)
                finalResponseObj.getStatus() >> finalResponseObjectGetStatus
            }
            ResponseInfo responseInfo = null
            if (!responseInfoIsNull)
                responseInfo = new FullResponseInfo(null, responseInfoHttpStatusCode, null, null, null, null, false)
        when:
            String result = accessLogger.combinedLogFormatPrefix(requestMock, finalResponseObj, responseInfo)
        then:
            result.contains("\"- - -\" " + expectedResultStatusCode)
        where:
            finalResponseObjectIsNull   |   finalResponseObjectGetStatus    |   responseInfoIsNull  |   responseInfoHttpStatusCode  |   expectedResultStatusCode
            true                        |   null                            |   true                |   null                        |   "-"
            true                        |   null                            |   false               |   null                        |   "-"
            true                        |   null                            |   false               |   4242                        |   4242
            false                       |   null                            |   false               |   4242                        |   4242
            false                       |   HttpResponseStatus.valueOf(42)  |   false               |   4242                        |   42
    }

    @Unroll
    def "combinedLogFormatPrefix(): sets content length correctly based on response info values"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            RequestInfo requestMock = Mock(RequestInfo)
            requestMock.getHeaders() >> Mock(DefaultHttpHeaders)
            ResponseInfo responseInfo = null
            if (!responseInfoIsNull) {
                responseInfo = new FullResponseInfo(null, null, null, null, null, null, false)
                responseInfo.finalContentLength = responseInfoFinalContentLength
            }
        when:
            String result = accessLogger.combinedLogFormatPrefix(requestMock, null, responseInfo)
        then:
            result.contains("\"- - -\" - " + expectedFinalContentLength)
        where:
            responseInfoIsNull  |   responseInfoFinalContentLength  |   expectedFinalContentLength
            true                |   null                            |   "-"
            false               |   null                            |   "-"
            false               |   0                               |   "-"
            false               |   42                              |   42
    }

    def "convertAdditionsToString(): returns hyphen when passed null"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
        expect:
            accessLogger.convertAdditionsToString(null) == "-"
    }

    def "formatAdditionPairForLogMessage(): returns hyphen when passed null"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
        expect:
            accessLogger.formatAdditionPairForLogMessage(null) == "-"
    }

    def "formatAdditionPairForLogMessage(): returns hyphen when passed pair with null key"() {
        given:
            AccessLogger accessLogger = new AccessLogger()
            String value = UUID.randomUUID().toString()
        expect:
            accessLogger.formatAdditionPairForLogMessage(Pair.of(null, value)) == "-=" + value
    }

}
