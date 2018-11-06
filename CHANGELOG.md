# Riposte Changelog / Release Notes

All notable changes to `Riposte` will be documented in this file. `Riposte` adheres to [Semantic Versioning](http://semver.org/).

## Why pre-1.0 releases?

Riposte is used heavily and is stable internally at Nike, however the wider community may have needs or use cases that we haven't considered. Therefore Riposte will live at a sub-1.0 version for a short time after its initial open source release to give it time to respond quickly to the open source community without ballooning the version numbers. Once its public APIs have stabilized again as an open source project it will be switched to the normal post-1.0 semantic versioning system.

#### 0.x Releases

- `0.13.x` Releases - [0.13.1](#0131), [0.13.0](#0130)
- `0.12.x` Releases - [0.12.2](#0122), [0.12.1](#0121), [0.12.0](#0120) 
- `0.11.x` Releases - [0.11.2](#0112), [0.11.1](#0111), [0.11.0](#0110)
- `0.10.x` Releases - [0.10.1](#0101), [0.10.0](#0100)
- `0.9.x` Releases - [0.9.4](#094), [0.9.3](#093), [0.9.2](#092), [0.9.1](#091), [0.9.0](#090)
- `0.8.x` Releases - [0.8.3](#083), [0.8.2](#082), [0.8.1](#081), [0.8.0](#080)

## [0.13.1](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.13.1)

Released on 2018-11-06.

### Fixed

- Fixed the default error response body serializer to not attempt to serialize bodies that are already raw 
`CharSequence`s. This allows you to output HTML pages for error responses (for example), rather than being forced to 
use an object that is serialized to JSON.
    - Fixed by [Nathanial Myers][contrib_nmyers322] in pull request 
    [#108](https://github.com/Nike-Inc/riposte/pull/108). 
    For issue [#107](https://github.com/Nike-Inc/riposte/issues/107)

## [0.13.0](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.13.0)

Released on 2018-04-26.

### Potentially breaking changes

Some of the changes in version `0.13.0` can affect what is returned to the caller in some situations. Although these
changes are effectively bug fixes that bring Riposte more in line with the HTTP specification, and therefore should be 
invisible and fully backwards compatible changes for most Riposte users, they can technically change what callers have 
been receiving for some requests/responses, so it is strongly advised that you look over the changes below to determine 
what impact updating to `0.13.0` might have for your services. 

### Fixed

- Fixed `StandardEndpoint` responses to force-remove payloads for situations where the HTTP specification forbids 
returning a payload, including HTTP status code 204, 205, and 304 responses, and responses to HEAD requests (see 
[RFC 2616 Section 4.4](https://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.4) and 
[RFC 2616 Section 10.2.6](https://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.6)). Some of these were 
already being handled by Netty (204, 304), but now 205 and HEAD requests are included. 
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull requests [#102](https://github.com/Nike-Inc/riposte/pull/102)
    and [#103](https://github.com/Nike-Inc/riposte/pull/103).
- Fixed `StandardEndpoint`s to allow you to specify a non-zero content-length header for HEAD or 304 responses even 
though the actual payload is empty. See [RFC 7230 Section 3.3.2](https://tools.ietf.org/html/rfc7230#section-3.3.2).
If you explicitly specify a content-length header in your `ResponseInfo` then that will be honored, otherwise Riposte
will calculate content-length from whatever payload you provide (serialized the same way Riposte would serialize a 
normal GET 200 response) before dropping the payload for the actual response. *This allows you to simply add the `HEAD` 
method to the `Matcher` for your `GET` endpoint and you will have proper `HEAD` support without any further changes.* 
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull requests [#102](https://github.com/Nike-Inc/riposte/pull/102) 
    and [#103](https://github.com/Nike-Inc/riposte/pull/103).
- Fixed a bug where `ProxyRouterEndpoint` responses were adding a default `application/json` content-type header 
if the downstream system wasn't returning a content-type. `ProxyRouterEndpoint`s will no longer do this.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#102](https://github.com/Nike-Inc/riposte/pull/102).
- Fixed `ProxyRouterEndpoint` responses to not force chunked transfer-encoding. After this change, if the downstream 
returns a content-length and the payload has no chunked transfer encoding applied then that's what will reach the 
caller. This allows callers to know how big the payload is before it arrives, enabling progress bars (for example). 
Between this and the previous fix for content-type, Riposte no longer makes any changes to the `ProxyRouterEndpoint` 
response from the downstream.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#102](https://github.com/Nike-Inc/riposte/pull/102).   

### Added

- Added `DelegatedErrorResponseBody` - a simple implementation of `ErrorResponseBody` that allows you to delegate
serialization of the error contract to a different object. 

### Changed

- Changed the Netty pipeline used by Riposte to use a combined `HttpServerCodec` handler rather than separate 
`HttpRequestDecoder` and `HttpResponseEncoder` handlers. This allows Netty to detect and fix more HTTP specification
violations (e.g. forcing empty payloads on responses to HEAD requests) but is otherwise equivalent. You should
not notice any difference with this change unless your service uses a `PipelineCreateHook` that expects to find a
`HttpRequestDecoder` or `HttpResponseEncoder` handler in the pipeline - you would need to adjust it to look for the new
`HttpServerCodec` instead. 

## [0.12.2](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.12.2)

Released on 2018-04-18.

### Changed

- Changed error handling system to allow you to specify blank response payloads or delegate serialization to an object 
other than the `ErrorResponseBody` impl when using custom `RiposteErrorHandler` and/or `RiposteUnhandledErrorHandler` 
impls (specified via your `ServerConfig`). See the javadocs and source for `ErrorResponseBody.bodyToSerialize()`, and 
then `RiposteApiExceptionHandler.prepareFrameworkRepresentation(...)` and 
`RiposteUnhandledExceptionHandler.prepareFrameworkRepresentation(...)` for where you'd hook in.  
    - Changed by [Alexander Banker][contrib_scientificmethod] in pull request [#99](https://github.com/Nike-Inc/riposte/pull/99).

## [0.12.1](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.12.1)

Released on 2018-02-27.

### Changed

- Changed `TypesafeConfigServer` to allow you to override the `appId` and/or `environment` that are used to load
the properties files: see the new overridable `TypesafeConfigServer.getAppIdAndEnvironmentPair()` method.
    - Changed by [jcnorman48][contrib_jcnorman48] in pull request [#96](https://github.com/Nike-Inc/riposte/pull/96).

## [0.12.0](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.12.0)

Released on 2018-02-14.

### Potentially breaking changes

- `StandardEndpoint<InputType, OutputType>`s have been adjusted to throw a "missing expected content" HTTP status code 
400 error when the caller does not send a payload and `InputType` is anything other than `Void`. Previously the endpoint 
would be executed and you'd have to check `RequestInfo.getContent()` manually to see if the caller had sent a payload
and throw an error yourself to indicate a missing required payload. This change could result in endpoints throwing 400s 
to callers when they previously did not. Please double-check your `StandardEndpoint`s - if they have a non-`Void` input 
type but you want payloads to be optional then you can override the new `isRequireRequestContent()` endpoint method to 
return `false` to keep the previous behavior of the endpoint allowing missing payloads for non-`Void` input types. More 
details about this change can be found below.

### Added

- Added `EurekaVipAddressRoundRobinWithAzAffinityService` to the `riposte-service-registration-eureka` module. This 
service will round-robin requests to instances in the same availability zone as the current server. If there are no 
instances in the current availability zone, it will round-robin instances in all availability zones. Routing requests 
this way has the advantage of lower response times and lower data transfer costs.
    - Added by [cjha][contrib_cjha] in pull request [#77](https://github.com/Nike-Inc/riposte/pull/77).
- `StandardEndpoint<InputType, OutputType>`s will now throw a "missing expected content" HTTP status code 400 error if 
the `InputType` is not `Void` (or more specifically, if the `requestContentType()` method returns something besides 
`null` or `Void`). This means you no longer have to check if `RequestInfo.getContent()` is `null` for endpoints that 
specify an input type. i.e. after this feature addition if a caller does not send a payload to a 
`StandardEndpoint<SomeObject, _>` endpoint then they would receive a "missing expected content" 400 error, but if
the endpoint was defined `StandardEndpoint<Void, _>` then the endpoint would be executed normally. If you need to 
change this behavior for endpoints where the payload is truly optional you can override the endpoint's 
`isRequireRequestContent()` method to return `false`. 
    - Added by [Robert Abeyta][contrib_rabeyta] in pull request [#83](https://github.com/Nike-Inc/riposte/pull/83).   
- Added ability to use `byte[]` as an input type for `StandardEndpoint`, e.g. `StandardEndpoint<byte[], OutputType>`.
This allows you to specify that you require a payload (see previous change) but that you don't want any deserialization 
done, i.e. you want to handle the raw payload bytes in the endpoint yourself rather than have Riposte try to interpret 
it. Previously specifying `byte[]` as an input type would result in a deserialization error.
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#86](https://github.com/Nike-Inc/riposte/pull/86).
- Added support for automatic gzip/deflate decompression on incoming requests. This defaults to off for
`ProxyRouterEndpoint` so that proxies don't modify payloads as they pass through, but defaults to on for all other 
endpoint types so that payloads are decompressed before `StandardEndpoint`s execute and payloads deserialized into
whatever input type is needed for the endpoint. If you want to change the default decompression behavior for a given
endpoint you can override `Endpoint.isDecompressRequestPayloadAllowed()` to return whatever you need.  
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#84](https://github.com/Nike-Inc/riposte/pull/84).
- Added `ServerConfig.responseCompressionThresholdBytes()` to allow you to specify the payload size threshold after 
which response payloads will be automatically gzip/deflate compressed (assuming the caller supports compressed 
responses). This feature already existed but was previously hardcoded to 500 bytes.
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#84](https://github.com/Nike-Inc/riposte/pull/84).
- `ProxyRouterEndpoint`s now have the ability to turn off the automatic subspan around the downstream call (using 
the new `DownstreamRequestFirstChunkInfo.withPerformSubSpanAroundDownstreamCall(boolean)` method), and you can now 
indicate that you do *not* want tracing headers set on the downstream call (using the new 
`DownstreamRequestFirstChunkInfo.withAddTracingHeadersToDownstreamCall(boolean)` method). Previously Riposte would
always surround the downstream call with a subspan, and would always set tracing headers. These new options default
to the previous behavior, but you can override them with the new behavior methods.
    - Added by [Robert Abeyta][contrib_rabeyta] in pull request [#88](https://github.com/Nike-Inc/riposte/pull/88). 
- Added options to `ServerConfig` for specifying the max initial line length, max combined header line length, and max 
chunk size values used when decoding incoming HTTP requests. See `ServerConfig.httpRequestDecoderConfig()` for details.
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#91](https://github.com/Nike-Inc/riposte/pull/91).
- Added a `RequestInfo.addRequestAttribute(...)` that exposes how much time was spent waiting for the request payload
to arrive (time from first chunk to last chunk), and another for how much time was spent waiting for a connection to
be established to the downstream system for a `ProxyRouterEndpoint` request. Refer to 
`AccessLogStartHandler.REQUEST_PAYLOAD_TRANSFER_TIME_NANOS_REQUEST_ATTR_KEY` and 
`ProxyRouterEndpointExecutionHandler.DOWNSTREAM_CALL_CONNECTION_SETUP_TIME_NANOS_REQUEST_ATTR_KEY` in your code for the
request attribute keys. You may want to consider adding these to your access logs to help diagnose intermittent slow
requests.
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#94](https://github.com/Nike-Inc/riposte/pull/94).    
    
### Fixed

- Fixed `ResponseInfo.withCookies(...)` handling when sending responses to serialize the entire Netty `Cookie` object 
rather than just name and value. This means the other cookie parameters/properties are now honored and sent as 
expected, e.g. `Max-Age` and `HTTPOnly`. Previously any extra properties beyond cookie name and value were not being
sent.
    - Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#82](https://github.com/Nike-Inc/riposte/pull/82).
- Fixed Riposte interaction with Netty `ByteBuf` when reading incoming request content to use the 
`ByteBuf.readerIndex()` rather than assuming index 0. This should have no effect currently but is the correct way to
read content from `ByteBuf` (future-proofing).
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#85](https://github.com/Nike-Inc/riposte/pull/85).
- Fixed some corner cases where server request/response metrics were not being updated (e.g. caller dropping their
connection in the middle of the request). In particular this was causing the `inflight_requests` metric to grow over
time if these corner case calls were occurring.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#89](https://github.com/Nike-Inc/riposte/pull/89).
- Fixed some corner cases where access logs were not being output (e.g. caller dropping their connection in the middle 
of the request). 
    - Also removed span name from access logger output defaults - callers shouldn't be sending this and it's a waste of 
    characters in the log message.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#93](https://github.com/Nike-Inc/riposte/pull/93).
- Fixed `ProxyRouterEndpoint` to no longer send a `X-B3-SpanName` on downstream calls. Sending that header is not
best practice.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#90](https://github.com/Nike-Inc/riposte/pull/90).
- Fixed the Backstopper Riposte framework listener to detect exceptions for requests that have too-long headers and
map them to a [431 HTTP status code](https://tools.ietf.org/html/rfc6585#page-4) rather than a 400. Also adjusted the
error messages and metadata sent to the user in the response when HTTP decoding issues like too-long headers are 
encountered to be more informative.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#92](https://github.com/Nike-Inc/riposte/pull/92).

### Updated

- Updated Wingtips to version `0.14.1` from `0.11.2`. 
[Wingtips changelog](https://github.com/Nike-Inc/wingtips/blob/master/CHANGELOG.md)
    - Updated by [Nic Munroe][contrib_nicmunroe] in pull request [#90](https://github.com/Nike-Inc/riposte/pull/90).
- Updated Backstopper to version `0.11.4` from `0.11.1`.
[Backstopper changelog](https://github.com/Nike-Inc/backstopper/blob/master/CHANGELOG.md)
    - Updated by [Nic Munroe][contrib_nicmunroe] in pull request [#92](https://github.com/Nike-Inc/riposte/pull/92).

### Project Build

- Updated to Kotlin 1.2.21. Doesn't affect anything except the Kotlin sample.
    - Done by [Nic Munroe][contrib_nicmunroe] in pull request [#87](https://github.com/Nike-Inc/riposte/pull/87).

## [0.11.2](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.11.2)

Released on 2017-10-26.

### Updated

- Updated Netty to version `4.0.52.Final`.
    - Updated by [Todd Lisonbee][contrib_tlisonbee] in pull request [#73](https://github.com/Nike-Inc/riposte/pull/73).

## [0.11.1](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.11.1)

Released on 2017-10-26.

### Added

- Added the ability to set a default `SignatureCalculator` through `AsyncHttpClientHelper`. A `SignatureCalculator` is 
executed immediately before the HTTP request is fired, allowing last-second adjustments like creating a request 
signature header for auth purposes.
    - Added by [Robert Abeyta][contrib_rabeyta] in pull request [#74](https://github.com/Nike-Inc/riposte/pull/74).
- Added fluent style setters to `AsyncHttpClientHelper` for a default `SignatureCalculator` and 
`performSubSpanAroundDownstreamCalls` value.
    - Added by [Robert Abeyta][contrib_rabeyta] in pull request [#74](https://github.com/Nike-Inc/riposte/pull/74).
- Added utility methods to `AsyncNettyHelper` to enable easily creating `CompletableFuture`s with distributed tracing 
support built-in (with optional subspans) and optional circuit breaker protection.
    - Added by [Robert Abeyta][contrib_rabeyta] in pull request [#72](https://github.com/Nike-Inc/riposte/pull/72).
    
## [0.11.0](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.11.0)

Released on 2017-08-11.

### Added

- Added helper methods to `SignalFxAwareCodahaleMetricsCollector` for easily creating dimensioned metrics. 
	- Added by [Nic Munroe][contrib_nicmunroe] in pull request [#68](https://github.com/Nike-Inc/riposte/pull/68).
- Added Kotlin-based sample (see `samples/sample-2-kotlin-todoservice`).
    - Added by [amitsk][contrib_amitsk] in pull request [#67](https://github.com/Nike-Inc/riposte/pull/67). 
    
### Updated

- Updated Jackson dependency version to 2.8.9.
	- Updated by [Nic Munroe][contrib_nicmunroe] in pull request [#68](https://github.com/Nike-Inc/riposte/pull/68).    
    
### Project Build

- Upgraded to Gradle 4.1.
    - Done by [Nic Munroe][contrib_nicmunroe] in pull request [#68](https://github.com/Nike-Inc/riposte/pull/68).    

## [0.10.1](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.10.1)

Released on 2017-07-19.

### Fixed

- Fixed `ProxyRouterEndpoint` processing to include the port in the `Host` header for the downstream call if the port is not the default for the scheme as per the [HTTP spec](https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.23).
    - Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#62](https://github.com/Nike-Inc/riposte/pull/62).
    
### Added

- Added `SignalFxAwareCodahaleMetricsCollector` - a `CodahaleMetricsCollector` that uses SignalFx mechanisms for creating metrics so that they will be tagged with the appropriate global/unique dimensions for your application.
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#65](https://github.com/Nike-Inc/riposte/pull/65).

### Project Build

- Upgraded to Gradle 4.0.1.
    - Done by [Nic Munroe][contrib_nicmunroe] in pull request [#64](https://github.com/Nike-Inc/riposte/pull/64)

## [0.10.0](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.10.0)

Released on 2017-06-12.

### Fixed

- Fail-fast on too-big request sizes when possible. If content-length header is larger than the configured max request size we will fail immediately without processing the full request payload.
    - Reported by [Nic Munroe][contrib_nicmunroe]. Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#59](https://github.com/Nike-Inc/riposte/pull/59). For issue [#37](https://github.com/Nike-Inc/riposte/issues/37).
- Added methods to `RequestBuilderWrapper` to enable a user to set the wrapped content (url and method) and then subsequently updated the wrapped `AsyncHttpClient.BoundRequestBuilder` to keep the two in sync. This removed public fields and now they need to be accessed through getter/setter instead.
    - Reported by [Robert Abeyta][contrib_rabeyta]. Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#57](https://github.com/Nike-Inc/riposte/pull/57). For issue [#56](https://github.com/Nike-Inc/riposte/issues/56).
- Fixed more corner-case error handling related to `ProxyRouterEndpoint` endpoints. 
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#60](https://github.com/Nike-Inc/riposte/pull/60)
- Improved handling of invalid HTTP calls - they will now show up in access logs and metrics. 
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#60](https://github.com/Nike-Inc/riposte/pull/60)
    
### Added

- Added `getPathTemplate` method to `RequestInfo` to enable users to get the matching path template used for the request
     - Reported by [Ferhat Sobay][contrib_ferhatsb]. Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#58](https://github.com/Nike-Inc/riposte/pull/58). For issue [#55](https://github.com/Nike-Inc/riposte/issues/55).

## [0.9.4](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.9.4)

Released on 2017-04-26.

### Fixed

- Removed usage of Netty internal class (`io.netty.util.internal.OneTimeTask`). This prevented use of newer versions of Netty where that class no longer existed.
    - Reported by [Vic Bell][contrib_vicbell]. Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#52](https://github.com/Nike-Inc/riposte/pull/52). For issues [#50](https://github.com/Nike-Inc/riposte/issues/50) and [#51](https://github.com/Nike-Inc/riposte/issues/51).

## [0.9.3](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.9.3)

Released on 2017-04-20.

### Fixed

- Fixed distributed tracing headers for the downstream call when handling `ProxyRouterEndpoint`s. The parent span's information was being passed downstream rather than the sub-span created for the downstream call. The trace ID was correct, but if the downstream call continued the trace then its span would be pointing at the wrong parent. This was fixed to correctly use the sub-span.
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#47](https://github.com/Nike-Inc/riposte/pull/47)
- Fixed several issues related to `ProxyRouterEndpoint` handling:
    - Fixed incorrect Netty reference counting handling. This could lead to leaks or incorrect over-decrementing. If you saw `LEAK: ByteBuf.release() was not called before it's garbage-collected` or `IllegalReferenceCountException`s in your logs for data handled by Riposte (i.e. not your application-specific code) then this should now be fixed.
    - Fixed some race conditions that could lead to requests not being processed correctly.
    - Better internal corner-case error handling, leading to less log spam warnings.
    - Improved logging when corner case errors do pop up. If a request is handled in an unexpected way and you have the trace ID from the response headers, then the logs should give you better insight into what happened (e.g. the caller or downstream system dropping connection partway through a request).
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#48](https://github.com/Nike-Inc/riposte/pull/48)

## [0.9.2](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.9.2)

Released on 2017-04-18.

### Added

- Refactored the Codahale/Dropwizard metrics system (see `CodahaleMetricsListener`, `CodahaleMetricsListener.Builder`, `EndpointMetricsHandler`, and `SignalFxEndpointMetricsHandler` for details on the following):
    - You can now set custom metric names and customize `Timer`/`Histogram` creation (e.g. so you can use different `Reservoir`s).
    - Endpoint metrics handling has been split out into a `EndpointMetricsHandler` interface, allowing you full flexibility for handling endpoint-specific metrics related to requests and responses. A default impl (`EndpointMetricsHandlerDefaultImpl`) is used for Graphite-style conventions if you don't specify a different impl.
    - SignalFx support has been added via the `riposte-metrics-codahale-signalfx` module. Wire it up using `SignalFxReporterFactory` and `SignalFxEndpointMetricsHandler`.
    - All of this should be opt-in only, i.e. existing Riposte projects using the Codahale metrics system should continue to behave the same way.
    - Added by [Nic Munroe][contrib_nicmunroe] in pull request [#42](https://github.com/Nike-Inc/riposte/pull/42).

## [0.9.1](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.9.1)

Released on 2017-03-17.

### Fixed

- Fixed Riposte to honor the `ServerConfig.maxRequestSizeInBytes()` setting. You can use this to limit incoming request payload size at a global level, with the option to override on a per-endpoint basis via `Endpoint.maxRequestSizeInBytesOverride()`. Previously this `ServerConfig` setting existed but was ignored and had no effect.
	- Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#28](https://github.com/Nike-Inc/riposte/pull/28). For issue [#27](https://github.com/Nike-Inc/riposte/issues/27).
- Fixed `ProxyRouterEndpoint`s so that they will honor any incoming content-length header rather than the previous behavior of removing content-length and replacing it with transfer-encoding=chunked.
	- Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#30](https://github.com/Nike-Inc/riposte/pull/30). For issue [#29](https://github.com/Nike-Inc/riposte/issues/29).
- Fixed request handling to detect when Netty marks a request chunk as a decoder failure (i.e. bad/non-HTTP spec requests). These errors will now be mapped to an appropriate HTTP status code 400 response for the caller. Previously this was incorrectly resulting in a 404-not-found response.
 	- Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#33](https://github.com/Nike-Inc/riposte/pull/33). For issue [#32](https://github.com/Nike-Inc/riposte/issues/32).

## [0.9.0](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.9.0)

Released on 2017-02-28.

### Added

- Added ability for `RequestAndResponseFilter` to be executed before or after security validation depending on the value of `RequestAndResponseFilter.shouldExecuteBeforeSecurityValidation()`.
	- Added by [Robert Abeyta][contrib_rabeyta] in pull request [#20](https://github.com/Nike-Inc/riposte/pull/20). For issue [#15](https://github.com/Nike-Inc/riposte/issues/15).
- Added functionality to detect bad or incomplete HTTP calls and return an appropriate error to the caller in those cases. Timeout value controlled via the new `ServerConfig.incompleteHttpCallTimeoutMillis()` config option.  
	- Added by [Nic Munroe][contrib_nicmunroe] in pull request [#24](https://github.com/Nike-Inc/riposte/pull/24).	

### Updated

- Updated Backstopper dependency version to 0.11.1. This version added convenience constructor to `ApiErrorWithMetadata` that takes a vararg of `Pair<String, Object>` so that you can inline the extra metadata without having to create and populate a `Map` separately.
	- Updated by [Nic Munroe][contrib_nicmunroe] in pull request [#25](https://github.com/Nike-Inc/riposte/pull/25).

## [0.8.3](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.8.3)

Released on 2017-02-15.

### Updated

- Updated Fastbreak dependency version to 0.10.0. This should be an invisible update for most users, however it might require a minor refactor if you used Fastbreak's manual/callback mode in your code. Now, instead of calling `throwExceptionIfCircuitBreakerIsOpen()`, `handleEvent(...)`, and `handleException(...)` directly on `CircuitBreaker` you must first call `CircuitBreaker.newManualModeTask()` which will return a `ManualModeTask` interface. That `ManualModeTask` interface now contains the methods that were moved out of `CircuitBreaker`.
	- Updated by [Nic Munroe][contrib_nicmunroe].

## [0.8.2](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.8.2)

Released on 2016-12-12.

### Added

- Added `Forbidden403Exception` typed exception for 403/forbidden responses.
    - Added by [Paul Evans][contrib_palemorningdun] in pull request [#7](https://github.com/Nike-Inc/riposte/pull/7).
    
### Fixed
    
- Fixed request path to be URL decoded by default. This also fixes path parameters so they are URL decoded by default as well.
    - Fixed by [Robert Abeyta][contrib_rabeyta] in pull request [#12](https://github.com/Nike-Inc/riposte/pull/12). For issue [#11](https://github.com/Nike-Inc/riposte/issues/11).
- Fixed `CodahaleMetricsListener` to support short circuiting non-endpoint calls (e.g. short circuiting request filters).
    - Fixed by [Nic Munroe][contrib_nicmunroe] in pull request [#13](https://github.com/Nike-Inc/riposte/pull/13).

### Updated

- Updated Backstopper dependency version to 0.11.0 ([backstopper changelog](https://github.com/Nike-Inc/backstopper/blob/master/CHANGELOG.md)).
	- Updated by [Nic Munroe][contrib_nicmunroe] in pull request [#13](https://github.com/Nike-Inc/riposte/pull/13).

## [0.8.1](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.8.1)

Released on 2016-11-04.

### Updated

- Updated Backstopper dependency version to 0.9.2.
	- Updated by [Nic Munroe][contrib_nicmunroe].

## [0.8.0](https://github.com/Nike-Inc/riposte/releases/tag/riposte-v0.8.0)

Released on 2016-11-03.

### Added

- Initial open source code drop for Riposte.
	- Added by [Nic Munroe][contrib_nicmunroe].
	

[contrib_nicmunroe]: https://github.com/nicmunroe
[contrib_palemorningdun]: https://github.com/palemorningdun
[contrib_rabeyta]: https://github.com/rabeyta
[contrib_vicbell]: https://github.com/vicbell
[contrib_ferhatsb]: https://github.com/ferhatsb
[contrib_amitsk]: https://github.com/amitsk
[contrib_tlisonbee]: https://github.com/tlisonbee
[contrib_cjha]: https://github.com/cjha
[contrib_jcnorman48]: https://github.com/jcnorman48
[contrib_scientificmethod]: https://github.com/ScientificMethod
[contrib_nmyers322]: https://github.com/nmyers322
