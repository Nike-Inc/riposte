<img src="riposte_logo.png" />

# Riposte

[ ![Download](https://api.bintray.com/packages/nike/maven/riposte/images/download.svg) ](https://bintray.com/nike/maven/riposte/_latestVersion)
[![][travis img]][travis]
[![Code Coverage](https://img.shields.io/codecov/c/github/Nike-Inc/riposte/master.svg)](https://codecov.io/github/Nike-Inc/riposte?branch=master)
[![][license img]][license]

**Riposte is a Netty-based microservice framework for rapid development of production-ready HTTP APIs.** It includes robust features baked in like distributed tracing (provided by the Zipkin-compatible [Wingtips](https://github.com/Nike-Inc/wingtips)), error handling and validation (pluggable implementation with the default provided by [Backstopper](https://github.com/Nike-Inc/backstopper)), and circuit breaking (provided by [Fastbreak](https://github.com/Nike-Inc/fastbreak)). 

## Quickstart

Java 8 is required.

The following class is a simple Java application containing a fully-functioning Riposte server. You can hit this server by calling `http://localhost:8080/hello` and it will respond with a `text/plain` payload of `Hello, world!`.

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

The [Hello World Sample](samples/sample-1-helloworld/) is similar to this but contains a few more niceties, and that sample's [README.md](samples/sample-1-helloworld/README.md) includes information on some of the features you can expect from Riposte.

## Full documentation coming soon

In the meantime the javadocs for Riposte classes are fairly fleshed out and give good guidance. Here are some important classes to get you started:

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
