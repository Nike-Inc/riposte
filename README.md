<img src="riposte_logo.png" />

# Riposte

[![Maven Central][maven_central_img]][maven_central]
[![Build][gh_action_build_img]][gh_action_build]
[![Code Coverage][codecov_img]][codecov]
[![License][license img]][license]

**Riposte is a Netty-based microservice framework for rapid development of production-ready HTTP APIs.** It includes robust features baked in like distributed tracing (provided by the Zipkin-compatible [Wingtips](https://github.com/Nike-Inc/wingtips)), error handling and validation (pluggable implementation with the default provided by [Backstopper](https://github.com/Nike-Inc/backstopper)), and circuit breaking (provided by [Fastbreak](https://github.com/Nike-Inc/fastbreak)). It works equally well as a fully-featured microservice by itself (see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template)), or as an embedded HTTP server inside another application. 

## Quickstart

Java 8 is required.

***Please see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) for the recommended starter template project AND usage documentation.*** The template project is a production-ready microservice with a number of bells and whistles and the template project's [README.md](https://github.com/Nike-Inc/riposte-microservice-template/blob/main/README.md) contains in-depth usage information and should be consulted first when learning how to use Riposte.

That said, the following class is a simple Java application containing a fully-functioning Riposte server. It represents the minimal code necessary to run a Riposte server. You can hit this server by calling `http://localhost:8080/hello` and it will respond with a `text/plain` payload of `Hello, world!`.

``` java
public class MyAppMain {

    public static void main(String[] args) throws Exception {
        Server server = new Server(new AppServerConfig());
        server.startup();
    }

    public static class AppServerConfig implements ServerConfig {
        private final Collection<Endpoint<?>> endpoints = Collections.singleton(new HelloWorldEndpoint());

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }
    }

    public static class HelloWorldEndpoint extends StandardEndpoint<Void, String> {
        @Override
        public Matcher requestMatcher() {
            return Matcher.match("/hello");
        }

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request,
                                                               Executor longRunningTaskExecutor,
                                                               ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder("Hello, world!")
                            .withDesiredContentWriterMimeType("text/plain")
                            .build()
            );
        }
    }

}
```

The [Hello World Sample](samples/sample-1-helloworld/) is similar to this but contains a few more niceties, and that sample's [README.md](samples/sample-1-helloworld/README.md) includes information on some of the features you can expect from Riposte, but again, please see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) for the recommended starter template project *and* usage documentation.

### Riposte usage with other JVM-based languages

Since Riposte is straight Java 8 with no bytecode manipulation, plugins, or other magic required it works seamlessly with whatever JVM language you prefer. Here's the same hello world app from above, but this time in Kotlin:

``` kotlin
fun main(args : Array<String>) {
    val server = Server(AppServerConfig)
    server.startup()
}

object AppServerConfig : ServerConfig {
    private val endpoints = Collections.singleton(HelloWorldEndpoint)

    override fun appEndpoints(): Collection<Endpoint<*>> {
        return endpoints
    }
}

object HelloWorldEndpoint : StandardEndpoint<Void, String>() {
    override fun requestMatcher(): Matcher {
        return Matcher.match("/hello")
    }

    override fun execute(request: RequestInfo<Void>,
                         longRunningTaskExecutor: Executor,
                         ctx: ChannelHandlerContext
    ): CompletableFuture<ResponseInfo<String>> {

        return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder("Hello, world!")
                        .withDesiredContentWriterMimeType("text/plain")
                        .build()
        )
    }
}
```

And again in Scala:

``` scala
object Main extends App {
  val server = new Server(AppServerConfig)
  server.startup()
}

object AppServerConfig extends ServerConfig {
  val endpoints: java.util.Collection[Endpoint[_]] = java.util.Collections.singleton(HelloWorldEndpoint)

  override def appEndpoints(): java.util.Collection[Endpoint[_]] = endpoints
}

object HelloWorldEndpoint extends StandardEndpoint[Void, String] {
  override def requestMatcher(): Matcher = Matcher.`match`("/hello")

  override def execute(
    request: RequestInfo[Void],
    longRunningTaskExecutor: Executor,
    ctx: ChannelHandlerContext): CompletableFuture[ResponseInfo[String]] =
  {
    CompletableFuture.completedFuture(
      ResponseInfo.newBuilder("Hello, world!")
        .withDesiredContentWriterMimeType("text/plain")
        .build()
    )
  }
}
```

## Template Microservice Project

It's been mentioned already, but it bears repeating: ***Please see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) for the recommended starter template project AND usage documentation.*** The template project is a production-ready microservice with a number of bells and whistles and the template project's [README.md](https://github.com/Nike-Inc/riposte-microservice-template/blob/main/README.md) contains in-depth usage information and should be consulted first when learning how to use Riposte. The rest of the documentation below in *this* readme will be focused on the Riposte core libraries.

## Riposte Libraries

Riposte is a collection of several libraries, mainly divided up based on dependencies. Note that only `riposte-spi` and `riposte-core` are required for a functioning Riposte server. Everything else is optional, but potentially useful depending on the needs of your application:

* [riposte-spi](riposte-spi/) - Contains the main interfaces and classes necessary to define the interface for a Riposte server.
* [riposte-core](riposte-core/) - Builds on `riposte-spi` to provide a fully functioning Riposte server.
* [riposte-async-http-client2](riposte-async-http-client2/) - Contains 
[`AsyncHttpClientHelper`](https://github.com/Nike-Inc/riposte/blob/main/riposte-async-http-client2/src/main/java/com/nike/riposte/client/asynchttp/AsyncHttpClientHelper.java), 
an HTTP client for performing async nonblocking calls using `CompletableFuture`s with distributed tracing baked in.
This is a wrapper around the [Async Http Client](https://github.com/AsyncHttpClient/async-http-client) libraries.
* [riposte-async-http-client](riposte-async-http-client/) - **DEPRECATED - Please use riposte-async-http-client2.** 
The older version of `AsyncHttpClientHelper`, built around the 
[Ning AsyncHttpClient](https://github.com/ning/async-http-client) (which eventually became the new 
[Async Http Client](https://github.com/AsyncHttpClient/async-http-client) project which `riposte-async-http-client2`
is based on).
* [riposte-metrics-codahale](riposte-metrics-codahale/) - Contains metrics support for Riposte using the `io.dropwizard` version of Codahale metrics.
* [riposte-metrics-codahale-signalfx](riposte-metrics-codahale-signalfx/) - Contains SignalFx-specific extensions of the `riposte-metrics-codahale` library module.
* [riposte-auth](riposte-auth/) - Contains a few implementations of the Riposte [`RequestSecurityValidator`](https://github.com/Nike-Inc/riposte/blob/main/riposte-spi/src/main/java/com/nike/riposte/server/error/validation/RequestSecurityValidator.java), e.g. for basic auth and other security schemes.
* [riposte-guice](riposte-guice/) - Contains helper classes for seamlessly integrating Riposte with Google Guice.
* [riposte-typesafe-config](riposte-typesafe-config/) - Contains helper classes for seamlessly integrating Riposte with Typesafe Config for properties management.
* [riposte-guice-typesafe-config](riposte-guice-typesafe-config/) - Contains helper classes that require both Google Guice *and* Typesafe Config.
* [riposte-archaius](riposte-archaius/) - Contains Riposte-related classes that require the Netflix Archaius dependency for properties management. 
* [riposte-service-registration-eureka](riposte-service-registration-eureka/) - Contains helper classes for easily integrating Riposte with Netflix's Eureka service registration system.
* [riposte-servlet-api-adapter](riposte-servlet-api-adapter/) - Contains `HttpServletRequest` and `HttpServletResponse` adapters for reusing Servlet-based utilities in Riposte.

These libraries are all deployed to Maven Central and can be pulled into your project by referencing the relevant dependency: `com.nike.riposte:[riposte-lib-artifact-name]:[version]`.

## Full Core Libraries Documentation

Full documentation on the Riposte libraries will be coming eventually. In the meantime the javadocs for Riposte classes are fairly fleshed out and give good guidance, and the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) is a reasonable user guide. Here are some important classes to get you started:

* [`com.nike.riposte.server.Server`](https://github.com/Nike-Inc/riposte/blob/main/riposte-core/src/main/java/com/nike/riposte/server/Server.java) - The Riposte server class. Binds to a port and listens for incoming HTTP requests. Uses [`ServerConfig`](https://github.com/Nike-Inc/riposte/blob/main/riposte-spi/src/main/java/com/nike/riposte/server/config/ServerConfig.java) for all configuration purposes.
* [`com.nike.riposte.server.config.ServerConfig`](https://github.com/Nike-Inc/riposte/blob/main/riposte-spi/src/main/java/com/nike/riposte/server/config/ServerConfig.java) - Responsible for configuring a Riposte server. There are lots of options and the javadocs explain what everything does and recommended usage.
* [`com.nike.riposte.server.http.StandardEndpoint`](https://github.com/Nike-Inc/riposte/blob/main/riposte-core/src/main/java/com/nike/riposte/server/http/StandardEndpoint.java) - A "typical" endpoint where you receive the full request and provide a full response. The javadocs in `StandardEndpoint`'s class hierarchy ([`com.nike.riposte.server.http.NonblockingEndpoint`](https://github.com/Nike-Inc/riposte/blob/main/riposte-spi/src/main/java/com/nike/riposte/server/http/NonblockingEndpoint.java) and [`com.nike.riposte.server.http.Endpoint`](https://github.com/Nike-Inc/riposte/blob/main/riposte-spi/src/main/java/com/nike/riposte/server/http/Endpoint.java)) are worth reading as well for usage guidelines and to see what endpoint options are available. 
* [`com.nike.riposte.server.http.ProxyRouterEndpoint`](https://github.com/Nike-Inc/riposte/blob/main/riposte-core/src/main/java/com/nike/riposte/server/http/ProxyRouterEndpoint.java) - A "proxy" or "router" style endpoint where you control the "first chunk" of the downstream request (downstream host, port, headers, path, query params, etc) and the payload is streamed to the destination immediately as chunks come in from the caller. The response is similarly streamed back to the caller immediately as chunks come back from the downstream server. This is incredibly efficient and fast, allowing you to provide proxy/routing capabilities on tiny servers without any fear of large payloads causing OOM, the whole of Java at your fingertips for implementing complex routing logic, and all while enjoying sub-millisecond lag times added by the Riposte server.  

## Performance Comparisons

To give you an idea of how Riposte performs we did some comparisons against a few popular well-known stacks in a handful of scenarios. These tests show what you can expect from each stack under normal circumstances - excessive tuning was *not* performed, just some basic configuration to get everything on equal footing (part of Riposte's philosophy is that you should get excellent performance without a lot of hassle). 

See the [test environment and setup notes](#perf_test_notes) section for more detailed information on how the performance testing was conducted.  
 
<a name="perf_test_hello_world"></a>
### Raw hello world performance

This test measures the simplest "hello world" type API, with a single endpoint that immediately returns a 200 HTTP response with a static string for the response payload.

**NOTE: Spring Boot was using the Undertow embedded container for maximum performance in these tests. The default Tomcat container was significantly worse than the numbers shown here.**

| Concurrent Call Spammers | Stack | Realized Requests Per Second | Avg latency (millis) | 50% latency (millis) | 90% latency (millis) | 99% latency (millis) | CPU Usage |
| -------------: | :------------- | :------------- | :------------- | :------------- | :------------- | :------------- | :------------- |
| 1 | Riposte | 7532 | 0.138 | 0.131 | 0.135 | 0.148 | 36% |
| 1 | Spring&nbsp;Boot | 4868 | 0.220 | 0.205 | 0.220 | 0.305 | 34% |
| | | | | | | | |
| | | | | | | | |
| 3 | Riposte | 18640 | 0.176 | 0.154 | 0.187 | 0.246 | 92% |
| 3 | Spring&nbsp;Boot | 11888 | 0.307 | 0.238 | 0.284 | 0.980 | 75% |
| | | | | | | | |
| | | | | | | | |
| 5 | Riposte | 22038 | 0.269 | 0.222 | 0.286 | 1.13 | 99%+ |
| 5 | Spring&nbsp;Boot | 12930 | 0.775 | 0.358 | 1.39 | 8.25 | 84% |
| | | | | | | | |
| | | | | | | | |
| 10 | Riposte | 23136 | 1.08 | 0.251 | 3.18 | 8.32 | 99%+ |
| 10 | Spring&nbsp;Boot | 13862 | 2.26 | 0.552 | 6.61 | 24.24 | 92% |
| | | | | | | | |
| | | | | | | | |
| 15 | Riposte | 23605 | 1.38 | 0.429 | 4.39 | 9.56 | 99%+ |
| 15 | Spring&nbsp;Boot | 14062 | 3.26 | 0.817 | 9.14 | 35.51 | 93% |

<a name="perf_test_async"></a>
### Async non-blocking endpoint performance

This test measures how well the stacks perform when executing asynchronous non-blocking tasks. For a real service this might mean using a NIO client for database or HTTP calls (e.g. Riposte's [`AsyncHttpClientHelper`](https://github.com/Nike-Inc/riposte/blob/main/riposte-async-http-client/src/main/java/com/nike/riposte/client/asynchttp/ning/AsyncHttpClientHelper.java)) to do the vast majority of the endpoint's work, where the endpoint is just waiting for data from an outside process (and therefore NIO allows us to wait without using a blocking thread). 

For these tests we simulate that scenario by returning `CompletableFutures` that are completed with a "hello world" payload after a 130 millisecond delay using a scheduler. In the case of Riposte we can reuse the built-in Netty scheduler via `ctx.executor().schedule(...)` calls, and for Spring Boot we reuse a scheduler created via `Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2)` to match the Netty scheduler as closely as possible. In both cases the thread count on the application remains small and stable even when handling thousands of concurrent requests. 

**NOTE: Spring Boot was using the Undertow embedded container for maximum performance in these tests. The default Tomcat container was significantly worse than the numbers shown here.**

*Each call in the tests below has a 130 millisecond scheduled delay before being completed and returned to the spammer, so 130 millis is the ideal latency.*
 
| Concurrent Call Spammers | Stack | Realized Requests Per Second | Avg latency (millis) | 90% latency (millis) | 95% latency (millis) | CPU Usage |
| -------------: | :------------- | :------------- | :------------- | :------------- | :------------- | :------------- |
| 700 | Riposte | 5356 | 130 | 131 | 131 | 29% |
| 700 | Spring&nbsp;Boot | 5206 | 134 | 140 | 143 | 64% |
| | | | | | | |
| | | | | | | |
| 1400 | Riposte | 10660 | 131 | 132 | 134 | 57% |
| 1400 | Spring&nbsp;Boot | 8449 | 165 | 181 | 188 | 97% |
| | | | | | | |
| | | | | | | |
| 2100 | Riposte | 15799 | 132 | 136 | 139 | 80% |
| 2100 | Spring&nbsp;Boot | 8489 | 247 | 267 | 274 | 99% |
| | | | | | | |
| | | | | | | |
| 2800 | Riposte | 20084 | 138 | 149 | 157 | 94% |
| 2800&nbsp;(Not&nbsp;Attempted) | Spring&nbsp;Boot | N/A | N/A | N/A | N/A | N/A |
| | | | | | | |
| | | | | | | |
| 3500 | Riposte | 21697 | 160 | 187 | 198 | 99% |
| 3500&nbsp;(Not&nbsp;Attempted) | Spring&nbsp;Boot | N/A | N/A | N/A | N/A | N/A |

<a name="perf_test_proxy_router"></a>
### Proxy/router performance

One of Riposte's endpoint types ([`ProxyRouterEndpoint`](https://github.com/Nike-Inc/riposte/blob/main/riposte-core/src/main/java/com/nike/riposte/server/http/ProxyRouterEndpoint.java)) is for proxy and/or routing use cases where you can adjust request and/or response headers and determine the destination of the call, but otherwise leave the payload alone. This allows Riposte to stream chunks to and from the destination and caller as they become available rather than waiting for the entire request/response to enter memory. The end result is a system that lets you use Riposte as a proxy or router on very low-end hardware and still get excellent performance; payload size essentially doesn't matter - e.g. you can act as a proxy/router piping gigabyte payloads between systems on a box that only has a few hundred megabytes of RAM allocated to Riposte. It also doesn't matter if the downstream service takes 5 seconds or 5 milliseconds to respond since Riposte uses nonblocking I/O under the hood and you won't end up with an explosion of threads.

These kinds of robust proxy/routing features are not normally available in Java microservice stacks, so to provide a performance comparison we put Riposte up against industry-leading NGINX. Riposte does not win the raw performance crown vs. NGINX (it's unlikely any Java-based solution could), however:
 
* Riposte comes with distributed tracing and circuit breaking baked into its proxy/routing features, both critical features in a distributed microservice environment.
* You have the full wealth of the Java ecosystem for implementing your proxy/routing logic, allowing you to easily add features like service discovery, load balancing, auth/security schemes, custom circuit breaker logic, dynamic configuration, or anything else you can dream up.
    * This is not something to be discounted, especially if you're in an environment with a lot of Java expertise but not much NGINX/C/C++/Lua expertise.
* Creating, implementing, and wiring up `ProxyRouterEndpoint` endpoints in Riposte is as simple as the `StandardEndpoint` endpoints.
* Mix and match your proxy/router endpoints with standard REST-style endpoints in the same application - again, in Riposte it's just a different endpoint type.
* Riposte required less tuning to achieve its max performance in these tests, including zero operating-system-level tweaks.
* Riposte does manage a respectable showing against NGINX, all things considered.
  
*Each call in the tests below is proxied through the stack to a backend service that has a 130 millisecond scheduled delay before responding, so 130 millis is the ideal latency.*  
  
| Concurrent Call Spammers | Stack | Realized Requests Per Second | Avg latency (millis) | 90% latency (millis) | 95% latency (millis) | CPU Usage |
| -------------: | :------------- | :------------- | :------------- | :------------- | :------------- | :------------- |  
| 140 | Riposte | 1068 | 131 | 132 | 132 | 18% |
| 140 | NGINX | 1070 | 130 | 131 | 131 | 6% |
| | | | | | | |
| | | | | | | |
| 700 | Riposte | 5256 | 133 | 135 | 139 | 77% |
| 700 | NGINX | 5330 | 131 | 132 | 132 | 21% |
| | | | | | | |
| | | | | | | |
| 1050 [†](#proxy_test_riposte_max_footnote) | Riposte | 7530 | 139 | 150 | 156 | 95% |
| 1050&nbsp;(Not&nbsp;Attempted) | NGINX | N/A | N/A | N/A | N/A | N/A |
| | | | | | | |
| | | | | | | |
| 2240&nbsp;(Not&nbsp;Attempted) | Riposte | N/A | N/A | N/A | N/A | N/A |
| 2240 [††](#proxy_test_nginx_max_footnote) | NGINX | 15985 | 139 | 138 | 140 | 57% |

#### Proxy/router test footnotes

<a name="proxy_test_riposte_max_footnote"></a>
† - Riposte maxed out on these tests at about 7500 RPS. The bottleneck was CPU usage. NGINX wasn't tested at this throughput, but would have performed very well given its max.

<a name="proxy_test_nginx_max_footnote"></a>
†† - NGINX maxed out on these tests at about 16000 RPS. The bottleneck was *not* CPU usage, but something else. Increasing concurrent spammers simply caused larger and larger bursts of multi-second response times - even at 2240 concurrent spammers there was a small number of outliers which caused the average to jump above the 90th percentile. Throughput could not be pushed above 16000 RPS even though there was plenty of CPU headroom.
  
<a name="perf_test_notes"></a>
### Performance comparison test environment and setup notes

* All tests were performed on an Amazon c4.large EC2 instance spun up with the basic Amazon Linux.
* [Gatling](http://gatling.io/) and [wrk](https://github.com/wg/wrk) were used as the benchmarking/load generating frameworks. 
* Spring Boot was chosen for Java microservice API performance comparisons as it is a well known, well supported, popular stack that is also geared towards spinning up projects quickly and easily. **We used Undertow as the Spring Boot embedded container as it had the best performance characteristics.** Also note that these performance comparisons should not be construed as testing Netty vs. Undertow, or even Riposte vs. Undertow. There are performance comparisons that directly exercise raw Netty and raw Undertow ([example](https://www.techempower.com/benchmarks/#section=intro&hw=ph&test=json)), but the ones here are specifically testing Spring Boot and Riposte, not the underlying framework/containers.
* NGINX was chosen for proxy/router performance comparisons as it is a well known, well supported, popular solution for high performance proxy/routing needs.
* Logging output was turned off for each stack via standard config mechanisms (we're measuring what the stacks can do, not logging frameworks or disk I/O capabilities).
* The Java stacks were started with a few "one-size-fits-many" JVM options we've found to be a good starting place for many use cases - new generation size 1/3 of total JVM memory, CMS garbage collector, etc. The JVM args used for these tests on the c4.large EC2 instance: `-Xmx2607876k -Xms2607876k -XX:NewSize=869292k -XX:+UseConcMarkSweepGC -XX:SurvivorRatio=6 -server`
* In order for NGINX to be able to handle some of the load we were sending it without "Too many open files" errors we had to adjust the operating system limits and the NGINX configuration. We also turned on connection pooling and keep-alive connections for NGINX in the config.
* To test maximum performance capabilities the tests are designed with "concurrent spammers" where a certain number of concurrent callers are calling the stack being tested as quickly as they can - as soon as a caller receives a response it sends out another request. 
* Keep-alive connections were used for the concurrent spammers and for proxied backend calls when doing proxy/router tests.
* All stacks were given warm-up periods to adjust to the test loads before measurement began. Measured test periods lasted 20 minutes for each individual test.
* Disabling the core distributed tracing functionality in Riposte is not something that is expected or desired and would fall under the "excessive tuning" category so it was left on. Since logging is off the result is essentially wasted CPU cycles for Riposte, but it does mean that when logging is turned on for normal microservices that distributed tracing is effectively "free" relative to these performance tests, where adding distributed tracing to the other stacks would require extra work and result in worse performance compared to these tests.

<a name="license"></a>
## License

Riposte is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

[maven_central]:https://search.maven.org/search?q=g:com.nike.riposte
[maven_central_img]:https://maven-badges.herokuapp.com/maven-central/com.nike.riposte/riposte-core/badge.svg?style=flat

[gh_action_build]:https://github.com/Nike-Inc/riposte/actions/workflows/build.yml
[gh_action_build_img]:https://github.com/Nike-Inc/riposte/actions/workflows/build.yml/badge.svg

[codecov]:https://codecov.io/github/Nike-Inc/riposte?branch=main
[codecov_img]:https://img.shields.io/codecov/c/github/Nike-Inc/riposte/main.svg

[license]:LICENSE.txt
[license img]:https://img.shields.io/badge/License-Apache%202-blue.svg
