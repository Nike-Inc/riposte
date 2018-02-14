package com.nike.riposte.server.logging;

import com.nike.internal.util.Pair;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;

import static io.netty.handler.codec.http.HttpHeaders.Names.ACCEPT;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.REFERER;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaders.Names.USER_AGENT;

/**
 * This class is responsible for logging an access log message for the given request/response to the SLF4J logger with
 * the name: ACCESS_LOG
 *
 * <p>To enable access logging, an application's {@link com.nike.riposte.server.config.ServerConfig#accessLogger()}
 * should return a new instance of this class.
 *
 * <p>You can customize the format and/or add data to the log messages:
 * <pre>
 * <ul>
 *     <li>
 *          The main hook for modification that leaves the defaults in place but allows you to add extra data that you
 *          need for your app is {@link
 *          #customApplicationLogMessageExtras(RequestInfo, HttpResponse, ResponseInfo, Long)}. You can safely return
 *          null if you have nothing extra you need logged.
 *     </li>
 *     <li>
 *          If you don't like the default delimiter between the additional data key/value pairs (the stuff after the
 *          NCSA Combined log string) then override {@link #formatAdditionPairForLogMessage(Pair)}.
 *     </li>
 *     <li>
 *         If you don't like the way the additional data key/value pairs are separated from each other then override
 *         {@link #convertAdditionsToString(java.util.List)}.
 *     </li>
 *     <li>
 *         If you don't like the way the NCSA Combined log string and the additional data is separated then override
 *         {@link #concatenateCombinedLogAndAdditionStrings(String, String)}.
 *     </li>
 *     <li>
 *         If you don't like *any* of it and want to control everything, you can simply override {@link
 *         #generateFinalAccessLogMessage(RequestInfo, HttpResponse, ResponseInfo, Long)}.
 *     </li>
 *     <li>
 *         For anything else you'll need to identify the method that does what you don't like and override it.
 *     </li>
 * </ul>
 * </pre>
 *
 * By default if you enable this class in your application and don't change anything about your SLF4J configuration
 * you'll see the access log messages show up in the same place as all your other log messages. If you want to keep them
 * separate you can do so by creating a different appender for your access logs funneling all messages to the logger
 * named "ACCESS_LOG" so that they go to the appender you want. For example here's a sample logback.groovy snippet that
 * would pipe all access log messages to a separate file (note that this is just a simplistic example - in real
 * production code you'd want the separate appender to be asynchronous as well):
 * <pre>
 *      appender("ACCESS-FILE", RollingFileAppender) {
 *          file = "${LOG_FILE_DIRECTORY_PATH}/@@APPNAME@@-access.log"
 *          rollingPolicy(FixedWindowRollingPolicy) {
 *              fileNamePattern = "${LOG_FILE_DIRECTORY_PATH}/@@APPNAME@@-access.%i.log.zip"
 *              minIndex = 1
 *              maxIndex = 5
 *          }
 *          triggeringPolicy(SizeBasedTriggeringPolicy) {
 *              maxFileSize = "50MB"
 *          }
 *          encoder(PatternLayoutEncoder) {
 *              pattern = "%msg%n"
 *          }
 *      }
 *      logger("ACCESS_LOG", INFO, ["ACCESS-FILE"], false)
 * </pre>
 */
@SuppressWarnings("WeakerAccess")
public class AccessLogger {

    protected static final Logger logger = LoggerFactory.getLogger("ACCESS_LOG");

    // B3 distributed tracing headers for wingtips/zipkin awareness.
    public static final String TRACE_ID = "X-B3-TraceId";
    public static final String SPAN_ID = "X-B3-SpanId";
    @SuppressWarnings("unused")
    public static final String PARENT_ID = "X-B3-ParentSpanId";
    public static final String SPAN_NAME = "X-B3-SpanName";
    public static final String TRACE_ENABLED = "X-B3-Sampled";

