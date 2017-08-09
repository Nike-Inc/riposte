# Riposte Sample Application - Kotlin-based TODO Server

* Build the sample by running the `./buildSample.sh` script.
* Launch the sample by running the `./runSample.sh` script. It will bind to port 8080. 
 
## Things to try

### Create A new TODO Item

`curl -X POST  -v http://localhost:8080/todos  -d '{ "name":"Amit", "task":"My task" }'`

### GET THE new TODO Item

`curl -v http://localhost:8080/todos/1`

### Update the new TODO Item
`curl -X PUT  -v http://localhost:8080/todos/1  -d '{"id":1, "name":"Amit", "task":"My task altered" }'`

### Delete the new TODO Item

`curl -v -X DELETE http://localhost:8080/todos/1`

## License

Riposte is released under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
