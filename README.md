<img src="riposte_logo.png" />

# Riposte

[ ![Download](https://api.bintray.com/packages/nike/maven/riposte/images/download.svg) ](https://bintray.com/nike/maven/riposte/_latestVersion)
[![][travis img]][travis]
[![Code Coverage](https://img.shields.io/codecov/c/github/Nike-Inc/riposte/master.svg)](https://codecov.io/github/Nike-Inc/riposte?branch=master)
[![][license img]][license]

**Riposte is a Netty-based microservice framework for rapid development of production-ready HTTP APIs.** It includes robust features baked in like distributed tracing (provided by the Zipkin-compatible [Wingtips](https://github.com/Nike-Inc/wingtips)), error handling and validation (pluggable implementation with the default provided by [Backstopper](https://github.com/Nike-Inc/backstopper)), and circuit breaking (provided by [Fastbreak](https://github.com/Nike-Inc/fastbreak)). It works equally well as a fully-features microservice by itself (see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template)), or as an embedded HTTP server inside another application. 

## Quickstart

Java 8 is required.

***Please see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) for the recommended starter template project AND usage documentation.*** The template project is a production-ready microservice with a number of bells and whistles and the template project's [README.md](https://github.com/Nike-Inc/riposte-microservice-template/blob/master/README.md) contains in-depth usage information and should be consulted first when learning how to use Riposte.

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

## Template Microservice Project

It's been mentioned already, but it bears repeating: ***Please see the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) for the recommended starter template project AND usage documentation.*** The template project is a production-ready microservice with a number of bells and whistles and the template project's [README.md](https://github.com/Nike-Inc/riposte-microservice-template/blob/master/README.md) contains in-depth usage information and should be consulted first when learning how to use Riposte. The rest of the documentation below in *this* readme will be focused on the Riposte core libraries.

## Riposte libraries

Riposte is a collection of several libraries, mainly divided up based on dependencies. Note that only `riposte-spi` and `riposte-core` are required for a functioning Riposte server. Everything else is optional, but potentially useful depending on the needs of your application:

* [riposte-spi](riposte-spi/) - Contains the main interfaces and classes necessary to define the interface for a Riposte server.
* [riposte-core](riposte-core/) - Builds on `riposte-spi` to provide a fully functioning Riposte server.
* [riposte-async-http-client](riposte-async-http-client/) - Contains `AsyncHttpClientHelper`, an HTTP client for performing async nonblocking calls using `CompletableFuture`s with distributed tracing baked in.
* [riposte-metrics-codahale](riposte-metrics-codahale/) - Contains metrics support for Riposte using the `io.dropwizard` version of Codahale metrics.
* [riposte-auth](riposte-auth/) - Contains a few implementations of the Riposte `RequestSecurityValidator`, e.g. for basic auth and other security schemes.
* [riposte-guice](riposte-guice/) - Contains helper classes for seamlessly integrating Riposte with Google Guice.
* [riposte-typesafe-config](riposte-typesafe-config/) - Contains helper classes for seamlessly integrating Riposte with Typesafe Config for properties management.
* [riposte-guice-typesafe-config](riposte-guice-typesafe-config/) - Contains helper classes that require both Google Guice *and* Typesafe Config.
* [riposte-archaius](riposte-archaius/) - Contains Riposte-related classes that require the Netflix Archaius dependency for properties management. 
* [riposte-service-registration-eureka](riposte-service-registration-eureka/) - Contains helper classes for easily integrating Riposte with Netflix's Eureka service registration system.
* [riposte-servlet-api-adapter](riposte-servlet-api-adapter/) - Contains `HttpServletRequest` and `HttpServletResponse` adapters for reusing Servlet-based utilities in Riposte.

These libraries are all deployed to Maven Central and JCenter and can be pulled into your project by referencing the relevant dependency: `com.nike.riposte:[riposte-lib-artifact-name]:[version]`.

## Full core libraries documentation

Full documentation on the Riposte libraries will be coming eventually. In the meantime the javadocs for Riposte classes are fairly fleshed out and give good guidance, and the [template microservice project](https://github.com/Nike-Inc/riposte-microservice-template) is a reasonable user guide. Here are some important classes to get you started:

* `com.nike.riposte.server.Server` - The Riposte server class. Binds to a port and listens for incoming HTTP requests. Uses `ServerConfig` for all configuration purposes.
* `com.nike.riposte.server.config.ServerConfig` - Responsible for configuring a Riposte server. There are lots of options and the javadocs explain what everything does and recommended usage.
* `com.nike.riposte.server.http.StandardEndpoint` - A "typical" endpoint where you receive the full request and provide a full response. The javadocs in `StandardEndpoint`'s class hierarchy (`com.nike.riposte.server.http.NonblockingEndpoint` and `com.nike.riposte.server.http.Endpoint`) are worth reading as well for usage guidelines and to see what endpoint options are available. 
* `com.nike.riposte.server.http.ProxyRouterEndpoint` - A "proxy" or "router" style endpoint where you control the "first chunk" of the downstream request (downstream host, port, headers, path, query params, etc) and the payload is streamed to the destination immediately as chunks come in from the caller. The response is similarly streamed back to the caller immediately as chunks come back from the downstream server. This is incredibly efficient and fast, allowing you to provide proxy/routing capabilities on tiny servers without any fear of large payloads causing OOM, the whole of Java at your fingertips for implementing complex routing logic, and all while enjoying sub-millisecond lag times added by the Riposte server.  

<a name="license"></a>
## License

Riposte is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)

[travis]:https://travis-ci.org/Nike-Inc/riposte
[travis img]:https://api.travis-ci.org/Nike-Inc/riposte.svg?branch=master

[license]:LICENSE.txt
[license img]:https://img.shields.io/badge/License-Apache%202-blue.svg