    private static String cachedLocalIpAddress;
    private static long cachedLocalIpAddressLastCheckedTime = 0;
    private static final long LOCAL_IP_ADDRESS_CACHE_CHECK_FREQUENCY_MILLIS = 60 * 1000;

    private final String timezoneString;
    private final String[] shortMonthNames;
    private final CompletableFuture<Void> alreadyCompletedFuture = CompletableFuture.completedFuture(null);

    public AccessLogger() {
        // Setup everything we can in advance now to make date/time formatting as efficient as possible later.
        timezoneString = " " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("Z"));
        Locale defaultLocale = Locale.getDefault(Locale.Category.FORMAT);
        Map<String, Integer> monthMap =
            Calendar.getInstance().getDisplayNames(Calendar.MONTH, Calendar.SHORT_FORMAT, defaultLocale);
        int maxMonthIndex = Collections.max(monthMap.values());
        shortMonthNames = new String[maxMonthIndex + 1];
        monthMap.entrySet().forEach(entry -> shortMonthNames[entry.getValue()] = entry.getKey());
    }

    /**
     * Outputs an access log message based on the given arguments to the SLF4J logger with the name ACCESS_LOG.
     *
     * <p>NOTE: Although this method returns a {@link CompletableFuture} it is actually run synchronously by default
     * (the returned future is created and returned as an already-completed future after the logging has completed)
     * because this default {@link AccessLogger} class runs very quickly - on the order of a handful of microseconds.
     * Therefore it doesn't really affect the Netty worker threads, and running asynchronously adds non-trivial CPU
     * overhead due to the thread context switching under high throughput scenarios so there can be a noticeable
     * downside to running asynchronously. If you really do need this to run asynchronously however (because whatever
     * you're doing in a subclass causes the netty worker threads to block too much), then you can force it to do so by
     * overriding {@link #logAsynchronously()} to return true. Performance testing under high throughput load is
     * recommended before assuming you need to log asynchronously.
     *
     * @param request
     *     The request to log; must not be null. This is the request that came into the application. WARNING: The
     *     content may have been released already, so you cannot rely on any of the methods that return the payload
     *     content in any form.
     * @param finalResponseObject
     *     The {@link HttpResponse} that was the actual final response object sent to the client; may be null. NOTE:
     *     This should be preferred over {@code responseInfo} whenever both have overlapping data since this argument
     *     may have been modified by outbound handlers after being initially populated by {@code responseInfo}.
     * @param responseInfo
     *     {@link com.nike.riposte.server.http.ResponseInfo} object that was initially used to build {@code
     *     finalResponseObject}; may be null. NOTE: {@code finalResponseObject} may have been modified by other outbound
     *     handlers (e.g. compression/gzip), so if it is non-null it should be preferred over this where possible.
     * @param elapsedTimeMillis
     *     represents time difference from receiving the request to ending the request in milliseconds; may be null
     */
    public CompletableFuture<Void> log(RequestInfo<?> request,
                                       HttpResponse finalResponseObject,
                                       ResponseInfo responseInfo,
                                       Long elapsedTimeMillis) {
        if (request == null)
            throw new IllegalArgumentException("request cannot be null");

        if (logAsynchronously()) {
            return CompletableFuture.runAsync(
                () -> logger.info(
                    generateFinalAccessLogMessage(request, finalResponseObject, responseInfo, elapsedTimeMillis)
                )
            );
        }

        // Not running asynchronously. Do it now and return an empty already-completed future.
        logger.info(generateFinalAccessLogMessage(request, finalResponseObject, responseInfo, elapsedTimeMillis));

        return alreadyCompletedFuture;
    }

    /**
     * @return true if this {@link AccessLogger} implementation should do its work asynchronously off the Netty worker
     * thread, false if it should run synchronously on the Netty worker thread. This returns false by default because
     * access loggers normally run very quickly and do not block the Netty worker thread, and the CPU overhead of
     * context switching to run asynchronously is usually very expensive compared to simply running synchronously.
     */
    protected boolean logAsynchronously() {
        return false;
    }

    /**
     * The {@link #log(RequestInfo, HttpResponse, ResponseInfo, Long)} method handles whether this access logger runs
     * synchronously or asynchronously - this method is for generating the full message that gets logged. If you don't
     * want to mess with any of the other helper methods and simply want to 100% fully control the access log message
     * that gets output then you should override this method.
     *
     * @param request
     *     The request to log; must not be null. This is the request that came into the application. WARNING: The
     *     content may have been released already, so you cannot rely on any of the methods that return the payload
     *     content in any form.
     * @param finalResponseObject
     *     The {@link HttpResponse} that was the actual final response object sent to the client; may be null. NOTE:
     *     This should be preferred over {@code responseInfo} whenever both have overlapping data since this argument
     *     may have been modified by outbound handlers after being initially populated by {@code responseInfo}.
     * @param responseInfo
     *     {@link com.nike.riposte.server.http.ResponseInfo} object that was initially used to build {@code
     *     finalResponseObject}; may be null. NOTE: {@code finalResponseObject} may have been modified by other outbound
     *     handlers (e.g. compression/gzip), so if it is non-null it should be preferred over this where possible.
     * @param elapsedTimeMillis
     *     represents time difference from receiving the request to ending the request in milliseconds; may be null
     *
     * @return The final string that will be logged as the full access log message.
     */
    protected String generateFinalAccessLogMessage(RequestInfo<?> request,
                                                   HttpResponse finalResponseObject,
                                                   ResponseInfo responseInfo,
                                                   Long elapsedTimeMillis) {
        String combinedLogString = combinedLogFormatPrefix(request, finalResponseObject, responseInfo);

        List<Pair<String, String>> logMessageAdditions = logMessageAdditions(
            request, finalResponseObject, responseInfo, elapsedTimeMillis
        );
        String logMessageAdditionsAsString = convertAdditionsToString(logMessageAdditions);

        return concatenateCombinedLogAndAdditionStrings(combinedLogString, logMessageAdditionsAsString);
    }

    /**
     * @param request
     *     The request to log; will not be null. This is the request that came into the application. WARNING: The
     *     content may have been released already, so you cannot rely on any of the methods that return the payload
     *     content in any form.
     * @param finalResponseObject
     *     The {@link HttpResponse} that was the actual final response object sent to the client; may be null. NOTE:
     *     This should be preferred over {@code responseInfo} whenever both have overlapping data since this argument
     *     may have been modified by outbound handlers after being initially populated by {@code responseInfo}.
     * @param responseInfo
     *     {@link com.nike.riposte.server.http.ResponseInfo} object that was initially used to build {@code
     *     finalResponseObject}; may be null. NOTE: {@code finalResponseObject} may have been modified by other outbound
     *     handlers (e.g. compression/gzip), so if it is non-null it should be preferred over this where possible.
     *
     * @return String representing the NCSA Combined log format for access logs plus the referrer and user agent: %h %l
     * %u [%t] "%r" %s %b "%i{Referer}" "%i{User-Agent}"
     */
    protected String combinedLogFormatPrefix(RequestInfo<?> request,
                                             HttpResponse finalResponseObject,
                                             ResponseInfo responseInfo) {
        String ipAddress = "<unknown>";
        try {
            ipAddress = getLocalIpAddress();
        }
        catch (UnknownHostException ex) {
            logger.warn("Unable to retrieve local IP address.", ex);
        }

        String method = (request.getMethod() == null) ? "-" : String.valueOf(request.getMethod());
        String uriString = request.getUri();
        if (uriString == null)
            uriString = "-";
        String protocolVersion =
            (request.getProtocolVersion() == null) ? "-" : String.valueOf(request.getProtocolVersion());
        String url = method + " " + uriString + " " + protocolVersion;

        String referer = "-";
        String userAgent = "-";
        if (request.getHeaders() != null) {
            referer = request.getHeaders().get(REFERER);
            if (referer == null)
                referer = "-";
            userAgent = request.getHeaders().get(USER_AGENT);
            if (userAgent == null)
                userAgent = "-";
        }
        String httpStatusCode = "-";
        if (finalResponseObject != null && finalResponseObject.getStatus() != null)
            httpStatusCode = String.valueOf(finalResponseObject.getStatus().code());
        else if (responseInfo != null && responseInfo.getHttpStatusCode() != null)
            httpStatusCode = String.valueOf(responseInfo.getHttpStatusCode());

        String contentLength = "-";
        if (responseInfo != null) {
            if (responseInfo.getFinalContentLength() != null && responseInfo.getFinalContentLength() > 0)
                contentLength = String.valueOf(responseInfo.getFinalContentLength());
        }

        return ipAddress +
               " - - [" + // remote log name and remote user
               getFormattedDateTimeForNcsaCombinedLog(ZonedDateTime.now()) +
               "] \"" +
               url +
               "\" " +
               httpStatusCode +
               " " +
               contentLength +
               " \"" +
               referer +
               "\" \"" +
               userAgent +
               "\"";
    }

    /**
     * Running datetimes through a {@link DateTimeFormatter} is much more expensive than it needs to be for our
     * purposes. This method returns a string identical to what you'd get if you ran the given datetime through a {@code
     * DateTimeFormatter.ofPattern("dd/MMM/YYYY:HH:mm:ss Z")} formatter, but it is cobbled together manually for maximum
     * throughput efficiency. Using a {@link DateTimeFormatter} instead would add roughly 50% to the cost of executing a
     * default access log message.
     *
     * @param dateTime
     *     The datetime to format.
     *
     * @return The given {@link ZonedDateTime} formatted as if it was run through: {@code
     * DateTimeFormatter.ofPattern("dd/MMM/YYYY:HH:mm:ss Z")}.
     */
    protected String getFormattedDateTimeForNcsaCombinedLog(ZonedDateTime dateTime) {
        StringBuilder resultString = new StringBuilder(32);
        appendTwoDigitFormattedInt(dateTime.getDayOfMonth(), resultString).append("/");
        resultString.append(shortMonthNames[dateTime.getMonthValue() - 1]).append("/")
                    .append(dateTime.getYear()).append(":");
        appendTwoDigitFormattedInt(dateTime.getHour(), resultString).append(":");
        appendTwoDigitFormattedInt(dateTime.getMinute(), resultString).append(":");
        appendTwoDigitFormattedInt(dateTime.getSecond(), resultString).append(" ");
        resultString.append(timezoneString);
        return resultString.toString();
    }

    /**
     * @param theInt
     *     The integer value to append to the given string builder.
     * @param sb
     *     The string builder to append the integer value to.
     *
     * @return The same string builder passed in after the given integer has been appended to it, with a '0' prefixed if
     * necessary so that the appended value takes up two characters - e.g. passing in an integer value of 3 would cause
     * "03" to be appended to the given string builder.
     */
    protected StringBuilder appendTwoDigitFormattedInt(int theInt, StringBuilder sb) {
        if (theInt < 10)
            sb.append("0");
        return sb.append(theInt);
    }

    /**
     * @return The current local IP address.
     */
    protected String getLocalIpAddress() throws UnknownHostException {
        // This is an imperfect solution:
        //      http://stackoverflow.com/questions/9481865/how-to-get-ip-address-of-current-machine-using-java
        long currentTimeMillis = System.currentTimeMillis();
        long timeSinceLastCheck = currentTimeMillis - cachedLocalIpAddressLastCheckedTime;
        if ((cachedLocalIpAddress == null) || (timeSinceLastCheck > LOCAL_IP_ADDRESS_CACHE_CHECK_FREQUENCY_MILLIS)) {
            cachedLocalIpAddress = InetAddress.getLocalHost().getHostAddress();
            cachedLocalIpAddressLastCheckedTime = currentTimeMillis;
        }
        return cachedLocalIpAddress;
    }

    /**
     * @param request
     *     The request to log; will not be null. This is the request that came into the application. WARNING: The
     *     content may have been released already, so you cannot rely on any of the methods that return the payload
     *     content in any form.
     * @param finalResponseObject
     *     The {@link HttpResponse} that was the actual final response object sent to the client; may be null. NOTE:
     *     This should be preferred over {@code responseInfo} whenever both have overlapping data since this argument
     *     may have been modified by outbound handlers after being initially populated by {@code responseInfo}.
     * @param responseInfo
     *     {@link com.nike.riposte.server.http.ResponseInfo} object that was initially used to build {@code
     *     finalResponseObject}; may be null. NOTE: {@code finalResponseObject} may have been modified by other outbound
     *     handlers (e.g. compression/gzip), so if it is non-null it should be preferred over this where possible.
     *
     * @return A list of key/value pairs of data that should be added to the access log message following {@link
     * #combinedLogFormatPrefix(RequestInfo, HttpResponse, ResponseInfo)}. This will include some defaults specified in
     * this method as well as anything returned by {@link #customApplicationLogMessageExtras(RequestInfo, HttpResponse,
     * ResponseInfo, Long)}.
     */
    protected List<Pair<String, String>> logMessageAdditions(RequestInfo<?> request,
                                                             HttpResponse finalResponseObject,
                                                             ResponseInfo responseInfo,
                                                             Long elapsedTimeMillis) {
        String httpStatusCode = null;
        String contentLengthResponseHeader = null;
        String transferEncodingResponseHeader = null;
        String errorUid = null;
        String responseTraceId = null;
        String uncompressedRawContentLength = null;
        String finalContentLength = null;

        if (finalResponseObject != null && finalResponseObject.getStatus() != null)
            httpStatusCode = String.valueOf(finalResponseObject.getStatus().code());
        else if (responseInfo != null && responseInfo.getHttpStatusCode() != null)
            httpStatusCode = String.valueOf(responseInfo.getHttpStatusCode());

        HttpHeaders responseHeadersToUse = null;
        if (finalResponseObject != null && finalResponseObject.headers() != null)
            responseHeadersToUse = finalResponseObject.headers();
        else if (responseInfo != null)
            responseHeadersToUse = responseInfo.getHeaders();

        if (responseHeadersToUse != null) {
            contentLengthResponseHeader = responseHeadersToUse.get(CONTENT_LENGTH);
            transferEncodingResponseHeader = responseHeadersToUse.get(TRANSFER_ENCODING);
            errorUid = responseHeadersToUse.get("error_uid");
            responseTraceId = responseHeadersToUse.get(TRACE_ID);
        }

        if (responseInfo != null) {
            if (responseInfo.getUncompressedRawContentLength() != null)
                uncompressedRawContentLength = String.valueOf(responseInfo.getUncompressedRawContentLength());

            if (responseInfo.getFinalContentLength() != null)
                finalContentLength = String.valueOf(responseInfo.getFinalContentLength());
        }

        List<Pair<String, String>> logMessageAdditions = new ArrayList<>();

        logMessageAdditions.addAll(Arrays.asList(
            Pair.of("accept-Req", request.getHeaders().get(ACCEPT)),
            Pair.of("content-type-Req", request.getHeaders().get(CONTENT_TYPE)),
            Pair.of("content-length-Res", contentLengthResponseHeader),
            Pair.of("transfer_encoding-Res", transferEncodingResponseHeader),
            Pair.of("http_status_code-Res", httpStatusCode),
            Pair.of("error_uid-Res", errorUid),
            Pair.of(TRACE_ENABLED + "-Req", request.getHeaders().get(TRACE_ENABLED)),
            Pair.of(SPAN_ID + "-Req", request.getHeaders().get(SPAN_ID)),
            Pair.of(TRACE_ID + "-Req", request.getHeaders().get(TRACE_ID)),
            Pair.of(TRACE_ID + "-Res", responseTraceId),
            Pair.of("raw_content_length-Req", String.valueOf(request.getRawContentLengthInBytes())),
            Pair.of("raw_content_length-Res", uncompressedRawContentLength),
            Pair.of("final_content_length-Res", finalContentLength),
            Pair.of("elapsed_time_millis", (elapsedTimeMillis == null) ? null : elapsedTimeMillis.toString())
        ));

        List<Pair<String, String>> customApplicationLogMessageExtras =
            customApplicationLogMessageExtras(request, finalResponseObject, responseInfo, elapsedTimeMillis);
        if (customApplicationLogMessageExtras != null)
            logMessageAdditions.addAll(customApplicationLogMessageExtras);

        return logMessageAdditions;
    }

    /**
     * Allows applications to provide custom key/value pairs that should be added to the default access log message.
     * This custom data will appear in the log message after the data returned by {@link
     * #logMessageAdditions(RequestInfo, HttpResponse, ResponseInfo, Long)}. You can safely return null if you have no
     * extra info you want added to the log message.
     *
     * @param request
     *     The request to log; will not be null. This is the request that came into the application. WARNING: The
     *     content may have been released already, so you cannot rely on any of the methods that return the payload
     *     content in any form.
     * @param finalResponseObject
     *     The {@link HttpResponse} that was the actual final response object sent to the client - may be null in some
     *     circumstances (i.e. an error occurred during request processing). NOTE: This should be preferred over {@code
     *     responseInfo} whenever both have overlapping data since this argument may have been modified by outbound
     *     handlers after being initially populated by {@code responseInfo}.
     * @param responseInfo
     *     {@link com.nike.riposte.server.http.ResponseInfo} object that was initially used to build {@code
     *     finalResponseObject} - may be null in some circumstances (i.e. an error occurred during request processing).
     *     NOTE: {@code finalResponseObject} may have been modified by other outbound handlers (e.g. compression/gzip),
     *     so if it is non-null it should be preferred over this where possible.
     * @param elapsedTimeMillis
     *     Represents time difference from receiving the request to ending the request in milliseconds - may be null in
     *     some circumstances (i.e. an error occurred during request processing).
     */
    protected List<Pair<String, String>> customApplicationLogMessageExtras(RequestInfo<?> request,
                                                                           HttpResponse finalResponseObject,
                                                                           ResponseInfo responseInfo,
                                                                           Long elapsedTimeMillis) {
        return null;
    }

    /**
     * Converts the given list of key/value pairs to a single string. By default this uses {@link
     * #formatAdditionPairForLogMessage(Pair)} to turn each pair into a string and then joins them all together with a "
     * " single space as the delimiter.
     */
    protected String convertAdditionsToString(List<Pair<String, String>> logMessageAdditions) {
        if (logMessageAdditions == null)
            return "-";

        return logMessageAdditions.stream().map(this::formatAdditionPairForLogMessage).collect(Collectors.joining(" "));
    }

    /**
     * Converts the given key/value pair to a string. By default this converts nulls to hyphens '-' and then joins the
     * key and value with an equals sign between '='. So for example a key of foo and value of bar would be returned as
     * foo=bar. A key of foo and null value would be returned as foo=-
     */
    protected String formatAdditionPairForLogMessage(Pair<String, String> pair) {
        if (pair == null)
            return "-";

        String key = pair.getKey();
        String value = pair.getValue();

        if (key == null)
            key = "-";

        if (value == null)
            value = "-";

        return key + "=" + value;
    }

    /**
     * Combines the two strings into a single string. By default this just puts a single space " " between the two
     * strings.
     */
    protected String concatenateCombinedLogAndAdditionStrings(String combinedLogString,
                                                              String logMessageAdditionsAsString) {
        return combinedLogString + " " + logMessageAdditionsAsString;
    }
}
