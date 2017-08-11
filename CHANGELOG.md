# Riposte Changelog / Release Notes

All notable changes to `Riposte` will be documented in this file. `Riposte` adheres to [Semantic Versioning](http://semver.org/).

## Why pre-1.0 releases?

Riposte is used heavily and is stable internally at Nike, however the wider community may have needs or use cases that we haven't considered. Therefore Riposte will live at a sub-1.0 version for a short time after its initial open source release to give it time to respond quickly to the open source community without ballooning the version numbers. Once its public APIs have stabilized again as an open source project it will be switched to the normal post-1.0 semantic versioning system.

#### 0.x Releases

- `0.11.x` Releases - [0.11.0](#0110)
- `0.10.x` Releases - [0.10.1](#0101), [0.10.0](#0100)
- `0.9.x` Releases - [0.9.4](#094), [0.9.3](#093), [0.9.2](#092), [0.9.1](#091), [0.9.0](#090)
- `0.8.x` Releases - [0.8.3](#083), [0.8.2](#082), [0.8.1](#081), [0.8.0](#080)

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