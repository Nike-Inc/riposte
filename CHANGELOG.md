# Riposte Changelog / Release Notes

All notable changes to `Riposte` will be documented in this file. `Riposte` adheres to [Semantic Versioning](http://semver.org/).

## Why pre-1.0 releases?

Riposte is used heavily and is stable internally at Nike, however the wider community may have needs or use cases that we haven't considered. Therefore Riposte will live at a sub-1.0 version for a short time after its initial open source release to give it time to respond quickly to the open source community without ballooning the version numbers. Once its public APIs have stabilized again as an open source project it will be switched to the normal post-1.0 semantic versioning system.

#### 0.x Releases

- `0.9.x` Releases - [0.9.2](#092), [0.9.1](#091), [0.9.0](#090)
- `0.8.x` Releases - [0.8.3](#083), [0.8.2](#082), [0.8.1](#081), [0.8.0](#080)

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