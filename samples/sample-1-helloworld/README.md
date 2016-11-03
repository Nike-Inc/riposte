# Riposte Sample Application - Hello, world

This submodule is the customary introductory sample that demonstrates how easy it is to create a microservice that returns "Hello, world" in response to a request at `http://localhost:8080/`.

* Build the sample by running the `./buildSample.sh` script.
* Launch the sample by running the `./runSample.sh` script. It will bind to port 8080. 
 
## Things to try

This sample is very basic. It has one endpoint that listens on `http://localhost:8080/` and responds with a `text/plain` payload of "Hello, world!".
  
There are some interesting things to note, however:

* The response headers will contain a trace ID. If you inspect the logs after making a request you'll see that each log message is tagged with that request's trace ID, allowing you to easily find all log messages associated with a given request.
* Riposte includes robust error handling baked in via [Backstopper](https://github.com/Nike-Inc/backstopper) that works for *all* errors, including ones usually associated with "the container". You can try the following to see this in action:
    * Trigger a 404 by navigating to `http://localhost:8080/doesnotexist`.
    * Trigger a 405 by hitting the Hello World endpoint on an HTTP method it does not support, like `POST`.
    * Note that the error contract is the same for all errors and is suitable for consumption by API callers. Also note that you can copy the error ID from the error response body or response headers and search for it in the application logs to jump directly to a single log message that contains full debugging information on the request.
* This sample application includes access logging (see the `Main` class for how this is setup), giving you a wealth of summary information about each request. You can pipe these access log messages to a separate file to keep them segregated from the normal application logs by configuring your SLF4J implementation (Logback in the case of this sample application) to send the messages for the `ACCESS_LOG` logger to a file of your choosing. This could be accomplished with some minor changes to the `logback.xml` file in this sample application.
* Each request will have a distributed trace log message output by [Wingtips](https://github.com/Nike-Inc/wingtips) to a SLF4J logger named `VALID_WINGTIPS_SPANS`, allowing you to have distributed trace information in your logs. Hooking up Zipkin is fairly trivial if you are in a Zipkin environment and would like your distributed traces sent to Zipkin - see [the relevant section of Wingtips documentation](https://github.com/Nike-Inc/wingtips/tree/master/wingtips-zipkin) for details on enabling Zipkin integration.
 
It's recommended that you use a REST client like [Postman](https://www.getpostman.com/) for making the requests so you can easily specify HTTP method, payloads, headers, etc, and fully inspect the response.

## More Info

See the base project documentation and Riposte repository source code and javadocs for all further information.

## License

Riposte is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
