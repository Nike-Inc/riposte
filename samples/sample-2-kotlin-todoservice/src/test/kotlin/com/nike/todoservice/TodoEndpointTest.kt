package com.nike.todoservice

import com.google.common.truth.Truth
import com.nike.riposte.server.Server
import io.kotlintest.ProjectConfig
import io.kotlintest.specs.FeatureSpec
import io.restassured.RestAssured.given
import io.restassured.http.ContentType.JSON
import io.restassured.specification.RequestSpecification
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.IOException
import java.net.ServerSocket

class TodoEndpointTest : FeatureSpec()  {

    class AppServerConfigForTesting(private val port: Int) : AppServerConfig() {
        override fun endpointsPort(): Int {
            return port
        }
    }

    init {

        feature("Operations to create/modify TODO Items") {

            scenario("A TODO Item is created successfully") {
                val response =
                        given()
                            .port(serverConfig!!.endpointsPort())
                            .request().contentType("application/json")
                            .body(postPayload)
                        .When()
                            .post(path)
                        .then()
                            .statusCode(201)
                            .contentType(JSON)
                            .extract()
                val todoRecord = response.`as`(TodoRecord::class.java)
                todoIdCreated = todoRecord.id?:0
                Truth.assertThat(todoRecord.name).isEqualTo("TaskOne")
                Truth.assertThat(todoRecord.task).isEqualTo("My first task")
            }

            scenario("A TODO Item can be queried successfully") {
                val response = given()
                            .port(serverConfig!!.endpointsPort())
                            .request()
                            .contentType("application/json")
                        .When()
                            .pathParam("todoId", todoIdCreated)
                            .get(pathWithParam )
                        .then()
                            .statusCode(200)
                            .contentType(JSON).extract()
                val todoRecord = response.`as`(TodoRecord::class.java)
                Truth.assertThat(todoRecord.name).isEqualTo("TaskOne")
                Truth.assertThat(todoRecord.task).isEqualTo("My first task")
            }

            scenario("A TODO Item can be updated") {
                val response = given()
                        .port(serverConfig!!.endpointsPort())
                        .request().contentType("application/json")
                    .When().pathParam("todoId", todoIdCreated)
                        .body(putPayload(todoIdCreated))
                        .put(pathWithParam )
                    .then().statusCode(200).contentType(JSON).extract()
                val todoRecord = response.`as`(TodoRecord::class.java)
                Truth.assertThat(todoRecord.name).isEqualTo("TaskTwo")
                Truth.assertThat(todoRecord.task).isEqualTo("My New task")
            }

            scenario("A TODO Item can be deleted") {
                given()
                        .port(serverConfig!!.endpointsPort())
                        .request().contentType("application/json")
                .When()
                        .pathParam("todoId", todoIdCreated)
                        .delete(pathWithParam )
                .then()
                        .statusCode(204)
                        .contentType(JSON)
            }
        }
    }

    companion object {
        data class TodoRecord(val id: Long?, val name: String, val task: String)
        val path = "/todos"
        val pathWithParam = "/todos/{todoId}"
        val postPayload = "{ \"name\": \"TaskOne\", \"task\" : \"My first task\" }"
        val putPayload : (Long) -> String = { "{\"id\" : $it,  \"name\": \"TaskTwo\", \"task\" : \"My New task\" }"}
        private var server: Server? = null
        private var serverConfig: AppServerConfigForTesting? = null
        var todoIdCreated: Long = 0

        object TodoEndpointConfig : ProjectConfig() {
            private var started: Long = 0
            override fun beforeAll() {
                started = System.currentTimeMillis()
                println(" Starting Riposte !!!")
                setup()
            }
            override fun afterAll() {
                val time = System.currentTimeMillis() - started
                println("overall time [ms]: " + time)
                teardown()
            }
        }

        @Throws(IOException::class)
        private fun findFreePort(): Int {
            ServerSocket(0).use { serverSocket -> return serverSocket.localPort }
        }

        @BeforeClass
        @Throws(Exception::class)
        fun setup() {
            serverConfig = AppServerConfigForTesting(findFreePort())
            server = Server(serverConfig)
            server!!.startup()
        }

        @AfterClass
        @Throws(Exception::class)
        fun teardown() {
            server!!.shutdown()
        }

    }
    //https://github.com/rest-assured/rest-assured/wiki/Usage#kotlin
    fun RequestSpecification.When(): RequestSpecification {
        return this.`when`()
    }

}
